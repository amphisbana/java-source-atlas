package io.github.javasourceatlas.idea.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 创建 Source Atlas 右侧工具窗口。
 */
public final class AtlasToolWindowFactory implements ToolWindowFactory, DumbAware {

    /**
     * 创建项目级工具窗口面板并把其生命周期交给 Content 管理。
     *
     * @param project    当前项目
     * @param toolWindow 工具窗口
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AtlasToolWindowPanel panel = new AtlasToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
