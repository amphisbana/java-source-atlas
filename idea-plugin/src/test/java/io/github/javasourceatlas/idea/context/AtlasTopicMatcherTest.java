package io.github.javasourceatlas.idea.context;

import io.github.javasourceatlas.idea.index.AtlasIndexService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证共享源码类的候选排序和歧义保留行为。
 */
class AtlasTopicMatcherTest {

    /**
     * 验证主源码类和项目版本可以在没有方法入口时给出稳定优先级。
     */
    @Test
    void shouldPreferPrimarySourceForSharedClass() {
        AtlasTopicMatcher.Resolution resolution = AtlasTopicMatcher.resolve(
                new AtlasIndexService(),
                "java.util.HashMap",
                null,
                "17"
        );

        assertEquals("openjdk8-java-util-hashmap", resolution.topic().topicId());
        assertEquals("openjdk8-java-util-hashmap", resolution.candidates().getFirst().topicId());
    }

    /**
     * 验证多个专题得分相同时不会静默选择第一个，而是把候选交给界面选择器。
     */
    @Test
    void shouldKeepAmbiguousCandidatesWithoutSelectingFirst() {
        AtlasTopicMatcher.Resolution resolution = AtlasTopicMatcher.resolve(
                new AtlasIndexService(),
                "java.lang.Thread",
                null,
                "17"
        );

        assertNull(resolution.topic());
        assertNull(resolution.entryPoint());
        assertTrue(resolution.candidates().size() > 1);
    }
}
