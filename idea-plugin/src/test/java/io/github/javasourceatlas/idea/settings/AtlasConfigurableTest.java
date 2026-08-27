package io.github.javasourceatlas.idea.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证教程地址设置页保存前的格式校验。
 */
class AtlasConfigurableTest {

    /**
     * 验证合法 HTTP 与 HTTPS 地址可以保存。
     */
    @Test
    void shouldAcceptHttpDocumentationUrls() {
        assertNull(AtlasConfigurable.documentationUrlValidationMessage("http://source.shaojie.wang/atlas"));
        assertNull(AtlasConfigurable.documentationUrlValidationMessage("https://docs.example.com/atlas"));
    }

    /**
     * 验证空地址、无协议地址和本地文件地址会在保存前被拒绝。
     */
    @Test
    void shouldRejectUnsupportedDocumentationUrls() {
        assertNotNull(AtlasConfigurable.documentationUrlValidationMessage(""));
        assertNotNull(AtlasConfigurable.documentationUrlValidationMessage("source.shaojie.wang/atlas"));
        assertNotNull(AtlasConfigurable.documentationUrlValidationMessage("file:///tmp/atlas"));
        assertNotNull(AtlasConfigurable.documentationUrlValidationMessage("https://docs.example.com/atlas#section"));
    }
}
