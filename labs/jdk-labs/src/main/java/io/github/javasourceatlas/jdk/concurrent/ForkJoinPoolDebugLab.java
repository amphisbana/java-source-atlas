package io.github.javasourceatlas.jdk.concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用可控分治任务观察 ForkJoinPool 窃取、join、异常和 ManagedBlocker 协议。
 */
public final class ForkJoinPoolDebugLab {

    private static final long WAIT_SECONDS = 5;

    /**
     * 工具类不需要创建实例。
     */
    private ForkJoinPoolDebugLab() {
    }

    /**
     * 按固定顺序运行全部 ForkJoinPool 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 等待任务结果或线程池终止时失败
     */
    public static void main(String[] args) throws Exception {
        printHeader("递归拆分、窃取与 join");
        observeRecursiveSplitAndJoin();

        printHeader("本地 LIFO 与 asyncMode FIFO");
        observeLocalSchedulingModes();

        printHeader("join 与 get 的异常报告");
        observeExceptionReporting();

        printHeader("ManagedBlocker 补偿观察");
        observeManagedBlocking();

        printHeader("外部提交与 commonPool 边界");
        observeExternalSubmission();
    }

    /**
     * 计算一到十六的和，并输出递归叶子与合并事件供断点对照。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    static void observeRecursiveSplitAndJoin() throws InterruptedException {
        ForkJoinPool pool = new ForkJoinPool(2);
        ConcurrentLinkedQueue<String> trace = new ConcurrentLinkedQueue<>();
        try {
            long result = pool.invoke(new RangeSumTask(1, 16, 4, trace));
            System.out.printf("求和结果=%d，观察到的 steal 估计=%d%n", result, pool.getStealCount());
            for (String event : trace) {
                System.out.println("  " + event);
            }
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 用单 worker 对比默认 LIFO 与 asyncMode FIFO 的本地事件任务顺序。
     *
     * @throws Exception 等待事件任务或线程池终止时失败
     */
    static void observeLocalSchedulingModes() throws Exception {
        List<String> lifoOrder = runEventMode(false);
        List<String> fifoOrder = runEventMode(true);
        System.out.printf("默认模式观察顺序=%s，asyncMode 观察顺序=%s%n", lifoOrder, fifoOrder);
    }

