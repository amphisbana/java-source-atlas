package io.github.javasourceatlas.idea.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import io.github.javasourceatlas.idea.browser.AtlasEmbeddedBrowser;
import io.github.javasourceatlas.idea.browser.AtlasEmbeddedBrowserFactory;
import io.github.javasourceatlas.idea.context.AtlasContextResolver;
import io.github.javasourceatlas.idea.debug.AtlasBreakpointManager;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.lab.AtlasLabLauncher;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.model.AtlasVersionInfo;
import io.github.javasourceatlas.idea.navigation.AtlasSourceNavigator;
import io.github.javasourceatlas.idea.settings.AtlasSettingsState;
import io.github.javasourceatlas.idea.version.AtlasVersionDetector;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 展示当前源码上下文、专题入口、推荐断点和版本信息。
 */
public final class AtlasToolWindowPanel extends SimpleToolWindowPanel implements Disposable {

    private final Project project;
    private final AtlasIndexService index;
    private final SearchTextField searchField = new SearchTextField(false);
    private final DefaultListModel<AtlasTopic> topicModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasEntryPoint> entryPointModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasBreakpoint> breakpointModel = new DefaultListModel<>();
    private final JBList<AtlasTopic> topicList = new JBList<>(topicModel);
    private final JBList<AtlasEntryPoint> entryPointList = new JBList<>(entryPointModel);
    private final JBList<AtlasBreakpoint> breakpointList = new JBList<>(breakpointModel);
    private final JBLabel contextLabel = new JBLabel("当前光标：等待 Java 编辑器");
    private final JBLabel topicTitleLabel = new JBLabel("选择一个源码专题");
    private final JBLabel versionLabel = new JBLabel();
    private final JBLabel compatibilityLabel = new JBLabel();
    private final JBLabel tutorialLocationLabel = new JBLabel("选择源码入口后在 IDEA 内阅读教程");
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JButton openDocumentationButton = new JButton("IDE 内阅读", AtlasIcons.DOCUMENTATION);
    private final JButton openExternalDocumentationButton = new JButton("浏览器打开");
    private final JButton navigateSourceButton = new JButton("定位源码", AtlasIcons.SOURCE);
    private final JButton addBreakpointButton = new JButton("添加当前断点");
    private final JButton addAllBreakpointsButton = new JButton("添加全部断点");
    private final JButton openLabButton = new JButton("打开 Lab");
    private final JButton debugLabButton = new JButton("Debug Lab");
    private final Timer contextTimer;

    private AtlasEmbeddedBrowser tutorialBrowser;
    private String lastContextKey = "";
    private AtlasEditorContext editorContext = new AtlasEditorContext(null, null, null, null);
    private boolean contextRefreshPending;

    /**
     * 构建工具窗口并启动轻量上下文刷新计时器。
     *
     * @param project 当前项目
     */
    public AtlasToolWindowPanel(Project project) {
        super(true, true);
        this.project = project;
        this.index = ApplicationManager.getApplication().getService(AtlasIndexService.class);

        configureLists();
        configureActions();
        setContent(createMainContent());
        rebuildTopicList("");
        refreshFromEditor(true);

        contextTimer = new Timer(800, ignored -> refreshFromEditor(false));
        contextTimer.setRepeats(true);
        contextTimer.start();
    }

