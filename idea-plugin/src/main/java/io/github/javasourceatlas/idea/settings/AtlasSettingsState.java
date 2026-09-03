package io.github.javasourceatlas.idea.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * 保存教程站点地址，便于本地预览和未来线上站点之间切换。
 */
@Service
@State(name = "JavaSourceAtlasSettings", storages = @Storage("java-source-atlas.xml"))
public final class AtlasSettingsState implements PersistentStateComponent<AtlasSettingsState> {

    public static final String DEFAULT_DOCS_BASE_URL = "http://source.shaojie.wang/atlas";
    private static final String LEGACY_LOCAL_DOCS_BASE_URL = "http://127.0.0.1:4180";
    private static final String LEGACY_PUBLIC_DOCS_BASE_URL = "https://amphisbana.github.io/java-source-atlas";

    public String docsBaseUrl = DEFAULT_DOCS_BASE_URL;
    public boolean environmentGuideSeen;
    public boolean followEditorForReading = true;
    public boolean translateAfterSourceNavigation;

    /**
     * 取得应用级设置实例。
     *
     * @return 设置实例
     */
    public static AtlasSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(AtlasSettingsState.class);
    }

    /**
     * 返回需要持久化的当前状态。
     *
     * @return 当前状态
     */
    @Override
    public AtlasSettingsState getState() {
        return this;
    }

    /**
     * 从 IDEA 配置文件恢复设置。
     *
     * @param state 已保存状态
     */
    @Override
    public void loadState(@NotNull AtlasSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
        // 2026-08-19：线上文档站切换到自定义域名，旧版默认地址升级后迁移到新站点；用户自定义地址不覆盖。
        if (docsBaseUrl == null
                || docsBaseUrl.isBlank()
                || LEGACY_LOCAL_DOCS_BASE_URL.equals(docsBaseUrl.trim())
                || LEGACY_PUBLIC_DOCS_BASE_URL.equals(docsBaseUrl.trim())) {
            docsBaseUrl = DEFAULT_DOCS_BASE_URL;
        }
    }

    /**
     * 把文档路由拼接为可由浏览器打开的完整地址。
     *
     * @param documentPath source-index 中的文档路径
     * @return 完整教程地址
     */
    public String documentationUrl(String documentPath) {
        String base = docsBaseUrl == null || docsBaseUrl.isBlank()
                ? DEFAULT_DOCS_BASE_URL
                : docsBaseUrl.trim();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = documentPath == null || documentPath.isBlank()
                ? "/"
                : (documentPath.startsWith("/") ? documentPath : "/" + documentPath);
        return normalizedBase + normalizedPath;
    }

    /**
     * 判断是否需要在插件首次打开时展示环境检查页。
     *
     * @return 尚未展示过环境检查页时返回 true
     */
    public boolean shouldShowEnvironmentGuide() {
        return !environmentGuideSeen;
    }

    /**
     * 记录环境检查页已经展示，后续打开插件时恢复常规专题导航。
     */
    public void markEnvironmentGuideSeen() {
        environmentGuideSeen = true;
    }
}
