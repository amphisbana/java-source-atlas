package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ThreadLocal 专题依赖的线程隔离、生命周期和继承公开行为。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ThreadLocalBehaviorTest {

    private static final long WAIT_SECONDS = 5;

    /**
     * 验证两个并发线程可以通过同一个 ThreadLocal 保存并读取各自的值。
     *
     * @throws Exception 等待并发任务或关闭执行器时抛出
     */
    @Test
    void shouldIsolateValuesBetweenThreads() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        CountDownLatch bothSet = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> readOwnValue(context, "thread-A", bothSet));
            Future<String> second = executor.submit(() -> readOwnValue(context, "thread-B", bothSet));

            assertEquals("thread-A", getWithin(first));
            assertEquals("thread-B", getWithin(second));
            assertNull(context.get());
        } finally {
            context.remove();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 withInitial 在同一线程首次 get 时只调用一次，remove 后再次 get 会重新初始化。
     *
     * @throws Exception 等待工作任务或关闭执行器时抛出
     */
    @Test
    void shouldInitializeOncePerBindingAndAgainAfterRemove() throws Exception {
        AtomicInteger initializations = new AtomicInteger();
        ThreadLocal<Integer> sequence = ThreadLocal.withInitial(initializations::incrementAndGet);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<List<Integer>> observations = executor.submit(() -> {
                try {
                    int first = sequence.get();
                    int second = sequence.get();
                    sequence.remove();
                    int afterRemove = sequence.get();
                    return Arrays.asList(first, second, afterRemove);
                } finally {
                    sequence.remove();
                }
            });

            assertEquals(Arrays.asList(1, 1, 2), getWithin(observations));
            assertEquals(2, initializations.get());
        } finally {
            clearWorkerAndShutdown(executor, sequence);
        }
    }

    /**
     * 验证单线程池任务遗漏 remove 后，下一个未设置上下文的任务会读取到旧值。
     *
     * @throws Exception 等待线程池任务或关闭执行器时抛出
     */
    @Test
    void shouldLeakBindingAcrossReusedWorkerWhenRemoveIsMissing() throws Exception {
        ThreadLocal<String> requestContext = new ThreadLocal<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            getWithin(executor.submit(() -> {
                requestContext.set("request-A");
                // 故意保留旧绑定，用于验证 worker 复用导致的跨任务污染。
                return null;
            }));

            assertEquals("request-A", getWithin(executor.submit(requestContext::get)));
        } finally {
            clearWorkerAndShutdown(executor, requestContext);
        }
    }

    /**
     * 验证任务使用 try/finally remove 后，复用同一 worker 的后续任务读取不到旧值。
     *
     * @throws Exception 等待线程池任务或关闭执行器时抛出
     */
    @Test
    void shouldPreventWorkerPollutionWithFinallyRemove() throws Exception {
        ThreadLocal<String> requestContext = new ThreadLocal<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            assertEquals("request-B", getWithin(executor.submit(() -> {
                requestContext.set("request-B");
                try {
                    return requestContext.get();
                } finally {
                    requestContext.remove();
                }
            })));

            assertNull(getWithin(executor.submit(requestContext::get)));
        } finally {
            clearWorkerAndShutdown(executor, requestContext);
        }
    }

    /**
     * 验证普通 ThreadLocal 不会把父线程已有绑定复制给新构造的子线程。
     *
     * @throws Exception 等待子线程结束时抛出
     */
    @Test
    void shouldNotInheritPlainThreadLocalValue() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        AtomicReference<String> childValue = new AtomicReference<>();
        AtomicBoolean childRan = new AtomicBoolean();
        Thread child;

        context.set("parent-value");
        child = new Thread(() -> {
            try {
                childValue.set(context.get());
                childRan.set(true);
            } finally {
                context.remove();
            }
        }, "plain-threadlocal-child");

        child.start();
        try {
            joinWithin(child);
            assertTrue(childRan.get());
            assertNull(childValue.get());
            assertEquals("parent-value", context.get());
        } finally {
            context.remove();
            interruptAndJoin(child);
        }
    }

    /**
     * 验证 InheritableThreadLocal 在 Thread 构造时复制父值，父线程随后修改不会刷新子快照。
     *
     * @throws Exception 等待子线程结束时抛出
     */
    @Test
    void shouldSnapshotInheritableValueWhenThreadIsConstructed() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        AtomicReference<String> childValue = new AtomicReference<>();

        inherited.set("parent-v1");
        Thread child = new Thread(() -> {
            try {
                childValue.set(inherited.get());
            } finally {
                inherited.remove();
            }
        }, "inheritable-snapshot-child");
        inherited.set("parent-v2");

        child.start();
        try {
            joinWithin(child);
            assertEquals("parent-v1", childValue.get());
            assertEquals("parent-v2", inherited.get());
        } finally {
            inherited.remove();
            interruptAndJoin(child);
        }
    }

    /**
     * 验证默认 childValue 只复制引用，父子线程会看到同一个可变对象。
     *
     * @throws Exception 等待子线程结束时抛出
     */
    @Test
    void shouldShareMutableReferenceWithDefaultChildValue() throws Exception {
        InheritableThreadLocal<List<String>> inherited = new InheritableThreadLocal<>();
        List<String> parentList = new ArrayList<>();
        AtomicReference<List<String>> childReference = new AtomicReference<>();

        parentList.add("parent");
        inherited.set(parentList);
        Thread child = new Thread(() -> {
            try {
                childReference.set(inherited.get());
                inherited.get().add("child");
            } finally {
                inherited.remove();
            }
        }, "inheritable-reference-child");

        child.start();
        try {
            joinWithin(child);
            assertSame(parentList, childReference.get());
            assertEquals(Arrays.asList("parent", "child"), parentList);
        } finally {
            inherited.remove();
            interruptAndJoin(child);
        }
    }

    /**
     * 验证线程池 worker 只在自身构造时继承一次，父线程后续 submit 不会刷新其绑定。
     *
     * @throws Exception 等待线程池任务或关闭执行器时抛出
     */
    @Test
    void shouldNotRefreshInheritedValueForLaterPoolSubmissions() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> firstWorker = new AtomicReference<>();
        AtomicReference<String> secondWorker = new AtomicReference<>();

        inherited.set("parent-v1");
        try {
            String first = getWithin(executor.submit(() -> {
                firstWorker.set(Thread.currentThread().getName());
                return inherited.get();
            }));

            inherited.set("parent-v2");
            String second = getWithin(executor.submit(() -> {
                secondWorker.set(Thread.currentThread().getName());
                return inherited.get();
            }));

            assertEquals(firstWorker.get(), secondWorker.get());
            assertEquals("parent-v1", first);
            assertEquals("parent-v1", second);
            assertEquals("parent-v2", inherited.get());
        } finally {
            inherited.remove();
            clearWorkerAndShutdown(executor, inherited);
        }
    }

    /**
     * 在线程任务中设置并读取自己的值，读取完成后确定性删除绑定。
     *
     * @param context 两个线程共享的 ThreadLocal key
     * @param value 当前线程要保存的值
     * @param bothSet 等待两个线程都完成 set 的闸门
     * @return 当前线程从 ThreadLocal 读回的值
     */
    private static String readOwnValue(ThreadLocal<String> context, String value, CountDownLatch bothSet) {
        context.set(value);
        bothSet.countDown();
        try {
            awaitGate(bothSet);
            return context.get();
        } finally {
            context.remove();
        }
    }

    /**
     * 在目标 worker 上执行 remove，保证测试结束后不遗留线程局部绑定。
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
     * 先尝试清理 worker 绑定，并保证无论清理任务是否成功都关闭执行器。
     *
     * @param executor 单线程执行器
     * @param threadLocal 待清理的 ThreadLocal
     * @throws Exception 清理任务失败或执行器未按时关闭时抛出
     */
    private static void clearWorkerAndShutdown(ExecutorService executor, ThreadLocal<?> threadLocal)
            throws Exception {
        try {
            clearOnWorker(executor, threadLocal);
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 在限定时间内取得 Future 结果，避免失败测试永久挂起。
     *
     * @param future 待读取的任务
     * @param <T> 结果类型
     * @return Future 发布的结果
     * @throws Exception 等待超时、中断或任务失败时抛出
     */
    private static <T> T getWithin(Future<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 在限定时间内等待测试闸门，超时或中断时终止当前工作任务。
     *
     * @param gate 需要等待的闸门
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            assertTrue(gate.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待测试闸门超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待测试闸门时被中断", exception);
        }
    }

    /**
     * 在截止时间内等待线程退出，并断言没有遗留工作线程。
     *
     * @param thread 待等待线程
     * @throws InterruptedException 当前线程等待时被中断
     */
    private static void joinWithin(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertFalse(thread.isAlive(), "线程未在截止时间内退出: " + thread.getName());
    }

    /**
     * 中断仍存活的测试线程并在截止时间内完成回收。
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
     * 关闭执行器并断言其 worker 在截止时间内退出。
     *
     * @param executor 待关闭执行器
     * @throws InterruptedException 当前线程等待关闭时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "执行器未在截止时间内退出");
    }
}
