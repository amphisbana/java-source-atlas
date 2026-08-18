package io.github.javasourceatlas.jdk.collection;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 HashMap 教学案例依赖的公开可观察行为。
 */
class HashMapBehaviorTest {

    /**
     * 验证相同键会覆盖旧值，并且不会增加映射数量。
     */
    @Test
    void shouldReplaceValueWithoutIncreasingSize() {
        Map<String, String> map = new HashMap<>();

        assertNull(map.put("language", "Java 8"));
        assertEquals("Java 8", map.put("language", "Java 17"));
        assertEquals("Java 17", map.get("language"));
        assertEquals(1, map.size());
    }

    /**
     * 验证连续扩容不会丢失此前写入的映射。
     */
    @Test
    void shouldKeepMappingsAfterResize() {
        Map<Integer, String> map = new HashMap<>(2);

        for (int i = 0; i < 1_000; i++) {
            map.put(i, "value-" + i);
        }

        assertEquals(1_000, map.size());
        for (int i = 0; i < 1_000; i++) {
            assertEquals("value-" + i, map.get(i));
        }
    }

    /**
     * 验证多个键发生哈希碰撞后仍可通过 equals 分别读取。
     */
    @Test
    void shouldKeepCollidingKeysDistinct() {
        Map<CollisionKey, Integer> map = new HashMap<>(64);

        for (int i = 0; i < 20; i++) {
            map.put(new CollisionKey(i, 7), i);
        }

        assertEquals(20, map.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(i, map.get(new CollisionKey(i, 7)));
        }
    }

    /**
     * 验证 HashMap 支持一个 null 键以及 null 值。
     */
    @Test
    void shouldSupportNullKeyAndValue() {
        Map<String, String> map = new HashMap<>();

        map.put(null, "null-key");
        map.put("null-value", null);

        assertEquals("null-key", map.get(null));
        assertNull(map.get("null-value"));
        assertTrue(map.containsKey("null-value"));
    }
}

