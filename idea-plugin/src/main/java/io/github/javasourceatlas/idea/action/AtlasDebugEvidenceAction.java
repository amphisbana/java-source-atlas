package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.javasourceatlas.idea.debug.AtlasBreakpointManager;
import io.github.javasourceatlas.idea.lab.AtlasLabLauncher;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;

import java.util.List;

/**
 * 从编辑器上下文添加推荐断点并调试对应的精确 JUnit 证据。
 */
public final class AtlasDebugEvidenceAction extends AtlasEditorContextAction {

    /**
     * 只有当前入口同时存在推荐断点和可执行证据时启用。
     *
     * @param context 已解析动作上下文
     * @return 是否可执行
     */
    @Override
    protected boolean isAvailable(ActionContext context) {
        return context.topic() != null && context.breakpoint() != null && context.evidence() != null;
    }

    /**
     * 先确认断点能够解析，再启动对应单个 JUnit 测试方法。
     *
     * @param project 当前项目
     * @param context 已解析动作上下文
     */
    @Override
    protected void perform(Project project, ActionContext context) {
        AtlasBreakpointManager.addBreakpointsAsync(
                project,
                project,
                context.topic(),
                List.of(context.breakpoint()),
                result -> {
                    if (result.added() + result.existing() == 0) {
                        Messages.showInfoMessage(
                                project,
                                "没有找到推荐断点方法，请先附加对应源码。",
                                "Java Source Atlas"
                        );
                        return;
                    }
                    AtlasLearningProgressState.getInstance().recordBreakpoint(
                            context.topic().topicId(),
                            context.breakpoint().method(),
                            context.topic().primaryVersion()
                    );
                    AtlasLabLauncher.debugEvidenceAsync(
                            project,
                            project,
                            context.evidence(),
                            started -> {
                                if (!started) {
                                    Messages.showInfoMessage(
                                            project,
                                            "未找到证据测试，请导入对应 Lab Maven 模块。",
                                            "Java Source Atlas"
                                    );
                                }
                            }
                    );
                }
        );
    }
}
