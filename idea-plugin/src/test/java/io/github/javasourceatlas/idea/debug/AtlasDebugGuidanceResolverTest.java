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
        assertEquals(breakpoint.evidenceId(), guidance.evidenceId());
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

    /**
     * 验证 JDK 21 统一 acquire 方法命中后仍能关联 JDK 8 基线专题的证据和下一断点。
     */
    @Test
    void shouldResolveAdaptedJdk21BreakpointGuidance() {
        AtlasIndexService index = new AtlasIndexService();
        AtlasTopic baseline = index.findById("openjdk8-reentrantlock-aqs").orElseThrow();
        String adaptedMethod = "AbstractQueuedSynchronizer.acquire(Node,int,boolean,boolean,boolean,long)";
        AtlasBreakpointState state = new AtlasBreakpointState();
        state.register(baseline.topicId(), "file:///AbstractQueuedSynchronizer.java", 310, adaptedMethod);

        AtlasDebugGuidance guidance = AtlasDebugGuidanceResolver.resolve(
                index,
                state,
                "file:///AbstractQueuedSynchronizer.java",
                310,
                "Java version 21.0.4"
        ).orElseThrow();

        assertEquals(adaptedMethod, guidance.breakpointMethod());
        assertEquals("condition-reacquire-concurrency", guidance.evidenceId());
        assertTrue(guidance.variables().contains("spins"));
    }

    /**
     * 验证项目切换到未验证 JDK 后不会把工作区残留的旧版断点误识别为可执行证据。
     */
    @Test
    void shouldIgnoreBreakpointOnUnsupportedJdkVersion() {
        AtlasIndexService index = new AtlasIndexService();
        AtlasTopic topic = index.findById("openjdk8-java-util-hashmap").orElseThrow();
        AtlasBreakpoint breakpoint = topic.breakpoints().getFirst();
        AtlasBreakpointState state = new AtlasBreakpointState();
        state.register(topic.topicId(), "file:///HashMap.java", 120, breakpoint.method());

        assertTrue(AtlasDebugGuidanceResolver.resolve(
                index,
                state,
                "file:///HashMap.java",
                120,
                "11.0.17"
        ).isEmpty());
    }
}
