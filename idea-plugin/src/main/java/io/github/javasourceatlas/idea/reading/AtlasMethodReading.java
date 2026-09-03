package io.github.javasourceatlas.idea.reading;

import io.github.javasourceatlas.idea.model.AtlasMethodRelation;

import java.util.List;

/**
 * 保存工具窗口展示一个源码方法时需要的结构化讲解。
 *
 * @param summary        方法职责摘要
 * @param process        按执行顺序整理的关键步骤
 * @param designInsights 方法级设计亮点
 * @param pitfalls       阅读时容易误解的边界
 * @param relatedMethods 建议关联阅读的方法
 */
public record AtlasMethodReading(
        String summary,
        List<String> process,
        List<String> designInsights,
        List<String> pitfalls,
        List<AtlasMethodRelation> relatedMethods
) {

    /**
     * 固化集合快照，确保界面刷新期间讲解内容不会被外部修改。
     */
    public AtlasMethodReading {
        summary = summary == null ? "" : summary;
        process = process == null ? List.of() : List.copyOf(process);
        designInsights = designInsights == null ? List.of() : List.copyOf(designInsights);
        pitfalls = pitfalls == null ? List.of() : List.copyOf(pitfalls);
        relatedMethods = relatedMethods == null ? List.of() : List.copyOf(relatedMethods);
    }
}
