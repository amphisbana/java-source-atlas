package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AtomicInteger 与 LongAdder 教学案例依赖的公开可观察行为。
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AtomicBehaviorTest {

    private static final long WAIT_SECONDS = 5;

    /**
     * 验证两个受控的非原子更新会丢失一次写入，而 AtomicInteger 保留两次更新。
     *
     * @throws Exception 等待并发任务失败或被中断
     */
    @Test
    void shouldContrastStableLostUpdateWithAtomicIncrement() throws Exception {
        int[] plainCounter = {0};
        AtomicInteger atomicCounter = new AtomicInteger();
        CountDownLatch snapshotsRead = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Runnable increment = () -> {
            // 两个线程必须先读取同一个旧值，再允许写回，确保测试不依赖调度器是否制造偶发竞态。
            int snapshot = plainCounter[0];
            snapshotsRead.countDown();
            awaitGate(snapshotsRead);
            plainCounter[0] = snapshot + 1;
            atomicCounter.incrementAndGet();
        };

        try {
            Future<?> first = executor.submit(increment);
            Future<?> second = executor.submit(increment);
            assertTrue(snapshotsRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
            first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            second.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals(1, plainCounter[0]);
            assertEquals(2, atomicCounter.get());
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 CAS 只在当前值与预期值匹配时更新。
     */
    @Test
    void shouldSucceedAndFailCompareAndSetByExpectedValue() {
        AtomicInteger state = new AtomicInteger(10);

        assertTrue(state.compareAndSet(10, 11));
        assertFalse(state.compareAndSet(10, 12));
        assertEquals(11, state.get());
    }

    /**
     * 验证竞争线程改变当前值后，updateAndGet 会重新应用更新函数。
     *
     * @throws Exception 等待更新任务失败或被中断
     */
    @Test
    void shouldRetryUpdateFunctionAfterCompetingUpdate() throws Exception {
        AtomicInteger value = new AtomicInteger();
        AtomicInteger functionCalls = new AtomicInteger();
        CountDownLatch firstFunctionCall = new CountDownLatch(1);
        CountDownLatch competingUpdateDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> updated = executor.submit(() -> value.updateAndGet(current -> {
                int call = functionCalls.incrementAndGet();
                if (call == 1) {
                    // 第一次函数计算完成但 CAS 尚未执行，此时改变值可稳定触发下一轮函数计算。
                    firstFunctionCall.countDown();
                    awaitGate(competingUpdateDone);
                }
                return current + 1;
            }));

            assertTrue(firstFunctionCall.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(value.compareAndSet(0, 1));
            competingUpdateDone.countDown();

            assertEquals(2, updated.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(2, value.get());
            assertTrue(functionCalls.get() >= 2);
        } finally {
            // 断言失败时也要释放函数和停止执行器，避免等待线程拖住测试进程。
            competingUpdateDone.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证多个线程并发累加 LongAdder 后，在写线程结束时可以读取精确总和。
     *
     * @throws Exception 等待并发任务失败或被中断
     */
    @Test
    void shouldSumLongAdderAfterConcurrentWritersFinish() throws Exception {
        int threadCount = 8;
        int incrementsPerThread = 1_000;
        LongAdder adder = new LongAdder();

        runConcurrently(threadCount, () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                adder.increment();
            }
        });

        assertEquals((long) threadCount * incrementsPerThread, adder.sum());
    }

    /**
     * 验证无并发写入时 sumThenReset 返回已完成窗口，并让后续更新进入下一窗口。
     */
    @Test
    void shouldSeparateQuiescentWindowsWithSumThenReset() {
        LongAdder adder = new LongAdder();
        adder.add(7);

        // JDK 不承诺与并发 add 重叠时的原子快照，本测试只断言静默期能够精确切分窗口。
        long firstWindow = adder.sumThenReset();
        adder.add(3);
        long secondWindow = adder.sumThenReset();

        assertEquals(7, firstWindow);
        assertEquals(3, secondWindow);
        assertEquals(0, adder.sum());
    }

    /**
     * 同时释放固定数量的线程，等待每个动作完成并传播任务异常。
     *
     * @param threadCount 并发线程数
     * @param action      每个线程执行的动作
     * @throws Exception 等待并发任务失败或被中断
     */
    private static void runConcurrently(int threadCount, Runnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    awaitGate(start);
                    action.run();
                }));
            }
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 在限定时间内等待闸门，超时或中断时终止当前测试任务。
     *
     * @param gate 需要等待的闸门
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试闸门未在预期时间内打开");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待测试闸门时被中断", exception);
        }
    }

    /**
     * 立即停止执行器并等待工作线程退出。
     *
     * @param executor 需要关闭的执行器
     * @throws InterruptedException 等待执行器终止时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                "测试执行器未在预期时间内终止");
    }
}
