package io.github.javasourceatlas.jdk.concurrent;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用受控闩锁观察 ScheduledThreadPoolExecutor 包装、周期、异常、取消和关闭分支。
 */
public final class ScheduledThreadPoolExecutorDebugLab {

    /**
     * 工具类不需要创建实例。
     */
    private ScheduledThreadPoolExecutorDebugLab() {
    }

    /**
     * 按固定顺序运行全部定时线程池调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 等待任务、Future 或线程池终止时失败
     */
    public static void main(String[] args) throws Exception {
        printHeader("execute 的零延迟包装");
        observeZeroDelayWrapping();

        printHeader("固定频率任务不并发重入");
        observeFixedRateWithoutOverlap();

        printHeader("周期任务异常后停止");
        observePeriodicException();

        printHeader("removeOnCancel 清理策略");
        observeRemoveOnCancel();

        printHeader("shutdown 默认策略");
        observeShutdownPolicies();

        printHeader("运行中周期任务的 shutdown 版本差异");
        observeRunningPeriodicShutdownDifference();

        printHeader("shutdownNow 返回 Future 的状态");
        observeShutdownNowFutureState();
    }

    /**
     * 占住唯一 worker 后提交普通命令，观察队列中的零延迟定时包装对象。
     *
     * @throws InterruptedException 等待工作线程或线程池终止时被中断
     */
    static void observeZeroDelayWrapping() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        Runnable originalCommand = () -> System.out.println("零延迟命令已执行");

        try {
            executor.execute(() -> {
                blockerStarted.countDown();
                awaitGate(releaseBlocker);
            });
            requireAwait(blockerStarted, "占位任务未按时启动");

            executor.execute(originalCommand);
            Runnable queued = executor.getQueue().peek();
            System.out.printf("队列类型=%s，是定时 Future=%s，与原命令相同=%s%n",
                    queued == null ? "null" : queued.getClass().getName(),
                    queued instanceof RunnableScheduledFuture,
                    queued == originalCommand);
        } finally {
            releaseBlocker.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 用两个 worker 和受控长任务确认同一固定频率任务不会并发重入。
     *
     * @throws InterruptedException 等待周期任务或线程池终止时被中断
     */
    static void observeFixedRateWithoutOverlap() throws InterruptedException {
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
            }, 0, 10, TimeUnit.MILLISECONDS);

            requireAwait(firstRunStarted, "固定频率第一轮未按时启动");
            // 周期已经过期多次时，第二个 worker 仍不能并发进入同一任务。
            boolean overlapped = secondRunStarted.await(150, TimeUnit.MILLISECONDS);
            releaseFirstRun.countDown();
            requireAwait(secondRunStarted, "释放第一轮后第二轮未按时启动");

