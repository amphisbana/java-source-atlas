package io.github.javasourceatlas.idea.version;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.model.AtlasVersionInfo;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从项目 SDK 和依赖类路径识别 JDK、Spring Framework 与 Spring Boot 版本。
 */
public final class AtlasVersionDetector {

    private static final Pattern SPRING_FRAMEWORK_JAR = Pattern.compile(
            "spring-(?:core|beans|context)-([0-9][A-Za-z0-9.+_-]*)\\.jar"
    );
    private static final Pattern SPRING_BOOT_JAR = Pattern.compile(
            "spring-boot-([0-9][A-Za-z0-9.+_-]*)\\.jar"
    );

    /**
     * 工具类不需要创建实例。
     */
    private AtlasVersionDetector() {
    }

    /**
     * 只读取项目 SDK 版本，不扫描依赖类路径，供高频编辑器动作选择 JDK 专题视图。
     *
     * @param project 当前项目
     * @return SDK 版本文本；未配置时返回“未配置”
     */
    public static String projectJdkVersion(Project project) {
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        return sdk == null || sdk.getVersionString() == null
                ? "未配置"
                : sdk.getVersionString();
    }

    /**
     * 扫描项目 SDK 与依赖根目录并返回展示用版本信息。
     *
     * @param project 当前项目
     * @return 版本信息
     */
    public static AtlasVersionInfo detect(Project project) {
        // 2026-09-02：原逻辑在完整版本检测中单独读取 SDK，新版本视图与检测流程统一复用同一入口。
        // Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        // String jdkVersion = sdk == null || sdk.getVersionString() == null
        //         ? "未配置"
        //         : sdk.getVersionString();
        String jdkVersion = projectJdkVersion(project);

        String springVersion = null;
        String springBootVersion = null;
        VirtualFile[] roots = OrderEnumerator.orderEntries(project)
                .librariesOnly()
                .recursively()
                .classes()
                .getRoots();
        for (VirtualFile root : roots) {
            String path = root.getPath();
            if (springVersion == null) {
                springVersion = extractVersion(SPRING_FRAMEWORK_JAR, path);
            }
            if (springBootVersion == null) {
                springBootVersion = extractVersion(SPRING_BOOT_JAR, path);
            }
            if (springVersion != null && springBootVersion != null) {
                break;
            }
        }
        return new AtlasVersionInfo(
                jdkVersion,
                springVersion == null ? "未检测到" : springVersion,
                springBootVersion == null ? "未检测到" : springBootVersion
        );
    }

    /**
     * 在后台非阻塞读操作中检测项目版本，并在界面线程交付结果。
     *
     * @param project  当前项目
     * @param parent   控制任务生命周期的父级对象
     * @param consumer 界面线程结果处理器
     */
    public static void detectAsync(
            Project project,
            Disposable parent,
            Consumer<AtlasVersionInfo> consumer
    ) {
        ReadAction.nonBlocking((Callable<AtlasVersionInfo>) () -> detect(project))
                .inSmartMode(project)
                .expireWith(parent)
                .coalesceBy(parent, AtlasVersionDetector.class)
                .finishOnUiThread(ModalityState.any(), consumer)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 根据专题基线与当前项目版本生成简短匹配提示。
     *
     * @param topic   当前专题
     * @param version 项目版本信息
     * @return 匹配提示
     */
    public static String compatibilityHint(AtlasTopic topic, AtlasVersionInfo version) {
        if (topic == null) {
            return "选择专题后显示版本匹配结果";
        }

        String primary = topic.primaryVersion().toLowerCase(java.util.Locale.ROOT);
        if (primary.contains("spring boot")) {
            return compareVersion(
                    topic,
                    version.springBootVersion(),
                    AtlasVersionMatcher.VersionKind.SPRING_BOOT
            );
        }
        if (primary.contains("spring framework")) {
            return compareVersion(
                    topic,
                    version.springVersion(),
                    AtlasVersionMatcher.VersionKind.SPRING_FRAMEWORK
            );
        }
        if (primary.contains("openjdk")) {
            return compareVersion(topic, version.jdkVersion(), AtlasVersionMatcher.VersionKind.JDK);
        }
        return "教程基线：" + topic.primaryVersion();
    }

    /**
     * 从依赖根路径中提取 Jar 版本。
     *
     * @param pattern Jar 名称模式
     * @param path    类路径根
     * @return 版本；未命中时返回 null
     */
    private static String extractVersion(Pattern pattern, String path) {
        Matcher matcher = pattern.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 生成框架专题的教程基线与项目版本对照文本。
     *
     * @param primaryVersion 教程基线
     * @param projectVersion 项目依赖版本
     * @return 对照文本
     */
    private static String compareVersion(
            AtlasTopic topic,
            String projectVersion,
            AtlasVersionMatcher.VersionKind kind
    ) {
        if (projectVersion == null || "未检测到".equals(projectVersion)) {
            return "教程基线：" + topic.primaryVersion() + "；项目未检测到对应版本";
        }
        AtlasVersionMatcher.VersionMatch match = AtlasVersionMatcher.match(
                kind,
                topic.primaryVersion(),
                topic.compatibleVersions(),
                projectVersion
        );
        return AtlasVersionMatcher.formatHint(topic.primaryVersion(), projectVersion, match);
    }
}
