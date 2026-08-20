package io.github.javasourceatlas.idea.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.ide.CopyPasteManager;
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
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.lab.AtlasLabLauncher;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEditorContext;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.model.AtlasTopicRelation;
import io.github.javasourceatlas.idea.model.AtlasVersionInfo;
import io.github.javasourceatlas.idea.navigation.AtlasSourceNavigator;
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
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * 展示当前源码上下文、专题入口、推荐断点和版本信息。
 */
public final class AtlasToolWindowPanel extends SimpleToolWindowPanel implements Disposable {

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
    private final JButton viewBreakpointExplanationButton = new JButton("查看断点讲解");
    private final JButton favoriteTopicButton = new JButton("收藏当前专题");
    private final JButton clearRecentButton = new JButton("清空最近阅读");
    private final JButton backToNavigationButton = new JButton("返回专题导航");
    private final JButton tutorialExternalDocumentationButton = new JButton("浏览器打开");
    private final JButton enterNextTopicButton = new JButton("进入下一专题");
    private final Timer contextTimer;

    private AtlasEmbeddedBrowser tutorialBrowser;
    private String lastContextKey = "";
    private AtlasEditorContext editorContext = new AtlasEditorContext(null, null, null, null);
    private boolean contextRefreshPending;
    private boolean updatingProgressControls;

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
        viewBreakpointExplanationButton.addActionListener(ignored -> viewBreakpointExplanation());
        favoriteTopicButton.addActionListener(ignored -> toggleFavoriteTopic());
        clearRecentButton.addActionListener(ignored -> clearRecentTopics());
        backToNavigationButton.addActionListener(ignored -> tabs.setSelectedIndex(0));
        tutorialExternalDocumentationButton.addActionListener(ignored -> openDocumentationExternally());
        readMainCheckBox.addActionListener(ignored -> saveLearningProgress());
        ranLabCheckBox.addActionListener(ignored -> saveLearningProgress());
        enterNextTopicButton.addActionListener(ignored -> enterRecommendedNextTopic());

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
        viewBreakpointExplanationButton.setToolTipText("打开与当前断点最匹配的源码入口讲解");
        favoriteTopicButton.setToolTipText("把当前专题加入或移出本地收藏");
        clearRecentButton.setToolTipText("清空本地最近阅读记录，不影响学习进度");
        enterNextTopicButton.setToolTipText("切换到索引推荐的下一专题");
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
                viewBreakpointExplanationButton
        ));
        navigationTabs.addTab("学习路径", createLearningSection());
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
        if (topic != null && navigationTabs.getSelectedIndex() == 3) {
            actionHintLabel.setText("确认阅读与实验进度，再沿推荐关系进入下一专题");
        } else if (topic != null && navigationTabs.getSelectedIndex() == 2) {
            actionHintLabel.setText(breakpoint == null
                    ? "双击列表项添加单个断点；也可以一次添加全部断点并打开 Lab"
                    : "当前断点已选中，双击即可添加，或启动 Debug Lab 观察变量变化");
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
        if (topic == null) {
            viewBreakpointExplanationButton.setEnabled(false);
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
            methodLabel.setText(value.method());
            scenarioLabel.setText("观察场景：" + StringUtil.shortenTextWithEllipsis(scenario, 118, 0));
            variablesLabel.setText("观察变量：" + StringUtil.shortenTextWithEllipsis(variables, 118, 0));
            setToolTipText(value.method() + " · 观察场景：" + scenario
                    + " · 观察变量：" + variables + " · 双击添加当前断点");
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
