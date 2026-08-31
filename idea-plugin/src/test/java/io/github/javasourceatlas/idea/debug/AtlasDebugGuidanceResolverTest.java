package io.github.javasourceatlas.idea.debug;

import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证暂停位置只会解析 Atlas 自己登记的断点，并补齐证据和下一步。
 */
class AtlasDebugGuidanceResolverTest {

    /**
     * 验证已登记断点会返回当前结论、观察变量和下一推荐断点。
     */
    @Test
    void shouldResolveManagedBreakpointGuidance() {
        AtlasIndexService index = new AtlasIndexService();
        AtlasTopic topic = index.findById("openjdk8-java-util-hashmap").orElseThrow();
        AtlasBreakpoint breakpoint = topic.breakpoints().getFirst();
        AtlasBreakpointState state = new AtlasBreakpointState();
        state.register(topic.topicId(), "file:///HashMap.java", 120, breakpoint.method());

        AtlasDebugGuidance guidance = AtlasDebugGuidanceResolver.resolve(
                index,
                state,
                "file:///HashMap.java",
                120
        ).orElseThrow();

        assertEquals(topic.topicId(), guidance.topicId());
        assertEquals(breakpoint.method(), guidance.breakpointMethod());
        assertEquals(breakpoint.variables(), guidance.variables());
        assertFalse(guidance.claim().isBlank());
        assertFalse(guidance.expectedOutcome().isBlank());
        assertEquals(topic.breakpoints().get(1).method(), guidance.nextBreakpointMethod());
    }

    /**
     * 验证同一文件中的普通用户断点不会被误识别为 Atlas 教学场景。
     */
    @Test
    void shouldIgnoreUnmanagedBreakpointLocation() {
        AtlasIndexService index = new AtlasIndexService();
        AtlasBreakpointState state = new AtlasBreakpointState();

        assertTrue(AtlasDebugGuidanceResolver.resolve(
                index,
                state,
                "file:///HashMap.java",
                120
        ).isEmpty());
    }
}
