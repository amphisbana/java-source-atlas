package io.github.javasourceatlas.idea.debug;

import java.util.List;

/**
 * 描述一次 Atlas 断点暂停后需要展示的结论、观察项和下一步。
 *
 * @param topicId             专题编号
 * @param topicTitle          专题标题
 * @param evidenceId          当前断点绑定的证据编号；未绑定时为空
 * @param breakpointMethod    当前推荐断点方法
 * @param scenario            当前观察场景
 * @param variables           建议加入 Watches 的表达式
 * @param claim               当前断点需要验证的源码结论
 * @param expectedOutcome     继续执行前应该观察到的结果
 * @param nextBreakpointMethod 下一推荐断点方法；没有下一项时为空
 */
public record AtlasDebugGuidance(
        String topicId,
        String topicTitle,
        String evidenceId,
        String breakpointMethod,
        String scenario,
        List<String> variables,
        String claim,
        String expectedOutcome,
        String nextBreakpointMethod
) {

    /**
     * 复制观察表达式，避免索引集合被界面或调试会话意外修改。
     */
    public AtlasDebugGuidance {
        evidenceId = evidenceId == null ? "" : evidenceId;
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
