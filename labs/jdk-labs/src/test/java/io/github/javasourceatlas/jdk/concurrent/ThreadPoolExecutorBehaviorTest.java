package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ThreadPoolExecutor 教学案例依赖的公开可观察行为。
 */
class ThreadPoolExecutorBehaviorTest {

    /**
     * 验证线程数和队列同时达到上限后执行拒绝策略。
     *
     * @throws InterruptedException 等待工作线程时被中断
     */
    @Test
    void shouldRejectWhenWorkersAndQueueAreFull() throws InterruptedException {
        ThreadPoolExecutor pool = newPool(1, 2, 1, new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            started.countDown();
            awaitGate(release);
        };
        try {
            pool.execute(blocker);
            pool.execute(() -> { });
            pool.execute(blocker);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
        } finally {
            release.countDown();
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 CallerRunsPolicy 在饱和时使用提交线程执行任务。
     *
     * @throws InterruptedException 等待工作线程时被中断
     */
    @Test
    void shouldRunRejectedTaskInCallerThread() throws InterruptedException {
        ThreadPoolExecutor pool = newPool(1, 1, 1, new ThreadPoolExecutor.CallerRunsPolicy());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> taskThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try {
            pool.execute(() -> {
                started.countDown();
                awaitGate(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            pool.execute(() -> { });

            pool.execute(() -> taskThread.set(Thread.currentThread()));

            assertEquals(caller, taskThread.get());
        } finally {
            release.countDown();
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 shutdown 会排空已接收任务，并拒绝后续提交。
     *
     * @throws InterruptedException 等待线程池终止时被中断
     */
    @Test
    void shouldDrainQueueDuringGracefulShutdown() throws InterruptedException {
        ThreadPoolExecutor pool = newPool(1, 1, 2, new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        try {
            pool.execute(() -> {
                started.countDown();
                awaitGate(release);
                completed.incrementAndGet();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            pool.execute(completed::incrementAndGet);

            pool.shutdown();
            assertThrows(RejectedExecutionException.class, () -> pool.execute(completed::incrementAndGet));
            release.countDown();

            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, completed.get());
        } finally {
            release.countDown();
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 shutdownNow 返回未开始任务，并中断正在等待的工作任务。
     *
     * @throws InterruptedException 等待中断结果时被中断
     */
    @Test
    void shouldReturnQueuedTasksDuringImmediateShutdown() throws InterruptedException {
        ThreadPoolExecutor pool = newPool(1, 1, 2, new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            pool.execute(() -> { });

            List<Runnable> notStarted = pool.shutdownNow();

            assertEquals(1, notStarted.size());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(pool.isTerminated());
            assertFalse(pool.isTerminating());
        } finally {
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 offer 成功后若 shutdown 抢先发生，execute 会移除刚入队任务并拒绝。
     *
     * @throws InterruptedException 等待提交线程和线程池终止时被中断
     */
    @Test
    void shouldRemoveAndRejectWhenShutdownWinsOfferRecheck() throws InterruptedException {
        CountDownLatch offered = new CountDownLatch(1);
        CountDownLatch continueOffer = new CountDownLatch(1);
        BlockingAfterOfferQueue queue = new BlockingAfterOfferQueue(offered, continueOffer);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, 1, 30, TimeUnit.SECONDS, queue,
                (task, executor) -> rejected.incrementAndGet());
        Thread submitter = new Thread(() -> pool.execute(executed::incrementAndGet), "offer-recheck-test");

        try {
            submitter.start();
            assertTrue(offered.await(5, TimeUnit.SECONDS));
            pool.shutdown();
            continueOffer.countDown();
            joinThread(submitter);

            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, executed.get());
            assertEquals(1, rejected.get());
            assertTrue(queue.isEmpty());
        } finally {
            continueOffer.countDown();
            pool.shutdownNow();
            joinThread(submitter);
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 shutdown 只中断空闲 Worker，不中断持有工作锁的活跃任务。
     *
     * @throws InterruptedException 等待任务和线程池终止时被中断
     */
    @Test
    void shouldNotInterruptActiveWorkerDuringGracefulShutdown() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        try {
            pool.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            pool.shutdown();

            assertFalse(interrupted.get());
            release.countDown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 allowCoreThreadTimeOut 会让空闲核心 Worker 通过定时 poll 回收到零。
     *
     * @throws InterruptedException 等待核心线程启动和回收时被中断
     */
    @Test
    void shouldAllowCoreWorkerToTimeOut() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 80, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            pool.allowCoreThreadTimeOut(true);
            assertTrue(pool.prestartCoreThread());
            awaitCondition(() -> pool.getPoolSize() == 1, "核心线程未启动");
            awaitCondition(() -> pool.getPoolSize() == 0, "核心线程未超时回收");

            assertEquals(0, pool.getPoolSize());
        } finally {
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 execute 任务异常退出后，线程池创建替补 Worker 继续处理队列任务。
     *
     * @throws InterruptedException 等待异常任务和替补任务时被中断
     */
    @Test
    void shouldReplaceWorkerAfterExecuteTaskFails() throws InterruptedException {
        AtomicInteger createdWorkers = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "replacement-test-" + createdWorkers.incrementAndGet());
            thread.setUncaughtExceptionHandler((failedThread, throwable) -> {
                // 测试通过替补线程数量验证异常退出，避免默认处理器重复打印预期异常。
            });
            return thread;
        };
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
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            pool.execute(secondCompleted::countDown);
            releaseFailure.countDown();

            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS));
            awaitCondition(() -> createdWorkers.get() >= 2, "线程池未创建替补 Worker");
            assertEquals(2, createdWorkers.get());
        } finally {
            releaseFailure.countDown();
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证三个模板钩子的顺序，以及 execute 与 submit 异常在 afterExecute 中的差异。
     *
     * @throws InterruptedException 等待钩子和线程池终止时被中断
     */
    @Test
    void shouldExposeDirectAndFutureFailuresThroughLifecycleHooks() throws InterruptedException {
        ThreadPoolExecutorDebugLab.HookTrackingExecutor pool =
                new ThreadPoolExecutorDebugLab.HookTrackingExecutor();
        try {
            pool.execute(() -> {
                throw new IllegalArgumentException("direct-failure");
            });
            assertTrue(pool.awaitDirectFailure(5, TimeUnit.SECONDS));

            Future<?> failedFuture = pool.submit(() -> {
                throw new IllegalStateException("submit-failure");
            });
            assertThrows(ExecutionException.class, failedFuture::get);
            assertTrue(pool.awaitFutureFailure(5, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(pool.directFailure() instanceof IllegalArgumentException);
            assertTrue(pool.futureFailure() instanceof IllegalStateException);
            assertTrue(pool.futureRawThrowableWasNull());
            assertEquals(
                    java.util.Arrays.asList(
                            "before:Runnable",
                            "after:Runnable:raw=IllegalArgumentException",
                            "before:FutureTask",
                            "after:FutureTask:raw=null",
                            "terminated"),
                    pool.events());
        } finally {
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证动态核心线程数约束在 JDK 8 与后续版本之间的真实差异。
     */
    @Test
    void shouldValidateCoreSizeAgainstMaximumAccordingToJdkVersion() {
        ThreadPoolExecutor pool = newPool(1, 1, 1, new ThreadPoolExecutor.AbortPolicy());
        try {
            if (javaMajorVersion() <= 8) {
                pool.setCorePoolSize(2);
                assertEquals(2, pool.getCorePoolSize());
            } else {
                assertThrows(IllegalArgumentException.class, () -> pool.setCorePoolSize(2));
                assertEquals(1, pool.getCorePoolSize());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证 afterExecute 始终获得原异常，而 Worker 外层对 sneaky checked Throwable 的传播随版本变化。
     *
     * @throws InterruptedException 等待 Worker 的未捕获异常处理器时被中断
     */
    @Test
    void shouldExposeSneakyCheckedThrowableAtBothObservationBoundaries() throws InterruptedException {
        AtomicReference<Throwable> uncaughtFailure = new AtomicReference<>();
        CountDownLatch uncaughtObserved = new CountDownLatch(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "sneaky-throw-test");
            thread.setUncaughtExceptionHandler((failedThread, throwable) -> {
                uncaughtFailure.compareAndSet(null, throwable);
                uncaughtObserved.countDown();
            });
            return thread;
        };
        ThrowableTrackingExecutor pool = new ThrowableTrackingExecutor(factory);
        Exception originalFailure = new Exception("checked-failure");

        try {
            pool.execute(() -> ThreadPoolExecutorBehaviorTest.<RuntimeException>sneakyThrow(originalFailure));
            assertTrue(pool.awaitAfterExecuteFailure(5, TimeUnit.SECONDS));
            assertTrue(uncaughtObserved.await(5, TimeUnit.SECONDS));

            assertSame(originalFailure, pool.afterExecuteFailure());
            if (javaMajorVersion() <= 8) {
                assertTrue(uncaughtFailure.get() instanceof Error);
                assertSame(originalFailure, uncaughtFailure.get().getCause());
            } else {
                assertSame(originalFailure, uncaughtFailure.get());
            }
        } finally {
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 验证 ExecutorService 自 JDK 19 起才公开 AutoCloseable close 方法。
     */
    @Test
    void shouldExposeExecutorServiceCloseFromJdk19() {
        boolean hasCloseMethod = java.util.Arrays.stream(ExecutorService.class.getMethods())
                .anyMatch(method -> method.getName().equals("close") && method.getParameterCount() == 0);

        assertEquals(javaMajorVersion() >= 19, hasCloseMethod);
        assertEquals(javaMajorVersion() >= 19, AutoCloseable.class.isAssignableFrom(ExecutorService.class));
    }

    /**
     * 创建使用有界队列的测试线程池。
     *
     * @param coreSize    核心线程数
     * @param maximumSize 最大线程数
     * @param queueSize   队列容量
     * @param handler     拒绝策略
     * @return 新线程池
     */
    private static ThreadPoolExecutor newPool(
            int coreSize,
            int maximumSize,
            int queueSize,
            java.util.concurrent.RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(
                coreSize,
                maximumSize,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize),
                handler);
    }

    /**
     * 读取当前 Java 规范主版本，兼容 Java 8 的 1.8 格式与后续整数格式。
     *
     * @return Java 主版本号
     */
    private static int javaMajorVersion() {
        String specificationVersion = System.getProperty("java.specification.version");
        if (specificationVersion.startsWith("1.")) {
            return Integer.parseInt(specificationVersion.substring(2));
        }
        return Integer.parseInt(specificationVersion);
    }

    /**
     * 绕过编译期 checked exception 声明，只用于验证 runWorker 的真实运行时传播边界。
     *
     * @param throwable 需要原样抛出的异常
     * @param <E> 由调用点推断的异常类型
     * @throws E 原样抛出的异常
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    /**
     * 等待闩锁释放，并在中断时恢复线程中断标记。
     *
     * @param gate 控制任务继续的闩锁
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 在截止时间内等待公开状态满足条件。
     *
     * @param condition 成功条件
     * @param message 超时断言信息
     * @throws InterruptedException 轮询等待时被中断
     */
    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(10L);
        }
    }

    /**
     * 等待测试辅助线程退出，防止失败路径遗留非守护线程。
     *
     * @param thread 待等待线程
     * @throws InterruptedException 等待时被中断
     */
    private static void joinThread(Thread thread) throws InterruptedException {
        if (thread.getState() == Thread.State.NEW) {
            return;
        }
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive());
    }

    /**
     * 立即关闭线程池并等待工作线程退出。
     *
     * @param pool 待关闭线程池
     * @throws InterruptedException 等待终止时被中断
     */
    private static void shutdownNowAndAwait(ThreadPoolExecutor pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * 在真实入队后暂停 offer，使 shutdown 与 execute 复查之间的竞争可重复。
     */
    private static final class BlockingAfterOfferQueue extends ArrayBlockingQueue<Runnable> {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch offered;
        private final CountDownLatch continueOffer;

        /**
         * 创建容量为一的同步队列。
         *
         * @param offered 入队完成信号
         * @param continueOffer 允许 offer 返回的信号
         */
        private BlockingAfterOfferQueue(CountDownLatch offered, CountDownLatch continueOffer) {
            super(1);
            this.offered = offered;
            this.continueOffer = continueOffer;
        }

        /**
         * 先调用真实队列完成入队，再等待测试线程放行。
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
     * 同时记录 afterExecute 原始异常的测试线程池。
     */
    private static final class ThrowableTrackingExecutor extends ThreadPoolExecutor {
        private final AtomicReference<Throwable> afterExecuteFailure = new AtomicReference<>();
        private final CountDownLatch afterExecuteObserved = new CountDownLatch(1);

        /**
         * 创建单 Worker 测试线程池。
         *
         * @param threadFactory 可记录未捕获异常的线程工厂
         */
        private ThrowableTrackingExecutor(ThreadFactory threadFactory) {
            super(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), threadFactory);
        }

        /**
         * 记录 runWorker 交给钩子的原始异常对象。
         *
         * @param runnable 已完成的任务
         * @param throwable 任务直接抛出的异常
         */
        @Override
        protected void afterExecute(Runnable runnable, Throwable throwable) {
            super.afterExecute(runnable, throwable);
            afterExecuteFailure.compareAndSet(null, throwable);
            afterExecuteObserved.countDown();
        }

        /**
         * 等待 afterExecute 观察到任务失败。
         *
         * @param timeout 超时时间
         * @param unit 时间单位
         * @return 是否在超时前完成观察
         * @throws InterruptedException 等待时被中断
         */
        private boolean awaitAfterExecuteFailure(long timeout, TimeUnit unit) throws InterruptedException {
            return afterExecuteObserved.await(timeout, unit);
        }

        /**
         * 返回 afterExecute 记录的异常对象。
         *
         * @return 原始任务异常
         */
        private Throwable afterExecuteFailure() {
            return afterExecuteFailure.get();
        }
    }
}
