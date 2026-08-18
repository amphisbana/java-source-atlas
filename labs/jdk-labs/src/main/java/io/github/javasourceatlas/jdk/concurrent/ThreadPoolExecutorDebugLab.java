package io.github.javasourceatlas.jdk.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 用确定性同步点复现 ThreadPoolExecutor 提交、Worker、钩子和关闭源码分支。
 */
public final class ThreadPoolExecutorDebugLab {

    /**
     * 工具类不需要创建实例。
     */
    private ThreadPoolExecutorDebugLab() {
    }

    /**
     * 按固定顺序运行全部 ThreadPoolExecutor 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws InterruptedException 等待线程池动作时被中断
     */
    public static void main(String[] args) throws InterruptedException {
        printHeader("execute 三步决策");
        observeExecuteDecision();

        printHeader("CallerRuns 背压");
        observeCallerRuns();

        printHeader("有序关闭");
        observeGracefulShutdown();

        printHeader("offer 后 shutdown 复查");
        observeOfferShutdownRecheck();

        printHeader("Worker 锁与空闲中断边界");
        observeIdleWorkerInterruptBoundary();

        printHeader("核心线程超时回收");
        observeCoreThreadTimeout();

        printHeader("execute 异常后的 Worker 替补");
        observeAbruptExitReplacement();

        printHeader("beforeExecute / afterExecute / terminated");
        observeLifecycleHooks();
    }

    /**
     * 用核心一、最大二、队列一的配置依次触发建核心线程、入队、扩线程和拒绝。
     *
     * @throws InterruptedException 等待工作线程时被中断
     */
    static void observeExecuteDecision() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch blockersStarted = new CountDownLatch(2);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        Runnable blocker = () -> {
            blockersStarted.countDown();
            awaitGate(releaseBlockers);
        };

