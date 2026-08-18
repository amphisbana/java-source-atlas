package io.github.javasourceatlas.idea.navigation;

import com.intellij.ide.util.EditSourceUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.psi.AtlasPsiResolver;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 根据索引中的完整类名和方法名反向定位 IDEA 可见源码。
 */
public final class AtlasSourceNavigator {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasSourceNavigator() {
    }

    /**
     * 判断当前项目类路径中是否存在目标源码类。
     *
     * @param project    当前项目
     * @param parent     控制任务生命周期的父级对象
     * @param topic      当前专题
     * @param entryPoint 目标入口
     * @param consumer   是否可导航的界面线程回调
     */
    public static void checkAvailability(
            Project project,
            Disposable parent,
            AtlasTopic topic,
            AtlasEntryPoint entryPoint,
            Consumer<Boolean> consumer
    ) {
        if (topic == null || entryPoint == null) {
            consumer.accept(false);
            return;
        }
        ReadAction.nonBlocking(
                        (Callable<Boolean>) () -> AtlasPsiResolver.resolveTarget(
                                project,
                                topic,
                                entryPoint.method(),
                                entryPoint.sourceClass(),
                                false
                        ) != null
                )
                .inSmartMode(project)
                .expireWith(parent)
                .coalesceBy(parent, "source-availability")
                .finishOnUiThread(ModalityState.any(), consumer)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在后台定位目标方法，并在界面线程执行最终导航；找不到精确方法时退回到所属类。
     *
     * @param project    当前项目
     * @param parent     控制任务生命周期的父级对象
     * @param topic      当前专题
     * @param entryPoint 目标入口
     * @param consumer   是否成功导航的界面线程回调
     */
    public static void navigateAsync(
            Project project,
            Disposable parent,
            AtlasTopic topic,
            AtlasEntryPoint entryPoint,
            Consumer<Boolean> consumer
    ) {
        if (topic == null || entryPoint == null) {
            consumer.accept(false);
            return;
        }

        ReadAction.nonBlocking(
                        (Callable<Navigatable>) () -> findNavigationDescriptor(
                                project,
                                topic,
                                entryPoint
                        )
                )
                .inSmartMode(project)
                .expireWith(parent)
                .coalesceBy(parent, "source-navigation")
                .finishOnUiThread(ModalityState.any(), target -> navigateToDescriptor(target, consumer))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在读锁内查找目标并提前解析文件导航描述符，避免在界面线程解析源码镜像。
     *
     * @param project    当前项目
     * @param topic      当前专题
     * @param entryPoint 目标入口
     * @return 文件导航描述符；没有对应类时返回 null
     */
    private static Navigatable findNavigationDescriptor(
            Project project,
            AtlasTopic topic,
            AtlasEntryPoint entryPoint
    ) {
        AtlasPsiResolver.ResolvedTarget resolved = AtlasPsiResolver.resolveTarget(
                project,
                topic,
                entryPoint.method(),
                entryPoint.sourceClass(),
                false
        );
        if (resolved == null) {
            return null;
        }
        PsiElement target = resolved.psiMethod() == null ? resolved.psiClass() : resolved.psiMethod();
        return EditSourceUtil.getDescriptor(target);
    }

    /**
     * 在界面线程执行已经解析好的 IDEA 文件导航。
     *
     * @param target   文件导航描述符
     * @param consumer 导航结果回调
     */
    private static void navigateToDescriptor(
            Navigatable target,
            Consumer<Boolean> consumer
    ) {
        boolean navigated = target != null && target.canNavigate();
        if (navigated) {
            target.navigate(true);
        }
        consumer.accept(navigated);
    }

}
