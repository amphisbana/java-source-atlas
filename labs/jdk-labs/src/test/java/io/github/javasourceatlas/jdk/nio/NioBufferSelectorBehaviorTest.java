package io.github.javasourceatlas.jdk.nio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.InvalidMarkException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ByteBuffer 与 Selector 教学案例依赖的公开可观察行为。
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NioBufferSelectorBehaviorTest {

    /**
     * 验证相对访问、flip、mark/reset、rewind 和 clear 对公开边界的影响。
     */
    @Test
    void shouldMoveBufferBoundariesWithoutErasingContent() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        assertBufferState(buffer, 0, 8, 8);

        buffer.put((byte) 'A').put((byte) 'B');
        assertBufferState(buffer, 2, 8, 8);

        buffer.flip();
        assertBufferState(buffer, 0, 2, 8);
        assertEquals((byte) 'A', buffer.get());
        buffer.mark();
        assertEquals((byte) 'B', buffer.get());
        buffer.reset();
        assertEquals(1, buffer.position());

        buffer.rewind();
        assertBufferState(buffer, 0, 2, 8);
        assertThrows(InvalidMarkException.class, buffer::reset);

        buffer.clear();
        assertBufferState(buffer, 0, 8, 8);
        assertEquals((byte) 'A', buffer.get(0));
    }

    /**
     * 验证 compact 保序搬移未读尾部，并把 position 放在保留数据末尾。
     */
    @Test
    void shouldCompactUnreadBytesAndContinueWriting() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.put(new byte[]{'A', 'B', 'C', 'D'}).flip();
        assertEquals((byte) 'A', buffer.get());
        assertEquals((byte) 'B', buffer.get());

        buffer.compact();

        assertBufferState(buffer, 2, 8, 8);
        assertEquals((byte) 'C', buffer.get(0));
        assertEquals((byte) 'D', buffer.get(1));

        buffer.put((byte) 'E').flip();
        byte[] combined = new byte[buffer.remaining()];
        buffer.get(combined);
        assertArrayEquals(new byte[]{'C', 'D', 'E'}, combined);
    }

    /**
     * 验证 heap 与 direct Buffer 对 backing array 和直接内存的公开声明。
     */
    @Test
    void shouldExposeHeapAndDirectMemoryKinds() {
        ByteBuffer heap = ByteBuffer.allocate(8);
        ByteBuffer direct = ByteBuffer.allocateDirect(8);

        assertTrue(heap.hasArray());
        assertFalse(heap.isDirect());
        assertFalse(direct.hasArray());
        assertTrue(direct.isDirect());
    }

    /**
     * 验证明确定义的字节序布局，以及类型视图共享内容但独立维护 position。
     */
    @Test
    void shouldApplyByteOrderAndShareTypedViewContent() {
        ByteBuffer bigEndian = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer littleEndian = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bigEndian.putInt(0x01020304);
        littleEndian.putInt(0x01020304);

        assertArrayEquals(new byte[]{1, 2, 3, 4}, bigEndian.array());
        assertArrayEquals(new byte[]{4, 3, 2, 1}, littleEndian.array());

        ByteBuffer shared = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        IntBuffer integers = shared.asIntBuffer();
        integers.put(0, 0x01020304);
        integers.position(1);

        assertEquals((byte) 4, shared.get(0));
        assertEquals((byte) 3, shared.get(1));
        assertEquals((byte) 2, shared.get(2));
        assertEquals((byte) 1, shared.get(3));
        assertEquals(0, shared.position());
        assertEquals(1, integers.position());
    }

    /**
     * 验证 JDK 9 起 fluent 状态方法通过协变返回保留具体 ByteBuffer 类型。
     */
    @Test
    void shouldExposeCovariantFluentReturnFromJdk9() {
        boolean hasByteBufferFlip = java.util.Arrays.stream(ByteBuffer.class.getMethods())
                .anyMatch(method -> method.getName().equals("flip")
                        && method.getParameterCount() == 0
                        && method.getReturnType() == ByteBuffer.class
                        && !method.isBridge());

        assertEquals(javaMajorVersion() >= 9, hasByteBufferFlip);
        if (!hasByteBufferFlip) {
            try {
                assertEquals(Buffer.class, ByteBuffer.class.getMethod("flip").getReturnType());
            } catch (NoSuchMethodException exception) {
                throw new AssertionError("ByteBuffer 应继承 Buffer.flip", exception);
            }
        }
    }

    /**
     * 验证 JDK 13 起二参 slice 使用绝对索引、保持原游标并创建共享内容的独立视图。
     *
     * @throws Exception 反射调用新版 ByteBuffer API 失败
     */
    @Test
    void shouldExposeAbsoluteRangeSliceFromJdk13() throws Exception {
        if (javaMajorVersion() < 13) {
            assertThrows(NoSuchMethodException.class,
                    () -> ByteBuffer.class.getMethod("slice", int.class, int.class));
            return;
        }

        ByteBuffer original = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < original.capacity(); index++) {
            original.put(index, (byte) index);
        }
        original.position(4);
        ByteBuffer slice = (ByteBuffer) ByteBuffer.class
                .getMethod("slice", int.class, int.class)
                .invoke(original, 1, 3);

        assertBufferState(slice, 0, 3, 3);
        assertEquals(4, original.position());
        assertEquals(ByteOrder.BIG_ENDIAN, slice.order());
        assertEquals((byte) 1, slice.get(0));
        slice.put(0, (byte) 9);
        assertEquals((byte) 9, original.get(1));
    }

    /**
     * 验证阻塞 Channel 不能注册，切换非阻塞后可以注册合法兴趣位和附件。
     *
     * @throws Exception 打开或关闭本地 Channel 失败
     */
    @Test
    void shouldRequireNonBlockingModeAndValidInterestOps() throws Exception {
        Selector selector = null;
        ServerSocketChannel server = null;
        try {
            selector = Selector.open();
            server = ServerSocketChannel.open();
            Selector openedSelector = selector;
            ServerSocketChannel openedServer = server;

            assertThrows(IllegalBlockingModeException.class,
                    () -> openedServer.register(openedSelector, SelectionKey.OP_ACCEPT));

            server.configureBlocking(false);
            SelectionKey key = server.register(selector, SelectionKey.OP_ACCEPT, "server");
            assertSame(key, server.keyFor(selector));
            assertEquals(SelectionKey.OP_ACCEPT, key.interestOps());
            assertEquals("server", key.attachment());
            assertThrows(IllegalArgumentException.class,
                    () -> openedServer.register(openedSelector, SelectionKey.OP_READ));
        } finally {
            closeQuietly(server);
            closeQuietly(selector);
        }
    }

    /**
     * 验证 selectedKeys 在应用显式删除前不会被后续选择操作自动清空。
     *
     * @throws Exception 本机回环连接或选择失败
     */
    @Test
    void shouldKeepSelectedKeyUntilApplicationRemovesIt() throws Exception {
        Selector selector = null;
        ServerSocketChannel server = null;
        SocketChannel client = null;
        try {
            selector = Selector.open();
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.configureBlocking(false);
            SelectionKey serverKey = server.register(selector, SelectionKey.OP_ACCEPT);

            client = SocketChannel.open();
            client.connect((InetSocketAddress) server.getLocalAddress());

            assertTrue(selector.select(TimeUnit.SECONDS.toMillis(2)) > 0);
            Set<SelectionKey> selected = selector.selectedKeys();
            assertTrue(selected.contains(serverKey));

            selector.selectNow();
            assertTrue(selected.contains(serverKey));

            Iterator<SelectionKey> iterator = selected.iterator();
            assertSame(serverKey, iterator.next());
            iterator.remove();
            assertTrue(selected.isEmpty());
        } finally {
            closeQuietly(client);
            closeQuietly(server);
            closeQuietly(selector);
        }
    }

    /**
     * 验证 JDK 11 起 Consumer 选择直接消费本轮 key，且不把新 key 加入 selected-key set。
     *
     * @throws Exception 打开 Pipe、选择或反射调用失败
     */
    @Test
    void shouldConsumeReadyKeyWithActionFromJdk11() throws Exception {
        if (javaMajorVersion() < 11) {
            assertThrows(NoSuchMethodException.class,
                    () -> Selector.class.getMethod("select", Consumer.class, long.class));
            return;
        }

        Selector selector = null;
        Pipe.SourceChannel source = null;
        Pipe.SinkChannel sink = null;
        try {
            selector = Selector.open();
            Pipe pipe = Pipe.open();
            source = pipe.source();
            sink = pipe.sink();
            source.configureBlocking(false);
            SelectionKey registered = source.register(selector, SelectionKey.OP_READ);
            sink.write(ByteBuffer.wrap(new byte[]{1}));

            AtomicInteger actionCalls = new AtomicInteger();
            Consumer<SelectionKey> action = key -> {
                assertSame(registered, key);
                assertTrue(key.isReadable());
                actionCalls.incrementAndGet();
            };
            int selected = (Integer) Selector.class
                    .getMethod("select", Consumer.class, long.class)
                    .invoke(selector, action, TimeUnit.SECONDS.toMillis(2));

            assertEquals(1, selected);
            assertEquals(1, actionCalls.get());
            assertTrue(selector.selectedKeys().isEmpty());
        } finally {
            closeQuietly(sink);
            closeQuietly(source);
            closeQuietly(selector);
        }
    }

    /**
     * 验证 JDK 11 起 interestOpsOr/And 原子更新位集合并返回旧值。
     *
     * @throws Exception 打开 Channel、注册或反射调用失败
     */
    @Test
    void shouldUpdateInterestBitsAtomicallyFromJdk11() throws Exception {
        if (javaMajorVersion() < 11) {
            assertThrows(NoSuchMethodException.class,
                    () -> SelectionKey.class.getMethod("interestOpsOr", int.class));
            assertThrows(NoSuchMethodException.class,
                    () -> SelectionKey.class.getMethod("interestOpsAnd", int.class));
            return;
        }

        Selector selector = null;
        SocketChannel channel = null;
        try {
            selector = Selector.open();
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            SelectionKey key = channel.register(selector, SelectionKey.OP_READ);

            int beforeOr = (Integer) SelectionKey.class
                    .getMethod("interestOpsOr", int.class)
                    .invoke(key, SelectionKey.OP_WRITE);
            assertEquals(SelectionKey.OP_READ, beforeOr);
            assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());

            int beforeAnd = (Integer) SelectionKey.class
                    .getMethod("interestOpsAnd", int.class)
                    .invoke(key, ~SelectionKey.OP_READ);
            assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, beforeAnd);
            assertEquals(SelectionKey.OP_WRITE, key.interestOps());
        } finally {
            closeQuietly(channel);
            closeQuietly(selector);
        }
    }

    /**
     * 验证 Buffer 类型层级在 JDK 19 起通过 sealed 元数据封闭。
     *
     * @throws Exception 反射调用 Class.isSealed 失败
     */
    @Test
    void shouldExposeSealedBufferHierarchyFromJdk19() throws Exception {
        if (javaMajorVersion() < 17) {
            assertThrows(NoSuchMethodException.class, () -> Class.class.getMethod("isSealed"));
            return;
        }

        java.lang.reflect.Method isSealed = Class.class.getMethod("isSealed");
        assertEquals(javaMajorVersion() >= 19, isSealed.invoke(Buffer.class));
        assertEquals(javaMajorVersion() >= 19, isSealed.invoke(ByteBuffer.class));
    }

    /**
     * 验证 wakeup 在有界时间内让另一个线程的长超时 select 返回。
     *
     * @throws Exception Selector 或线程等待失败
     */
    @Test
    void shouldWakeBlockedSelectionWithoutSleeping() throws Exception {
        long elapsedMillis = NioBufferSelectorDebugLab.measureWakeupLatencyMillis();

        assertTrue(elapsedMillis < TimeUnit.SECONDS.toMillis(2),
                "wakeup 后 select 未在预期时间内返回，耗时=" + elapsedMillis + "ms");
    }

    /**
     * 验证本机回环完成连接、接收、双向读写，并遵守已选集合和 OP_WRITE 纪律。
     *
     * @throws Exception 本机非阻塞回环失败
     */
    @Test
    void shouldEchoOverLoopbackAndCleanAllSelectorState() throws Exception {
        NioBufferSelectorDebugLab.LoopbackResult result =
                NioBufferSelectorDebugLab.runLoopbackExchange("PING");

        assertEquals("PING", result.request);
        assertEquals("PING", result.response);
        assertEquals(1, result.acceptEvents);
        assertEquals(1, result.connectCompletions);
        assertTrue(result.readEvents >= 2);
        assertTrue(result.writeEvents >= 2);
        assertTrue(result.removedSelectedKeys >= 4);
        assertTrue(result.writeInterestRemoved);
        assertTrue(result.selectedKeysEmpty);
        assertTrue(result.resourcesClosed);
        assertFalse(result.eventTrace.isEmpty());
    }

    /**
     * 验证回环实验拒绝空请求和超过教学上限的请求，避免无界分配。
     */
    @Test
    void shouldRejectInvalidLoopbackMessageLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> NioBufferSelectorDebugLab.runLoopbackExchange(""));

        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index < 1025; index++) {
            oversized.append('X');
        }
        assertThrows(IllegalArgumentException.class,
                () -> NioBufferSelectorDebugLab.runLoopbackExchange(oversized.toString()));
    }

    /**
     * 统一断言 Buffer 的 position、limit 和 capacity。
     *
     * @param buffer 需要检查的 Buffer
     * @param position 预期 position
     * @param limit 预期 limit
     * @param capacity 预期 capacity
     */
    private static void assertBufferState(
            ByteBuffer buffer, int position, int limit, int capacity) {
        assertEquals(position, buffer.position());
        assertEquals(limit, buffer.limit());
        assertEquals(capacity, buffer.capacity());
        assertEquals(limit - position, buffer.remaining());
    }

    /**
     * 读取当前 Java 规范主版本，兼容 Java 8 的 1.8 格式。
     *
     * @return Java 主版本号
     */
    private static int javaMajorVersion() {
        String specificationVersion = System.getProperty("java.specification.version");
        if (specificationVersion.startsWith("1.")) {
            return Integer.parseInt(specificationVersion.substring(2));
        }
        return Integer.parseInt(specificationVersion);
    }

    /**
     * 安静关闭测试资源，避免清理异常遮盖原始断言失败。
     *
     * @param closeable 需要关闭的资源，允许为 null
     */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 测试 finally 尽力回收端口和 Selector，原始断言保留为首要失败原因。
        }
    }
}
