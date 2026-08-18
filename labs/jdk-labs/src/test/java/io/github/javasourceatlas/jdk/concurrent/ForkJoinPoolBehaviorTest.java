package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ForkJoinPool 专题依赖的公开任务、模式、异常和 ManagedBlocker 契约。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ForkJoinPoolBehaviorTest {

    private static final long WAIT_SECONDS = 5;

    /**
     * 验证 RecursiveTask 递归拆分并 join 后得到完整区间和。
     *
     * @throws Exception 等待任务结果或线程池终止时失败
     */
    @Test
    void shouldComputeRecursiveRangeSum() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            ForkJoinTask<Long> task = pool.submit(new RangeSumTask(1, 100, 10));
            assertEquals(5050L, task.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 验证 pool 内 fork 返回任务自身，并能通过 join 取得子任务结果。
     *
     * @throws Exception 等待根任务结果或线程池终止时失败
     */
    @Test
    void shouldForkAndJoinChildInsidePool() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            ForkJoinTask<Boolean> root = pool.submit(() -> {
                ValueTask child = new ValueTask(42);
                ForkJoinTask<Integer> returned = child.fork();
                return returned == child && child.join() == 42;
            });

            assertTrue(root.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 验证普通线程通过自建 pool submit 的 Callable 在该 pool 内执行并返回结果。
     *
     * @throws Exception 等待任务结果或线程池终止时失败
     */
    @Test
    void shouldExecuteExternallySubmittedCallableInCustomPool() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            ForkJoinTask<Boolean> task = pool.submit(() ->
                    ForkJoinTask.inForkJoinPool() && ForkJoinTask.getPool() == pool);

            assertTrue(task.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 验证构造器公开的默认 LIFO 模式和 asyncMode FIFO 查询结果。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    @Test
    void shouldExposeConfiguredAsyncMode() throws InterruptedException {
        ForkJoinPool defaultMode = new ForkJoinPool(
                1,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null,
                false);
        ForkJoinPool asyncMode = new ForkJoinPool(
                1,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null,
                true);
        try {
            assertFalse(defaultMode.getAsyncMode());
            assertTrue(asyncMode.getAsyncMode());
        } finally {
            shutdownAndAwait(defaultMode);
            shutdownAndAwait(asyncMode);
        }
    }

    /**
     * 验证 join 直接报告 unchecked 异常，而 get 使用 ExecutionException 包装原因。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    @Test
    void shouldReportJoinAndGetFailuresDifferently() throws InterruptedException {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            FailingTask joinedTask = new FailingTask();
            pool.execute(joinedTask);
            ExecutionException joinedFailure = assertThrows(
                    ExecutionException.class,
                    () -> joinedTask.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(joinedFailure.getCause() instanceof IllegalStateException);
            // 先用有截止时间的 get 确认任务已经结束，再验证 join 的 unchecked 报告方式。
            assertThrows(IllegalStateException.class, joinedTask::join);

            FailingTask gottenTask = new FailingTask();
            pool.execute(gottenTask);
            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> gottenTask.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof IllegalStateException);
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 验证 ManagedBlocker 在条件不可用时进入 block，释放后结束公开循环。
     *
     * @throws Exception 等待 blocker、任务结果或线程池终止时失败
     */
    @Test
    void shouldCompleteManagedBlockerAfterRelease() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        CountDownLatch blockEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LatchBlocker blocker = new LatchBlocker(blockEntered, release);
        ForkJoinTask<Boolean> task = null;
        try {
            task = pool.submit(() -> {
                try {
                    ForkJoinPool.managedBlock(blocker);
                    return blocker.isReleasable();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("ManagedBlocker 被中断", exception);
                }
            });

            assertTrue(blockEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            release.countDown();

            assertTrue(task.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, blocker.blockCalls());
            assertTrue(blocker.isReleasable());
        } finally {
            release.countDown();
            if (task != null) {
                task.cancel(true);
            }
            shutdownAndAwait(pool);
        }
    }

    /**
     * 验证 commonPool 返回同一个进程级共享实例，但不锁定其并行度或线程配置。
     */
    @Test
    void shouldReturnSharedCommonPoolInstance() {
        assertSame(ForkJoinPool.commonPool(), ForkJoinPool.commonPool());
    }

    /**
     * 有序关闭自建 pool，超时后升级为立即关闭并再次等待。
     *
     * @param pool 待关闭的自建 ForkJoinPool
     * @throws InterruptedException 等待终止时被中断
     */
    private static void shutdownAndAwait(ForkJoinPool pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
        assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                "ForkJoinPool 测试线程池未按时终止");
    }

    /**
     * 递归计算闭区间整数和。
     */
    private static final class RangeSumTask extends RecursiveTask<Long> {

        private final long startInclusive;
        private final long endInclusive;
        private final int threshold;

        /**
         * 创建区间求和任务。
         *
         * @param startInclusive 起始值，包含
         * @param endInclusive 结束值，包含
         * @param threshold 直接计算阈值
         */
        private RangeSumTask(long startInclusive, long endInclusive, int threshold) {
            this.startInclusive = startInclusive;
            this.endInclusive = endInclusive;
            this.threshold = threshold;
        }

        /**
         * 小区间直接累加，大区间 fork 右侧并同步计算左侧后 join。
         *
         * @return 当前闭区间的和
         */
        @Override
        protected Long compute() {
            long length = endInclusive - startInclusive + 1;
            if (length <= threshold) {
                long sum = 0;
                for (long value = startInclusive; value <= endInclusive; value++) {
                    sum += value;
                }
                return sum;
            }

            long middle = (startInclusive + endInclusive) >>> 1;
            RangeSumTask left = new RangeSumTask(startInclusive, middle, threshold);
            RangeSumTask right = new RangeSumTask(middle + 1, endInclusive, threshold);
            // 保留一侧给当前 worker 直接执行，另一侧进入队列供空闲 worker 窃取。
            right.fork();
            return left.compute() + right.join();
        }
    }

    /**
     * 返回固定整数的最小 RecursiveTask。
     */
    private static final class ValueTask extends RecursiveTask<Integer> {

        private final int value;

        /**
         * 创建固定值任务。
         *
         * @param value 任务结果
         */
        private ValueTask(int value) {
            this.value = value;
        }

        /**
         * 返回构造时保存的固定值。
         *
         * @return 固定任务结果
         */
        @Override
        protected Integer compute() {
            return value;
        }
    }

    /**
     * 每次执行都抛出 IllegalStateException 的失败任务。
     */
    private static final class FailingTask extends RecursiveTask<Integer> {

        /**
         * 抛出教学异常，触发 ForkJoinTask 异常完成路径。
         *
         * @return 永远不会正常返回
         */
        @Override
        protected Integer compute() {
            throw new IllegalStateException("fork join failure");
        }
    }

    /**
     * 用闩锁实现可重复查询且可中断的 ManagedBlocker。
     */
    private static final class LatchBlocker implements ForkJoinPool.ManagedBlocker {

        private final CountDownLatch blockEntered;
        private final CountDownLatch release;
        private final AtomicInteger blockCalls = new AtomicInteger();

        /**
         * 创建闩锁阻塞器。
         *
         * @param blockEntered 进入 block 的通知闩锁
         * @param release 允许阻塞结束的闩锁
         */
        private LatchBlocker(CountDownLatch blockEntered, CountDownLatch release) {
            this.blockEntered = blockEntered;
            this.release = release;
        }

        /**
         * 通知测试线程已经进入阻塞方法，并等待释放闩锁。
         *
         * @return 释放后返回 true
         * @throws InterruptedException 等待释放时被中断
         */
        @Override
        public boolean block() throws InterruptedException {
            blockCalls.incrementAndGet();
            blockEntered.countDown();
            release.await();
            return true;
        }

        /**
         * 非阻塞检查释放条件是否已经满足。
         *
         * @return 释放闩锁计数为零时返回 true
         */
        @Override
        public boolean isReleasable() {
            return release.getCount() == 0;
        }

        /**
         * 返回 block 实际调用次数。
         *
         * @return block 调用次数
         */
        private int blockCalls() {
            return blockCalls.get();
        }
    }
}
