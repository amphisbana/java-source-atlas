package io.github.javasourceatlas.idea.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 描述 IDEA 当前编辑器光标所在的 Java 类、方法和匹配结果。
 *
 * @param className  当前完整类名
 * @param methodName 当前简单方法名
 * @param topic      命中的专题
 * @param entryPoint 命中的方法入口
 * @param topicCandidates 当前类对应的排序专题候选
 * @param methodSignature 当前方法完整签名，用于识别同名重载
 */
public record AtlasEditorContext(
        String className,
        String methodName,
        AtlasTopic topic,
        AtlasEntryPoint entryPoint,
        List<AtlasTopic> topicCandidates,
        String methodSignature
) {

    /**
     * 保留旧调用方使用的四参数构造方式。
     *
     * @param className  当前完整类名
     * @param methodName 当前简单方法名
     * @param topic      命中的专题
     * @param entryPoint 命中的方法入口
     */
    public AtlasEditorContext(
            String className,
            String methodName,
            AtlasTopic topic,
            AtlasEntryPoint entryPoint
    ) {
        this(
                className,
                methodName,
                topic,
                entryPoint,
                topic == null ? List.of() : List.of(topic),
                null
        );
    }

    /**
     * 规范化候选列表，避免编辑器上下文持有可变集合。
     */
    public AtlasEditorContext {
        topicCandidates = topicCandidates == null ? List.of() : List.copyOf(topicCandidates);
        methodSignature = methodSignature == null ? "" : methodSignature;
    }

    /**
     * 构造用于判断编辑器上下文是否变化的稳定键，包含重载签名和候选专题。
     *
     * @return 类名、方法签名和候选专题组合
     */
    public String contextKey() {
        String candidateKey = topicCandidates.stream()
                .map(AtlasTopic::topicId)
                .collect(Collectors.joining(","));
        String methodKey = methodSignature.isBlank() ? methodName : methodSignature;
        String entryKey = entryPoint == null ? "" : entryPoint.method();
        // 2026-08-26：原逻辑只使用类名和简单方法名，同名重载会被错误视为同一上下文。
        // return String.valueOf(className) + "#" + String.valueOf(methodName);
        return String.valueOf(className)
                + "#" + String.valueOf(methodKey)
                + "#" + entryKey
                + "#" + candidateKey;
    }
}
