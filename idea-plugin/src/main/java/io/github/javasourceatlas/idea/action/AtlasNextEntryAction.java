package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.project.Project;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.navigation.AtlasSourceNavigator;

/**
 * 从编辑器直接进入当前专题调用链中的下一个源码入口。
 */
public final class AtlasNextEntryAction extends AtlasEditorContextAction {

    /**
     * 只有当前入口后仍有下一项时启用。
     *
     * @param context 已解析动作上下文
     * @return 是否可执行
     */
    @Override
    protected boolean isAvailable(ActionContext context) {
        return context.sourceActionsAllowed() && nextEntry(context) != null;
    }

    /**
     * 定位调用链下一入口，并在成功后更新精确阅读位置。
     *
     * @param project 当前项目
     * @param context 已解析动作上下文
     */
    @Override
    protected void perform(Project project, ActionContext context) {
        AtlasEntryPoint next = nextEntry(context);
        if (next == null) {
            return;
        }
        AtlasSourceNavigator.navigateAsync(project, project, context.topic(), next, navigated -> {
            if (navigated) {
                AtlasLearningProgressState.getInstance().recordEntry(
                        context.topic().topicId(),
                        next.method(),
                        next.document(),
                        context.topic().primaryVersion()
                );
            }
        });
    }

    /**
     * 计算当前入口之后的下一条源码入口。
     *
     * @param context 已解析动作上下文
     * @return 下一入口；当前已是最后一项时返回 null
     */
    private AtlasEntryPoint nextEntry(ActionContext context) {
        if (context.topic() == null || context.entryPoint() == null) {
            return null;
        }
        int currentIndex = context.topic().entryPoints().indexOf(context.entryPoint());
        int nextIndex = currentIndex + 1;
        return currentIndex >= 0 && nextIndex < context.topic().entryPoints().size()
                ? context.topic().entryPoints().get(nextIndex)
                : null;
    }
}