            System.out.printf("第一轮阻塞期间出现第二轮=%s，最大并发数=%d，已运行轮数=%d%n",
                    overlapped, maxActiveCount.get(), runCount.get());
        } finally {
            releaseFirstRun.countDown();
            if (future != null) {
                future.cancel(true);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 让周期任务第一轮抛出异常，观察 Future 保存原因并停止重新入队。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    static void observePeriodicException() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        AtomicInteger runCount = new AtomicInteger();
        try {
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                runCount.incrementAndGet();
                throw new IllegalStateException("演示周期失败");
            }, 0, 1, TimeUnit.MILLISECONDS);

            try {
                future.get(5, TimeUnit.SECONDS);
                throw new IllegalStateException("异常周期任务不应正常完成");
            } catch (ExecutionException exception) {
                System.out.printf("运行轮数=%d，Future 已完成=%s，异常原因=%s%n",
                        runCount.get(), future.isDone(), exception.getCause().getMessage());
            } catch (java.util.concurrent.TimeoutException exception) {
                throw new IllegalStateException("异常 Future 未按时完成", exception);
            }
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 对比默认取消保留与 removeOnCancel 开启后的立即移除行为。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    static void observeRemoveOnCancel() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            ScheduledFuture<?> retained = executor.schedule(() -> { }, 1, TimeUnit.DAYS);
            retained.cancel(false);
            boolean retainedByDefault = executor.getQueue().contains(retained);

            executor.purge();
            executor.setRemoveOnCancelPolicy(true);
            ScheduledFuture<?> removed = executor.schedule(() -> { }, 1, TimeUnit.DAYS);
            removed.cancel(false);
            boolean removedImmediately = !executor.getQueue().contains(removed);

            System.out.printf("默认取消后仍在队列=%s，开启策略后立即移除=%s%n",
                    retainedByDefault, removedImmediately);
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 在 worker 被占用时关闭线程池，观察默认保留单次任务并取消周期任务。
     *
     * @throws Exception 等待任务结果或线程池终止时失败
     */
    static void observeShutdownPolicies() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger delayedRuns = new AtomicInteger();
        AtomicInteger periodicRuns = new AtomicInteger();
        ScheduledFuture<?> delayed = null;
        ScheduledFuture<?> periodic = null;
        try {
            executor.execute(() -> {
                blockerStarted.countDown();
                awaitGate(releaseBlocker);
            });
            requireAwait(blockerStarted, "关闭实验的占位任务未按时启动");

            delayed = executor.schedule(delayedRuns::incrementAndGet, 0, TimeUnit.NANOSECONDS);
            periodic = executor.scheduleAtFixedRate(
                    periodicRuns::incrementAndGet, 0, 1, TimeUnit.DAYS);
            executor.shutdown();

            System.out.printf("shutdown 后：单次已取消=%s，周期已取消=%s%n",
                    delayed.isCancelled(), periodic.isCancelled());
            releaseBlocker.countDown();
            delayed.get(5, TimeUnit.SECONDS);
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("线程池未按时完成默认关闭流程");
            }
            System.out.printf("单次执行次数=%d，周期执行次数=%d%n",
                    delayedRuns.get(), periodicRuns.get());
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
     * 在周期任务第一轮运行时调用 shutdown，观察 JDK 8 与 JDK 17+ 的 Future 终态差异。
     *
     * @throws Exception 等待任务、Future 或线程池终止时失败
     */
    static void observeRunningPeriodicShutdownDifference() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        ScheduledFuture<?> periodic = null;
        try {
            periodic = executor.scheduleAtFixedRate(() -> {
                firstRunStarted.countDown();
                awaitGate(releaseFirstRun);
            }, 0, 1, TimeUnit.DAYS);
            requireAwait(firstRunStarted, "运行中关闭实验的周期任务未按时开始");

            executor.shutdown();
            releaseFirstRun.countDown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("运行中关闭实验的线程池未按时终止");
            }

            String getResult;
            try {
                periodic.get(100, TimeUnit.MILLISECONDS);
                getResult = "意外正常返回";
            } catch (CancellationException exception) {
                getResult = "CancellationException";
            } catch (java.util.concurrent.TimeoutException exception) {
                getResult = "TimeoutException";
            }
            System.out.printf("Java=%s，isDone=%s，isCancelled=%s，get=%s%n",
                    System.getProperty("java.specification.version"),
                    periodic.isDone(), periodic.isCancelled(), getResult);
        } finally {
            releaseFirstRun.countDown();
            if (periodic != null) {
                periodic.cancel(false);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 关闭含远期任务的线程池，观察返回列表与 ScheduledFuture 状态彼此独立。
     *
     * @throws Exception 等待读取 Future 或线程池终止时失败
     */
    static void observeShutdownNowFutureState() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture<?> delayed = null;
        try {
            delayed = executor.schedule(() -> System.out.println("远期任务不应执行"),
                    1, TimeUnit.DAYS);
            List<Runnable> pending = executor.shutdownNow();

            boolean timedOut = false;
            try {
                delayed.get(100, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException exception) {
                timedOut = true;
            }
            System.out.printf("返回列表包含 Future=%s，isDone=%s，isCancelled=%s，get 超时=%s%n",
                    pending.contains(delayed), delayed.isDone(), delayed.isCancelled(), timedOut);
        } finally {
            if (delayed != null) {
                delayed.cancel(false);
            }
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 使用 CAS 更新观察到的最大并发数，避免并发覆盖较大的历史值。
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
     * 在限定时间内等待闩锁释放；超时或中断都终止当前工作任务。
     *
     * @param gate 控制任务继续执行的闩锁
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("定时线程池实验闸门未按时打开");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待定时线程池实验闸门时被中断", exception);
        }
    }

    /**
     * 在统一截止时间内等待闩锁，超时则终止当前实验。
     *
     * @param gate 待完成闩锁
     * @param message 超时错误信息
     * @throws InterruptedException 等待时被中断
     */
    private static void requireAwait(CountDownLatch gate, String message) throws InterruptedException {
        if (!gate.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 立即关闭线程池并等待 worker 退出，防止调试场景残留非守护线程。
     *
     * @param executor 待关闭线程池
     * @throws InterruptedException 等待终止时被中断
     */
    private static void shutdownNowAndAwait(ScheduledThreadPoolExecutor executor)
            throws InterruptedException {
        if (!executor.isTerminated()) {
            executor.shutdownNow();
        }
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("定时线程池未按时终止");
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
}
