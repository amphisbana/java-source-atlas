package io.github.javasourceatlas.jdk.collection;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用公开 API 稳定触发 LinkedHashMap 顺序维护与 LRU 淘汰分支的调试入口。
 */
public final class LinkedHashMapDebugLab {

    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * 工具类不需要创建实例。
     */
    private LinkedHashMapDebugLab() {
    }

    /**
     * 按固定顺序运行全部 LinkedHashMap 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        printHeader("插入顺序与覆盖");
        observeInsertionOrder();

        printHeader("访问顺序");
        observeAccessOrder();

        printHeader("集合视图读取");
        observeCollectionViewRead();

        printHeader("LRU 淘汰");
        observeLruEviction();

        printHeader("访问导致迭代器快速失败");
        observeFailFastAfterAccess();

        printHeader("删除不同位置的节点");
        observeRemovalPositions();
    }

    /**
     * 覆盖已有键的值，观察插入顺序不会改变。
     */
    static void observeInsertionOrder() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);
        map.put("B", 20);

        System.out.printf("覆盖 B 后顺序=%s，B 的值=%d%n", keysOf(map), map.get("B"));
    }

    /**
     * 读取非尾节点和不存在的键，观察访问顺序的移动边界。
     */
    static void observeAccessOrder() {
        Map<String, Integer> map = new LinkedHashMap<>(16, DEFAULT_LOAD_FACTOR, true);
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);

        Integer value = map.get("A");
        List<String> afterHit = keysOf(map);
        Integer missing = map.get("missing");

        System.out.printf("读取 A=%d 后顺序=%s；未命中返回=%s，顺序=%s%n",
                value, afterHit, missing, keysOf(map));
    }

    /**
     * 通过 keySet 查询已有键，观察集合视图读取不会刷新访问顺序。
     */
    static void observeCollectionViewRead() {
        Map<String, Integer> map = new LinkedHashMap<>(16, DEFAULT_LOAD_FACTOR, true);
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);

        boolean contains = map.keySet().contains("A");

        System.out.printf("keySet.contains(A)=%s，顺序=%s%n", contains, keysOf(map));
    }

    /**
     * 访问 A 后插入 D，观察容量为三时淘汰真正最久未访问的 B。
     */
    static void observeLruEviction() {
        Map<String, Integer> cache = new FixedSizeLruMap<>(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        cache.get("A");
        List<String> afterAccess = keysOf(cache);
        cache.put("D", 4);

        System.out.printf("访问 A 后=%s；插入 D 并淘汰后=%s，是否仍有 B=%s%n",
                afterAccess, keysOf(cache), cache.containsKey("B"));
    }

    /**
     * 在创建迭代器后访问非尾节点，观察顺序变化触发快速失败。
     */
    static void observeFailFastAfterAccess() {
        Map<String, Integer> map = new LinkedHashMap<>(16, DEFAULT_LOAD_FACTOR, true);
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);

        Iterator<String> iterator = map.keySet().iterator();
        map.get("A");

        try {
            iterator.next();
        } catch (ConcurrentModificationException exception) {
            System.out.println("访问 A 改变顺序后，旧迭代器捕获到 ConcurrentModificationException");
        }
    }

    /**
     * 依次删除中间、头部、尾部和最后一个节点，便于观察 afterNodeRemoval 的全部边界。
     */
    static void observeRemovalPositions() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("A", 1);
        map.put("B", 2);
        map.put("C", 3);
        map.put("D", 4);

        map.remove("B");
        System.out.printf("删除中间 B 后=%s%n", keysOf(map));
        map.remove("A");
        System.out.printf("删除 head A 后=%s%n", keysOf(map));
        map.remove("D");
        System.out.printf("删除 tail D 后=%s%n", keysOf(map));
        map.remove("C");
        System.out.printf("删除最后节点 C 后=%s%n", keysOf(map));
    }

    /**
     * 复制当前 key 的遍历顺序，避免控制台打印依赖 Map 的 value 格式。
     *
     * @param map 待观察的映射
     * @param <K> key 类型
     * @param <V> value 类型
     * @return 按当前迭代顺序排列的 key 列表
     */
    private static <K, V> List<K> keysOf(Map<K, V> map) {
        return new ArrayList<>(map.keySet());
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

    /**
     * 通过 LinkedHashMap 的淘汰 Hook 实现固定条目数的访问顺序映射。
     *
     * @param <K> key 类型
     * @param <V> value 类型
     */
    static final class FixedSizeLruMap<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        /**
         * 创建按访问顺序维护的固定容量映射。
         *
         * @param maxEntries 允许保留的最大映射数量，必须大于零
         */
        FixedSizeLruMap(int maxEntries) {
            super(maxEntries, DEFAULT_LOAD_FACTOR, true);
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries 必须大于零");
            }
            this.maxEntries = maxEntries;
        }

        /**
         * 新映射加入后，条目数超过上限时请求父类删除最久未访问项。
         *
         * @param eldest 当前顺序链的 head，即最久未访问映射
         * @return 超过条目数上限时返回 true
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
}
