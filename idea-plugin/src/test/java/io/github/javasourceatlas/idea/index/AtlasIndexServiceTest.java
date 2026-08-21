package io.github.javasourceatlas.idea.index;

import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.model.AtlasTopicRelation;
import io.github.javasourceatlas.idea.model.AtlasVersionComparison;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证构建期生成的共享专题索引可以被插件完整读取。
 */
class AtlasIndexServiceTest {

    /**
     * 验证 29 个专题、12 个版本对比元数据、HashMap 入口和 Spring IOC 关联类均进入插件资源。
     */
    @Test
    void shouldLoadGeneratedTopicIndex() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/atlas-index/topics.json")) {
            assertTrue(inputStream != null, "构建产物应包含合并后的专题索引");
            List<AtlasTopic> topics = AtlasIndexService.loadTopics(inputStream);

            assertEquals(29, topics.size());
            assertTrue(topics.stream().allMatch(topic -> topic.lab() != null));
            assertTrue(topics.stream().allMatch(topic -> topic.evidence().size() >= 3));
            assertTrue(topics.stream().flatMap(topic -> topic.evidence().stream())
                    .allMatch(evidence -> evidence.id() != null
                            && evidence.kind() != null
                            && evidence.expectedOutcome() != null));

            // 2026-08-20：版本对比数据由 source-index 生成，必须确认插件资源没有丢失或错配。
            Set<String> comparisonIds = topics.stream()
                    .map(AtlasTopic::versionComparison)
                    .filter(comparison -> comparison != null)
                    .map(AtlasVersionComparison::id)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(
                    "hashmap",
                    "concurrent-hashmap",
                    "thread-local",
                    "synchronized-monitor",
                    "completable-future",
                    "classloader-service-loader",
                    "aqs-reentrantlock",
                    "thread-pool-executor",
                    "future-task",
                    "bytebuffer-selector",
                    "reference-weakhashmap",
                    "stream-spliterator"
            ), comparisonIds);
            assertTrue(topics.stream()
                    .map(AtlasTopic::versionComparison)
                    .filter(comparison -> comparison != null)
                    .allMatch(comparison -> comparison.supportedVersions().containsAll(List.of("8", "17", "21"))));

            AtlasTopic hashMap = findTopic(topics, "openjdk8-java-util-hashmap");
            assertTrue(hashMap.entryPoints().stream().anyMatch(entry -> "putVal".equals(entry.simpleMethodName())));
            assertEquals(
                    "io.github.javasourceatlas.jdk.collection.HashMapDebugLab",
                    hashMap.lab().mainClass()
            );
            assertEquals("shouldReplaceValueWithoutIncreasingSize", hashMap.evidence().getFirst().testMethod());
            assertEquals("put-main", hashMap.evidence().getFirst().id());
            assertEquals("openjdk8-java-util-linkedhashmap", hashMap.recommendedNextTopicId());

            AtlasTopic springIoc = findTopic(topics, "spring-framework-5-3-ioc");
            assertTrue(springIoc.containsSourceClass(
                    "org.springframework.context.annotation.ConfigurationClassParser"
            ));
            assertFalse(springIoc.breakpoints().isEmpty());
        }
    }

    /**
     * 验证下一站与反向前置关系都能从同一份推荐关系图推导，无需维护第二套关联数据。
     */
    @Test
    void shouldDeriveRecommendedAndRelatedTopics() {
        AtlasIndexService index = new AtlasIndexService();
        AtlasTopic hashMap = index.findById("openjdk8-java-util-hashmap").orElseThrow();

        assertEquals(
                "openjdk8-java-util-linkedhashmap",
                index.recommendedNext(hashMap).orElseThrow().topicId()
        );
        List<AtlasTopicRelation> relations = index.relatedTopics(hashMap);
        assertTrue(relations.stream().anyMatch(relation ->
                "推荐下一步".equals(relation.label())
                        && "openjdk8-java-util-linkedhashmap".equals(relation.topic().topicId())));
        assertTrue(relations.stream().anyMatch(relation ->
                "前置专题".equals(relation.label())
                        && "openjdk8-java-util-arraylist".equals(relation.topic().topicId())));
    }

    /**
     * 验证最近阅读编号会按输入顺序解析，并忽略已经从索引删除的历史编号。
     */
    @Test
    void shouldResolveTopicsByIdsInInputOrder() {
        AtlasIndexService index = new AtlasIndexService();

        List<AtlasTopic> topics = index.topicsByIds(List.of(
                "openjdk8-java-util-treemap",
                "missing-topic",
                "openjdk8-java-util-hashmap"
        ));

        assertEquals(List.of(
                "openjdk8-java-util-treemap",
                "openjdk8-java-util-hashmap"
        ), topics.stream().map(AtlasTopic::topicId).toList());
    }

    /**
     * 验证推荐断点能匹配精确入口，并为所属类相同的简单方法名提供讲解回退。
     */
    @Test
    void shouldFindBreakpointExplanation() {
        AtlasIndexService index = new AtlasIndexService();
        AtlasTopic topic = index.findById("openjdk8-java-util-hashmap").orElseThrow();
        AtlasBreakpoint breakpoint = topic.breakpoints().stream()
                .filter(item -> item.method().startsWith("putVal"))
                .findFirst()
                .orElseThrow();

        assertTrue(index.explanationForBreakpoint(topic, breakpoint).isPresent());
        assertEquals("put-main", index.evidenceForBreakpoint(topic, breakpoint).orElseThrow().id());
        assertFalse(index.explanationForBreakpoint(
                topic,
                new AtlasBreakpoint("missingMethod()", "无对应讲解", List.of(), null, null)
        ).isPresent());
    }

    /**
     * 按专题编号从测试集合中取得唯一专题。
     *
     * @param topics 全部专题
     * @param topicId 目标编号
     * @return 目标专题
     */
    private AtlasTopic findTopic(List<AtlasTopic> topics, String topicId) {
        return topics.stream()
                .filter(topic -> topicId.equals(topic.topicId()))
                .findFirst()
                .orElseThrow();
    }
}
