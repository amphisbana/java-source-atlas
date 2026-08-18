package io.github.javasourceatlas.jdk.concurrent;

import io.github.javasourceatlas.jdk.collection.CollisionKey;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ConcurrentHashMap 教学案例依赖的公开可观察行为。
 */
class ConcurrentHashMapBehaviorTest {

    private static final int COLLISION_HASH = 11;

    /**
     * 验证空键和空值都会被拒绝。
     */
    @Test
    void shouldRejectNullKeyAndValue() {
        Map<String, String> map = new ConcurrentHashMap<>();

        assertThrows(NullPointerException.class, () -> map.put(null, "value"));
        assertThrows(NullPointerException.class, () -> map.put("key", null));
    }

    /**
     * 验证多个线程通过 merge 原子累加不会丢失更新。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    @Test
    void shouldMergeAtomically() throws InterruptedException {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        int threadCount = 8;
        int increments = 500;

        runConcurrently(threadCount, () -> {
            for (int i = 0; i < increments; i++) {
                counts.merge("total", 1, Integer::sum);
            }
        });

        assertEquals(threadCount * increments, counts.get("total"));
    }

    /**
     * 验证并发请求同一个缺失键时只建立一个映射。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    @Test
    void shouldComputeOneMappingForSharedKey() throws InterruptedException {
        Map<String, Object> map = new ConcurrentHashMap<>();
        AtomicInteger mappingCalls = new AtomicInteger();

        runConcurrently(8, () -> map.computeIfAbsent("shared", key -> {
            mappingCalls.incrementAndGet();
            return new Object();
        }));

        assertEquals(1, mappingCalls.get());
        assertEquals(1, map.size());
    }

    /**
     * 验证带条件的替换与删除只在当前值匹配时生效。
     */
    @Test
    void shouldApplyConditionalUpdatesAtomically() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        map.put("status", "created");

        assertFalse(map.replace("status", "wrong", "running"));
        assertTrue(map.replace("status", "created", "running"));
        assertFalse(map.remove("status", "created"));
        assertTrue(map.remove("status", "running"));
    }

    /**
     * 验证并发写入跨过多轮扩容后，全部公开映射仍然完整可读。
     *
     * @throws InterruptedException 等待并发任务时被中断
     */
    @Test
    void shouldPreserveMappingsAcrossConcurrentResize() throws InterruptedException {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(2);
        int threadCount = 4;
        int mappingsPerThread = 4_000;

        AtomicInteger sequence = new AtomicInteger();
        runConcurrently(threadCount, () -> {
            for (int index = 0; index < mappingsPerThread; index++) {
                int ordinal = sequence.getAndIncrement();
                map.put(distributedKey(ordinal), ordinal);
            }
        });

        int expectedMappings = threadCount * mappingsPerThread;
        assertEquals(expectedMappings, map.mappingCount());
        for (int ordinal = 0; ordinal < expectedMappings; ordinal++) {
            assertEquals(ordinal, map.get(distributedKey(ordinal)));
        }
    }

    /**
     * 验证达到树化条件的强碰撞桶仍支持查找、覆盖和新增，不依赖反射判断 TreeBin 类型。
     */
    @Test
    void shouldKeepCollidingMappingsReadableAfterTreeification() {
        ConcurrentHashMap<CollisionKey, String> map = new ConcurrentHashMap<>(64);
        int initialMappings = 16;
        for (int id = 0; id < initialMappings; id++) {
            map.put(new CollisionKey(id, COLLISION_HASH), "value-" + id);
        }

        for (int id = 0; id < initialMappings; id++) {
            assertEquals("value-" + id, map.get(new CollisionKey(id, COLLISION_HASH)));
        }
        assertEquals("value-5", map.put(new CollisionKey(5, COLLISION_HASH), "updated-5"));
        map.put(new CollisionKey(initialMappings, COLLISION_HASH), "new-value");

        assertEquals("updated-5", map.get(new CollisionKey(5, COLLISION_HASH)));
        assertEquals("new-value", map.get(new CollisionKey(initialMappings, COLLISION_HASH)));
        assertEquals(initialMappings + 1, map.size());
    }

    /**
     * 验证弱一致迭代器允许创建后继续发生删除与扩容，并且只返回非空公开映射。
     */
    @Test
    void shouldTraverseSafelyWhileMapChanges() {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(2);
        for (int index = 0; index < 1_024; index++) {
            map.put(index, index);
        }

        Iterator<Map.Entry<Integer, Integer>> iterator = map.entrySet().iterator();
        assertTrue(iterator.hasNext());
        Map.Entry<Integer, Integer> first = iterator.next();
        map.remove(first.getKey());
        for (int index = 1_024; index < 4_096; index++) {
            map.put(index, index);
        }

        int observedMappings = 1;
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> entry = iterator.next();
            assertNotNull(entry.getKey());
            assertNotNull(entry.getValue());
            observedMappings++;
        }
        assertTrue(observedMappings > 1);
        assertEquals(4_095, map.size());
    }

    /**
     * 生成互不重复且分布较均匀的整数键，使测试稳定跨过多个公开容量增长阶段。
     *
     * @param ordinal 连续序号
     * @return Map 使用的整数键
     */
    private static int distributedKey(int ordinal) {
        return ordinal * 0x9E3779B9;
    }

    /**
     * 同时释放固定数量的工作线程，并等待所有测试动作完成。
     *
     * @param threadCount 并发线程数
     * @param action      每个线程执行的动作
     * @throws InterruptedException 等待闩锁时被中断
     */
    private static void runConcurrently(int threadCount, Runnable action) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        try {
            // 先让工作线程全部抵达起跑线，再同时执行被测原子操作。
            for (int i = 0; i < threadCount; i++) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        action.run();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
