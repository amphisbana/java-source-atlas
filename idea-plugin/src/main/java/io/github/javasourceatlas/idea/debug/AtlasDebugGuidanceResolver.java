package io.github.javasourceatlas.idea.debug;

import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.version.AtlasTopicVersion;
import io.github.javasourceatlas.idea.version.AtlasTopicVersionResolver;

import java.util.List;
import java.util.Optional;

/**
 * 将 IDEA 当前暂停位置解析为稳定、可测试的源码学习提示。
 */
public final class AtlasDebugGuidanceResolver {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasDebugGuidanceResolver() {
    }

    /**
     * 根据文件和零基行号查找 Atlas 管理的断点，并补齐专题证据与下一断点。
     *
     * @param index   共享源码索引
     * @param state   当前项目的 Atlas 断点归属
     * @param fileUrl 当前暂停源码文件 URL
     * @param line    当前暂停零基行号
     * @return 命中的调试引导；普通用户断点或过期索引返回空
     */
    public static Optional<AtlasDebugGuidance> resolve(
            AtlasIndexService index,
            AtlasBreakpointState state,
            String fileUrl,
            int line
    ) {
        return resolve(index, state, fileUrl, line, null);
    }

    /**
     * 根据项目 JDK 版本解析暂停位置，保证新版 JDK 方法映射与断点登记使用同一专题视图。
     *
     * @param index             共享源码索引
     * @param state             当前项目的 Atlas 断点归属
     * @param fileUrl           当前暂停源码文件 URL
     * @param line              当前暂停零基行号
     * @param projectJdkVersion IDEA 项目 SDK 版本文本
     * @return 命中的版本感知调试引导
     */
    public static Optional<AtlasDebugGuidance> resolve(
            AtlasIndexService index,
            AtlasBreakpointState state,
            String fileUrl,
            int line,
            String projectJdkVersion
    ) {
        if (index == null || state == null || fileUrl == null || fileUrl.isBlank() || line < 0) {
            return Optional.empty();
        }
        AtlasBreakpointState.ManagedBreakpoint location = state.locations().stream()
                .filter(item -> line == item.line && fileUrl.equals(item.fileUrl))
                .findFirst()
                .orElse(null);
        if (location == null) {
            return Optional.empty();
        }

        // 2026-09-02：原逻辑始终使用 JDK 8 基线专题，无法识别 JDK 17/21 的方法签名变化。
        // AtlasTopic topic = index.findById(location.topicId).orElse(null);
        AtlasTopic baselineTopic = index.findById(location.topicId).orElse(null);
        if (baselineTopic == null) {
            return Optional.empty();
        }
        AtlasTopic topic = baselineTopic;
        if (projectJdkVersion != null) {
            AtlasTopicVersion version = AtlasTopicVersionResolver.resolve(baselineTopic, projectJdkVersion);
            if (!version.sourceActionsAllowed()) {
                return Optional.empty();
            }
            topic = version.topic();
        }
        return resolve(topic, state, fileUrl, line);
    }

    /**
     * 在已经确定专题版本视图时解析调试引导，供版本适配与 IDEA Platform 集成测试复用。
     *
     * @param topic   当前项目版本专题
     * @param state   当前项目的 Atlas 断点归属
     * @param fileUrl 当前暂停源码文件 URL
     * @param line    当前暂停零基行号
     * @return 命中的调试引导
     */
    static Optional<AtlasDebugGuidance> resolve(
            AtlasTopic topic,
            AtlasBreakpointState state,
            String fileUrl,
            int line
    ) {
        if (topic == null || state == null || fileUrl == null || fileUrl.isBlank() || line < 0) {
            return Optional.empty();
        }
        AtlasBreakpointState.ManagedBreakpoint location = state.locations().stream()
                .filter(item -> line == item.line && fileUrl.equals(item.fileUrl))
                .filter(item -> topic.topicId().equals(item.topicId))
                .findFirst()
                .orElse(null);
        if (location == null) {
            return Optional.empty();
        }
        AtlasBreakpoint breakpoint = topic.breakpoints().stream()
                .filter(item -> item.method().equals(location.signature))
                .findFirst()
                .orElse(null);
        if (breakpoint == null) {
            return Optional.empty();
        }

        AtlasEvidence evidence = topic.findEvidenceById(breakpoint.evidenceId()).orElse(null);
        int currentIndex = topic.breakpoints().indexOf(breakpoint);
        String nextMethod = currentIndex >= 0 && currentIndex + 1 < topic.breakpoints().size()
                ? topic.breakpoints().get(currentIndex + 1).method()
                : "";
        String claim = evidence == null || blank(evidence.claim())
                ? breakpoint.scenario()
                : evidence.claim();
        String expected = evidence == null || blank(evidence.expectedOutcome())
                ? "按观察变量核对当前场景的状态变化"
                : evidence.expectedOutcome();
        return Optional.of(new AtlasDebugGuidance(
                topic.topicId(),
                topic.title(),
                breakpoint.evidenceId(),
                breakpoint.method(),
                breakpoint.scenario(),
                breakpoint.variables(),
                claim,
                expected,
                nextMethod
        ));
    }

    /**
     * 判断索引文本是否没有可展示内容。
     *
     * @param value 待判断文本
     * @return 是否为空
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
