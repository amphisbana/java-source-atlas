package io.github.javasourceatlas.idea.debug;

import java.util.List;

/**
 * 保存一次 IDEA Debug 会话中经过的 Atlas 断点路径，并生成可复制的学习摘要。
 *
 * @param active    会话是否仍在运行
 * @param startedAt 会话开始时间
 * @param endedAt   会话结束时间；仍在运行时为空
 * @param visits    按实际命中顺序保存的断点记录
 */
public record AtlasDebugSessionReport(
        boolean active,
        String startedAt,
        String endedAt,
        List<Visit> visits
) {

    /**
     * 规范化可空时间和访问列表，避免界面读取调试状态时额外判空。
     */
    public AtlasDebugSessionReport {
        startedAt = normalized(startedAt);
        endedAt = normalized(endedAt);
        visits = visits == null ? List.of() : List.copyOf(visits);
    }

    /**
     * 创建尚未产生 Atlas 命中记录的空报告。
     *
     * @return 空报告
     */
    public static AtlasDebugSessionReport empty() {
        return new AtlasDebugSessionReport(false, "", "", List.of());
    }

    /**
     * 统计本次会话实际验证过的非空证据编号数量。
     *
     * @return 去重后的证据数量
     */
    public long verifiedEvidenceCount() {
        return visits.stream()
                .map(Visit::evidenceId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
    }

    /**
     * 将会话路径整理为适合粘贴到学习笔记或 Issue 的 Markdown 文本。
     *
     * @return 调试会话摘要
     */
    public String toMarkdown() {
        StringBuilder summary = new StringBuilder("# Java Source Atlas 调试摘要\n\n");
        summary.append("- 状态：").append(active ? "进行中" : "已结束").append('\n');
        if (!startedAt.isBlank()) {
            summary.append("- 开始时间：").append(startedAt).append('\n');
        }
        if (!endedAt.isBlank()) {
            summary.append("- 结束时间：").append(endedAt).append('\n');
        }
        summary.append("- 命中断点：").append(visits.size()).append('\n');
        summary.append("- 已验证证据：").append(verifiedEvidenceCount()).append("\n\n");
        summary.append("## 实际调用路径\n");
        if (visits.isEmpty()) {
            return summary.append("\n本次会话尚未命中 Atlas 推荐断点。\n").toString();
        }
        for (int index = 0; index < visits.size(); index++) {
            Visit visit = visits.get(index);
            summary.append('\n').append(index + 1).append(". **")
                    .append(visit.topicTitle()).append(" · ")
                    .append(visit.breakpointMethod()).append("**\n");
            if (!visit.evidenceId().isBlank()) {
                summary.append("   - 证据：").append(visit.evidenceId()).append('\n');
            }
            summary.append("   - 验证：").append(visit.claim()).append('\n');
            summary.append("   - 预期：").append(visit.expectedOutcome()).append('\n');
            if (!visit.sourceLocation().isBlank()) {
                summary.append("   - 位置：").append(visit.sourceLocation()).append('\n');
            }
        }
        return summary.toString();
    }

    /**
     * 把可空文本规范化为空字符串。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private static String normalized(String value) {
        return value == null ? "" : value;
    }

    /**
     * 描述一次真实暂停位置以及该位置验证的源码结论。
     *
     * @param topicId          专题编号
     * @param topicTitle       专题标题
     * @param breakpointMethod 命中的推荐断点方法
     * @param evidenceId       绑定证据编号
     * @param claim            需要验证的源码结论
     * @param expectedOutcome  预期运行结果
     * @param sourceLocation   源文件与一基行号
     * @param visitedAt        命中时间
     */
    public record Visit(
            String topicId,
            String topicTitle,
            String breakpointMethod,
            String evidenceId,
            String claim,
            String expectedOutcome,
            String sourceLocation,
            String visitedAt
    ) {

        /**
         * 规范化调试器回调中可能缺失的显示文本。
         */
        public Visit {
            topicId = normalized(topicId);
            topicTitle = normalized(topicTitle);
            breakpointMethod = normalized(breakpointMethod);
            evidenceId = normalized(evidenceId);
            claim = normalized(claim);
            expectedOutcome = normalized(expectedOutcome);
            sourceLocation = normalized(sourceLocation);
            visitedAt = normalized(visitedAt);
        }
    }
}
