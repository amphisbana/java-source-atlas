package io.github.javasourceatlas.idea.debug;

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.psi.AtlasPsiResolver;
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 将专题推荐断点解析为 IDEA Java 行断点。
 */
public final class AtlasBreakpointManager {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasBreakpointManager() {
    }

    /**
     * 在后台解析一个或多个推荐断点，并在界面线程去重后添加。
     *
     * @param project     当前项目
     * @param parent      控制任务生命周期的父级对象
     * @param topic       当前专题
     * @param breakpoints 待添加的推荐断点
     * @param consumer    添加结果回调
     */
    public static void addBreakpointsAsync(
            Project project,
            Disposable parent,
            AtlasTopic topic,
            List<AtlasBreakpoint> breakpoints,
            Consumer<AddResult> consumer
    ) {
        if (topic == null || breakpoints == null || breakpoints.isEmpty()) {
            consumer.accept(new AddResult(0, 0, List.of(), List.of()));
            return;
        }
        ReadAction.nonBlocking(
                        (Callable<Resolution>) () -> resolveLocations(project, topic, breakpoints)
                )
                .withDocumentsCommitted(project)
                .inSmartMode(project)
                .expireWith(parent)
                .finishOnUiThread(ModalityState.any(), resolution -> addResolved(project, resolution, consumer))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在读锁中解析方法与第一条可执行语句所在行。
     *
     * @param project     当前项目
     * @param topic       当前专题
     * @param breakpoints 推荐断点
     * @return 已解析位置和未解析签名
     */
    static Resolution resolveLocations(
            Project project,
            AtlasTopic topic,
            List<AtlasBreakpoint> breakpoints
    ) {
        List<BreakpointLocation> locations = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (AtlasBreakpoint breakpoint : breakpoints) {
            AtlasPsiResolver.ResolvedTarget target = AtlasPsiResolver.resolveTarget(
                    project,
                    topic,
                    breakpoint.method(),
                    breakpoint.sourceClass(),
                    true
            );
            BreakpointLocation location = target == null || target.psiMethod() == null
                    ? null
                    : toLocation(project, target.psiMethod(), breakpoint.method());
            if (location == null) {
                unresolved.add(breakpoint.method());
            } else {
                locations.add(location);
            }
        }
        return new Resolution(topic.topicId(), List.copyOf(locations), List.copyOf(unresolved));
    }

    /**
     * 把 PSI 方法转换为文件和零基行号，优先停在方法体第一条语句。
     *
     * @param project   当前项目
     * @param method    目标方法
     * @param signature 原始签名
     * @return 断点位置；文件或文档不可用时返回 null
     */
    private static BreakpointLocation toLocation(Project project, PsiMethod method, String signature) {
        PsiMethod sourceMethod = sourceMethod(method);
        PsiFile sourceFile = sourceMethod.getContainingFile();
        VirtualFile file = sourceFile == null ? null : sourceFile.getVirtualFile();
        Document document = sourceFile == null
                ? null
                : PsiDocumentManager.getInstance(project).getDocument(sourceFile);
        if (file == null || document == null) {
            return null;
        }

        // 2026-08-24：原逻辑在编译 PSI 没有方法体时回退到方法声明，创建的行断点无法在运行时命中。
        // PsiElement lineElement = method;
        // PsiCodeBlock body = method.getBody();
        // if (body != null) {
        //     PsiStatement[] statements = body.getStatements();
        //     if (statements.length > 0) {
        //         lineElement = statements[0];
        //     }
        // }
        PsiStatement lineElement = firstExecutableStatement(sourceMethod);
        if (lineElement == null || document.getTextLength() == 0) {
            return null;
        }
        int safeOffset = Math.min(lineElement.getTextOffset(), Math.max(document.getTextLength() - 1, 0));
        return new BreakpointLocation(file, document.getLineNumber(safeOffset), signature);
    }

    /**
     * 将编译方法切换到已经附加的源码导航元素，确保后续能够读取真实方法体。
     *
     * @param method PSI 解析到的方法
     * @return 源码方法；没有独立导航元素时返回原方法
     */
    static PsiMethod sourceMethod(PsiMethod method) {
        PsiElement navigationElement = method.getNavigationElement();
        return navigationElement instanceof PsiMethod navigationMethod
                ? navigationMethod
                : method;
    }

    /**
     * 返回源码方法体中的第一条可执行语句；声明、抽象方法和空方法均不生成断点。
     *
     * @param sourceMethod 已经切换到源码的 PSI 方法
     * @return 第一条语句；方法体缺失或为空时返回 null
     */
    static PsiStatement firstExecutableStatement(PsiMethod sourceMethod) {
        PsiCodeBlock body = sourceMethod.getBody();
        PsiStatement[] statements = body == null ? PsiStatement.EMPTY_ARRAY : body.getStatements();
        return statements.length == 0 ? null : statements[0];
    }

    /**
     * 在界面线程检查已有断点并添加新 Java 行断点。
     *
     * @param project    当前项目
     * @param resolution 后台解析结果
     * @param consumer   结果回调
     */
    static void addResolved(Project project, Resolution resolution, Consumer<AddResult> consumer) {
        XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
        JavaLineBreakpointType breakpointType = com.intellij.xdebugger.XDebuggerUtil.getInstance()
                .findBreakpointType(JavaLineBreakpointType.class);
        if (breakpointType == null) {
            consumer.accept(new AddResult(
                    0,
                    0,
                    resolution.unresolved(),
                    resolution.locations().stream().map(BreakpointLocation::signature).toList()
            ));
            return;
        }

        int added = 0;
        int existing = 0;
        List<String> failed = new ArrayList<>();
        AtlasBreakpointState state = AtlasBreakpointState.getInstance(project);
        for (BreakpointLocation location : resolution.locations()) {
            if (containsLineBreakpoint(manager, location.file().getUrl(), location.line())) {
                existing++;
                // 2026-09-02：原逻辑只计数后跳过，用户已有断点命中时无法进入 Atlas 调试引导。
                // continue;
                state.registerReference(
                        resolution.topicId(),
                        location.file().getUrl(),
                        location.line(),
                        location.signature()
                );
                continue;
            }
            // 2026-08-24：原逻辑只创建断点，不记录归属，插件之后无法只清理自己创建的断点。
            // addLineBreakpoint(manager, breakpointType, location);
            XLineBreakpoint<JavaLineBreakpointProperties> created = addLineBreakpoint(
                    manager,
                    breakpointType,
                    location
            );
            if (created != null) {
                state.register(
                        resolution.topicId(),
                        location.file().getUrl(),
                        location.line(),
                        location.signature()
                );
                added++;
            } else {
                // 2026-08-26：原逻辑无论 IDEA 是否真正创建断点都会递增 added，导致 UI 和 Debug 流程误判成功。
                // added++;
                failed.add(location.signature());
            }
        }
        consumer.accept(new AddResult(added, existing, resolution.unresolved(), List.copyOf(failed)));
    }

    /**
     * 创建 Java 行断点默认属性并交给 IDEA 断点管理器持久化。
     *
     * @param manager        断点管理器
     * @param breakpointType Java 行断点类型
     * @param location       文件位置
     */
    private static XLineBreakpoint<JavaLineBreakpointProperties> addLineBreakpoint(
            XBreakpointManager manager,
            JavaLineBreakpointType breakpointType,
            BreakpointLocation location
    ) {
        JavaLineBreakpointProperties properties = breakpointType.createBreakpointProperties(
                location.file(),
                location.line()
        );
        return manager.addLineBreakpoint(
                breakpointType,
                location.file().getUrl(),
                location.line(),
                properties
        );
    }

    /**
     * 检查同一文件同一行是否已有任意行断点，避免重复创建。
     *
     * @param manager 断点管理器
     * @param fileUrl 文件 URL
     * @param line    零基行号
     * @return 是否已存在
     */
    private static boolean containsLineBreakpoint(XBreakpointManager manager, String fileUrl, int line) {
        return findLineBreakpoint(manager, fileUrl, line) != null;
    }

    /**
     * 查找同一文件同一行的现有行断点。
     *
     * @param manager 断点管理器
     * @param fileUrl 文件 URL
     * @param line    零基行号
     * @return 行断点；不存在时返回 null
     */
    private static XLineBreakpoint<?> findLineBreakpoint(XBreakpointManager manager, String fileUrl, int line) {
        for (XBreakpoint<?> breakpoint : manager.getAllBreakpoints()) {
            if (breakpoint instanceof XLineBreakpoint<?> lineBreakpoint
                    && fileUrl.equals(lineBreakpoint.getFileUrl())
                    && line == lineBreakpoint.getLine()) {
                return lineBreakpoint;
            }
        }
        return null;
    }

    /**
     * 启用或禁用插件创建的断点，并清理用户已经手动删除的过期归属记录。
     *
     * @param project 当前项目
     * @param enabled 目标启用状态
     * @return 本次管理结果
     */
    public static ManageResult setManagedBreakpointsEnabled(Project project, boolean enabled) {
        XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
        AtlasBreakpointState state = AtlasBreakpointState.getInstance(project);
        int affected = 0;
        int stale = 0;
        for (AtlasBreakpointState.ManagedBreakpoint location : state.locations()) {
            XLineBreakpoint<?> breakpoint = findLineBreakpoint(manager, location.fileUrl, location.line);
            if (breakpoint == null) {
                state.removeLocation(location.fileUrl, location.line);
                stale++;
                continue;
            }
            if (!location.owned) {
                continue;
            }
            breakpoint.setEnabled(enabled);
            affected++;
        }
        return new ManageResult(affected, stale, state.locations().size(), enabled);
    }

    /**
     * 删除当前专题或全部由插件创建的断点，不触碰复用的用户断点。
     *
     * @param project 当前项目
     * @param topicId 专题编号；为空时删除全部 Atlas 断点
     * @return 本次管理结果
     */
    public static ManageResult removeManagedBreakpoints(Project project, String topicId) {
        XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
        AtlasBreakpointState state = AtlasBreakpointState.getInstance(project);
        int removed = 0;
        int stale = 0;
        for (AtlasBreakpointState.ManagedBreakpoint location : state.locations()) {
            if (topicId != null && !topicId.isBlank() && !topicId.equals(location.topicId)) {
                continue;
            }
            XLineBreakpoint<?> breakpoint = findLineBreakpoint(manager, location.fileUrl, location.line);
            if (breakpoint == null) {
                stale++;
            // 2026-09-02：原逻辑会删除所有已登记位置，复用用户断点后必须只移除 Atlas 自己创建的断点。
            // } else {
            } else if (location.owned) {
                manager.removeBreakpoint(breakpoint);
                removed++;
            }
            state.removeLocation(location.fileUrl, location.line);
        }
        return new ManageResult(removed, stale, state.locations().size(), true);
    }

    /**
     * 汇总当前仍然存在的 Atlas 断点数量和启用状态。
     *
     * @param project 当前项目
     * @return 断点管理摘要
     */
    public static ManagedSummary managedSummary(Project project) {
        XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
        AtlasBreakpointState state = AtlasBreakpointState.getInstance(project);
        int count = 0;
        boolean allEnabled = true;
        for (AtlasBreakpointState.ManagedBreakpoint location : state.locations()) {
            XLineBreakpoint<?> breakpoint = findLineBreakpoint(manager, location.fileUrl, location.line);
            if (breakpoint == null) {
                state.removeLocation(location.fileUrl, location.line);
                continue;
            }
            count++;
            allEnabled &= breakpoint.isEnabled();
        }
        return new ManagedSummary(count, count == 0 || allEnabled);
    }

    /**
     * 保存一次断点添加的统计、未解析签名和创建失败签名。
     *
     * @param added      新增数量
     * @param existing   已存在数量
     * @param unresolved 未找到的方法签名
     * @param failed     IDEA 创建断点失败的方法签名
     */
    public record AddResult(int added, int existing, List<String> unresolved, List<String> failed) {

        /**
         * 保留旧调用方的三参数构造方式，旧调用方默认没有创建失败明细。
         *
         * @param added      新增数量
         * @param existing   已存在数量
         * @param unresolved 未找到的方法签名
         */
        public AddResult(int added, int existing, List<String> unresolved) {
            this(added, existing, unresolved, List.of());
        }

        /**
         * 规范化异步回调中的明细集合，避免调用方修改结果对象。
         */
        public AddResult {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }
    }

    /**
     * 保存一次 Atlas 断点启停或清理操作的结果。
     *
     * @param affected  实际修改的断点数
     * @param stale     清理的过期归属记录数
     * @param remaining 当前仍由 Atlas 管理的断点数
     * @param enabled   操作后的目标启用状态
     */
    public record ManageResult(int affected, int stale, int remaining, boolean enabled) {
    }

    /**
     * 保存当前 Atlas 断点数量和整体启用状态。
     *
     * @param count      当前断点数
     * @param allEnabled 是否全部启用
     */
    public record ManagedSummary(int count, boolean allEnabled) {
    }

    /**
     * 保存一个已解析的断点位置。
     *
     * @param file      源文件
     * @param line      零基行号
     * @param signature 原始签名
     */
    record BreakpointLocation(VirtualFile file, int line, String signature) {
    }

    /**
     * 保存后台批量解析结果。
     *
     * @param locations 已解析位置
     * @param unresolved 未解析签名
     */
    record Resolution(String topicId, List<BreakpointLocation> locations, List<String> unresolved) {
    }
}
