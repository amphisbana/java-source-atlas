package io.github.javasourceatlas.idea.version;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对 JDK、Spring Framework 与 Spring Boot 版本执行结构化比较。
 */
public final class AtlasVersionMatcher {

    private static final Pattern JDK_NAMED_VERSION = Pattern.compile(
            "(?i)(?:openjdk|jdk|java\\s+version|version)\\s*(?:version\\s*)?[\\\"']?(1\\.)?(\\d{1,2})"
    );
    private static final Pattern GENERIC_VERSION = Pattern.compile(
            "(?<!\\d)(1\\.)?(\\d+)(?:[._](\\d+|x))?(?:[._](\\d+|x))?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 工具类不需要创建实例。
     */
    private AtlasVersionMatcher() {
    }

    /**
     * 比较教程基线、兼容范围和项目实际版本。
     *
     * @param kind               版本体系
     * @param baseline           教程主版本
     * @param compatibleVersions 索引声明的兼容版本
     * @param projectVersion     项目检测版本
     * @return 结构化比较结果
     */
    public static VersionMatch match(
            VersionKind kind,
            String baseline,
            List<String> compatibleVersions,
            String projectVersion
    ) {
        ParsedVersion baselineVersion = parse(kind, baseline);
        ParsedVersion actualVersion = parse(kind, projectVersion);
        if (baselineVersion == null || actualVersion == null) {
            return new VersionMatch(VersionRelation.UNKNOWN, null);
        }

        VersionRelation relation = compare(baselineVersion, actualVersion);
        if (relation == VersionRelation.EXACT) {
            return new VersionMatch(relation, null);
        }

        List<String> compatible = compatibleVersions == null ? Collections.emptyList() : compatibleVersions;
        String matched = compatible.stream()
                .filter(candidate -> matchesCompatible(kind, candidate, actualVersion))
                .findFirst()
                .orElse(null);
        return new VersionMatch(relation, matched);
    }

    /**
     * 将比较结果格式化为工具窗口中的中文提示。
     *
     * @param baseline       教程基线文本
     * @param projectVersion 项目版本文本
     * @param match          比较结果
     * @return 可直接展示的提示
     */
    public static String formatHint(String baseline, String projectVersion, VersionMatch match) {
        String relationText = switch (match.relation()) {
            case EXACT -> "完全匹配";
            case SAME_MINOR -> "同一 minor，patch 不同";
            case SAME_MAJOR -> "同一 major，minor 不同";
            case MAJOR_MISMATCH -> "major 不同，需重点核对实现差异";
            case UNKNOWN -> "版本格式无法识别，请人工核对";
        };
        String compatibleText = match.compatibleVersion() == null
                ? ""
                : "；命中兼容范围：" + match.compatibleVersion();
        return "教程基线：" + baseline + "；项目：" + projectVersion
                + "（" + relationText + "）" + compatibleText;
    }

    /**
     * 按版本体系解析版本数字，JDK 兼容 1.8、8u、17 与带引号的 SDK 文本。
     *
     * @param kind 版本体系
     * @param text 原始版本文本
     * @return 解析结果；无法识别时返回 null
     */
    private static ParsedVersion parse(VersionKind kind, String text) {
        if (text == null || text.isBlank() || "未检测到".equals(text) || "未配置".equals(text)) {
            return null;
        }
        if (kind == VersionKind.JDK) {
            Matcher named = JDK_NAMED_VERSION.matcher(text);
            if (named.find()) {
                int major = Integer.parseInt(named.group(2));
                return new ParsedVersion(major, null, null);
            }
        }

        Matcher matcher = GENERIC_VERSION.matcher(text.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        boolean legacyJdk = kind == VersionKind.JDK && matcher.group(1) != null;
        int major = Integer.parseInt(matcher.group(2));
        if (legacyJdk) {
            return new ParsedVersion(major, null, null);
        }
        Integer minor = numericComponent(matcher.group(3));
        Integer patch = numericComponent(matcher.group(4));
        return kind == VersionKind.JDK
                ? new ParsedVersion(major, null, null)
                : new ParsedVersion(major, minor, patch);
    }

    /**
     * 比较主版本、次版本和补丁版本，缺省的低位视为当前教程系列。
     *
     * @param baseline 教程版本
     * @param actual   项目版本
     * @return 差异级别
     */
    private static VersionRelation compare(ParsedVersion baseline, ParsedVersion actual) {
        if (baseline.major() != actual.major()) {
            return VersionRelation.MAJOR_MISMATCH;
        }
        if (baseline.minor() != null && actual.minor() != null
                && !baseline.minor().equals(actual.minor())) {
            return VersionRelation.SAME_MAJOR;
        }
        if (baseline.patch() != null && actual.patch() != null
                && !baseline.patch().equals(actual.patch())) {
            return VersionRelation.SAME_MINOR;
        }
        return VersionRelation.EXACT;
    }

    /**
     * 判断项目版本是否落入某个兼容版本声明，支持 x 通配系列。
     *
     * @param kind      版本体系
     * @param candidate 兼容版本声明
     * @param actual    项目实际版本
     * @return 是否命中
     */
    private static boolean matchesCompatible(VersionKind kind, String candidate, ParsedVersion actual) {
        ParsedVersion expected = parse(kind, candidate);
        if (expected == null || expected.major() != actual.major()) {
            return false;
        }
        if (expected.minor() != null && !expected.minor().equals(actual.minor())) {
            return false;
        }
        if (expected.patch() != null && !expected.patch().equals(actual.patch())) {
            return false;
        }
        return true;
    }

    /**
     * 把数字分量转换为整数，x 或缺省值表示通配。
     *
     * @param component 正则捕获分量
     * @return 数字；通配时返回 null
     */
    private static Integer numericComponent(String component) {
        return component == null || component.equalsIgnoreCase("x")
                ? null
                : Integer.valueOf(component);
    }

    /**
     * 版本体系决定 JDK 主版本规则或三段语义版本规则。
     */
    public enum VersionKind {
        JDK,
        SPRING_FRAMEWORK,
        SPRING_BOOT
    }

    /**
     * 主版本比较的差异级别。
     */
    public enum VersionRelation {
        EXACT,
        SAME_MINOR,
        SAME_MAJOR,
        MAJOR_MISMATCH,
        UNKNOWN
    }

    /**
     * 保存主版本差异与命中的可选兼容范围。
     *
     * @param relation          主版本差异
     * @param compatibleVersion 命中的兼容声明
     */
    public record VersionMatch(VersionRelation relation, String compatibleVersion) {
    }

    /**
     * 保存解析后的版本数字。
     *
     * @param major 主版本
     * @param minor 次版本
     * @param patch 补丁版本
     */
    private record ParsedVersion(int major, Integer minor, Integer patch) {
    }
}
