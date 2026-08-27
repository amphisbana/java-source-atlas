package io.github.javasourceatlas.idea.settings;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import io.github.javasourceatlas.idea.environment.AtlasEnvironmentChecker;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.FlowLayout;
import java.util.Objects;

/**
 * 提供 Java Source Atlas 教程地址设置页。
 */
public final class AtlasConfigurable implements Configurable {

    private JBTextField docsBaseUrlField;
    private JButton testConnectionButton;
    private JButton restoreDefaultButton;
    private JBLabel connectionStatusLabel;
    private Disposable connectionDisposable;
    private int connectionTestGeneration;

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
        testConnectionButton = new JButton("测试连接", AllIcons.Actions.Execute);
        restoreDefaultButton = new JButton("恢复默认", AllIcons.Actions.Rollback);
        connectionStatusLabel = new JBLabel("尚未测试", AllIcons.General.Information, JBLabel.LEFT);
        connectionDisposable = Disposer.newDisposable("Java Source Atlas 教程连接测试");

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        actions.add(testConnectionButton);
        actions.add(restoreDefaultButton);
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("教程站点地址："), docsBaseUrlField, 1, false)
                .addComponent(new JBLabel("默认使用 http://source.shaojie.wang/atlas；本地开发可改为 http://127.0.0.1:4180"), 1)
                .addComponent(actions, 1)
                .addComponent(connectionStatusLabel, 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        configureActions();
        reset();
        return panel;
    }

    /**
     * 连接设置页中的测试与恢复操作。
     */
    private void configureActions() {
        Objects.requireNonNull(testConnectionButton).addActionListener(ignored -> testDocumentationConnection());
        Objects.requireNonNull(restoreDefaultButton).addActionListener(ignored -> restoreDefaultDocumentationUrl());
        Objects.requireNonNull(docsBaseUrlField).getDocument().addDocumentListener(new DocumentAdapter() {
            /**
             * 地址变化时作废旧连接结果，并允许用户立即测试新地址。
             *
             * @param event 输入框文档事件
             */
            @Override
            protected void textChanged(@org.jetbrains.annotations.NotNull DocumentEvent event) {
                connectionTestGeneration++;
                if (testConnectionButton != null) {
                    testConnectionButton.setEnabled(true);
                }
                updateConnectionStatus("地址已修改，尚未测试", StatusType.PENDING);
            }
        });
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
     * 校验并保存教程站点地址，非法地址会留在设置页供用户直接修正。
     *
     * @throws ConfigurationException 地址不是带主机名的 HTTP 或 HTTPS 地址
     */
    @Override
    public void apply() throws ConfigurationException {
        String candidate = Objects.requireNonNull(docsBaseUrlField).getText().trim();
        String validationMessage = documentationUrlValidationMessage(candidate);
        if (validationMessage != null) {
            throw new ConfigurationException(validationMessage);
        }
        // 2026-08-27：原逻辑不校验输入内容，任意字符串都会直接写入插件设置。
        // AtlasSettingsState.getInstance().docsBaseUrl = candidate;
        AtlasSettingsState.getInstance().docsBaseUrl = candidate;
    }

    /**
     * 用当前持久化状态重置表单。
     */
    @Override
    public void reset() {
        if (docsBaseUrlField != null) {
            docsBaseUrlField.setText(AtlasSettingsState.getInstance().docsBaseUrl);
            connectionTestGeneration++;
            updateConnectionStatus("尚未测试", StatusType.PENDING);
        }
    }

    /**
     * 在后台测试输入中的教程站点，完成后把结果就地显示在设置页。
     */
    private void testDocumentationConnection() {
        String candidate = Objects.requireNonNull(docsBaseUrlField).getText().trim();
        String validationMessage = documentationUrlValidationMessage(candidate);
        if (validationMessage != null) {
            updateConnectionStatus(validationMessage, StatusType.FAILURE);
            return;
        }

        int generation = ++connectionTestGeneration;
        Objects.requireNonNull(testConnectionButton).setEnabled(false);
        updateConnectionStatus("正在连接 " + candidate, StatusType.PENDING);
        AtlasEnvironmentChecker.checkDocumentationAsync(
                Objects.requireNonNull(connectionDisposable),
                candidate,
                status -> applyConnectionResult(generation, status)
        );
    }

    /**
     * 仅应用最后一次连接测试结果，避免较慢的旧请求覆盖新地址状态。
     *
     * @param generation 本次测试编号
     * @param status     教程站点探测结果
     */
    private void applyConnectionResult(
            int generation,
            AtlasEnvironmentChecker.DocumentationStatus status
    ) {
        if (generation != connectionTestGeneration || testConnectionButton == null) {
            return;
        }
        testConnectionButton.setEnabled(true);
        updateConnectionStatus(
                status.detail(),
                status.available() ? StatusType.SUCCESS : StatusType.FAILURE
        );
    }

    /**
     * 把输入框恢复为插件默认教程地址，等待用户点击“应用”保存。
     */
    private void restoreDefaultDocumentationUrl() {
        Objects.requireNonNull(docsBaseUrlField).setText(AtlasSettingsState.DEFAULT_DOCS_BASE_URL);
        connectionTestGeneration++;
        if (testConnectionButton != null) {
            testConnectionButton.setEnabled(true);
        }
        updateConnectionStatus("已恢复默认地址，点击“应用”保存", StatusType.PENDING);
    }

    /**
     * 更新连接测试提示的文字与状态图标。
     *
     * @param text 提示文字
     * @param type 状态类型
     */
    private void updateConnectionStatus(String text, StatusType type) {
        if (connectionStatusLabel == null) {
            return;
        }
        connectionStatusLabel.setText(text);
        connectionStatusLabel.setIcon(switch (type) {
            case SUCCESS -> AllIcons.General.InspectionsOK;
            case FAILURE -> AllIcons.General.Error;
            case PENDING -> AllIcons.General.Information;
        });
    }

    /**
     * 返回设置页可以直接展示的教程地址校验结果。
     *
     * @param candidate 待保存的教程站点地址
     * @return 地址合法时返回 null，否则返回错误说明
     */
    static String documentationUrlValidationMessage(String candidate) {
        return AtlasEnvironmentChecker.isSupportedDocumentationUrl(candidate)
                ? null
                : "请输入不带账号、查询参数或锚点的 HTTP/HTTPS 教程根地址。";
    }

    /**
     * 释放设置页持有的 Swing 引用。
     */
    @Override
    public void disposeUIResources() {
        connectionTestGeneration++;
        if (connectionDisposable != null) {
            Disposer.dispose(connectionDisposable);
        }
        docsBaseUrlField = null;
        testConnectionButton = null;
        restoreDefaultButton = null;
        connectionStatusLabel = null;
        connectionDisposable = null;
    }

    /**
     * 区分连接测试的成功、失败和等待状态。
     */
    private enum StatusType {
        SUCCESS,
        FAILURE,
        PENDING
    }
}
