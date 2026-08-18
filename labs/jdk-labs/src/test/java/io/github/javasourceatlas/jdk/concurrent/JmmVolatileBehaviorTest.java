package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 JMM 专题依赖的确定性可见性、发布与原子性边界。
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class JmmVolatileBehaviorTest {

    private static final long WAIT_SECONDS = 5L;

    /**
     * 验证读线程观察到 volatile 标志后，也能观察到写线程此前的普通字段写入。
     *
     * @throws Exception 等待线程协作失败或被中断
     */
    @Test
    void shouldPublishPlainPayloadThroughVolatileFlag() throws Exception {
        PublicationState state = new PublicationState();
        AtomicInteger observed = new AtomicInteger(-1);
        CountDownLatch readerStarted = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            while (!state.ready) {
                Thread.yield();
            }
            observed.set(state.payload);
        });

        reader.start();
        assertTrue(readerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        state.payload = 42;
        state.ready = true;
        joinThread(reader);

        assertEquals(42, observed.get());
    }

    /**
     * 验证 start 前普通写入对新线程可见，线程内普通写入在 join 返回后对调用方可见。
     *
     * @throws Exception 等待线程失败或被中断
     */
    @Test
    void shouldPublishThroughStartAndJoin() throws Exception {
        int[] configuration = {7};
        int[] result = {0};
        Thread worker = new Thread(() -> result[0] = configuration[0] * 6);

        worker.start();
        worker.join();

        assertEquals(42, result[0]);
    }

    /**
     * 验证 volatile counter 的两次受控 read-modify-write 会覆盖成一次结果。
     *
     * @throws Exception 等待线程协作失败或被中断
     */
    @Test
    void shouldShowVolatileIncrementIsNotAtomic() throws Exception {
        VolatileCounter counter = new VolatileCounter();
        CountDownLatch snapshotsRead = new CountDownLatch(2);
        CountDownLatch writeBack = new CountDownLatch(1);
        Thread first = incrementThread(counter, snapshotsRead, writeBack);
        Thread second = incrementThread(counter, snapshotsRead, writeBack);

        first.start();
        second.start();
        assertTrue(snapshotsRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
        writeBack.countDown();
        joinThread(first);
        joinThread(second);

        assertEquals(1, counter.value);
    }

    /**
     * 验证构造完成的 final 状态经 start 边界安全发布后保持一致。
     *
     * @throws Exception 等待读线程失败或被中断
     */
    @Test
    void shouldReadFinalStateAfterSafePublication() throws Exception {
        ImmutableSnapshot snapshot = new ImmutableSnapshot("atlas", 42);
        AtomicInteger observed = new AtomicInteger();
        AtomicReference<String> observedName = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            observedName.set(snapshot.name);
            observed.set(snapshot.value);
        });

        reader.start();
        reader.join();

        assertEquals("atlas", observedName.get());
        assertEquals(42, observed.get());
    }

    /**
     * 验证 VarHandle 只存在于 JDK 9+，专题代码本身仍可在 JDK 8 编译运行。
     */
    @Test
    void shouldMatchVarHandleAvailabilityToRuntimeVersion() {
        int feature = javaFeatureVersion();
        boolean available;
        try {
            Class.forName("java.lang.invoke.VarHandle");
            available = true;
        } catch (ClassNotFoundException ignored) {
            available = false;
        }

        if (feature >= 9) {
            assertTrue(available);
        } else {
            assertFalse(available);
        }
    }

    /**
     * 创建一个先读取 volatile 快照、等待同行完成读取后再写回的线程。
     *
     * @param counter       共享计数器
     * @param snapshotsRead 已读取快照的线程计数
     * @param writeBack     写回闸门
     * @return 尚未启动的线程
     */
    private static Thread incrementThread(
            VolatileCounter counter,
            CountDownLatch snapshotsRead,
            CountDownLatch writeBack) {
        return new Thread(() -> {
            int snapshot = counter.value;
            snapshotsRead.countDown();
            awaitGate(writeBack);
            counter.value = snapshot + 1;
        });
    }

    /**
     * 解析 Java 规范版本，兼容 Java 8 的 1.8 格式和 Java 9+ 的整数格式。
     *
     * @return Java 主版本号
     */
    private static int javaFeatureVersion() {
        String specificationVersion = System.getProperty("java.specification.version");
        return specificationVersion.startsWith("1.")
                ? Integer.parseInt(specificationVersion.substring(2))
                : Integer.parseInt(specificationVersion);
    }

    /**
     * 在限定时间内等待闸门，失败时终止当前测试任务。
     *
     * @param gate 需要等待的闸门
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JMM 测试闸门未在预期时间内打开");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 JMM 测试闸门时被中断", exception);
        }
    }

    /**
     * 有界等待线程结束，避免失败场景拖住测试进程。
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
        assertFalse(thread.isAlive(), "JMM 测试线程未在预期时间内结束");
    }

    /**
     * 普通数据与 volatile 发布标志。
     */
    private static final class PublicationState {
        private int payload;
        private volatile boolean ready;
    }

    /**
     * 用于证明复合更新非原子的 volatile 计数器。
     */
    private static final class VolatileCounter {
        private volatile int value;
    }

    /**
     * 构造完成后状态不再变化的不可变快照。
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
