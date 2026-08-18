package io.github.javasourceatlas.jdk.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用确定性线程协作观察 JMM、volatile、final 与版本化内存访问 API 的调试入口。
 */
public final class JmmVolatileDebugLab {

    private static final long WAIT_SECONDS = 5L;

    /**
     * 工具类不需要创建实例。
     */
    private JmmVolatileDebugLab() {
    }

    /**
     * 按固定顺序执行全部 JMM 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws InterruptedException 等待线程协作时被中断
     */
    public static void main(String[] args) throws InterruptedException {
        printHeader("volatile 发布普通写入");
        observeVolatilePublication();

        printHeader("volatile 复合更新仍会丢失");
        observeControlledLostUpdate();

        printHeader("Thread.start 与 join 的可见性边界");
        observeStartAndJoinBoundaries();

        printHeader("final 状态与安全发布");
        observeFinalSnapshot();

        printHeader("Unsafe 与 VarHandle 的版本边界");
        observeVarHandleAvailability();
    }

    /**
     * 先写普通 payload，再用 volatile ready 发布，验证读线程看见 ready 后也能看见之前的写入。
     *
     * @throws InterruptedException 等待读线程时被中断
     */
    static void observeVolatilePublication() throws InterruptedException {
        PublicationState state = new PublicationState();
        AtomicReference<Integer> observedPayload = new AtomicReference<>();
        CountDownLatch readerStarted = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            while (!state.ready) {
                Thread.yield();
            }
            observedPayload.set(state.payload);
        }, "jmm-volatile-reader");

        reader.start();
        awaitGate(readerStarted, "volatile 读线程未按时启动");
        state.payload = 42;
        state.ready = true;
        joinThread(reader);

        System.out.printf("reader 看见 ready=true 后 payload=%d%n", observedPayload.get());
    }

    /**
     * 强制两个线程先读取相同 volatile 旧值，再同时写回，证明单次读写可见不等于 read-modify-write 原子。
     *
     * @throws InterruptedException 等待两个更新线程时被中断
     */
    static void observeControlledLostUpdate() throws InterruptedException {
        VolatileCounter counter = new VolatileCounter();
        CountDownLatch snapshotsRead = new CountDownLatch(2);
        CountDownLatch writeBack = new CountDownLatch(1);
        Thread first = controlledIncrementThread("jmm-counter-1", counter, snapshotsRead, writeBack);
        Thread second = controlledIncrementThread("jmm-counter-2", counter, snapshotsRead, writeBack);

        first.start();
        second.start();
        awaitGate(snapshotsRead, "两个线程未按时读取 volatile 旧值");
        writeBack.countDown();
        joinThread(first);
        joinThread(second);

        System.out.printf("逻辑执行两次 counter++，受控最终值=%d%n", counter.value);
    }

    /**
     * 在 start 前写配置、在线程内写结果，并在 join 后读取，展示两条 Thread happens-before 边。
     *
     * @throws InterruptedException 等待工作线程时被中断
     */
    static void observeStartAndJoinBoundaries() throws InterruptedException {
        int[] configuration = {7};
        int[] result = {0};
        Thread worker = new Thread(() -> result[0] = configuration[0] * 6, "jmm-start-join-worker");

        worker.start();
        worker.join();

        System.out.printf("start 前配置=%d，join 后结果=%d%n", configuration[0], result[0]);
    }

    /**
     * 通过 start 安全发布构造完成的不可变对象，观察 final 字段和其指向状态。
     *
     * @throws InterruptedException 等待读线程时被中断
     */
    static void observeFinalSnapshot() throws InterruptedException {
        ImmutableSnapshot snapshot = new ImmutableSnapshot("atlas", 42);
        AtomicReference<String> observed = new AtomicReference<>();
        Thread reader = new Thread(
                () -> observed.set(snapshot.name + ':' + snapshot.value),
                "jmm-final-reader");

        reader.start();
        reader.join();

        System.out.printf("安全发布后的不可变快照=%s%n", observed.get());
    }

    /**
     * 根据运行时是否存在 java.lang.invoke.VarHandle，说明 JDK 8 与 JDK 9+ 的 API 边界。
     */
    static void observeVarHandleAvailability() {
        boolean available;
        try {
            Class.forName("java.lang.invoke.VarHandle");
            available = true;
        } catch (ClassNotFoundException ignored) {
            available = false;
        }

        System.out.printf("Java 规范版本=%s，VarHandle 可用=%s%n",
                System.getProperty("java.specification.version"), available);
    }

    /**
     * 创建一个在受控闸门两侧执行 volatile 读取和写回的线程。
     *
     * @param name          线程名称
     * @param counter       共享 volatile 计数器
     * @param snapshotsRead 已完成读取的线程计数
     * @param writeBack     允许写回的闸门
     * @return 尚未启动的更新线程
     */
    private static Thread controlledIncrementThread(
            String name,
            VolatileCounter counter,
            CountDownLatch snapshotsRead,
            CountDownLatch writeBack) {
        return new Thread(() -> {
            int snapshot = counter.value;
            snapshotsRead.countDown();
            awaitGate(writeBack, "volatile 更新线程未获准写回");
            counter.value = snapshot + 1;
        }, name);
    }

    /**
     * 在限定时间内等待闸门，避免实验因线程协作异常永久挂起。
     *
     * @param gate         需要等待的闸门
     * @param timeoutError 超时错误信息
     */
    private static void awaitGate(CountDownLatch gate, String timeoutError) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(timeoutError);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 JMM 实验闸门时被中断", exception);
        }
    }

    /**
     * 有界等待线程结束，超时后中断并报告失败。
     *
     * @param thread 需要回收的线程
     * @throws InterruptedException 等待线程时被中断
     */
    private static void joinThread(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        if (thread.isAlive()) {
            throw new IllegalStateException("JMM 调试线程未在预期时间内结束");
        }
    }

    /**
     * 输出实验分段标题。
     *
     * @param title 分段标题
     */
    private static void printHeader(String title) {
        System.out.printf("%n=== %s ===%n", title);
    }

    /**
     * 普通 payload 由 volatile ready 建立发布边界。
     */
    private static final class PublicationState {
        private int payload;
        private volatile boolean ready;
    }

    /**
     * 单次读写可见但复合更新不原子的 volatile 计数器。
     */
    private static final class VolatileCounter {
        private volatile int value;
    }

    /**
     * 构造完成后不再变化的快照对象。
     */
    private static final class ImmutableSnapshot {
        private final String name;
        private final int value;

        /**
         * 创建不可变快照。
         *
         * @param name  名称
         * @param value 数值
         */
        private ImmutableSnapshot(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }
}
