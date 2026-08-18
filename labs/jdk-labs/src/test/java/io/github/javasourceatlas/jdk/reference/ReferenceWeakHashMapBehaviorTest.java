package io.github.javasourceatlas.jdk.reference;

import org.junit.jupiter.api.Test;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ReferenceQueue 与 WeakHashMap 教学案例依赖的确定性公开行为。
 */
class ReferenceWeakHashMapBehaviorTest {

    /**
     * 验证 clear 不自动入队，而显式 enqueue 会清 referent 并把 Reference 放入注册队列。
     *
     * @throws InterruptedException 等待队列元素时被中断
     */
    @Test
    void shouldSeparateClearFromEnqueue() throws InterruptedException {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Object clearReferent = new Object();
        WeakReference<Object> clearOnly = new WeakReference<>(clearReferent, queue);

        clearOnly.clear();

        // 保留强引用到 clear 返回后，排除 GC 抢先处理并自动入队的竞争。
        assertNotNull(clearReferent);
        assertNull(clearOnly.get());
        assertNull(queue.poll());

        Object directReferent = new Object();
        WeakReference<Object> directEnqueue = new WeakReference<>(directReferent, queue);
        assertSame(directReferent, directEnqueue.get());
        assertTrue(directEnqueue.enqueue());
        // 保留强引用到 enqueue 返回后，排除 GC 提前清除 referent 对断言的干扰。
        assertNotNull(directReferent);
        assertNull(directEnqueue.get());
        assertSame(directEnqueue, queue.remove(1_000L));
        assertFalse(directEnqueue.enqueue());
    }

    /**
     * 验证 PhantomReference 无论 referent 是否仍被强引用，get 都返回 null。
     *
     * @throws InterruptedException 等待手动入队元素时被中断
     */
    @Test
    void shouldExposeNullFromPhantomReference() throws InterruptedException {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Object referent = new Object();
        PhantomReference<Object> phantom = new PhantomReference<>(referent, queue);

        assertNull(phantom.get());
        assertTrue(phantom.enqueue());
        assertSame(phantom, queue.remove(1_000L));
        assertNotNull(referent);
    }

    /**
     * 验证 WeakHashMap 支持 null key 与 null value，并能区分存在映射和查询缺失。
     */
    @Test
    void shouldSupportNullKeyAndValue() {
        Map<Object, Object> map = new WeakHashMap<>();

        map.put(null, null);

        assertTrue(map.containsKey(null));
        assertNull(map.get(null));
        assertEquals(1, map.size());
    }

    /**
     * 验证 key 仍存活时，WeakHashMap 与普通 Map 一样使用 equals/hashCode 查找。
     */
    @Test
    void shouldUseEqualsWhileKeysAreAlive() {
        Map<EqualityKey, String> map = new WeakHashMap<>();
        EqualityKey stored = new EqualityKey(7);
        EqualityKey lookup = new EqualityKey(7);

        map.put(stored, "metadata");

        assertEquals("metadata", map.get(lookup));
        assertTrue(map.containsKey(lookup));
    }

    /**
     * 验证 value 强引用 key 时，Map 仍通过 value 建立可达路径，弱 Entry 无法让 key 消失。
     */
    @Test
    void shouldKeepKeyReachableThroughValueBackReference() {
        Map<Object, OwnerMetadata> map = new WeakHashMap<>();
        Object key = new Object();
        WeakReference<Object> observer = new WeakReference<>(key);
        map.put(key, new OwnerMetadata(key));
        key = null;

        // 清空外部强变量后，owner 只能先沿 map -> value -> key 路径取得。
        Object ownerFromMap = map.values().iterator().next().getOwner();

        assertNotNull(ownerFromMap);
        assertSame(ownerFromMap, observer.get());
        assertEquals(1, map.size());
    }

    /**
     * 验证显式 remove 与 clear 仍遵循普通 Map 的确定性结构修改语义。
     */
    @Test
    void shouldRemoveMappingsExplicitly() {
        Map<EqualityKey, String> map = new WeakHashMap<>();
        EqualityKey first = new EqualityKey(1);
        EqualityKey second = new EqualityKey(2);
        map.put(first, "first");
        map.put(second, "second");

        assertEquals("first", map.remove(new EqualityKey(1)));
        assertEquals(1, map.size());
        map.clear();
        assertTrue(map.isEmpty());
    }

    /**
     * 提供稳定 equals/hashCode 的测试 key，避免依赖对象身份碰巧相同。
     */
    private static final class EqualityKey {
        private final int id;

        /**
         * 创建指定业务标识的 key。
         *
         * @param id 业务标识
         */
        private EqualityKey(int id) {
            this.id = id;
        }

        /**
         * 按业务标识比较 key。
         *
         * @param other 待比较对象
         * @return 是否代表同一个业务 key
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof EqualityKey)) {
                return false;
            }
            EqualityKey that = (EqualityKey) other;
            return id == that.id;
        }

        /**
         * 返回与业务标识一致的哈希值。
         *
         * @return key 哈希值
         */
        @Override
        public int hashCode() {
            return id;
        }
    }

    /**
     * 模拟 value 对 WeakHashMap key 的反向强引用。
     */
    private static final class OwnerMetadata {
        private final Object owner;

        /**
         * 保存 owner key。
         *
         * @param owner 被回指的 key
         */
        private OwnerMetadata(Object owner) {
            this.owner = owner;
        }

        /**
         * 返回 value 强引用的 owner。
         *
         * @return owner key
         */
        private Object getOwner() {
            return owner;
        }
    }
}
