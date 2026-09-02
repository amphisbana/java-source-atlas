package io.github.javasourceatlas.idea.ui;

import com.intellij.icons.AllIcons;
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
import com.intellij.ui.components.ActionLink;
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
import io.github.javasourceatlas.idea.debug.AtlasDebugGuidance;
import io.github.javasourceatlas.idea.debug.AtlasDebugGuidanceService;
import io.github.javasourceatlas.idea.debug.AtlasDebugSessionReport;
import io.github.javasourceatlas.idea.debug.AtlasWatchManager;
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
import io.github.javasourceatlas.idea.version.AtlasTopicVersion;
import io.github.javasourceatlas.idea.version.AtlasTopicVersionResolver;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
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
import java.util.Optional;

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
    private final DefaultListModel<String> observationModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasTopicRelation> relatedTopicModel = new DefaultListModel<>();
    private final DefaultListModel<AtlasTopic> recentTopicModel = new DefaultListModel<>();
    private final JBList<AtlasTopic> topicList = new JBList<>(topicModel);
    private final JBList<AtlasEntryPoint> entryPointList = new JBList<>(entryPointModel);
    private final JBList<AtlasBreakpoint> breakpointList = new JBList<>(breakpointModel);
    private final JBList<String> observationList = new JBList<>(observationModel);
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
    private final JBLabel topicSectionLabel = new JBLabel("专题");
    private final JBLabel entrySectionLabel = new JBLabel("源码入口");
    private final JBLabel breakpointSectionLabel = new JBLabel("推荐断点");
    private final JBLabel readingSessionLabel = new JBLabel("阅读会话：选择专题后开始");
    private final JBLabel breakpointScenarioLabel = new JBLabel("观察场景：选择一个推荐断点");
    private final JBLabel breakpointExpectedLabel = new JBLabel("预期结果：等待选择证据场景");
    private final JPanel debugGuidancePanel = new JPanel();
    private final JBLabel debugGuidanceTitleLabel = new JBLabel("调试引导：等待命中 Atlas 断点");
    private final JBLabel debugGuidanceClaimLabel = new JBLabel();
    private final JBLabel debugGuidanceExpectedLabel = new JBLabel();
    private final JBLabel debugGuidanceNextLabel = new JBLabel();
    private final JBLabel debugSessionLabel = new JBLabel();
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
    private final JButton openExternalDocumentationButton = new ActionLink("浏览器打开");
    private final JButton openVersionComparisonButton = new ActionLink("版本对比");
    private final JButton copyCallChainButton = new ActionLink("复制调用链");
    private final JButton navigateSourceButton = new ActionLink("定位源码");
    private final JButton resumeReadingButton = new JButton("继续阅读");
    private final JButton previousEntryButton = new ActionLink("上一个");
    private final JButton nextEntryButton = new ActionLink("下一个");
    private final JButton addAllBreakpointsButton = new ActionLink("添加全部断点");
    private final JButton openLabButton = new ActionLink("打开 Lab");
    private final JButton debugLabButton = new ActionLink("Debug Lab");
    private final JButton debugEvidenceButton = new JButton("添加断点并 Debug");
    private final JButton viewBreakpointExplanationButton = new ActionLink("查看讲解");
    private final JButton breakpointManagementButton = new ActionLink("断点管理");
    private final JButton copyWatchExpressionsButton = new ActionLink("复制变量");
    private final JButton addWatchExpressionsButton = new JButton("添加 Watches");
    private final JButton selectCurrentGuidanceButton = new ActionLink("查看当前断点");
    private final JButton selectNextGuidanceButton = new JButton("添加下一断点并继续");
    private final JButton copyDebugSummaryButton = new ActionLink("复制调试摘要");
    private final JButton favoriteTopicButton = new ActionLink("收藏当前专题");
    private final JButton clearRecentButton = new ActionLink("清空最近阅读");
    private final JButton backToNavigationButton = new ActionLink("返回专题导航");
    private final JButton tutorialExternalDocumentationButton = new JButton("浏览器打开");
    private final JButton enterNextTopicButton = new JButton("进入下一专题");
    private final JButton refreshEnvironmentButton = new JButton("重新检测");
    private final JButton openEnvironmentGuideButton = new ActionLink("使用指南");
    private final JButton openProjectRepositoryButton = new ActionLink("项目仓库");
    private final JButton openAtlasSettingsButton = new ActionLink("教程设置");
    private final JButton fixDocumentationButton = new JButton("教程设置");
    private final JButton fixJdkButton = new JButton("配置 JDK");
    private final JButton fixProjectButton = new JButton("打开仓库");
    private final JButton fixSourceButton = new JButton("配置源码");
    private final JButton fixLabButton = new JButton("刷新 Maven");
    private final JButton fixEvidenceButton = new JButton("选择断点");
    private final JButton fixBrowserButton = new JButton("浏览器打开");
    // 2026-08-26：取消编辑器光标的持续跟随，原上下文计时器字段不再需要。
    // private final Timer contextTimer;

    private AtlasEmbeddedBrowser tutorialBrowser;
    private AtlasDebugGuidance currentDebugGuidance;
    private AtlasTopicVersion selectedTopicVersion;
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
     * 构建工具窗口并在首次打开时识别一次编辑器上下文。
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
        AtlasDebugGuidanceService.getInstance(project).addListener(this, this::applyDebugGuidance);

        // 2026-08-26：原逻辑每 800ms 跟随编辑器光标，会覆盖用户手动选择的专题和源码入口，现取消持续刷新。
        // contextTimer = new Timer(800, ignored -> refreshFromEditor(false));
        // contextTimer.setRepeats(true);
        // contextTimer.start();
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
                refreshReadingSession(topicList.getSelectedValue());
                updateActionState();
            }
        });
        breakpointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshBreakpointObservation();
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
        resumeReadingButton.addActionListener(ignored -> continueReadingSession());
        previousEntryButton.addActionListener(ignored -> navigateToAdjacentEntry(-1));
        nextEntryButton.addActionListener(ignored -> navigateToAdjacentEntry(1));
        addAllBreakpointsButton.addActionListener(ignored -> addAllBreakpoints());
        openLabButton.addActionListener(ignored -> openLab());
        debugLabButton.addActionListener(ignored -> debugLab());
        // 2026-08-21：原操作只启动 JUnit，保留 debugCurrentEvidence 方法作为单独启动实现，新入口先添加断点。
        // debugEvidenceButton.addActionListener(ignored -> debugCurrentEvidence());
        debugEvidenceButton.addActionListener(ignored -> addBreakpointAndDebugCurrentEvidence());
        viewBreakpointExplanationButton.addActionListener(ignored -> viewBreakpointExplanation());
        breakpointManagementButton.addActionListener(ignored -> showBreakpointManagementMenu());
        copyWatchExpressionsButton.addActionListener(ignored -> copyObservationExpressions());
        addWatchExpressionsButton.addActionListener(ignored -> addObservationWatches());
        selectCurrentGuidanceButton.addActionListener(ignored -> selectGuidanceBreakpoint(false));
        // 2026-09-02：原操作只在列表中选择下一断点，用户仍需手动添加并恢复 Debug，会中断阅读节奏。
        // selectNextGuidanceButton.addActionListener(ignored -> selectGuidanceBreakpoint(true));
        selectNextGuidanceButton.addActionListener(ignored -> addNextBreakpointAndResume());
        copyDebugSummaryButton.addActionListener(ignored -> copyDebugSessionSummary());
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
        resumeReadingButton.setToolTipText("恢复上次阅读的方法；首次阅读时从第一个入口开始");
        previousEntryButton.setToolTipText("定位调用链中的上一个源码入口");
        nextEntryButton.setToolTipText("定位调用链中的下一个源码入口");
        addAllBreakpointsButton.setToolTipText("添加当前专题的全部推荐断点");
        openLabButton.setToolTipText("打开当前专题的 Lab 主类");
        debugLabButton.setToolTipText("创建临时配置并 Debug 当前专题 Lab");
        debugEvidenceButton.setToolTipText("自动添加当前推荐断点，再 Debug 对应的单个 JUnit 测试方法");
        viewBreakpointExplanationButton.setToolTipText("打开与当前断点最匹配的源码入口讲解");
        breakpointManagementButton.setToolTipText("启用、禁用或清理由 Source Atlas 创建的断点");
        copyWatchExpressionsButton.setToolTipText("复制当前断点建议观察的变量表达式");
        addWatchExpressionsButton.setToolTipText("把观察变量加入当前 IDEA Debug 会话的 Watches");
        selectCurrentGuidanceButton.setToolTipText("恢复到本次暂停命中的 Atlas 推荐断点");
        selectNextGuidanceButton.setToolTipText("创建调用链中的下一推荐断点，并立即恢复当前 Debug 会话");
        copyDebugSummaryButton.setToolTipText("复制本次 Debug 实际经过的断点、证据和结论");
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
        configureVisualStyle();
    }

    /**
     * 为命令补充 IDEA 原生图标，并区分主操作按钮与轻量链接操作。
     */
    private void configureVisualStyle() {
        openExternalDocumentationButton.setIcon(AllIcons.Actions.OpenNewTab);
        openVersionComparisonButton.setIcon(AllIcons.Actions.Diff);
        copyCallChainButton.setIcon(AllIcons.Actions.Copy);
        navigateSourceButton.setIcon(AtlasIcons.SOURCE);
        resumeReadingButton.setIcon(AllIcons.Actions.Resume);
        previousEntryButton.setIcon(AllIcons.Actions.Back);
        nextEntryButton.setIcon(AllIcons.Actions.Forward);
        addAllBreakpointsButton.setIcon(AllIcons.Debugger.MultipleBreakpoints);
        openLabButton.setIcon(AllIcons.Actions.MenuOpen);
        debugLabButton.setIcon(AllIcons.Actions.StartDebugger);
        debugEvidenceButton.setIcon(AllIcons.Actions.StartDebugger);
        viewBreakpointExplanationButton.setIcon(AtlasIcons.DOCUMENTATION);
        breakpointManagementButton.setIcon(AllIcons.Debugger.ViewBreakpoints);
        copyWatchExpressionsButton.setIcon(AllIcons.Actions.Copy);
        addWatchExpressionsButton.setIcon(AllIcons.Debugger.AddToWatch);
        selectCurrentGuidanceButton.setIcon(AllIcons.Debugger.Db_set_breakpoint);
        selectNextGuidanceButton.setIcon(AllIcons.Actions.Resume);
        copyDebugSummaryButton.setIcon(AllIcons.Actions.Copy);
        favoriteTopicButton.setIcon(AllIcons.Nodes.Favorite);
        clearRecentButton.setIcon(AllIcons.General.Remove);
        backToNavigationButton.setIcon(AllIcons.Actions.Back);
        tutorialExternalDocumentationButton.setIcon(AllIcons.Actions.OpenNewTab);
        enterNextTopicButton.setIcon(AllIcons.Actions.Forward);
        refreshEnvironmentButton.setIcon(AllIcons.Actions.Refresh);
        openEnvironmentGuideButton.setIcon(AllIcons.Actions.Help);
        openProjectRepositoryButton.setIcon(AllIcons.General.Web);
        openAtlasSettingsButton.setIcon(AllIcons.General.Settings);
        fixDocumentationButton.setIcon(AllIcons.General.Settings);
        fixJdkButton.setIcon(AllIcons.General.ProjectStructure);
        fixProjectButton.setIcon(AllIcons.Actions.ProjectDirectory);
        fixSourceButton.setIcon(AllIcons.Actions.Download);
        fixLabButton.setIcon(AllIcons.Actions.Refresh);
        fixEvidenceButton.setIcon(AllIcons.Debugger.ViewBreakpoints);
        fixBrowserButton.setIcon(AllIcons.Actions.OpenNewTab);

        stylePrimaryActions(
                openDocumentationButton,
                resumeReadingButton,
                debugEvidenceButton,
                addWatchExpressionsButton,
                selectNextGuidanceButton,
                enterNextTopicButton,
                refreshEnvironmentButton,
                tutorialExternalDocumentationButton
        );
        styleCompactActions(
                openExternalDocumentationButton,
                openVersionComparisonButton,
                copyCallChainButton,
                navigateSourceButton,
                previousEntryButton,
                nextEntryButton,
                addAllBreakpointsButton,
                openLabButton,
                debugLabButton,
                viewBreakpointExplanationButton,
                breakpointManagementButton,
                copyWatchExpressionsButton,
                selectCurrentGuidanceButton,
                copyDebugSummaryButton,
                favoriteTopicButton,
                clearRecentButton,
                backToNavigationButton,
                openEnvironmentGuideButton,
                openProjectRepositoryButton,
                openAtlasSettingsButton
        );
        styleIconActions(previousEntryButton, nextEntryButton, copyCallChainButton);
    }

    /**
     * 强化每个工作流唯一的主要命令，避免所有操作看起来拥有相同优先级。
     *
     * @param actions 主要命令按钮
     */
    private void stylePrimaryActions(JButton... actions) {
        for (JButton action : actions) {
            action.putClientProperty("JButton.buttonType", "default");
            action.setFont(action.getFont().deriveFont(Font.BOLD, 12f));
            action.setMargin(JBUI.insets(5, 10));
            action.setFocusPainted(false);
        }
    }

    /**
     * 统一次要命令的紧凑尺寸和字重，由 ActionLink 提供原生悬浮与键盘反馈。
     *
     * @param actions 次要命令按钮
     */
    private void styleCompactActions(JButton... actions) {
        for (JButton action : actions) {
            action.setFont(action.getFont().deriveFont(Font.PLAIN, 12f));
            action.setMargin(JBUI.insets(3, 5));
            action.setFocusPainted(false);
        }
    }

    /**
     * 把含义明确且高频的导航命令收紧为图标按钮，并保留 tooltip 与无障碍名称。
     *
     * @param actions 图标命令按钮
     */
    private void styleIconActions(JButton... actions) {
        for (JButton action : actions) {
            String accessibleName = action.getText();
            action.setText("");
            action.setPreferredSize(new Dimension(JBUI.scale(28), JBUI.scale(28)));
            action.setMinimumSize(action.getPreferredSize());
            action.setMaximumSize(action.getPreferredSize());
            action.getAccessibleContext().setAccessibleName(accessibleName);
        }
    }

    /**
     * 创建工具窗口的主布局。
     *
     * @return 主组件
     */
    private JComponent createMainContent() {
        // 2026-08-24：主页签缩短为工作对象名称，避免和内部导航标题重复。
        tabs.addTab("阅读工作台", createNavigationContent());
        tabs.addTab("教程", createTutorialContent());
        return tabs;
    }

    /**
     * 创建专题列表、源码入口、断点和命令区组成的导航页。
     *
     * @return 导航页组件
     */
    private JComponent createNavigationContent() {
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        root.setBorder(JBUI.Borders.empty(8, 10, 10, 10));
        root.add(createHeader(), BorderLayout.NORTH);

        // 2026-08-19：窄工具窗口不再并排展示三组列表，改为单列分段页签，避免说明文字被挤压。
        navigationTabs.addTab("专题", createTopicSection());
        // 2026-08-24：原源码入口页只有列表和两个独立操作，无法连续恢复并沿调用链阅读。
        // navigationTabs.addTab("源码入口", createListSection(
        //         "关键源码入口",
        //         entryPointList,
        //         navigateSourceButton,
        //         copyCallChainButton
        // ));
        navigationTabs.addTab("源码", createEntryPointSection());
        // 2026-08-24：原推荐断点页把变量压缩在列表文字中，缺少独立观察清单和断点生命周期入口。
        // navigationTabs.addTab("推荐断点", createListSection(
        //         "推荐断点",
        //         breakpointList,
        //         addAllBreakpointsButton,
        //         openLabButton,
        //         debugLabButton,
        //         debugEvidenceButton,
        //         viewBreakpointExplanationButton
        // ));
        navigationTabs.addTab("断点", createBreakpointSection());
        navigationTabs.addTab("路径", createLearningSection());
        navigationTabs.addTab("环境", createEnvironmentSection());
        navigationTabs.setBorder(JBUI.Borders.emptyTop(4));
        navigationTabs.setToolTipTextAt(0, "搜索和选择源码专题");
        navigationTabs.setToolTipTextAt(1, "沿关键方法调用链连续阅读");
        navigationTabs.setToolTipTextAt(2, "准备断点、观察变量和证据场景");
        navigationTabs.setToolTipTextAt(3, "管理学习进度和后续专题");
        navigationTabs.setToolTipTextAt(4, "检查源码阅读和调试环境");
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
        JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(6)));

        // 2026-08-19：搜索框从公共头部移动到“专题”页，避免用户在源码入口页误以为搜索会过滤方法。
        // header.add(searchField, BorderLayout.NORTH);

        // 2026-08-24：原头部使用完整矩形边框包住全部信息，视觉上像传统表单卡片。
        JPanel summary = new JPanel();
        summary.setLayout(new javax.swing.BoxLayout(summary, javax.swing.BoxLayout.Y_AXIS));
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtil.getBoundsColor()),
                JBUI.Borders.empty(4, 4, 10, 4)
        ));

        JPanel titleRow = new JPanel(new BorderLayout(JBUI.scale(7), 0));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JBLabel atlasIcon = new JBLabel(AtlasIcons.ATLAS);
        atlasIcon.setToolTipText("Java Source Atlas 当前专题");
        titleRow.add(atlasIcon, BorderLayout.WEST);
        titleRow.add(topicTitleLabel, BorderLayout.CENTER);
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(24)));
        topicTitleLabel.setFont(topicTitleLabel.getFont().deriveFont(Font.BOLD, 16f));
        topicTitleLabel.setToolTipText("当前选中的源码专题");
        summary.add(titleRow);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)));
        contextLabel.setForeground(UIUtil.getLabelInfoForeground());
        contextLabel.setFont(contextLabel.getFont().deriveFont(Font.PLAIN, 12f));
        jdkVersionLabel.setForeground(UIUtil.getLabelInfoForeground());
        springVersionLabel.setForeground(UIUtil.getLabelInfoForeground());
        springBootVersionLabel.setForeground(UIUtil.getLabelInfoForeground());
        compatibilityLabel.setForeground(UIUtil.getLabelInfoForeground());
        actionHintLabel.setForeground(UIUtil.getLabelInfoForeground());
        jdkVersionLabel.setFont(jdkVersionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        springVersionLabel.setFont(springVersionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        springBootVersionLabel.setFont(springBootVersionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        compatibilityLabel.setFont(compatibilityLabel.getFont().deriveFont(Font.PLAIN, 12f));
        contextLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        jdkVersionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        springVersionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        springBootVersionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        compatibilityLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionHintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(contextLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));
        summary.add(jdkVersionLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(springVersionLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(springBootVersionLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        summary.add(compatibilityLabel);
        summary.add(javax.swing.Box.createVerticalStrut(JBUI.scale(7)));
        actionHintLabel.setIcon(AllIcons.General.Information);
        actionHintLabel.setIconTextGap(JBUI.scale(5));
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

        configureSectionLabel(topicSectionLabel);
        topicSectionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(topicSectionLabel);
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

        JComponent actionBar = createTabActionBar(enterNextTopicButton, favoriteTopicButton, clearRecentButton);
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

        // 2026-08-24：环境页底部从四个等权重矩形按钮改为一个主操作加三个轻量入口。
        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        actionBar.add(refreshEnvironmentButton);
        actionBar.add(openEnvironmentGuideButton);
        actionBar.add(openProjectRepositoryButton);
        actionBar.add(openAtlasSettingsButton);
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(32)));
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
        // 2026-08-24：原状态仅使用文本符号，改用 IDEA 主题图标提升扫描速度和主题适配。
        label.setIcon(switch (state) {
            case READY -> AllIcons.General.GreenCheckmark;
            case ACTION_REQUIRED -> AllIcons.General.Warning;
            case CHECKING -> AllIcons.Actions.Refresh;
            case PENDING, NOT_APPLICABLE -> AllIcons.General.Information;
        });
        label.setIconTextGap(JBUI.scale(5));
        label.setText(StringUtil.shortenTextWithEllipsis(StringUtil.notNullize(detail), 34, 0));
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
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境 …");
        } else if (readingReady && debugReady) {
            environmentOverviewLabel.setText("环境状态：源码阅读与场景调试已就绪");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境 ✓");
        } else if (readingReady) {
            environmentOverviewLabel.setText("环境状态：源码阅读可用，场景调试仍需准备");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境 !");
        } else if (required > 0) {
            environmentOverviewLabel.setText("环境状态：有 " + required + " 项需要处理");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境 !");
        } else {
            environmentOverviewLabel.setText("环境状态：选择专题与可调试断点后继续检查");
            navigationTabs.setTitleAt(ENVIRONMENT_TAB_INDEX, "环境");
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
     * 统一工作页中的区块标题字重与颜色，数量和进度会直接写入同一标签。
     *
     * @param label 区块标题
     */
    private void configureSectionLabel(JBLabel label) {
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(UIUtil.getLabelForeground());
        label.setBorder(JBUI.Borders.empty(2, 0, 4, 0));
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
     * 创建带会话状态和前后入口导航的源码阅读区域。
     *
     * @return 源码入口阅读区域
     */
    private JComponent createEntryPointSection() {
        JPanel section = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        JPanel sectionHeader = new JPanel();
        sectionHeader.setLayout(new javax.swing.BoxLayout(sectionHeader, javax.swing.BoxLayout.Y_AXIS));

        JComponent actionBar = createTabActionBar(
                resumeReadingButton,
                navigateSourceButton,
                previousEntryButton,
                nextEntryButton,
                copyCallChainButton
        );
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(actionBar);
        sectionHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)));
        readingSessionLabel.setFont(readingSessionLabel.getFont().deriveFont(Font.BOLD, 13f));
        readingSessionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(readingSessionLabel);
        sectionHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)));

        configureSectionLabel(entrySectionLabel);
        entrySectionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(entrySectionLabel);
        section.add(sectionHeader, BorderLayout.NORTH);
        entryPointList.getEmptyText().setText(emptyTextFor("关键源码入口"));
        section.add(new JBScrollPane(entryPointList), BorderLayout.CENTER);
        return section;
    }

    /**
     * 创建推荐断点列表、生命周期菜单和独立观察清单组成的工作区。
     *
     * @return 推荐断点工作区
     */
    private JComponent createBreakpointSection() {
        JPanel section = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        JPanel sectionHeader = new JPanel();
        sectionHeader.setLayout(new javax.swing.BoxLayout(sectionHeader, javax.swing.BoxLayout.Y_AXIS));
        // 2026-08-24：原六个按钮使用三行网格，主操作和辅助操作难以区分。
        JComponent primaryActions = createTabActionBar(
                debugEvidenceButton,
                addAllBreakpointsButton,
                openLabButton
        );
        primaryActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(primaryActions);
        JComponent secondaryActions = createTabActionBar(
                debugLabButton,
                viewBreakpointExplanationButton,
                breakpointManagementButton
        );
        secondaryActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(secondaryActions);
        sectionHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
        configureSectionLabel(breakpointSectionLabel);
        breakpointSectionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionHeader.add(breakpointSectionLabel);
        section.add(sectionHeader, BorderLayout.NORTH);

        breakpointList.getEmptyText().setText(emptyTextFor("推荐断点"));
        JPanel observation = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel observationHeader = new JPanel();
        observationHeader.setLayout(new javax.swing.BoxLayout(observationHeader, javax.swing.BoxLayout.Y_AXIS));
        JComponent guidance = createDebugGuidancePanel();
        guidance.setAlignmentX(Component.LEFT_ALIGNMENT);
        observationHeader.add(guidance);
        breakpointScenarioLabel.setFont(breakpointScenarioLabel.getFont().deriveFont(Font.BOLD, 13f));
        breakpointScenarioLabel.setIcon(AllIcons.Debugger.Watch);
        breakpointScenarioLabel.setIconTextGap(JBUI.scale(5));
        breakpointExpectedLabel.setForeground(UIUtil.getLabelInfoForeground());
        breakpointScenarioLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        breakpointExpectedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        observationHeader.add(breakpointScenarioLabel);
        observationHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
        observationHeader.add(breakpointExpectedLabel);
        observationHeader.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
        JComponent observationActions = createTabActionBar(addWatchExpressionsButton, copyWatchExpressionsButton);
        observationActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        observationHeader.add(observationActions);
        observation.add(observationHeader, BorderLayout.NORTH);
        observationList.setVisibleRowCount(4);
        observationList.setFixedCellHeight(JBUI.scale(24));
        observationList.getEmptyText().setText("当前断点没有额外观察变量");
        observation.add(new JBScrollPane(observationList), BorderLayout.CENTER);

        JSplitPane splitter = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JBScrollPane(breakpointList),
                observation
        );
        splitter.setBorder(null);
        splitter.setResizeWeight(0.62);
        splitter.setDividerSize(JBUI.scale(5));
        section.add(splitter, BorderLayout.CENTER);
        return section;
    }

    /**
     * 创建仅在命中 Atlas 断点后显示的结论与下一步区域。
     *
     * @return 调试引导区域
     */
    private JComponent createDebugGuidancePanel() {
        debugGuidancePanel.setLayout(new javax.swing.BoxLayout(
                debugGuidancePanel,
                javax.swing.BoxLayout.Y_AXIS
        ));
        debugGuidancePanel.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        debugGuidanceTitleLabel.setFont(debugGuidanceTitleLabel.getFont().deriveFont(Font.BOLD, 13f));
        debugGuidanceTitleLabel.setIcon(AllIcons.Actions.StartDebugger);
        debugGuidanceTitleLabel.setIconTextGap(JBUI.scale(5));
        debugGuidanceClaimLabel.setForeground(UIUtil.getLabelInfoForeground());
        debugGuidanceExpectedLabel.setForeground(UIUtil.getLabelInfoForeground());
        debugGuidanceNextLabel.setForeground(UIUtil.getLabelInfoForeground());
        debugSessionLabel.setForeground(UIUtil.getLabelInfoForeground());
        debugGuidanceTitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        debugGuidanceClaimLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        debugGuidanceExpectedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        debugGuidanceNextLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        debugSessionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        debugGuidancePanel.add(debugGuidanceTitleLabel);
        debugGuidancePanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
        debugGuidancePanel.add(debugGuidanceClaimLabel);
        debugGuidancePanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        debugGuidancePanel.add(debugGuidanceExpectedLabel);
        debugGuidancePanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        debugGuidancePanel.add(debugGuidanceNextLabel);
        debugGuidancePanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)));
        debugGuidancePanel.add(debugSessionLabel);
        debugGuidancePanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
        JComponent actions = createTabActionBar(
                selectCurrentGuidanceButton,
                selectNextGuidanceButton,
                copyDebugSummaryButton
        );
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        debugGuidancePanel.add(actions);
        debugGuidancePanel.setVisible(false);
        return debugGuidancePanel;
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
        // 2026-09-02：原逻辑把 OpenJDK 8 基线专题直接放入列表，后续动作无法感知项目 JDK。
        // List<AtlasTopic> matches = index.search(query).stream()
        //         .filter(topic -> "全部类型".equals(selectedType) || topicType(topic).equals(selectedType))
        //         .filter(topic -> !favoritesOnlyCheckBox.isSelected() || learningProgress.isFavorite(topic.topicId()))
        //         .toList();
        List<AtlasTopic> matches = index.search(query).stream()
                .filter(topic -> "全部类型".equals(selectedType) || topicType(topic).equals(selectedType))
                .filter(topic -> !favoritesOnlyCheckBox.isSelected() || learningProgress.isFavorite(topic.topicId()))
                .map(this::resolveTopicVersion)
                .map(AtlasTopicVersion::topic)
                .toList();
        topicModel.clear();
        matches.forEach(topicModel::addElement);

        // 2026-09-02：版本视图是基线专题的副本，不能再用 record equals 恢复列表选择。
        // AtlasTopic preferred = editorContext.topic() != null && matches.contains(editorContext.topic())
        //         ? editorContext.topic()
        //         : previous;
        AtlasTopic preferred = findTopicById(
                matches,
                editorContext.topic() == null
                        ? previous == null ? null : previous.topicId()
                        : editorContext.topic().topicId()
        );
        if (preferred != null) {
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
            selectedTopicVersion = null;
            topicTitleLabel.setText("没有匹配的源码专题");
            topicTitleLabel.setToolTipText("调整搜索条件后重新选择专题");
            contextLabel.setText("当前光标：未找到匹配专题");
            jdkVersionLabel.setText("");
            springVersionLabel.setText("");
            springBootVersionLabel.setText("");
            compatibilityLabel.setText("调整搜索条件后重试");
            actionHintLabel.setText("输入类名、方法名或专题名称开始搜索");
            refreshLearningSection(null);
            refreshReadingSession(null);
            refreshBreakpointObservation();
            updateNavigationTabTitles();
            updateActionState();
            if (navigationTabs.getSelectedIndex() == ENVIRONMENT_TAB_INDEX) {
                refreshEnvironmentChecks(false);
            }
            return;
        }

        selectedTopicVersion = resolveTopicVersion(topic);
        topic = selectedTopicVersion.topic();

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
        AtlasTopic displayedTopic = topic;
        AtlasVersionDetector.detectAsync(
                project,
                this,
                versionInfo -> applyVersionInfo(displayedTopic, versionInfo)
        );

        // 2026-08-24：原逻辑没有编辑器命中入口时始终选择第一项，无法恢复上次读到的方法。
        // if (preferredEntry != null && topic.entryPoints().contains(preferredEntry)) {
        //     entryPointList.setSelectedValue(preferredEntry, true);
        // } else if (!topic.entryPoints().isEmpty()) {
        //     entryPointList.setSelectedIndex(0);
        // }
        AtlasEntryPoint restoredEntry = preferredEntry != null
                ? resolveVersionEntry(topic, preferredEntry)
                : lastReadEntry(topic);
        if (restoredEntry != null && topic.entryPoints().contains(restoredEntry)) {
            entryPointList.setSelectedValue(restoredEntry, true);
        } else if (!topic.entryPoints().isEmpty()) {
            entryPointList.setSelectedIndex(0);
        }
        refreshReadingSession(topic);
        refreshBreakpointObservation();
        updateActionState();
        if (navigationTabs.getSelectedIndex() == ENVIRONMENT_TAB_INDEX) {
            refreshEnvironmentChecks(false);
        }
    }

    /**
     * 从方法级进度中查找当前专题上次阅读的源码入口。
     *
     * @param topic 当前专题
     * @return 上次入口；没有记录或入口已从索引删除时返回 null
     */
    private AtlasEntryPoint lastReadEntry(AtlasTopic topic) {
        if (topic == null) {
            return null;
        }
        String lastMethod = learningProgress.progressFor(topic.topicId()).lastEntryMethod;
        return topic.entryPoints().stream()
                .filter(entryPoint -> entryPoint.method().equals(lastMethod))
                .findFirst()
                .orElse(null);
    }

    /**
     * 刷新源码阅读会话的当前位置、方法完成数和导航按钮状态。
     *
     * @param topic 当前专题
     */
    private void refreshReadingSession(AtlasTopic topic) {
        AtlasEntryPoint current = entryPointList.getSelectedValue();
        if (topic == null || topic.entryPoints().isEmpty()) {
            readingSessionLabel.setText("阅读会话：当前专题没有源码入口");
            readingSessionLabel.setToolTipText(null);
            resumeReadingButton.setEnabled(false);
            previousEntryButton.setEnabled(false);
            nextEntryButton.setEnabled(false);
            return;
        }

        AtlasLearningProgressState.TopicProgress progress = learningProgress.progressFor(topic.topicId());
        int currentIndex = current == null ? -1 : topic.entryPoints().indexOf(current);
        long visitedCount = topic.entryPoints().stream()
                .map(AtlasEntryPoint::method)
                .filter(progress.visitedEntryMethods::contains)
                .count();
        String position = currentIndex < 0
                ? "尚未选择入口"
                : "当前 " + (currentIndex + 1) + "/" + topic.entryPoints().size();
        String version = progress.lastVersion.isBlank() ? topic.primaryVersion() : progress.lastVersion;
        String status = "阅读会话：" + position + " · 已读 " + visitedCount + "/"
                + topic.entryPoints().size() + " · " + version;
        readingSessionLabel.setText(shortLabel(status));
        readingSessionLabel.setToolTipText(status
                + (progress.lastDocument.isBlank() ? "" : " · 上次文档：" + progress.lastDocument));
        resumeReadingButton.setEnabled(true);
        previousEntryButton.setEnabled(currentIndex > 0);
        nextEntryButton.setEnabled(currentIndex >= 0 && currentIndex < topic.entryPoints().size() - 1);
    }

    /**
     * 恢复上次阅读入口；没有历史时从专题第一个入口开始并定位源码。
     */
    private void continueReadingSession() {
        AtlasTopic topic = topicList.getSelectedValue();
        if (topic == null || topic.entryPoints().isEmpty()) {
            return;
        }
        AtlasEntryPoint target = lastReadEntry(topic);
        entryPointList.setSelectedValue(target == null ? topic.entryPoints().get(0) : target, true);
        navigationTabs.setSelectedIndex(1);
        navigateToSource();
    }

    /**
     * 沿专题索引定义的调用链切换入口并立即定位源码。
     *
     * @param offset 相对当前位置的偏移，负数向前、正数向后
     */
    private void navigateToAdjacentEntry(int offset) {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint current = entryPointList.getSelectedValue();
        if (topic == null || current == null) {
            return;
        }
        int targetIndex = topic.entryPoints().indexOf(current) + offset;
        if (targetIndex < 0 || targetIndex >= topic.entryPoints().size()) {
            return;
        }
        entryPointList.setSelectedIndex(targetIndex);
        entryPointList.ensureIndexIsVisible(targetIndex);
        navigateToSource();
    }

    /**
     * 记录一次实际打开的源码或教程入口，并同步最近阅读与会话进度。
     *
     * @param topic      当前专题
     * @param entryPoint 已打开的源码入口
     */
    private void recordEntryVisit(AtlasTopic topic, AtlasEntryPoint entryPoint) {
        if (topic == null || entryPoint == null) {
            return;
        }
        learningProgress.recordEntry(
                topic.topicId(),
                entryPoint.method(),
                entryPoint.document(),
                topic.primaryVersion()
        );
        refreshReadingSession(topic);
        updateNavigationTabTitles();
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
                + (latest.methodName() == null ? "" : "#" + latest.methodName())
                + (latest.topic() == null && latest.topicCandidates().size() > 1
                ? "（匹配到多个专题，请选择）"
                : ""));

        if (latest.topic() == null && latest.topicCandidates().size() > 1) {
            // 2026-08-26：共享源码类无法唯一判断时不再静默切换到第一个专题，改为让用户明确选择。
            showTopicCandidateChooser(latest.topicCandidates());
            return;
        }

        if (latest.topic() != null) {
            searchField.setText("");
            // 2026-09-02：原逻辑用基线 topic equals 选择列表，无法命中 JDK 17/21 版本视图。
            // topicList.setSelectedValue(latest.topic(), true);
            AtlasTopic versionTopic = topicInCurrentModel(latest.topic().topicId());
            if (versionTopic != null) {
                topicList.setSelectedValue(versionTopic, true);
                showTopic(versionTopic, latest.entryPoint());
            }
        }
    }

    /**
     * 展示共享源码类对应的专题候选，避免用户在多个专题之间被动跳转。
     *
     * @param candidates 已按方法入口、版本和源码类排序的专题候选
     */
    private void showTopicCandidateChooser(List<AtlasTopic> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return;
        }
        // 2026-08-27：原逻辑在工具窗口内单独维护选择器，编辑器动作无法复用同一交互。
        // String[] options = candidates.stream()
        //         .map(topic -> topic.title() + "（" + topic.primaryVersion() + "）")
        //         .toArray(String[]::new);
        // int selected = Messages.showChooseDialog(
        //         project,
        //         "当前源码类对应多个 Source Atlas 专题，请选择要阅读的专题：",
        //         "选择 Source Atlas 专题",
        //         AtlasIcons.ATLAS,
        //         options,
        //         options[0]
        // );
        // if (selected >= 0 && selected < candidates.size()) {
        //     AtlasTopic topic = candidates.get(selected);
        AtlasTopic topic = AtlasTopicChooser.choose(project, candidates);
        if (topic != null) {
            // 2026-09-02：候选选择器返回基线专题，统一通过编号切换到当前项目版本视图。
            navigateToTopic(topic);
        }
    }

    /**
     * 仅当专题仍处于选中状态时展示后台检测到的项目版本。
     *
     * @param topic       发起检测时的专题
     * @param versionInfo 项目版本信息
     */
    private void applyVersionInfo(AtlasTopic topic, AtlasVersionInfo versionInfo) {
        if (!sameTopic(topic, topicList.getSelectedValue())) {
            return;
        }
        String jdkText = "项目 JDK：" + versionInfo.jdkVersion();
        String springText = "Spring：" + versionInfo.springVersion();
        String springBootText = "Spring Boot：" + versionInfo.springBootVersion();
        String matchText = AtlasVersionDetector.compatibilityHint(topic, versionInfo);
        String compatibilityText = selectedTopicVersion == null
                ? matchText
                : selectedTopicVersion.message() + "；" + matchText;
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
     * 从稳定专题编号重新取得基线数据，再按当前项目 SDK 生成版本视图。
     *
     * @param topic 基线或既有版本视图
     * @return 当前项目应使用的专题版本
     */
    private AtlasTopicVersion resolveTopicVersion(AtlasTopic topic) {
        if (topic == null) {
            return AtlasTopicVersionResolver.resolve(null, AtlasVersionDetector.projectJdkVersion(project));
        }
        AtlasTopic baseline = index.findById(topic.topicId()).orElse(topic);
        return AtlasTopicVersionResolver.resolve(
                baseline,
                AtlasVersionDetector.projectJdkVersion(project)
        );
    }

    /**
     * 按稳定编号从专题集合中查找版本视图，避免 record 内容变化导致 equals 失效。
     *
     * @param topics  专题集合
     * @param topicId 稳定专题编号
     * @return 匹配专题；不存在时返回空
     */
    private AtlasTopic findTopicById(List<AtlasTopic> topics, String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return null;
        }
        return topics.stream()
                .filter(topic -> topicId.equals(topic.topicId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从当前列表模型查找指定专题编号。
     *
     * @param topicId 稳定专题编号
     * @return 当前项目版本的专题视图
     */
    private AtlasTopic topicInCurrentModel(String topicId) {
        for (int index = 0; index < topicModel.size(); index++) {
            AtlasTopic topic = topicModel.get(index);
            if (topicId != null && topicId.equals(topic.topicId())) {
                return topic;
            }
        }
        return null;
    }

    /**
     * 判断两个专题是否指向同一个稳定编号。
     *
     * @param left  左侧专题
     * @param right 右侧专题
     * @return 是否为同一专题
     */
    private boolean sameTopic(AtlasTopic left, AtlasTopic right) {
        return left != null && right != null && left.topicId().equals(right.topicId());
    }

    /**
     * 将编辑器基线入口映射为当前 JDK 版本入口，优先完整签名再使用简单方法名。
     *
     * @param topic          当前版本专题
     * @param preferredEntry 编辑器匹配到的基线入口
     * @return 当前版本入口；无法对应时返回原入口
     */
    private AtlasEntryPoint resolveVersionEntry(AtlasTopic topic, AtlasEntryPoint preferredEntry) {
        return topic.entryPoints().stream()
                .filter(entry -> entry.method().equals(preferredEntry.method()))
                .findFirst()
                .or(() -> topic.entryPoints().stream()
                        .filter(entry -> entry.simpleMethodName().equals(preferredEntry.simpleMethodName()))
                        .findFirst())
                .orElse(preferredEntry);
    }

    /**
     * 根据当前选择启用或禁用命令按钮。
     */
    private void updateActionState() {
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasEntryPoint entryPoint = entryPointList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        refreshReadingSession(topic);
        refreshBreakpointObservation();
        openDocumentationButton.setEnabled(topic != null && entryPoint != null);
        openExternalDocumentationButton.setEnabled(topic != null && entryPoint != null);
        openVersionComparisonButton.setEnabled(topic != null && topic.versionComparison() != null);
        copyCallChainButton.setEnabled(topic != null && !topic.entryPoints().isEmpty());
        boolean sourceActionsAllowed = selectedTopicVersion != null
                && selectedTopicVersion.sourceActionsAllowed();
        addAllBreakpointsButton.setEnabled(sourceActionsAllowed && topic != null && !topic.breakpoints().isEmpty());
        viewBreakpointExplanationButton.setEnabled(topic != null && breakpoint != null
                && index.explanationForBreakpoint(topic, breakpoint).isPresent());
        boolean hasObservation = breakpoint != null && !breakpoint.variables().isEmpty();
        copyWatchExpressionsButton.setEnabled(hasObservation);
        addWatchExpressionsButton.setEnabled(hasObservation);
        AtlasBreakpointManager.ManagedSummary managedSummary = AtlasBreakpointManager.managedSummary(project);
        breakpointManagementButton.setEnabled(managedSummary.count() > 0);
        breakpointManagementButton.setText(managedSummary.count() == 0
                ? "断点管理"
                : "断点管理 " + managedSummary.count());
        favoriteTopicButton.setEnabled(topic != null);
        boolean favorite = topic != null && learningProgress.isFavorite(topic.topicId());
        favoriteTopicButton.setText(favorite ? "取消收藏" : "收藏当前专题");
        favoriteTopicButton.setIcon(favorite ? AllIcons.Nodes.Favorite : AllIcons.Nodes.NotFavoriteOnHover);
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
            if (!sameTopic(topic, topicList.getSelectedValue())) {
                return;
            }
            openLabButton.setEnabled(sourceActionsAllowed && available);
            debugLabButton.setEnabled(sourceActionsAllowed && available);
            String labHint = available
                    ? "已找到 " + topic.lab().mainClass()
                    : "当前项目未导入 " + topic.lab().module();
            openLabButton.setToolTipText(labHint);
            debugLabButton.setToolTipText(labHint);
        });
        if (evidence != null) {
            AtlasLabLauncher.checkEvidenceAvailability(project, this, evidence, available -> {
                if (!sameTopic(topic, topicList.getSelectedValue())
                        || !breakpoint.equals(breakpointList.getSelectedValue())) {
                    return;
                }
                debugEvidenceButton.setEnabled(sourceActionsAllowed && available && !evidenceDebugPending);
                debugEvidenceButton.setToolTipText(available
                        ? evidence.testClass() + "#" + evidence.testMethod()
                        + "；将先添加当前断点；预期：" + evidence.expectedOutcome()
                        : "当前项目未导入测试 " + evidence.testClass() + "#" + evidence.testMethod());
            });
        }
        if (entryPoint != null) {
            AtlasSourceNavigator.checkAvailability(project, this, topic, entryPoint, available -> {
                if (!sameTopic(topic, topicList.getSelectedValue())
                        || !entryPoint.equals(entryPointList.getSelectedValue())) {
                    return;
                }
                navigateSourceButton.setEnabled(sourceActionsAllowed && available);
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
        // 2026-08-24：数量和进度移入页面标题，导航页签只承担模式切换，减少拥挤。
        navigationTabs.setTitleAt(0, "专题");
        topicSectionLabel.setText("专题  ·  " + topicModel.size() + " 个结果");
        // 2026-08-24：原页签只展示入口总数，现在同时展示已实际打开的方法数量。
        // navigationTabs.setTitleAt(1, "源码入口 " + entryPointModel.size());
        AtlasTopic selectedTopic = topicList.getSelectedValue();
        long visitedEntries = selectedTopic == null
                ? 0
                : selectedTopic.entryPoints().stream()
                .map(AtlasEntryPoint::method)
                .filter(learningProgress.progressFor(selectedTopic.topicId()).visitedEntryMethods::contains)
                .count();
        navigationTabs.setTitleAt(1, "源码");
        entrySectionLabel.setText("源码入口  ·  已读 " + visitedEntries + "/" + entryPointModel.size()
                + "  ·  双击定位");
        // 2026-08-24：原页签只展示断点总数，现在同时展示已经添加或准备过的断点数量。
        // navigationTabs.setTitleAt(2, "推荐断点 " + breakpointModel.size());
        AtlasLearningProgressState.TopicProgress breakpointProgress = selectedTopic == null
                ? new AtlasLearningProgressState.TopicProgress()
                : learningProgress.progressFor(selectedTopic.topicId());
        long preparedBreakpoints = selectedTopic == null
                ? 0
                : selectedTopic.breakpoints().stream()
                .map(AtlasBreakpoint::method)
                .filter(breakpointProgress.preparedBreakpointMethods::contains)
                .count();
        long verifiedBreakpoints = selectedTopic == null
                ? 0
                : selectedTopic.breakpoints().stream()
                .map(AtlasBreakpoint::method)
                .filter(breakpointProgress.verifiedBreakpointMethods::contains)
                .count();
        navigationTabs.setTitleAt(2, "断点");
        // 2026-09-02：原标题只展示准备数量，无法区分“已添加”和“运行时真正命中”。
        // breakpointSectionLabel.setText("推荐断点  ·  已准备 " + preparedBreakpoints + "/"
        //         + breakpointModel.size() + "  ·  双击添加");
        breakpointSectionLabel.setText("推荐断点  ·  已准备 " + preparedBreakpoints + "/"
                + breakpointModel.size() + "  ·  已命中 " + verifiedBreakpoints + "  ·  双击添加");
        AtlasTopic topic = selectedTopic;
        AtlasLearningProgressState.TopicProgress progress = topic == null
                ? new AtlasLearningProgressState.TopicProgress()
                : learningProgress.progressFor(topic.topicId());
        navigationTabs.setTitleAt(3, progress.readMain && progress.ranLab ? "路径 ✓" : "路径");
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
        // 2026-09-02：原文案只显示证据总数，没有反馈 Debug 已经验证了多少条。
        // evidenceLabel.setText("可执行证据：" + topic.evidence().size()
        //         + " 条（源码入口 → 讲解 → Lab → JUnit）");
        evidenceLabel.setText("可执行证据：已验证 " + progress.verifiedEvidenceIds.size() + "/"
                + topic.evidence().size() + " 条（源码入口 → 讲解 → Lab → JUnit）");

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
        // 2026-09-02：原逻辑直接选择基线对象；版本视图必须按稳定专题编号定位。
        // topicList.setSelectedValue(target, true);
        AtlasTopic versionTarget = topicInCurrentModel(target.topicId());
        if (versionTarget != null) {
            topicList.setSelectedValue(versionTarget, true);
        }
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
        // 2026-08-24：原逻辑只记录最近专题，现在同时保存精确方法、文档锚点和阅读版本。
        // learningProgress.recordRecent(topic.topicId());
        // updateNavigationTabTitles();
        recordEntryVisit(topic, entryPoint);
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
            // 2026-08-24：原逻辑只记录最近专题，现在浏览器阅读也进入方法级会话。
            // learningProgress.recordRecent(topic.topicId());
            // updateNavigationTabTitles();
            recordEntryVisit(topic, entryPoint);
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
     * 根据当前推荐断点刷新观察场景、预期结果和变量表达式列表。
     */
    private void refreshBreakpointObservation() {
        observationModel.clear();
        AtlasTopic topic = topicList.getSelectedValue();
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        if (topic == null || breakpoint == null) {
            breakpointScenarioLabel.setText("观察场景：选择一个推荐断点");
            breakpointScenarioLabel.setToolTipText(null);
            breakpointExpectedLabel.setText("预期结果：等待选择证据场景");
            breakpointExpectedLabel.setToolTipText(null);
            return;
        }

        String scenario = StringUtil.notNullize(breakpoint.scenario(), "当前断点暂无场景说明");
        breakpointScenarioLabel.setText(shortLabel("观察场景：" + scenario));
        breakpointScenarioLabel.setToolTipText(scenario);
        AtlasEvidence evidence = index.evidenceForBreakpoint(topic, breakpoint).orElse(null);
        String expected = evidence == null
                ? "当前断点没有绑定独立证据，请按场景检查状态变化"
                : evidence.expectedOutcome();
        breakpointExpectedLabel.setText(shortLabel("预期结果：" + expected));
        breakpointExpectedLabel.setToolTipText(expected);
        breakpoint.variables().forEach(observationModel::addElement);
    }

    /**
     * 展示或清除当前 Debug 会话命中的 Atlas 证据，并自动选中对应推荐断点。
     *
     * @param guidance 当前暂停位置解析结果
     */
    private void applyDebugGuidance(Optional<AtlasDebugGuidance> guidance) {
        currentDebugGuidance = guidance.orElse(null);
        AtlasDebugSessionReport report = AtlasDebugGuidanceService.getInstance(project).latestReport();
        // 2026-09-02：原逻辑在会话恢复或结束时直接隐藏引导，导致刚形成的调用路径和摘要无法查看。
        // if (currentDebugGuidance == null) {
        //     debugGuidancePanel.setVisible(false);
        //     debugGuidancePanel.revalidate();
        //     debugGuidancePanel.repaint();
        //     return;
        // }
        if (currentDebugGuidance == null && report.visits().isEmpty()) {
            debugGuidancePanel.setVisible(false);
            debugGuidancePanel.revalidate();
            debugGuidancePanel.repaint();
            return;
        }

        String title = currentDebugGuidance == null
                ? report.active() ? "调试引导 · 会话运行中" : "调试引导 · 本次会话已结束"
                : "调试引导 · 已命中 " + currentDebugGuidance.breakpointMethod();
        String claim = currentDebugGuidance == null
                ? "最近命中：" + report.visits().getLast().breakpointMethod()
                : "正在验证：" + currentDebugGuidance.claim();
        String expected = currentDebugGuidance == null
                ? "已保存本次实际经过的源码断点路径"
                : "预期状态：" + currentDebugGuidance.expectedOutcome();
        String next = currentDebugGuidance == null
                ? "下一步：可复制摘要沉淀到学习笔记"
                : currentDebugGuidance.nextBreakpointMethod().isBlank()
                ? "下一步：当前专题的推荐断点已经走完"
                : "下一断点：" + currentDebugGuidance.nextBreakpointMethod();
        String session = "会话记录：命中 " + report.visits().size()
                + " 次，验证 " + report.verifiedEvidenceCount() + " 条证据";
        debugGuidanceTitleLabel.setText(shortLabel(title));
        debugGuidanceTitleLabel.setToolTipText(title);
        debugGuidanceClaimLabel.setText(shortLabel(claim));
        debugGuidanceClaimLabel.setToolTipText(claim);
        debugGuidanceExpectedLabel.setText(shortLabel(expected));
        debugGuidanceExpectedLabel.setToolTipText(expected);
        debugGuidanceNextLabel.setText(shortLabel(next));
        debugGuidanceNextLabel.setToolTipText(next);
        debugSessionLabel.setText(shortLabel(session));
        debugSessionLabel.setToolTipText(session);
        selectCurrentGuidanceButton.setVisible(currentDebugGuidance != null);
        selectNextGuidanceButton.setVisible(currentDebugGuidance != null
                && !currentDebugGuidance.nextBreakpointMethod().isBlank());
        selectNextGuidanceButton.setEnabled(selectNextGuidanceButton.isVisible());
        selectNextGuidanceButton.setText("添加下一断点并继续");
        copyDebugSummaryButton.setVisible(!report.visits().isEmpty());
        copyDebugSummaryButton.setEnabled(!report.visits().isEmpty());
        debugGuidancePanel.setVisible(true);
        if (currentDebugGuidance != null) {
            selectGuidanceBreakpoint(false);
        }
        debugGuidancePanel.revalidate();
        debugGuidancePanel.repaint();
    }

    /**
     * 从调试引导切换到当前或下一推荐断点，不自动创建新断点。
     *
     * @param next 是否选择下一推荐断点
     */
    private void selectGuidanceBreakpoint(boolean next) {
        if (currentDebugGuidance == null) {
            return;
        }
        String method = next
                ? currentDebugGuidance.nextBreakpointMethod()
                : currentDebugGuidance.breakpointMethod();
        if (method == null || method.isBlank()) {
            return;
        }
        AtlasTopic topic = index.findById(currentDebugGuidance.topicId()).orElse(null);
        if (topic == null) {
            return;
        }
        // 2026-09-02：原逻辑用完整 record 比较基线专题和版本专题，JDK 17/21 会被误判为不同专题。
        // if (!topic.equals(topicList.getSelectedValue())) {
        if (!sameTopic(topic, topicList.getSelectedValue())) {
            navigateToTopic(topic);
        }
        for (int index = 0; index < breakpointModel.size(); index++) {
            if (method.equals(breakpointModel.get(index).method())) {
                breakpointList.setSelectedIndex(index);
                breakpointList.ensureIndexIsVisible(index);
                break;
            }
        }
        tabs.setSelectedIndex(0);
        navigationTabs.setSelectedIndex(2);
        updateActionState();
    }

    /**
     * 解析并添加当前调用链中的下一推荐断点，成功后立即继续活动 Debug 会话。
     */
    private void addNextBreakpointAndResume() {
        if (currentDebugGuidance == null || currentDebugGuidance.nextBreakpointMethod().isBlank()) {
            return;
        }
        AtlasTopic baseline = index.findById(currentDebugGuidance.topicId()).orElse(null);
        AtlasTopic topic = baseline == null
                ? null
                : AtlasTopicVersionResolver.resolve(
                        baseline,
                        AtlasVersionDetector.projectJdkVersion(project)
                ).topic();
        AtlasBreakpoint nextBreakpoint = topic == null
                ? null
                : topic.breakpoints().stream()
                .filter(item -> currentDebugGuidance.nextBreakpointMethod().equals(item.method()))
                .findFirst()
                .orElse(null);
        if (topic == null || nextBreakpoint == null) {
            Messages.showInfoMessage(
                    project,
                    "当前项目版本中没有找到下一推荐断点，请打开版本对比确认方法变化。",
                    "Java Source Atlas"
            );
            return;
        }

        selectNextGuidanceButton.setEnabled(false);
        selectNextGuidanceButton.setText("准备下一断点…");
        AtlasBreakpointManager.addBreakpointsAsync(
                project,
                this,
                topic,
                List.of(nextBreakpoint),
                result -> {
                    selectNextGuidanceButton.setText("添加下一断点并继续");
                    if (result.added() + result.existing() == 0) {
                        selectNextGuidanceButton.setEnabled(true);
                        Messages.showInfoMessage(
                                project,
                                "没有解析到 " + nextBreakpoint.method()
                                        + " 的可执行首行，请确认当前版本源码已经附加。",
                                "Java Source Atlas"
                        );
                        return;
                    }
                    learningProgress.recordBreakpoint(
                            topic.topicId(),
                            nextBreakpoint.method(),
                            topic.primaryVersion()
                    );
                    updateNavigationTabTitles();
                    boolean resumed = AtlasDebugGuidanceService.getInstance(project).resumeCurrentSession();
                    if (!resumed) {
                        selectNextGuidanceButton.setEnabled(true);
                        Messages.showInfoMessage(
                                project,
                                "下一断点已经添加，但当前 Debug 会话不再处于暂停状态。",
                                "Java Source Atlas"
                        );
                    }
                }
        );
    }

    /**
     * 复制当前或最近一次 Debug 会话的实际断点路径、证据与预期结果。
     */
    private void copyDebugSessionSummary() {
        AtlasDebugSessionReport report = AtlasDebugGuidanceService.getInstance(project).latestReport();
        if (report.visits().isEmpty()) {
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(report.toMarkdown()));
        Messages.showInfoMessage(
                project,
                "已复制本次 Debug 会话的 " + report.visits().size() + " 条断点记录。",
                "Java Source Atlas"
        );
    }

    /**
     * 将当前断点观察变量按一行一个表达式复制到系统剪贴板。
     */
    private void copyObservationExpressions() {
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        if (breakpoint == null || breakpoint.variables().isEmpty()) {
            return;
        }
        CopyPasteManager.getInstance().setContents(
                new StringSelection(String.join(System.lineSeparator(), breakpoint.variables()))
        );
        Messages.showInfoMessage(
                project,
                "已复制 " + breakpoint.variables().size() + " 个观察表达式。",
                "Source Atlas 观察清单"
        );
    }

    /**
     * 把当前断点的观察变量加入活动 Debug 会话 Watches，并反馈去重结果。
     */
    private void addObservationWatches() {
        AtlasBreakpoint breakpoint = breakpointList.getSelectedValue();
        if (breakpoint == null || breakpoint.variables().isEmpty()) {
            return;
        }
        AtlasWatchManager.WatchResult result = AtlasWatchManager.addToCurrentSession(
                project,
                breakpoint.variables()
        );
        if (result.noSession()) {
            Messages.showInfoMessage(
                    project,
                    "请先启动 Debug 会话并暂停到断点，再添加 Watches。",
                    "Source Atlas 观察清单"
            );
            return;
        }
        if (result.unsupported()) {
            CopyPasteManager.getInstance().setContents(
                    new StringSelection(String.join(System.lineSeparator(), breakpoint.variables()))
            );
            Messages.showInfoMessage(
                    project,
                    "当前 IDEA 版本不支持插件直接写入 Watches，已将 "
                            + breakpoint.variables().size()
                            + " 个观察表达式复制到剪贴板，请在 Debug 的 Watches 面板中粘贴。",
                    "Source Atlas 观察清单"
            );
            return;
        }
        Messages.showInfoMessage(
                project,
                "新增 " + result.added() + " 个 Watches，已存在 " + result.existing() + " 个。",
                "Source Atlas 观察清单"
        );
    }

    /**
     * 展示 Atlas 断点启停与清理菜单，操作范围只包含插件自己创建的断点。
     */
    private void showBreakpointManagementMenu() {
        AtlasBreakpointManager.ManagedSummary summary = AtlasBreakpointManager.managedSummary(project);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem toggle = new JMenuItem(summary.allEnabled() ? "禁用全部 Atlas 断点" : "启用全部 Atlas 断点");
        toggle.addActionListener(ignored -> updateManagedBreakpointEnabled(!summary.allEnabled()));
        menu.add(toggle);

        JMenuItem removeTopic = new JMenuItem("清理本专题 Atlas 断点");
        removeTopic.setEnabled(topicList.getSelectedValue() != null);
        removeTopic.addActionListener(ignored -> removeManagedBreakpoints(false));
        menu.add(removeTopic);

        JMenuItem removeAll = new JMenuItem("清理全部 Atlas 断点");
        // 2026-08-26：原逻辑直接执行批量清理，缺少数量确认。
        // removeAll.addActionListener(ignored -> removeManagedBreakpoints(true));
        removeAll.addActionListener(ignored -> confirmRemoveAllManagedBreakpoints());
        menu.add(removeAll);
        menu.show(breakpointManagementButton, 0, breakpointManagementButton.getHeight());
    }

    /**
     * 清理全部 Atlas 断点前展示数量确认，避免用户误触发批量删除。
     */
    private void confirmRemoveAllManagedBreakpoints() {
        AtlasBreakpointManager.ManagedSummary summary = AtlasBreakpointManager.managedSummary(project);
        if (summary.count() == 0) {
            removeManagedBreakpoints(true);
            return;
        }
        int answer = Messages.showYesNoDialog(
                project,
                "将清理 " + summary.count() + " 个由 Source Atlas 创建的断点。\n"
                        + "用户手动创建的同位置断点不会被删除。是否继续？",
                "确认清理全部 Atlas 断点",
                Messages.getQuestionIcon()
        );
        if (answer == Messages.YES) {
            removeManagedBreakpoints(true);
        }
    }

    /**
     * 切换全部 Atlas 断点启用状态并刷新管理摘要。
     *
     * @param enabled 是否启用
     */
    private void updateManagedBreakpointEnabled(boolean enabled) {
        AtlasBreakpointManager.ManageResult result = AtlasBreakpointManager.setManagedBreakpointsEnabled(
                project,
                enabled
        );
        updateActionState();
        Messages.showInfoMessage(
                project,
                "已" + (enabled ? "启用 " : "禁用 ") + result.affected()
                        + " 个 Atlas 断点。",
                "Source Atlas 断点管理"
        );
    }

    /**
     * 清理当前专题或全部 Atlas 断点，并保留用户原本创建的断点。
     *
     * @param allTopics 是否清理全部专题
     */
    private void removeManagedBreakpoints(boolean allTopics) {
        AtlasTopic topic = topicList.getSelectedValue();
        String topicId = allTopics || topic == null ? null : topic.topicId();
        AtlasBreakpointManager.ManageResult result = AtlasBreakpointManager.removeManagedBreakpoints(
                project,
                topicId
        );
        updateActionState();
        Messages.showInfoMessage(
                project,
                "已清理 " + result.affected() + " 个断点，剩余 " + result.remaining()
                        + " 个 Atlas 断点。",
                "Source Atlas 断点管理"
        );
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
        String versionQuery = selectedTopicVersion == null || selectedTopicVersion.projectMajor() == null
                ? ""
                : "&version=" + selectedTopicVersion.projectMajor();
        String path = "/jdk/version-comparison/?topic=" + topic.versionComparison().id() + versionQuery;
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
            breakpoints.stream()
                    .filter(breakpoint -> !result.unresolved().contains(breakpoint.method()))
                    .filter(breakpoint -> !result.failed().contains(breakpoint.method()))
                    .forEach(breakpoint -> learningProgress.recordBreakpoint(
                            topic.topicId(),
                            breakpoint.method(),
                            topic.primaryVersion()
                    ));
            updateNavigationTabTitles();
            updateActionState();
            String unresolved = result.unresolved().isEmpty()
                    ? ""
                    : "\n未找到：" + summarizeUnresolved(result.unresolved());
            String failed = result.failed().isEmpty()
                    ? ""
                    : "\n创建失败：" + summarizeUnresolved(result.failed());
            Messages.showInfoMessage(
                    project,
                    "新增 " + result.added() + " 个，已存在 " + result.existing() + " 个，未解析 "
                            + result.unresolved().size() + " 个，创建失败 " + result.failed().size()
                            + " 个。" + unresolved + failed,
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
                    learningProgress.recordBreakpoint(
                            topic.topicId(),
                            breakpoint.method(),
                            topic.primaryVersion()
                    );
                    updateNavigationTabTitles();
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
            if (navigated) {
                recordEntryVisit(topic, entryPoint);
            }
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
     * 工具窗口关闭时释放 JCEF 浏览器资源。
     */
    @Override
    public void dispose() {
        // 2026-08-26：持续跟随编辑器的计时器已经取消，不再需要在关闭时停止。
        // contextTimer.stop();
        if (tutorialBrowser != null) {
            tutorialBrowser.dispose();
        }
    }

    /**
     * 渲染专题标题和主要版本。
     */
    private static final class TopicRenderer extends JPanel implements ListCellRenderer<AtlasTopic> {

        private final JBLabel titleLabel = new JBLabel();
        private final JBLabel metadataLabel = new JBLabel();

        /**
         * 创建突出专题名称、弱化版本元数据的两行列表项。
         */
        private TopicRenderer() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setOpaque(true);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
            titleLabel.setIcon(AllIcons.Nodes.Class);
            titleLabel.setIconTextGap(JBUI.scale(6));
            metadataLabel.setFont(metadataLabel.getFont().deriveFont(Font.PLAIN, 12f));
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            metadataLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(titleLabel);
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
            add(metadataLabel);
            setBorder(JBUI.Borders.empty(8, 8));
        }

        /**
         * 为专题列表展示标题、技术类型、版本和内容规模。
         *
         * @param list     当前列表
         * @param value    专题
         * @param index    行号
         * @param selected 是否选中
         * @param hasFocus 是否拥有焦点
        */
        @Override
        public Component getListCellRendererComponent(
                @NotNull JList<? extends AtlasTopic> list,
                AtlasTopic value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            String kind = value.primaryVersion().startsWith("OpenJDK")
                    ? "JDK"
                    : value.primaryVersion().startsWith("Spring Boot")
                    ? "Spring Boot"
                    : "Spring Framework";
            titleLabel.setText(StringUtil.shortenTextWithEllipsis(value.title(), 58, 0));
            metadataLabel.setText(kind + "  ·  " + value.primaryVersion()
                    + "  ·  " + value.entryPoints().size() + " 个入口"
                    + "  ·  " + value.breakpoints().size() + " 个断点");
            setToolTipText(value.title() + " · " + value.primaryVersion());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            ColorPair colors = ColorPair.from(list, selected);
            titleLabel.setForeground(colors.foreground());
            metadataLabel.setForeground(colors.secondaryForeground());
            return this;
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
            methodLabel.setIcon(AllIcons.Nodes.Method);
            methodLabel.setIconTextGap(JBUI.scale(6));
            purposeLabel.setFont(purposeLabel.getFont().deriveFont(Font.PLAIN, 12f));
            methodLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            purposeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(methodLabel);
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)));
            add(purposeLabel);
            setBorder(JBUI.Borders.empty(8, 8));
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
            purposeLabel.setText("阅读重点  ·  " + StringUtil.shortenTextWithEllipsis(purpose, 128, 0));
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
            methodLabel.setIcon(AllIcons.Debugger.Db_set_breakpoint);
            methodLabel.setIconTextGap(JBUI.scale(6));
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
            setBorder(JBUI.Borders.empty(8, 8));
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
            scenarioLabel.setText("场景  ·  " + StringUtil.shortenTextWithEllipsis(scenario, 118, 0));
            variablesLabel.setText("观察  ·  " + StringUtil.shortenTextWithEllipsis(variables, 118, 0)
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
