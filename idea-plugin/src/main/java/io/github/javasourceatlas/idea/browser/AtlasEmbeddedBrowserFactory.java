package io.github.javasourceatlas.idea.browser;

import com.intellij.openapi.diagnostic.Logger;

import java.lang.reflect.InvocationTargetException;

/**
 * 按当前 IDEA 运行环境创建可用的内嵌浏览器。
 */
public final class AtlasEmbeddedBrowserFactory {

    private static final Logger LOG = Logger.getInstance(AtlasEmbeddedBrowserFactory.class);
    private static final String JCEF_BROWSER_CLASS =
            "io.github.javasourceatlas.idea.browser.JcefAtlasEmbeddedBrowser";

    /**
     * 工具类不允许实例化。
     */
    private AtlasEmbeddedBrowserFactory() {
    }

    /**
     * 通过反射延迟加载 JCEF 适配器，JCEF 模块缺失或初始化失败时返回空。
     *
     * <p>反射边界很重要：IDEA 2026.2 将 JCEF 拆成独立模块，若在常驻 UI 类中直接引用
     * JCEF 类型，插件类加载器会在面板创建前抛出 {@link NoClassDefFoundError}。</p>
     *
     * @return 可用的内嵌浏览器；当前环境不支持时返回 {@code null}
     */
    public static AtlasEmbeddedBrowser create() {
        try {
            Class<?> implementation = Class.forName(
                    JCEF_BROWSER_CLASS,
                    true,
                    AtlasEmbeddedBrowserFactory.class.getClassLoader()
            );
            return (AtlasEmbeddedBrowser) implementation.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException exception) {
            logUnavailable(exception.getCause() == null ? exception : exception.getCause());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            logUnavailable(exception);
        }
        return null;
    }

    /**
     * 记录内嵌浏览器降级原因，便于排查环境问题且不阻断工具窗口。
     *
     * @param exception JCEF 加载或初始化异常
     */
    private static void logUnavailable(Throwable exception) {
        LOG.warn("Source Atlas 内嵌浏览器不可用，已降级为系统浏览器入口", exception);
    }
}
