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
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
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
    private final JBLabel actionHintLabel = new JBLabel("选择专题后，从源码入口开始阅读");
    private final JBLabel tutorialLocationLabel = new JBLabel("选择源码入口后在 IDEA 内阅读教程");
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JBTabbedPane navigationTabs = new JBTabbedPane();
    private final JButton openDocumentationButton = new JButton("IDE 内阅读", AtlasIcons.DOCUMENTATION);
    private final JButton openExternalDocumentationButton = new JButton("浏览器打开");
    private final JButton navigateSourceButton = new JButton("定位源码", AtlasIcons.SOURCE);
    private final JButton addAllBreakpointsButton = new JButton("添加全部断点");
    private final JButton openLabButton = new JButton("打开 Lab");
    private final JButton debugLabButton = new JButton("Debug Lab");
    private final JButton backToNavigationButton = new JButton("返回专题导航");
    private final JButton tutorialExternalDocumentationButton = new JButton("浏览器打开");
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
        breakpointList.addMouseListener(new MouseAdapter() {
            /**
             * 双击推荐断点时添加当前断点，保留单断点操作但不占用页签顶部空间。
             *
             * @param event 鼠标事件
             */
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    addSelectedBreakpoint();
                }
            }
        });
    }

    /**
     * 连接搜索框和各页签中的操作按钮。
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
        addAllBreakpointsButton.addActionListener(ignored -> addAllBreakpoints());
        openLabButton.addActionListener(ignored -> openLab());
        debugLabButton.addActionListener(ignored -> debugLab());
        backToNavigationButton.addActionListener(ignored -> tabs.setSelectedIndex(0));
        tutorialExternalDocumentationButton.addActionListener(ignored -> openDocumentationExternally());

        // 2026-08-19：把“IDE 内阅读”设为当前专题的主操作，降低首次使用时的选择成本。
        openDocumentationButton.putClientProperty("JButton.buttonType", "default");
        openDocumentationButton.setToolTipText("在 IDEA 内打开当前源码入口对应的教程");
        openExternalDocumentationButton.setToolTipText("在系统浏览器中打开当前教程");
        navigateSourceButton.setToolTipText("跳转到项目或依赖中的源码类和方法");
        addAllBreakpointsButton.setToolTipText("添加当前专题的全部推荐断点");
        openLabButton.setToolTipText("打开当前专题的 Lab 主类");
        debugLabButton.setToolTipText("创建临时配置并 Debug 当前专题 Lab");
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
        root.setBorder(JBUI.Borders.empty(10));
        root.add(createHeader(), BorderLayout.NORTH);

        // 2026-08-19：窄工具窗口不再并排展示三组列表，改为单列分段页签，避免说明文字被挤压。
        navigationTabs.addTab("专题", createListSection(
                "全部专题",
                topicList,
                openDocumentationButton,
                openExternalDocumentationButton
        ));
        navigationTabs.addTab("源码入口", createListSection(
                "关键源码入口",
                entryPointList,
                navigateSourceButton,
                addAllBreakpointsButton,
                openLabButton
        ));
        navigationTabs.addTab("推荐断点", createListSection(
                "推荐断点",
                breakpointList,
                debugLabButton
        ));
        navigationTabs.setBorder(JBUI.Borders.emptyTop(2));
        navigationTabs.addChangeListener(ignored -> updateActionState());
        root.add(navigationTabs, BorderLayout.CENTER);
        return root;
    }

    /**
     * 创建 IDEA 内嵌教程页；JCEF 不可用时展示清晰的浏览器回退说明。
     *
     * @return 教程页组件
     */
    private JComponent createTutorialContent() {
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        root.setBorder(JBUI.Borders.empty(10));
        JPanel tutorialHeader = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        tutorialHeader.setBorder(JBUI.Borders.emptyBottom(4));
        tutorialLocationLabel.setToolTipText("当前教程地址会跟随所选源码入口变化");
        tutorialHeader.add(tutorialLocationLabel, BorderLayout.CENTER);
        JPanel tutorialActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
        tutorialActions.add(backToNavigationButton);
        tutorialActions.add(tutorialExternalDocumentationButton);
        tutorialHeader.add(tutorialActions, BorderLayout.EAST);
        root.add(tutorialHeader, BorderLayout.NORTH);
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
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(10), JBUI.scale(8)));

        // 2026-08-19：搜索框独立置顶，使信息层级与源码浏览流程保持一致。
        searchField.getTextEditor().getEmptyText().setText("搜索类名、方法或专题");
        searchField.setToolTipText("支持专题标题、源码类名、方法名和版本搜索");
        header.add(searchField, BorderLayout.NORTH);

        JPanel summary = new JPanel();
        summary.setLayout(new javax.swing.BoxLayout(summary, javax.swing.BoxLayout.Y_AXIS));
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIUtil.getBoundsColor()),
                JBUI.Borders.empty(10)
        ));
        topicTitleLabel.setFont(topicTitleLabel.getFont().deriveFont(Font.BOLD, 14f));
        topicTitleLabel.setToolTipText("当前选中的源码专题");
        contextLabel.setForeground(UIUtil.getLabelInfoForeground());
        versionLabel.setForeground(UIUtil.getLabelInfoForeground());
        compatibilityLabel.setForeground(UIUtil.getLabelInfoForeground());
        actionHintLabel.setForeground(UIUtil.getLabelInfoForeground());
        summary.add(topicTitleLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));
        summary.add(contextLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(versionLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(compatibilityLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));
        summary.add(actionHintLabel);
        header.add(summary, BorderLayout.CENTER);
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
     * 创建带页签数量和空状态说明的单列列表区域。
     *
     * @param title   区域标题
     * @param list    列表组件
     * @param actions 当前页签显示的操作按钮
     * @return 列表区域
     */
    private JComponent createListSection(String title, JBList<?> list, JButton... actions) {
        JPanel section = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        JPanel sectionHeader = new JPanel();
        sectionHeader.setLayout(new javax.swing.BoxLayout(sectionHeader, javax.swing.BoxLayout.Y_AXIS));

        // 2026-08-19：操作跟随所属页签展示，减少跨场景按钮造成的认知负担。
        if (actions.length > 0) {
            JComponent actionBar = createTabActionBar(actions);
            actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
            sectionHeader.add(actionBar);
            sectionHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));
        }
        JLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(UIUtil.getLabelInfoForeground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(label);
        section.add(sectionHeader, BorderLayout.NORTH);
        list.getEmptyText().setText(emptyTextFor(title));
        section.add(new JBScrollPane(list), BorderLayout.CENTER);
        return section;
    }

    /**
     * 创建页签顶部的紧凑操作栏。
     *
     * @param actions 当前页签可执行的操作
     * @return 顶部操作栏
     */
    private JComponent createTabActionBar(JButton... actions) {
        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        for (JButton action : actions) {
            actionBar.add(action);
        }
        return actionBar;
    }

    /**
     * 根据列表用途提供明确的空状态文案，避免用户把空白误认为页面未加载。
     *
     * @param title 列表标题
     * @return 空状态文案
     */
    private String emptyTextFor(String title) {
        if ("全部专题".equals(title)) {
            return "暂无匹配专题，请调整搜索条件";
        }
        if ("关键源码入口".equals(title)) {
            return "当前专题暂无源码入口";
        }
        return "当前专题暂无推荐断点";
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
            topicTitleLabel.setToolTipText("调整搜索条件后重新选择专题");
            contextLabel.setText("当前光标：未找到匹配专题");
            versionLabel.setText("");
            compatibilityLabel.setText("调整搜索条件后重试");
            actionHintLabel.setText("输入类名、方法名或专题名称开始搜索");
            updateNavigationTabTitles();
            updateActionState();
            return;
        }

        topicTitleLabel.setText(StringUtil.shortenTextWithEllipsis(topic.title(), 58, 0));
        topicTitleLabel.setToolTipText(topic.title());
        topic.entryPoints().forEach(entryPointModel::addElement);
        topic.breakpoints().forEach(breakpointModel::addElement);
        versionLabel.setText("项目版本：检测中…");
        compatibilityLabel.setText("教程基线：" + topic.primaryVersion());
        actionHintLabel.setText("先选源码入口，再阅读调用链或定位实现");
        updateNavigationTabTitles();
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
        addAllBreakpointsButton.setEnabled(topic != null && !topic.breakpoints().isEmpty());
        navigateSourceButton.setEnabled(false);
        openLabButton.setEnabled(false);
        debugLabButton.setEnabled(false);
        if (topic != null && navigationTabs.getSelectedIndex() == 2) {
            actionHintLabel.setText(breakpoint == null
                    ? "查看推荐断点，并使用 Debug Lab 复现调试现场"
                    : "当前断点已选中，可启动 Debug Lab 观察变量变化");
        } else if (topic != null && navigationTabs.getSelectedIndex() == 1) {
            actionHintLabel.setText(entryPoint == null
                    ? "选择一个源码入口开始阅读"
                    : "可定位源码、添加全部断点或打开配套 Lab");
        } else if (topic != null) {
            actionHintLabel.setText(entryPoint == null
                    ? "当前专题暂无可阅读的源码入口"
                    : "可在 IDEA 内阅读默认入口，也可使用浏览器打开");
        }
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
     * 根据当前专题刷新三个工作页签的数量，帮助用户判断每个页签是否有内容。
     */
    private void updateNavigationTabTitles() {
        navigationTabs.setTitleAt(0, "专题 " + topicModel.size());
        navigationTabs.setTitleAt(1, "源码入口 " + entryPointModel.size());
        navigationTabs.setTitleAt(2, "推荐断点 " + breakpointModel.size());
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
            append(StringUtil.shortenTextWithEllipsis(value.title(), 52, 0));
            append("  " + value.primaryVersion(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setBorder(JBUI.Borders.empty(3, 2));
            setToolTipText(value.title() + " · " + value.primaryVersion());
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
            append("  " + StringUtil.shortenTextWithEllipsis(
                    StringUtil.notNullize(value.purpose()), 68, 0
            ), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setBorder(JBUI.Borders.empty(3, 2));
            setToolTipText(value.method() + " · " + StringUtil.notNullize(value.purpose()));
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
            append("  " + StringUtil.shortenTextWithEllipsis(
                    StringUtil.notNullize(value.scenario()), 56, 0
            ), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            if (!value.variables().isEmpty()) {
                String variables = StringUtil.shortenTextWithEllipsis(
                        String.join(", ", value.variables()), 54, 0
                );
                append("  [" + variables + "]", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            setBorder(JBUI.Borders.empty(3, 2));
            setToolTipText(value.method() + " · " + StringUtil.notNullize(value.scenario())
                    + (value.variables().isEmpty() ? "" : " · 观察变量：" + String.join(", ", value.variables()))
                    + " · 双击添加当前断点");
        }
    }
}
