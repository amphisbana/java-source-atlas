package io.github.javasourceatlas.jdk.concurrent;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用受控线程顺序演示 ThreadLocal 隔离、清理、线程池复用和继承快照的调试入口。
 */
public final class ThreadLocalDebugLab {

    private static final long WAIT_SECONDS = 5;
    private static final int GC_ATTEMPTS = 10;
    private static final long GC_OBSERVE_MILLIS = 80;

    /**
     * 工具类不需要创建实例。
     */
    private ThreadLocalDebugLab() {
    }

    /**
     * 按固定顺序运行全部 ThreadLocal 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 等待线程、任务结果或执行器关闭时抛出
     */
    public static void main(String[] args) throws Exception {
        printHeader("每线程独立初始化与 remove");
        observePerThreadValues();

        printHeader("线程池污染与 finally 清理");
        observeThreadPoolPollutionAndCleanup();

        printHeader("InheritableThreadLocal 构造时快照");
        observeInheritableSnapshot();

        printHeader("弱 key 与 stale value 的非确定性观察");
        observeWeakKeyBoundary();
    }

    /**
     * 在主线程和工作线程中读取同一个 ThreadLocal，验证初始化按线程发生且 remove 后可重新初始化。
     *
     * @throws Exception 等待工作线程结束时抛出
     */
    static void observePerThreadValues() throws Exception {
        AtomicInteger initializations = new AtomicInteger();
        ThreadLocal<String> context = ThreadLocal.withInitial(() ->
                Thread.currentThread().getName() + "-value-" + initializations.incrementAndGet());
        AtomicReference<String> workerFirst = new AtomicReference<>();
        AtomicReference<String> workerSecond = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        String mainFirst = context.get();
        String mainSecond = context.get();
        Thread worker = new Thread(captureFailure(() -> {
            try {
                workerFirst.set(context.get());
                workerSecond.set(context.get());
            } finally {
                context.remove();
            }
        }, workerFailure), "threadlocal-worker");

        worker.start();
        try {
            joinWithin(worker);
            rethrowThreadFailure(workerFailure.get());
            context.remove();
            String mainAfterRemove = context.get();

            require(mainFirst.equals(mainSecond), "主线程重复 get 不应重复初始化");
            require(workerFirst.get().equals(workerSecond.get()), "工作线程重复 get 不应重复初始化");
            require(!mainFirst.equals(workerFirst.get()), "两个线程不应共享同一绑定");
            require(!mainFirst.equals(mainAfterRemove), "remove 后再次 get 应重新初始化");

            System.out.printf("主线程=%s / %s，工作线程=%s / %s，remove 后=%s，初始化次数=%d%n",
                    mainFirst, mainSecond, workerFirst.get(), workerSecond.get(),
                    mainAfterRemove, initializations.get());
        } finally {
            context.remove();
            interruptAndJoin(worker);
        }
    }

