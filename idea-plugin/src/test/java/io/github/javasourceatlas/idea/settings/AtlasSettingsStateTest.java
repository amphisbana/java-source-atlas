package io.github.javasourceatlas.idea.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证教程站点默认值、升级迁移和自定义地址拼接。
 */
class AtlasSettingsStateTest {

    /**
     * 验证旧版本保存的本地默认地址在升级后迁移到新的线上站点。
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
     * 验证旧版 GitHub Pages 默认地址在自定义域名启用后自动迁移。
     */
    @Test
    void shouldMigrateLegacyPublicDefaultUrl() {
        AtlasSettingsState savedState = new AtlasSettingsState();
        savedState.docsBaseUrl = "https://amphisbana.github.io/java-source-atlas";
        AtlasSettingsState currentState = new AtlasSettingsState();

        currentState.loadState(savedState);

        assertEquals("http://source.shaojie.wang/atlas", currentState.docsBaseUrl);
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

    /**
     * 验证环境向导只在首次打开时自动展示，并能随插件设置持久化恢复。
     */
    @Test
    void shouldRememberEnvironmentGuideWasShown() {
        AtlasSettingsState currentState = new AtlasSettingsState();
        assertTrue(currentState.shouldShowEnvironmentGuide());

        currentState.markEnvironmentGuideSeen();
        assertFalse(currentState.shouldShowEnvironmentGuide());

        AtlasSettingsState restored = new AtlasSettingsState();
        restored.loadState(currentState);
        assertFalse(restored.shouldShowEnvironmentGuide());
    }

    /**
     * 验证当前方法跟随和定位后翻译偏好可以随 IDEA 设置恢复。
     */
    @Test
    void shouldRememberMethodReadingOptions() {
        AtlasSettingsState saved = new AtlasSettingsState();
        saved.followEditorForReading = false;
        saved.translateAfterSourceNavigation = true;
        AtlasSettingsState restored = new AtlasSettingsState();

        restored.loadState(saved);

        assertFalse(restored.followEditorForReading);
        assertTrue(restored.translateAfterSourceNavigation);
    }
}
