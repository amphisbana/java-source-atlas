package io.github.javasourceatlas.idea.version;

import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasMethodRelation;
import io.github.javasourceatlas.idea.model.AtlasSource;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.model.AtlasVersionMethodMapping;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 根据项目 JDK 选择固定源码 Tag，并把已知的方法变化应用到入口、断点和证据。
 */
public final class AtlasTopicVersionResolver {

    private static final String JDK_8_SOURCE_REF = "jdk8u412-b08";
    private static final String JDK_17_SOURCE_REF = "jdk-17+35";
    private static final String JDK_21_SOURCE_REF = "jdk-21+35";

    /**
     * 工具类不需要创建实例。
     */
    private AtlasTopicVersionResolver() {
    }

    /**
     * 为专题解析项目 JDK 对应的可执行版本视图。
     *
     * @param topic             基线专题
     * @param projectJdkVersion IDEA 项目 SDK 版本文本
     * @return 版本解析结果
     */
    public static AtlasTopicVersion resolve(AtlasTopic topic, String projectJdkVersion) {
        if (topic == null) {
            return new AtlasTopicVersion(null, null, null, AtlasTopicVersion.Status.UNKNOWN, "尚未选择专题");
        }
        if (!isJdkTopic(topic)) {
            return new AtlasTopicVersion(
                    topic,
                    topic,
                    null,
                    AtlasTopicVersion.Status.BASELINE,
                    "当前框架专题使用固定基线 " + topic.primaryVersion()
            );
        }

        Integer projectMajor = AtlasVersionMatcher.majorVersion(
                AtlasVersionMatcher.VersionKind.JDK,
                projectJdkVersion
        );
        if (projectMajor == null) {
            return new AtlasTopicVersion(
                    topic,
                    topic,
                    null,
                    AtlasTopicVersion.Status.UNKNOWN,
                    "无法识别项目 JDK，已保留 " + topic.primaryVersion() + " 教程；源码断点暂不可用"
            );
        }

        Set<Integer> supportedMajors = supportedMajors(topic);
        if (!supportedMajors.contains(projectMajor)) {
            return new AtlasTopicVersion(
                    topic,
                    topic,
                    projectMajor,
                    AtlasTopicVersion.Status.UNSUPPORTED,
                    "项目 JDK " + projectMajor + " 尚无已验证索引；可阅读基线教程，但不会创建源码断点"
            );
        }
        if (projectMajor == 8) {
            return new AtlasTopicVersion(
                    topic,
                    topic,
                    projectMajor,
                    AtlasTopicVersion.Status.BASELINE,
                    "已选择 OpenJDK 8u 固定源码与调试场景"
            );
        }

        AtlasTopic adapted = adaptTopic(topic, projectMajor);
        return new AtlasTopicVersion(
                topic,
                adapted,
                projectMajor,
                AtlasTopicVersion.Status.ADAPTED,
                "已按项目 JDK 切换到 OpenJDK " + projectMajor + " 固定源码、入口和断点"
        );
    }

    /**
     * 判断专题是否属于 OpenJDK 索引。
     *
     * @param topic 当前专题
     * @return 是否为 JDK 专题
     */
    private static boolean isJdkTopic(AtlasTopic topic) {
        return topic.primaryVersion() != null && topic.primaryVersion().startsWith("OpenJDK");
    }