    /**
     * 在单线程执行器中先故意遗漏 remove，再用 try/finally 清理并对比后续任务读取结果。
     *
     * @throws Exception 等待线程池任务或关闭执行器时抛出
     */
    static void observeThreadPoolPollutionAndCleanup() throws Exception {
        ThreadLocal<String> requestContext = new ThreadLocal<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "threadlocal-pool-worker"));

        try {
            String writerThread = getWithin(executor.submit(() -> {
                requestContext.set("request-A");
                // 故意不 remove，用于稳定复现同一 worker 上的跨任务污染。
                return Thread.currentThread().getName();
            }));
            String leaked = getWithin(executor.submit(requestContext::get));

            getWithin(executor.submit(() -> {
                requestContext.remove();
                return null;
            }));
            String scoped = getWithin(executor.submit(() -> {
                requestContext.set("request-C");
                try {
                    return requestContext.get();
                } finally {
                    // 任务边界必须确定性清理，不能等待弱 key 进入 stale 状态。
                    requestContext.remove();
                }
            }));
            String clean = getWithin(executor.submit(requestContext::get));

            require("request-A".equals(leaked), "复用 worker 的任务应能观察到遗漏清理的旧绑定");
            require("request-C".equals(scoped), "任务自身应读取到本次设置的上下文");
            require(clean == null, "finally remove 后的下一个任务不应读取到旧上下文");

            System.out.printf("worker=%s，未清理后的读取=%s，finally 内=%s，清理后=%s%n",
                    writerThread, leaked, scoped, clean);
        } finally {
            try {
                clearOnWorker(executor, requestContext);
            } finally {
                shutdownNowAndAwait(executor);
            }
        }
    }

    /**
     * 先构造子线程再修改父值，验证继承发生在 new Thread 期间而不是 start 期间。
     *
     * @throws Exception 等待子线程结束时抛出
     */
    static void observeInheritableSnapshot() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        AtomicReference<String> childInitial = new AtomicReference<>();
        AtomicReference<String> childAfterSet = new AtomicReference<>();
        AtomicReference<Throwable> childFailure = new AtomicReference<>();

        inherited.set("parent-v1");
        Thread child = new Thread(captureFailure(() -> {
            try {
                childInitial.set(inherited.get());
                inherited.set("child-value");
                childAfterSet.set(inherited.get());
            } finally {
                inherited.remove();
            }
        }, childFailure), "threadlocal-child");

        // Thread.init 已在构造 child 时复制 inheritableThreadLocals；随后修改父值不会刷新 child 快照。
        inherited.set("parent-v2");
        child.start();
        try {
            joinWithin(child);
            rethrowThreadFailure(childFailure.get());

            require("parent-v1".equals(childInitial.get()), "子线程应读取构造时的 parent-v1 快照");
            require("child-value".equals(childAfterSet.get()), "子线程应能独立替换自己的 Entry");
            require("parent-v2".equals(inherited.get()), "子线程修改不应替换父线程 Entry");

            System.out.printf("child 初值=%s，child 修改后=%s，parent 当前值=%s%n",
                    childInitial.get(), childAfterSet.get(), inherited.get());
        } finally {
            inherited.remove();
            interruptAndJoin(child);
        }
    }

    /**
     * 让存活 worker 持有 ThreadLocalMap，只有限请求 GC 并打印弱 key 与 value 的本次可达状态。
     *
     * @throws Exception 等待探针安装、工作任务结束或执行器关闭时抛出
     */
    static void observeWeakKeyBoundary() throws Exception {
        ProbeReferences references = new ProbeReferences();
        CountDownLatch installed = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "threadlocal-stale-worker"));
        Future<?> worker = executor.submit(() -> {
            installWeakKeyProbe(references);
            installed.countDown();
            awaitGate(releaseWorker);
            return null;
        });

        try {
            require(installed.await(WAIT_SECONDS, TimeUnit.SECONDS), "弱 key 探针未按时安装");
            require(references.key != null && references.value != null, "弱引用探针未正确发布");

            int attempts = 0;
            while (attempts < GC_ATTEMPTS && references.key.get() != null) {
                attempts++;
                System.gc();
                TimeUnit.MILLISECONDS.sleep(GC_OBSERVE_MILLIS);
            }

            boolean keyCollected = references.key.get() == null;
            boolean valueReachableWhileWorkerLives = references.value.get() != null;
            System.out.printf("GC 请求次数=%d，key 本次已清空=%s，worker 存活时 value 本次仍可达=%s%n",
                    attempts, keyCollected, valueReachableWhileWorkerLives);
            System.out.println("说明：System.gc() 只是建议，上述 key 结果不作为实验通过条件。");
        } finally {
            releaseWorker.countDown();
            try {
                getWithin(worker);
            } finally {
                shutdownNowAndAwait(executor);
            }
        }
    }

    /**
     * 在独立栈帧中创建 ThreadLocal 和较大 value，只向观察者发布弱引用后立即返回。
     *
     * @param references 弱 key 与弱 value 的发布容器
     */
    private static void installWeakKeyProbe(ProbeReferences references) {
        ThreadLocal<byte[]> local = new ThreadLocal<>();
        byte[] value = new byte[512 * 1024];
        value[0] = 42;
        local.set(value);
        references.key = new WeakReference<>(local);
        references.value = new WeakReference<>(value);
    }

    /**
     * 在目标 worker 上删除残留绑定，确保实验异常退出时也不会把上下文留给后续任务。
     *
     * @param executor 单线程执行器
     * @param threadLocal 待清理的 ThreadLocal
     * @throws Exception 等待清理任务时抛出
     */
    private static void clearOnWorker(ExecutorService executor, ThreadLocal<?> threadLocal) throws Exception {
        getWithin(executor.submit(() -> {
            threadLocal.remove();
            return null;
        }));
    }

    /**
     * 在限定时间内取得 Future 结果，避免调试实验因任务异常永久阻塞。
     *
     * @param future 待读取的异步结果
     * @param <T> 结果类型
     * @return Future 发布的结果
     * @throws Exception 等待超时、中断或任务失败时抛出
     */
    private static <T> T getWithin(Future<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 把线程动作中的失败保存到共享引用，避免异常只停留在线程未捕获输出中。
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
     * 在限定时间内等待闸门，超时或中断时终止当前工作任务。
     *
     * @param gate 需要等待的闸门
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            require(gate.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待实验闸门超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待实验闸门时被中断", exception);
        }
    }

    /**
     * 在截止时间内等待线程退出，超时后抛出失败而不是永久挂起。
     *
     * @param thread 待等待线程
     * @throws InterruptedException 当前线程等待时被中断
     */
    private static void joinWithin(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        require(!thread.isAlive(), "线程未在截止时间内退出: " + thread.getName());
    }

    /**
     * 中断仍存活的实验线程并在截止时间内完成回收。
     *
     * @param thread 待回收线程
     * @throws InterruptedException 当前线程等待时被中断
     */
    private static void interruptAndJoin(Thread thread) throws InterruptedException {
        if (thread.isAlive()) {
            thread.interrupt();
            joinWithin(thread);
        }
    }

    /**
     * 关闭执行器并确认 worker 在截止时间内退出。
     *
     * @param executor 待关闭执行器
     * @throws InterruptedException 当前线程等待关闭时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        require(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "执行器未在截止时间内退出");
    }

    /**
     * 重新抛出工作线程失败，使命令行实验以失败状态结束。
     *
     * @param failure 工作线程捕获的失败
     */
    private static void rethrowThreadFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("实验线程执行失败", failure);
    }

    /**
     * 校验实验前置条件或可观察结果，不满足时立即终止当前场景。
     *
     * @param condition 必须成立的条件
     * @param message 失败说明
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 输出单个实验标题，便于在命令行区分观察场景。
     *
     * @param title 实验标题
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    /**
     * 保存探针对象的弱引用，避免主线程本身阻止 key 或 value 被回收。
     */
    private static final class ProbeReferences {
        private WeakReference<ThreadLocal<byte[]>> key;
        private WeakReference<byte[]> value;

        /**
         * 仅由外部实验类创建空容器，字段在线程闸门发布后读取。
         */
        private ProbeReferences() {
        }
    }
}
