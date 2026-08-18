package io.github.javasourceatlas.jdk.concurrent;

import io.github.javasourceatlas.jdk.collection.CollisionKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用公开 API 稳定触发 ConcurrentHashMap 核心源码分支的调试入口。
 */
public final class ConcurrentHashMapDebugLab {

    private static final int THREAD_COUNT = 4;
    private static final int INCREMENTS_PER_THREAD = 500;
    private static final int RESIZE_INITIAL_CAPACITY = 1 << 14;
    private static final int RESIZE_TABLE_CAPACITY = 1 << 15;
    private static final int RESIZE_THRESHOLD = RESIZE_TABLE_CAPACITY - (RESIZE_TABLE_CAPACITY >>> 2);
    private static final int RESIZE_ADDITIONS = 1 << 12;
    private static final int RESIZE_OBSERVER_ROUNDS = 1 << 17;
    private static final int RESIZE_GUARD_KEY = RESIZE_TABLE_CAPACITY - 2;
    private static final int RESIZE_OBSERVATION_KEY = RESIZE_TABLE_CAPACITY - 1;
    private static final int RESIZE_ADDITION_KEY_BASE = 1 << 20;
    private static final int TREE_BIN_KEY_COUNT = 12;
    private static final int COLLISION_HASH = 7;
    private static final int CONTROL_READY_TIMEOUT_SECONDS = 5;
    private static final int DEBUG_COMPLETION_TIMEOUT_SECONDS = 300;

    /**
     * 工具类不需要创建实例。
     */
    private ConcurrentHashMapDebugLab() {
    }

    /**
     * 按固定顺序运行全部 ConcurrentHashMap 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws InterruptedException 等待并发任务时被中断
     */
    public static void main(String[] args) throws InterruptedException {
        printHeader("空桶与碰撞桶写入");
        observePutPaths();

        printHeader("受控扩容、ForwardingNode 与遍历跨表");
        observeResizeAndForwardingNode();

        printHeader("TreeBin 查找与插入");
        observeTreeBinPaths();

        printHeader("并发 merge");
        observeAtomicMerge();

        printHeader("computeIfAbsent 单键计算");
        observeComputeIfAbsent();

        printHeader("null 边界");
        observeNullBoundary();
    }

    /**
     * 写入两个哈希相同的键，依次触发空桶 CAS 和碰撞桶更新路径。
     */
    static void observePutPaths() {
        Map<CollisionKey, String> map = new ConcurrentHashMap<>();
        CollisionKey first = new CollisionKey(1, 7);
        CollisionKey second = new CollisionKey(2, 7);
        map.put(first, "first");
        map.put(second, "second");

        System.out.printf("碰撞后 size=%d，first=%s，second=%s%n",
                map.size(), map.get(first), map.get(second));
    }

    /**
     * 把大表预热到 JDK 8 扩容阈值前一位，用公开 compute API 暂时占住高区相邻桶，
     * 让扩容线程在迁移观察桶后确定停下，再运行读、写和遍历观察。
     * 该场景适合在 transfer、helpTransfer、ForwardingNode.find 与 Traverser.advance
     * 上设置断点，不依赖对私有字段的反射。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    static void observeResizeAndForwardingNode() throws InterruptedException {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(RESIZE_INITIAL_CAPACITY);
        int prefilledMappings = RESIZE_THRESHOLD - 1;
        prefillResizeMap(map, prefilledMappings);

        AtomicInteger readMisses = new AtomicInteger();
        AtomicInteger traversedMappings = new AtomicInteger();
        runWithControlledResizeWindow(map, readMisses, traversedMappings);

        int expectedMappings = prefilledMappings + RESIZE_ADDITIONS;
        int missingMappings = countMissingMappings(map, expectedMappings);
        System.out.printf("扩容后 mappingCount=%d，预期=%d，读缺失=%d，最终缺失=%d，遍历看到=%d%n",
                map.mappingCount(), expectedMappings, readMisses.get(), missingMappings,
                traversedMappings.get());
    }

    /**
     * 在容量已满足树化条件的 Map 中写入固定哈希键，稳定触发 TreeBin 的查找与插入路径。
     */
    static void observeTreeBinPaths() {
        ConcurrentHashMap<CollisionKey, String> map = new ConcurrentHashMap<>(64);
        for (int id = 0; id < TREE_BIN_KEY_COUNT; id++) {
            map.put(new CollisionKey(id, COLLISION_HASH), "value-" + id);
        }

        String found = map.get(new CollisionKey(5, COLLISION_HASH));
        String previous = map.put(new CollisionKey(5, COLLISION_HASH), "updated-5");
        map.put(new CollisionKey(TREE_BIN_KEY_COUNT, COLLISION_HASH), "tree-new");

        System.out.printf("树桶 size=%d，find=%s，putTreeVal 旧值=%s，新值=%s%n",
                map.size(), found, previous,
                map.get(new CollisionKey(TREE_BIN_KEY_COUNT, COLLISION_HASH)));
    }

