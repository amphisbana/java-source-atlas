package io.github.javasourceatlas.idea.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import io.github.javasourceatlas.idea.context.AtlasContextResolver;
import io.github.javasourceatlas.idea.context.AtlasEditorContextSupport;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.ui.AtlasTopicChooser;
import io.github.javasourceatlas.idea.version.AtlasTopicVersion;
import io.github.javasourceatlas.idea.version.AtlasTopicVersionResolver;
import io.github.javasourceatlas.idea.version.AtlasVersionDetector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
        // 2026-08-27：原逻辑只检查唯一命中的 topic，歧义候选会导致编辑器动作全部隐藏。
        // ActionContext context = project == null ? null : resolveContext(project);
        // event.getPresentation().setEnabledAndVisible(context != null && isAvailable(context));
        List<ActionContext> contexts = project == null ? List.of() : resolveContexts(project);
        event.getPresentation().setEnabledAndVisible(contexts.stream().anyMatch(this::isAvailable));
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
        // 2026-08-27：原逻辑无法在共享源码类命中多个专题时选择动作上下文。
        // ActionContext context = resolveContext(project);
        // if (isAvailable(context)) {
        //     perform(project, context);
        // }
        List<ActionContext> availableContexts = resolveContexts(project).stream()
                .filter(this::isAvailable)
                .toList();
        ActionContext selectedContext = selectContext(project, availableContexts);
        if (selectedContext != null) {
            perform(project, selectedContext);
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
     * @return 唯一专题或全部歧义候选对应的动作上下文
     */
    private List<ActionContext> resolveContexts(Project project) {
        AtlasIndexService index = ApplicationManager.getApplication().getService(AtlasIndexService.class);
        AtlasEditorContext editorContext = AtlasContextResolver.resolve(project, index);
        // 2026-08-27：原逻辑只为 editorContext.topic() 构造一个上下文，歧义候选中的 topic 为空。
        // AtlasTopic topic = editorContext.topic();
        // AtlasEntryPoint entryPoint = resolveEntryPoint(topic, editorContext.entryPoint());
        // AtlasBreakpoint breakpoint = index.breakpointForEntryPoint(topic, entryPoint).orElse(null);
        // AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        // return new ActionContext(index, editorContext, topic, entryPoint, breakpoint, evidence);
        return AtlasEditorContextSupport.availableTopics(editorContext).stream()
                .map(topic -> createActionContext(project, index, editorContext, topic))
                .toList();
    }

    /**
     * 为一个候选专题补齐入口、推荐断点和证据场景。
     *
     * @param project       当前项目
     * @param index         专题索引
     * @param editorContext 当前编辑器上下文
     * @param topic         当前候选专题
     * @return 完整动作上下文
     */
    private ActionContext createActionContext(
            Project project,
            AtlasIndexService index,
            AtlasEditorContext editorContext,
            AtlasTopic topic
    ) {
        AtlasTopicVersion version = AtlasTopicVersionResolver.resolve(
                topic,
                AtlasVersionDetector.projectJdkVersion(project)
        );
        AtlasTopic resolvedTopic = version.topic();
        String lastMethod = AtlasLearningProgressState.getInstance()
                .progressFor(resolvedTopic.topicId())
                .lastEntryMethod;
        // 2026-08-27：原逻辑只接收唯一专题的 editorEntry，无法为用户选中的歧义专题恢复当前重载入口。
        // AtlasEntryPoint entryPoint = editorEntry != null
        //         ? editorEntry
        //         : topic.entryPoints().stream()
        //         .filter(candidate -> candidate.method().equals(lastMethod))
        //         .findFirst()
        //         .orElse(topic.entryPoints().get(0));
        // 2026-09-02：原逻辑直接使用 OpenJDK 8 基线专题，项目 JDK 17/21 的私有方法变化会生成错误动作。
        // AtlasEntryPoint entryPoint = AtlasEditorContextSupport.resolveEntryPoint(topic, editorContext, lastMethod);
        AtlasEntryPoint entryPoint = AtlasEditorContextSupport.resolveEntryPoint(
                resolvedTopic,
                editorContext,
                lastMethod
        );
        AtlasBreakpoint breakpoint = index.breakpointForEntryPoint(resolvedTopic, entryPoint).orElse(null);
        AtlasEvidence evidence = index.evidenceForBreakpoint(resolvedTopic, breakpoint).orElse(null);
        return new ActionContext(
                index,
                editorContext,
                resolvedTopic,
                entryPoint,
                breakpoint,
                evidence,
                version.sourceActionsAllowed(),
                version.message()
        );
    }

    /**
     * 在动作真正执行时选择共享源码类的专题，菜单更新阶段只计算可用性而不弹窗。
     *
     * @param project  当前 IDEA 项目
     * @param contexts 满足具体动作条件的候选上下文
     * @return 用户选中的动作上下文；取消时返回 null
     */
    private ActionContext selectContext(Project project, List<ActionContext> contexts) {
        if (contexts.isEmpty()) {
            return null;
        }
        AtlasTopic selectedTopic = AtlasTopicChooser.choose(
                project,
                contexts.stream().map(ActionContext::topic).toList()
        );
        if (selectedTopic == null) {
            return null;
        }
        return contexts.stream()
                .filter(context -> selectedTopic.equals(context.topic()))
                .findFirst()
                .orElse(null);
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
     * @param sourceActionsAllowed 当前项目版本是否允许执行源码与断点动作
     * @param versionMessage 当前项目版本解析说明
     */
    protected record ActionContext(
            AtlasIndexService index,
            AtlasEditorContext editorContext,
            AtlasTopic topic,
            AtlasEntryPoint entryPoint,
            AtlasBreakpoint breakpoint,
            AtlasEvidence evidence,
            boolean sourceActionsAllowed,
            String versionMessage
    ) {
    }
}
