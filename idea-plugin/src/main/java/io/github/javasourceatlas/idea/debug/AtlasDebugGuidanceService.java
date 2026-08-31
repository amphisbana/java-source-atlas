package io.github.javasourceatlas.idea.debug;

import com.intellij.openapi.Disposable;
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

import java.util.Arrays;
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
    private final Set<XDebugSession> attachedSessions = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Consumer<Optional<AtlasDebugGuidance>>> listeners =
            new CopyOnWriteArrayList<>();
    private volatile AtlasDebugGuidance currentGuidance;

    /**
     * 订阅新建 Debug 进程，并接管服务创建前已经存在的会话。
     *
     * @param project 当前项目
     */
    public AtlasDebugGuidanceService(Project project) {
        this.project = project;
        this.index = ApplicationManager.getApplication().getService(AtlasIndexService.class);
        this.breakpointState = AtlasBreakpointState.getInstance(project);
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
     * 给一个 Debug 会话绑定暂停、栈帧和结束事件，重复会话只绑定一次。
     *
     * @param session IDEA Debug 会话
     */
    private void attachSession(XDebugSession session) {
        if (session == null || !attachedSessions.add(session)) {
            return;
        }
        session.addSessionListener(new XDebugSessionListener() {
            /**
             * 暂停后根据执行位置解析 Atlas 证据。
             */
            @Override
            public void sessionPaused() {
                updateFromSession(session);
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
                publish(null);
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
        AtlasDebugGuidance guidance = position == null
                ? null
                : AtlasDebugGuidanceResolver.resolve(
                        index,
                        breakpointState,
                        position.getFile().getUrl(),
                        position.getLine()
                ).orElse(null);
        publish(guidance);
    }

    /**
     * 在 IDEA 界面线程发布最新引导，并跳过内容完全相同的重复栈帧事件。
     *
     * @param guidance 新引导；为空表示当前不是 Atlas 断点
     */
    private void publish(AtlasDebugGuidance guidance) {
        if (java.util.Objects.equals(currentGuidance, guidance)) {
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
        listeners.clear();
        currentGuidance = null;
    }
}
