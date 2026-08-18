package io.github.javasourceatlas.jdk.reference;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 使用公开 API 观察 ReferenceQueue 与 WeakHashMap 生命周期边界的调试入口。
 */
public final class ReferenceWeakHashMapDebugLab {

    private static final long GC_OBSERVATION_MILLIS = 1_500L;
    private static final int PRESSURE_BYTES = 64 * 1024;

    /**
     * 工具类不需要创建实例。
     */
    private ReferenceWeakHashMapDebugLab() {
    }

    /**
     * 按固定顺序运行全部引用与弱键调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws InterruptedException 等待 ReferenceQueue 时被中断
     */
    public static void main(String[] args) throws InterruptedException {
        printHeader("clear 与 enqueue 是两个动作");
        observeExplicitQueueProtocol();

        printHeader("WeakHashMap 弱键与惰性 expunge");
        observeWeakKeyCollection();

        printHeader("value 回指 key 的强可达陷阱");
        observeValueBackReference();

        printHeader("null key 哨兵边界");
        observeNullKeyBoundary();

        printHeader("SoftReference 策略边界");
        observeSoftReferenceBoundary();

        printHeader("PhantomReference 队列通知");
        observePhantomReference();
    }

    /**
     * 验证 clear 只清 referent，而显式 enqueue 会同时清 referent 并加入注册队列。
     *
     * @throws InterruptedException 等待队列元素时被中断
     */
    static void observeExplicitQueueProtocol() throws InterruptedException {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Object clearReferent = new Object();
        WeakReference<Object> clearOnly = new WeakReference<>(clearReferent, queue);

        clearOnly.clear();
        // 保留强引用到 clear 返回后，确保队列状态只反映显式 clear 的行为。
        boolean clearReferentKeptAlive = clearReferent != null;
        boolean emptyAfterClear = queue.poll() == null;

        Object directReferent = new Object();
        WeakReference<Object> directEnqueue = new WeakReference<>(directReferent, queue);
        boolean presentBeforeEnqueue = directEnqueue.get() == directReferent;
        boolean enqueued = directEnqueue.enqueue();
        // 保留强引用到 enqueue 返回后，确保入队前状态不是由 GC 抢先改变。
        boolean directReferentKeptAlive = directReferent != null;
        boolean clearedByEnqueue = directEnqueue.get() == null;
        boolean sameReference = queue.remove(1_000L) == directEnqueue;

        System.out.printf("clear 强引用保活=%s、队列为空=%s；enqueue 前 referent=%s，强引用保活=%s，enqueue 后已清除=%s，入队=%s，取回同一引用=%s%n",
                clearReferentKeptAlive, emptyAfterClear, presentBeforeEnqueue, directReferentKeptAlive,
                clearedByEnqueue, enqueued, sameReference);
    }

    /**
     * 有界请求 GC，观察弱 key 本次是否被清除，并通过 size 触发 WeakHashMap 清理队列。
     * 本场景只打印观察结果，不把 System.gc 的执行时机作为通过条件。
     */
    static void observeWeakKeyCollection() {
        Map<Object, String> map = new WeakHashMap<>();
        Object key = new Object();
        WeakReference<Object> keyReference = new WeakReference<>(key);
        map.put(key, "metadata");
        key = null;

        boolean collected = awaitCollected(keyReference, GC_OBSERVATION_MILLIS);
        int sizeAfterAccess = map.size();

        System.out.printf("本次观察 key 已清除=%s，触发 map.size() 后 size=%d%n",
                collected, sizeAfterAccess);
        if (!collected) {
            System.out.println("说明：System.gc() 只是建议，未在窗口内清除完全符合公开契约。");
        } else if (sizeAfterAccess != 0) {
            System.out.println("说明：referent 已清除不等于 Entry 已入队；当前仍处在入队与 expunge 之间的合法窗口。");
        }
    }