    /**
     * 从主版本和兼容版本声明中提取已经验证的 JDK 主版本。
     *
     * @param topic 当前专题
     * @return 支持的主版本集合
     */
    private static Set<Integer> supportedMajors(AtlasTopic topic) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(topic.primaryVersion()),
                        topic.compatibleVersions().stream()
                )
                .map(value -> AtlasVersionMatcher.majorVersion(AtlasVersionMatcher.VersionKind.JDK, value))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 复制专题并替换目标版本的源码 Tag、路径和方法变化。
     *
     * @param topic        基线专题
     * @param projectMajor 目标 JDK 主版本
     * @return 目标版本专题视图
     */
    private static AtlasTopic adaptTopic(AtlasTopic topic, int projectMajor) {
        // 2026-09-02：原逻辑逐条映射后直接返回，AQS 多个旧方法合并为 acquire 时会产生重复入口和同一行断点。
        // List<AtlasEntryPoint> entryPoints = topic.entryPoints().stream()
        //         .map(entryPoint -> adaptEntryPoint(topic, entryPoint, projectMajor))
        //         .filter(Objects::nonNull)
        //         .toList();
        // List<AtlasBreakpoint> breakpoints = topic.breakpoints().stream()
        //         .map(breakpoint -> adaptBreakpoint(topic, breakpoint, projectMajor))
        //         .filter(Objects::nonNull)
        //         .toList();
        List<AtlasEntryPoint> entryPoints = mergeEntryPoints(topic.entryPoints().stream()
                .map(entryPoint -> adaptEntryPoint(topic, entryPoint, projectMajor))
                .filter(Objects::nonNull)
                .toList());
        List<AtlasBreakpoint> breakpoints = mergeBreakpoints(topic.breakpoints().stream()
                .map(breakpoint -> adaptBreakpoint(topic, breakpoint, projectMajor))
                .filter(Objects::nonNull)
                .toList());
        List<AtlasEvidence> evidence = topic.evidence().stream()
                .map(item -> adaptEvidence(topic, item, projectMajor))
                .toList();
        return new AtlasTopic(
                topic.topicId(),
                topic.title().replace("OpenJDK 8", "OpenJDK " + projectMajor),
                "OpenJDK " + projectMajor,
                sourceRef(projectMajor),
                topic.designInsight(),
                topic.focusQuestion(),
                topic.readingGoal(),
                topic.recommendedNextTopicId(),
                topic.recommendedNextReason(),
                topic.compatibleVersions(),
                topic.lab(),
                adaptSource(topic.source(), projectMajor),
                topic.relatedSources().stream().map(source -> adaptSource(source, projectMajor)).toList(),
                topic.versionComparison(),
                topic.versionMethodMappings(),
                entryPoints,
                evidence,
                breakpoints
        );
    }

    /**
     * 应用入口方法映射；目标方法为空表示该入口在目标版本中不再存在。
     *
     * @param topic        当前专题
     * @param entryPoint   基线入口
     * @param projectMajor 目标 JDK 主版本
     * @return 目标版本入口；已移除时返回空
     */
    private static AtlasEntryPoint adaptEntryPoint(
            AtlasTopic topic,
            AtlasEntryPoint entryPoint,
            int projectMajor
    ) {
        AtlasVersionMethodMapping mapping = mappingFor(topic, entryPoint.method(), projectMajor);
        if (mapping == null) {
            // 2026-09-03：原逻辑直接复用入口，新增关联阅读后需要同步适配其中的方法签名。
            // return entryPoint;
            return new AtlasEntryPoint(
                    entryPoint.method(),
                    entryPoint.document(),
                    entryPoint.purpose(),
                    entryPoint.sourceClass(),
                    entryPoint.summary(),
                    entryPoint.process(),
                    entryPoint.designInsights(),
                    entryPoint.pitfalls(),
                    adaptRelations(topic, entryPoint.relatedMethods(), projectMajor)
            );
        }
        if (blank(mapping.targetMethod())) {
            return null;
        }
        // 2026-09-03：原四参数构造只适配入口本身，无法保留方法讲解和关联阅读数据。
        // return new AtlasEntryPoint(
        //         mapping.targetMethod(),
        //         fallback(mapping.document(), entryPoint.document()),
        //         fallback(mapping.purpose(), entryPoint.purpose()),
        //         entryPoint.sourceClass()
        // );
        return new AtlasEntryPoint(
                mapping.targetMethod(),
                fallback(mapping.document(), entryPoint.document()),
                fallback(mapping.purpose(), entryPoint.purpose()),
                entryPoint.sourceClass(),
                entryPoint.summary(),
                entryPoint.process(),
                entryPoint.designInsights(),
                entryPoint.pitfalls(),
                adaptRelations(topic, entryPoint.relatedMethods(), projectMajor)
        );
    }

    /**
     * 应用断点签名、场景和观察变量映射；目标方法为空时禁用该断点。
     *
     * @param topic        当前专题
     * @param breakpoint   基线断点
     * @param projectMajor 目标 JDK 主版本
     * @return 目标版本断点；已移除时返回空
     */
    private static AtlasBreakpoint adaptBreakpoint(
            AtlasTopic topic,
            AtlasBreakpoint breakpoint,
            int projectMajor
    ) {
        AtlasVersionMethodMapping mapping = mappingFor(topic, breakpoint.method(), projectMajor);
        if (mapping == null) {
            return breakpoint;
        }
        if (blank(mapping.targetMethod())) {
            return null;
        }
        return new AtlasBreakpoint(
                mapping.targetMethod(),
                fallback(mapping.scenario(), breakpoint.scenario()),
                mapping.variables() == null ? breakpoint.variables() : mapping.variables(),
                breakpoint.sourceClass(),
                breakpoint.evidenceId()
        );
    }

    /**
     * 同步证据绑定的入口签名与讲解地址，保证调试引导展示目标版本内容。
     *
     * @param topic        当前专题
     * @param evidence     基线证据
     * @param projectMajor 目标 JDK 主版本
     * @return 目标版本证据
     */
    private static AtlasEvidence adaptEvidence(AtlasTopic topic, AtlasEvidence evidence, int projectMajor) {
        AtlasVersionMethodMapping mapping = mappingFor(topic, evidence.entryPoint(), projectMajor);
        if (mapping == null || blank(mapping.targetMethod())) {
            return evidence;
        }
        return new AtlasEvidence(
                evidence.id(),
                evidence.kind(),
                evidence.claim(),
                mapping.targetMethod(),
                fallback(mapping.document(), evidence.document()),
                evidence.labMethod(),
                evidence.testClass(),
                evidence.testMethod(),
                evidence.expectedOutcome()
        );
    }

    /**
     * 合并目标版本中落到同一类同一方法的旧版入口，并串联原本分散的阅读目的。
     *
     * @param entryPoints 已应用版本映射的入口
     * @return 去重且保持首次出现顺序的入口
     */
    private static List<AtlasEntryPoint> mergeEntryPoints(List<AtlasEntryPoint> entryPoints) {
        Map<String, AtlasEntryPoint> merged = new LinkedHashMap<>();
        for (AtlasEntryPoint entryPoint : entryPoints) {
            String key = normalized(entryPoint.sourceClass()) + "|" + entryPoint.method();
            AtlasEntryPoint existing = merged.get(key);
            if (existing == null) {
                merged.put(key, entryPoint);
                continue;
            }
            // 2026-09-03：原四参数构造只合并入口用途，新增结构化讲解后需要同步合并各讲解字段。
            // merged.put(key, new AtlasEntryPoint(
            //         existing.method(),
            //         fallback(existing.document(), entryPoint.document()),
            //         mergeText(existing.purpose(), entryPoint.purpose()),
            //         existing.sourceClass()
            // ));
            merged.put(key, new AtlasEntryPoint(
                    existing.method(),
                    fallback(existing.document(), entryPoint.document()),
                    mergeText(existing.purpose(), entryPoint.purpose()),
                    existing.sourceClass(),
                    mergeText(existing.summary(), entryPoint.summary()),
                    mergeValues(existing.process(), entryPoint.process()),
                    mergeValues(existing.designInsights(), entryPoint.designInsights()),
                    mergeValues(existing.pitfalls(), entryPoint.pitfalls()),
                    mergeRelations(existing.relatedMethods(), entryPoint.relatedMethods())
            ));
        }
        return List.copyOf(merged.values());
    }

    /**
     * 把关联方法同步映射到目标 JDK 版本，已移除的方法不再展示为可导航入口。
     *
     * @param topic        当前专题
     * @param relations    基线关联方法
     * @param projectMajor 目标 JDK 主版本
     * @return 目标版本关联方法
     */
    private static List<AtlasMethodRelation> adaptRelations(
            AtlasTopic topic,
            List<AtlasMethodRelation> relations,
            int projectMajor
    ) {
        return relations.stream()
                .map(relation -> adaptRelation(topic, relation, projectMajor))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 应用单个关联方法的版本映射。
     *
     * @param topic        当前专题
     * @param relation     基线关联方法
     * @param projectMajor 目标 JDK 主版本
     * @return 目标版本关系；目标方法已移除时返回 null
     */
    private static AtlasMethodRelation adaptRelation(
            AtlasTopic topic,
            AtlasMethodRelation relation,
            int projectMajor
    ) {
        AtlasVersionMethodMapping mapping = mappingFor(topic, relation.method(), projectMajor);
        if (mapping == null) {
            return relation;
        }
        if (blank(mapping.targetMethod())) {
            return null;
        }
        return new AtlasMethodRelation(
                mapping.targetMethod(),
                relation.relation(),
                relation.reason(),
                relation.sourceClass()
        );
    }

    /**
     * 合并目标版本中位于同一方法的断点场景，观察变量取稳定并集以覆盖合并后的所有分支。
     *
     * @param breakpoints 已应用版本映射的断点
     * @return 去重后的断点
     */
    private static List<AtlasBreakpoint> mergeBreakpoints(List<AtlasBreakpoint> breakpoints) {
        Map<String, AtlasBreakpoint> merged = new LinkedHashMap<>();
        for (AtlasBreakpoint breakpoint : breakpoints) {
            String key = normalized(breakpoint.sourceClass()) + "|" + breakpoint.method();
            AtlasBreakpoint existing = merged.get(key);
            if (existing == null) {
                merged.put(key, breakpoint);
                continue;
            }
            merged.put(key, new AtlasBreakpoint(
                    existing.method(),
                    mergeText(existing.scenario(), breakpoint.scenario()),
                    mergeValues(existing.variables(), breakpoint.variables()),
                    existing.sourceClass(),
                    fallback(existing.evidenceId(), breakpoint.evidenceId())
            ));
        }
        return List.copyOf(merged.values());
    }

    /**
     * 合并两个说明文本，相同内容只保留一次。
     *
     * @param first  首个说明
     * @param second 后续说明
     * @return 合并后的说明
     */
    private static String mergeText(String first, String second) {
        if (blank(first)) {
            return normalized(second);
        }
        if (blank(second) || first.equals(second)) {
            return first;
        }
        return first + "；" + second;
    }

    /**
     * 按首次出现顺序合并观察变量，避免相同表达式重复展示。
     *
     * @param first  首个变量集合
     * @param second 后续变量集合
     * @return 稳定去重后的变量集合
     */
    private static List<String> mergeValues(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>();
        java.util.stream.Stream.concat(first.stream(), second.stream())
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !values.contains(value))
                .forEach(values::add);
        return List.copyOf(values);
    }

    /**
     * 合并方法关联关系，避免版本入口合并后出现重复导航项。
     *
     * @param first  首个入口的关联方法
     * @param second 后续入口的关联方法
     * @return 稳定去重后的关联关系
     */
    private static List<AtlasMethodRelation> mergeRelations(
            List<AtlasMethodRelation> first,
            List<AtlasMethodRelation> second
    ) {
        Map<String, AtlasMethodRelation> relations = new LinkedHashMap<>();
        java.util.stream.Stream.concat(first.stream(), second.stream())
                .filter(Objects::nonNull)
                .filter(relation -> !relation.method().isBlank())
                .forEach(relation -> relations.putIfAbsent(
                        relation.relation() + "|" + relation.method(),
                        relation
                ));
        return List.copyOf(relations.values());
    }

    /**
     * 查找目标版本和基线方法完全匹配的映射。
     *
     * @param topic        当前专题
     * @param method       基线方法
     * @param projectMajor 目标 JDK 主版本
     * @return 映射；不存在时返回空
     */
    private static AtlasVersionMethodMapping mappingFor(AtlasTopic topic, String method, int projectMajor) {
        String version = String.valueOf(projectMajor);
        return topic.versionMethodMappings().stream()
                .filter(mapping -> version.equals(mapping.version()))
                .filter(mapping -> method.equals(mapping.sourceMethod()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 把 JDK 8 仓库布局转换为模块化 JDK 的源码目录布局。
     *
     * @param source       基线源码坐标
     * @param projectMajor 目标 JDK 主版本
     * @return 目标版本源码坐标
     */
    private static AtlasSource adaptSource(AtlasSource source, int projectMajor) {
        if (source == null || projectMajor == 8) {
            return source;
        }
        String path = source.sourcePath()
                .replace("jdk/src/share/classes/", "src/java.base/share/classes/")
                .replace("jdk/src/solaris/classes/", "src/java.base/solaris/classes/")
                .replace("hotspot/src/share/vm/", "src/hotspot/share/");
        if ("sun.nio.ch.EPollSelectorImpl".equals(source.className())) {
            path = "src/java.base/linux/classes/sun/nio/ch/EPollSelectorImpl.java";
        }
        return new AtlasSource(source.className(), path);
    }

    /**
     * 返回目标 JDK 的固定 GA 或维护版源码 Tag。
     *
     * @param projectMajor JDK 主版本
     * @return 固定源码 Tag
     */
    private static String sourceRef(int projectMajor) {
        return switch (projectMajor) {
            case 8 -> JDK_8_SOURCE_REF;
            case 17 -> JDK_17_SOURCE_REF;
            case 21 -> JDK_21_SOURCE_REF;
            default -> throw new IllegalArgumentException("尚未配置 OpenJDK " + projectMajor + " 源码基线");
        };
    }

    /**
     * 可选覆盖值为空时沿用基线文本。
     *
     * @param override 覆盖值
     * @param baseline 基线值
     * @return 最终文本
     */
    private static String fallback(String override, String baseline) {
        return blank(override) ? baseline : override;
    }

    /**
     * 判断文本是否为空。
     *
     * @param value 待判断文本
     * @return 是否为空
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 把可空映射字段规范化为空字符串，供合并键和展示文本安全使用。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private static String normalized(String value) {
        return value == null ? "" : value;
    }
}
