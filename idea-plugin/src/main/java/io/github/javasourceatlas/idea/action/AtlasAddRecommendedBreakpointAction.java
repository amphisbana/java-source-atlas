package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.javasourceatlas.idea.debug.AtlasBreakpointManager;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;

import java.util.List;

/**
 * 从编辑器上下文添加当前方法对应的推荐断点。
 */
public final class AtlasAddRecommendedBreakpointAction extends AtlasEditorContextAction {

    /**
     * 仅在当前入口存在推荐断点时启用。
     *
     * @param context 已解析动作上下文
     * @return 是否可执行
     */
    @Override
    protected boolean isAvailable(ActionContext context) {
        return context.sourceActionsAllowed() && context.topic() != null && context.breakpoint() != null;
    }

    /**
     * 添加或复用推荐断点，并记录方法级断点进度。
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
                    if (result.added() + result.existing() > 0) {
                        AtlasLearningProgressState.getInstance().recordBreakpoint(
                                context.topic().topicId(),
                                context.breakpoint().method(),
                                context.topic().primaryVersion()
                        );
                    }
                    Messages.showInfoMessage(
                            project,
                            "新增 " + result.added() + " 个，已存在 " + result.existing()
                                    + " 个，未解析 " + result.unresolved().size()
                                    + " 个，创建失败 " + result.failed().size() + " 个。",
                            "Source Atlas 推荐断点"
                    );
                }
        );
    }
}
