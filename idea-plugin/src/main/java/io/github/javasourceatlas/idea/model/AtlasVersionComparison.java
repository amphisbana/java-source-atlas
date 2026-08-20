package io.github.javasourceatlas.idea.model;

import java.util.List;

/**
 * 描述专题是否接入 JDK 版本对比工作台及其迁移提示。
 *
 * @param id               对比工作台使用的稳定编号
 * @param summary          版本差异摘要
 * @param supportedVersions 支持对比的 JDK 主版本
 * @param migrationHint    面向升级和调试的提示
 */
public record AtlasVersionComparison(
        String id,
        String summary,
        List<String> supportedVersions,
        String migrationHint
) {
}
