package io.github.javasourceatlas.idea.action;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import io.github.javasourceatlas.idea.context.AtlasContextResolver;
import io.github.javasourceatlas.idea.context.AtlasEditorContextSupport;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.settings.AtlasSettingsState;
import io.github.javasourceatlas.idea.ui.AtlasTopicChooser;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
        // 2026-08-27：原逻辑要求唯一 topic，导致共享源码类的教程动作在右键菜单中隐藏。
        // boolean available = project != null && resolveContext(project).topic() != null;
        boolean available = false;
        if (project != null) {
            AtlasEditorContext context = resolveContext(project);
            available = AtlasEditorContextSupport.availableTopics(context).stream()
                    .anyMatch(topic -> !topic.entryPoints().isEmpty());
        }
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
        // 2026-08-27：原逻辑把 topic 为空的歧义候选误判为“尚未收录”。
        // if (context.topic() == null || context.topic().entryPoints().isEmpty()) {
        //     Messages.showInfoMessage(project, "当前 Java 类尚未收录到 Source Atlas。", "Java Source Atlas");
        //     return;
        // }
        List<AtlasTopic> candidates = AtlasEditorContextSupport.availableTopics(context).stream()
                .filter(topic -> !topic.entryPoints().isEmpty())
                .toList();
        if (candidates.isEmpty()) {
            Messages.showInfoMessage(project, "当前 Java 类尚未收录到 Source Atlas。", "Java Source Atlas");
            return;
        }
        AtlasTopic topic = AtlasTopicChooser.choose(project, candidates);
        if (topic == null) {
            return;
        }
        // 2026-08-24：原逻辑在光标只命中类时固定打开第一个入口，和阅读会话的恢复语义不一致。
        // AtlasEntryPoint entryPoint = context.entryPoint() == null
        //         ? context.topic().entryPoints().get(0)
        //         : context.entryPoint();
        AtlasEntryPoint entryPoint = resolveEntryPoint(topic, context);
        // 2026-08-20：编辑器右键主动打开教程也计入最近阅读，和工具窗口入口保持一致。
        // 2026-08-24：原逻辑只记录专题编号，方法级阅读会话需要同步入口、锚点和版本。
        // AtlasLearningProgressState.getInstance().recordRecent(context.topic().topicId());
        AtlasLearningProgressState.getInstance().recordEntry(
                topic.topicId(),
                entryPoint.method(),
                entryPoint.document(),
                topic.primaryVersion()
        );
        BrowserUtil.browse(AtlasSettingsState.getInstance().documentationUrl(entryPoint.document()));
    }

    /**
     * 优先返回光标命中的方法入口，其次恢复上次阅读入口，最后回退到专题第一项。
     *
     * @param topic   用户选定的专题
     * @param context 当前编辑器匹配结果
     * @return 本次需要打开的源码入口
     */
    private AtlasEntryPoint resolveEntryPoint(AtlasTopic topic, AtlasEditorContext context) {
        String lastMethod = AtlasLearningProgressState.getInstance()
                .progressFor(topic.topicId())
                .lastEntryMethod;
        // 2026-08-27：原逻辑只处理 context.topic()，无法为用户选中的歧义专题匹配当前方法。
        // if (context.entryPoint() != null) {
        //     return context.entryPoint();
        // }
        // return context.topic().entryPoints().stream()
        //         .filter(entryPoint -> entryPoint.method().equals(lastMethod))
        //         .findFirst()
        //         .orElse(context.topic().entryPoints().get(0));
        return AtlasEditorContextSupport.resolveEntryPoint(topic, context, lastMethod);
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
