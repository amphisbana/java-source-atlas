package io.github.javasourceatlas.jdk.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 用受控线程顺序触发 FutureTask 执行、等待、完成、取消和复位分支的调试入口。
 */
public final class FutureTaskDebugLab {

    private static final long WAIT_SECONDS = 5;

    /**
     * 工具类不需要创建实例。
     */
    private FutureTaskDebugLab() {
    }

    /**
     * 按固定顺序运行全部 FutureTask 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 等待实验线程、读取结果或传播任务失败时抛出
     */
    public static void main(String[] args) throws Exception {
        printHeader("两个 run 只执行一次");
        observeSingleExecution();

        printHeader("两个 get 等待者与正常发布");
        observeWaitersAndCompletion();

        printHeader("异常完成");
        observeExceptionalCompletion();

        printHeader("超时等待不取消任务");
        observeTimedGet();

        printHeader("取消与协作中断");
        observeCancellation();

        printHeader("done 与 runAndReset");
        observeCompletionHooks();

        printHeader("get 等待者中断");
        observeInterruptedWaiter();
    }

    /**
     * 让两个线程同时调用同一个任务的 run，观察 runner CAS 只允许一次 Callable 调用。
     *
     * @throws Exception 等待线程完成或读取任务结果时抛出
     */
    static void observeSingleExecution() throws Exception {
        AtomicInteger callableCalls = new AtomicInteger();
        CountDownLatch runnersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        FutureTask<Integer> task = new FutureTask<>(() -> {
            callableCalls.incrementAndGet();
            return 42;
        });

        Thread first = new Thread(captureFailure(() -> {
            runnersReady.countDown();
            awaitGate(start);
            task.run();
        }, threadFailure), "future-runner-1");
        Thread second = new Thread(captureFailure(() -> {
            runnersReady.countDown();
            awaitGate(start);
            task.run();
        }, threadFailure), "future-runner-2");

        first.start();
        second.start();
        try {
            require(runnersReady.await(WAIT_SECONDS, TimeUnit.SECONDS), "两个 run 线程未按时就绪");
            start.countDown();
            joinWithin(first);
            joinWithin(second);
            rethrowThreadFailure(threadFailure.get());

            System.out.printf("Callable 调用次数=%d，结果=%d%n",
                    callableCalls.get(), task.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            interruptAndJoin(first);
            interruptAndJoin(second);
        }
    }

    /**
     * 让两个线程先进入 get 等待，再释放 Callable，观察 WaitNode 注册和逐个唤醒。
     *
     * @throws Exception 等待线程完成或读取结果时抛出
     */
    static void observeWaitersAndCompletion() throws Exception {
        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch releaseCallable = new CountDownLatch(1);
        AtomicReference<Integer> firstResult = new AtomicReference<>();
        AtomicReference<Integer> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        FutureTask<Integer> task = new FutureTask<>(() -> {
            callableStarted.countDown();
            awaitGate(releaseCallable);
            return 42;
        });

        Thread runner = new Thread(captureFailure(task::run, threadFailure), "future-completer");
        Thread firstWaiter = new Thread(captureFailure(() ->
                firstResult.set(getUnchecked(task)), threadFailure), "future-waiter-1");
        Thread secondWaiter = new Thread(captureFailure(() ->
                secondResult.set(getUnchecked(task)), threadFailure), "future-waiter-2");

        runner.start();
        try {
            require(callableStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "Callable 未按时开始");
            firstWaiter.start();
            secondWaiter.start();
            waitUntilBlocked(firstWaiter);
            waitUntilBlocked(secondWaiter);

            releaseCallable.countDown();
            joinWithin(runner);
            joinWithin(firstWaiter);
            joinWithin(secondWaiter);
            rethrowThreadFailure(threadFailure.get());

            System.out.printf("W1=%d，W2=%d，任务已完成=%s%n",
                    firstResult.get(), secondResult.get(), task.isDone());
        } finally {
            releaseCallable.countDown();
            interruptAndJoin(runner);
            interruptAndJoin(firstWaiter);
            interruptAndJoin(secondWaiter);
        }
    }

    /**
     * 让 Callable 抛出异常，观察 run 保存失败而 get 使用 ExecutionException 报告原因。
     */
    static void observeExceptionalCompletion() {
        IllegalStateException failure = new IllegalStateException("模拟计算失败");
        FutureTask<Integer> task = new FutureTask<>(() -> {
            throw failure;
        });

        task.run();
        try {
            task.get();
            throw new IllegalStateException("异常任务不应返回正常结果");
        } catch (ExecutionException exception) {
            System.out.printf("state 对外已完成=%s，异常原因相同=%s，原因=%s%n",
                    task.isDone(), exception.getCause() == failure, exception.getCause().getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取异常任务时被中断", exception);
        }
    }

    /**
     * 先触发一次定时 get 超时，再完成同一个任务，证明等待超时不会修改 FutureTask 状态。
     *
     * @throws Exception 等待线程、超时结果或最终结果时抛出
     */
    static void observeTimedGet() throws Exception {
        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch releaseCallable = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        FutureTask<String> task = new FutureTask<>(() -> {
            callableStarted.countDown();
            awaitGate(releaseCallable);
            return "done";
        });
        Thread runner = new Thread(captureFailure(task::run, threadFailure), "future-timeout-runner");

        runner.start();
        try {
            require(callableStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "超时实验 Callable 未按时开始");
            boolean timedOut = false;
            try {
                task.get(80, TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                timedOut = true;
            }
            System.out.printf("首次等待超时=%s，任务已完成=%s，已取消=%s%n",
                    timedOut, task.isDone(), task.isCancelled());

            releaseCallable.countDown();
            String result = task.get(WAIT_SECONDS, TimeUnit.SECONDS);
            joinWithin(runner);
            rethrowThreadFailure(threadFailure.get());
            System.out.printf("释放任务后的结果=%s%n", result);
        } finally {
            releaseCallable.countDown();
            task.cancel(true);
            interruptAndJoin(runner);
        }
    }

    /**
     * 对比执行前 cancel(false) 与运行中 cancel(true)，展示取消和协作中断边界。
     *
     * @throws Exception 等待运行中任务观察中断或线程退出时抛出
     */
    static void observeCancellation() throws Exception {
        AtomicInteger neverStartedCalls = new AtomicInteger();
        FutureTask<Integer> cancelledBeforeRun = new FutureTask<>(neverStartedCalls::incrementAndGet);
        boolean cancelled = cancelledBeforeRun.cancel(false);
        cancelledBeforeRun.run();

        boolean cancellationReported = false;
        try {
            cancelledBeforeRun.get();
        } catch (CancellationException exception) {
            cancellationReported = true;
        }
        System.out.printf("执行前取消=%s，Callable 调用次数=%d，get 报告取消=%s%n",
                cancelled, neverStartedCalls.get(), cancellationReported);

        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch callableStopped = new CountDownLatch(1);
        AtomicBoolean interruptObserved = new AtomicBoolean();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        FutureTask<String> cooperativeTask = new FutureTask<>(() -> {
            callableStarted.countDown();
            try {
                new CountDownLatch(1).await();
                return "不可达结果";
            } catch (InterruptedException exception) {
                // cancel(true) 只是发出中断请求；任务在这里识别请求并自行结束。
                interruptObserved.set(true);
                Thread.currentThread().interrupt();
                return "协作退出";
            } finally {
                callableStopped.countDown();
            }
        });
        Thread runner = new Thread(captureFailure(cooperativeTask::run, threadFailure),
                "future-cooperative-runner");

        runner.start();
        try {
            require(callableStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "协作取消任务未按时开始");
            boolean interruptRequested = cooperativeTask.cancel(true);
            require(callableStopped.await(WAIT_SECONDS, TimeUnit.SECONDS), "协作取消任务未按时退出");
            joinWithin(runner);
            rethrowThreadFailure(threadFailure.get());

            System.out.printf("cancel(true) 成功=%s，Callable 观察到中断=%s，Future 已取消=%s%n",
                    interruptRequested, interruptObserved.get(), cooperativeTask.isCancelled());
        } finally {
            cooperativeTask.cancel(true);
            interruptAndJoin(runner);
        }
    }

    /**
     * 连续执行两次 runAndReset，再以普通 run 发布最终结果并触发 done。
     *
     * @throws Exception 读取最终结果时抛出
     */
    static void observeCompletionHooks() throws Exception {
        AtomicInteger callableCalls = new AtomicInteger();
        InspectableFutureTask<Integer> task = new InspectableFutureTask<>(callableCalls::incrementAndGet);

        boolean firstReset = task.runAndResetOnce();
        boolean secondReset = task.runAndResetOnce();
        System.out.printf("两轮 reset=%s/%s，调用次数=%d，isDone=%s，done 次数=%d%n",
                firstReset, secondReset, callableCalls.get(), task.isDone(), task.doneCalls());

        task.run();
        System.out.printf("最终结果=%d，调用次数=%d，done 次数=%d，done 线程=%s%n",
                task.get(WAIT_SECONDS, TimeUnit.SECONDS), callableCalls.get(),
                task.doneCalls(), task.doneThreadName());
    }

    /**
     * 中断一个已经停在 get 的等待者，观察等待退出但 FutureTask 仍可继续完成。
     *
     * @throws Exception 等待线程结束、传播线程失败或读取最终结果时抛出
     */
    static void observeInterruptedWaiter() throws Exception {
        FutureTask<Integer> task = new FutureTask<>(() -> 42);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread waiter = new Thread(captureFailure(() -> {
            try {
                task.get();
                throw new IllegalStateException("被中断的等待者不应正常取得结果");
            } catch (InterruptedException exception) {
                interrupted.set(true);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("未运行的任务不应异常完成", exception);
            }
        }, threadFailure), "future-interrupted-waiter");

        waiter.start();
        try {
            waitUntilBlocked(waiter);
            waiter.interrupt();
            joinWithin(waiter);
            rethrowThreadFailure(threadFailure.get());

            System.out.printf("等待者观察中断=%s，中断后 isDone=%s，isCancelled=%s%n",
                    interrupted.get(), task.isDone(), task.isCancelled());
            task.run();
            System.out.printf("其他线程仍可完成任务，结果=%d%n",
                    task.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            waiter.interrupt();
            interruptAndJoin(waiter);
            task.cancel(false);
        }
    }

    /**
     * 把线程动作中的失败记录到共享引用，避免异常只打印在线程未捕获处理器中。
     *
     * @param action 线程要执行的动作
     * @param failure 首个线程失败的保存位置
     * @return 会记录失败的线程动作
     */
    private static Runnable captureFailure(Runnable action, AtomicReference<Throwable> failure) {
        return () -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        };
    }

    /**
     * 在线程动作中读取 FutureTask 结果，把受检异常转换为可记录的运行时失败。
     *
     * @param task 待读取的任务
     * @param <V> 结果类型
     * @return 已完成任务结果
     */
    private static <V> V getUnchecked(FutureTask<V> task) {
        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 FutureTask 时被中断", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("FutureTask 异常完成", exception.getCause());
        }
    }

    /**
     * 在限定时间内等待闸门，超时或中断都终止当前实验动作。
     *
     * @param gate 需要等待的闸门
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("实验闸门未在预期时间内打开");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待实验闸门时被中断", exception);
        }
    }

    /**
     * 等待目标线程进入 park 对应的等待状态，确保 WaitNode 已有机会完成注册。
     *
     * @param thread 预期进入阻塞状态的线程
     */
    private static void waitUntilBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (!thread.isAlive()) {
                throw new IllegalStateException("等待线程在进入阻塞状态前已经结束");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new IllegalStateException("等待线程未在预期时间内进入阻塞状态");
    }

    /**
     * 在限定时间内等待线程结束，超时后中断并再次等待。
     *
     * @param thread 需要回收的线程
     * @throws InterruptedException 当前线程在等待期间被中断
     */
    private static void joinWithin(Thread thread) throws InterruptedException {
        if (!thread.isAlive()) {
            return;
        }
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        if (thread.isAlive()) {
            throw new IllegalStateException("实验线程未在预期时间内结束：" + thread.getName());
        }
    }

    /**
     * 清理仍存活的实验线程，并使用有限等待避免进程被残留线程拖住。
     *
     * @param thread 需要清理的线程
     * @throws InterruptedException 当前线程在等待期间被中断
     */
    private static void interruptAndJoin(Thread thread) throws InterruptedException {
        if (thread.isAlive()) {
            thread.interrupt();
            joinWithin(thread);
        }
    }

    /**
     * 把实验线程记录的失败重新抛给主线程。
     *
     * @param failure 线程记录的首个失败，可以为空
     * @throws Exception 线程记录的是受检异常时原样抛出
     */
    private static void rethrowThreadFailure(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("实验线程失败", failure);
    }

    /**
     * 检查实验前置条件，失败时给出明确原因。
     *
     * @param condition 需要满足的条件
     * @param message 条件不满足时的错误信息
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
     * 暴露受保护 runAndReset 并记录 done 调用，供调试扩展点时使用。
     *
     * @param <V> 任务结果类型
     */
    private static final class InspectableFutureTask<V> extends FutureTask<V> {

        private final AtomicInteger doneCalls = new AtomicInteger();
        private final AtomicReference<String> doneThreadName = new AtomicReference<>();

        /**
         * 创建可观察完成钩子和复位运行的 FutureTask。
         *
         * @param callable 真实计算
         */
        private InspectableFutureTask(Callable<V> callable) {
            super(callable);
        }

        /**
         * 在 FutureTask 到达终态时记录调用次数和当前线程。
         */
        @Override
        protected void done() {
            doneCalls.incrementAndGet();
            doneThreadName.set(Thread.currentThread().getName());
        }

        /**
         * 为实验公开一次受控的 runAndReset 调用。
         *
         * @return 本轮 Callable 正常结束且任务仍保持 NEW 时返回 true
         */
        private boolean runAndResetOnce() {
            return super.runAndReset();
        }

        /**
         * 返回 done 钩子的累计调用次数。
         *
         * @return done 调用次数
         */
        private int doneCalls() {
            return doneCalls.get();
        }

        /**
         * 返回最近一次执行 done 的线程名。
         *
         * @return done 执行线程名
         */
        private String doneThreadName() {
            return doneThreadName.get();
        }
    }
}
