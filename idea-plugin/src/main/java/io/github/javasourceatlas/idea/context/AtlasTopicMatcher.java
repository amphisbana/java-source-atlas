package io.github.javasourceatlas.idea.context;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.psi.PsiMethod;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一处理共享源码类对应的专题候选、方法入口和版本优先级。
 */
public final class AtlasTopicMatcher {

    private static final Pattern MAJOR_VERSION = Pattern.compile("(\\d+)");

    /**
     * 工具类不需要创建实例。
     */
    private AtlasTopicMatcher() {
    }

    /**
     * 根据当前项目 SDK 和 PSI 方法解析最相关的专题候选。
     *
     * @param index     专题索引
     * @param project   当前 IDEA 项目
     * @param className 当前完整源码类名
     * @param method    当前 PSI 方法；光标只落在类上时可以为空
     * @return 排序后的候选和唯一命中结果
     */
    public static Resolution resolve(
            AtlasIndexService index,
            Project project,
            String className,
            PsiMethod method
    ) {
        return resolve(index, className, method, projectMajorVersion(project));
    }

    /**
     * 使用指定项目版本解析专题，供测试覆盖版本排序而不依赖完整 IDEA 项目。
     *
     * @param index          专题索引
     * @param className      当前完整源码类名
     * @param method         当前 PSI 方法；光标只落在类上时可以为空
     * @param projectVersion 项目 JDK 主版本
     * @return 排序后的候选和唯一命中结果
     */
    static Resolution resolve(
            AtlasIndexService index,
            String className,
            PsiMethod method,
            String projectVersion
    ) {
        List<AtlasTopic> sourceCandidates = index.findBySourceClassCandidates(className);
        if (sourceCandidates.isEmpty()) {
            return new Resolution(List.of(), null, null);
        }

        List<ScoredCandidate> scored = sourceCandidates.stream()
                .map(topic -> score(topic, className, method, projectVersion))
                .toList();
        boolean hasMethodMatch = method != null && scored.stream()
                .anyMatch(candidate -> candidate.methodScore() > 0);
        if (hasMethodMatch) {
            scored = scored.stream()
                    .filter(candidate -> candidate.methodScore() > 0)
                    .toList();
        }

        List<ScoredCandidate> ranked = scored.stream()
                .sorted(Comparator
                        .comparingInt(ScoredCandidate::totalScore)
                        .reversed()
                        .thenComparing(candidate -> candidate.topic().topicId()))
                .toList();
        List<AtlasTopic> candidates = ranked.stream().map(ScoredCandidate::topic).toList();
        if (ranked.isEmpty()) {
            return new Resolution(candidates, null, null);
        }

        ScoredCandidate best = ranked.getFirst();
        boolean unique = ranked.size() == 1
                || best.totalScore() > ranked.get(1).totalScore();
        return unique
                ? new Resolution(candidates, best.topic(), best.entryPoint())
                : new Resolution(candidates, null, null);
    }

    /**
     * 按方法精确度、项目版本和主源码类顺序计算候选专题得分。
     *
     * @param topic         当前候选专题
     * @param className     当前完整源码类名
     * @param method        当前 PSI 方法
     * @param projectVersion 项目 JDK 主版本
     * @return 带入口和排序得分的候选
     */
    private static ScoredCandidate score(
            AtlasTopic topic,
            String className,
            PsiMethod method,
            String projectVersion
    ) {
        AtlasEntryPoint exactEntryPoint = method == null
                ? null
                : topic.entryPoints().stream()
                .filter(entryPoint -> className.equals(entryPoint.effectiveSourceClass(topic)))
                .filter(entryPoint -> AtlasMethodMatcher.matches(method, entryPoint.method()))
                .findFirst()
                .orElse(null);
        AtlasEntryPoint fallbackEntryPoint = method == null || exactEntryPoint != null
                ? exactEntryPoint
                : AtlasMethodMatcher.findBestEntryPoint(topic, className, method).orElse(null);
        int methodScore = exactEntryPoint != null ? 4 : fallbackEntryPoint != null ? 2 : 0;
        int versionScore = versionScore(topic, projectVersion);
        int sourceScore = topic.source() != null && className.equals(topic.source().className()) ? 1 : 0;
        return new ScoredCandidate(
                topic,
                fallbackEntryPoint,
                methodScore,
                versionScore,
                sourceScore,
                methodScore * 100 + versionScore * 10 + sourceScore
        );
    }

    /**
     * 优先选择教程主版本与当前 JDK 一致的专题，其次选择声明兼容版本的专题。
     *
     * @param topic         候选专题
     * @param projectVersion 项目 JDK 主版本
     * @return 版本匹配得分
     */
    private static int versionScore(AtlasTopic topic, String projectVersion) {
        if (projectVersion == null || projectVersion.isBlank()) {
            return 0;
        }
        String normalizedVersion = projectVersion.trim().toLowerCase(Locale.ROOT);
        String primary = topic.primaryVersion() == null
                ? ""
                : topic.primaryVersion().toLowerCase(Locale.ROOT);
        if (containsVersion(primary, normalizedVersion)) {
            return 3;
        }
        boolean compatible = topic.compatibleVersions().stream()
                .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> containsVersion(value, normalizedVersion));
        return compatible ? 2 : 0;
    }

    /**
     * 判断版本描述是否包含完整主版本，避免把 8 误匹配到 18。
     *
     * @param description 版本描述
     * @param major       项目主版本
     * @return 是否包含目标主版本
     */
    private static boolean containsVersion(String description, String major) {
        Matcher matcher = MAJOR_VERSION.matcher(description);
        while (matcher.find()) {
            if (major.equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从当前项目 SDK 文本提取 JDK 主版本。
     *
     * @param project 当前 IDEA 项目
     * @return JDK 主版本；无法识别时返回空字符串
     */
    private static String projectMajorVersion(Project project) {
        if (project == null) {
            return "";
        }
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        String version = sdk == null ? null : sdk.getVersionString();
        if (version == null || version.isBlank()) {
            return "";
        }
        Matcher matcher = MAJOR_VERSION.matcher(version);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 保存专题候选和唯一命中结果；命中不唯一时 topic 与 entryPoint 均为空。
     *
     * @param candidates 排序后的专题候选
     * @param topic      唯一命中的专题
     * @param entryPoint 唯一命中的源码入口
     */
    public record Resolution(List<AtlasTopic> candidates, AtlasTopic topic, AtlasEntryPoint entryPoint) {
        /**
         * 规范化候选列表，避免调用方修改索引快照。
         */
        public Resolution {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * 保存候选专题的中间排序信息。
     */
    private record ScoredCandidate(
            AtlasTopic topic,
            AtlasEntryPoint entryPoint,
            int methodScore,
            int versionScore,
            int sourceScore,
            int totalScore
    ) {
    }
}
