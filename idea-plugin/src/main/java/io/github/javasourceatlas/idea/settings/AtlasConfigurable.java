package io.github.javasourceatlas.idea.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.Objects;

/**
 * 提供 Java Source Atlas 教程地址设置页。
 */
public final class AtlasConfigurable implements Configurable {

    private JBTextField docsBaseUrlField;

    /**
     * 返回 IDEA 设置树中的显示名称。
     *
     * @return 设置页名称
     */
    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Java Source Atlas";
    }

    /**
     * 创建设置页表单。
     *
     * @return 设置页组件
     */
    @Override
    public @Nullable JComponent createComponent() {
        docsBaseUrlField = new JBTextField();
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("教程站点地址："), docsBaseUrlField, 1, false)
                .addComponent(new JBLabel("默认使用线上站点；本地开发可改为 http://127.0.0.1:4180"), 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    /**
     * 判断表单内容是否不同于已保存设置。
     *
     * @return 是否已修改
     */
    @Override
    public boolean isModified() {
        String current = Objects.requireNonNull(docsBaseUrlField).getText().trim();
        return !current.equals(AtlasSettingsState.getInstance().docsBaseUrl);
    }

    /**
     * 保存教程站点地址。
     */
    @Override
    public void apply() {
        AtlasSettingsState.getInstance().docsBaseUrl =
                Objects.requireNonNull(docsBaseUrlField).getText().trim();
    }

    /**
     * 用当前持久化状态重置表单。
     */
    @Override
    public void reset() {
        if (docsBaseUrlField != null) {
            docsBaseUrlField.setText(AtlasSettingsState.getInstance().docsBaseUrl);
        }
    }

    /**
     * 释放设置页持有的 Swing 引用。
     */
    @Override
    public void disposeUIResources() {
        docsBaseUrlField = null;
    }
}
