package io.github.javasourceatlas.idea.context;

import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证共享源码类由用户选择专题后的入口恢复规则。
 */
class AtlasEditorContextSupportTest {

    /**
     * 验证歧义上下文会保留全部排序候选，不再要求预先存在唯一专题。
     */
    @Test
    void shouldExposeAmbiguousTopicCandidates() {
        AtlasIndexService index = new AtlasIndexService();
        List<AtlasTopic> candidates = index.findBySourceClassCandidates("java.lang.Thread");
        AtlasEditorContext context = new AtlasEditorContext(
                "java.lang.Thread",
                "join",
                null,
                null,
                candidates,
                "java.lang.Thread.join(long)"
        );

        assertTrue(candidates.size() > 1);
        assertEquals(candidates, AtlasEditorContextSupport.availableTopics(context));
    }

    /**
     * 验证用户选定专题后使用完整参数签名恢复对应重载入口。
     */
    @Test
    void shouldResolveSelectedTopicEntryByMethodSignature() {
        AtlasIndexService index = new AtlasIndexService();
        AtlasTopic topic = index.findById("openjdk8-thread-locksupport").orElseThrow();
        AtlasEditorContext context = new AtlasEditorContext(
                "java.lang.Thread",
                "join",
                null,
                null,
                index.findBySourceClassCandidates("java.lang.Thread"),
                "java.lang.Thread.join(long)"
        );

        AtlasEntryPoint entryPoint = AtlasEditorContextSupport.resolveEntryPoint(topic, context, "");

        assertEquals("join(long)", entryPoint.method());
    }
}
