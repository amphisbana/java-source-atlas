package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.navigation.AtlasSourceNavigator;

/**
 * 从编辑器上下文直接定位当前 Source Atlas 源码入口。
 */
public final class AtlasNavigateSourceAction extends AtlasEditorContextAction {

    /**
     * 仅在专题存在可恢复源码入口时启用。
     *
     * @param context 已解析动作上下文
     * @return 是否可执行
     */
    @Override
    protected boolean isAvailable(ActionContext context) {
        return context.topic() != null && context.entryPoint() != null;
    }

    /**
     * 异步定位源码，并在成功后记录精确阅读位置。
     *
     * @param project 当前项目
     * @param context 已解析动作上下文
     */
    @Override
    protected void perform(Project project, ActionContext context) {
        AtlasSourceNavigator.navigateAsync(
                project,
                project,
                context.topic(),
                context.entryPoint(),
                navigated -> {
                    if (!navigated) {
                        Messages.showInfoMessage(
                                project,
                                "当前项目或依赖中没有找到该源码，请先附加对应源码。",
                                "Java Source Atlas"
                        );
                        return;
                    }
                    AtlasLearningProgressState.getInstance().recordEntry(
                            context.topic().topicId(),
                            context.entryPoint().method(),
                            context.entryPoint().document(),
                            context.topic().primaryVersion()
                    );
                }
        );
    }
}
