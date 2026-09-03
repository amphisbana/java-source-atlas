package io.github.javasourceatlas.idea.translation;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * 通过动作系统适配 Translation 插件，同时为未安装场景提供明确引导。
 */
public final class AtlasTranslationSupport {

    public static final String PLUGIN_MARKETPLACE_URL =
            "https://plugins.jetbrains.com/plugin/8579-translation";
    private static final PluginId PLUGIN_ID = PluginId.getId("cn.yiiguxing.plugin.translate");
    private static final String TRANSLATE_ACTION_ID = "Translation.EditorTranslateAction";
    private static final String ACTION_PLACE = "JavaSourceAtlas.MethodReading";
    private static final String PLUGIN_MANAGER_CONFIGURABLE_ID = "preferences.pluginManager";

    /**
     * 工具类不需要创建实例。
     */
    private AtlasTranslationSupport() {
    }

    /**
     * 返回 Translation 插件当前是否已经安装并启用。
     *
     * @return 插件可以响应翻译动作时返回 true
     */
    public static boolean isAvailable() {
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PLUGIN_ID);
        return descriptor != null && descriptor.isEnabled();
    }

    /**
     * 返回适合展示在源码页的集成状态。
     *
     * @return 已连接、未启用或未安装状态
     */
    public static String statusText() {
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PLUGIN_ID);
        if (descriptor == null) {
            return "翻译：未安装 Translation";
        }
        return descriptor.isEnabled()
                ? "翻译：Translation 已连接"
                : "翻译：Translation 已安装但未启用";
    }

    /**
     * 选中当前光标所在注释或当前方法的 Javadoc，并调用 Translation 翻译动作。
     *
     * @param project       当前 IDEA 项目
     * @param promptInstall 缺少 Translation 时是否立即展示安装引导
     * @return 是否已经成功触发翻译动作
     */
    public static boolean translateCurrentComment(Project project, boolean promptInstall) {
        if (!ensureAvailable(project, promptInstall)) {
            return false;
        }
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            Messages.showInfoMessage(project, "请先打开 Java 源码并把光标放到注释或方法中。", "Java Source Atlas");
            return false;
        }

        // 2026-09-03：原逻辑在没有注释时仍会调用翻译动作，可能误翻选区或光标所在源码。
        // selectCurrentComment(project, editor);
        if (!selectCurrentComment(project, editor)) {
            Messages.showInfoMessage(
                    project,
                    "当前光标不在注释中，并且当前方法没有 Javadoc。请先把光标放到需要翻译的源码注释中。",
                    "Java Source Atlas"
            );
            return false;
        }
        AnAction action = ActionManager.getInstance().getAction(TRANSLATE_ACTION_ID);
        if (action == null) {
            showIncompatiblePluginMessage(project);
            return false;
        }
        ActionManager.getInstance().tryToExecute(
                action,
                null,
                editor.getContentComponent(),
                ACTION_PLACE,
                true
        );
        return true;
    }

    /**
     * 打开 Translation 自己的引擎与目标语言设置，不复制或修改第三方插件配置。
     *
     * @param project 当前 IDEA 项目
     */
    public static void openTranslationSettings(Project project) {
        if (!ensureAvailable(project, true)) {
            return;
        }
        // 2026-09-03：Translation 3.8.x 未注册独立设置动作，原动作调用保留如下。
        // AnAction action = ActionManager.getInstance().getAction("Translation.TranslationEngineSettingsAction");
        // Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        // if (action == null || editor == null) {
        //     showIncompatiblePluginMessage(project);
        //     return;
        // }
        // ActionManager.getInstance().tryToExecute(
        //         action,
        //         null,
        //         editor.getContentComponent(),
        //         ACTION_PLACE,
        //         true
        // );
        ShowSettingsUtil.getInstance().showSettingsDialog(
                project,
                configurable -> "Translation".equals(configurable.getDisplayName()),
                configurable -> {
                    // 仅负责选中第三方设置页，不读取或修改 Translation 的配置对象。
                }
        );
    }

    /**
     * 检查第三方插件状态，并在需要时提供 IDE 内安装入口和 Marketplace 链接。
     *
     * @param project       当前 IDEA 项目
     * @param promptInstall 是否显示安装引导
     * @return 插件是否已经启用
     */
    private static boolean ensureAvailable(Project project, boolean promptInstall) {
        if (isAvailable()) {
            return true;
        }
        if (!promptInstall) {
            return false;
        }

        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PLUGIN_ID);
        String message = descriptor == null
                ? "翻译源码注释需要 Translation 插件。安装后可直接复用它的翻译引擎和目标语言设置。"
                : "Translation 已安装但未启用，请在插件设置中启用并按提示重启 IDEA。";
        int choice = Messages.showDialog(
                project,
                message,
                "需要 Translation 插件",
                new String[]{"打开 IDEA 插件设置", "浏览器查看", "暂不安装"},
                0,
                Messages.getInformationIcon()
        );
        if (choice == 0) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PLUGIN_MANAGER_CONFIGURABLE_ID);
        } else if (choice == 1) {
            BrowserUtil.browse(PLUGIN_MARKETPLACE_URL);
        }
        return false;
    }

    /**
     * 优先选中光标所在注释，其次选中当前方法的 Javadoc，确保翻译对象是说明文字而不是方法名。
     *
     * @param project 当前 IDEA 项目
     * @param editor  当前文本编辑器
     * @return 找到并选中可翻译注释时返回 true
     */
    private static boolean selectCurrentComment(Project project, Editor editor) {
        TextRange range = ApplicationManager.getApplication().runReadAction(
                (Computable<TextRange>) () -> findCurrentCommentRange(project, editor)
        );
        if (range != null && range.getEndOffset() <= editor.getDocument().getTextLength()) {
            editor.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset());
            editor.getCaretModel().moveToOffset(range.getStartOffset());
            return true;
        }
        return false;
    }

    /**
     * 在 PSI 中查找当前注释范围；调用方必须持有读锁。
     *
     * @param project 当前 IDEA 项目
     * @param editor  当前文本编辑器
     * @return 注释文本范围；没有注释时返回 null
     */
    static TextRange findCurrentCommentRange(Project project, Editor editor) {
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null || psiFile.getTextLength() == 0) {
            return null;
        }
        int offset = Math.min(editor.getCaretModel().getOffset(), psiFile.getTextLength() - 1);
        PsiElement element = psiFile.findElementAt(offset);
        PsiComment comment = PsiTreeUtil.getParentOfType(element, PsiComment.class, false);
        if (comment != null) {
            return comment.getTextRange();
        }
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        return method == null || method.getDocComment() == null
                ? null
                : method.getDocComment().getTextRange();
    }

    /**
     * 提示当前 Translation 版本没有暴露预期动作，避免静默无响应。
     *
     * @param project 当前 IDEA 项目
     */
    private static void showIncompatiblePluginMessage(Project project) {
        Messages.showInfoMessage(
                project,
                "当前 Translation 版本没有提供 Source Atlas 所需的翻译动作，请更新插件后重试。",
                "Java Source Atlas"
        );
    }
}
