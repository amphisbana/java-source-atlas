package io.github.javasourceatlas.jdk.nio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.InvalidMarkException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
