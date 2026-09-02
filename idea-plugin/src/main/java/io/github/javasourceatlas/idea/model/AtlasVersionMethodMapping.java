package io.github.javasourceatlas.idea.model;

import java.util.List;

/**
 * 描述一个 JDK 主版本中的源码方法签名、说明和观察变量变化。
 *
 * @param version      目标 JDK 主版本
 * @param sourceMethod 基线索引中的方法签名
 * @param targetMethod 目标版本方法签名；为空表示该入口在目标版本中已移除
 * @param document     目标版本讲解地址；为空时沿用基线地址
 * @param purpose      目标版本入口说明；为空时沿用基线说明
 * @param scenario     目标版本断点场景；为空时沿用基线场景
 * @param variables    目标版本观察变量；为空时沿用基线变量
 */
public record AtlasVersionMethodMapping(
        String version,
        String sourceMethod,
        String targetMethod,
        String document,
        String purpose,
        String scenario,
        List<String> variables
) {

    /**
     * 复制可选观察变量，避免索引集合被界面层修改。
     */
    public AtlasVersionMethodMapping {
        variables = variables == null ? null : List.copyOf(variables);
    }
}
