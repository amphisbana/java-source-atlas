package io.github.javasourceatlas.idea.version;

import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 JDK 8 基线专题可以安全切换到 JDK 17/21 固定源码和方法视图。
 */
class AtlasTopicVersionResolverTest {

    /**
     * 验证 HashMap 在 JDK 17 中切换源码 Tag、模块化路径和 getNode 签名。
     */
    @Test
    void shouldAdaptHashMapToJdk17() {
        AtlasTopic baseline = topic("openjdk8-java-util-hashmap");

        AtlasTopicVersion version = AtlasTopicVersionResolver.resolve(
                baseline,
                "openjdk version \"17.0.14\""
        );

        assertEquals(AtlasTopicVersion.Status.ADAPTED, version.status());
        assertTrue(version.sourceActionsAllowed());
        assertEquals("OpenJDK 17", version.topic().primaryVersion());
        assertEquals("jdk-17+35", version.topic().sourceRef());
        assertEquals(
                "src/java.base/share/classes/java/util/HashMap.java",
                version.topic().source().sourcePath()
        );
        assertTrue(version.topic().entryPoints().stream()
                .anyMatch(entry -> "getNode(Object)".equals(entry.method())));
        assertFalse(version.topic().entryPoints().stream()
                .anyMatch(entry -> "getNode(int,Object)".equals(entry.method())));
    }

    /**
     * 验证 AQS JDK 21 断点切换到统一 acquire，并同步新版观察变量。
     */
    @Test
    void shouldAdaptAqsBreakpointToJdk21UnifiedAcquire() {
        AtlasTopic baseline = topic("openjdk8-reentrantlock-aqs");

        AtlasTopicVersion version = AtlasTopicVersionResolver.resolve(baseline, "21.0.4");
        AtlasBreakpoint breakpoint = version.topic().breakpoints().stream()
                .filter(item -> item.method().contains("acquire(Node,int,boolean"))
                .findFirst()
                .orElseThrow();

        assertEquals("OpenJDK 21", version.topic().primaryVersion());
        assertTrue(breakpoint.variables().contains("spins"));
        assertTrue(breakpoint.scenario().contains("shared=true"));
        assertEquals(1, version.topic().breakpoints().stream()
                .filter(item -> item.method().contains("acquire(Node,int,boolean"))
                .count());
        assertFalse(version.topic().breakpoints().stream()
                .anyMatch(item -> "acquireQueued(Node,int)".equals(item.method())));
    }

    /**
     * 验证全部 JDK 专题在 8、17、21 三条已声明基线上都能生成可执行版本视图。
     */
    @Test
    void shouldResolveCompleteJdkVersionMatrix() {
        AtlasIndexService index = new AtlasIndexService();
        java.util.List<AtlasTopic> jdkTopics = index.topics().stream()
                .filter(item -> item.primaryVersion().startsWith("OpenJDK"))
                .toList();

        assertTrue(jdkTopics.size() >= 20);
        for (AtlasTopic topic : jdkTopics) {
            AtlasTopicVersion jdk8 = AtlasTopicVersionResolver.resolve(topic, "1.8.0_412");
            AtlasTopicVersion jdk17 = AtlasTopicVersionResolver.resolve(topic, "17.0.14");
            AtlasTopicVersion jdk21 = AtlasTopicVersionResolver.resolve(topic, "21.0.4");

            assertEquals(AtlasTopicVersion.Status.BASELINE, jdk8.status(), topic.topicId());
            assertEquals(AtlasTopicVersion.Status.ADAPTED, jdk17.status(), topic.topicId());
            assertEquals(AtlasTopicVersion.Status.ADAPTED, jdk21.status(), topic.topicId());
            assertEquals("jdk-17+35", jdk17.topic().sourceRef(), topic.topicId());
            assertEquals("jdk-21+35", jdk21.topic().sourceRef(), topic.topicId());
            assertTrue(jdk17.sourceActionsAllowed(), topic.topicId());
            assertTrue(jdk21.sourceActionsAllowed(), topic.topicId());
        }
    }

    /**
     * 验证未声明兼容的 JDK 版本只保留基线阅读，不允许创建源码断点。
     */
    @Test
    void shouldBlockUnverifiedJdkVersion() {
        AtlasTopic baseline = topic("openjdk8-java-util-hashmap");

        AtlasTopicVersion version = AtlasTopicVersionResolver.resolve(baseline, "11.0.17");

        assertEquals(AtlasTopicVersion.Status.UNSUPPORTED, version.status());
        assertFalse(version.sourceActionsAllowed());
        assertEquals(baseline, version.topic());
    }

    /**
     * 验证 Spring 固定基线不受项目 JDK 版本视图影响。
     */
    @Test
    void shouldKeepSpringTopicBaseline() {
        AtlasTopic baseline = topic("spring-framework-5-3-ioc");

        AtlasTopicVersion version = AtlasTopicVersionResolver.resolve(baseline, "21");

        assertEquals(AtlasTopicVersion.Status.BASELINE, version.status());
        assertTrue(version.sourceActionsAllowed());
        assertEquals(baseline, version.topic());
    }

    /**
     * 从共享索引取得测试专题。
     *
     * @param topicId 专题编号
     * @return 专题
     */
    private AtlasTopic topic(String topicId) {
        return new AtlasIndexService().findById(topicId).orElseThrow();
    }
}
