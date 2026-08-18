package io.github.javasourceatlas.jdk.concurrent;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 用公开 API 稳定触发 CompletableFuture 创建、传播、组合和异常分支的调试入口。
 */
public final class CompletableFutureDebugLab {

    /**
     * 工具类不需要创建实例。
     */
    private CompletableFutureDebugLab() {
    }

    /**
     * 创建受控执行器并按固定顺序运行全部 CompletableFuture 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("atlas-io"));
        ExecutorService cpuExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("atlas-cpu"));
        try {
            printHeader("同步与异步回调线程");
            observeThreadBoundaries(ioExecutor, cpuExecutor);

            printHeader("thenCompose 展平");
            observeComposition();

            printHeader("异常恢复");
            observeExceptionRecovery(ioExecutor);

            printHeader("allOf 聚合");
            observeAllOf();

            printHeader("join 等待与唤醒");
            observeJoinWaiting();

            printHeader("取消状态");
            observeCancellation();
        } finally {
            ioExecutor.shutdownNow();
            cpuExecutor.shutdownNow();
        }
    }

    /**
     * 通过闸门确保依赖先注册，再观察普通回调和 Async 回调的线程边界。
     *
     * @param ioExecutor 执行源任务的单线程执行器
     * @param cpuExecutor 执行异步转换的单线程执行器
     */
    static void observeThreadBoundaries(ExecutorService ioExecutor, ExecutorService cpuExecutor) {
        CountDownLatch startGate = new CountDownLatch(1);
        CompletableFuture<String> pipeline = CompletableFuture
                .supplyAsync(() -> {
                    await(startGate);
                    System.out.printf("Supplier 线程=%s%n", Thread.currentThread().getName());
                    return 21;
                }, ioExecutor)
                .thenApply(value -> {
                    System.out.printf("thenApply 线程=%s%n", Thread.currentThread().getName());
                    return value * 2;
                })
                .thenApplyAsync(value -> {
                    System.out.printf("thenApplyAsync 线程=%s%n", Thread.currentThread().getName());
                    return "value=" + value;
                }, cpuExecutor);

        startGate.countDown();
        System.out.printf("最终结果=%s%n", pipeline.join());
    }

    /**
     * 让函数返回另一个阶段，观察 thenCompose 得到单层结果。
     */
    static void observeComposition() {
        CompletableFuture<Integer> source = new CompletableFuture<>();
        CompletableFuture<Integer> returnedStage = new CompletableFuture<>();
        CompletableFuture<String> composed = source.thenCompose(value ->
                returnedStage.thenApply(multiplier -> "value=" + (value * multiplier)));

        source.complete(21);
        System.out.printf("返回阶段完成前，composed.isDone=%s%n", composed.isDone());
        returnedStage.complete(2);

        System.out.printf("展平结果=%s%n", composed.join());
    }

    /**
     * 让源任务抛出异常，观察普通转换被跳过而 exceptionally 提供替代值。
     *
     * @param executor 执行失败源任务的执行器
     */
    static void observeExceptionRecovery(ExecutorService executor) {
        CountDownLatch startGate = new CountDownLatch(1);
        CompletableFuture<Integer> recovered = CompletableFuture
                .<Integer>supplyAsync(() -> {
                    await(startGate);
                    throw new IllegalStateException("模拟加载失败");
                }, executor)
                .thenApply(value -> value * 2)
                .exceptionally(exception -> -1);

        startGate.countDown();
        System.out.printf("恢复结果=%d%n", recovered.join());
    }

    /**
     * 等待三个阶段全部完成，再从原阶段读取各自结果。
     */
    static void observeAllOf() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();
        CompletableFuture<Integer> third = new CompletableFuture<>();
        CompletableFuture<Void> all = CompletableFuture.allOf(first, second, third);

        first.complete(1);
        second.complete(2);
        System.out.printf("两个输入完成后，all.isDone=%s%n", all.isDone());
        third.complete(3);
        all.join();
        List<Integer> values = Arrays.asList(first.join(), second.join(), third.join());
        System.out.printf("allOf 结果值仍保存在输入阶段中：%s%n", values);
    }

    /**
     * 让独立线程先进入 join 等待，再由主线程完成 future 并观察唤醒结果。
     */
    static void observeJoinWaiting() {
        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch joinStarted = new CountDownLatch(1);
        AtomicReference<String> joinedValue = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            joinStarted.countDown();
            joinedValue.set(future.join());
        }, "cf-join-waiter");

        waiter.start();
        await(joinStarted);
        try {
            waitUntilBlocked(waiter, 2, TimeUnit.SECONDS);
        } finally {
            // 状态观察失败时也完成 future 并回收线程，避免调试进程被永久等待线程拖住。
            future.complete("done");
            join(waiter, 2, TimeUnit.SECONDS);
        }

        System.out.printf("join 唤醒结果=%s%n", joinedValue.get());
    }

    /**
     * 取消尚未完成的阶段，观察取消和异常完成状态。
     */
    static void observeCancellation() {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.cancel(true);

        try {
            future.join();
        } catch (CancellationException exception) {
            System.out.printf("isCancelled=%s，isCompletedExceptionally=%s%n",
                    future.isCancelled(), future.isCompletedExceptionally());
        }
    }

    /**
     * 等待闸门打开；若线程被中断，则恢复中断标记并终止实验。
     *
     * @param latch 需要等待的闸门
     */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待实验闸门时被中断", exception);
        }
    }

    /**
     * 等待目标线程进入阻塞状态，使调试时能够稳定命中 waitingGet 和 Signaller。
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
                throw new IllegalStateException("join 线程在进入等待状态前已结束");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new IllegalStateException("join 线程未在预期时间内进入等待状态");
    }

    /**
     * 等待线程结束；中断时恢复当前线程中断标记并终止实验。
     *
     * @param thread 需要等待的线程
     * @param timeout 最长等待时长
     * @param unit 等待时长单位
     */
    private static void join(Thread thread, long timeout, TimeUnit unit) {
        try {
            thread.join(unit.toMillis(timeout));
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(unit.toMillis(timeout));
            }
            if (thread.isAlive()) {
                throw new IllegalStateException("调试线程未在预期时间内结束");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            thread.interrupt();
            throw new IllegalStateException("等待实验线程时被中断", exception);
        }
    }

    /**
     * 创建带稳定名称的线程工厂，便于从控制台和调试器识别执行器边界。
     *
     * @param prefix 线程名前缀
     * @return 线程工厂
     */
    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
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
}
