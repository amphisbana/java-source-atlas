package io.github.javasourceatlas.idea.model;

/**
 * 描述 IDEA 当前编辑器光标所在的 Java 类、方法和匹配结果。
 *
 * @param className  当前完整类名
 * @param methodName 当前简单方法名
 * @param topic      命中的专题
 * @param entryPoint 命中的方法入口
 */
public record AtlasEditorContext(
        String className,
        String methodName,
        AtlasTopic topic,
        AtlasEntryPoint entryPoint
) {

    /**
     * 构造用于判断编辑器上下文是否变化的稳定键。
     *
     * @return 类名与方法名组合
     */
    public String contextKey() {
        return String.valueOf(className) + "#" + String.valueOf(methodName);
    }
}
