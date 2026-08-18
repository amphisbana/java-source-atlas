package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ScheduledThreadPoolExecutor 专题依赖的公开可观察行为。
 */
@Timeout(value = 10)
class ScheduledThreadPoolExecutorBehaviorTest {

    /**
     * 验证 execute 会把普通命令包装成零延迟 RunnableScheduledFuture。
     *
     * @throws InterruptedException 等待工作线程或线程池终止时被中断
     */
    @Test
    void shouldWrapExecuteCommandAsZeroDelayScheduledFuture() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        Runnable original = () -> { };
        try {
            executor.execute(() -> {
                blockerStarted.countDown();
                awaitGate(releaseBlocker);
            });
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

            executor.execute(original);
            Runnable queued = executor.getQueue().peek();

            assertTrue(queued instanceof RunnableScheduledFuture);
            assertNotSame(original, queued);
        } finally {
            releaseBlocker.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证周期小于单轮耗时时，同一固定频率任务仍不会并发重入。
     *
     * @throws InterruptedException 等待周期任务或线程池终止时被中断
     */
    @Test
    void shouldNotOverlapFixedRateExecutions() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        CountDownLatch secondRunStarted = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        AtomicInteger activeCount = new AtomicInteger();
        AtomicInteger maxActiveCount = new AtomicInteger();
        ScheduledFuture<?> future = null;
        try {
            future = executor.scheduleAtFixedRate(() -> {
                int active = activeCount.incrementAndGet();
                updateMaximum(maxActiveCount, active);
                int currentRun = runCount.incrementAndGet();
                try {
                    if (currentRun == 1) {
                        firstRunStarted.countDown();
                        awaitGate(releaseFirstRun);
                    } else if (currentRun == 2) {
                        secondRunStarted.countDown();
                    }
                } finally {
                    activeCount.decrementAndGet();
                }
            }, 0, 1, TimeUnit.MILLISECONDS);

            assertTrue(firstRunStarted.await(5, TimeUnit.SECONDS));
            // 等待远超 period 的时间，确认第二个 worker 也不能进入同一周期任务。
            assertFalse(secondRunStarted.await(150, TimeUnit.MILLISECONDS));
            releaseFirstRun.countDown();
            assertTrue(secondRunStarted.await(5, TimeUnit.SECONDS));

            assertEquals(1, maxActiveCount.get());
        } finally {
            releaseFirstRun.countDown();
            if (future != null) {
                future.cancel(true);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证固定延迟从上一轮完成后开始计算，而不是追赶原计划时刻。
     *
     * @throws InterruptedException 等待周期任务或线程池终止时被中断
     */
    @Test
    void shouldMeasureFixedDelayAfterPreviousCompletion() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        CountDownLatch secondRunStarted = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        AtomicLong firstCompletionNanos = new AtomicLong();
        AtomicLong secondStartNanos = new AtomicLong();
        ScheduledFuture<?> future = null;
        try {
            future = executor.scheduleWithFixedDelay(() -> {
                int currentRun = runCount.incrementAndGet();
                if (currentRun == 1) {
                    firstRunStarted.countDown();
                    awaitGate(releaseFirstRun);
                    firstCompletionNanos.set(System.nanoTime());
                } else if (currentRun == 2) {
                    secondStartNanos.set(System.nanoTime());
                    secondRunStarted.countDown();
                }
            }, 0, 100, TimeUnit.MILLISECONDS);

            assertTrue(firstRunStarted.await(5, TimeUnit.SECONDS));
            releaseFirstRun.countDown();
            assertTrue(secondRunStarted.await(5, TimeUnit.SECONDS));

            long observedDelay = secondStartNanos.get() - firstCompletionNanos.get();
            // 给计时换算留出余量，只验证不会在上一轮完成后立即追赶。
            assertTrue(observedDelay >= TimeUnit.MILLISECONDS.toNanos(70));
        } finally {
            releaseFirstRun.countDown();
            if (future != null) {
                future.cancel(true);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证周期任务抛出异常后 Future 异常完成且不会继续调度。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    @Test
    void shouldStopPeriodicExecutionAfterException() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        AtomicInteger runCount = new AtomicInteger();
        try {
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                runCount.incrementAndGet();
                throw new IllegalStateException("periodic failure");
            }, 0, 1, TimeUnit.MILLISECONDS);

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));

            assertTrue(exception.getCause() instanceof IllegalStateException);
            assertEquals(1, runCount.get());
            assertTrue(future.isDone());
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证默认取消保留任务，而 removeOnCancel 会立即从延迟堆删除。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    @Test
    void shouldRemoveCancelledTaskImmediatelyWhenPolicyEnabled() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            ScheduledFuture<?> retained = executor.schedule(() -> { }, 1, TimeUnit.DAYS);
            assertTrue(retained.cancel(false));
            assertTrue(executor.getQueue().contains(retained));

            executor.purge();
            assertFalse(executor.getQueue().contains(retained));

            executor.setRemoveOnCancelPolicy(true);
            ScheduledFuture<?> removed = executor.schedule(() -> { }, 1, TimeUnit.DAYS);
            assertTrue(removed.cancel(false));
            assertFalse(executor.getQueue().contains(removed));
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 shutdown 默认保留单次延迟任务并取消周期任务。
     *
     * @throws Exception 等待任务结果或线程池终止时失败
     */
    @Test
    void shouldApplyDefaultShutdownPolicies() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger delayedRuns = new AtomicInteger();
        ScheduledFuture<?> delayed = null;
        ScheduledFuture<?> periodic = null;
        try {
            executor.execute(() -> {
                blockerStarted.countDown();
                awaitGate(releaseBlocker);
            });
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

            delayed = executor.schedule(delayedRuns::incrementAndGet, 0, TimeUnit.NANOSECONDS);
            periodic = executor.scheduleAtFixedRate(() -> { }, 0, 1, TimeUnit.DAYS);
            executor.shutdown();

            assertFalse(delayed.isCancelled());
            assertTrue(periodic.isCancelled());

            releaseBlocker.countDown();
            delayed.get(5, TimeUnit.SECONDS);
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, delayedRuns.get());
        } finally {
            releaseBlocker.countDown();
            if (delayed != null) {
                delayed.cancel(false);
            }
            if (periodic != null) {
                periodic.cancel(false);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证显式策略可以取消既有单次任务并让周期任务在 shutdown 后保留。
     *
     * @throws InterruptedException 等待工作线程或线程池终止时被中断
     */
    @Test
    void shouldApplyConfiguredShutdownPolicies() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);
        ScheduledFuture<?> delayed = null;
        ScheduledFuture<?> periodic = null;
        try {
            executor.execute(() -> {
                blockerStarted.countDown();
                awaitGate(releaseBlocker);
            });
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

            // 使用明确未到期的任务，兼容 JDK 8 与 JDK 17/21 对已到期任务的关闭边界差异。
            delayed = executor.schedule(() -> { }, 1, TimeUnit.DAYS);
            periodic = executor.scheduleAtFixedRate(() -> { }, 1, 1, TimeUnit.DAYS);
            executor.shutdown();

            assertTrue(delayed.isCancelled());
            assertFalse(periodic.isCancelled());
            assertTrue(executor.getQueue().contains(periodic));

            // 开启继续周期任务会阻止有序关闭自然结束，实验主动取消以便收口。
            periodic.cancel(false);
        } finally {
            releaseBlocker.countDown();
            if (delayed != null) {
                delayed.cancel(false);
            }
            if (periodic != null) {
                periodic.cancel(false);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证运行中的周期任务遇到默认 shutdown 时，JDK 8 与 JDK 17+ 发布不同 Future 状态。
     *
     * @throws Exception 等待任务进入、线程池终止或读取 Future 时失败
     */
    @Test
    void shouldExposeRunningPeriodicShutdownDifferenceAcrossJdkVersions() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        ScheduledFuture<?> periodic = null;
        try {
            periodic = executor.scheduleAtFixedRate(() -> {
                firstRunStarted.countDown();
                awaitGate(releaseFirstRun);
            }, 0, 1, TimeUnit.DAYS);
            assertTrue(firstRunStarted.await(5, TimeUnit.SECONDS));

            executor.shutdown();
            releaseFirstRun.countDown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            if (isJdk8Runtime()) {
                // JDK 8 首次状态检查失败时直接返回，Future 仍停留在 NEW。
                assertFalse(periodic.isDone());
                assertFalse(periodic.isCancelled());
                ScheduledFuture<?> unfinished = periodic;
                assertThrows(TimeoutException.class,
                        () -> unfinished.get(100, TimeUnit.MILLISECONDS));
            } else {
                assertTrue(periodic.isDone());
                assertTrue(periodic.isCancelled());
                ScheduledFuture<?> cancelled = periodic;
                assertThrows(CancellationException.class, cancelled::get);
            }
        } finally {
            releaseFirstRun.countDown();
            if (periodic != null) {
                periodic.cancel(false);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 shutdownNow 只返回尚未开始的定时任务，不会自动取消对应 Future。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    @Test
    void shouldLeaveDrainedScheduledFutureNewAfterShutdownNow() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture<?> delayed = null;
        try {
            delayed = executor.schedule(() -> { }, 1, TimeUnit.DAYS);
            List<Runnable> pending = executor.shutdownNow();

            assertTrue(pending.contains(delayed));
            assertFalse(delayed.isDone());
            assertFalse(delayed.isCancelled());
            ScheduledFuture<?> unfinished = delayed;
            assertThrows(TimeoutException.class,
                    () -> unfinished.get(100, TimeUnit.MILLISECONDS));
        } finally {
            if (delayed != null) {
                delayed.cancel(false);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 使用 CAS 更新观察到的最大并发数，避免较小的新值覆盖历史最大值。
     *
     * @param maximum 当前最大值
     * @param candidate 本次候选值
     */
    private static void updateMaximum(AtomicInteger maximum, int candidate) {
        int current = maximum.get();
        while (candidate > current && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    /**
     * 在限定时间内等待闩锁释放；超时或中断都让工作任务失败。
     *
     * @param gate 控制任务继续执行的闩锁
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("定时线程池测试闸门未按时打开");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待定时线程池测试闸门时被中断", exception);
        }
    }

    /**
     * 判断当前测试是否运行在 Java 8，用于断言已确认的源码版本差异。
     *
     * @return Java 8 运行时返回 true
     */
    private static boolean isJdk8Runtime() {
        return "1.8".equals(System.getProperty("java.specification.version"));
    }

    /**
     * 立即关闭线程池并等待 worker 退出，保证测试不会残留后台线程。
     *
     * @param executor 待关闭线程池
     * @throws InterruptedException 等待终止时被中断
     */
    private static void shutdownNowAndAwait(ScheduledThreadPoolExecutor executor)
            throws InterruptedException {
        if (!executor.isTerminated()) {
            executor.shutdownNow();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
