package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 FutureTask 专题依赖的公开可观察行为和受保护扩展点。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class FutureTaskBehaviorTest {

    private static final long WAIT_SECONDS = 5;

    /**
     * 验证两个并发 run 调用只能让一个线程执行 Callable，之后重复 run 也不会重新执行。
     *
     * @throws Exception 等待并发调用或读取结果时抛出
     */
    @Test
    void shouldRunCallableOnlyOnce() throws Exception {
        AtomicInteger callableCalls = new AtomicInteger();
        CountDownLatch runnersReady = new CountDownLatch(2);
        CountDownLatch startRunners = new CountDownLatch(1);
        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch releaseCallable = new CountDownLatch(1);
        FutureTask<Integer> task = new FutureTask<>(() -> {
            callableCalls.incrementAndGet();
            callableStarted.countDown();
            awaitGate(releaseCallable);
            return 42;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstRun = executor.submit(() -> {
                runnersReady.countDown();
                awaitGate(startRunners);
                task.run();
            });
            Future<?> secondRun = executor.submit(() -> {
                runnersReady.countDown();
                awaitGate(startRunners);
                task.run();
            });

            assertTrue(runnersReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            startRunners.countDown();
            assertTrue(callableStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            releaseCallable.countDown();
            firstRun.get(WAIT_SECONDS, TimeUnit.SECONDS);
            secondRun.get(WAIT_SECONDS, TimeUnit.SECONDS);

            task.run();
            assertEquals(1, callableCalls.get());
            assertEquals(42, task.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(task.isDone());
            assertFalse(task.isCancelled());
        } finally {
            startRunners.countDown();
            releaseCallable.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 Callable 抛出的原异常被保存，并由 get 使用 ExecutionException 包装。
     */
    @Test
    void shouldReportExceptionalCompletion() {
        IllegalStateException failure = new IllegalStateException("模拟失败");
        FutureTask<Integer> task = new FutureTask<>(() -> {
            throw failure;
        });

        task.run();

        ExecutionException thrown = assertThrows(ExecutionException.class, task::get);
        assertSame(failure, thrown.getCause());
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
    }

    /**
     * 验证定时 get 超时不会完成或取消任务，任务随后仍能正常发布结果。
     *
     * @throws Exception 等待任务开始、完成或关闭执行器时抛出
     */
    @Test
    void shouldKeepTaskRunningAfterTimedGetExpires() throws Exception {
        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch releaseCallable = new CountDownLatch(1);
        FutureTask<String> task = new FutureTask<>(() -> {
            callableStarted.countDown();
            awaitGate(releaseCallable);
            return "done";
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.execute(task);
            assertTrue(callableStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () -> task.get(30, TimeUnit.MILLISECONDS));
            assertFalse(task.isDone());
            assertFalse(task.isCancelled());

            releaseCallable.countDown();
            assertEquals("done", task.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            releaseCallable.countDown();
            task.cancel(true);
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 get 等待者被中断后只退出自身等待，FutureTask 仍可由其他线程正常完成。
     *
     * @throws Exception 等待线程结束或读取最终任务结果时失败
     */
    @Test
    void shouldRemoveInterruptedGetWaiterWithoutCompletingTask() throws Exception {
        FutureTask<Integer> task = new FutureTask<>(() -> 42);
        AtomicReference<Throwable> waiterResult = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                task.get();
                waiterResult.set(new AssertionError("被中断的 get 不应正常返回"));
            } catch (InterruptedException exception) {
                waiterResult.set(exception);
            } catch (ExecutionException exception) {
                waiterResult.set(exception);
            }
        }, "future-interrupted-waiter");

        waiter.start();
        try {
            waitUntilBlocked(waiter, "FutureTask get 等待者未进入阻塞状态");
            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertFalse(waiter.isAlive());
            assertTrue(waiterResult.get() instanceof InterruptedException);
            assertFalse(task.isDone());
            assertFalse(task.isCancelled());

            task.run();
            assertEquals(42, task.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            task.cancel(false);
        }
    }

    /**
     * 验证执行前 cancel(false) 会阻止 Callable 开始，并让 get 抛 CancellationException。
     */
    @Test
    void shouldPreventRunWhenCancelledBeforeExecution() {
        AtomicInteger callableCalls = new AtomicInteger();
        FutureTask<Integer> task = new FutureTask<>(callableCalls::incrementAndGet);

        assertTrue(task.cancel(false));
        task.run();

        assertEquals(0, callableCalls.get());
        assertTrue(task.isDone());
        assertTrue(task.isCancelled());
        assertFalse(task.cancel(true));
        assertThrows(CancellationException.class, task::get);
    }

    /**
     * 验证运行中 cancel(false) 只取消 Future 结果，不中断已经执行的 Callable。
     *
     * @throws Exception 等待 Callable 继续和执行器退出时抛出
     */
    @Test
    void shouldNotInterruptRunningCallableWhenCancelledWithoutInterrupt() throws Exception {
        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch releaseCallable = new CountDownLatch(1);
        CountDownLatch callableFinished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        FutureTask<String> task = new FutureTask<>(() -> {
            callableStarted.countDown();
            try {
                releaseCallable.await();
                return "calculated";
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return "interrupted";
            } finally {
                callableFinished.countDown();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.execute(task);
            assertTrue(callableStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));

            // 取消状态已经发布，但 Callable 只能在测试主动释放闸门后自然结束。
            releaseCallable.countDown();
            assertTrue(callableFinished.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
            assertThrows(CancellationException.class, task::get);
        } finally {
            releaseCallable.countDown();
            task.cancel(true);
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证运行中 cancel(true) 会请求中断，阻塞中的 Callable 可以观察并协作退出。
     *
     * @throws Exception 等待中断观察和执行器退出时抛出
     */
    @Test
    void shouldRequestCooperativeInterruptWhenCancelledWithInterrupt() throws Exception {
        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch callableFinished = new CountDownLatch(1);
        AtomicBoolean interruptObserved = new AtomicBoolean();
        FutureTask<String> task = new FutureTask<>(() -> {
            callableStarted.countDown();
            try {
                new CountDownLatch(1).await();
                return "不可达结果";
            } catch (InterruptedException exception) {
                // 测试任务显式响应中断并退出，用于证明停止依赖 Callable 自己协作。
                interruptObserved.set(true);
                Thread.currentThread().interrupt();
                return "协作退出";
            } finally {
                callableFinished.countDown();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.execute(task);
            assertTrue(callableStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(task.cancel(true));

            assertTrue(callableFinished.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(interruptObserved.get());
            assertTrue(task.isDone());
            assertTrue(task.isCancelled());
            assertThrows(CancellationException.class, task::get);
        } finally {
            task.cancel(true);
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 done 在正常完成和取消时各调用一次，重复完成尝试不会再次调用钩子。
     */
    @Test
    void shouldInvokeDoneHookOnceForEachTerminalTask() {
        RecordingFutureTask<Integer> completed = new RecordingFutureTask<>(() -> 42);
        completed.run();
        completed.run();
        completed.cancel(false);

        RecordingFutureTask<Integer> cancelled = new RecordingFutureTask<>(() -> 7);
        cancelled.cancel(false);
        cancelled.run();

        assertEquals(1, completed.doneCalls());
        assertTrue(completed.doneSawDone());
        assertFalse(completed.doneSawCancelled());
        assertEquals(1, cancelled.doneCalls());
        assertTrue(cancelled.doneSawDone());
        assertTrue(cancelled.doneSawCancelled());
    }

    /**
     * 验证 runAndReset 成功运行后仍保持未完成，普通 run 才发布结果并触发 done。
     *
     * @throws Exception 读取最终结果时抛出
     */
    @Test
    void shouldKeepTaskNewAfterSuccessfulRunAndReset() throws Exception {
        AtomicInteger callableCalls = new AtomicInteger();
        RecordingFutureTask<Integer> task = new RecordingFutureTask<>(callableCalls::incrementAndGet);

        assertTrue(task.runAndResetOnce());
        assertTrue(task.runAndResetOnce());
        assertEquals(2, callableCalls.get());
        assertFalse(task.isDone());
        assertEquals(0, task.doneCalls());

        task.run();
        assertEquals(3, task.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(3, callableCalls.get());
        assertEquals(1, task.doneCalls());
    }

    /**
     * 在限定时间内等待测试闸门，超时或中断时使工作任务失败。
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
     * 在统一截止时间内等待 FutureTask 等待者真正进入 park 状态。
     *
     * @param thread 预期进入等待的线程
     * @param message 未按时进入等待时的错误信息
     */
    private static void waitUntilBlocked(Thread thread, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (!thread.isAlive()) {
                throw new AssertionError(message + "：线程已提前结束");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError(message);
    }

    /**
     * 立即停止执行器并在限定时间内等待所有工作线程退出。
     *
     * @param executor 需要关闭的执行器
     * @throws InterruptedException 等待执行器终止时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                "FutureTask 测试执行器未在预期时间内终止");
    }

    /**
     * 记录 done 状态并暴露 runAndReset，便于验证 FutureTask 受保护扩展点。
     *
     * @param <V> 任务结果类型
     */
    private static final class RecordingFutureTask<V> extends FutureTask<V> {

        private final AtomicInteger doneCalls = new AtomicInteger();
        private final AtomicBoolean doneSawDone = new AtomicBoolean();
        private final AtomicBoolean doneSawCancelled = new AtomicBoolean();

        /**
         * 创建记录完成钩子的 FutureTask。
         *
         * @param callable 真实计算
         */
        private RecordingFutureTask(Callable<V> callable) {
            super(callable);
        }

        /**
         * 在终态已经发布后记录公开查询结果。
         */
        @Override
        protected void done() {
            doneCalls.incrementAndGet();
            doneSawDone.set(isDone());
            doneSawCancelled.set(isCancelled());
        }

        /**
         * 为测试公开一次受控的 runAndReset 调用。
         *
         * @return 本轮 Callable 正常结束且任务仍为 NEW 时返回 true
         */
        private boolean runAndResetOnce() {
            return super.runAndReset();
        }

        /**
         * 返回 done 累计调用次数。
         *
         * @return done 调用次数
         */
        private int doneCalls() {
            return doneCalls.get();
        }

        /**
         * 返回 done 执行时是否已经观察到 isDone。
         *
         * @return done 观察到终态时返回 true
         */
        private boolean doneSawDone() {
            return doneSawDone.get();
        }

        /**
         * 返回 done 执行时是否观察到取消状态。
         *
         * @return done 观察到取消时返回 true
         */
        private boolean doneSawCancelled() {
            return doneSawCancelled.get();
        }
    }
}
