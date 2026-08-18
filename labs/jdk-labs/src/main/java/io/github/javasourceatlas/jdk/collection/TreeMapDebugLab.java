package io.github.javasourceatlas.jdk.collection;

import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 用公开 API 稳定触发 TreeMap 核心源码分支的调试入口。
 */
public final class TreeMapDebugLab {

    /**
     * 工具类不需要创建实例。
     */
    private TreeMapDebugLab() {
    }

    /**
     * 按固定顺序运行全部 TreeMap 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        printHeader("自然顺序、比较器与覆盖");
        observeOrderingAndReplacement();

        printHeader("Comparator 定义键身份");
        observeComparatorIdentity();

        printHeader("插入重着色与旋转");
        observeInsertionBalancing();

        printHeader("邻近键导航");
        observeNavigation();

        printHeader("SubMap 后备视图");
        observeRangeView();

        printHeader("删除与后继替换");
        observeDeletion();

        printHeader("删除修复四类分支的固定序列");
        observeDeletionRepairBranches();

        printHeader("迭代器快速失败");
        observeFailFastIterator();
    }

    /**
     * 对比自然升序、反向比较器与覆盖已有键的行为。
     */
    static void observeOrderingAndReplacement() {
        NavigableMap<Integer, String> natural = new TreeMap<>();
        natural.put(3, "three");
        natural.put(1, "one");
        natural.put(2, "two");
        String oldValue = natural.put(2, "TWO");

        NavigableMap<Integer, String> reverse = new TreeMap<>(Comparator.reverseOrder());
        reverse.putAll(natural);

        System.out.printf("自然顺序=%s，覆盖旧值=%s，size=%d%n",
                natural.keySet(), oldValue, natural.size());
        System.out.printf("反向顺序=%s%n", reverse.keySet());
    }

    /**
     * 使用忽略大小写比较器，观察比较结果为零时只更新原节点的 value。
     */
    static void observeComparatorIdentity() {
        NavigableMap<String, Integer> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.put("Java", 1);
        Integer oldValue = map.put("JAVA", 2);

        System.out.printf("旧值=%d，保留的 key=%s，当前 value=%d，size=%d%n",
                oldValue, map.firstKey(), map.firstEntry().getValue(), map.size());
    }

    /**
     * 递增写入一到八，稳定触发叔父重着色和左右链接旋转。
     */
    static void observeInsertionBalancing() {
        NavigableMap<Integer, String> map = new TreeMap<>();
        for (int key = 1; key <= 8; key++) {
            map.put(key, "v" + key);
        }
        System.out.printf("插入完成，升序键=%s%n", map.navigableKeySet());
    }

    /**
     * 对存在键二十和不存在键二十五执行四组邻近导航查询。
     */
    static void observeNavigation() {
        NavigableMap<Integer, String> map = createNavigationMap();

        System.out.printf("查询 20：lower=%s，floor=%s，ceiling=%s，higher=%s%n",
                map.lowerKey(20), map.floorKey(20), map.ceilingKey(20), map.higherKey(20));
        System.out.printf("查询 25：lower=%s，floor=%s，ceiling=%s，higher=%s%n",
                map.lowerKey(25), map.floorKey(25), map.ceilingKey(25), map.higherKey(25));
    }

    /**
     * 通过范围视图和原 Map 双向修改数据，并观察越界写入保护。
     */
    static void observeRangeView() {
        NavigableMap<Integer, String> map = createNavigationMap();
        NavigableMap<Integer, String> view = map.subMap(20, true, 40, false);

        view.put(25, "twenty-five");
        map.put(35, "thirty-five");
        System.out.printf("原 Map=%s，范围视图=%s%n", map.keySet(), view.keySet());

        try {
            view.put(40, "out-of-range");
        } catch (IllegalArgumentException exception) {
            System.out.println("范围 [20,40) 拒绝写入键 40");
        }

        view.clear();
        System.out.printf("清空视图后，原 Map 保留=%s%n", map.keySet());
    }

    /**
     * 删除拥有两个孩子的键以及叶子节点，观察后继复制和删除修复。
     */
    static void observeDeletion() {
        NavigableMap<Integer, String> map = new TreeMap<>();
        int[] keys = {4, 2, 6, 1, 3, 5, 7, 8};
        for (int key : keys) {
            map.put(key, "v" + key);
        }

        String removedRootValue = map.remove(4);
        map.remove(1);
        map.remove(7);
        System.out.printf("删除 4 的旧值=%s，剩余键=%s%n", removedRootValue, map.keySet());
    }

    /**
     * 运行三组固定插入和删除序列，分别触发远侄红、兄弟红加全黑上推、近侄红转换分支。
     */
    static void observeDeletionRepairBranches() {
        int[][] insertionSequences = {
                {1, 2, 3, 4, 5, 6, 7, 8},
                {2, 1, 4, 3, 7, 5, 6},
                {3, 2, 4, 5, 7, 1, 6}
        };
        int[] removedKeys = {4, 1, 4};

        for (int index = 0; index < insertionSequences.length; index++) {
            NavigableMap<Integer, String> map = createIntegerMap(insertionSequences[index]);
            int removedKey = removedKeys[index];
            String removedValue = map.remove(removedKey);
            System.out.printf("序列=%s，删除=%d，旧值=%s，剩余=%s%n",
                    java.util.Arrays.toString(insertionSequences[index]),
                    removedKey,
                    removedValue,
                    map.navigableKeySet());
        }
    }

    /**
     * 创建迭代器后新增节点，观察结构性修改的快速失败检查。
     */
    static void observeFailFastIterator() {
        NavigableMap<Integer, String> map = createNavigationMap();
        Iterator<Integer> iterator = map.navigableKeySet().iterator();
        map.put(25, "twenty-five");

        try {
            iterator.next();
        } catch (ConcurrentModificationException exception) {
            System.out.println("捕获到 ConcurrentModificationException");
        }
    }

    /**
     * 建立导航和范围视图实验共用的四节点 TreeMap。
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
     * 按给定顺序建立整数 TreeMap，保留插入顺序对红黑树形态的影响。
     *
     * @param keys 依次插入的键
     * @return 已按固定顺序完成插入的 TreeMap
     */
    private static NavigableMap<Integer, String> createIntegerMap(int[] keys) {
        NavigableMap<Integer, String> map = new TreeMap<>();
        for (int key : keys) {
            map.put(key, "v" + key);
        }
        return map;
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
