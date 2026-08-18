package io.github.javasourceatlas.idea.browser;

import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;

import javax.swing.JComponent;

/**
 * 使用 IntelliJ JCEF 实现教程内嵌阅读的适配器。
 *
 * <p>本类单独存放所有 JCEF 类型引用，避免工具窗口主类在缺少 JCEF 模块时加载失败。</p>
 */
public final class JcefAtlasEmbeddedBrowser implements AtlasEmbeddedBrowser {

    private final JBCefBrowser browser;

    /**
     * 检查 JCEF 支持状态并创建浏览器。
     */
    public JcefAtlasEmbeddedBrowser() {
        if (!JBCefApp.isSupported()) {
            throw new IllegalStateException("当前 IDEA 运行环境不支持 JCEF");
        }
        browser = new JBCefBrowser();
    }

    /**
     * 获取 JCEF 对应的 Swing 容器组件。
     *
     * @return 浏览器组件
     */
    @Override
    public JComponent component() {
        return browser.getComponent();
    }

    /**
     * 让 JCEF 浏览器加载指定教程地址。
     *
     * @param url 教程地址
     */
    @Override
    public void loadUrl(String url) {
        browser.loadURL(url);
    }

    /**
     * 释放 JCEF 原生浏览器资源。
     */
    @Override
    public void dispose() {
        browser.dispose();
    }
}
