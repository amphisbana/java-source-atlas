package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;

/**
 * 从编辑器上下文收藏或取消收藏当前源码专题。
 */
public final class AtlasToggleFavoriteAction extends AtlasEditorContextAction {

    /**
     * 当前类命中任意专题时即可执行收藏切换。
     *
     * @param context 已解析动作上下文
     * @return 是否可执行
     */
    @Override
    protected boolean isAvailable(ActionContext context) {
        return context.topic() != null;
    }

    /**
     * 切换当前专题收藏状态并给出明确结果。
     *
     * @param project 当前项目
     * @param context 已解析动作上下文
     */
    @Override
    protected void perform(Project project, ActionContext context) {
        AtlasLearningProgressState progress = AtlasLearningProgressState.getInstance();
        boolean favorite = !progress.isFavorite(context.topic().topicId());
        progress.setFavorite(context.topic().topicId(), favorite);
        Messages.showInfoMessage(
                project,
                "已" + (favorite ? "收藏" : "取消收藏") + "专题：" + context.topic().title(),
                "Java Source Atlas"
        );
    }
}
