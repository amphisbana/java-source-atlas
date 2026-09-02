package io.github.javasourceatlas.idea.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Atlas 断点归属记录的去重、专题清理和状态恢复。
 */
class AtlasBreakpointStateTest {

    /**
     * 验证同一文件同一行重复登记时只保留最新专题归属。
     */
    @Test
    void shouldKeepOneOwnerForSameBreakpointLocation() {
        AtlasBreakpointState state = new AtlasBreakpointState();

        state.register("hashmap", "file:///HashMap.java", 100, "putVal(...)");
        state.register("concurrenthashmap", "file:///HashMap.java", 100, "transfer(...)");

        assertEquals(1, state.locations().size());
        assertEquals("concurrenthashmap", state.locations().getFirst().topicId);
        assertEquals("transfer(...)", state.locations().getFirst().signature);
    }

    /**
     * 验证按专题清理不会删除其他专题的断点归属记录。
     */
    @Test
    void shouldRemoveOnlyRequestedTopic() {
        AtlasBreakpointState state = new AtlasBreakpointState();
        state.register("hashmap", "file:///HashMap.java", 100, "resize()");
        state.register("treemap", "file:///TreeMap.java", 80, "fixAfterInsertion(...)");

        state.removeTopic("hashmap");

        assertEquals(1, state.locations().size());
        assertEquals("treemap", state.locations().getFirst().topicId);
    }

    /**
     * 验证恢复旧工作区状态时忽略无效文件位置并复制有效记录。
     */
    @Test
    void shouldNormalizeRestoredLocations() {
        AtlasBreakpointState saved = new AtlasBreakpointState();
        saved.register("hashmap", "file:///HashMap.java", 100, "resize()");
        AtlasBreakpointState.ManagedBreakpoint invalid = new AtlasBreakpointState.ManagedBreakpoint();
        invalid.fileUrl = "";
        saved.managedBreakpoints.add(invalid);
        AtlasBreakpointState restored = new AtlasBreakpointState();

        restored.loadState(saved);
        saved.managedBreakpoints.getFirst().topicId = "changed";

        assertEquals(1, restored.locations().size());
        assertEquals("hashmap", restored.locations().getFirst().topicId);
    }

    /**
     * 验证复用用户断点时保存非所有者标记，后续 Atlas 创建同位置断点时可以升级为所有者。
     */
    @Test
    void shouldTrackReferencedBreakpointOwnership() {
        AtlasBreakpointState state = new AtlasBreakpointState();

        state.registerReference("hashmap", "file:///HashMap.java", 100, "resize()");
        assertFalse(state.locations().getFirst().owned);

        state.register("hashmap", "file:///HashMap.java", 100, "resize()");
        assertTrue(state.locations().getFirst().owned);
    }
}
