package io.github.javasourceatlas.idea.debug;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerManagerListener;
import com.intellij.xdebugger.XSourcePosition;
import io.github.javasourceatlas.idea.index.AtlasIndexService;
import io.github.javasourceatlas.idea.learning.AtlasLearningProgressState;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.version.AtlasTopicVersionResolver;
import io.github.javasourceatlas.idea.version.AtlasVersionDetector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 监听项目 Debug 会话，在命中 Atlas 断点时发布引导信息。
 */
@Service(Service.Level.PROJECT)
public final class AtlasDebugGuidanceService implements Disposable {

    private final Project project;
    private final AtlasIndexService index;
    private final AtlasBreakpointState breakpointState;
    private final AtlasLearningProgressState learningProgress;
    private final Set<XDebugSession> attachedSessions = ConcurrentHashMap.newKeySet();
    private final Map<XDebugSession, SessionTrace> sessionTraces = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Optional<AtlasDebugGuidance>>> listeners =
            new CopyOnWriteArrayList<>();
    private volatile AtlasDebugGuidance currentGuidance;
    private volatile XDebugSession currentSession;
    private volatile AtlasDebugSessionReport latestReport = AtlasDebugSessionReport.empty();

    /**
     * 订阅新建 Debug 进程，并接管服务创建前已经存在的会话。
     *
     * @param project 当前项目
     */
    public AtlasDebugGuidanceService(Project project) {
        this.project = project;
        this.index = ApplicationManager.getApplication().getService(AtlasIndexService.class);
        this.breakpointState = AtlasBreakpointState.getInstance(project);
        this.learningProgress = AtlasLearningProgressState.getInstance();
        project.getMessageBus().connect(this).subscribe(
                XDebuggerManager.TOPIC,
                new XDebuggerManagerListener() {
                    /**
                     * 新进程启动后立即监听暂停和栈帧变化。
                     *
                     * @param debugProcess 新建调试进程
                     */
                    @Override
                    public void processStarted(XDebugProcess debugProcess) {
                        attachSession(debugProcess.getSession());
                    }
                }
        );
        Arrays.stream(XDebuggerManager.getInstance(project).getDebugSessions()).forEach(this::attachSession);
    }

    /**
     * 取得当前项目的调试引导服务。
     *
     * @param project 当前项目
     * @return 项目级服务
     */
    public static AtlasDebugGuidanceService getInstance(Project project) {
        return project.getService(AtlasDebugGuidanceService.class);
    }

