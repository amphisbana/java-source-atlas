package io.github.javasourceatlas.idea.context;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 把当前编辑器光标解析为 Source Atlas 专题上下文。
 */
public final class AtlasContextResolver {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasContextResolver() {
    }

    /**
     * 在读锁中解析当前 Java 类、方法及其专题入口。
     *
     * @param project 当前 IDEA 项目
     * @param index   专题索引
     * @return 当前编辑器上下文；非 Java 文件或没有打开编辑器时返回空上下文
     */
    public static AtlasEditorContext resolve(Project project, AtlasIndexService index) {
        return ApplicationManager.getApplication().runReadAction(
                (Computable<AtlasEditorContext>) () -> resolveInsideReadAction(project, index));
    }

    /**
     * 在后台非阻塞读操作中解析编辑器上下文，并在界面线程交付结果。
     *
     * @param project  当前 IDEA 项目
     * @param index    专题索引
     * @param parent   控制任务生命周期的父级对象
     * @param consumer 界面线程结果处理器
     */
    public static void resolveAsync(
            Project project,
            AtlasIndexService index,
            Disposable parent,
            Consumer<AtlasEditorContext> consumer
    ) {
        ReadAction.nonBlocking((Callable<AtlasEditorContext>) () -> resolveInsideReadAction(project, index))
                .withDocumentsCommitted(project)
                .expireWith(parent)
                .coalesceBy(parent, AtlasContextResolver.class)
                .finishOnUiThread(ModalityState.any(), consumer)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 读取当前 PSI 元素并完成类、方法匹配；调用方必须持有读锁。
     *
     * @param project 当前 IDEA 项目
     * @param index   专题索引
     * @return 解析结果
     */
    private static AtlasEditorContext resolveInsideReadAction(Project project, AtlasIndexService index) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return new AtlasEditorContext(null, null, null, null);
        }

        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return new AtlasEditorContext(null, null, null, null);
        }

        int offset = Math.min(editor.getCaretModel().getOffset(), Math.max(psiFile.getTextLength() - 1, 0));
        PsiElement element = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        PsiClass psiClass = method == null
                ? PsiTreeUtil.getParentOfType(element, PsiClass.class, false)
                : method.getContainingClass();
        if (psiClass == null || psiClass.getQualifiedName() == null) {
            return new AtlasEditorContext(null, null, null, null);
        }

        String className = psiClass.getQualifiedName();
        String methodName = method == null ? null : method.getName();
        // 2026-08-26：原逻辑只取共享源码类对应的第一个专题；现在按方法入口、项目 JDK 版本和主源码类排序。
        // AtlasTopic topic = index.findBySourceClass(className).orElse(null);
        AtlasTopicMatcher.Resolution resolution = AtlasTopicMatcher.resolve(index, project, className, method);
        return new AtlasEditorContext(
                className,
                methodName,
                resolution.topic(),
                resolution.entryPoint(),
                resolution.candidates(),
                AtlasMethodMatcher.signatureOf(method)
        );
    }
}
