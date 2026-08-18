package io.github.javasourceatlas.jdk.collection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 ArrayList 教学案例依赖的公开可观察行为。
 */
class ArrayListBehaviorTest {

    /**
     * 验证连续扩容后仍保持元素数量和顺序。
     */
    @Test
    void shouldKeepOrderAfterGrowth() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            list.add(i);
        }

        assertEquals(100, list.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i, list.get(i));
        }
    }

    /**
     * 验证中间插入和删除会保持剩余元素的列表顺序。
     */
    @Test
    void shouldShiftElementsForMiddleMutation() {
        List<String> list = new ArrayList<>(Arrays.asList("A", "B", "C"));

        list.add(1, "X");
        assertEquals("B", list.remove(2));
        assertEquals(Arrays.asList("A", "X", "C"), list);
    }

    /**
     * 验证 Integer 参数会根据静态类型选择不同的 remove 重载。
     */
    @Test
    void shouldDistinguishRemoveOverloads() {
        List<Integer> byIndex = new ArrayList<>(Arrays.asList(1, 2, 1));
        List<Integer> byValue = new ArrayList<>(byIndex);

        assertEquals(2, byIndex.remove(1));
        byValue.remove(Integer.valueOf(1));

        assertEquals(Arrays.asList(1, 1), byIndex);
        assertEquals(Arrays.asList(2, 1), byValue);
    }

    /**
     * 验证 subList 修改会反映到父列表，而显式复制不会继续共享结构。
     */
    @Test
    void shouldExposeSubListAsView() {
        List<String> parent = new ArrayList<>(Arrays.asList("A", "B", "C"));
        List<String> view = parent.subList(1, 3);
        List<String> copy = new ArrayList<>(view);

        view.set(0, "X");

        assertEquals(Arrays.asList("A", "X", "C"), parent);
        assertEquals(Arrays.asList("B", "C"), copy);
    }

    /**
     * 验证迭代器会尽力发现创建后的结构性修改。
     */
    @Test
    void shouldFailFastAfterStructuralModification() {
        List<String> list = new ArrayList<>(Arrays.asList("A", "B"));
        Iterator<String> iterator = list.iterator();

        list.add("C");

        assertThrows(ConcurrentModificationException.class, iterator::next);
    }
}

