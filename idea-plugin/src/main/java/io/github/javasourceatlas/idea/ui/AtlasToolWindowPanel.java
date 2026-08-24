package io.github.javasourceatlas.idea.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.javasourceatlas.idea.browser.AtlasEmbeddedBrowser;
import io.github.javasourceatlas.idea.browser.AtlasEmbeddedBrowserFactory;
import io.github.javasourceatlas.idea.context.AtlasContextResolver;
import io.github.javasourceatlas.idea.debug.AtlasBreakpointManager;
import io.github.javasourceatlas.idea.environment.AtlasEnvironmentChecker;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.lab.AtlasLabLauncher;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.model.AtlasTopicRelation;
import io.github.javasourceatlas.idea.model.AtlasVersionInfo;
import io.github.javasourceatlas.idea.navigation.AtlasSourceNavigator;
import io.github.javasourceatlas.idea.settings.AtlasConfigurable;
import io.github.javasourceatlas.idea.settings.AtlasSettingsState;
import io.github.javasourceatlas.idea.version.AtlasVersionDetector;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * 展示当前源码上下文、专题入口、推荐断点和版本信息。
 */
public final class AtlasToolWindowPanel extends SimpleToolWindowPanel implements Disposable {

    private static final int ENVIRONMENT_TAB_INDEX = 4;
    private static final String PROJECT_REPOSITORY_URL = "https://github.com/amphisbana/java-source-atlas";

    private final Project project;
    private final AtlasIndexService index;
    private final AtlasLearningProgressState learningProgress;
    private final SearchTextField searchField = new SearchTextField(false);
    private final JComboBox<String> topicTypeFilter = new JComboBox<>(
            new String[]{"全部类型", "JDK", "Spring Framework", "Spring Boot"}
    );
    private final DefaultListModel<AtlasTopic> topicModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasEntryPoint> entryPointModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasBreakpoint> breakpointModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasTopicRelation> relatedTopicModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasTopic> recentTopicModel = new DefaultListModel<>();
    private final JBList<AtlasTopic> topicList = new JBList<>(topicModel);
    private final JBList<AtlasEntryPoint> entryPointList = new JBList<>(entryPointModel);
    private final JBList<AtlasBreakpoint> breakpointList = new JBList<>(breakpointModel);
    private final JBList<AtlasTopicRelation> relatedTopicList = new JBList<>(relatedTopicModel);
    private final JBList<AtlasTopic> recentTopicList = new JBList<>(recentTopicModel);
    private final JBLabel contextLabel = new JBLabel("当前光标：等待 Java 编辑器");
    private final JBLabel topicTitleLabel = new JBLabel("选择一个源码专题");
    private final JBLabel jdkVersionLabel = new JBLabel();
    private final JBLabel springVersionLabel = new JBLabel();
    private final JBLabel springBootVersionLabel = new JBLabel();
    private final JBLabel compatibilityLabel = new JBLabel();
    private final JBLabel actionHintLabel = new JBLabel("选择专题后，从源码入口开始阅读");
    private final JBLabel tutorialLocationLabel = new JBLabel("选择源码入口后在 IDEA 内阅读教程");
    private final JBLabel learningStatusLabel = new JBLabel("进度：选择一个源码专题");
    private final JBLabel learningGoalLabel = new JBLabel("完成标准：等待选择专题");
    private final JBLabel evidenceLabel = new JBLabel("可执行证据：0 条");
    private final JBLabel nextTopicLabel = new JBLabel("下一步：等待选择专题");
    private final JBLabel nextReasonLabel = new JBLabel();
    private final JBLabel environmentOverviewLabel = new JBLabel("环境状态：等待检测");
    private final JBLabel documentationEnvironmentLabel = new JBLabel("等待检测");
    private final JBLabel jdkEnvironmentLabel = new JBLabel("等待检测");
    private final JBLabel projectEnvironmentLabel = new JBLabel("等待检测");
    private final JBLabel sourceEnvironmentLabel = new JBLabel("等待选择专题");
    private final JBLabel labEnvironmentLabel = new JBLabel("等待选择专题");
    private final JBLabel evidenceEnvironmentLabel = new JBLabel("等待选择推荐断点");
    private final JBLabel browserEnvironmentLabel = new JBLabel("等待检测");
    private final JBCheckBox readMainCheckBox = new JBCheckBox("我已完成主线阅读");
    private final JBCheckBox ranLabCheckBox = new JBCheckBox("我已运行并理解 Lab");
    private final JBCheckBox favoritesOnlyCheckBox = new JBCheckBox("只看收藏");
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JBTabbedPane navigationTabs = new JBTabbedPane();
    private final JBTabbedPane learningTabs = new JBTabbedPane();
    private final JButton openDocumentationButton = new JButton("IDE 内阅读", AtlasIcons.DOCUMENTATION);
    private final JButton openExternalDocumentationButton = new JButton("浏览器打开");
    private final JButton openVersionComparisonButton = new JButton("版本对比");
    private final JButton copyCallChainButton = new JButton("复制调用链");
    private final JButton navigateSourceButton = new JButton("定位源码", AtlasIcons.SOURCE);
    private final JButton addAllBreakpointsButton = new JButton("添加全部断点");
    private final JButton openLabButton = new JButton("打开 Lab");
    private final JButton debugLabButton = new JButton("Debug Lab");
    private final JButton debugEvidenceButton = new JButton("添加断点并 Debug");
    private final JButton viewBreakpointExplanationButton = new JButton("查看断点讲解");
    private final JButton favoriteTopicButton = new JButton("收藏当前专题");
    private final JButton clearRecentButton = new JButton("清空最近阅读");
    private final JButton backToNavigationButton = new JButton("返回专题导航");
    private final JButton tutorialExternalDocumentationButton = new JButton("浏览器打开");
    private final JButton enterNextTopicButton = new JButton("进入下一专题");
    private final JButton refreshEnvironmentButton = new JButton("重新检测");
    private final JButton openEnvironmentGuideButton = new JButton("使用指南");
    private final JButton openProjectRepositoryButton = new JButton("项目仓库");
    private final JButton openAtlasSettingsButton = new JButton("教程设置");
    private final JButton fixDocumentationButton = new JButton("教程设置");
    private final JButton fixJdkButton = new JButton("配置 JDK");
    private final JButton fixProjectButton = new JButton("打开仓库");
    private final JButton fixSourceButton = new JButton("配置源码");
    private final JButton fixLabButton = new JButton("刷新 Maven");
    private final JButton fixEvidenceButton = new JButton("选择断点");
    private final JButton fixBrowserButton = new JButton("浏览器打开");
    private final Timer contextTimer;

    private AtlasEmbeddedBrowser tutorialBrowser;
    private String lastContextKey = "";
    private AtlasEditorContext editorContext = new AtlasEditorContext(null, null, null, null);
    private boolean contextRefreshPending;
    private boolean updatingProgressControls;
    private boolean evidenceDebugPending;
    private boolean documentationCheckPending;
    private int environmentCheckGeneration;
    private int documentationCheckGeneration;
    private String checkedDocumentationUrl = "";
    private AtlasEnvironmentChecker.DocumentationStatus documentationStatus;
    private EnvironmentCheckState documentationEnvironmentState = EnvironmentCheckState.PENDING;
    private EnvironmentCheckState jdkEnvironmentState = EnvironmentCheckState.PENDING;
    private EnvironmentCheckState projectEnvironmentState = EnvironmentCheckState.PENDING;
    private EnvironmentCheckState sourceEnvironmentState = EnvironmentCheckState.NOT_APPLICABLE;
    private EnvironmentCheckState labEnvironmentState = EnvironmentCheckState.NOT_APPLICABLE;
    private EnvironmentCheckState evidenceEnvironmentState = EnvironmentCheckState.NOT_APPLICABLE;
    private EnvironmentCheckState browserEnvironmentState = EnvironmentCheckState.PENDING;