    /**
     * 在指定本地调度模式下依次 fork 三个不 join 的事件任务。
     *
     * @param asyncMode 是否启用本地 FIFO 模式
     * @return 本次运行观察到的标签顺序
     * @throws Exception 等待根任务、子任务或线程池终止时失败
     */
    private static List<String> runEventMode(boolean asyncMode) throws Exception {
        ForkJoinPool pool = new ForkJoinPool(
                1,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null,
                asyncMode);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch eventsDone = new CountDownLatch(3);
        try {
            ForkJoinTask<?> root = pool.submit(() -> {
                forkEvent("A", executionOrder, eventsDone);
                forkEvent("B", executionOrder, eventsDone);
                forkEvent("C", executionOrder, eventsDone);
            });
            root.get(WAIT_SECONDS, TimeUnit.SECONDS);
            require(eventsDone.await(WAIT_SECONDS, TimeUnit.SECONDS), "事件任务未按时执行完成");
            synchronized (executionOrder) {
                return new ArrayList<>(executionOrder);
            }
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * fork 一个记录标签的事件任务，任务结束时保证释放完成闩锁。
     *
     * @param label 事件标签
     * @param executionOrder 线程安全的执行顺序列表
     * @param eventsDone 全部事件完成闩锁
     */
    private static void forkEvent(
            String label,
            List<String> executionOrder,
            CountDownLatch eventsDone) {
        ForkJoinTask.adapt(() -> {
            try {
                executionOrder.add(label);
            } finally {
                eventsDone.countDown();
            }
        }).fork();
    }

    /**
     * 用两个独立失败任务对比 join 的 unchecked 异常和 get 的 ExecutionException。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    static void observeExceptionReporting() throws InterruptedException {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            FailingTask joinedTask = new FailingTask("join failure");
            pool.execute(joinedTask);
            try {
                joinedTask.join();
                throw new AssertionError("失败任务不应正常 join");
            } catch (IllegalStateException exception) {
                System.out.printf("join 报告类型=%s，原因链=%s%n",
                        exception.getClass().getSimpleName(), exception.getCause());
            }

            FailingTask gottenTask = new FailingTask("get failure");
            pool.execute(gottenTask);
            try {
                gottenTask.get(WAIT_SECONDS, TimeUnit.SECONDS);
                throw new IllegalStateException("失败任务不应正常 get");
            } catch (ExecutionException exception) {
                System.out.printf("get 外层=%s，计算原因=%s%n",
                        exception.getClass().getSimpleName(),
                        exception.getCause().getClass().getSimpleName());
            } catch (java.util.concurrent.TimeoutException exception) {
                throw new IllegalStateException("失败任务未按时完成", exception);
            }
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 在 parallelism 为一的 pool 中阻塞 worker，有限观察 follower 是否获得补偿执行机会。
     *
     * @throws Exception 等待 blocker、任务结果或线程池终止时失败
     */
    static void observeManagedBlocking() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        CountDownLatch blockEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch followerRan = new CountDownLatch(1);
        LatchBlocker blocker = new LatchBlocker(blockEntered, releaseBlocker);
        ForkJoinTask<String> blockedTask = null;
        ForkJoinTask<String> followerTask = null;
        try {
            blockedTask = pool.submit(() -> {
                try {
                    ForkJoinPool.managedBlock(blocker);
                    return "blocker released";
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("ManagedBlocker 被中断", exception);
                }
            });
            require(blockEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "ManagedBlocker 未按时进入 block");

            followerTask = pool.submit(() -> {
                followerRan.countDown();
                return "follower finished";
            });
            boolean followerBeforeRelease = followerRan.await(300, TimeUnit.MILLISECONDS);
            int observedPoolSize = pool.getPoolSize();

            // 有限观察结束后主动释放，不能让实验正确性依赖一定发生补偿。
            releaseBlocker.countDown();
            String blockedResult = blockedTask.get(WAIT_SECONDS, TimeUnit.SECONDS);
            String followerResult = followerTask.get(WAIT_SECONDS, TimeUnit.SECONDS);
            System.out.printf("释放前 follower 已运行=%s，观察 poolSize=%d，结果=%s/%s%n",
                    followerBeforeRelease, observedPoolSize, blockedResult, followerResult);
        } finally {
            releaseBlocker.countDown();
            if (blockedTask != null) {
                blockedTask.cancel(true);
            }
            if (followerTask != null) {
                followerTask.cancel(true);
            }
            shutdownAndAwait(pool);
        }
    }

    /**
     * 从普通线程向自建 pool 提交 Callable，并观察任务内部所属 pool。
     *
     * @throws Exception 等待任务结果或线程池终止时失败
     */
    static void observeExternalSubmission() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            ForkJoinTask<String> task = pool.submit(() -> {
                boolean inForkJoinPool = ForkJoinTask.inForkJoinPool();
                boolean currentPoolMatches = ForkJoinTask.getPool() == pool;
                return "inPool=" + inForkJoinPool + ", currentPoolMatches=" + currentPoolMatches;
            });
            String result = task.get(WAIT_SECONDS, TimeUnit.SECONDS);
            boolean commonIsSingleton = ForkJoinPool.commonPool() == ForkJoinPool.commonPool();
            System.out.printf("外部提交结果=%s，commonPool 单例=%s，common parallelism=%d%n",
                    result, commonIsSingleton, ForkJoinPool.getCommonPoolParallelism());
        } finally {
            shutdownAndAwait(pool);
        }
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
            if (!pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("ForkJoinPool 未按时终止");
            }
        }
    }

    /**
     * 检查实验前置条件，失败时抛出明确错误。
     *
     * @param condition 必须满足的条件
     * @param message 条件失败信息
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 打印场景标题，使控制台输出与断点手册保持一致。
     *
     * @param title 场景名称
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    /**
     * 递归计算闭区间整数和，并记录叶子计算与父任务合并事件。
     */
    private static final class RangeSumTask extends RecursiveTask<Long> {

        private final long startInclusive;
        private final long endInclusive;
        private final int threshold;
        private final ConcurrentLinkedQueue<String> trace;

        /**
         * 创建区间求和任务。
         *
         * @param startInclusive 起始值，包含
         * @param endInclusive 结束值，包含
         * @param threshold 直接计算阈值
         * @param trace 并发事件记录队列
         */
        private RangeSumTask(
                long startInclusive,
                long endInclusive,
                int threshold,
                ConcurrentLinkedQueue<String> trace) {
            this.startInclusive = startInclusive;
            this.endInclusive = endInclusive;
            this.threshold = threshold;
            this.trace = trace;
        }

        /**
         * 小区间直接累加，大区间 fork 右侧、同步计算左侧后 join 合并。
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
                trace.add(Thread.currentThread().getName() + " leaf["
                        + startInclusive + "," + endInclusive + "]=" + sum);
                return sum;
            }

            long middle = (startInclusive + endInclusive) >>> 1;
            RangeSumTask left = new RangeSumTask(startInclusive, middle, threshold, trace);
            RangeSumTask right = new RangeSumTask(middle + 1, endInclusive, threshold, trace);
            // 只 fork 右侧，当前 worker 保留左侧计算，给其他 worker 留出可窃取工作。
            right.fork();
            long leftResult = left.compute();
            long rightResult = right.join();
            long result = leftResult + rightResult;
            trace.add(Thread.currentThread().getName() + " merge["
                    + startInclusive + "," + endInclusive + "]=" + result);
            return result;
        }
    }

    /**
     * 每次 compute 都抛出指定 IllegalStateException 的失败任务。
     */
    private static final class FailingTask extends RecursiveTask<Integer> {

        private final String message;

        /**
         * 创建失败任务。
         *
         * @param message 异常消息
         */
        private FailingTask(String message) {
            this.message = message;
        }

        /**
         * 抛出教学异常，触发 ForkJoinTask 异常完成路径。
         *
         * @return 永远不会正常返回
         */
        @Override
        protected Integer compute() {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 用两个闩锁实现可重复查询且可中断的 ManagedBlocker。
     */
    private static final class LatchBlocker implements ForkJoinPool.ManagedBlocker {

        private final CountDownLatch blockEntered;
        private final CountDownLatch release;
        private final AtomicInteger blockCalls = new AtomicInteger();

        /**
         * 创建闩锁阻塞器。
         *
         * @param blockEntered 进入阻塞方法的通知闩锁
         * @param release 允许阻塞结束的闩锁
         */
        private LatchBlocker(CountDownLatch blockEntered, CountDownLatch release) {
            this.blockEntered = blockEntered;
            this.release = release;
        }

        /**
         * 等待释放闩锁，并在真正进入阻塞前通知实验主线程。
         *
         * @return 释放后返回 true，表示无需继续阻塞
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
         * @return 闩锁已释放时返回 true
         */
        @Override
        public boolean isReleasable() {
            return release.getCount() == 0;
        }

        /**
         * 返回 block 的实际调用次数，供实验输出使用。
         *
         * @return block 调用次数
         */
        private int blockCalls() {
            return blockCalls.get();
        }
    }
}
