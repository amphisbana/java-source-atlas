package io.github.javasourceatlas.idea.browser;

import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.JComponent;

/**
 * 使用 IntelliJ JCEF 实现教程内嵌阅读的适配器。
 *
 * <p>本类单独存放所有 JCEF 类型引用，避免工具窗口主类在缺少 JCEF 模块时加载失败。</p>
 */
public final class JcefAtlasEmbeddedBrowser implements AtlasEmbeddedBrowser {

    private static final String SCROLL_TO_FRAGMENT_SCRIPT = """
            (() => {
              const scrollToFragment = () => {
                if (!window.location.hash) {
                  return;
                }
                let id = window.location.hash.substring(1);
                try {
                  id = decodeURIComponent(id);
                } catch (ignored) {
                  // 非法转义交给浏览器按原始锚点处理，避免中断后续重试。
                }
                const target = document.getElementById(id);
                if (target) {
                  target.scrollIntoView({ block: 'start' });
                }
              };
              scrollToFragment();
              window.requestAnimationFrame(scrollToFragment);
              window.setTimeout(scrollToFragment, 120);
              window.setTimeout(scrollToFragment, 500);
            })();
            """;

    private final JBCefBrowser browser;
    private final CefLoadHandlerAdapter loadHandler;
    private final CefDisplayHandlerAdapter displayHandler;

    /**
     * 检查 JCEF 支持状态并创建浏览器。
     */
    public JcefAtlasEmbeddedBrowser() {
        if (!JBCefApp.isSupported()) {
            throw new IllegalStateException("当前 IDEA 运行环境不支持 JCEF");
        }
        browser = new JBCefBrowser();
        loadHandler = new CefLoadHandlerAdapter() {
            /**
             * 主文档加载结束后再次定位锚点，覆盖 VitePress 水合导致的滚动位置变化。
             *
             * @param cefBrowser 当前 JCEF 浏览器
             * @param frame      完成加载的框架
             * @param statusCode HTTP 状态码
             */
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int statusCode) {
                if (frame.isMain()) {
                    scrollToCurrentFragment(cefBrowser);
                }
            }
        };
        displayHandler = new CefDisplayHandlerAdapter() {
            /**
             * 同一文档只变化锚点时不会触发完整加载，通过地址变化事件补做定位。
             *
             * @param cefBrowser 当前 JCEF 浏览器
             * @param frame      地址变化的框架
             * @param url        最新地址
             */
            @Override
            public void onAddressChange(CefBrowser cefBrowser, CefFrame frame, String url) {
                if (frame.isMain()) {
                    scrollToCurrentFragment(cefBrowser);
                }
            }
        };
        browser.getJBCefClient().addLoadHandler(loadHandler, browser.getCefBrowser());
        browser.getJBCefClient().addDisplayHandler(displayHandler, browser.getCefBrowser());
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
        // 2026-08-27：原逻辑只加载 URL，VitePress 完成水合后可能把 JCEF 的原生锚点滚动复位。
        // browser.loadURL(url);
        browser.loadURL(url);
        // 相同 URL 被再次打开时可能没有加载或地址事件，主动执行一次保证仍能回到目标标题。
        scrollToCurrentFragment(browser.getCefBrowser());
    }

    /**
     * 在页面内执行锚点定位脚本，并通过短暂重试等待动态正文完成布局。
     *
     * @param cefBrowser 当前 JCEF 浏览器
     */
    private void scrollToCurrentFragment(CefBrowser cefBrowser) {
        if (!cefBrowser.hasDocument()) {
            return;
        }
        cefBrowser.executeJavaScript(
                SCROLL_TO_FRAGMENT_SCRIPT,
                cefBrowser.getURL(),
                0
        );
    }

    /**
     * 释放 JCEF 原生浏览器资源。
     */
    @Override
    public void dispose() {
        browser.getJBCefClient().removeLoadHandler(loadHandler, browser.getCefBrowser());
        browser.getJBCefClient().removeDisplayHandler(displayHandler, browser.getCefBrowser());
        browser.dispose();
    }
}
