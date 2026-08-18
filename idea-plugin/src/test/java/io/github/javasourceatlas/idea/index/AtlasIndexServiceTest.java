package io.github.javasourceatlas.idea.index;

import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证构建期生成的共享专题索引可以被插件完整读取。
 */
class AtlasIndexServiceTest {

    /**
     * 验证 29 个专题、HashMap 入口和 Spring IOC 关联类均进入插件资源。
     */
    @Test
    void shouldLoadGeneratedTopicIndex() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/atlas-index/topics.json")) {
            assertTrue(inputStream != null, "构建产物应包含合并后的专题索引");
            List<AtlasTopic> topics = AtlasIndexService.loadTopics(inputStream);

            assertEquals(29, topics.size());
            assertTrue(topics.stream().allMatch(topic -> topic.lab() != null));
            AtlasTopic hashMap = findTopic(topics, "openjdk8-java-util-hashmap");
            assertTrue(hashMap.entryPoints().stream().anyMatch(entry -> "putVal".equals(entry.simpleMethodName())));
            assertEquals(
                    "io.github.javasourceatlas.jdk.collection.HashMapDebugLab",
                    hashMap.lab().mainClass()
            );

            AtlasTopic springIoc = findTopic(topics, "spring-framework-5-3-ioc");
            assertTrue(springIoc.containsSourceClass(
                    "org.springframework.context.annotation.ConfigurationClassParser"
            ));
            assertFalse(springIoc.breakpoints().isEmpty());
        }
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
