package io.github.javasourceatlas.idea.context;

import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.List;

/**
 * 为编辑器动作统一整理唯一专题、歧义候选和源码入口恢复规则。
 */
public final class AtlasEditorContextSupport {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasEditorContextSupport() {
    }

    /**
     * 返回编辑器上下文中可以由用户选择的专题。
     *
     * @param context 当前编辑器上下文
     * @return 唯一命中专题或排序后的歧义候选
     */
    public static List<AtlasTopic> availableTopics(AtlasEditorContext context) {
        if (context == null) {
            return List.of();
        }
        if (context.topic() != null) {
            return List.of(context.topic());
        }
        return context.topicCandidates();
    }

    /**
     * 为用户选定的专题恢复最合适的源码入口，优先当前完整方法签名，再使用阅读进度。
     *
     * @param topic           用户选定的专题
     * @param editorContext   当前编辑器上下文
     * @param lastEntryMethod 上次阅读的方法签名
     * @return 本次动作应使用的源码入口；专题没有入口时返回 null
     */
    public static AtlasEntryPoint resolveEntryPoint(
            AtlasTopic topic,
            AtlasEditorContext editorContext,
            String lastEntryMethod
    ) {
        if (topic == null || topic.entryPoints().isEmpty()) {
            return null;
        }
        if (editorContext != null
                && topic.equals(editorContext.topic())
                && editorContext.entryPoint() != null) {
            return editorContext.entryPoint();
        }
        if (editorContext != null
                && editorContext.className() != null
                && editorContext.methodName() != null) {
            AtlasEntryPoint matched = AtlasMethodMatcher.findBestEntryPoint(
                    topic,
                    editorContext.className(),
                    editorContext.methodName(),
                    editorContext.methodSignature()
            ).orElse(null);
            if (matched != null) {
                return matched;
            }
        }
        if (lastEntryMethod != null && !lastEntryMethod.isBlank()) {
            AtlasEntryPoint recent = topic.entryPoints().stream()
                    .filter(entryPoint -> entryPoint.method().equals(lastEntryMethod))
                    .findFirst()
                    .orElse(null);
            if (recent != null) {
                return recent;
            }
        }
        return topic.entryPoints().getFirst();
    }
}