    /**
     * 构建工具窗口并启动轻量上下文刷新计时器。
     *
     * @param project 当前项目
     */
    public AtlasToolWindowPanel(Project project) {
        super(true, true);
        this.project = project;
        this.index = ApplicationManager.getApplication().getService(AtlasIndexService.class);
        this.learningProgress = ApplicationManager.getApplication().getService(AtlasLearningProgressState.class);

        configureLists();
        configureActions();
        setContent(createMainContent());
        rebuildTopicList("");
        refreshFromEditor(true);
        showEnvironmentGuideOnFirstUse();

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
        relatedTopicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        topicList.setCellRenderer(new TopicRenderer());
        entryPointList.setCellRenderer(new EntryPointRenderer());
        breakpointList.setCellRenderer(new BreakpointRenderer());
        relatedTopicList.setCellRenderer(new RelatedTopicRenderer());
        recentTopicList.setCellRenderer(new RecentTopicRenderer());

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
        relatedTopicList.addMouseListener(new MouseAdapter() {
            /**
             * 双击关联专题时直接切换当前学习专题。
             *
             * @param event 鼠标事件
             */
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    AtlasTopicRelation relation = relatedTopicList.getSelectedValue();
                    if (relation != null) {
                        navigateToTopic(relation.topic());
                    }
                }
            }
        });
        recentTopicList.addMouseListener(new MouseAdapter() {
            /**
             * 双击最近阅读记录时恢复对应专题，减少重新搜索的成本。
             *
             * @param event 鼠标事件
             */
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    AtlasTopic topic = recentTopicList.getSelectedValue();
                    if (topic != null) {
                        navigateToTopic(topic);
                    }
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
        topicTypeFilter.addActionListener(ignored -> rebuildTopicList(searchField.getText()));
        favoritesOnlyCheckBox.addActionListener(ignored -> rebuildTopicList(searchField.getText()));
        openDocumentationButton.addActionListener(ignored -> openDocumentationInIde());
        openExternalDocumentationButton.addActionListener(ignored -> openDocumentationExternally());
        openVersionComparisonButton.addActionListener(ignored -> openVersionComparison());
        copyCallChainButton.addActionListener(ignored -> copyCallChain());
        navigateSourceButton.addActionListener(ignored -> navigateToSource());
        addAllBreakpointsButton.addActionListener(ignored -> addAllBreakpoints());
        openLabButton.addActionListener(ignored -> openLab());
        debugLabButton.addActionListener(ignored -> debugLab());
        // 2026-08-21：原操作只启动 JUnit，保留 debugCurrentEvidence 方法作为单独启动实现，新入口先添加断点。
        // debugEvidenceButton.addActionListener(ignored -> debugCurrentEvidence());
        debugEvidenceButton.addActionListener(ignored -> addBreakpointAndDebugCurrentEvidence());
        viewBreakpointExplanationButton.addActionListener(ignored -> viewBreakpointExplanation());
        favoriteTopicButton.addActionListener(ignored -> toggleFavoriteTopic());
        clearRecentButton.addActionListener(ignored -> clearRecentTopics());
        backToNavigationButton.addActionListener(ignored -> tabs.setSelectedIndex(0));
        tutorialExternalDocumentationButton.addActionListener(ignored -> openDocumentationExternally());
        readMainCheckBox.addActionListener(ignored -> saveLearningProgress());
        ranLabCheckBox.addActionListener(ignored -> saveLearningProgress());
        enterNextTopicButton.addActionListener(ignored -> enterRecommendedNextTopic());
        refreshEnvironmentButton.addActionListener(ignored -> refreshEnvironmentChecks(true));
        openEnvironmentGuideButton.addActionListener(ignored -> BrowserUtil.browse(
                AtlasSettingsState.getInstance().documentationUrl("/guide/idea-plugin-quick-start")
        ));
        openProjectRepositoryButton.addActionListener(ignored -> BrowserUtil.browse(PROJECT_REPOSITORY_URL));
        // 2026-08-24：原逻辑只允许从环境页底部进入教程设置，现在同一动作也提供给失败检查项。
        // openAtlasSettingsButton.addActionListener(ignored -> {
        //     ShowSettingsUtil.getInstance().showSettingsDialog(project, AtlasConfigurable.class);
        //     refreshEnvironmentChecks(true);
        // });
        openAtlasSettingsButton.addActionListener(ignored -> openAtlasSettings());
        fixDocumentationButton.addActionListener(ignored -> openAtlasSettings());
        fixJdkButton.addActionListener(ignored -> chooseProjectSdk());
        fixProjectButton.addActionListener(ignored -> BrowserUtil.browse(PROJECT_REPOSITORY_URL));
        fixSourceButton.addActionListener(ignored -> repairSourceEnvironment());
        fixLabButton.addActionListener(ignored -> refreshMavenProjects());
        fixEvidenceButton.addActionListener(ignored -> repairEvidenceEnvironment());
        fixBrowserButton.addActionListener(ignored -> openDocumentationExternally());

        // 2026-08-19：把“IDE 内阅读”设为当前专题的主操作，降低首次使用时的选择成本。
        openDocumentationButton.putClientProperty("JButton.buttonType", "default");
        openDocumentationButton.setToolTipText("在 IDEA 内打开当前源码入口对应的教程");
        openExternalDocumentationButton.setToolTipText("在系统浏览器中打开当前教程");
        openVersionComparisonButton.setToolTipText("打开当前专题的 JDK 8 / 17 / 21 版本差异");
        copyCallChainButton.setToolTipText("复制当前专题按阅读顺序整理的源码调用链");
        navigateSourceButton.setToolTipText("跳转到项目或依赖中的源码类和方法");
        addAllBreakpointsButton.setToolTipText("添加当前专题的全部推荐断点");
        openLabButton.setToolTipText("打开当前专题的 Lab 主类");
        debugLabButton.setToolTipText("创建临时配置并 Debug 当前专题 Lab");
        debugEvidenceButton.setToolTipText("自动添加当前推荐断点，再 Debug 对应的单个 JUnit 测试方法");
        viewBreakpointExplanationButton.setToolTipText("打开与当前断点最匹配的源码入口讲解");
        favoriteTopicButton.setToolTipText("把当前专题加入或移出本地收藏");
        clearRecentButton.setToolTipText("清空本地最近阅读记录，不影响学习进度");
        enterNextTopicButton.setToolTipText("切换到索引推荐的下一专题");
        refreshEnvironmentButton.setToolTipText("重新检查教程站、项目结构、源码、Lab 和 JUnit 场景");
        openEnvironmentGuideButton.setToolTipText("打开 IDEA 插件首次使用与问题修复指南");
        openProjectRepositoryButton.setToolTipText("打开 Java Source Atlas GitHub 仓库");
        openAtlasSettingsButton.setToolTipText("修改教程站点地址");
        fixDocumentationButton.setToolTipText("打开 Java Source Atlas 教程站点设置");
        fixJdkButton.setToolTipText("打开项目结构并配置当前项目使用的 JDK");
        fixProjectButton.setToolTipText("打开完整 Java Source Atlas 项目仓库");
        fixSourceButton.setToolTipText("配置 JDK 源码，或下载 Maven 依赖源码");
        fixLabButton.setToolTipText("重新导入全部 Maven 项目和 Lab 模块");
        fixEvidenceButton.setToolTipText("选择可调试断点，或刷新缺失的测试模块");
        fixBrowserButton.setToolTipText("JCEF 不可用时在系统浏览器中打开当前教程");
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
        navigationTabs.addTab("专题", createTopicSection());
        navigationTabs.addTab("源码入口", createListSection(
                "关键源码入口",
                entryPointList,
                navigateSourceButton,
                copyCallChainButton
        ));
        navigationTabs.addTab("推荐断点", createListSection(
                "推荐断点",
                breakpointList,
                addAllBreakpointsButton,
                openLabButton,
                debugLabButton,
                debugEvidenceButton,
                viewBreakpointExplanationButton
        ));
        navigationTabs.addTab("学习路径", createLearningSection());
        navigationTabs.addTab("环境检查", createEnvironmentSection());
        navigationTabs.setBorder(JBUI.Borders.emptyTop(2));
        // 2026-08-21：原逻辑仅刷新页签按钮状态；环境页需要在用户进入时重新检查当前专题的依赖条件。
        navigationTabs.addChangeListener(ignored -> {
            updateActionState();
            if (navigationTabs.getSelectedIndex() == ENVIRONMENT_TAB_INDEX) {
                refreshEnvironmentChecks(false);
            }
        });
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

        // 2026-08-19：搜索框从公共头部移动到“专题”页，避免用户在源码入口页误以为搜索会过滤方法。
        // header.add(searchField, BorderLayout.NORTH);

        JPanel summary = new JPanel();
        summary.setLayout(new javax.swing.BoxLayout(summary, javax.swing.BoxLayout.Y_AXIS));
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIUtil.getBoundsColor()),
                JBUI.Borders.empty(10)
        ));
        topicTitleLabel.setFont(topicTitleLabel.getFont().deriveFont(Font.BOLD, 14f));
        topicTitleLabel.setToolTipText("当前选中的源码专题");
        contextLabel.setForeground(UIUtil.getLabelInfoForeground());
        jdkVersionLabel.setForeground(UIUtil.getLabelInfoForeground());
        springVersionLabel.setForeground(UIUtil.getLabelInfoForeground());
        springBootVersionLabel.setForeground(UIUtil.getLabelInfoForeground());
        compatibilityLabel.setForeground(UIUtil.getLabelInfoForeground());
        actionHintLabel.setForeground(UIUtil.getLabelInfoForeground());
        jdkVersionLabel.setFont(jdkVersionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        springVersionLabel.setFont(springVersionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        springBootVersionLabel.setFont(springBootVersionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        compatibilityLabel.setFont(compatibilityLabel.getFont().deriveFont(Font.PLAIN, 12f));
        summary.add(topicTitleLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));
        summary.add(contextLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(jdkVersionLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(springVersionLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(springBootVersionLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(compatibilityLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));
        summary.add(actionHintLabel);
        header.add(summary, BorderLayout.CENTER);
        return header;
    }

    /**
     * 创建专题页的专属布局，把操作、搜索和类型筛选放在同一条工作流中。
     *
     * @return 专题页组件
     */
    private JComponent createTopicSection() {
        JPanel section = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        JPanel sectionHeader = new JPanel();
        sectionHeader.setLayout(new javax.swing.BoxLayout(sectionHeader, javax.swing.BoxLayout.Y_AXIS));

        // 2026-08-19：专题页保留教程操作按钮，并把搜索与筛选放在按钮正下方，符合“先选专题、再查专题”的阅读顺序。
        JComponent actionBar = createTabActionBar(
                openDocumentationButton,
                openExternalDocumentationButton,
                openVersionComparisonButton
        );
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(actionBar);
        sectionHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)));

        searchField.getTextEditor().getEmptyText().setText("搜索类名、方法或专题");
        searchField.setToolTipText("支持专题标题、源码类名、方法名和版本搜索");
        topicTypeFilter.setToolTipText("按专题类型筛选 JDK、Spring Framework 或 Spring Boot");
        int searchHeight = Math.max(searchField.getPreferredSize().height, JBUI.scale(26));
        topicTypeFilter.setPreferredSize(new Dimension(JBUI.scale(116), searchHeight));
        JPanel typeFilterPanel = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        typeFilterPanel.add(new JBLabel("类型"), BorderLayout.WEST);
        typeFilterPanel.add(topicTypeFilter, BorderLayout.CENTER);
        JPanel searchBar = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        searchBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchBar.add(searchField, BorderLayout.CENTER);
        searchBar.add(typeFilterPanel, BorderLayout.EAST);
        sectionHeader.add(searchBar);
        sectionHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(7)));

        favoritesOnlyCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        favoritesOnlyCheckBox.setToolTipText("只显示已经收藏的专题");
        sectionHeader.add(favoritesOnlyCheckBox);
        sectionHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(7)));

        JLabel label = new JBLabel("全部专题");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(UIUtil.getLabelInfoForeground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(label);
        section.add(sectionHeader, BorderLayout.NORTH);
        topicList.getEmptyText().setText(emptyTextFor("全部专题"));
        section.add(new JBScrollPane(topicList), BorderLayout.CENTER);
        return section;
    }

    /**
     * 创建学习进度、完成标准、下一站与关联专题组成的学习路径页。
     *
     * @return 学习路径页组件
     */
    private JComponent createLearningSection() {
        JPanel section = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JPanel summary = new JPanel();
        summary.setLayout(new javax.swing.BoxLayout(summary, javax.swing.BoxLayout.Y_AXIS));

        learningStatusLabel.setFont(learningStatusLabel.getFont().deriveFont(Font.BOLD, 13f));
        learningGoalLabel.setForeground(UIUtil.getLabelInfoForeground());
        evidenceLabel.setForeground(UIUtil.getLabelInfoForeground());
        nextTopicLabel.setFont(nextTopicLabel.getFont().deriveFont(Font.BOLD, 13f));
        nextReasonLabel.setForeground(UIUtil.getLabelInfoForeground());
        summary.add(learningStatusLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(7)));
        summary.add(readMainCheckBox);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
        summary.add(ranLabCheckBox);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
        summary.add(learningGoalLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
        summary.add(evidenceLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(9)));
        summary.add(nextTopicLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
        summary.add(nextReasonLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(7)));

        JComponent actionBar = createTabActionBar(favoriteTopicButton, enterNextTopicButton, clearRecentButton);
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(actionBar);
        summary.setBorder(JBUI.Borders.emptyBottom(6));
        section.add(summary, BorderLayout.NORTH);

        JPanel relations = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JBLabel relationsLabel = new JBLabel("关联专题（双击切换）");
        relationsLabel.setFont(relationsLabel.getFont().deriveFont(Font.BOLD));
        relations.add(relationsLabel, BorderLayout.NORTH);
        relatedTopicList.getEmptyText().setText("当前专题暂无关联专题");
        relations.add(new JBScrollPane(relatedTopicList), BorderLayout.CENTER);

        JPanel recent = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JBLabel recentLabel = new JBLabel("最近阅读（双击恢复）");
        recentLabel.setFont(recentLabel.getFont().deriveFont(Font.BOLD));
        recent.add(recentLabel, BorderLayout.NORTH);
        recentTopicList.getEmptyText().setText("还没有最近阅读记录");
        recent.add(new JBScrollPane(recentTopicList), BorderLayout.CENTER);

        learningTabs.removeAll();
        learningTabs.addTab("关联专题", relations);
        learningTabs.addTab("最近阅读", recent);
        section.add(learningTabs, BorderLayout.CENTER);
        return section;
    }

    /**
     * 创建首次使用环境检查页，把阅读和调试所需条件集中展示为可恢复的检查项。
     *
     * @return 环境检查页组件
     */
    private JComponent createEnvironmentSection() {
        JPanel content = new JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setBorder(JBUI.Borders.empty(2, 2, 8, 2));

        environmentOverviewLabel.setFont(environmentOverviewLabel.getFont().deriveFont(Font.BOLD, 14f));
        environmentOverviewLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(environmentOverviewLabel);
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));

        JBLabel readingWorkflowLabel = new JBLabel("阅读：教程站 → 项目 JDK → 源码入口");
        readingWorkflowLabel.setForeground(UIUtil.getLabelInfoForeground());
        readingWorkflowLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(readingWorkflowLabel);
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        JBLabel debugWorkflowLabel = new JBLabel("调试：Maven 仓库 → Lab → JUnit 场景");
        debugWorkflowLabel.setForeground(UIUtil.getLabelInfoForeground());
        debugWorkflowLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(debugWorkflowLabel);
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)));

        // 2026-08-24：原环境行只展示文字状态，用户仍需自行寻找修复入口。
        // content.add(createEnvironmentRow("教程站点", documentationEnvironmentLabel));
        // content.add(createEnvironmentRow("项目 JDK", jdkEnvironmentLabel));
        // content.add(createEnvironmentRow("Maven 与仓库", projectEnvironmentLabel));
        // content.add(createEnvironmentRow("当前源码入口", sourceEnvironmentLabel));
        // content.add(createEnvironmentRow("当前 Lab 模块", labEnvironmentLabel));
        // content.add(createEnvironmentRow("当前 JUnit 场景", evidenceEnvironmentLabel));
        // content.add(createEnvironmentRow("IDE 内教程", browserEnvironmentLabel));
        content.add(createEnvironmentRow("教程站点", documentationEnvironmentLabel, fixDocumentationButton));
        content.add(createEnvironmentRow("项目 JDK", jdkEnvironmentLabel, fixJdkButton));
        content.add(createEnvironmentRow("Maven 与仓库", projectEnvironmentLabel, fixProjectButton));
        content.add(createEnvironmentRow("当前源码入口", sourceEnvironmentLabel, fixSourceButton));
        content.add(createEnvironmentRow("当前 Lab 模块", labEnvironmentLabel, fixLabButton));
        content.add(createEnvironmentRow("当前 JUnit 场景", evidenceEnvironmentLabel, fixEvidenceButton));
        content.add(createEnvironmentRow("IDE 内教程", browserEnvironmentLabel, fixBrowserButton));
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));

        JPanel actionBar = new JPanel(new GridLayout(2, 2, JBUI.scale(6), JBUI.scale(6)));
        actionBar.add(refreshEnvironmentButton);
        actionBar.add(openEnvironmentGuideButton);
        actionBar.add(openProjectRepositoryButton);
        actionBar.add(openAtlasSettingsButton);
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(62)));
        content.add(actionBar);

        JBScrollPane scrollPane = new JBScrollPane(content);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scrollPane;
    }

    /**
     * 创建一条带标题和诊断详情的环境状态行。
     *
     * @param title       检查项标题
     * @param statusLabel 状态详情标签
     * @param repairButton 当前检查项的修复按钮
     * @return 环境状态行
     */
    private JComponent createEnvironmentRow(String title, JBLabel statusLabel, JButton repairButton) {
        JPanel row = new JPanel();
        // 2026-08-24：原逻辑使用单列 BoxLayout，只能放标题和诊断文字。
        // row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.Y_AXIS));
        row.setLayout(new BorderLayout(JBUI.scale(8), 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtil.getBoundsColor()),
                JBUI.Borders.empty(7, 2)
        ));
        JPanel description = new JPanel();
        description.setLayout(new javax.swing.BoxLayout(description, javax.swing.BoxLayout.Y_AXIS));
        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setForeground(UIUtil.getLabelInfoForeground());
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        description.add(titleLabel);
        description.add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
        description.add(statusLabel);
        row.add(description, BorderLayout.CENTER);
        repairButton.setVisible(false);
        row.add(repairButton, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(54)));
        return row;
    }

    /**
     * 首次打开插件时自动进入环境检查页；展示一次后恢复用户主动选择页签的行为。
     */
    private void showEnvironmentGuideOnFirstUse() {
        AtlasSettingsState settings = AtlasSettingsState.getInstance();
        if (!settings.shouldShowEnvironmentGuide()) {
            return;
        }
        // 2026-08-21：原逻辑首次打开直接停在专题列表；现在先展示可操作的环境准备状态。
        settings.markEnvironmentGuideSeen();
        navigationTabs.setSelectedIndex(ENVIRONMENT_TAB_INDEX);
    }

    /**
     * 检查当前专题所需的教程、项目结构、源码、Lab 与 JUnit 场景。
     *
     * @param forceDocumentation 是否忽略教程站点缓存并重新发起网络探测
     */
    private void refreshEnvironmentChecks(boolean forceDocumentation) {
        int generation = ++environmentCheckGeneration;
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);

        checkDocumentationEnvironment(forceDocumentation);
        checkJdkEnvironment();
        checkProjectEnvironment();
        browserEnvironmentState = tutorialBrowser == null
                ? EnvironmentCheckState.NOT_APPLICABLE
                : EnvironmentCheckState.READY;
        applyEnvironmentStatus(
                browserEnvironmentLabel,
                browserEnvironmentState,
                tutorialBrowser == null
                        ? "当前 IDEA 未提供 JCEF，教程会使用系统浏览器打开"
                        : "JCEF 可用，可以在 IDEA 内阅读教程"
        );

        if (topic == null || entryPoint == null) {
            sourceEnvironmentState = EnvironmentCheckState.NOT_APPLICABLE;
            applyEnvironmentStatus(sourceEnvironmentLabel, sourceEnvironmentState, "请先选择专题和源码入口");
        } else {
            sourceEnvironmentState = EnvironmentCheckState.CHECKING;
            applyEnvironmentStatus(sourceEnvironmentLabel, sourceEnvironmentState, "正在检查 " + entryPoint.method());
            AtlasSourceNavigator.checkAvailability(project, this, topic, entryPoint, available -> {
                if (generation != environmentCheckGeneration) {
                    return;
                }
                sourceEnvironmentState = available
                        ? EnvironmentCheckState.READY
                        : EnvironmentCheckState.ACTION_REQUIRED;
                applyEnvironmentStatus(
                        sourceEnvironmentLabel,
                        sourceEnvironmentState,
                        available
                                ? "已找到 " + entryPoint.method()
                                : "未找到目标类或方法，请附加对应 JDK/Spring 源码"
                );
                updateEnvironmentOverview();
            });
        }

        if (topic == null || topic.lab() == null) {
            labEnvironmentState = EnvironmentCheckState.NOT_APPLICABLE;
            applyEnvironmentStatus(labEnvironmentLabel, labEnvironmentState, "当前专题没有 Lab 配置");
        } else {
            labEnvironmentState = EnvironmentCheckState.CHECKING;
            applyEnvironmentStatus(labEnvironmentLabel, labEnvironmentState, "正在检查 " + topic.lab().module());
            AtlasLabLauncher.checkAvailability(project, this, topic, available -> {
                if (generation != environmentCheckGeneration) {
                    return;
                }
                labEnvironmentState = available
                        ? EnvironmentCheckState.READY
                        : EnvironmentCheckState.ACTION_REQUIRED;
                applyEnvironmentStatus(
                        labEnvironmentLabel,
                        labEnvironmentState,
                        available
                                ? "已导入 " + topic.lab().module()
                                : "未导入 " + topic.lab().module() + "，请打开完整仓库并刷新 Maven"
                );
                updateEnvironmentOverview();
            });
        }

        if (evidence == null) {
            evidenceEnvironmentState = EnvironmentCheckState.NOT_APPLICABLE;
            applyEnvironmentStatus(
                    evidenceEnvironmentLabel,
                    evidenceEnvironmentState,
                    breakpoint == null
                            ? "请在推荐断点页选择带“可调试场景”标记的断点"
                            : "当前断点没有绑定独立 JUnit 场景"
            );
        } else {
            evidenceEnvironmentState = EnvironmentCheckState.CHECKING;
            applyEnvironmentStatus(
                    evidenceEnvironmentLabel,
                    evidenceEnvironmentState,
                    "正在检查 " + evidence.testClass() + "#" + evidence.testMethod()
            );
            AtlasLabLauncher.checkEvidenceAvailability(project, this, evidence, available -> {
                if (generation != environmentCheckGeneration) {
                    return;
                }
                evidenceEnvironmentState = available
                        ? EnvironmentCheckState.READY
                        : EnvironmentCheckState.ACTION_REQUIRED;
                applyEnvironmentStatus(
                        evidenceEnvironmentLabel,
                        evidenceEnvironmentState,
                        available
                                ? "已找到 " + evidence.testClass() + "#" + evidence.testMethod()
                                : "未找到测试方法，请导入对应 labs 模块并刷新 Maven"
                );
                updateEnvironmentOverview();
            });
        }
        updateEnvironmentOverview();
    }

    /**
     * 读取当前项目 SDK，判断源码阅读和 Lab 编译是否具备 Java 环境。
     */
    private void checkJdkEnvironment() {
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        String version = sdk == null ? null : sdk.getVersionString();
        jdkEnvironmentState = version == null || version.isBlank()
                ? EnvironmentCheckState.ACTION_REQUIRED
                : EnvironmentCheckState.READY;
        applyEnvironmentStatus(
                jdkEnvironmentLabel,
                jdkEnvironmentState,
                jdkEnvironmentState == EnvironmentCheckState.READY
                        ? version
                        : "当前项目未配置 Project SDK"
        );
    }

    /**
     * 检查当前 IDEA 项目是否为包含根 pom、source-index 和 labs 的完整 Atlas 仓库。
     */
    private void checkProjectEnvironment() {
        AtlasEnvironmentChecker.ProjectFiles files =
                AtlasEnvironmentChecker.inspectProjectFiles(project.getBasePath());
        projectEnvironmentState = files.atlasRepository()
                ? EnvironmentCheckState.READY
                : EnvironmentCheckState.ACTION_REQUIRED;
        String detail;
        if (files.atlasRepository()) {
            detail = "已找到根 pom.xml、source-index 和 labs";
        } else if (files.rootPom()) {
            detail = "已找到 Maven 根工程，但不是完整 Atlas 仓库；Lab 调试需要打开项目仓库";
        } else {
            detail = "当前项目没有 Atlas 根 pom.xml；Lab 调试需要打开完整项目仓库";
        }
        applyEnvironmentStatus(projectEnvironmentLabel, projectEnvironmentState, detail);
    }

    /**
     * 使用缓存或后台网络请求检查当前配置的教程站点。
     *
     * @param force 是否强制重新访问站点
     */
    private void checkDocumentationEnvironment(boolean force) {
        String docsUrl = StringUtil.notNullize(AtlasSettingsState.getInstance().docsBaseUrl).trim();
        if (!force && documentationStatus != null && docsUrl.equals(checkedDocumentationUrl)) {
            documentationEnvironmentState = documentationStatus.available()
                    ? EnvironmentCheckState.READY
                    : EnvironmentCheckState.ACTION_REQUIRED;
            applyEnvironmentStatus(documentationEnvironmentLabel, documentationEnvironmentState, documentationStatus.detail());
            return;
        }
        if (!force && documentationCheckPending && docsUrl.equals(checkedDocumentationUrl)) {
            return;
        }

        int generation = ++documentationCheckGeneration;
        checkedDocumentationUrl = docsUrl;
        documentationCheckPending = true;
        documentationEnvironmentState = EnvironmentCheckState.CHECKING;
        applyEnvironmentStatus(documentationEnvironmentLabel, documentationEnvironmentState, "正在访问 " + docsUrl);
        AtlasEnvironmentChecker.checkDocumentationAsync(this, docsUrl, status -> {
            if (generation != documentationCheckGeneration) {
                return;
            }
            documentationCheckPending = false;
            documentationStatus = status;
            documentationEnvironmentState = status.available()
                    ? EnvironmentCheckState.READY
                    : EnvironmentCheckState.ACTION_REQUIRED;
            applyEnvironmentStatus(documentationEnvironmentLabel, documentationEnvironmentState, status.detail());
            updateEnvironmentOverview();
        });
    }

    /**
     * 根据环境状态生成统一的符号、短文本和完整悬浮说明。
     *
     * @param label  状态标签
     * @param state  检查状态
     * @param detail 完整诊断说明
     */
    private void applyEnvironmentStatus(JBLabel label, EnvironmentCheckState state, String detail) {
        String marker = switch (state) {
            case READY -> "✓";
            case ACTION_REQUIRED -> "!";
            case CHECKING -> "…";
            case PENDING, NOT_APPLICABLE -> "·";
        };
        label.setText(marker + " "
                + StringUtil.shortenTextWithEllipsis(StringUtil.notNullize(detail), 34, 0));
        label.setToolTipText(detail);
    }

    /**
     * 汇总阅读与调试条件，并把结论同步到环境页签标题。
     */
    private void updateEnvironmentOverview() {
        List<EnvironmentCheckState> states = List.of(
                documentationEnvironmentState,
                jdkEnvironmentState,
                projectEnvironmentState,
                sourceEnvironmentState,
                labEnvironmentState,
                evidenceEnvironmentState
        );
        boolean checking = states.stream().anyMatch(state -> state == EnvironmentCheckState.CHECKING);
        long required = states.stream().filter(state -> state == EnvironmentCheckState.ACTION_REQUIRED).count();
        boolean readingReady = documentationEnvironmentState == EnvironmentCheckState.READY
                && jdkEnvironmentState == EnvironmentCheckState.READY
                && sourceEnvironmentState == EnvironmentCheckState.READY;
        boolean debugReady = projectEnvironmentState == EnvironmentCheckState.READY
                && labEnvironmentState == EnvironmentCheckState.READY
                && evidenceEnvironmentState == EnvironmentCheckState.READY;

        if (checking) {
            environmentOverviewLabel.setText("环境状态：正在检测…");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境检查 …");
        } else if (readingReady && debugReady) {
            environmentOverviewLabel.setText("环境状态：源码阅读与场景调试已就绪");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境检查 ✓");
        } else if (readingReady) {
            environmentOverviewLabel.setText("环境状态：源码阅读可用，场景调试仍需准备");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境检查 !");
        } else if (required > 0) {
            environmentOverviewLabel.setText("环境状态：有 " + required + " 项需要处理");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境检查 !");
        } else {
            environmentOverviewLabel.setText("环境状态：选择专题与可调试断点后继续检查");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境检查");
        }
        updateEnvironmentRepairActions();
    }

    /**
     * 根据每项检查结果只展示当前真正需要的修复动作。
     */
    private void updateEnvironmentRepairActions() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);

        fixDocumentationButton.setVisible(documentationEnvironmentState == EnvironmentCheckState.ACTION_REQUIRED);
        fixJdkButton.setVisible(jdkEnvironmentState == EnvironmentCheckState.ACTION_REQUIRED);
        fixProjectButton.setVisible(projectEnvironmentState == EnvironmentCheckState.ACTION_REQUIRED);
        fixSourceButton.setVisible(sourceEnvironmentState == EnvironmentCheckState.ACTION_REQUIRED);
        fixLabButton.setVisible(labEnvironmentState == EnvironmentCheckState.ACTION_REQUIRED);
        fixBrowserButton.setVisible(browserEnvironmentState != EnvironmentCheckState.READY);
        fixBrowserButton.setEnabled(topic != null && entryPointList.getSelectedValue() != null);

        boolean evidenceNeedsAction = evidenceEnvironmentState == EnvironmentCheckState.ACTION_REQUIRED
                || evidenceEnvironmentState == EnvironmentCheckState.NOT_APPLICABLE;
        fixEvidenceButton.setVisible(evidenceNeedsAction);
        fixEvidenceButton.setText(evidence == null ? "选择断点" : "刷新 Maven");
        fixSourceButton.setText(topic != null && "JDK".equals(topicType(topic)) ? "配置源码" : "下载源码");
        navigationTabs.revalidate();
        navigationTabs.repaint();
    }

    /**
     * 打开插件教程站点设置，并在设置关闭后重新检查最新地址。
     */
    private void openAtlasSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AtlasConfigurable.class);
        documentationStatus = null;
        refreshEnvironmentChecks(true);
    }

    /**
     * 打开 IDEA 项目结构页配置 SDK，并在窗口关闭后延迟刷新环境状态。
     */
    private void chooseProjectSdk() {
        // 2026-08-24：原实现调用 chooseAndSetSdk()，该 API 在 IDEA 2026.2 已标记为未来移除。
        // ProjectSettingsService.getInstance(project).chooseAndSetSdk();
        ProjectSettingsService.getInstance(project).openProjectSettings();
        scheduleEnvironmentRefresh();
    }

    /**
     * JDK 专题打开项目结构配置源码，Spring 专题直接触发 Maven 下载全部依赖源码。
     */
    private void repairSourceEnvironment() {
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic != null && "JDK".equals(topicType(topic))) {
            ProjectSettingsService.getInstance(project).openProjectSettings();
            scheduleEnvironmentRefresh();
            return;
        }
        if (!executeIdeAction("Maven.DownloadAllSources", fixSourceButton)) {
            BrowserUtil.browse(AtlasSettingsState.getInstance().documentationUrl(
                    "/guide/idea-plugin-quick-start#阅读当前项目中的源码"
            ));
            return;
        }
        actionHintLabel.setText("已触发 Maven 源码下载，完成后会重新检查当前源码入口");
        scheduleEnvironmentRefresh();
    }

    /**
     * 触发 IDEA Maven 全量重新导入，让缺失的 Lab 和测试模块进入项目模型。
     */
    private void refreshMavenProjects() {
        if (!executeIdeAction("Maven.Reimport", fixLabButton)) {
            Messages.showInfoMessage(
                    project,
                    "当前 IDEA 没有可用的 Maven 刷新动作，请在 Maven 工具窗口手动点击重新加载。",
                    "Java Source Atlas"
            );
            return;
        }
        actionHintLabel.setText("已触发 Maven 重新导入，完成后会再次检查 Lab 与 JUnit 场景");
        scheduleEnvironmentRefresh();
    }

    /**
     * 没有绑定场景时跳到推荐断点页；测试类缺失时直接刷新 Maven。
     */
    private void repairEvidenceEnvironment() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        if (evidence == null) {
            navigationTabs.setSelectedIndex(2);
            breakpointList.requestFocusInWindow();
            return;
        }
        refreshMavenProjects();
    }

    /**
     * 通过稳定 Action ID 执行 IDEA 内置动作，插件未安装或动作不存在时返回失败。
     *
     * @param actionId IDEA 动作编号
     * @param source   动作上下文组件
     * @return 是否找到并提交动作
     */
    private boolean executeIdeAction(String actionId, JComponent source) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) {
            return false;
        }
        ActionManager.getInstance().tryToExecute(
                action,
                null,
                source,
                ActionPlaces.UNKNOWN,
                true
        );
        return true;
    }

    /**
     * 给 Maven 导入和源码下载留出处理时间，再进行一次非阻塞环境检查。
     */
    private void scheduleEnvironmentRefresh() {
        Timer refreshTimer = new Timer(3_000, ignored -> refreshEnvironmentChecks(false));
        refreshTimer.setRepeats(false);
        refreshTimer.start();
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
            // 2026-08-21：原逻辑始终使用单行 FlowLayout；场景调试按钮增加后，窄工具窗口改用两列网格避免截断。
            JComponent actionBar = actions.length > 3
                    ? createMultiRowActionBar(actions)
                    : createTabActionBar(actions);
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
     * 为四个以上操作创建两列多行布局，保证窄工具窗口中的按钮文案完整可见。
     *
     * @param actions 当前页签可执行的操作
     * @return 两列操作栏
     */
    private JComponent createMultiRowActionBar(JButton... actions) {
        int rows = (actions.length + 1) / 2;
        JPanel actionBar = new JPanel(new GridLayout(rows, 2, JBUI.scale(6), JBUI.scale(6)));
        for (JButton action : actions) {
            actionBar.add(action);
        }
        if (actions.length % 2 != 0) {
            actionBar.add(new JPanel());
        }
        actionBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(rows * 30)));
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
        String selectedType = String.valueOf(topicTypeFilter.getSelectedItem());
        // 2026-08-19：类型字段暂未独立存储在索引中，先从 primaryVersion 派生稳定分类，兼容现有索引格式。
        // 2026-08-20：收藏筛选复用本地持久化编号，不修改共享 source-index 数据结构。
        List<AtlasTopic> matches = index.search(query).stream()
                .filter(topic -> "全部类型".equals(selectedType) || topicType(topic).equals(selectedType))
                .filter(topic -> !favoritesOnlyCheckBox.isSelected() || learningProgress.isFavorite(topic.topicId()))
                .toList();
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
            jdkVersionLabel.setText("");
            springVersionLabel.setText("");
            springBootVersionLabel.setText("");
            compatibilityLabel.setText("调整搜索条件后重试");
            actionHintLabel.setText("输入类名、方法名或专题名称开始搜索");
            refreshLearningSection(null);
            updateNavigationTabTitles();
            updateActionState();
            if (navigationTabs.getSelectedIndex() == ENVIRONMENT_TAB_INDEX) {
                refreshEnvironmentChecks(false);
            }
            return;
        }

        topicTitleLabel.setText(StringUtil.shortenTextWithEllipsis(topic.title(), 58, 0));
        topicTitleLabel.setToolTipText(topic.title());
        topic.entryPoints().forEach(entryPointModel::addElement);
        topic.breakpoints().forEach(breakpointModel::addElement);
        jdkVersionLabel.setText("项目 JDK：检测中…");
        springVersionLabel.setText("Spring：检测中…");
        springBootVersionLabel.setText("Spring Boot：检测中…");
        compatibilityLabel.setText("教程基线：" + topic.primaryVersion());
        actionHintLabel.setText("先选源码入口，再阅读调用链或定位实现");
        refreshLearningSection(topic);
        updateNavigationTabTitles();
        AtlasVersionDetector.detectAsync(project, this, versionInfo -> applyVersionInfo(topic, versionInfo));

        if (preferredEntry != null && topic.entryPoints().contains(preferredEntry)) {
            entryPointList.setSelectedValue(preferredEntry, true);
        } else if (!topic.entryPoints().isEmpty()) {
            entryPointList.setSelectedIndex(0);
        }
        updateActionState();
        if (navigationTabs.getSelectedIndex() == ENVIRONMENT_TAB_INDEX) {
            refreshEnvironmentChecks(false);
        }
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
        String jdkText = "项目 JDK：" + versionInfo.jdkVersion();
        String springText = "Spring：" + versionInfo.springVersion();
        String springBootText = "Spring Boot：" + versionInfo.springBootVersion();
        String compatibilityText = AtlasVersionDetector.compatibilityHint(topic, versionInfo);
        jdkVersionLabel.setText(jdkText);
        springVersionLabel.setText(springText);
        springBootVersionLabel.setText(springBootText);
        jdkVersionLabel.setToolTipText(jdkText);
        springVersionLabel.setToolTipText(springText);
        springBootVersionLabel.setToolTipText(springBootText);
        compatibilityLabel.setText(shortLabel(compatibilityText));
        compatibilityLabel.setToolTipText(compatibilityText);
    }

    /**
     * 根据专题主版本推导筛选类型；判断 Spring Boot 必须早于 Spring Framework。
     *
     * @param topic 当前专题
     * @return 用于下拉筛选的类型名称
     */
    private String topicType(AtlasTopic topic) {
        String primaryVersion = topic == null ? "" : StringUtil.notNullize(topic.primaryVersion());
        if (primaryVersion.startsWith("OpenJDK")) {
            return "JDK";
        }
        if (primaryVersion.startsWith("Spring Boot")) {
            return "Spring Boot";
        }
        return "Spring Framework";
    }

    /**
     * 根据当前选择启用或禁用命令按钮。
     */
    private void updateActionState() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        openDocumentationButton.setEnabled(topic != null && entryPoint != null);
        openExternalDocumentationButton.setEnabled(topic != null && entryPoint != null);
        openVersionComparisonButton.setEnabled(topic != null && topic.versionComparison() != null);
        copyCallChainButton.setEnabled(topic != null && !topic.entryPoints().isEmpty());
        addAllBreakpointsButton.setEnabled(topic != null && !topic.breakpoints().isEmpty());
        viewBreakpointExplanationButton.setEnabled(topic != null && breakpoint != null
                && index.explanationForBreakpoint(topic, breakpoint).isPresent());
        favoriteTopicButton.setEnabled(topic != null);
        favoriteTopicButton.setText(topic != null && learningProgress.isFavorite(topic.topicId())
                ? "取消收藏"
                : "收藏当前专题");
        navigateSourceButton.setEnabled(false);
        openLabButton.setEnabled(false);
        debugLabButton.setEnabled(false);
        debugEvidenceButton.setEnabled(false);
        if (topic != null && navigationTabs.getSelectedIndex() == ENVIRONMENT_TAB_INDEX) {
            actionHintLabel.setText("检查教程、源码和 Lab 状态，按提示补齐调试环境");
        } else if (topic != null && navigationTabs.getSelectedIndex() == 3) {
            actionHintLabel.setText("确认阅读与实验进度，再沿推荐关系进入下一专题");
        } else if (topic != null && navigationTabs.getSelectedIndex() == 2) {
            // 2026-08-21：原逻辑统一提示启动整个 Debug Lab；现在优先提示断点绑定的单个 JUnit 证据场景。
            actionHintLabel.setText(breakpoint == null
                    ? "双击列表项添加单个断点；也可以一次添加全部断点并打开 Lab"
                    : evidence == null
                    ? "当前断点可双击添加；该断点暂未绑定独立测试场景"
                    : "当前断点已绑定行为测试，可自动添加断点并 Debug 精确场景");
        } else if (topic != null && navigationTabs.getSelectedIndex() == 1) {
            actionHintLabel.setText(entryPoint == null
                    ? "选择一个源码入口开始阅读"
                    : "双击源码入口或点击“定位源码”，跳转到项目或依赖中的实现");
        } else if (topic != null) {
            actionHintLabel.setText(entryPoint == null
                    ? "当前专题暂无可阅读的源码入口"
                    : "选择源码入口后，可在 IDEA 内阅读或使用浏览器打开教程");
        }
        navigateSourceButton.setToolTipText(topic == null || entryPoint == null
                ? "选择源码入口后可定位"
                : "正在检查项目类路径…");
        openLabButton.setToolTipText(topic == null ? "选择专题后可打开实验" : "正在检查 Lab 主类…");
        debugLabButton.setToolTipText(topic == null ? "选择专题后可调试实验" : "正在检查 Lab 主类…");
        debugEvidenceButton.setToolTipText(evidence == null
                ? "当前断点没有绑定可执行证据，请选择带“可调试场景”标记的断点"
                : "正在检查测试方法 " + evidence.testClass() + "#" + evidence.testMethod());
        if (topic == null) {
            viewBreakpointExplanationButton.setEnabled(false);
            debugEvidenceButton.setEnabled(false);
            favoriteTopicButton.setEnabled(false);
            clearRecentButton.setEnabled(!learningProgress.recentTopicIds().isEmpty());
            return;
        }
        clearRecentButton.setEnabled(!learningProgress.recentTopicIds().isEmpty());

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
        if (evidence != null) {
            AtlasLabLauncher.checkEvidenceAvailability(project, this, evidence, available -> {
                if (!topic.equals(topicList.getSelectedValue())
                        || !breakpoint.equals(breakpointList.getSelectedValue())) {
                    return;
                }
                debugEvidenceButton.setEnabled(available && !evidenceDebugPending);
                debugEvidenceButton.setToolTipText(available
                        ? evidence.testClass() + "#" + evidence.testMethod()
                        + "；将先添加当前断点；预期：" + evidence.expectedOutcome()
                        : "当前项目未导入测试 " + evidence.testClass() + "#" + evidence.testMethod());
            });
        }
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
     * 根据当前专题刷新四个工作页签的数量与状态，帮助用户判断每个页签是否有内容。
     */
    private void updateNavigationTabTitles() {
        navigationTabs.setTitleAt(0, "专题 " + topicModel.size());
        navigationTabs.setTitleAt(1, "源码入口 " + entryPointModel.size());
        navigationTabs.setTitleAt(2, "推荐断点 " + breakpointModel.size());
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasLearningProgressState.TopicProgress progress = topic == null
                ? new AtlasLearningProgressState.TopicProgress()
                : learningProgress.progressFor(topic.topicId());
        navigationTabs.setTitleAt(3, progress.readMain && progress.ranLab ? "学习路径 ✓" : "学习路径");
        recentTopicModel.clear();
        index.topicsByIds(learningProgress.recentTopicIds()).forEach(recentTopicModel::addElement);
        clearRecentButton.setEnabled(!recentTopicModel.isEmpty());
    }

    /**
     * 根据当前专题刷新学习状态、完成标准、证据和关联专题。
     *
     * @param topic 当前专题
     */
    private void refreshLearningSection(AtlasTopic topic) {
        updatingProgressControls = true;
        relatedTopicModel.clear();
        if (topic == null) {
            learningStatusLabel.setText("进度：选择一个源码专题");
            learningGoalLabel.setText("完成标准：等待选择专题");
            learningGoalLabel.setToolTipText(null);
            evidenceLabel.setText("可执行证据：0 条");
            nextTopicLabel.setText("下一步：等待选择专题");
            nextReasonLabel.setText("");
            nextReasonLabel.setToolTipText(null);
            readMainCheckBox.setSelected(false);
            ranLabCheckBox.setSelected(false);
            readMainCheckBox.setEnabled(false);
            ranLabCheckBox.setEnabled(false);
            enterNextTopicButton.setEnabled(false);
            updatingProgressControls = false;
            return;
        }

        AtlasLearningProgressState.TopicProgress progress = learningProgress.progressFor(topic.topicId());
        readMainCheckBox.setSelected(progress.readMain);
        ranLabCheckBox.setSelected(progress.ranLab);
        readMainCheckBox.setEnabled(true);
        ranLabCheckBox.setEnabled(true);
        int completedActions = (progress.readMain ? 1 : 0) + (progress.ranLab ? 1 : 0);
        learningStatusLabel.setText(completedActions == 2
                ? "进度：已完成本轮学习"
                : completedActions == 1 ? "进度：进行中（1/2）" : "进度：尚未开始");

        String readingGoal = StringUtil.notNullize(
                topic.readingGoal(),
                "能够沿关键入口复述调用链，并用 Lab 验证结论。"
        );
        learningGoalLabel.setText(shortLabel("完成标准：" + readingGoal));
        learningGoalLabel.setToolTipText(readingGoal);
        evidenceLabel.setText("可执行证据：" + topic.evidence().size() + " 条（源码入口 → 讲解 → Lab → JUnit）");

        index.relatedTopics(topic).forEach(relatedTopicModel::addElement);
        index.recommendedNext(topic).ifPresentOrElse(next -> {
            String reason = StringUtil.notNullize(topic.recommendedNextReason(), "沿学习路线继续下一站。");
            nextTopicLabel.setText(shortLabel("下一步：" + next.title()));
            nextTopicLabel.setToolTipText(next.title());
            nextReasonLabel.setText(shortLabel("推荐理由：" + reason));
            nextReasonLabel.setToolTipText(reason);
            enterNextTopicButton.setEnabled(true);
        }, () -> {
            nextTopicLabel.setText("下一步：当前推荐路线已完成");
            nextTopicLabel.setToolTipText(null);
            nextReasonLabel.setText("可从专题页或关联专题选择新的阅读方向");
            nextReasonLabel.setToolTipText(null);
            enterNextTopicButton.setEnabled(false);
        });
        updatingProgressControls = false;
    }

    /**
     * 把学习页复选框状态持久化到 IDEA 本地配置。
     */
    private void saveLearningProgress() {
        if (updatingProgressControls) {
            return;
        }
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic == null) {
            return;
        }
        learningProgress.update(topic.topicId(), readMainCheckBox.isSelected(), ranLabCheckBox.isSelected());
        refreshLearningSection(topic);
        updateNavigationTabTitles();
    }

    /**
     * 进入当前专题明确推荐的下一站。
     */
    private void enterRecommendedNextTopic() {
        AtlasTopic topic = topicList.getSelectedValue();
        index.recommendedNext(topic).ifPresent(this::navigateToTopic);
    }

    /**
     * 清除搜索和类型筛选后切换到目标专题，保证关联专题不会因当前筛选条件而不可见。
     *
     * @param target 目标专题
     */
    private void navigateToTopic(AtlasTopic target) {
        if (target == null) {
            return;
        }
        topicTypeFilter.setSelectedIndex(0);
        searchField.setText("");
        rebuildTopicList("");
        topicList.setSelectedValue(target, true);
        navigationTabs.setSelectedIndex(0);
    }

    /**
     * 在 IDEA 内嵌浏览器中打开选中方法对应的教程锚点。
     */
    private void openDocumentationInIde() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        if (topic == null || entryPoint == null) {
            return;
        }
        learningProgress.recordRecent(topic.topicId());
        updateNavigationTabTitles();
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
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        if (topic != null && entryPoint != null) {
            learningProgress.recordRecent(topic.topicId());
            updateNavigationTabTitles();
            BrowserUtil.browse(AtlasSettingsState.getInstance().documentationUrl(entryPoint.document()));
        }
    }

    /**
     * 复制当前专题按入口顺序生成的调用链，方便粘贴到笔记、Issue 或评审记录。
     */
    private void copyCallChain() {
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic == null) {
            return;
        }
        String chain = io.github.javasourceatlas.idea.learning.AtlasCallChainFormatter.format(
                topic,
                entryPointList.getSelectedValue(),
                path -> AtlasSettingsState.getInstance().documentationUrl(path)
        );
        CopyPasteManager.getInstance().setContents(new StringSelection(chain));
        Messages.showInfoMessage(project, "已复制当前专题的源码阅读调用链。", "Java Source Atlas");
    }

    /**
     * 打开与所选推荐断点最贴近的源码入口讲解，并同步入口列表选择。
     */
    private void viewBreakpointExplanation() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        if (topic == null || breakpoint == null) {
            return;
        }
        index.explanationForBreakpoint(topic, breakpoint).ifPresentOrElse(entryPoint -> {
            entryPointList.setSelectedValue(entryPoint, true);
            openDocumentationInIde();
        }, () -> Messages.showInfoMessage(
                project,
                "当前断点没有对应的源码入口讲解，请先从断点方法所在类开始阅读。",
                "Java Source Atlas"
        ));
    }

    /**
     * 切换当前专题的收藏状态，并立即刷新专题列表和学习路径提示。
     */
    private void toggleFavoriteTopic() {
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic == null) {
            return;
        }
        learningProgress.setFavorite(topic.topicId(), !learningProgress.isFavorite(topic.topicId()));
        rebuildTopicList(searchField.getText());
        refreshLearningSection(topicList.getSelectedValue());
        updateActionState();
    }

    /**
     * 清空最近阅读记录，并保留专题收藏与完成进度。
     */
    private void clearRecentTopics() {
        learningProgress.clearRecent();
        updateNavigationTabTitles();
        updateActionState();
    }

    /**
     * 打开当前专题对应的 JDK 版本对比工作台，并携带稳定专题编号。
     */
    private void openVersionComparison() {
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic == null || topic.versionComparison() == null) {
            return;
        }
        String path = "/jdk/version-comparison/?topic=" + topic.versionComparison().id();
        BrowserUtil.browse(AtlasSettingsState.getInstance().documentationUrl(path));
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
     * 先添加当前推荐断点，确认断点能够解析后再启动绑定的单个 JUnit 证据场景。
     */
    private void addBreakpointAndDebugCurrentEvidence() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        if (topic == null || breakpoint == null || evidence == null) {
            return;
        }

        evidenceDebugPending = true;
        debugEvidenceButton.setEnabled(false);
        debugEvidenceButton.setText("准备场景…");
        AtlasBreakpointManager.addBreakpointsAsync(
                project,
                this,
                topic,
                List.of(breakpoint),
                result -> {
                    if (result.added() + result.existing() == 0) {
                        evidenceDebugPending = false;
                        debugEvidenceButton.setText("添加断点并 Debug");
                        updateActionState();
                        Messages.showInfoMessage(
                                project,
                                "没有找到断点方法 " + breakpoint.method()
                                        + "。请先在环境检查中确认源码已经附加。",
                                "Java Source Atlas"
                        );
                        return;
                    }
                    debugEvidenceButton.setText("启动 Debug…");
                    startEvidenceDebug(evidence);
                }
        );
    }

    /**
     * 为当前推荐断点绑定的单个 JUnit 证据创建临时 Debug 配置。
     */
    private void debugCurrentEvidence() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        if (evidence == null) {
            return;
        }
        startEvidenceDebug(evidence);
    }

    /**
     * 启动已经确认存在的 JUnit 证据场景，并在结束启动流程后恢复操作按钮。
     *
     * @param evidence 要调试的证据场景
     */
    private void startEvidenceDebug(AtlasEvidence evidence) {
        evidenceDebugPending = true;
        debugEvidenceButton.setEnabled(false);
        AtlasLabLauncher.debugEvidenceAsync(project, this, evidence, started -> {
            // 2026-08-21：原逻辑只恢复按钮可用性；串联断点添加后还需要清除场景准备状态和按钮文案。
            evidenceDebugPending = false;
            debugEvidenceButton.setText("添加断点并 Debug");
            updateActionState();
            if (!started) {
                Messages.showInfoMessage(
                        project,
                        "当前项目中未找到证据测试 " + evidence.testClass() + "#" + evidence.testMethod()
                                + "。请用 IDEA 打开完整 java-source-atlas 仓库并导入对应 labs 模块。",
                        "Java Source Atlas"
                );
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
            setFont(getFont().deriveFont(Font.PLAIN, 13f));
            setBorder(JBUI.Borders.empty(5, 4));
            setToolTipText(value.title() + " · " + value.primaryVersion());
        }
    }

    /**
     * 渲染最近阅读专题，复用版本信息帮助用户确认要恢复的阅读上下文。
     */
    private static final class RecentTopicRenderer extends ColoredListCellRenderer<AtlasTopic> {

        /**
         * 为最近阅读列表追加专题标题和版本。
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
            setFont(getFont().deriveFont(Font.PLAIN, 13f));
            setBorder(JBUI.Borders.empty(5, 4));
            setToolTipText("双击恢复：" + value.title());
        }
    }

    /**
     * 渲染关联专题的关系方向、标题和版本，完整推荐理由放在悬浮提示中。
     */
    private static final class RelatedTopicRenderer extends ColoredListCellRenderer<AtlasTopicRelation> {

        /**
         * 为关联专题追加关系标签、标题与版本信息。
         *
         * @param list     当前列表
         * @param value    专题关系
         * @param index    行号
         * @param selected 是否选中
         * @param hasFocus 是否拥有焦点
         */
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends AtlasTopicRelation> list,
                AtlasTopicRelation value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            append(value.label() + "  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            append(StringUtil.shortenTextWithEllipsis(value.topic().title(), 48, 0));
            append("  " + value.topic().primaryVersion(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setFont(getFont().deriveFont(Font.PLAIN, 13f));
            setBorder(JBUI.Borders.empty(6, 4));
            setToolTipText(value.label() + " · " + value.topic().title()
                    + " · " + StringUtil.notNullize(value.reason(), "沿学习关系继续阅读"));
        }
    }

    /**
     * 渲染源码入口的方法名和阅读重点，使用两层信息帮助用户先扫方法再理解目的。
     */
    private static final class EntryPointRenderer extends JPanel implements ListCellRenderer<AtlasEntryPoint> {

        private final JBLabel methodLabel = new JBLabel();
        private final JBLabel purposeLabel = new JBLabel();

        /**
         * 创建两行源码入口渲染器。
         */
        private EntryPointRenderer() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setOpaque(true);
            methodLabel.setFont(methodLabel.getFont().deriveFont(Font.BOLD, 13f));
            purposeLabel.setFont(purposeLabel.getFont().deriveFont(Font.PLAIN, 12f));
            methodLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            purposeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(methodLabel);
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
            add(purposeLabel);
            setBorder(JBUI.Borders.empty(5, 6));
        }

        /**
         * 为入口列表设置方法名、阅读重点和完整提示。
         *
         * @param list     当前列表
         * @param value    方法入口
         * @param index    行号
         * @param selected 是否选中
         * @param hasFocus 是否拥有焦点
         */
        @Override
        public Component getListCellRendererComponent(
                @NotNull JList<? extends AtlasEntryPoint> list,
                AtlasEntryPoint value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            String purpose = StringUtil.notNullize(value.purpose(), "暂无阅读说明");
            methodLabel.setText(value.method());
            purposeLabel.setText("阅读重点：" + StringUtil.shortenTextWithEllipsis(purpose, 128, 0));
            setToolTipText(value.method() + " · 阅读重点：" + purpose);
            applySelectionState(list, selected);
            return this;
        }

        /**
         * 根据列表选中状态同步背景和前景色，保证自定义多行渲染器符合 IDEA 主题。
         *
         * @param list     当前列表
         * @param selected 是否选中
         */
        private void applySelectionState(JList<?> list, boolean selected) {
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            ColorPair colors = ColorPair.from(list, selected);
            methodLabel.setForeground(colors.foreground());
            purposeLabel.setForeground(colors.secondaryForeground());
        }
    }

    /**
     * 渲染推荐断点的方法、观察场景和变量，明确双击操作的可发现性。
     */
    private static final class BreakpointRenderer extends JPanel implements ListCellRenderer<AtlasBreakpoint> {

        private final JBLabel methodLabel = new JBLabel();
        private final JBLabel scenarioLabel = new JBLabel();
        private final JBLabel variablesLabel = new JBLabel();

        /**
         * 创建三行推荐断点渲染器。
         */
        private BreakpointRenderer() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setOpaque(true);
            methodLabel.setFont(methodLabel.getFont().deriveFont(Font.BOLD, 13f));
            scenarioLabel.setFont(scenarioLabel.getFont().deriveFont(Font.PLAIN, 12f));
            variablesLabel.setFont(variablesLabel.getFont().deriveFont(Font.PLAIN, 12f));
            methodLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            scenarioLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            variablesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(methodLabel);
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
            add(scenarioLabel);
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
            add(variablesLabel);
            setBorder(JBUI.Borders.empty(5, 6));
        }

        /**
         * 为断点列表设置方法、观察场景、观察变量和完整提示。
         *
         * @param list     当前列表
         * @param value    推荐断点
         * @param index    行号
         * @param selected 是否选中
         * @param hasFocus 是否拥有焦点
         */
        @Override
        public Component getListCellRendererComponent(
                @NotNull JList<? extends AtlasBreakpoint> list,
                AtlasBreakpoint value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            String scenario = StringUtil.notNullize(value.scenario(), "暂无场景说明");
            String variables = value.variables().isEmpty()
                    ? "暂无额外变量"
                    : String.join("、", value.variables());
            String evidenceHint = value.evidenceId() == null ? "" : " · 可调试场景";
            methodLabel.setText(value.method());
            scenarioLabel.setText("观察场景：" + StringUtil.shortenTextWithEllipsis(scenario, 118, 0));
            variablesLabel.setText("观察变量：" + StringUtil.shortenTextWithEllipsis(variables, 118, 0)
                    + evidenceHint);
            setToolTipText(value.method() + " · 观察场景：" + scenario
                    + " · 观察变量：" + variables + evidenceHint + " · 双击添加当前断点");
            applySelectionState(list, selected);
            return this;
        }

        /**
         * 根据列表选中状态同步背景和前景色，保证推荐断点在深色主题中仍然清晰。
         *
         * @param list     当前列表
         * @param selected 是否选中
         */
        private void applySelectionState(JList<?> list, boolean selected) {
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            ColorPair colors = ColorPair.from(list, selected);
            methodLabel.setForeground(colors.foreground());
            scenarioLabel.setForeground(colors.secondaryForeground());
            variablesLabel.setForeground(colors.secondaryForeground());
        }
    }

    /**
     * 描述环境检查项在等待、检查、可用和需要处理之间的状态。
     */
    private enum EnvironmentCheckState {
        PENDING,
        CHECKING,
        READY,
        ACTION_REQUIRED,
        NOT_APPLICABLE
    }

    /**
     * 封装列表主文字和辅助文字的主题颜色，避免两个多行渲染器重复处理选中态。
     *
     * @param foreground        主文字颜色
     * @param secondaryForeground 辅助文字颜色
     */
    private record ColorPair(java.awt.Color foreground, java.awt.Color secondaryForeground) {

        /**
         * 根据 IDEA 列表主题计算选中态和普通态颜色。
         *
         * @param list     当前列表
         * @param selected 是否选中
         * @return 颜色组合
         */
        private static ColorPair from(JList<?> list, boolean selected) {
            java.awt.Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            java.awt.Color secondary = selected
                    ? foreground
                    : UIUtil.getLabelInfoForeground();
            return new ColorPair(foreground, secondary);
        }
    }
}
