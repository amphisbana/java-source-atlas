package io.github.javasourceatlas.jdk.collection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 LinkedHashMap 教学案例依赖的公开可观察行为。
 */
class LinkedHashMapBehaviorTest {

    /**
     * 验证插入顺序模式下覆盖已有 value 不会移动 key。
     */
    @Test
    void shouldKeepInsertionOrderWhenReplacingValue() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);

        map.put("B", 20);

        assertEquals(Arrays.asList("A", "B", "C"), keysOf(map));
        assertEquals(20, map.get("B"));
    }

    /**
     * 验证访问顺序模式会把命中的非尾节点移动到迭代顺序末尾。
     */
    @Test
    void shouldMoveAccessedEntryToTail() {
        Map<String, Integer> map = new LinkedHashMap<>(16, 0.75f, true);
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);

        assertEquals(2, map.get("B"));

        assertEquals(Arrays.asList("A", "C", "B"), keysOf(map));
    }

    /**
     * 验证未命中查询和集合视图读取都不会刷新访问顺序。
     */
    @Test
    void shouldNotReorderForMissOrCollectionViewRead() {
        Map<String, Integer> map = new LinkedHashMap<>(16, 0.75f, true);
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);

        assertNull(map.get("missing"));
        assertTrue(map.keySet().contains("A"));

        assertEquals(Arrays.asList("A", "B", "C"), keysOf(map));
    }

    /**
     * 验证访问 A 后再插入 D，会淘汰真正最久未访问的 B。
     */
    @Test
    void shouldEvictLeastRecentlyUsedEntry() {
        Map<String, Integer> cache = new LinkedHashMapDebugLab.FixedSizeLruMap<>(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.get("A");
        cache.put("D", 4);

        assertFalse(cache.containsKey("B"));
        assertEquals(Arrays.asList("C", "A", "D"), keysOf(cache));
        assertEquals(3, cache.size());
    }

    /**
     * 验证复合更新方法在真正新增映射时也会触发 LRU 淘汰判断。
     */
    @Test
    void shouldEvictAfterAlternativeInsertionMethodsCreateMappings() {
        assertAlternativeInsertionEvictsEldest((cache, key) -> cache.putIfAbsent(key, 3));
        assertAlternativeInsertionEvictsEldest((cache, key) -> cache.computeIfAbsent(key, ignored -> 3));
        assertAlternativeInsertionEvictsEldest((cache, key) -> cache.compute(key, (ignored, value) -> 3));
        assertAlternativeInsertionEvictsEldest((cache, key) -> cache.merge(key, 3, Integer::sum));
    }

    /**
     * 验证访问移动属于结构性修改，会使已经创建的迭代器快速失败。
     */
    @Test
    void shouldFailFastWhenAccessChangesOrder() {
        Map<String, Integer> map = new LinkedHashMap<>(16, 0.75f, true);
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);
        Iterator<String> iterator = map.keySet().iterator();

        map.get("A");

        assertThrows(ConcurrentModificationException.class, iterator::next);
    }

    /**
     * 验证多次扩容后仍保持插入顺序，删除节点后剩余顺序也保持稳定。
     */
    @Test
    void shouldKeepOrderAcrossResizeAndRemoval() {
        Map<Integer, String> map = new LinkedHashMap<>(2);
        for (int i = 0; i < 64; i++) {
            map.put(i, "value-" + i);
        }

        map.remove(0);
        map.remove(31);
        map.remove(63);

        List<Integer> expected = new ArrayList<>();
        // 扩容不应重排 before/after，期望列表只跳过被主动删除的三个 key。
        for (int i = 1; i < 63; i++) {
            if (i != 31) {
                expected.add(i);
            }
        }
        assertEquals(expected, keysOf(map));
    }

    /**
     * 验证实验用 LRU 映射拒绝无效的容量上限。
     */
    @Test
    void shouldRejectNonPositiveLruCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new LinkedHashMapDebugLab.FixedSizeLruMap<String, Integer>(0));
    }

    /**
     * 复制当前 key 的公开迭代顺序，供各行为断言复用。
     *
     * @param map 待检查的映射
     * @param <K> key 类型
     * @param <V> value 类型
     * @return 按迭代顺序排列的 key 列表
     */
    private static <K, V> List<K> keysOf(Map<K, V> map) {
        return new ArrayList<>(map.keySet());
    }

    /**
     * 使用容量为 2 的独立缓存执行一次复合新增，并断言最老映射被淘汰。
     *
     * @param insertion 要执行的复合新增操作
     */
    private static void assertAlternativeInsertionEvictsEldest(
            BiConsumer<Map<String, Integer>, String> insertion) {
        Map<String, Integer> cache = new LinkedHashMapDebugLab.FixedSizeLruMap<>(2);
        cache.put("A", 1);
        cache.put("B", 2);

        insertion.accept(cache, "C");

        assertEquals(Arrays.asList("B", "C"), keysOf(cache));
        assertFalse(cache.containsKey("A"));
    }
}
