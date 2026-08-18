package io.github.javasourceatlas.idea.browser;

import com.intellij.openapi.Disposable;

import javax.swing.JComponent;

/**
 * 隔离工具窗口与具体浏览器实现，保证 JCEF 缺失时主界面仍可创建。
 */
public interface AtlasEmbeddedBrowser extends Disposable {

    /**
     * 获取可放入教程页的浏览器组件。
     *
     * @return 浏览器 Swing 组件
     */
    JComponent component();

    /**
     * 在内嵌浏览器中加载指定教程地址。
     *
     * @param url 教程地址
     */
    void loadUrl(String url);
}
