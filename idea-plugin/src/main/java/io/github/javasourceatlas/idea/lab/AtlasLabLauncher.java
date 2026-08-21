package io.github.javasourceatlas.idea.lab;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.ide.util.EditSourceUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.javasourceatlas.idea.model.AtlasLab;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.psi.AtlasPsiResolver;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 打开专题 Lab 主类并创建临时 Debug 运行配置。
 */
public final class AtlasLabLauncher {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasLabLauncher() {
    }

    /**
     * 在后台检查当前 IDEA 项目是否已经导入专题 Lab 主类。
     *
     * @param project  当前项目
     * @param parent   控制任务生命周期的父级对象
     * @param topic    当前专题
     * @param consumer 是否可用的界面线程回调
     */
    public static void checkAvailability(
            Project project,
            Disposable parent,
            AtlasTopic topic,
            Consumer<Boolean> consumer
    ) {
        AtlasLab lab = topic == null ? null : topic.lab();
        if (lab == null) {
            consumer.accept(false);
            return;
        }
        ReadAction.nonBlocking(
                        (Callable<Boolean>) () -> AtlasPsiResolver.findClass(project, lab.mainClass()) != null
                )
                .inSmartMode(project)
                .expireWith(parent)
                .coalesceBy(parent, "lab-availability")
                .finishOnUiThread(ModalityState.any(), consumer)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在后台检查证据声明的 JUnit 测试方法是否已经导入当前 IDEA 项目。
     *
     * @param project  当前项目
     * @param parent   控制任务生命周期的父级对象
     * @param evidence 当前断点绑定的证据
     * @param consumer 是否可用的界面线程回调
     */
    public static void checkEvidenceAvailability(
            Project project,
            Disposable parent,
            AtlasEvidence evidence,
            Consumer<Boolean> consumer
    ) {
        if (evidence == null) {
            consumer.accept(false);
            return;
        }
        ReadAction.nonBlocking((Callable<Boolean>) () -> resolveEvidenceTarget(project, evidence) != null)
                .inSmartMode(project)
                .expireWith(parent)
                .coalesceBy(parent, "evidence-availability", evidence.testClass(), evidence.testMethod())
                .finishOnUiThread(ModalityState.any(), consumer)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在后台解析 Lab 主类导航描述符，并在界面线程打开源码。
     *
     * @param project  当前项目
     * @param parent   控制任务生命周期的父级对象
     * @param topic    当前专题
     * @param consumer 是否成功打开的回调
     */
    public static void openAsync(
            Project project,
            Disposable parent,
            AtlasTopic topic,
            Consumer<Boolean> consumer
    ) {
        AtlasLab lab = topic == null ? null : topic.lab();
        if (lab == null) {
            consumer.accept(false);
            return;
        }
        ReadAction.nonBlocking((Callable<Navigatable>) () -> {
                    PsiClass psiClass = AtlasPsiResolver.findClass(project, lab.mainClass());
                    return psiClass == null ? null : EditSourceUtil.getDescriptor(psiClass);
                })
                .inSmartMode(project)
                .expireWith(parent)
                .finishOnUiThread(ModalityState.any(), descriptor -> navigate(descriptor, consumer))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在后台解析 Lab 主类和所属模块，并在界面线程启动 Debug。
     *
     * @param project  当前项目
     * @param parent   控制任务生命周期的父级对象
     * @param topic    当前专题
     * @param consumer 是否成功启动的回调
     */
    public static void debugAsync(
            Project project,
            Disposable parent,
            AtlasTopic topic,
            Consumer<Boolean> consumer
    ) {
        AtlasLab lab = topic == null ? null : topic.lab();
        if (lab == null) {
            consumer.accept(false);
            return;
        }
        ReadAction.nonBlocking((Callable<LabTarget>) () -> {
                    PsiClass psiClass = AtlasPsiResolver.findClass(project, lab.mainClass());
                    if (psiClass == null) {
                        return null;
                    }
                    Module module = ModuleUtilCore.findModuleForPsiElement(psiClass);
                    return module == null ? null : new LabTarget(lab.mainClass(), module);
                })
                .inSmartMode(project)
                .expireWith(parent)
                .finishOnUiThread(
                        ModalityState.any(),
                        target -> startDebug(project, topic, target, consumer)
                )
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在后台解析证据绑定的测试方法，并为该单个 JUnit 场景创建临时 Debug 配置。
     *
     * @param project  当前项目
     * @param parent   控制任务生命周期的父级对象
     * @param evidence 当前断点绑定的证据
     * @param consumer 是否成功启动的回调
     */
    public static void debugEvidenceAsync(
            Project project,
            Disposable parent,
            AtlasEvidence evidence,
            Consumer<Boolean> consumer
    ) {
        if (evidence == null) {
            consumer.accept(false);
            return;
        }
        ReadAction.nonBlocking((Callable<EvidenceTarget>) () -> resolveEvidenceTarget(project, evidence))
                .inSmartMode(project)
                .expireWith(parent)
                .finishOnUiThread(
                        ModalityState.any(),
                        target -> startEvidenceDebug(project, evidence, target, consumer)
                )
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 执行已经在后台解析完成的源码导航。
     *
     * @param descriptor 导航描述符
     * @param consumer   结果回调
     */
    private static void navigate(Navigatable descriptor, Consumer<Boolean> consumer) {
        boolean available = descriptor != null && descriptor.canNavigate();
        if (available) {
            descriptor.navigate(true);
        }
        consumer.accept(available);
    }

    /**
     * 创建临时 Application 配置并交给 IDEA Debug Executor 启动。
     *
     * @param project  当前项目
     * @param topic    当前专题
     * @param target   Lab 主类和模块
     * @param consumer 启动结果回调
     */
    private static void startDebug(
            Project project,
            AtlasTopic topic,
            LabTarget target,
            Consumer<Boolean> consumer
    ) {
        if (target == null) {
            consumer.accept(false);
            return;
        }

        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(
                "Source Atlas - " + topic.title(),
                ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]
        );
        ApplicationConfiguration configuration = (ApplicationConfiguration) settings.getConfiguration();
        configuration.setModule(target.module());
        configuration.setMainClassName(target.mainClass());
        settings.setTemporary(true);
        runManager.setTemporaryConfiguration(settings);
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
        consumer.accept(true);
    }

    /**
     * 查找测试类中的无歧义测试方法及所属模块。
     * 调用方必须位于 IDEA 读操作中。
     *
     * @param project  当前项目
     * @param evidence 证据中的 JUnit 定位
     * @return 测试目标；类、方法或模块缺失时返回空
     */
    private static EvidenceTarget resolveEvidenceTarget(Project project, AtlasEvidence evidence) {
        PsiClass psiClass = AtlasPsiResolver.findClass(project, evidence.testClass());
        if (psiClass == null) {
            return null;
        }
        PsiMethod[] methods = psiClass.findMethodsByName(evidence.testMethod(), false);
        if (methods.length != 1) {
            return null;
        }
        Module module = ModuleUtilCore.findModuleForPsiElement(methods[0]);
        return module == null ? null : new EvidenceTarget(methods[0], module);
    }

    /**
     * 创建只运行一条行为测试的临时 JUnit 配置并启动 Debug。
     *
     * @param project  当前项目
     * @param evidence 证据信息，用于生成可识别的配置名称
     * @param target   已解析测试方法和模块
     * @param consumer 启动结果回调
     */
    private static void startEvidenceDebug(
            Project project,
            AtlasEvidence evidence,
            EvidenceTarget target,
            Consumer<Boolean> consumer
    ) {
        if (target == null) {
            consumer.accept(false);
            return;
        }

        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(
                "Source Atlas - " + evidence.testMethod(),
                JUnitConfigurationType.getInstance().getFactory()
        );
        JUnitConfiguration configuration = (JUnitConfiguration) settings.getConfiguration();
        configuration.setModule(target.module());
        configuration.beMethodConfiguration(new PsiLocation<>(project, target.module(), target.method()));
        settings.setTemporary(true);
        runManager.setTemporaryConfiguration(settings);
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
        consumer.accept(true);
    }

    /**
     * 保存后台解析到的 Lab 主类及所属 IDEA 模块。
     *
     * @param mainClass Lab 主类完整名称
     * @param module    所属模块
     */
    private record LabTarget(String mainClass, Module module) {
    }

    /**
     * 保存单个证据场景的测试方法及所属 IDEA 模块。
     *
     * @param method JUnit 测试方法
     * @param module 所属模块
     */
    private record EvidenceTarget(PsiMethod method, Module module) {
    }
}