    /**
     * 建立 map -> value -> key 强路径，证明弱 Entry 不能打破 value 的反向强引用。
     */
    static void observeValueBackReference() {
        Map<Object, OwnerMetadata> map = new WeakHashMap<>();
        Object key = new Object();
        WeakReference<Object> keyReference = new WeakReference<>(key);
        map.put(key, new OwnerMetadata(key));
        key = null;

        System.gc();
        // 请求 GC 时，外部局部变量已释放，只剩 map -> value -> key 的强可达路径。
        Object ownerFromValue = map.values().iterator().next().getOwner();
        System.out.printf("value 回指后 weakReference 仍可见=%s，owner 身份一致=%s%n",
                keyReference.get() != null, ownerFromValue == keyReference.get());
    }

    /**
     * 验证 WeakHashMap 公开支持 null key 与 null value，null key 不表示 stale Entry。
     */
    static void observeNullKeyBoundary() {
        Map<Object, Object> map = new WeakHashMap<>();
        map.put(null, null);
        System.gc();

        System.out.printf("contains null key=%s，null value=%s，size=%d%n",
                map.containsKey(null), map.get(null), map.size());
    }

    /**
     * 只展示 SoftReference 当前可读，不假定内存压力下的具体清除时刻。
     */
    static void observeSoftReferenceBoundary() {
        SoftReference<byte[]> reference = new SoftReference<>(new byte[1_024]);
        System.out.printf("创建后 soft referent 可见=%s；不把本次 GC 结果当缓存策略%n",
                reference.get() != null);
    }

    /**
     * 验证 PhantomReference 从创建起就不暴露 referent，并有限等待本次 GC 队列通知。
     *
     * @throws InterruptedException 等待 ReferenceQueue 时被中断
     */
    static void observePhantomReference() throws InterruptedException {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Object referent = new Object();
        PhantomReference<Object> phantom = new PhantomReference<>(referent, queue);
        boolean alwaysNull = phantom.get() == null;
        referent = null;

        boolean enqueued = awaitEnqueued(queue, phantom, GC_OBSERVATION_MILLIS);
        System.out.printf("phantom.get 始终为 null=%s，本次观察已入队=%s%n",
                alwaysNull, enqueued);
        if (!enqueued) {
            System.out.println("说明：未在有限窗口收到通知不代表 PhantomReference 协议失效。");
        }
    }

    /**
     * 在有限时间内请求收集，并制造轻量分配压力帮助演示；返回值只供输出观察。
     *
     * @param reference 被观察的弱引用
     * @param timeoutMillis 最长观察时间
     * @return referent 是否在窗口内变为 null
     */
    private static boolean awaitCollected(WeakReference<?> reference, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        int pressureChecksum = 0;
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            byte[] pressure = new byte[PRESSURE_BYTES];
            pressure[0] = 1;
            pressureChecksum += pressure[0];
            Thread.yield();
        }
        // 使用校验和阻止演示分配被简单视为无用代码，同时不让临时数组逃逸到循环外。
        if (pressureChecksum < 0) {
            throw new AssertionError("不可达的分配校验分支");
        }
        return reference.get() == null;
    }

    /**
     * 在有限时间内等待目标 Reference 入队；GC 只作为演示建议，不保证成功。
     *
     * @param queue 注册队列
     * @param expected 期望收到的 Reference
     * @param timeoutMillis 最长等待时间
     * @return 是否在窗口内收到同一个 Reference
     * @throws InterruptedException 等待队列时被中断
     */
    private static boolean awaitEnqueued(ReferenceQueue<Object> queue,
                                         Reference<Object> expected,
                                         long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            System.gc();
            Reference<?> queued = queue.remove(50L);
            if (queued == expected) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打印实验分段标题。
     *
     * @param title 标题文本
     */
    private static void printHeader(String title) {
        System.out.printf("%n=== %s ===%n", title);
    }

    /**
     * 模拟 WeakHashMap value 反向保存 owner key 的错误元数据结构。
     */
    private static final class OwnerMetadata {
        private final Object owner;

        /**
         * 保存 owner 强引用。
         *
         * @param owner 被回指的 key
         */
        private OwnerMetadata(Object owner) {
            this.owner = owner;
        }

        /**
         * 返回当前强引用的 owner。
         *
         * @return owner key
         */
        private Object getOwner() {
            return owner;
        }
    }
}
