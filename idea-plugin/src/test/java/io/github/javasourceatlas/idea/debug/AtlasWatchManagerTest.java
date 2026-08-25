package io.github.javasourceatlas.idea.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证推荐观察表达式进入 Watches 前的规范化规则。
 */
class AtlasWatchManagerTest {

    /**
     * 验证空表达式会被过滤，重复表达式只保留第一次出现的位置。
     */
    @Test
    void shouldNormalizeWatchExpressions() {
        List<String> normalized = AtlasWatchManager.normalizedExpressions(
                java.util.Arrays.asList(" size ", "", "table", "size", null, "table[index]")
        );

        assertEquals(List.of("size", "table", "table[index]"), normalized);
    }
}