    /**
     * 配置列表选择方式、渲染内容和双击导航行为。
     */
    private void configureLists() {
        topicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entryPointList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        breakpointList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        topicList.setCellRenderer(new TopicRenderer());
        entryPointList.setCellRenderer(new EntryPointRenderer());
        breakpointList.setCellRenderer(new BreakpointRenderer());

        topicList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showTopic(topicList.getSelectedValue(), null);
            }
        });
        entryPointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActionState();
            }
        });
        breakpointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActionState();
            }
        });
        entryPointList.addMouseListener(new MouseAdapter() {
            /**
             * 双击入口时直接定位源码。
             *
             * @param event 鼠标事件
             */
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    navigateToSource();
                }
            }
        });
    }

    /**
     * 连接搜索框和底部命令按钮。
     */
    private void configureActions() {
        searchField.getTextEditor().getDocument().addDocumentListener(new DocumentAdapter() {
            /**
             * 搜索文本变化时重建专题列表。
             *
             * @param event 文档事件
             */
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                rebuildTopicList(searchField.getText());
            }
        });
        openDocumentationButton.addActionListener(ignored -> openDocumentationInIde());
        openExternalDocumentationButton.addActionListener(ignored -> openDocumentationExternally());
        navigateSourceButton.addActionListener(ignored -> navigateToSource());
        addBreakpointButton.addActionListener(ignored -> addSelectedBreakpoint());
        addAllBreakpointsButton.addActionListener(ignored -> addAllBreakpoints());
        openLabButton.addActionListener(ignored -> openLab());
        debugLabButton.addActionListener(ignored -> debugLab());
    }

    /**
     * 创建工具窗口的主布局。
     *
     * @return 主组件
     */
    private JComponent createMainContent() {
        tabs.addTab("专题导航", createNavigationContent());
        tabs.addTab("教程阅读", createTutorialContent());
        return tabs;
    }

    /**
     * 创建专题列表、源码入口、断点和命令区组成的导航页。
     *
     * @return 导航页组件
     */
    private JComponent createNavigationContent() {
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        root.setBorder(JBUI.Borders.empty(8));
        root.add(createHeader(), BorderLayout.NORTH);

        JBSplitter outerSplitter = new JBSplitter(true, 0.34f);
        outerSplitter.setFirstComponent(createSection("全部专题", new JBScrollPane(topicList)));

        JBSplitter detailSplitter = new JBSplitter(true, 0.58f);
        detailSplitter.setFirstComponent(createSection("关键源码入口", new JBScrollPane(entryPointList)));
        detailSplitter.setSecondComponent(createSection("推荐断点", new JBScrollPane(breakpointList)));
        outerSplitter.setSecondComponent(detailSplitter);
        root.add(outerSplitter, BorderLayout.CENTER);
        root.add(createCommandBar(), BorderLayout.SOUTH);
        return root;
    }

    /**
     * 创建 IDEA 内嵌教程页；JCEF 不可用时展示清晰的浏览器回退说明。
     *
     * @return 教程页组件
     */
    private JComponent createTutorialContent() {
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        root.setBorder(JBUI.Borders.empty(8));
        tutorialLocationLabel.setBorder(JBUI.Borders.emptyBottom(4));
        root.add(tutorialLocationLabel, BorderLayout.NORTH);
        tutorialBrowser = AtlasEmbeddedBrowserFactory.create();
        if (tutorialBrowser != null) {
            root.add(tutorialBrowser.component(), BorderLayout.CENTER);
        } else {
            JBLabel fallback = new JBLabel(
                    "当前 IDEA 运行环境不支持 JCEF，请使用“浏览器打开”查看教程。",
                    JBLabel.CENTER
            );
            root.add(fallback, BorderLayout.CENTER);
        }
        return root;
    }

    /**
     * 创建当前上下文、搜索框和版本提示区域。
     *
     * @return 头部组件
     */
    private JComponent createHeader() {
        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));

        topicTitleLabel.setFont(topicTitleLabel.getFont().deriveFont(Font.BOLD));
        header.add(topicTitleLabel);
        header.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
        header.add(contextLabel);
        header.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        header.add(versionLabel);
        header.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        header.add(compatibilityLabel);
        header.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));

        searchField.getTextEditor().getEmptyText().setText("搜索类、方法或专题");
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchField.getPreferredSize().height));
        header.add(searchField);
        return header;
    }

    /**
     * 创建带紧凑标题的内容分区。
     *
     * @param title   分区标题
     * @param content 分区内容
     * @return 分区组件
     */
    private JComponent createSection(String title, JComponent content) {
        JPanel section = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        section.add(label, BorderLayout.NORTH);
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    /**
     * 创建教程、源码、断点与 Lab 命令区。
     *
     * @return 命令区组件
     */
    private JComponent createCommandBar() {
        JPanel commandBar = new JPanel();
        commandBar.setLayout(new javax.swing.BoxLayout(commandBar, javax.swing.BoxLayout.Y_AXIS));
        commandBar.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(4), 0, 0, 0));
        JPanel navigationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        navigationRow.add(openDocumentationButton);
        navigationRow.add(openExternalDocumentationButton);
        navigationRow.add(navigateSourceButton);
        JPanel experimentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)));
        experimentRow.add(addBreakpointButton);
        experimentRow.add(addAllBreakpointsButton);
        experimentRow.add(openLabButton);
        experimentRow.add(debugLabButton);
        commandBar.add(navigationRow);
        commandBar.add(experimentRow);
        return commandBar;
    }

    /**
     * 按搜索文本重建专题列表并尽量保留当前选择。
     *
     * @param query 搜索文本
     */
    private void rebuildTopicList(String query) {
        AtlasTopic previous = topicList.getSelectedValue();
        List<AtlasTopic> matches = index.search(query);
        topicModel.clear();
        matches.forEach(topicModel::addElement);

        AtlasTopic preferred = editorContext.topic() != null && matches.contains(editorContext.topic())
                ? editorContext.topic()
                : previous;
        if (preferred != null && matches.contains(preferred)) {
            topicList.setSelectedValue(preferred, true);
        } else if (!matches.isEmpty()) {
            topicList.setSelectedIndex(0);
        } else {
            showTopic(null, null);
        }
    }

    /**
     * 把选中专题的入口、断点和版本信息填充到详情区。
     *
     * @param topic          选中专题
     * @param preferredEntry 希望优先选中的方法入口
     */
    private void showTopic(AtlasTopic topic, AtlasEntryPoint preferredEntry) {
        entryPointModel.clear();
        breakpointModel.clear();
        if (topic == null) {
            topicTitleLabel.setText("没有匹配的源码专题");
            compatibilityLabel.setText("调整搜索条件后重试");
            updateActionState();
            return;
        }

        topicTitleLabel.setText(topic.title());
        topic.entryPoints().forEach(entryPointModel::addElement);
        topic.breakpoints().forEach(breakpointModel::addElement);
        versionLabel.setText("项目版本：检测中…");
        compatibilityLabel.setText("教程基线：" + topic.primaryVersion());
        AtlasVersionDetector.detectAsync(project, this, versionInfo -> applyVersionInfo(topic, versionInfo));

        if (preferredEntry != null && topic.entryPoints().contains(preferredEntry)) {
            entryPointList.setSelectedValue(preferredEntry, true);
        } else if (!topic.entryPoints().isEmpty()) {
            entryPointList.setSelectedIndex(0);
        }
        updateActionState();
    }

    /**
     * 检查编辑器上下文是否变化，并在变化时同步专题与入口选择。
     *
     * @param force 是否忽略上下文键强制刷新
     */
    private void refreshFromEditor(boolean force) {
        if (contextRefreshPending) {
            return;
        }
        contextRefreshPending = true;
        AtlasContextResolver.resolveAsync(project, index, this, latest -> {
            contextRefreshPending = false;
            applyEditorContext(force, latest);
        });
    }

    /**
     * 把后台解析完成的编辑器上下文应用到工具窗口。
     *
     * @param force  是否忽略上下文键强制刷新
     * @param latest 最新编辑器上下文
     */
    private void applyEditorContext(boolean force, AtlasEditorContext latest) {
        if (!force && latest.contextKey().equals(lastContextKey)) {
            return;
        }
        editorContext = latest;
        lastContextKey = latest.contextKey();
        contextLabel.setText(latest.className() == null
                ? "当前光标：未识别到 Java 类"
                : "当前光标：" + latest.className()
                + (latest.methodName() == null ? "" : "#" + latest.methodName()));

        if (latest.topic() != null) {
            searchField.setText("");
            topicList.setSelectedValue(latest.topic(), true);
            showTopic(latest.topic(), latest.entryPoint());
        }
    }

    /**
     * 仅当专题仍处于选中状态时展示后台检测到的项目版本。
     *
     * @param topic       发起检测时的专题
     * @param versionInfo 项目版本信息
     */
    private void applyVersionInfo(AtlasTopic topic, AtlasVersionInfo versionInfo) {
        if (!topic.equals(topicList.getSelectedValue())) {
            return;
        }
        String versionText = "项目 JDK：" + versionInfo.jdkVersion()
                + "；Spring：" + versionInfo.springVersion()
                + "；Boot：" + versionInfo.springBootVersion();
        String compatibilityText = AtlasVersionDetector.compatibilityHint(topic, versionInfo);
        versionLabel.setText(shortLabel(versionText));
        versionLabel.setToolTipText(versionText);
        compatibilityLabel.setText(shortLabel(compatibilityText));
        compatibilityLabel.setToolTipText(compatibilityText);
    }

    /**
     * 根据当前选择启用或禁用命令按钮。
     */
    private void updateActionState() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        openDocumentationButton.setEnabled(topic != null && entryPoint != null);
        openExternalDocumentationButton.setEnabled(topic != null && entryPoint != null);
        addBreakpointButton.setEnabled(topic != null && breakpoint != null);
        addAllBreakpointsButton.setEnabled(topic != null && !topic.breakpoints().isEmpty());
        navigateSourceButton.setEnabled(false);
        openLabButton.setEnabled(false);
        debugLabButton.setEnabled(false);
        navigateSourceButton.setToolTipText(topic == null || entryPoint == null
                ? "选择源码入口后可定位"
                : "正在检查项目类路径…");
        openLabButton.setToolTipText(topic == null ? "选择专题后可打开实验" : "正在检查 Lab 主类…");
        debugLabButton.setToolTipText(topic == null ? "选择专题后可调试实验" : "正在检查 Lab 主类…");
        if (topic == null) {
            return;
        }

        AtlasLabLauncher.checkAvailability(project, this, topic, available -> {
            if (!topic.equals(topicList.getSelectedValue())) {
                return;
            }
            openLabButton.setEnabled(available);
            debugLabButton.setEnabled(available);
            String labHint = available
                    ? "已找到 " + topic.lab().mainClass()
                    : "当前项目未导入 " + topic.lab().module();
            openLabButton.setToolTipText(labHint);
            debugLabButton.setToolTipText(labHint);
        });
        if (entryPoint != null) {
            AtlasSourceNavigator.checkAvailability(project, this, topic, entryPoint, available -> {
                if (!topic.equals(topicList.getSelectedValue())
                        || !entryPoint.equals(entryPointList.getSelectedValue())) {
                    return;
                }
                navigateSourceButton.setEnabled(available);
                navigateSourceButton.setToolTipText(available
                        ? "跳转到项目或依赖源码"
                        : "当前项目类路径中未找到该源码类");
            });
        }
    }

    /**
     * 在 IDEA 内嵌浏览器中打开选中方法对应的教程锚点。
     */
    private void openDocumentationInIde() {
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        if (entryPoint == null) {
            return;
        }
        String url = AtlasSettingsState.getInstance().documentationUrl(entryPoint.document());
        tutorialLocationLabel.setText(shortLabel("当前教程：" + entryPoint.method()));
        tutorialLocationLabel.setToolTipText(url);
        tabs.setSelectedIndex(1);
        if (tutorialBrowser == null) {
            Messages.showInfoMessage(
                    project,
                    "当前 IDEA 运行环境不支持内嵌教程，请使用“浏览器打开”。",
                    "Java Source Atlas"
            );
            return;
        }
        tutorialBrowser.loadUrl(url);
    }

    /**
     * 在系统浏览器中打开选中方法对应的教程锚点。
     */
    private void openDocumentationExternally() {
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        if (entryPoint != null) {
            BrowserUtil.browse(AtlasSettingsState.getInstance().documentationUrl(entryPoint.document()));
        }
    }

    /**
     * 添加当前列表选中的一个推荐断点。
     */
    private void addSelectedBreakpoint() {
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        if (breakpoint != null) {
            addBreakpoints(List.of(breakpoint));
        }
    }

    /**
     * 添加当前专题全部可解析的推荐断点。
     */
    private void addAllBreakpoints() {
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic != null) {
            addBreakpoints(topic.breakpoints());
        }
    }

    /**
     * 调用断点管理器并展示新增、重复和未解析数量。
     *
     * @param breakpoints 待添加断点
     */
    private void addBreakpoints(List<AtlasBreakpoint> breakpoints) {
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic == null || breakpoints.isEmpty()) {
            return;
        }
        addBreakpointButton.setEnabled(false);
        addAllBreakpointsButton.setEnabled(false);
        AtlasBreakpointManager.addBreakpointsAsync(project, this, topic, breakpoints, result -> {
            updateActionState();
            String unresolved = result.unresolved().isEmpty()
                    ? ""
                    : "\n未找到：" + summarizeUnresolved(result.unresolved());
            Messages.showInfoMessage(
                    project,
                    "新增 " + result.added() + " 个，已存在 " + result.existing() + " 个，未解析 "
                            + result.unresolved().size() + " 个。" + unresolved,
                    "Source Atlas 推荐断点"
            );
        });
    }

    /**
     * 打开当前专题配套 Lab 主类。
     */
    private void openLab() {
        AtlasTopic topic = topicList.getSelectedValue();
        openLabButton.setEnabled(false);
        AtlasLabLauncher.openAsync(project, this, topic, opened -> {
            updateActionState();
            if (!opened) {
                showMissingLabMessage(topic);
            }
        });
    }

    /**
     * 创建临时 Application 配置并以 Debug 模式运行当前专题 Lab。
     */
    private void debugLab() {
        AtlasTopic topic = topicList.getSelectedValue();
        debugLabButton.setEnabled(false);
        AtlasLabLauncher.debugAsync(project, this, topic, started -> {
            updateActionState();
            if (!started) {
                showMissingLabMessage(topic);
            }
        });
    }

    /**
     * 提示用户需要把索引声明的 labs 模块导入当前 IDEA 项目。
     *
     * @param topic 当前专题
     */
    private void showMissingLabMessage(AtlasTopic topic) {
        String module = topic == null || topic.lab() == null ? "对应 labs 模块" : topic.lab().module();
        Messages.showInfoMessage(
                project,
                "当前项目中未找到 Lab 主类。请用 IDEA 打开完整 java-source-atlas 仓库并导入 " + module + "。",
                "Java Source Atlas"
        );
    }

    /**
     * 缩短窄工具窗口中的单行标签，完整内容由 tooltip 保留。
     *
     * @param text 完整标签文本
     * @return 最多 64 个字符的展示文本
     */
    private String shortLabel(String text) {
        return StringUtil.shortenTextWithEllipsis(StringUtil.notNullize(text), 64, 0);
    }

    /**
     * 推荐断点大量未解析时只展示前六项，避免消息框被长列表撑满。
     *
     * @param unresolved 未解析签名
     * @return 紧凑摘要
     */
    private String summarizeUnresolved(List<String> unresolved) {
        List<String> visible = unresolved.stream().limit(6).toList();
        return String.join("、", visible)
                + (unresolved.size() > visible.size() ? " 等 " + unresolved.size() + " 项" : "");
    }

    /**
     * 跳转到选中入口的 PSI 方法；无法定位时给出明确提示。
     */
    private void navigateToSource() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        navigateSourceButton.setEnabled(false);
        navigateSourceButton.setText("定位中…");
        AtlasSourceNavigator.navigateAsync(project, this, topic, entryPoint, navigated -> {
            navigateSourceButton.setText("定位源码");
            updateActionState();
            if (!navigated) {
                Messages.showInfoMessage(
                        project,
                        "当前项目或依赖源码中没有找到该类。请先附加对应 JDK/Spring 源码。",
                        "Java Source Atlas"
                );
            }
        });
    }

    /**
     * 工具窗口关闭时停止刷新计时器并释放 JCEF 浏览器资源。
     */
    @Override
    public void dispose() {
        contextTimer.stop();
        if (tutorialBrowser != null) {
            tutorialBrowser.dispose();
        }
    }

    /**
     * 渲染专题标题和主要版本。
     */
    private static final class TopicRenderer extends ColoredListCellRenderer<AtlasTopic> {

        /**
         * 为专题列表追加标题和灰色版本信息。
         *
         * @param list     当前列表
         * @param value    专题
         * @param index    行号
         * @param selected 是否选中
         * @param hasFocus 是否拥有焦点
         */
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends AtlasTopic> list,
                AtlasTopic value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            append(value.title());
            append("  " + value.primaryVersion(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setBorder(JBUI.Borders.empty(3, 2));
        }
    }

    /**
     * 渲染入口方法和阅读目的。
     */
    private static final class EntryPointRenderer extends ColoredListCellRenderer<AtlasEntryPoint> {

        /**
         * 为入口列表追加方法签名和灰色目的说明。
         *
         * @param list     当前列表
         * @param value    方法入口
         * @param index    行号
         * @param selected 是否选中
         * @param hasFocus 是否拥有焦点
         */
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends AtlasEntryPoint> list,
                AtlasEntryPoint value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            append(value.method(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append("  " + StringUtil.notNullize(value.purpose()), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setBorder(JBUI.Borders.empty(3, 2));
        }
    }

    /**
     * 渲染推荐断点和实验场景。
     */
    private static final class BreakpointRenderer extends ColoredListCellRenderer<AtlasBreakpoint> {

        /**
         * 为断点列表追加方法、场景和观察变量。
         *
         * @param list     当前列表
         * @param value    推荐断点
         * @param index    行号
         * @param selected 是否选中
         * @param hasFocus 是否拥有焦点
         */
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends AtlasBreakpoint> list,
                AtlasBreakpoint value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            append(value.method(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append("  " + StringUtil.notNullize(value.scenario()), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            if (!value.variables().isEmpty()) {
                append("  [" + String.join(", ", value.variables()) + "]", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            setBorder(JBUI.Borders.empty(3, 2));
        }
    }
}
