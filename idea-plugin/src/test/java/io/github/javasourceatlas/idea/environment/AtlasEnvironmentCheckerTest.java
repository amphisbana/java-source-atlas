package io.github.javasourceatlas.idea.environment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证首次使用环境检查中的地址和项目结构判断。
 */
class AtlasEnvironmentCheckerTest {

    /**
     * 验证教程地址只接受带主机名的 HTTP 或 HTTPS 地址。
     */
    @Test
    void shouldValidateDocumentationUrl() {
        assertTrue(AtlasEnvironmentChecker.isSupportedDocumentationUrl("http://source.shaojie.wang/atlas"));
        assertTrue(AtlasEnvironmentChecker.isSupportedDocumentationUrl("https://docs.example.com/atlas/"));
        assertFalse(AtlasEnvironmentChecker.isSupportedDocumentationUrl("file:///tmp/atlas"));
        assertFalse(AtlasEnvironmentChecker.isSupportedDocumentationUrl("source.shaojie.wang/atlas"));
        assertFalse(AtlasEnvironmentChecker.isSupportedDocumentationUrl("https://docs.example.com/atlas?preview=true"));
        assertFalse(AtlasEnvironmentChecker.isSupportedDocumentationUrl("https://docs.example.com/atlas#section"));
        assertFalse(AtlasEnvironmentChecker.isSupportedDocumentationUrl(""));
    }

    /**
     * 验证只有 Maven 根文件、索引和 Labs 同时存在时才识别为完整仓库。
     *
     * @param temporaryDirectory JUnit 创建的临时目录
     * @throws Exception 文件创建失败时抛出
     */
    @Test
    void shouldInspectAtlasRepositoryLayout(@TempDir Path temporaryDirectory) throws Exception {
        Files.createFile(temporaryDirectory.resolve("pom.xml"));
        AtlasEnvironmentChecker.ProjectFiles mavenOnly =
                AtlasEnvironmentChecker.inspectProjectFiles(temporaryDirectory.toString());
        assertTrue(mavenOnly.rootPom());
        assertFalse(mavenOnly.atlasRepository());

        Files.createDirectories(temporaryDirectory.resolve("source-index"));
        Files.createDirectories(temporaryDirectory.resolve("labs"));
        AtlasEnvironmentChecker.ProjectFiles complete =
                AtlasEnvironmentChecker.inspectProjectFiles(temporaryDirectory.toString());
        assertTrue(complete.sourceIndex());
        assertTrue(complete.labs());
        assertTrue(complete.atlasRepository());
    }
}
