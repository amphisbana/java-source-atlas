package io.github.javasourceatlas.idea.debug;

import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasTopic;

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

        AtlasTopic topic = index.findById(location.topicId).orElse(null);
        if (topic == null) {
            return Optional.empty();
        }
        AtlasBreakpoint breakpoint = topic.breakpoints().stream()
                .filter(item -> item.method().equals(location.signature))
                .findFirst()
                .orElse(null);
        if (breakpoint == null) {
            return Optional.empty();
        }

        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
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
