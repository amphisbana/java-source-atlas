package io.github.javasourceatlas.jdk.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 用公开 API 稳定触发 AtomicInteger 与 LongAdder 核心行为的调试入口。
 */
public final class AtomicDebugLab {

    private static final int THREAD_COUNT = 4;
    private static final int INCREMENTS_PER_THREAD = 1_000;
    private static final long WAIT_SECONDS = 5;

    /**
     * 工具类不需要创建实例。
     */
    private AtomicDebugLab() {
    }

    /**
     * 按固定顺序运行全部 AtomicInteger 与 LongAdder 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws InterruptedException 等待并发任务时被中断
     */
    public static void main(String[] args) throws InterruptedException {
        printHeader("非原子累加与原子累加");
        observeAtomicCounterContrast();

        printHeader("CAS 成功与失败");
        observeCompareAndSet();

        printHeader("updateAndGet 函数重试");
        observeUpdateFunctionRetry();

        printHeader("LongAdder 并发累加");
        observeLongAdderSum();

        printHeader("sumThenReset 窗口边界");
        observeSumThenResetBoundary();
    }

    /**
     * 让两个线程先读取同一个旧值，再分别写回，稳定展示非原子更新丢失和原子累加的差异。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    static void observeAtomicCounterContrast() throws InterruptedException {
        int[] plainCounter = {0};
        AtomicInteger atomicCounter = new AtomicInteger();
        CountDownLatch snapshotsRead = new CountDownLatch(2);

        runConcurrently(2, () -> {
            // 写回前强制两个线程都读取到 0，使丢失更新成为受控结果，而不是依赖偶发竞态。
            int snapshot = plainCounter[0];
            snapshotsRead.countDown();
            awaitGate(snapshotsRead, "两个非原子计数线程未按时读取旧值");
            plainCounter[0] = snapshot + 1;
            atomicCounter.incrementAndGet();
        });

        System.out.printf("非原子计数=%d，AtomicInteger=%d，逻辑更新次数=2%n",
                plainCounter[0], atomicCounter.get());
    }

    /**
     * 使用相同原子变量演示预期值匹配时 CAS 成功、预期值过期时 CAS 失败。
     */
    static void observeCompareAndSet() {
        AtomicInteger state = new AtomicInteger(10);

        boolean success = state.compareAndSet(10, 11);
        boolean failure = state.compareAndSet(10, 12);

        System.out.printf("首次 CAS=%s，旧预期值再次 CAS=%s，最终值=%d%n",
                success, failure, state.get());
    }

    /**
     * 在 updateAndGet 第一次计算后插入一次竞争更新，稳定观察更新函数被重新执行。
     *
     * @throws InterruptedException 等待更新线程时被中断
     */
    static void observeUpdateFunctionRetry() throws InterruptedException {
        AtomicInteger value = new AtomicInteger();
        AtomicInteger functionCalls = new AtomicInteger();
        AtomicInteger returnedValue = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        CountDownLatch firstFunctionCall = new CountDownLatch(1);
        CountDownLatch competingUpdateDone = new CountDownLatch(1);
        Thread updater = new Thread(() -> {
            try {
                returnedValue.set(value.updateAndGet(current -> {
                    int call = functionCalls.incrementAndGet();
                    if (call == 1) {
                        // 第一次函数计算先暂停，让主线程改变当前值，随后 CAS 必然因预期值过期而失败。
                        firstFunctionCall.countDown();
                        awaitGate(competingUpdateDone, "竞争更新未按时完成");
                    }
                    return current + 1;
                }));
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "atomic-update-retry");

        updater.start();
        try {
            awaitGate(firstFunctionCall, "更新函数未按时开始第一次计算");
            if (!value.compareAndSet(0, 1)) {
                throw new IllegalStateException("未能在第一次函数计算后插入竞争更新");
            }
        } finally {
            // 前置步骤失败时也必须放行并回收更新线程，避免调试进程残留等待线程。
            competingUpdateDone.countDown();
            joinThread(updater);
        }

        if (workerFailure.get() != null) {
            throw new IllegalStateException("updateAndGet 更新线程执行失败", workerFailure.get());
        }
        System.out.printf("函数调用次数=%d，updateAndGet 返回值=%d，最终值=%d%n",
                functionCalls.get(), returnedValue.get(), value.get());
    }

    /**
     * 让多个线程并发写入 LongAdder，并在写线程结束后的静默期读取精确总和。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    static void observeLongAdderSum() throws InterruptedException {
        LongAdder adder = new LongAdder();

        runConcurrently(THREAD_COUNT, () -> {
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
                adder.increment();
            }
        });

        System.out.printf("LongAdder 总和=%d，预期=%d%n",
                adder.sum(), THREAD_COUNT * INCREMENTS_PER_THREAD);
    }

    /**
     * 在没有并发写入的窗口边界调用 sumThenReset，观察已完成窗口和后续窗口相互独立。
     */
    static void observeSumThenResetBoundary() {
        LongAdder adder = new LongAdder();
        adder.add(7);

        // sumThenReset 与并发 add 重叠时不承诺原子快照，因此这里只演示静默期的精确窗口切分。
        long completedWindow = adder.sumThenReset();
        long afterReset = adder.sum();
        adder.add(3);

        System.out.printf("已完成窗口=%d，重置后=%d，下一窗口=%d%n",
                completedWindow, afterReset, adder.sum());
    }

    /**
     * 同时释放固定数量的工作线程，并等待全部动作完成。
     *
     * @param threadCount 并发线程数
     * @param action      每个线程执行的动作
     * @throws InterruptedException 等待线程或执行器终止时被中断
     */
    private static void runConcurrently(int threadCount, Runnable action) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(
                threadCount, namedThreadFactory("atomic-worker"));
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        awaitGate(start, "并发任务未按时开始");
                        action.run();
                    } catch (Throwable failure) {
                        workerFailure.compareAndSet(null, failure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            awaitGate(ready, "并发任务未按时就绪");
            start.countDown();
            awaitGate(done, "并发任务未按时完成");
            if (workerFailure.get() != null) {
                throw new IllegalStateException("并发实验线程执行失败", workerFailure.get());
            }
        } finally {
            start.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 在限定时间内等待闸门打开，避免调试实验因线程协作异常永久挂起。
     *
     * @param gate         需要等待的闸门
     * @param timeoutError 等待超时后的错误信息
     */
    private static void awaitGate(CountDownLatch gate, String timeoutError) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(timeoutError);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待实验闸门时被中断", exception);
        }
    }

    /**
     * 在限定时间内等待独立线程结束，超时后先中断再报告实验失败。
     *
     * @param thread 需要回收的线程
     * @throws InterruptedException 等待线程时被中断
     */
    private static void joinThread(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        if (thread.isAlive()) {
            throw new IllegalStateException("调试线程未在预期时间内结束");
        }
    }

    /**
     * 立即停止执行器并等待工作线程退出。
     *
     * @param executor 需要关闭的执行器
     * @throws InterruptedException 等待执行器终止时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发实验执行器未在预期时间内终止");
        }
    }

    /**
     * 创建带稳定名称的线程工厂，便于从控制台和调试器识别工作线程。
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
