package io.github.javasourceatlas.idea.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证教程站点默认值、升级迁移和自定义地址拼接。
 */
class AtlasSettingsStateTest {

    /**
     * 验证 0.2.1 保存的本地默认地址在升级后迁移到公开站点。
     */
    @Test
    void shouldMigrateLegacyLocalDefaultUrl() {
        AtlasSettingsState savedState = new AtlasSettingsState();
        savedState.docsBaseUrl = "http://127.0.0.1:4180";
        AtlasSettingsState currentState = new AtlasSettingsState();

        currentState.loadState(savedState);

        assertEquals(AtlasSettingsState.DEFAULT_DOCS_BASE_URL, currentState.docsBaseUrl);
    }

    /**
     * 验证用户主动填写的自定义教程地址不会被升级迁移覆盖。
     */
    @Test
    void shouldKeepCustomDocsBaseUrl() {
        AtlasSettingsState savedState = new AtlasSettingsState();
        savedState.docsBaseUrl = "https://docs.example.com/atlas/";
        AtlasSettingsState currentState = new AtlasSettingsState();

        currentState.loadState(savedState);

        assertEquals("https://docs.example.com/atlas/", currentState.docsBaseUrl);
        assertEquals(
                "https://docs.example.com/atlas/jdk/collections/hashmap/#putval",
                currentState.documentationUrl("jdk/collections/hashmap/#putval")
        );
    }
}
