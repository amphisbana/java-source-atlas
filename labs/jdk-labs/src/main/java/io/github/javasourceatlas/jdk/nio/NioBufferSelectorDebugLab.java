package io.github.javasourceatlas.jdk.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 用公开 API 展示 ByteBuffer 状态变化和 Selector 本机非阻塞回环。
 */
public final class NioBufferSelectorDebugLab {

    private static final long WAIT_SECONDS = 5;
    private static final long SELECT_SLICE_MILLIS = 250;
    private static final int MAX_MESSAGE_BYTES = 1024;

    /**
     * 工具类不需要创建实例。
     */
    private NioBufferSelectorDebugLab() {
    }

    /**
     * 按固定顺序运行 Buffer、回环 Selector 和 wakeup 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 本机回环或线程等待失败
     */
    public static void main(String[] args) throws Exception {
        printHeader("Buffer 四指标与边界切换");
        observeBufferStateTransitions();

        printHeader("compact、heap/direct、端序与类型视图");
        observeCompactMemoryAndViews();

        printHeader("Selector 本机非阻塞 PING 回环");
        LoopbackResult result = runLoopbackExchange("PING");
        System.out.printf(
                "请求=%s，响应=%s，accept=%d，connect=%d，read=%d，write=%d%n",
                result.request,
                result.response,
                result.acceptEvents,
                result.connectCompletions,
                result.readEvents,
                result.writeEvents);
        System.out.printf(
                "selectedKeys 已删除=%d，集合为空=%s，空输出已移除 OP_WRITE=%s，资源关闭=%s%n",
                result.removedSelectedKeys,
                result.selectedKeysEmpty,
                result.writeInterestRemoved,
                result.resourcesClosed);
        for (String event : result.eventTrace) {
            System.out.println("  " + event);
        }

        printHeader("wakeup 打断阻塞选择");
        observeWakeup();
    }

    /**
     * 逐步打印 allocate、put、flip、mark/reset、compact、rewind 和 clear 后的公开状态。
     */
    static void observeBufferStateTransitions() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        printBufferState("allocate(8)", buffer);

        buffer.put((byte) 'A').put((byte) 'B').put((byte) 'C').put((byte) 'D');
        printBufferState("put(A..D)", buffer);

        buffer.flip();
        printBufferState("flip()", buffer);

        byte first = buffer.get();
        buffer.mark();
        printBufferState("get(A); mark()，返回=" + (char) first, buffer);

        byte second = buffer.get();
        buffer.reset();
        printBufferState("get(B); reset()，返回=" + (char) second, buffer);

        buffer.get();
        buffer.compact();
        printBufferState("get(B); compact()", buffer);

        buffer.put((byte) 'E');
        buffer.flip();
        printBufferState("put(E); flip()，可读=" + asciiRemaining(buffer), buffer);

        buffer.get();
        buffer.get();
        buffer.rewind();
        printBufferState("get(C,D); rewind()", buffer);

