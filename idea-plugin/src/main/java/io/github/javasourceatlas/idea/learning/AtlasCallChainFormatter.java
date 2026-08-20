package io.github.javasourceatlas.idea.learning;

import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.function.Function;

/**
 * 把专题入口序列格式化为可以粘贴到笔记或 Issue 的阅读调用链。
 */
public final class AtlasCallChainFormatter {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasCallChainFormatter() {
    }

    /**
     * 生成包含版本、当前入口、所属类、阅读目的和教程地址的调用链文本。
     *
     * @param topic       当前专题
     * @param current     当前选中的源码入口
     * @param urlResolver 文档路径到完整地址的转换器
     * @return 可直接复制的调用链；专题为空时返回空字符串
     */
    public static String format(
            AtlasTopic topic,
            AtlasEntryPoint current,
            Function<String, String> urlResolver
    ) {
        if (topic == null) {
            return "";
        }

        StringBuilder result = new StringBuilder()
                .append(topic.title()).append("\n")
                .append("教程基线：").append(topic.primaryVersion())
                .append(" / ").append(topic.sourceRef()).append("\n");
        if (current != null) {
            result.append("当前入口：").append(current.method()).append("\n");
        }
        result.append("\n源码阅读调用链：\n");

        for (int index = 0; index < topic.entryPoints().size(); index++) {
            AtlasEntryPoint entryPoint = topic.entryPoints().get(index);
            String marker = entryPoint.equals(current) ? "  <- 当前" : "";
            String owner = entryPoint.effectiveSourceClass(topic);
            String url = urlResolver == null ? entryPoint.document() : urlResolver.apply(entryPoint.document());
            result.append(index + 1).append(". ")
                    .append(owner).append('#').append(entryPoint.method()).append(marker).append("\n")
                    .append("   目的：").append(entryPoint.purpose()).append("\n")
                    .append("   讲解：").append(url).append("\n");
        }
        return result.toString().stripTrailing();
    }
}
