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
            consumer.accept(new AddResult(0, 0, List.of()));
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
    private static Resolution resolveLocations(
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
        return new Resolution(List.copyOf(locations), List.copyOf(unresolved));
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
        VirtualFile file = method.getContainingFile().getVirtualFile();
        Document document = PsiDocumentManager.getInstance(project).getDocument(method.getContainingFile());
        if (file == null || document == null) {
            return null;
        }

        PsiElement lineElement = method;
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            PsiStatement[] statements = body.getStatements();
            if (statements.length > 0) {
                lineElement = statements[0];
            }
        }
        int safeOffset = Math.min(lineElement.getTextOffset(), Math.max(document.getTextLength() - 1, 0));
        return new BreakpointLocation(file, document.getLineNumber(safeOffset), signature);
    }

    /**
     * 在界面线程检查已有断点并添加新 Java 行断点。
     *
     * @param project    当前项目
     * @param resolution 后台解析结果
     * @param consumer   结果回调
     */
    private static void addResolved(Project project, Resolution resolution, Consumer<AddResult> consumer) {
        XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
        JavaLineBreakpointType breakpointType = com.intellij.xdebugger.XDebuggerUtil.getInstance()
                .findBreakpointType(JavaLineBreakpointType.class);
        if (breakpointType == null) {
            consumer.accept(new AddResult(0, 0, resolution.unresolved()));
            return;
        }

        int added = 0;
        int existing = 0;
        for (BreakpointLocation location : resolution.locations()) {
            if (containsLineBreakpoint(manager, location.file().getUrl(), location.line())) {
                existing++;
                continue;
            }
            addLineBreakpoint(manager, breakpointType, location);
            added++;
        }
        consumer.accept(new AddResult(added, existing, resolution.unresolved()));
    }

    /**
     * 创建 Java 行断点默认属性并交给 IDEA 断点管理器持久化。
     *
     * @param manager        断点管理器
     * @param breakpointType Java 行断点类型
     * @param location       文件位置
     */
    private static void addLineBreakpoint(
            XBreakpointManager manager,
            JavaLineBreakpointType breakpointType,
            BreakpointLocation location
    ) {
        JavaLineBreakpointProperties properties = breakpointType.createBreakpointProperties(
                location.file(),
                location.line()
        );
        manager.addLineBreakpoint(
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
        for (XBreakpoint<?> breakpoint : manager.getAllBreakpoints()) {
            if (breakpoint instanceof XLineBreakpoint<?> lineBreakpoint
                    && fileUrl.equals(lineBreakpoint.getFileUrl())
                    && line == lineBreakpoint.getLine()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 保存一次断点添加的统计与未解析签名。
     *
     * @param added      新增数量
     * @param existing   已存在数量
     * @param unresolved 未找到的方法签名
     */
    public record AddResult(int added, int existing, List<String> unresolved) {
    }

    /**
     * 保存一个已解析的断点位置。
     *
     * @param file      源文件
     * @param line      零基行号
     * @param signature 原始签名
     */
    private record BreakpointLocation(VirtualFile file, int line, String signature) {
    }

    /**
     * 保存后台批量解析结果。
     *
     * @param locations 已解析位置
     * @param unresolved 未解析签名
     */
    private record Resolution(List<BreakpointLocation> locations, List<String> unresolved) {
    }
}
