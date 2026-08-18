package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CompletableFuture 教学案例依赖的公开可观察行为。
 */
class CompletableFutureBehaviorTest {

    /**
     * 验证 thenApply 转换普通值，而 thenCompose 会展平函数返回的阶段。
     */
    @Test
    void shouldTransformAndComposeResult() {
        CompletableFuture<Integer> source = new CompletableFuture<>();
        CompletableFuture<Integer> returnedStage = new CompletableFuture<>();
        CompletableFuture<String> result = source
                .thenApply(value -> value * 2)
                .thenCompose(value -> returnedStage.thenApply(suffix -> "value=" + (value + suffix)));

        source.complete(20);
        assertFalse(result.isDone());
        returnedStage.complete(2);

        assertEquals("value=42", result.join());
    }

    /**
     * 验证上游异常时普通转换不会执行，exceptionally 可以返回正常替代值。
     */
    @Test
    void shouldSkipNormalTransformAndRecoverException() {
        AtomicBoolean transformed = new AtomicBoolean();
        CompletableFuture<Integer> failed = new CompletableFuture<>();
        CompletableFuture<Integer> recovered = failed
                .thenApply(value -> {
                    transformed.set(true);
                    return value * 2;
                })
                .exceptionally(exception -> -1);

        failed.completeExceptionally(new IllegalStateException("模拟失败"));

        assertEquals(-1, recovered.join());
        assertTrue(failed.isCompletedExceptionally());
        assertFalse(transformed.get());
    }

    /**
     * 验证 thenCombine 只在两个输入都有结果后计算合并值。
     */
    @Test
    void shouldCombineTwoCompletedResults() {
        CompletableFuture<Integer> price = new CompletableFuture<>();
        CompletableFuture<Integer> count = new CompletableFuture<>();
        CompletableFuture<Integer> total = price.thenCombine(count, (left, right) -> left * right);

        price.complete(7);
        assertFalse(total.isDone());
        count.complete(6);

        assertEquals(42, total.join());
    }

    /**
     * 验证 allOf 只等待全部阶段，具体值仍需从原阶段读取。
     */
    @Test
    void shouldWaitForAllInputsWithoutCollectingValues() {
        CompletableFuture<Integer> first = CompletableFuture.completedFuture(1);
        CompletableFuture<Integer> second = CompletableFuture.completedFuture(2);
        CompletableFuture<Integer> third = CompletableFuture.completedFuture(3);

        CompletableFuture<Void> all = CompletableFuture.allOf(first, second, third);
        all.join();
        List<Integer> values = Arrays.asList(first.join(), second.join(), third.join());

        assertEquals(Arrays.asList(1, 2, 3), values);
    }

    /**
     * 验证 allOf 在其他输入未完成时不会仅因一个输入失败就立即完成。
     */
    @Test
    void shouldWaitForRemainingInputsBeforePropagatingAllOfFailure() {
        CompletableFuture<Integer> failed = new CompletableFuture<>();
        CompletableFuture<Integer> pending = new CompletableFuture<>();
        CompletableFuture<Void> all = CompletableFuture.allOf(failed, pending);
        IllegalStateException failure = new IllegalStateException("模拟失败");

        failed.completeExceptionally(failure);
        assertFalse(all.isDone());
        pending.complete(2);

        CompletionException thrown = assertThrows(CompletionException.class, all::join);
        assertSame(failure, thrown.getCause());
    }

    /**
     * 验证空 anyOf 保持未完成，首个异常结果也会完成聚合阶段。
     */
    @Test
    void shouldKeepEmptyAnyOfPendingAndPropagateFirstFailure() {
        assertFalse(CompletableFuture.anyOf().isDone());

        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();
        CompletableFuture<Object> any = CompletableFuture.anyOf(first, second);
        IllegalArgumentException failure = new IllegalArgumentException("首个阶段失败");

        first.completeExceptionally(failure);
        CompletionException thrown = assertThrows(CompletionException.class, any::join);
        second.complete(2);

        assertSame(failure, thrown.getCause());
    }

    /**
     * 验证上游与 whenComplete 动作同时失败时，上游异常保持为主要结果。
     */
    @Test
    void shouldPreferSourceFailureWhenObserverAlsoFails() {
        CompletableFuture<Integer> source = new CompletableFuture<>();
        IllegalStateException sourceFailure = new IllegalStateException("上游失败");
        CompletableFuture<Integer> observed = source.whenComplete((value, exception) -> {
            throw new IllegalArgumentException("观察动作失败");
        });

        source.completeExceptionally(sourceFailure);

        CompletionException thrown = assertThrows(CompletionException.class, observed::join);
        assertSame(sourceFailure, thrown.getCause());
    }

