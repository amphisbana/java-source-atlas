package io.github.javasourceatlas.jdk.collection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 TreeMap 教学案例依赖的公开可观察行为。
 */
class TreeMapBehaviorTest {

    /**
     * 验证自然顺序遍历，以及覆盖已有键不会增加映射数量。
     */
    @Test
    void shouldOrderNaturallyAndReplaceExistingValue() {
        NavigableMap<Integer, String> map = new TreeMap<>();
        map.put(3, "three");
        map.put(1, "one");
        map.put(2, "two");

        assertEquals("two", map.put(2, "TWO"));
        assertEquals(3, map.size());
        assertIterableEquals(Arrays.asList(1, 2, 3), map.navigableKeySet());
        assertEquals("TWO", map.get(2));
    }

    /**
     * 验证显式反向比较器同时决定遍历和导航顺序。
     */
    @Test
    void shouldRespectExplicitComparatorOrder() {
        NavigableMap<Integer, String> map = new TreeMap<>(Comparator.reverseOrder());
        map.put(1, "one");
        map.put(3, "three");
        map.put(2, "two");

        assertIterableEquals(Arrays.asList(3, 2, 1), map.navigableKeySet());
        assertEquals(Integer.valueOf(2), map.higherKey(3));
    }

    /**
     * 验证比较结果为零时更新同一节点，并保留第一次写入的 key。
     */
    @Test
    void shouldUseComparatorResultAsKeyIdentity() {
        NavigableMap<String, Integer> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.put("Java", 1);

        assertEquals(Integer.valueOf(1), map.put("JAVA", 2));
        assertEquals(1, map.size());
        assertEquals("Java", map.firstKey());
        assertEquals(Integer.valueOf(2), map.get("java"));
    }

    /**
     * 验证 lower、floor、ceiling、higher 的严格与包含边界。
     */
    @Test
    void shouldNavigateStrictAndInclusiveBoundaries() {
        NavigableMap<Integer, String> map = createNavigationMap();

        assertEquals(Integer.valueOf(10), map.lowerKey(20));
        assertEquals(Integer.valueOf(20), map.floorKey(20));
        assertEquals(Integer.valueOf(20), map.ceilingKey(20));
        assertEquals(Integer.valueOf(30), map.higherKey(20));

        assertEquals(Integer.valueOf(20), map.lowerKey(25));
        assertEquals(Integer.valueOf(20), map.floorKey(25));
        assertEquals(Integer.valueOf(30), map.ceilingKey(25));
        assertEquals(Integer.valueOf(30), map.higherKey(25));
        assertNull(map.lowerKey(10));
        assertNull(map.higherKey(40));
    }

    /**
     * 验证 subMap 与原 Map 共享数据，并拒绝排他上界的写入。
     */
    @Test
    void shouldExposeBackedRangeViewAndRejectOutOfRangeWrites() {
        NavigableMap<Integer, String> map = createNavigationMap();
        NavigableMap<Integer, String> view = map.subMap(20, true, 40, false);

        view.put(25, "twenty-five");
        assertTrue(map.containsKey(25));

        map.put(35, "thirty-five");
        assertTrue(view.containsKey(35));
        assertThrows(IllegalArgumentException.class, () -> view.put(40, "forty-again"));

        view.clear();
        assertIterableEquals(Arrays.asList(10, 40), map.navigableKeySet());
    }

    /**
     * 验证导航 API 返回的 Entry 是不允许 setValue 的导出快照。
     */
    @Test
    void shouldReturnImmutableNavigationEntrySnapshot() {
        NavigableMap<Integer, String> map = createNavigationMap();
        Map.Entry<Integer, String> first = map.firstEntry();

        assertThrows(UnsupportedOperationException.class, () -> first.setValue("changed"));
        assertEquals("ten", map.get(10));
    }

    /**
     * 验证删除双孩子节点及其他节点后，剩余映射仍保持有序且可查询。
     */
    @Test
    void shouldKeepOrderAndMappingsAfterDeletion() {
        NavigableMap<Integer, String> map = new TreeMap<>();
        int[] keys = {4, 2, 6, 1, 3, 5, 7, 8};
        for (int key : keys) {
            map.put(key, "v" + key);
        }

        assertEquals("v4", map.remove(4));
        map.remove(1);
        map.remove(7);

        assertIterableEquals(Arrays.asList(2, 3, 5, 6, 8), map.navigableKeySet());
        assertEquals("v5", map.get(5));
        assertNull(map.get(4));
    }

    /**
     * 验证动画使用的三组删除修复输入都保留其余映射和有序遍历公开契约。
     */
    @Test
    void shouldPreserveMappingsAcrossDeletionRepairScenarios() {
        NavigableMap<Integer, String> successorAndFarNephew = createIntegerMap(1, 2, 3, 4, 5, 6, 7, 8);
        assertEquals("v4", successorAndFarNephew.remove(4));
        assertIterableEquals(Arrays.asList(1, 2, 3, 5, 6, 7, 8),
                successorAndFarNephew.navigableKeySet());

        NavigableMap<Integer, String> redSiblingAndAllBlack = createIntegerMap(2, 1, 4, 3, 7, 5, 6);
        assertEquals("v1", redSiblingAndAllBlack.remove(1));
        assertIterableEquals(Arrays.asList(2, 3, 4, 5, 6, 7),
                redSiblingAndAllBlack.navigableKeySet());

        NavigableMap<Integer, String> nearNephew = createIntegerMap(3, 2, 4, 5, 7, 1, 6);
        assertEquals("v4", nearNephew.remove(4));
        assertIterableEquals(Arrays.asList(1, 2, 3, 5, 6, 7), nearNephew.navigableKeySet());
    }

    /**
     * 验证迭代器会尽力发现创建后的结构性修改。
     */
    @Test
    void shouldFailFastAfterStructuralModification() {
        NavigableMap<Integer, String> map = createNavigationMap();
        Iterator<Integer> iterator = map.navigableKeySet().iterator();

        map.put(25, "twenty-five");

        assertThrows(ConcurrentModificationException.class, iterator::next);
    }

    /**
     * 建立导航和范围测试共用的四节点 TreeMap。
     *
     * @return 包含十、二十、三十、四十的有序映射
     */
    private static NavigableMap<Integer, String> createNavigationMap() {
        NavigableMap<Integer, String> map = new TreeMap<>();
        map.put(10, "ten");
        map.put(20, "twenty");
        map.put(30, "thirty");
        map.put(40, "forty");
        return map;
    }

    /**
     * 按给定顺序建立整数 TreeMap，供删除修复公开行为测试复用。
     *
     * @param keys 依次插入的键
     * @return 包含全部键和值的 TreeMap
     */
    private static NavigableMap<Integer, String> createIntegerMap(int... keys) {
        NavigableMap<Integer, String> map = new TreeMap<>();
        for (int key : keys) {
            map.put(key, "v" + key);
        }
        return map;
    }
}