    /**
     * 让多个线程使用 merge 原子累加同一个键。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    static void observeAtomicMerge() throws InterruptedException {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        runConcurrently(THREAD_COUNT, () -> {
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
                counts.merge("total", 1, Integer::sum);
            }
        });

        System.out.printf("merge 最终计数=%d，预期=%d%n",
                counts.get("total"), THREAD_COUNT * INCREMENTS_PER_THREAD);
    }

    /**
     * 让多个线程请求同一个缺失键，观察映射函数只创建一次值。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    static void observeComputeIfAbsent() throws InterruptedException {
        Map<String, Object> map = new ConcurrentHashMap<>();
        AtomicInteger mappingCalls = new AtomicInteger();
        runConcurrently(THREAD_COUNT, () -> map.computeIfAbsent("shared", key -> {
            mappingCalls.incrementAndGet();
            return new Object();
        }));

        System.out.printf("映射函数调用次数=%d，size=%d%n", mappingCalls.get(), map.size());
    }

    /**
     * 验证 ConcurrentHashMap 明确禁止空键和空值。
     */
    static void observeNullBoundary() {
        Map<String, String> map = new ConcurrentHashMap<>();
        boolean nullKeyRejected = isNullPointer(() -> map.put(null, "value"));
        boolean nullValueRejected = isNullPointer(() -> map.put("key", null));

        System.out.printf("拒绝 null key=%s，拒绝 null value=%s%n",
                nullKeyRejected, nullValueRejected);
    }

    /**
     * 预热普通低区键和两个相邻高区键，使 Map 恰好停在扩容阈值前一位。
     *
     * @param map               待预热的 Map
     * @param prefilledMappings 目标映射数量
     */
    private static void prefillResizeMap(ConcurrentHashMap<Integer, Integer> map,
                                         int prefilledMappings) {
        int regularMappings = prefilledMappings - 2;
        for (int key = 0; key < regularMappings; key++) {
            map.put(key, key);
        }
        map.put(RESIZE_GUARD_KEY, RESIZE_GUARD_KEY);
        map.put(RESIZE_OBSERVATION_KEY, RESIZE_OBSERVATION_KEY);
    }

