package io.github.javasourceatlas.idea.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 验证编辑器上下文键能够区分同名重载方法。
 */
class AtlasEditorContextTest {

    /**
     * 验证参数类型变化会触发新的上下文刷新。
     */
    @Test
    void shouldDistinguishOverloadedMethods() {
        AtlasEditorContext first = new AtlasEditorContext(
                "java.util.Map",
                "put",
                null,
                null,
                List.of(),
                "java.util.Map.put(java.lang.Object,java.lang.Object)"
        );
        AtlasEditorContext second = new AtlasEditorContext(
                "java.util.Map",
                "put",
                null,
                null,
                List.of(),
                "java.util.Map.put(java.lang.Object)"
        );

        assertNotEquals(first.contextKey(), second.contextKey());
    }
}
