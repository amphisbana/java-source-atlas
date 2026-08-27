package io.github.javasourceatlas.idea.environment;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 检查教程站点和当前项目是否具备源码阅读、Lab 调试所需的基础环境。
 */
public final class AtlasEnvironmentChecker {

    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 3_000;

    /**
     * 工具类不需要创建实例。
     */
    private AtlasEnvironmentChecker() {
    }

    /**
     * 在后台探测教程站点，网络访问不会阻塞 IDEA 界面线程。
     *
     * @param parent  控制探测任务生命周期的父级对象
     * @param baseUrl 教程站点根地址
     * @param consumer 探测结果的界面线程回调
     */
    public static void checkDocumentationAsync(
            Disposable parent,
            String baseUrl,
            Consumer<DocumentationStatus> consumer
    ) {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            DocumentationStatus status = probeDocumentation(baseUrl);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!Disposer.isDisposed(parent)) {
                    consumer.accept(status);
                }
            });
        });
    }

    /**
     * 检查项目根目录是否同时包含 Maven 根工程、源码索引和 Lab 目录。
     *
     * @param basePath IDEA 当前项目根路径
     * @return 项目结构检查结果
     */
    public static ProjectFiles inspectProjectFiles(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return new ProjectFiles(false, false, false, false);
        }
        try {
            Path root = Path.of(basePath);
            boolean rootPom = Files.isRegularFile(root.resolve("pom.xml"));
            boolean sourceIndex = Files.isDirectory(root.resolve("source-index"));
            boolean labs = Files.isDirectory(root.resolve("labs"));
            return new ProjectFiles(rootPom, sourceIndex, labs, rootPom && sourceIndex && labs);
        } catch (InvalidPathException ignored) {
            return new ProjectFiles(false, false, false, false);
        }
    }

    /**
     * 校验教程地址是否为可以直接访问的 HTTP 或 HTTPS 根地址。
     *
     * @param baseUrl 待检查地址
     * @return 地址结构是否合法
     */
    public static boolean isSupportedDocumentationUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(baseUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            // 2026-08-27：原逻辑允许查询参数和锚点，后续拼接教程路由时会生成无效地址。
            // return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
            return ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    /**
     * 访问教程首页并把网络异常转换为可直接展示的诊断结果。
     *
     * @param baseUrl 教程站点根地址
     * @return 教程站点状态
     */
    private static DocumentationStatus probeDocumentation(String baseUrl) {
        if (!isSupportedDocumentationUrl(baseUrl)) {
            return new DocumentationStatus(false, "地址格式无效，请在插件设置中填写 HTTP 或 HTTPS 地址");
        }

        HttpURLConnection connection = null;
        try {
            connection = openConnection(URI.create(baseUrl.trim()));
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_BAD_METHOD
                    || responseCode == HttpURLConnection.HTTP_NOT_IMPLEMENTED) {
                connection.disconnect();
                connection = openConnection(URI.create(baseUrl.trim()));
                connection.setRequestMethod("GET");
                responseCode = connection.getResponseCode();
            }
            boolean available = responseCode >= 200 && responseCode < 400;
            return new DocumentationStatus(
                    available,
                    available ? "可访问（HTTP " + responseCode + "）" : "返回 HTTP " + responseCode
            );
        } catch (IOException | IllegalArgumentException exception) {
            String message = exception.getMessage();
            return new DocumentationStatus(
                    false,
                    message == null || message.isBlank()
                            ? "连接失败，请检查网络或教程地址"
                            : "连接失败：" + message
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 创建带超时和重定向支持的教程站点连接，默认使用轻量 HEAD 请求。
     *
     * @param uri 教程站点地址
     * @return 尚未发起请求的 HTTP 连接
     * @throws IOException 无法创建连接时抛出
     */
    private static HttpURLConnection openConnection(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("HEAD");
        connection.setRequestProperty("User-Agent", "Java-Source-Atlas-IDEA-Plugin");
        return connection;
    }

    /**
     * 保存教程站点可用性和面向用户的诊断说明。
     *
     * @param available 是否可以访问
     * @param detail     诊断说明
     */
    public record DocumentationStatus(boolean available, String detail) {
    }

    /**
     * 保存当前项目根目录中的 Maven、索引和 Lab 文件状态。
     *
     * @param rootPom        是否存在根 pom.xml
     * @param sourceIndex    是否存在 source-index 目录
     * @param labs           是否存在 labs 目录
     * @param atlasRepository 是否为完整 Atlas 仓库
     */
    public record ProjectFiles(
            boolean rootPom,
            boolean sourceIndex,
            boolean labs,
            boolean atlasRepository
    ) {
    }
}
