package io.github.javasourceatlas.jdk.collection;

import java.util.HashMap;
import java.util.Map;

/**
 * 用公开 API 稳定触发 HashMap 核心源码分支的调试入口。
 */
public final class HashMapDebugLab {

    private static final int COLLISION_HASH = 7;

    /**
     * 工具类不需要创建实例。
     */
    private HashMapDebugLab() {
    }

    /**
     * 按固定顺序运行全部 HashMap 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        printHeader("首次写入与覆盖");
        observeBasicPutAndReplace();

        printHeader("容量扩张");
        observeResize();

        printHeader("哈希碰撞");
        observeCollision();

        printHeader("链表树化");
        observeTreeification();

        printHeader("null 键");
        observeNullKey();
    }

    /**
     * 观察首次写入分配桶数组，以及相同键覆盖值但不增加 size 的行为。
     */
    static void observeBasicPutAndReplace() {
        Map<String, String> map = new HashMap<>();
        String firstResult = map.put("language", "Java 8");
        String replacedValue = map.put("language", "Java 17");

        System.out.printf("首次 put 返回=%s，覆盖返回=%s，当前值=%s，size=%d%n",
                firstResult, replacedValue, map.get("language"), map.size());
    }

    /**
     * 使用较小初始容量连续写入，触发 resize 并验证旧映射仍然可读取。
     */
    static void observeResize() {
        Map<Integer, String> map = new HashMap<>(4);
        for (int i = 0; i < 8; i++) {
            map.put(i, "value-" + i);
        }

        System.out.printf("写入 8 个映射后 size=%d，key 0=%s，key 7=%s%n",
                map.size(), map.get(0), map.get(7));
    }

    /**
     * 写入两个哈希相同但 equals 不相等的键，观察链表碰撞处理。
     */
    static void observeCollision() {
        Map<CollisionKey, String> map = new HashMap<>();
        CollisionKey firstKey = new CollisionKey(1, COLLISION_HASH);
        CollisionKey secondKey = new CollisionKey(2, COLLISION_HASH);
        map.put(firstKey, "first");
        map.put(secondKey, "second");

        System.out.printf("碰撞后 size=%d，first=%s，second=%s%n",
                map.size(), map.get(firstKey), map.get(secondKey));
    }

    /**
     * 在容量达到 64 的前提下写入九个碰撞键，稳定触发 treeifyBin 分支。
     */
    static void observeTreeification() {
        Map<CollisionKey, String> map = new HashMap<>(64);
        for (int i = 0; i < 9; i++) {
            map.put(new CollisionKey(i, COLLISION_HASH), "tree-value-" + i);
        }

        String lastValue = map.get(new CollisionKey(8, COLLISION_HASH));
        System.out.printf("九个碰撞键写入后 size=%d，最后一个值=%s%n", map.size(), lastValue);
    }

    /**
     * 观察 null 键哈希为零，以及再次写入时覆盖原映射的行为。
     */
    static void observeNullKey() {
        Map<String, String> map = new HashMap<>();
        map.put(null, "first-null-value");
        String oldValue = map.put(null, "second-null-value");

        System.out.printf("null 键覆盖返回=%s，当前值=%s，size=%d%n",
                oldValue, map.get(null), map.size());
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
