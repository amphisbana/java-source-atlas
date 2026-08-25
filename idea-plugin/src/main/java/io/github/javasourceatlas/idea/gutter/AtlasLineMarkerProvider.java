package io.github.javasourceatlas.idea.gutter;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.settings.AtlasSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

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
        AtlasTopic topic = index.findBySourceClass(psiClass.getQualifiedName()).orElse(null);
        if (topic == null) {
            return null;
        }
        AtlasEntryPoint entryPoint = AtlasMethodMatcher.findBestEntryPoint(
                topic,
                psiClass.getQualifiedName(),
                method
        ).orElse(null);
        if (entryPoint == null) {
            return null;
        }

        GutterIconNavigationHandler<PsiElement> handler =
                (MouseEvent event, PsiElement ignored) -> {
                    // 2026-08-20：gutter 主动阅读入口纳入最近阅读历史，便于在学习路径中恢复上下文。
                    // 2026-08-24：原逻辑只记录专题编号，现在同步方法、文档锚点和阅读版本。
                    // AtlasLearningProgressState.getInstance().recordRecent(topic.topicId());
                    AtlasLearningProgressState.getInstance().recordEntry(
                            topic.topicId(),
                            entryPoint.method(),
                            entryPoint.document(),
                            topic.primaryVersion()
                    );
                    BrowserUtil.browse(
                            AtlasSettingsState.getInstance().documentationUrl(entryPoint.document())
                    );
                };
        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                AtlasIcons.ATLAS,
                ignored -> "打开 Source Atlas：" + entryPoint.purpose(),
                handler,
                GutterIconRenderer.Alignment.RIGHT,
                () -> "打开 Source Atlas 教程"
        );
    }
}
