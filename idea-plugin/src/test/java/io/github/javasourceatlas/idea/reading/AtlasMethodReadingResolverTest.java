package io.github.javasourceatlas.idea.reading;

import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasLab;
import io.github.javasourceatlas.idea.model.AtlasMethodRelation;
import io.github.javasourceatlas.idea.model.AtlasSource;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证当前方法讲解的结构化字段、旧索引回退和关联方法解析。
 */
class AtlasMethodReadingResolverTest {

    /**
     * 验证结构化讲解优先于兼容文案，并按方法和关系去重。
     */
    @Test
    void shouldPreferStructuredMethodReading() {
        AtlasMethodRelation relation = new AtlasMethodRelation(
                "resize()",
                "容量协作者",
                "超过阈值后迁移节点",
                null
        );
        AtlasEntryPoint putVal = new AtlasEntryPoint(
                "putVal(int,K,V,boolean,boolean)",
                "/hashmap/put",
                "写入主流程",
                null,
                "统一处理空桶、覆盖和碰撞。",
                List.of("定位桶", "处理冲突"),
                List.of("用 Hook 支撑子类扩展"),
                List.of("新增和覆盖的计数不同"),
                List.of(relation, relation)
        );
        AtlasEntryPoint resize = new AtlasEntryPoint("resize()", "/hashmap/resize", "迁移节点", null);
        AtlasTopic topic = topic(List.of(putVal, resize));

        AtlasMethodReading reading = AtlasMethodReadingResolver.resolve(topic, putVal);

        assertEquals("统一处理空桶、覆盖和碰撞。", reading.summary());
        assertEquals(List.of("定位桶", "处理冲突"), reading.process());
        assertEquals(1, reading.relatedMethods().size());
        assertEquals(resize, AtlasMethodReadingResolver.resolveRelatedEntry(topic, relation).orElseThrow());
    }

    /**
     * 验证旧索引没有新字段时仍能显示步骤、专题设计亮点和前后入口。
     */
    @Test
    void shouldBuildFallbackReadingForLegacyEntry() {
        AtlasEntryPoint first = new AtlasEntryPoint("put(K,V)", "/hashmap/put", "公开入口", null);
        AtlasEntryPoint current = new AtlasEntryPoint("putVal(int,K,V,boolean,boolean)", "/hashmap/put", "写入主流程", null);
        AtlasEntryPoint next = new AtlasEntryPoint("resize()", "/hashmap/resize", "迁移节点", null);
        AtlasTopic topic = topic(List.of(first, current, next));

        AtlasMethodReading reading = AtlasMethodReadingResolver.resolve(topic, current);

        assertEquals("写入主流程", reading.summary());
        assertEquals(3, reading.process().size());
        assertTrue(reading.designInsights().contains("容量、哈希和冲突结构协同演进。"));
        assertFalse(reading.pitfalls().isEmpty());
        assertEquals(List.of("put(K,V)", "resize()"), reading.relatedMethods().stream()
                .map(AtlasMethodRelation::method)
                .toList());
    }

    /**
     * 创建阅读解析测试使用的最小专题。
     *
     * @param entryPoints 测试入口
     * @return 最小 HashMap 专题
     */
    private AtlasTopic topic(List<AtlasEntryPoint> entryPoints) {
        return new AtlasTopic(
                "hashmap",
                "HashMap 源码",
                "OpenJDK 8",
                "jdk8",
                "容量、哈希和冲突结构协同演进。",
                "为什么能够稳定迁移？",
                "能复述写入和扩容路径。",
                "",
                "",
                List.of("8"),
                new AtlasLab("labs/jdk", "HashMapLab", "HashMapLab.java"),
                new AtlasSource("java.util.HashMap", "HashMap.java"),
                List.of(),
                null,
                entryPoints,
                List.of(),
                List.of()
        );
    }
}
