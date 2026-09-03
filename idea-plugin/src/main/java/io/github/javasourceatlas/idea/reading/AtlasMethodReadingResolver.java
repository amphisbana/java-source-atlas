package io.github.javasourceatlas.idea.reading;

import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasMethodRelation;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把专题索引中的方法说明整理为可直接展示的阅读视图。
 */
public final class AtlasMethodReadingResolver {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasMethodReadingResolver() {
    }

    /**
     * 解析当前入口的详细讲解；新字段缺失时使用已有专题数据生成兼容内容。
     *
     * @param topic      当前专题
     * @param entryPoint 当前源码入口
     * @return 完整且可展示的方法阅读说明
     */
    public static AtlasMethodReading resolve(AtlasTopic topic, AtlasEntryPoint entryPoint) {
        if (topic == null || entryPoint == null) {
            return new AtlasMethodReading("", List.of(), List.of(), List.of(), List.of());
        }

        String summary = entryPoint.summary().isBlank() ? entryPoint.purpose() : entryPoint.summary();
        List<String> process = entryPoint.process().isEmpty()
                ? defaultProcess(entryPoint)
                : entryPoint.process();
        List<String> insights = entryPoint.designInsights().isEmpty()
                ? fallbackInsight(topic)
                : entryPoint.designInsights();
        List<String> pitfalls = entryPoint.pitfalls().isEmpty()
                ? List.of("不要孤立阅读当前方法；同时确认调用者、共享状态变化以及异常或并发边界。")
                : entryPoint.pitfalls();
        List<AtlasMethodRelation> relatedMethods = entryPoint.relatedMethods().isEmpty()
                ? adjacentRelations(topic, entryPoint)
                : deduplicate(entryPoint.relatedMethods());
        return new AtlasMethodReading(summary, process, insights, pitfalls, relatedMethods);
    }

    /**
     * 在当前专题中解析一条关联关系指向的实际源码入口。
     *
     * @param topic    当前专题
     * @param relation 关联方法描述
     * @return 可用于源码导航的入口
     */
    public static Optional<AtlasEntryPoint> resolveRelatedEntry(
            AtlasTopic topic,
            AtlasMethodRelation relation
    ) {
        if (topic == null || relation == null || relation.method().isBlank()) {
            return Optional.empty();
        }
        Optional<AtlasEntryPoint> exact = topic.entryPoints().stream()
                .filter(entryPoint -> relation.method().equals(entryPoint.method()))
                .filter(entryPoint -> relation.sourceClass() == null
                        || relation.sourceClass().isBlank()
                        || relation.sourceClass().equals(entryPoint.effectiveSourceClass(topic)))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }

        String methodName = AtlasMethodMatcher.extractSimpleMethodName(relation.method());
        return topic.entryPoints().stream()
                .filter(entryPoint -> methodName.equals(entryPoint.simpleMethodName()))
                .findFirst();
    }

    /**
     * 为旧索引生成一条可操作的三步阅读提示。
     *
     * @param entryPoint 当前源码入口
     * @return 默认执行过程
     */
    private static List<String> defaultProcess(AtlasEntryPoint entryPoint) {
        return List.of(
                "确认调用者传入的关键参数以及当前对象状态。",
                "沿主要分支追踪状态变化，重点理解：" + entryPoint.purpose() + "。",
                "结合推荐断点和 Lab 验证返回值、共享状态或数据结构变化。"
        );
    }

    /**
     * 使用专题级设计说明补足尚未细化到方法级的入口。
     *
     * @param topic 当前专题
     * @return 至少包含一条设计观察
     */
    private static List<String> fallbackInsight(AtlasTopic topic) {
        if (topic.designInsight() == null || topic.designInsight().isBlank()) {
            return List.of("该入口通过清晰的职责边界连接上下游方法，适合结合完整调用链理解。");
        }
        return List.of(topic.designInsight());
    }

    /**
     * 为旧入口用前后阅读顺序生成关联方法，避免升级后出现空面板。
     *
     * @param topic      当前专题
     * @param entryPoint 当前入口
     * @return 前置和后续入口关系
     */
    private static List<AtlasMethodRelation> adjacentRelations(AtlasTopic topic, AtlasEntryPoint entryPoint) {
        int currentIndex = topic.entryPoints().indexOf(entryPoint);
        if (currentIndex < 0) {
            return List.of();
        }

        List<AtlasMethodRelation> relations = new ArrayList<>();
        if (currentIndex > 0) {
            AtlasEntryPoint previous = topic.entryPoints().get(currentIndex - 1);
            relations.add(new AtlasMethodRelation(
                    previous.method(),
                    "前置入口",
                    previous.purpose(),
                    previous.sourceClass()
            ));
        }
        if (currentIndex + 1 < topic.entryPoints().size()) {
            AtlasEntryPoint next = topic.entryPoints().get(currentIndex + 1);
            relations.add(new AtlasMethodRelation(
                    next.method(),
                    "后续入口",
                    next.purpose(),
                    next.sourceClass()
            ));
        }
        return List.copyOf(relations);
    }

    /**
     * 按方法和关系类型去重，同时保持索引中的阅读顺序。
     *
     * @param relations 原始关联关系
     * @return 稳定去重后的关系
     */
    private static List<AtlasMethodRelation> deduplicate(List<AtlasMethodRelation> relations) {
        Map<String, AtlasMethodRelation> unique = new LinkedHashMap<>();
        for (AtlasMethodRelation relation : relations) {
            if (relation == null || relation.method().isBlank()) {
                continue;
            }
            unique.putIfAbsent(relation.relation() + "|" + relation.method(), relation);
        }
        return List.copyOf(unique.values());
    }
}