    /**
     * 验证 get 使用 ExecutionException，而 join 使用 CompletionException 包装业务异常。
     */
    @Test
    void shouldExposeDifferentExceptionWrappersForGetAndJoin() {
        CompletableFuture<Integer> failed = new CompletableFuture<>();
        IllegalStateException failure = new IllegalStateException("模拟失败");
        failed.completeExceptionally(failure);

        ExecutionException getFailure = assertThrows(ExecutionException.class, failed::get);
        CompletionException joinFailure = assertThrows(CompletionException.class, failed::join);

        assertSame(failure, getFailure.getCause());
        assertSame(failure, joinFailure.getCause());
    }

    /**
     * 验证非 Async 回调可由完成源阶段的线程直接执行。
     *
     * @throws InterruptedException 等待完成线程结束时被中断
     */
    @Test
    void shouldRunNonAsyncActionOnCompletingThread() throws InterruptedException {
        CompletableFuture<Integer> source = new CompletableFuture<>();
        AtomicReference<String> actionThread = new AtomicReference<>();
        CompletableFuture<Integer> dependent = source.thenApply(value -> {
            actionThread.set(Thread.currentThread().getName());
            return value * 2;
        });
        Thread completingThread = new Thread(() -> source.complete(21), "atlas-completer");

        completingThread.start();
        completingThread.join();

        assertEquals(42, dependent.join());
        assertEquals("atlas-completer", actionThread.get());
    }

    /**
     * 验证 join 不因中断提前结束，并在取得结果后恢复线程中断标记。
     *
     * @throws InterruptedException 等待测试线程时被中断
     */
    @Test
    void shouldRestoreInterruptStatusAfterJoinCompletes() throws InterruptedException {
        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch joinStarted = new CountDownLatch(1);
        AtomicBoolean returnedWithInterrupt = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            joinStarted.countDown();
            future.join();
            returnedWithInterrupt.set(Thread.currentThread().isInterrupted());
        }, "join-interrupted-waiter");

        waiter.start();
        joinStarted.await();
        try {
            waitUntilBlocked(waiter, 2, TimeUnit.SECONDS);
            assertTrue(waiter.isAlive());
        } finally {
            // 即使状态轮询或断言失败，也必须完成 future，避免非守护等待线程拖住测试进程。
            future.complete("done");
            waiter.join(TimeUnit.SECONDS.toMillis(2));
            if (waiter.isAlive()) {
                waiter.interrupt();
                waiter.join(TimeUnit.SECONDS.toMillis(2));
            }
        }

        assertFalse(waiter.isAlive());
        assertTrue(returnedWithInterrupt.get());
    }

    /**
     * 验证 get 检测到中断后抛出 InterruptedException 并清除中断状态。
     *
     * @throws InterruptedException 等待测试线程时被中断
     */
    @Test
    void shouldClearInterruptStatusWhenGetThrows() throws InterruptedException {
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicBoolean interruptedExceptionSeen = new AtomicBoolean();
        AtomicBoolean interruptStatusAfterCatch = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                future.get();
            } catch (InterruptedException exception) {
                interruptedExceptionSeen.set(true);
                interruptStatusAfterCatch.set(Thread.currentThread().isInterrupted());
            } catch (ExecutionException exception) {
                throw new AssertionError("未完成 future 不应产生业务异常", exception);
            }
        }, "get-interrupted-waiter");

        waiter.start();
        waiter.join();

        assertTrue(interruptedExceptionSeen.get());
        assertFalse(interruptStatusAfterCatch.get());
    }

    /**
     * 验证取消会让阶段同时处于已取消和异常完成状态。
     */
    @Test
    void shouldExposeCancellationAsExceptionalCompletion() {
        CompletableFuture<String> future = new CompletableFuture<>();

        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
        assertTrue(future.isCompletedExceptionally());
        assertThrows(CancellationException.class, future::join);
    }

    /**
     * 验证 cancel(true) 只改变 future 状态，不会中断已经运行的 Supplier。
     *
     * @throws InterruptedException 等待执行器和闸门时被中断
     */
    @Test
    void shouldNotInterruptRunningSupplierWhenCancelled() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean supplierInterrupted = new AtomicBoolean();
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    supplierInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return "done";
            }, executor);

            started.await();
            assertTrue(future.cancel(true));
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            assertFalse(supplierInterrupted.get());
            assertThrows(CancellationException.class, future::join);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 等待目标线程进入阻塞状态，确保中断语义测试覆盖 waitingGet 路径。
     *
     * @param thread 预期进入等待的线程
     * @param timeout 最长等待时长
     * @param unit 等待时长单位
     */
    private static void waitUntilBlocked(Thread thread, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() - deadline < 0) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (!thread.isAlive()) {
                throw new AssertionError("测试线程在进入等待状态前已结束");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("测试线程未在预期时间内进入等待状态");
    }
}