    /**
     * 占住 table[32766] 后启动扩容，等待迁移者阻塞，再在稳定窗口中运行三个观察动作。
     * table[32767] 位于它前一个扫描位置，此时已经安装 ForwardingNode；尚未领取的低区
     * 又允许写线程在 helpTransfer 中登记并真正领取迁移任务。
     *
     * @param map               被观察的 Map
     * @param readMisses        读取缺失次数
     * @param traversedMappings 遍历到的映射次数
     * @throws InterruptedException 等待控制线程时被中断
     */
    private static void runWithControlledResizeWindow(ConcurrentHashMap<Integer, Integer> map,
                                                      AtomicInteger readMisses,
                                                      AtomicInteger traversedMappings)
            throws InterruptedException {
        ExecutorService controlExecutor = Executors.newFixedThreadPool(2);
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        CountDownLatch resizeOwnerStarted = new CountDownLatch(1);
        CountDownLatch resizeOwnerFinished = new CountDownLatch(1);
        AtomicReference<Thread> resizeOwner = new AtomicReference<>();
        AtomicReference<Throwable> controlFailure = new AtomicReference<>();
        boolean resizeOwnerSubmitted = false;
        try {
            controlExecutor.execute(() -> {
                try {
                    holdTransferGuard(map, guardEntered, releaseGuard);
                } catch (RuntimeException | Error exception) {
                    controlFailure.compareAndSet(null, exception);
                }
            });
            if (!guardEntered.await(CONTROL_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("扩容阻塞桶未按时就绪");
            }

            controlExecutor.execute(() -> {
                resizeOwner.set(Thread.currentThread());
                resizeOwnerStarted.countDown();
                try {
                    addResizeMappings(map);
                } catch (RuntimeException | Error exception) {
                    controlFailure.compareAndSet(null, exception);
                } finally {
                    resizeOwnerFinished.countDown();
                }
            });
            resizeOwnerSubmitted = true;
            if (!resizeOwnerStarted.await(CONTROL_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("扩容线程未按时启动");
            }
            awaitBlockedOnGuard(resizeOwner.get(), resizeOwnerFinished);

            List<Runnable> observers = Arrays.asList(
                    () -> repeatedlyReadForwardedMapping(map, readMisses),
                    () -> repeatedlyRewriteForwardedMapping(map),
                    () -> traverseDuringResize(map, traversedMappings)
            );
            runConcurrently(observers);
        } finally {
            // 无论观察动作是否成功，都释放桶首监视器，避免把受控断点场景变成遗留死锁。
            releaseGuard.countDown();
            try {
                if (resizeOwnerSubmitted
                        && !resizeOwnerFinished.await(DEBUG_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    controlFailure.compareAndSet(null, new IllegalStateException("扩容线程未按时完成"));
                }
            } finally {
                // 即使等待被外部中断，也必须关闭控制线程，不能在调试退出后留下后台任务。
                controlExecutor.shutdownNow();
            }
        }
        if (controlFailure.get() != null) {
            throw new IllegalStateException("受控扩容场景执行失败", controlFailure.get());
        }
    }

    /**
     * 在 remappingFunction 中占住高区桶首监视器，直到观察动作结束后才释放。
     *
     * @param map          被观察的 Map
     * @param guardEntered 已经进入桶锁的信号
     * @param releaseGuard 允许释放桶锁的信号
     */
    private static void holdTransferGuard(ConcurrentHashMap<Integer, Integer> map,
                                          CountDownLatch guardEntered,
                                          CountDownLatch releaseGuard) {
        map.computeIfPresent(RESIZE_GUARD_KEY, (key, value) -> {
            guardEntered.countDown();
            try {
                if (!releaseGuard.await(DEBUG_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("扩容观察窗口未按时释放");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待释放扩容阻塞桶时被中断", exception);
            }
            return value;
        });
    }

    /**
     * 等待扩容线程阻塞在相邻桶监视器上；该公开线程状态证明高一位观察桶已经迁移。
     *
     * @param resizeOwner        扩容发起线程
     * @param resizeOwnerFinished 扩容线程完成信号
     * @throws InterruptedException 轮询等待时被中断
     */
    private static void awaitBlockedOnGuard(Thread resizeOwner,
                                            CountDownLatch resizeOwnerFinished)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(DEBUG_COMPLETION_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (resizeOwner.getState() == Thread.State.BLOCKED) {
                return;
            }
            if (resizeOwnerFinished.getCount() == 0) {
                throw new IllegalStateException("扩容线程未进入预期阻塞窗口");
            }
            Thread.sleep(1L);
        }
        throw new IllegalStateException("扩容线程未按时阻塞在观察桶");
    }

    /**
     * 写入一批互不重复的新键，首个写入使预热后的 Map 确定跨过扩容阈值。
     *
     * @param map 被观察的 Map
     */
    private static void addResizeMappings(ConcurrentHashMap<Integer, Integer> map) {
        for (int offset = 0; offset < RESIZE_ADDITIONS; offset++) {
            int key = RESIZE_ADDITION_KEY_BASE + offset;
            map.put(key, key);
        }
    }

    /**
     * 在已迁移的 table[32767] 上重复读取，使调用稳定进入 ForwardingNode.find。
     *
     * @param map        被观察的 Map
     * @param readMisses 读取缺失计数
     */
    private static void repeatedlyReadForwardedMapping(ConcurrentHashMap<Integer, Integer> map,
                                                       AtomicInteger readMisses) {
        for (int round = 0; round < RESIZE_OBSERVER_ROUNDS; round++) {
            if (map.get(RESIZE_OBSERVATION_KEY) == null) {
                readMisses.incrementAndGet();
            }
        }
    }

    /**
     * 在已迁移的 table[32767] 上反复覆写，使 putVal 稳定进入 helpTransfer 并领取低区任务。
     *
     * @param map 被观察的 Map
     */
    private static void repeatedlyRewriteForwardedMapping(ConcurrentHashMap<Integer, Integer> map) {
        for (int round = 0; round < RESIZE_OBSERVER_ROUNDS; round++) {
            map.put(RESIZE_OBSERVATION_KEY, RESIZE_OBSERVATION_KEY);
        }
    }

    /**
     * 在扩容线程运行时遍历条目，使 Traverser 遇到 ForwardingNode 后保存并恢复旧表位置。
     *
     * @param map               被观察的 Map
     * @param traversedMappings 遍历到的非空映射计数
     */
    private static void traverseDuringResize(ConcurrentHashMap<Integer, Integer> map,
                                             AtomicInteger traversedMappings) {
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                traversedMappings.incrementAndGet();
            }
        }
    }

    /**
     * 检查扩容前后所有预期键仍能按公开 get 契约读取，避免把内部结构当成断言目标。
     *
     * @param map              待检查的 Map
     * @param expectedMappings 预期映射数量
     * @return 缺失或值不匹配的映射数量
     */
    private static int countMissingMappings(ConcurrentHashMap<Integer, Integer> map,
                                            int expectedMappings) {
        int missingMappings = 0;
        int regularMappings = (RESIZE_THRESHOLD - 1) - 2;
        for (int key = 0; key < regularMappings; key++) {
            if (!hasExpectedValue(map, key)) {
                missingMappings++;
            }
        }
        if (!hasExpectedValue(map, RESIZE_GUARD_KEY)) {
            missingMappings++;
        }
        if (!hasExpectedValue(map, RESIZE_OBSERVATION_KEY)) {
            missingMappings++;
        }
        for (int offset = 0; offset < RESIZE_ADDITIONS; offset++) {
            if (!hasExpectedValue(map, RESIZE_ADDITION_KEY_BASE + offset)) {
                missingMappings++;
            }
        }
        if (expectedMappings != regularMappings + 2 + RESIZE_ADDITIONS) {
            throw new IllegalArgumentException("预期映射数量与受控场景不一致");
        }
        return missingMappings;
    }

    /**
     * 检查整数键是否仍映射到同值，统一最终完整性验证规则。
     *
     * @param map 被检查的 Map
     * @param key 预期键和值
     * @return 键存在且值等于键时返回 true
     */
    private static boolean hasExpectedValue(ConcurrentHashMap<Integer, Integer> map, int key) {
        Integer value = map.get(key);
        return value != null && value == key;
    }

    /**
     * 同时释放固定数量的工作线程，并等待全部动作完成。
     *
     * @param threadCount 并发线程数
     * @param action      每个线程执行的动作
     * @throws InterruptedException 等待闩锁时被中断
     */
    private static void runConcurrently(int threadCount, Runnable action) throws InterruptedException {
        List<Runnable> actions = new ArrayList<>(threadCount);
        for (int index = 0; index < threadCount; index++) {
            actions.add(action);
        }
        runConcurrently(actions);
    }

    /**
     * 同步放行一组不同任务，等待全部完成，并把工作线程异常带回主线程。
     *
     * @param actions 需要并发执行的任务
     * @throws InterruptedException 等待闩锁时被中断
     */
    private static void runConcurrently(List<Runnable> actions) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(actions.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            // 所有线程先报告就绪，再由同一个闩锁同时放行，减少启动先后对实验的干扰。
            for (Runnable action : actions) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        action.run();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        failure.compareAndSet(null, exception);
                    } catch (RuntimeException | Error exception) {
                        failure.compareAndSet(null, exception);
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发任务未按时就绪");
            }
            start.countDown();
            // 源码断点会主动暂停单个工作线程，调试入口预留足够时间供学习者检查局部变量。
            if (!done.await(DEBUG_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发任务未按时完成");
            }
            if (failure.get() != null) {
                throw new IllegalStateException("并发任务执行失败", failure.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 执行动作并判断是否抛出空指针异常。
     *
     * @param action 待验证动作
     * @return 抛出 NullPointerException 时返回 true
     */
    private static boolean isNullPointer(Runnable action) {
        try {
            action.run();
            return false;
        } catch (NullPointerException exception) {
            return true;
        }
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
