package io.github.javasourceatlas.idea.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Debug 会话报告保留真实命中顺序并生成可读摘要。
 */
class AtlasDebugSessionReportTest {

    /**
     * 验证重复证据只计数一次，但实际经过的两个断点都会保留在调用路径中。
     */
    @Test
    void shouldCreateOrderedMarkdownSummary() {
        AtlasDebugSessionReport report = new AtlasDebugSessionReport(
                false,
                "2026-09-02T10:00:00Z",
                "2026-09-02T10:01:00Z",
                List.of(
                        visit("putVal(int,K,V,boolean,boolean)", "put-main", "HashMap.java:630"),
                        visit("resize()", "put-main", "HashMap.java:680")
                )
        );

        String markdown = report.toMarkdown();

        assertEquals(1, report.verifiedEvidenceCount());
        assertTrue(markdown.contains("命中断点：2"));
        assertTrue(markdown.indexOf("putVal(int,K,V,boolean,boolean)") < markdown.indexOf("resize()"));
        assertTrue(markdown.contains("HashMap.java:680"));
    }

    /**
     * 创建一条 HashMap 调试访问记录，减少摘要用例中的重复字段。
     *
     * @param method   断点方法
     * @param evidence 证据编号
     * @param location 源码位置
     * @return 调试访问记录
     */
    private AtlasDebugSessionReport.Visit visit(String method, String evidence, String location) {
        return new AtlasDebugSessionReport.Visit(
                "openjdk8-java-util-hashmap",
                "HashMap",
                method,
                evidence,
                "验证 HashMap 状态变化",
                "观察容量与桶结构",
                location,
                "2026-09-02T10:00:30Z"
        );
    }
}