        boolean rejected = false;
        try {
            // 四次提交依次覆盖核心线程、队列、非核心线程和拒绝四条路径。
            pool.execute(blocker);
            pool.execute(() -> System.out.println("队列任务已执行"));
            pool.execute(blocker);
            if (!blockersStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("工作线程未按时启动");
            }
            try {
                pool.execute(() -> System.out.println("不应执行的饱和任务"));
            } catch (RejectedExecutionException exception) {
                rejected = true;
            }
            System.out.printf("poolSize=%d，queueSize=%d，第四个任务被拒绝=%s%n",
                    pool.getPoolSize(), pool.getQueue().size(), rejected);
        } finally {
            releaseBlockers.countDown();
            shutdownAndAwait(pool);
        }
    }

    /**
     * 在线程池饱和时观察 CallerRunsPolicy 让提交线程执行任务。
     *
     * @throws InterruptedException 等待工作线程时被中断
     */
    static void observeCallerRuns() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy());
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<String> executionThread = new AtomicReference<>();
        try {
            pool.execute(() -> {
                workerStarted.countDown();
                awaitGate(releaseWorker);
            });
            if (!workerStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("核心工作线程未按时启动");
            }
            pool.execute(() -> System.out.println("排队任务已执行"));
            pool.execute(() -> executionThread.set(Thread.currentThread().getName()));
            System.out.printf("CallerRuns 执行线程=%s，提交线程=%s%n",
                    executionThread.get(), Thread.currentThread().getName());
        } finally {
            releaseWorker.countDown();
            shutdownAndAwait(pool);
        }
    }

    /**
     * 验证 shutdown 拒绝新任务，但继续处理已经入队的任务。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    static void observeGracefulShutdown() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        try {
            pool.execute(() -> {
                firstStarted.countDown();
                awaitGate(releaseFirst);
                completed.incrementAndGet();
            });
            if (!firstStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("首个任务未按时启动");
            }
            pool.execute(completed::incrementAndGet);
            pool.shutdown();

            // 关闭后的提交必须进入拒绝策略，但关闭前排队的任务仍应完成。
            boolean rejectedAfterShutdown = false;
            try {
                pool.execute(completed::incrementAndGet);
            } catch (RejectedExecutionException exception) {
                rejectedAfterShutdown = true;
            }
            releaseFirst.countDown();
            boolean terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
            System.out.printf("已完成=%d，关闭后拒绝=%s，已终止=%s%n",
                    completed.get(), rejectedAfterShutdown, terminated);
        } finally {
            releaseFirst.countDown();
            shutdownAndAwait(pool);
        }
    }

    /**
     * 把 shutdown 精确插入 offer 成功与 execute 复查 ctl 之间，观察移除和拒绝补偿。
     *
     * @throws InterruptedException 等待提交与关闭线程时被中断
     */
    static void observeOfferShutdownRecheck() throws InterruptedException {
        CountDownLatch offered = new CountDownLatch(1);
        CountDownLatch continueOffer = new CountDownLatch(1);
        BlockingAfterOfferQueue queue = new BlockingAfterOfferQueue(offered, continueOffer);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, 1, 30, TimeUnit.SECONDS, queue,
                (task, executor) -> rejected.incrementAndGet());
        Thread submitter = new Thread(() -> pool.execute(executed::incrementAndGet), "offer-recheck-submitter");

        try {
            submitter.start();
            if (!offered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("任务未按时完成 offer");
            }
            pool.shutdown();
            continueOffer.countDown();
            joinThread(submitter, "提交线程");
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("复查实验线程池未按时终止");
            }
            System.out.printf("执行次数=%d，拒绝次数=%d，队列为空=%s，已终止=%s%n",
                    executed.get(), rejected.get(), queue.isEmpty(), pool.isTerminated());
        } finally {
            continueOffer.countDown();
            pool.shutdownNow();
            joinThread(submitter, "提交线程");
            awaitTerminationOrThrow(pool);
        }
    }

    /**
     * 证明 shutdown 无法取得正在执行任务的 Worker 锁，因此不会中断活跃任务。
     *
     * @throws InterruptedException 等待任务和线程池终止时被中断
     */
    static void observeIdleWorkerInterruptBoundary() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        try {
            pool.execute(() -> {
                taskStarted.countDown();
                try {
                    releaseTask.await();
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            if (!taskStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("活跃任务未按时启动");
            }
            pool.shutdown();
            System.out.printf("shutdown 返回后活跃任务被中断=%s%n", interrupted.get());
            releaseTask.countDown();
            awaitTerminationOrThrow(pool);
        } finally {
            releaseTask.countDown();
            shutdownAndAwait(pool);
        }
    }

    /**
     * 开启核心线程超时并观察预启动核心线程最终收缩到零。
     *
     * @throws InterruptedException 等待线程创建和回收时被中断
     */
    static void observeCoreThreadTimeout() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 120, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            pool.allowCoreThreadTimeOut(true);
            boolean started = pool.prestartCoreThread();
            awaitCondition(() -> pool.getPoolSize() == 1, 5, TimeUnit.SECONDS, "核心线程未按时启动");
            awaitCondition(() -> pool.getPoolSize() == 0, 5, TimeUnit.SECONDS, "核心线程未按时超时回收");
            System.out.printf("预启动成功=%s，超时后 poolSize=%d%n", started, pool.getPoolSize());
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 让 execute 任务异常退出，验证 processWorkerExit 创建替补线程继续消费队列。
     *
     * @throws InterruptedException 等待两个任务和替补线程时被中断
     */
    static void observeAbruptExitReplacement() throws InterruptedException {
        AtomicInteger createdWorkers = new AtomicInteger();
        ThreadFactory factory = namedThreadFactory("replacement-worker-", createdWorkers);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), factory);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);

        try {
            pool.execute(() -> {
                firstStarted.countDown();
                awaitGate(releaseFailure);
                throw new IllegalStateException("execute-failure");
            });
            if (!firstStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("异常任务未按时启动");
            }
            pool.execute(secondCompleted::countDown);
            releaseFailure.countDown();
            if (!secondCompleted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("替补 Worker 未完成排队任务");
            }
            awaitCondition(() -> createdWorkers.get() >= 2, 5, TimeUnit.SECONDS, "未观察到替补 Worker");
            System.out.printf("已创建 Worker=%d，排队任务已完成=%s%n",
                    createdWorkers.get(), secondCompleted.getCount() == 0);
        } finally {
            releaseFailure.countDown();
            shutdownAndAwait(pool);
        }
    }

    /**
     * 用自定义线程池记录三个模板钩子，并对比 execute 与 submit 异常的可见性。
     *
     * @throws InterruptedException 等待钩子和线程池终止时被中断
     */
    static void observeLifecycleHooks() throws InterruptedException {
        HookTrackingExecutor pool = new HookTrackingExecutor();
        try {
            pool.execute(() -> {
                throw new IllegalArgumentException("direct-failure");
            });
            if (!pool.awaitDirectFailure(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("afterExecute 未观察到 execute 异常");
            }

            Future<?> failedFuture = pool.submit(() -> {
                throw new IllegalStateException("submit-failure");
            });
            try {
                failedFuture.get();
            } catch (ExecutionException expected) {
                // 主线程只确认 Future 已完成，真实原因由 afterExecute 的解包逻辑记录。
            }
            if (!pool.awaitFutureFailure(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("afterExecute 未解包 submit 异常");
            }

            pool.shutdown();
            awaitTerminationOrThrow(pool);
            System.out.printf("execute Throwable=%s，submit 原始 Throwable 为空=%s，submit 原因=%s%n",
                    simpleName(pool.directFailure()), pool.futureRawThrowableWasNull(),
                    simpleName(pool.futureFailure()));
            System.out.println("钩子顺序=" + pool.events());
        } finally {
            shutdownAndAwait(pool);
        }
    }

    /**
     * 等待闩锁释放；收到中断时恢复当前线程中断标记。
     *
     * @param gate 控制任务继续执行的闩锁
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 在截止时间内轮询公开状态，避免用固定长睡眠决定并发先后。
     *
     * @param condition 成功条件
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param message 超时错误信息
     * @throws InterruptedException 轮询等待时被中断
     */
    private static void awaitCondition(
            BooleanSupplier condition,
            long timeout,
            TimeUnit unit,
            String message) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(message);
            }
            Thread.sleep(10L);
        }
    }

    /**
     * 等待普通线程退出，并把超时转换为明确失败。
     *
     * @param thread 待等待线程
     * @param description 线程用途说明
     * @throws InterruptedException 等待时被中断
     */
    private static void joinThread(Thread thread, String description) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        if (thread.isAlive()) {
            throw new IllegalStateException(description + "未按时退出");
        }
    }

    /**
     * 有序关闭线程池，并在超时后升级为立即关闭。
     *
     * @param pool 待关闭线程池
     * @throws InterruptedException 等待终止时被中断
     */
    private static void shutdownAndAwait(ThreadPoolExecutor pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            awaitTerminationOrThrow(pool);
        }
    }

    /**
     * 等待线程池终止，并在超时时抛出带语义的异常。
     *
     * @param pool 待等待线程池
     * @throws InterruptedException 等待终止时被中断
     */
    private static void awaitTerminationOrThrow(ThreadPoolExecutor pool) throws InterruptedException {
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("线程池未按时终止");
        }
    }

    /**
     * 创建带稳定名称和异常处理器的线程工厂，便于统计 Worker 替补且避免实验噪声。
     *
     * @param prefix 线程名前缀
     * @param createdWorkers 已创建线程计数器
     * @return 线程工厂
     */
    private static ThreadFactory namedThreadFactory(String prefix, AtomicInteger createdWorkers) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + createdWorkers.incrementAndGet());
            thread.setUncaughtExceptionHandler((failedThread, throwable) -> {
                // 异常已经由 afterExecute 或实验计数器观测，这里只阻止默认处理器重复打印堆栈。
            });
            return thread;
        };
    }

    /**
     * 返回异常简单类名，避免控制台输出与具体消息耦合。
     *
     * @param throwable 待展示异常
     * @return 异常简单类名；没有异常时返回 null
     */
    private static String simpleName(Throwable throwable) {
        return throwable == null ? "null" : throwable.getClass().getSimpleName();
    }

    /**
     * 打印场景标题，使控制台输出与断点实验步骤保持一致。
     *
     * @param title 场景名称
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    /**
     * 在元素真正进入队列后暂停 offer，使测试能稳定插入 shutdown。
     */
    private static final class BlockingAfterOfferQueue extends ArrayBlockingQueue<Runnable> {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch offered;
        private final CountDownLatch continueOffer;

        /**
         * 创建容量为一的同步测试队列。
         *
         * @param offered 元素入队完成信号
         * @param continueOffer 允许 offer 返回的信号
         */
        private BlockingAfterOfferQueue(CountDownLatch offered, CountDownLatch continueOffer) {
            super(1);
            this.offered = offered;
            this.continueOffer = continueOffer;
        }

        /**
         * 先执行真实入队，再暂停调用线程，准确复现 execute 的复查窗口。
         *
         * @param runnable 待入队任务
         * @return 是否成功入队
         */
        @Override
        public boolean offer(Runnable runnable) {
            boolean accepted = super.offer(runnable);
            if (accepted) {
                offered.countDown();
                awaitGate(continueOffer);
            }
            return accepted;
        }
    }

    /**
     * 记录模板方法调用和两种任务异常边界的教学线程池。
     */
    static final class HookTrackingExecutor extends ThreadPoolExecutor {
        private final CopyOnWriteArrayList<String> hookEvents = new CopyOnWriteArrayList<>();
        private final CountDownLatch directFailureObserved = new CountDownLatch(1);
        private final CountDownLatch futureFailureObserved = new CountDownLatch(1);
        private final AtomicReference<Throwable> directFailure = new AtomicReference<>();
        private final AtomicReference<Throwable> futureFailure = new AtomicReference<>();
        private final AtomicBoolean futureRawThrowableWasNull = new AtomicBoolean();

        /**
         * 创建单 Worker、可替补且带稳定线程名称的钩子实验池。
         */
        HookTrackingExecutor() {
            super(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4),
                    namedThreadFactory("hook-worker-", new AtomicInteger()));
        }

        /**
         * 在用户任务前记录任务类型，再调用父类模板方法。
         *
         * @param thread 执行任务的线程
         * @param runnable 即将执行的任务
         */
        @Override
        protected void beforeExecute(Thread thread, Runnable runnable) {
            hookEvents.add("before:" + taskType(runnable));
            super.beforeExecute(thread, runnable);
        }

        /**
         * 同时记录直接异常和 Future 内部异常，展示 afterExecute 的真实参数边界。
         *
         * @param runnable 已完成任务
         * @param throwable runWorker 直接捕获到的异常
         */
        @Override
        protected void afterExecute(Runnable runnable, Throwable throwable) {
            super.afterExecute(runnable, throwable);
            hookEvents.add("after:" + taskType(runnable) + ":raw=" + simpleName(throwable));
            if (throwable != null) {
                directFailure.compareAndSet(null, throwable);
                directFailureObserved.countDown();
                return;
            }

            if (runnable instanceof Future<?> && ((Future<?>) runnable).isDone()) {
                futureRawThrowableWasNull.set(true);
                try {
                    ((Future<?>) runnable).get();
                } catch (CancellationException exception) {
                    futureFailure.compareAndSet(null, exception);
                    futureFailureObserved.countDown();
                } catch (ExecutionException exception) {
                    futureFailure.compareAndSet(null, exception.getCause());
                    futureFailureObserved.countDown();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * 在线程池进入 TERMINATED 前记录最终钩子。
         */
        @Override
        protected void terminated() {
            try {
                hookEvents.add("terminated");
            } finally {
                super.terminated();
            }
        }

        /**
         * 等待直接 execute 异常到达 afterExecute。
         *
         * @param timeout 超时时间
         * @param unit 时间单位
         * @return 是否在超时前观察到异常
         * @throws InterruptedException 等待时被中断
         */
        boolean awaitDirectFailure(long timeout, TimeUnit unit) throws InterruptedException {
            return directFailureObserved.await(timeout, unit);
        }

        /**
         * 等待 Future 内部异常被 afterExecute 解包。
         *
         * @param timeout 超时时间
         * @param unit 时间单位
         * @return 是否在超时前观察到异常
         * @throws InterruptedException 等待时被中断
         */
        boolean awaitFutureFailure(long timeout, TimeUnit unit) throws InterruptedException {
            return futureFailureObserved.await(timeout, unit);
        }

        /**
         * 返回直接 execute 任务暴露的异常。
         *
         * @return 直接任务异常
         */
        Throwable directFailure() {
            return directFailure.get();
        }

        /**
         * 返回从 FutureTask 解包得到的真实异常。
         *
         * @return Future 内部异常
         */
        Throwable futureFailure() {
            return futureFailure.get();
        }

        /**
         * 返回 submit 失败到达 afterExecute 时原始 Throwable 是否为空。
         *
         * @return 原始 Throwable 是否为空
         */
        boolean futureRawThrowableWasNull() {
            return futureRawThrowableWasNull.get();
        }

        /**
         * 返回钩子事件的稳定快照。
         *
         * @return 钩子事件副本
         */
        List<String> events() {
            return new ArrayList<>(hookEvents);
        }

        /**
         * 把 FutureTask 与普通 Runnable 转成简短类型标签。
         *
         * @param runnable 待识别任务
         * @return 任务类型标签
         */
        private static String taskType(Runnable runnable) {
            return runnable instanceof Future<?> ? "FutureTask" : "Runnable";
        }
    }
}
