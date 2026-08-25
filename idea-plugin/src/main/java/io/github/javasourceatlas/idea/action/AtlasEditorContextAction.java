package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import io.github.javasourceatlas.idea.context.AtlasContextResolver;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.jetbrains.annotations.NotNull;

/**
 * 使用模板方法统一解析编辑器中的专题、入口、断点和证据上下文。
 */
abstract class AtlasEditorContextAction extends DumbAwareAction {

    /**
     * 初始化带 Atlas 图标的编辑器上下文动作。
     */
    protected AtlasEditorContextAction() {
        getTemplatePresentation().setIcon(AtlasIcons.ATLAS);
    }

    /**
     * 在后台线程解析 PSI 上下文，避免动作菜单更新阻塞界面。
     *
     * @return 后台更新线程
     */
    @Override
    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 只有子类声明的上下文条件满足时才显示动作。
     *
     * @param event IDEA 动作事件
     */
    @Override
    public final void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        ActionContext context = project == null ? null : resolveContext(project);
        event.getPresentation().setEnabledAndVisible(context != null && isAvailable(context));
    }

    /**
     * 重新解析点击时的最新上下文，并交给具体动作执行。
     *
     * @param event IDEA 动作事件
     */
    @Override
    public final void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        ActionContext context = resolveContext(project);
        if (isAvailable(context)) {
            perform(project, context);
        }
    }

    /**
     * 判断当前解析结果是否满足具体动作的执行条件。
     *
     * @param context 已解析动作上下文
     * @return 是否可执行
     */
    protected abstract boolean isAvailable(ActionContext context);

    /**
     * 执行具体编辑器上下文动作。
     *
     * @param project 当前项目
     * @param context 已解析动作上下文
     */
    protected abstract void perform(Project project, ActionContext context);

    /**
     * 解析当前编辑器上下文，并用上次阅读入口补足光标没有命中方法的场景。
     *
     * @param project 当前项目
     * @return 完整动作上下文
     */
    private ActionContext resolveContext(Project project) {
        AtlasIndexService index = ApplicationManager.getApplication().getService(AtlasIndexService.class);
        AtlasEditorContext editorContext = AtlasContextResolver.resolve(project, index);
        AtlasTopic topic = editorContext.topic();
        AtlasEntryPoint entryPoint = resolveEntryPoint(topic, editorContext.entryPoint());
        AtlasBreakpoint breakpoint = index.breakpointForEntryPoint(topic, entryPoint).orElse(null);
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        return new ActionContext(index, editorContext, topic, entryPoint, breakpoint, evidence);
    }

    /**
     * 优先使用光标命中的入口，其次恢复上次入口，最后使用专题第一个入口。
     *
     * @param topic       当前专题
     * @param editorEntry 光标命中的入口
     * @return 动作使用的入口
     */
    private AtlasEntryPoint resolveEntryPoint(AtlasTopic topic, AtlasEntryPoint editorEntry) {
        if (topic == null || topic.entryPoints().isEmpty()) {
            return null;
        }
        if (editorEntry != null) {
            return editorEntry;
        }
        String lastMethod = AtlasLearningProgressState.getInstance()
                .progressFor(topic.topicId())
                .lastEntryMethod;
        return topic.entryPoints().stream()
                .filter(entryPoint -> entryPoint.method().equals(lastMethod))
                .findFirst()
                .orElse(topic.entryPoints().get(0));
    }

    /**
     * 保存编辑器动作执行所需的完整阅读上下文。
     *
     * @param index         专题索引服务
     * @param editorContext 原始编辑器上下文
     * @param topic         当前专题
     * @param entryPoint    当前或恢复的入口
     * @param breakpoint    对应推荐断点
     * @param evidence      对应可执行证据
     */
    protected record ActionContext(
            AtlasIndexService index,
            AtlasEditorContext editorContext,
            AtlasTopic topic,
            AtlasEntryPoint entryPoint,
            AtlasBreakpoint breakpoint,
            AtlasEvidence evidence
    ) {
    }
}
