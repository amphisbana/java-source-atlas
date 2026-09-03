package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.translation.AtlasTranslationSupport;
import org.jetbrains.annotations.NotNull;

/**
 * 从编辑器右键菜单翻译当前源码注释。
 */
public final class AtlasTranslateCommentAction extends DumbAwareAction {

    /**
     * 初始化动作并复用 Source Atlas 图标。
     */
    public AtlasTranslateCommentAction() {
        getTemplatePresentation().setIcon(AtlasIcons.ATLAS);
    }

    /**
     * 调用 Translation 适配器翻译当前注释，缺少插件时展示安装引导。
     *
     * @param event IDEA 动作事件
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) {
            AtlasTranslationSupport.translateCurrentComment(project, true);
        }
    }
}