        buffer.clear();
        printBufferState("clear()，索引0旧字节仍为=" + (char) buffer.get(0), buffer);
    }

    /**
     * 展示 compact 的半包保留、堆/直接内存公开差异、字节序和类型视图共享。
     */
    static void observeCompactMemoryAndViews() {
        ByteBuffer partial = ByteBuffer.allocate(8);
        partial.put(new byte[]{'A', 'B', 'C', 'D'}).flip();
        partial.get();
        partial.get();
        partial.compact();
        partial.put((byte) 'E').flip();
        System.out.println("compact 后组合结果=" + asciiRemaining(partial));

        ByteBuffer heap = ByteBuffer.allocate(8);
        ByteBuffer direct = ByteBuffer.allocateDirect(8);
        System.out.printf("heap: hasArray=%s, direct=%s%n", heap.hasArray(), heap.isDirect());
        System.out.printf("direct: hasArray=%s, direct=%s%n", direct.hasArray(), direct.isDirect());

        ByteBuffer bigEndian = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer littleEndian = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bigEndian.putInt(0x01020304);
        littleEndian.putInt(0x01020304);
        System.out.println("BIG_ENDIAN  = " + hexCapacity(bigEndian));
        System.out.println("LITTLE_ENDIAN = " + hexCapacity(littleEndian));

        ByteBuffer sharedBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        IntBuffer integers = sharedBytes.asIntBuffer();
        integers.put(0, 0x01020304);
        integers.position(1);
        System.out.printf(
                "IntBuffer 写入后字节=%s，bytePosition=%d，intPosition=%d%n",
                hexCapacity(sharedBytes), sharedBytes.position(), integers.position());
    }

    /**
     * 在一个 Selector 中完成本机客户端和服务端的非阻塞 PING 回环。
     *
     * @param request 需要回显的 ASCII 请求，长度必须在教学上限内
     * @return 包含事件计数、轨迹和资源关闭状态的结果
     * @throws Exception 打开回环、选择或读写失败
     */
    static LoopbackResult runLoopbackExchange(String request) throws Exception {
        byte[] requestBytes = request.getBytes(StandardCharsets.US_ASCII);
        if (requestBytes.length == 0 || requestBytes.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("请求长度必须在 1 到 " + MAX_MESSAGE_BYTES + " 字节之间");
        }

        LoopbackContext context = new LoopbackContext(requestBytes.length);
        Selector selector = null;
        ServerSocketChannel server = null;
        SocketChannel client = null;
        try {
            selector = Selector.open();

            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT, ConnectionState.server());

            InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();
            client = SocketChannel.open();
            client.configureBlocking(false);
            boolean connectedImmediately = client.connect(address);
            ConnectionState clientState = ConnectionState.client(requestBytes);
            if (connectedImmediately) {
                client.register(selector, SelectionKey.OP_WRITE, clientState);
                context.connectCompletions++;
                context.eventTrace.add("CLIENT connect 立即完成，直接关注 WRITE");
            } else {
                client.register(selector, SelectionKey.OP_CONNECT, clientState);
                context.eventTrace.add("CLIENT connect 未完成，先关注 CONNECT");
            }

            runEventLoop(selector, context);
            context.selectedKeysEmpty = selector.selectedKeys().isEmpty();
        } finally {
            // accepted peer 不在局部 try-with-resources 中，必须与其余资源一并在 finally 关闭。
            closeQuietly(context.acceptedPeer);
            closeQuietly(client);
            closeQuietly(server);
            closeQuietly(selector);
            context.resourcesClosed = isClosed(context.acceptedPeer)
                    && isClosed(client)
                    && isClosed(server)
                    && (selector == null || !selector.isOpen());
        }

        return context.toResult(request);
    }

    /**
     * 在单调时钟截止时间内推进所有已选 key，直到客户端收到完整回声。
     *
     * @param selector 统一管理三个回环 Channel 的 Selector
     * @param context 事件计数、附件长度和响应状态
     * @throws Exception 选择超时、Channel 处理或线程中断失败
     */
    private static void runEventLoop(Selector selector, LoopbackContext context) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (context.response == null) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException("Selector 回环未在截止时间内完成，轨迹=" + context.eventTrace);
            }

            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            int updated = selector.select(Math.min(SELECT_SLICE_MILLIS, remainingMillis));
            if (updated == 0) {
                continue;
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectedKeys.iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                // 先移除再执行业务，避免 handler 抛异常后把旧 readyOps 留给下一轮。
                iterator.remove();
                context.removedSelectedKeys++;
                if (!key.isValid()) {
                    continue;
                }

                if (key.isAcceptable()) {
                    handleAccept(key, selector, context);
                }
                if (key.isValid() && key.isConnectable()) {
                    handleConnect(key, context);
                }
                if (key.isValid() && key.isWritable()) {
                    handleWrite(key, context);
                }
                if (key.isValid() && key.isReadable()) {
                    handleRead(key, context);
                }
            }
        }
    }

    /**
     * 接收本机客户端，配置非阻塞并把服务端 peer 注册为 OP_READ。
     *
     * @param key ServerSocketChannel 的 accept key
     * @param selector 回环 Selector
     * @param context 保存 accepted peer 和事件轨迹的上下文
     * @throws IOException accept 或注册失败
     */
    private static void handleAccept(
            SelectionKey key, Selector selector, LoopbackContext context) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel peer = server.accept();
        if (peer == null) {
            context.eventTrace.add("SERVER 收到 ACCEPT，但 accept() 返回 null");
            return;
        }

        peer.configureBlocking(false);
        peer.register(selector, SelectionKey.OP_READ, ConnectionState.peer(context.expectedBytes));
        context.acceptedPeer = peer;
        context.acceptEvents++;
        context.eventTrace.add("SERVER ACCEPT → PEER 注册 READ");
    }

    /**
     * 完成非阻塞连接，并把 Client 从 OP_CONNECT 切换为 OP_WRITE。
     *
     * @param key Client SocketChannel 的 connect key
     * @param context 事件计数和轨迹上下文
     * @throws IOException finishConnect 失败
     */
    private static void handleConnect(SelectionKey key, LoopbackContext context) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.finishConnect()) {
            key.interestOps(SelectionKey.OP_WRITE);
            context.connectCompletions++;
            context.eventTrace.add("CLIENT CONNECT → finishConnect → 关注 WRITE");
        }
    }

    /**
     * 推进附件中的待发送 Buffer，并在发送完毕后立即移除 OP_WRITE。
     *
     * @param key 当前可写的 SocketChannel key
     * @param context 事件计数和轨迹上下文
     * @throws IOException 写入失败
     */
    private static void handleWrite(SelectionKey key, LoopbackContext context) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        ByteBuffer output = state.output;
        if (output == null || !output.hasRemaining()) {
            removeWriteInterest(key, context, state.role);
            return;
        }

        int written = channel.write(output);
        if (written > 0) {
            context.writeEvents++;
            context.eventTrace.add(state.role + " WRITE → " + written + " 字节");
        }

        if (!output.hasRemaining()) {
            state.output = null;
            removeWriteInterest(key, context, state.role);
        }
    }

    /**
     * 推进附件输入 Buffer；Peer 收齐请求后准备回声，Client 收齐后结束实验。
     *
     * @param key 当前可读的 SocketChannel key
     * @param context 事件计数、响应和轨迹上下文
     * @throws IOException 读取失败或对端提前关闭
     */
    private static void handleRead(SelectionKey key, LoopbackContext context) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        int read = channel.read(state.input);
        if (read < 0) {
            throw new IOException(state.role + " 在完整消息到达前收到 EOF");
        }
        if (read == 0) {
            return;
        }

        context.readEvents++;
        context.eventTrace.add(state.role + " READ → " + read + " 字节");
        if (state.input.position() < state.expectedBytes) {
            return;
        }

        state.input.flip();
        byte[] message = new byte[state.input.remaining()];
        state.input.get(message);
        if (state.role == ConnectionRole.PEER) {
            state.output = ByteBuffer.wrap(message);
            key.interestOps(SelectionKey.OP_WRITE);
            context.eventTrace.add("PEER 收齐请求 → flip → 关注 WRITE");
        } else if (state.role == ConnectionRole.CLIENT) {
            context.response = new String(message, StandardCharsets.US_ASCII);
            key.interestOps(0);
            context.eventTrace.add("CLIENT 收齐响应=" + context.response);
        }
    }

    /**
     * 从 key 的兴趣集合移除 OP_WRITE，并保留该连接的 OP_READ。
     *
     * @param key 需要更新的连接 key
     * @param context 记录空输出移除次数的上下文
     * @param role 连接角色，用于事件轨迹
     */
    private static void removeWriteInterest(
            SelectionKey key, LoopbackContext context, ConnectionRole role) {
        key.interestOps((key.interestOps() & ~SelectionKey.OP_WRITE) | SelectionKey.OP_READ);
        context.writeInterestRemovals++;
        context.eventTrace.add(role + " 输出清空 → 移除 WRITE，保留 READ");
    }

    /**
     * 运行一次跨线程 wakeup，确认带长超时的选择可以在短时间内返回。
     *
     * @throws Exception Selector、线程提交或等待失败
     */
    static void observeWakeup() throws Exception {
        long elapsedMillis = measureWakeupLatencyMillis();
        System.out.println("wakeup 后 select 返回耗时=" + elapsedMillis + "ms");
    }

    /**
     * 测量 wakeup 使工作线程 select 返回所需的时间，不使用 sleep 制造时序。
     *
     * @return 从工作线程准备选择到 select 返回的毫秒数
     * @throws Exception Selector 或有界线程等待失败
     */
    static long measureWakeupLatencyMillis() throws Exception {
        Selector selector = Selector.open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch enteringSelect = new CountDownLatch(1);
        try {
            Future<Long> selection = executor.submit(new Callable<Long>() {
                /**
                 * 在工作线程进入带长超时的 select，并返回实际耗时。
                 *
                 * @return select 调用耗时毫秒数
                 * @throws IOException 选择失败
                 */
                @Override
                public Long call() throws IOException {
                    long started = System.nanoTime();
                    enteringSelect.countDown();
                    selector.select(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                }
            });

            if (!enteringSelect.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("选择线程未按时准备完成");
            }
            selector.wakeup();
            return selection.get(2, TimeUnit.SECONDS);
        } finally {
            closeQuietly(selector);
            shutdownExecutor(executor);
        }
    }

    /**
     * 打印 Buffer 的公开状态和当前 remaining，便于与页面动画逐帧核对。
     *
     * @param label 当前操作说明
     * @param buffer 需要观察的 Buffer
     */
    private static void printBufferState(String label, ByteBuffer buffer) {
        System.out.printf(
                "%-36s position=%d, limit=%d, capacity=%d, remaining=%d%n",
                label,
                buffer.position(),
                buffer.limit(),
                buffer.capacity(),
                buffer.remaining());
    }

    /**
     * 通过 duplicate 读取剩余 ASCII 内容，不改变原 Buffer 的 position。
     *
     * @param source 当前处于可读状态的 Buffer
     * @return 剩余字节对应的 ASCII 字符串
     */
    private static String asciiRemaining(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /**
     * 以十六进制输出索引 0 到 capacity 的底层字节，不改变原 Buffer 状态。
     *
     * @param source 需要检查存储布局的 Buffer
     * @return 使用空格分隔的十六进制字节
     */
    private static String hexCapacity(ByteBuffer source) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < source.capacity(); index++) {
            if (index > 0) {
                output.append(' ');
            }
            output.append(String.format("%02X", source.get(index) & 0xFF));
        }
        return output.toString();
    }

    /**
     * 判断可关闭资源是否已经关闭；null 视为无需关闭。
     *
     * @param channel 需要检查的 SocketChannel
     * @return 资源为空或已经关闭时返回 true
     */
    private static boolean isClosed(SocketChannel channel) {
        return channel == null || !channel.isOpen();
    }

    /**
     * 判断可关闭资源是否已经关闭；null 视为无需关闭。
     *
     * @param channel 需要检查的 ServerSocketChannel
     * @return 资源为空或已经关闭时返回 true
     */
    private static boolean isClosed(ServerSocketChannel channel) {
        return channel == null || !channel.isOpen();
    }

    /**
     * 安静关闭实验资源；原始业务异常优先，不让清理异常覆盖它。
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
            // 教学实验在 finally 中尽力关闭，主流程异常保留为首要诊断信息。
        }
    }

    /**
     * 立即关闭实验线程池，并在短截止时间内等待工作线程退出。
     *
     * @param executor 需要关闭的单线程执行器
     * @throws InterruptedException 等待线程池退出时被中断
     */
    private static void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Selector wakeup 实验线程未按时退出");
        }
    }

    /**
     * 打印场景标题，使控制台输出与文档步骤保持一致。
     *
     * @param title 场景名称
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    /**
     * 区分 Selector 附件对应的监听端、发起端和服务端连接。
     */
    private enum ConnectionRole {
        SERVER,
        CLIENT,
        PEER
    }

    /**
     * 保存一个 SelectionKey 跨多轮 read/write 所需的 Buffer 和协议长度。
     */
    private static final class ConnectionState {
        private final ConnectionRole role;
        private final int expectedBytes;
        private final ByteBuffer input;
        private ByteBuffer output;

        /**
         * 创建连接状态；监听端不需要输入输出 Buffer。
         *
         * @param role 连接角色
         * @param expectedBytes 本轮消息预期长度
         * @param input 累计读取进度的 Buffer
         * @param output 累计写出进度的 Buffer
         */
        private ConnectionState(
                ConnectionRole role, int expectedBytes, ByteBuffer input, ByteBuffer output) {
            this.role = role;
            this.expectedBytes = expectedBytes;
            this.input = input;
            this.output = output;
        }

        /**
         * 创建监听 Channel 的无数据附件。
         *
         * @return SERVER 角色附件
         */
        private static ConnectionState server() {
            return new ConnectionState(ConnectionRole.SERVER, 0, null, null);
        }

        /**
         * 创建发起端附件，初始携带待发送请求和响应输入空间。
         *
         * @param requestBytes 待发送请求
         * @return CLIENT 角色附件
         */
        private static ConnectionState client(byte[] requestBytes) {
            return new ConnectionState(
                    ConnectionRole.CLIENT,
                    requestBytes.length,
                    ByteBuffer.allocate(requestBytes.length),
                    ByteBuffer.wrap(requestBytes));
        }

        /**
         * 创建服务端 peer 附件，先只关注读取完整请求。
         *
         * @param expectedBytes 请求预期字节数
         * @return PEER 角色附件
         */
        private static ConnectionState peer(int expectedBytes) {
            return new ConnectionState(
                    ConnectionRole.PEER,
                    expectedBytes,
                    ByteBuffer.allocate(expectedBytes),
                    null);
        }
    }

    /**
     * 聚合一次回环运行中的可变状态，避免把平台事件顺序写成固定流程。
     */
    private static final class LoopbackContext {
        private final int expectedBytes;
        private final List<String> eventTrace = new ArrayList<>();
        private SocketChannel acceptedPeer;
        private String response;
        private int acceptEvents;
        private int connectCompletions;
        private int readEvents;
        private int writeEvents;
        private int removedSelectedKeys;
        private int writeInterestRemovals;
        private boolean selectedKeysEmpty;
        private boolean resourcesClosed;

        /**
         * 创建指定消息长度的回环上下文。
         *
         * @param expectedBytes 请求与响应预期字节数
         */
        private LoopbackContext(int expectedBytes) {
            this.expectedBytes = expectedBytes;
        }

        /**
         * 在 finally 完成资源清理后生成只读结果快照。
         *
         * @param request 原始请求文本
         * @return 面向测试和控制台输出的结果
         */
        private LoopbackResult toResult(String request) {
            return new LoopbackResult(
                    request,
                    response,
                    acceptEvents,
                    connectCompletions,
                    readEvents,
                    writeEvents,
                    removedSelectedKeys,
                    writeInterestRemovals >= 2,
                    selectedKeysEmpty,
                    resourcesClosed,
                    eventTrace);
        }
    }

    /**
     * 暴露回环实验可稳定断言的公开结果，不暴露 provider 私有结构。
     */
    static final class LoopbackResult {
        final String request;
        final String response;
        final int acceptEvents;
        final int connectCompletions;
        final int readEvents;
        final int writeEvents;
        final int removedSelectedKeys;
        final boolean writeInterestRemoved;
        final boolean selectedKeysEmpty;
        final boolean resourcesClosed;
        final List<String> eventTrace;

        /**
         * 创建不可变回环结果并复制事件轨迹。
         *
         * @param request 请求文本
         * @param response 响应文本
         * @param acceptEvents accept 成功次数
         * @param connectCompletions connect 完成次数
         * @param readEvents 产生读取进度的次数
         * @param writeEvents 产生写入进度的次数
         * @param removedSelectedKeys 应用主动移除的 selected key 次数
         * @param writeInterestRemoved 两端空输出后是否都移除了 OP_WRITE
         * @param selectedKeysEmpty 事件循环完成后已选集合是否为空
         * @param resourcesClosed finally 是否关闭了全部资源
         * @param eventTrace 不依赖固定顺序的诊断轨迹
         */
        private LoopbackResult(
                String request,
                String response,
                int acceptEvents,
                int connectCompletions,
                int readEvents,
                int writeEvents,
                int removedSelectedKeys,
                boolean writeInterestRemoved,
                boolean selectedKeysEmpty,
                boolean resourcesClosed,
                List<String> eventTrace) {
            this.request = request;
            this.response = response;
            this.acceptEvents = acceptEvents;
            this.connectCompletions = connectCompletions;
            this.readEvents = readEvents;
            this.writeEvents = writeEvents;
            this.removedSelectedKeys = removedSelectedKeys;
            this.writeInterestRemoved = writeInterestRemoved;
            this.selectedKeysEmpty = selectedKeysEmpty;
            this.resourcesClosed = resourcesClosed;
            this.eventTrace = Collections.unmodifiableList(new ArrayList<>(eventTrace));
        }
    }
}
