package io.github.javasourceatlas.idea.gutter;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import io.github.javasourceatlas.idea.context.AtlasTopicMatcher;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.settings.AtlasSettingsState;
import io.github.javasourceatlas.idea.ui.AtlasTopicChooser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 在已收录的 Java 方法旁显示 Source Atlas gutter 图标。
 */
public final class AtlasLineMarkerProvider implements LineMarkerProvider {

    /**
     * 匹配方法名称标识符并创建教程导航图标。
     *
     * @param element 当前 PSI 元素
     * @return 行标记；未收录时返回 null
     */
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiMethod method)) {
            return null;
        }
        PsiClass psiClass = method.getContainingClass();
        if (psiClass == null || psiClass.getQualifiedName() == null) {
            return null;
        }

        AtlasIndexService index = ApplicationManager.getApplication().getService(AtlasIndexService.class);
        // 2026-08-26：原逻辑只取共享源码类对应的第一个专题；gutter 现在复用统一候选排序并跳过无法唯一判断的类。
        // AtlasTopic topic = index.findBySourceClass(psiClass.getQualifiedName()).orElse(null);
        AtlasTopicMatcher.Resolution resolution = AtlasTopicMatcher.resolve(
                index,
                element.getProject(),
                psiClass.getQualifiedName(),
                method
        );
        AtlasTopic topic = resolution.topic();
        AtlasEntryPoint entryPoint = resolution.entryPoint();
        if (entryPoint == null && resolution.candidates().size() < 2) {
            return null;
        }

        GutterIconNavigationHandler<PsiElement> handler =
                (MouseEvent event, PsiElement ignored) -> {
                    AtlasTopic selectedTopic = topic;
                    AtlasEntryPoint selectedEntryPoint = entryPoint;
                    if (selectedTopic == null) {
                        int selected = chooseTopic(resolution.candidates(), element.getProject());
                        if (selected < 0) {
                            return;
                        }
                        selectedTopic = resolution.candidates().get(selected);
                        selectedEntryPoint = AtlasMethodMatcher.findBestEntryPoint(
                                selectedTopic,
                                psiClass.getQualifiedName(),
                                method
                        ).orElse(null);
                    }
                    if (selectedEntryPoint == null) {
                        return;
                    }
                    // 2026-08-20：gutter 主动阅读入口纳入最近阅读历史，便于在学习路径中恢复上下文。
                    // 2026-08-24：原逻辑只记录专题编号，现在同步方法、文档锚点和阅读版本。
                    // AtlasLearningProgressState.getInstance().recordRecent(topic.topicId());
                    AtlasLearningProgressState.getInstance().recordEntry(
                            selectedTopic.topicId(),
                            selectedEntryPoint.method(),
                            selectedEntryPoint.document(),
                            selectedTopic.primaryVersion()
                    );
                    BrowserUtil.browse(
                            AtlasSettingsState.getInstance().documentationUrl(selectedEntryPoint.document())
                    );
                };
        String tooltip = topic == null
                ? "选择 Source Atlas 专题"
                : "打开 Source Atlas：" + entryPoint.purpose();
        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                AtlasIcons.ATLAS,
                ignored -> tooltip,
                handler,
                GutterIconRenderer.Alignment.RIGHT,
                () -> "打开 Source Atlas 教程"
        );
    }

    /**
     * 在 gutter 图标点击时让用户选择共享源码类对应的专题。
     *
     * @param candidates 已按方法入口、版本和源码类排序的候选
     * @param project    当前 IDEA 项目
     * @return 选中下标；取消时返回负数
     */
    private int chooseTopic(List<AtlasTopic> candidates, Project project) {
        // 2026-08-27：原逻辑由 gutter 单独构造选择器，现在复用统一专题选择交互。
        // String[] options = candidates.stream()
        //         .map(candidate -> candidate.title() + "（" + candidate.primaryVersion() + "）")
        //         .toArray(String[]::new);
        // return Messages.showChooseDialog(
        //         project,
        //         "当前源码类对应多个 Source Atlas 专题，请选择要阅读的专题：",
        //         "选择 Source Atlas 专题",
        //         AtlasIcons.ATLAS,
        //         options,
        //         options[0]
        // );
        AtlasTopic selected = AtlasTopicChooser.choose(project, candidates);
        return selected == null ? -1 : candidates.indexOf(selected);
    }
}
