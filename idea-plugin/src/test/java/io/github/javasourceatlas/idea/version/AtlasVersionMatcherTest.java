package io.github.javasourceatlas.idea.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 JDK、Spring Framework 与 Spring Boot 的结构化版本匹配。
 */
class AtlasVersionMatcherTest {

    /**
     * 验证旧式 1.8 SDK 字符串与 OpenJDK 8u 教程属于同一基线。
     */
    @Test
    void shouldParseLegacyJdkVersion() {
        AtlasVersionMatcher.VersionMatch match = AtlasVersionMatcher.match(
                AtlasVersionMatcher.VersionKind.JDK,
                "OpenJDK 8u",
                List.of("OpenJDK 17"),
                "java version \"1.8.0_412\""
        );
        assertEquals(AtlasVersionMatcher.VersionRelation.EXACT, match.relation());
    }

    /**
     * 验证 JDK major 不同但可命中索引声明的兼容版本。
     */
    @Test
    void shouldMatchCompatibleJdkMajor() {
        AtlasVersionMatcher.VersionMatch match = AtlasVersionMatcher.match(
                AtlasVersionMatcher.VersionKind.JDK,
                "OpenJDK 8u",
                List.of("OpenJDK 17", "OpenJDK 21"),
                "openjdk version \"17.0.12\""
        );
        assertEquals(AtlasVersionMatcher.VersionRelation.MAJOR_MISMATCH, match.relation());
        assertEquals("OpenJDK 17", match.compatibleVersion());
    }

    /**
     * 验证 Spring 同 minor 的 patch 差异可被单独识别并命中 x 范围。
     */
    @Test
    void shouldDetectSpringPatchDifference() {
        AtlasVersionMatcher.VersionMatch match = AtlasVersionMatcher.match(
                AtlasVersionMatcher.VersionKind.SPRING_FRAMEWORK,
                "Spring Framework 5.3.39",
                List.of("Spring Framework 5.3.x"),
                "5.3.31"
        );
        assertEquals(AtlasVersionMatcher.VersionRelation.SAME_MINOR, match.relation());
        assertEquals("Spring Framework 5.3.x", match.compatibleVersion());
    }

    /**
     * 验证同一 Spring major 下的 minor 差异。
     */
    @Test
    void shouldDetectSpringMinorDifference() {
        AtlasVersionMatcher.VersionMatch match = AtlasVersionMatcher.match(
                AtlasVersionMatcher.VersionKind.SPRING_FRAMEWORK,
                "Spring Framework 5.3.39",
                List.of(),
                "5.2.24.RELEASE"
        );
        assertEquals(AtlasVersionMatcher.VersionRelation.SAME_MAJOR, match.relation());
    }

    /**
     * 验证 Spring Boot 完整三段版本可以精确匹配。
     */
    @Test
    void shouldMatchExactSpringBootVersion() {
        AtlasVersionMatcher.VersionMatch match = AtlasVersionMatcher.match(
                AtlasVersionMatcher.VersionKind.SPRING_BOOT,
                "Spring Boot 2.7.18",
                List.of("Spring Boot 2.7.x"),
                "2.7.18"
        );
        assertEquals(AtlasVersionMatcher.VersionRelation.EXACT, match.relation());
    }
}
