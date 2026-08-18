package io.github.javasourceatlas.idea.action;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import io.github.javasourceatlas.idea.context.AtlasContextResolver;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.settings.AtlasSettingsState;
import org.jetbrains.annotations.NotNull;

/**
 * 从编辑器右键菜单打开当前类或方法对应的 Source Atlas 教程。
 */
public final class AtlasOpenDocumentationAction extends DumbAwareAction {

    /**
     * 初始化带 Atlas 图标的编辑器操作。
     */
    public AtlasOpenDocumentationAction() {
        getTemplatePresentation().setIcon(AtlasIcons.ATLAS);
    }

    /**
     * 在后台线程计算 PSI 匹配状态，避免阻塞界面线程。
     *
     * @return 后台更新线程
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 只有当前 Java 类命中专题时才启用操作。
     *
     * @param event 动作上下文
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        boolean available = project != null && resolveContext(project).topic() != null;
        event.getPresentation().setEnabledAndVisible(available);
    }

    /**
     * 打开最精确的方法教程；光标未命中入口时退回专题第一个入口。
     *
     * @param event 动作上下文
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        AtlasEditorContext context = resolveContext(project);
        if (context.topic() == null || context.topic().entryPoints().isEmpty()) {
            Messages.showInfoMessage(project, "当前 Java 类尚未收录到 Source Atlas。", "Java Source Atlas");
            return;
        }
        AtlasEntryPoint entryPoint = context.entryPoint() == null
                ? context.topic().entryPoints().get(0)
                : context.entryPoint();
        BrowserUtil.browse(AtlasSettingsState.getInstance().documentationUrl(entryPoint.document()));
    }

    /**
     * 复用应用级索引解析当前编辑器上下文。
     *
     * @param project 当前项目
     * @return 编辑器匹配结果
     */
    private AtlasEditorContext resolveContext(Project project) {
        AtlasIndexService index = ApplicationManager.getApplication().getService(AtlasIndexService.class);
        return AtlasContextResolver.resolve(project, index);
    }
}