    /**
     * 注册界面监听器，并立即回放当前暂停位置。
     *
     * @param parent   监听器生命周期
     * @param listener 引导变化回调
     */
    public void addListener(Disposable parent, Consumer<Optional<AtlasDebugGuidance>> listener) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
        listener.accept(Optional.ofNullable(currentGuidance));
    }

    /**
     * 返回当前或最近一次 Debug 会话的 Atlas 命中路径快照。
     *
     * @return 调试会话报告
     */
    public AtlasDebugSessionReport latestReport() {
        return latestReport;
    }

    /**
     * 继续当前命中 Atlas 断点的 Debug 会话。
     *
     * @return 是否找到仍处于暂停状态的会话
     */
    public boolean resumeCurrentSession() {
        XDebugSession session = currentSession;
        if (session == null || !session.isSuspended()) {
            return false;
        }
        Runnable resumeAction = session::resume;
        if (ApplicationManager.getApplication().isDispatchThread()) {
            resumeAction.run();
        } else {
            ApplicationManager.getApplication().invokeLater(resumeAction, ModalityState.any());
        }
        return true;
    }

    /**
     * 给一个 Debug 会话绑定暂停、栈帧和结束事件，重复会话只绑定一次。
     *
     * @param session IDEA Debug 会话
     */
    private void attachSession(XDebugSession session) {
        if (session == null || !attachedSessions.add(session)) {
            return;
        }
        sessionTraces.putIfAbsent(session, new SessionTrace(Instant.now().toString()));
        session.addSessionListener(new XDebugSessionListener() {
            /**
             * 暂停后根据执行位置解析 Atlas 证据。
             */
            @Override
            public void sessionPaused() {
                updateFromSession(session);
            }

            /**
             * 会话恢复后允许下一次在相同源码位置形成新的真实命中记录。
             */
            @Override
            public void sessionResumed() {
                SessionTrace trace = sessionTraces.get(session);
                if (trace != null) {
                    trace.markResumed();
                    if (trace.hasVisits()) {
                        latestReport = trace.snapshot(true);
                    }
                }
                publish(null, true);
            }

            /**
             * 用户切换栈帧后同步当前选中的源码位置。
             */
            @Override
            public void stackFrameChanged() {
                if (session.isSuspended()) {
                    updateFromSession(session);
                }
            }

            /**
             * 会话结束时清理已经展示的调试提示。
             */
            @Override
            public void sessionStopped() {
                attachedSessions.remove(session);
                // 2026-09-02：原逻辑只清空当前引导，没有保存本次 Debug 实际经过的断点路径。
                // publish(null);
                SessionTrace trace = sessionTraces.remove(session);
                if (trace != null && trace.hasVisits()) {
                    latestReport = trace.finish(Instant.now().toString());
                }
                if (currentSession == session) {
                    currentSession = null;
                }
                publish(null, true);
            }
        }, this);
        if (session.isSuspended()) {
            updateFromSession(session);
        }
    }

    /**
     * 把 Debug 会话执行位置映射为 Atlas 调试引导；普通断点会清空旧提示。
     *
     * @param session 当前暂停会话
     */
    private void updateFromSession(XDebugSession session) {
        XSourcePosition position = session.getCurrentPosition();
        // 2026-09-02：原逻辑不传项目 JDK 版本，只能用基线签名解析暂停位置。
        // AtlasDebugGuidanceResolver.resolve(index, breakpointState,
        //         position.getFile().getUrl(), position.getLine());
        AtlasDebugGuidance guidance = position == null
                ? null
                : AtlasDebugGuidanceResolver.resolve(
                        index,
                        breakpointState,
                        position.getFile().getUrl(),
                        position.getLine(),
                        AtlasVersionDetector.projectJdkVersion(project)
                ).orElse(null);
        boolean visitAdded = false;
        if (guidance != null && position != null) {
            currentSession = session;
            AtlasTopic topic = versionTopic(guidance.topicId());
            learningProgress.recordVerifiedEvidence(
                    guidance.topicId(),
                    guidance.evidenceId(),
                    guidance.breakpointMethod(),
                    topic == null ? "" : topic.primaryVersion()
            );
            SessionTrace trace = sessionTraces.computeIfAbsent(
                    session,
                    ignored -> new SessionTrace(Instant.now().toString())
            );
            visitAdded = trace.append(guidance, sourceLocation(position), Instant.now().toString());
            latestReport = trace.snapshot(true);
            if (visitAdded) {
                navigateToPosition(position);
            }
        }
        // 2026-09-02：原逻辑只发布当前引导，不会刷新命中次数、证据进度和会话摘要。
        // publish(guidance);
        publish(guidance, visitAdded);
    }

    /**
     * 取得当前项目 JDK 对应的专题视图，供已验证证据保存准确的版本基线。
     *
     * @param topicId 专题编号
     * @return 版本专题；索引缺失时为空
     */
    private AtlasTopic versionTopic(String topicId) {
        AtlasTopic baseline = index.findById(topicId).orElse(null);
        return baseline == null
                ? null
                : AtlasTopicVersionResolver.resolve(
                        baseline,
                        AtlasVersionDetector.projectJdkVersion(project)
                ).topic();
    }

    /**
     * 命中新的 Atlas 断点后自动打开实际暂停行，让源码和引导信息保持同步。
     *
     * @param position 调试器当前源码位置
     */
    private void navigateToPosition(XSourcePosition position) {
        Runnable navigation = () -> {
            if (!project.isDisposed() && position.getFile().isValid()) {
                new OpenFileDescriptor(project, position.getFile(), position.getLine(), 0).navigate(true);
            }
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            navigation.run();
        } else {
            ApplicationManager.getApplication().invokeLater(navigation, ModalityState.any());
        }
    }

    /**
     * 将虚拟文件路径和零基行号格式化为用户熟悉的一基源码位置。
     *
     * @param position 调试器当前源码位置
     * @return 文件路径与行号
     */
    private String sourceLocation(XSourcePosition position) {
        return position.getFile().getPresentableUrl() + ":" + (position.getLine() + 1);
    }

    /**
     * 在 IDEA 界面线程发布最新引导，并跳过内容完全相同的重复栈帧事件。
     *
     * @param guidance 新引导；为空表示当前不是 Atlas 断点
     * @param force    是否即使引导内容相同也刷新会话统计
     */
    private void publish(AtlasDebugGuidance guidance, boolean force) {
        if (!force && java.util.Objects.equals(currentGuidance, guidance)) {
            return;
        }
        currentGuidance = guidance;
        Runnable notification = () -> {
            if (project.isDisposed()) {
                return;
            }
            Optional<AtlasDebugGuidance> value = Optional.ofNullable(guidance);
            listeners.forEach(listener -> listener.accept(value));
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            notification.run();
        } else {
            ApplicationManager.getApplication().invokeLater(notification, ModalityState.any());
        }
    }

    /**
     * 项目关闭时释放会话和界面监听器引用。
     */
    @Override
    public void dispose() {
        attachedSessions.clear();
        sessionTraces.clear();
        listeners.clear();
        currentGuidance = null;
        currentSession = null;
        latestReport = AtlasDebugSessionReport.empty();
    }

    /**
     * 维护单个 Debug 会话的有序断点路径，并抑制同一次暂停产生的重复栈帧事件。
     */
    private static final class SessionTrace {

        private final String startedAt;
        private final List<AtlasDebugSessionReport.Visit> visits = new ArrayList<>();
        private String pauseKey = "";

        /**
         * 创建新的调试会话轨迹。
         *
         * @param startedAt 会话开始时间
         */
        private SessionTrace(String startedAt) {
            this.startedAt = startedAt;
        }

        /**
         * 追加一次真实暂停；同一次暂停触发的栈帧切换只记录一次。
         *
         * @param guidance      当前调试引导
         * @param sourceLocation 源码位置
         * @param visitedAt     命中时间
         * @return 是否新增了访问记录
         */
        private synchronized boolean append(
                AtlasDebugGuidance guidance,
                String sourceLocation,
                String visitedAt
        ) {
            String currentKey = guidance.topicId() + "|" + guidance.breakpointMethod() + "|" + sourceLocation;
            if (currentKey.equals(pauseKey)) {
                return false;
            }
            pauseKey = currentKey;
            visits.add(new AtlasDebugSessionReport.Visit(
                    guidance.topicId(),
                    guidance.topicTitle(),
                    guidance.breakpointMethod(),
                    guidance.evidenceId(),
                    guidance.claim(),
                    guidance.expectedOutcome(),
                    sourceLocation,
                    visitedAt
            ));
            return true;
        }

        /**
         * 会话继续运行时清除暂停标识，使后续再次命中同一行仍会被记录。
         */
        private synchronized void markResumed() {
            pauseKey = "";
        }

        /**
         * 判断当前会话是否已经形成至少一条 Atlas 断点记录。
         *
         * @return 是否存在访问记录
         */
        private synchronized boolean hasVisits() {
            return !visits.isEmpty();
        }

        /**
         * 创建当前进行中会话的不可变快照。
         *
         * @param active 会话是否进行中
         * @return 会话报告
         */
        private synchronized AtlasDebugSessionReport snapshot(boolean active) {
            return new AtlasDebugSessionReport(active, startedAt, "", List.copyOf(visits));
        }

        /**
         * 结束会话并生成带结束时间的最终报告。
         *
         * @param endedAt 会话结束时间
         * @return 最终会话报告
         */
        private synchronized AtlasDebugSessionReport finish(String endedAt) {
            return new AtlasDebugSessionReport(false, startedAt, endedAt, List.copyOf(visits));
        }
    }
}
