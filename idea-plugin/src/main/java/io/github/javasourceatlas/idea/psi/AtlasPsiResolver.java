package io.github.javasourceatlas.idea.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;
import io.github.javasourceatlas.idea.model.AtlasSource;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 集中解析专题签名对应的 PSI 类和方法，供导航、断点与 Lab 操作复用。
 */
public final class AtlasPsiResolver {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasPsiResolver() {
    }

    /**
     * 按显式源码类、签名 owner、专题源码和可选 Lab 的顺序解析目标。
     * 调用方必须位于 IDEA 读操作中。
     *
     * @param project          当前项目
     * @param topic            当前专题
     * @param signature        方法签名
     * @param sourceClass      可选显式源码类
     * @param allowLabFallback 是否允许回退到配套实验类
     * @return 解析结果；全部候选均不存在时返回 null
     */
    public static ResolvedTarget resolveTarget(
            Project project,
            AtlasTopic topic,
            String signature,
            String sourceClass,
            boolean allowLabFallback
    ) {
        if (project == null || topic == null || signature == null || signature.isBlank()) {
            return null;
        }

        ResolvedTarget nameFallback = null;
        ResolvedTarget classFallback = null;
        for (String className : classCandidates(topic, signature, sourceClass, allowLabFallback)) {
            PsiClass psiClass = findClass(project, className);
            if (psiClass == null) {
                continue;
            }
            if (classFallback == null) {
                classFallback = new ResolvedTarget(psiClass, null, false);
            }

            PsiMethod method = findExactMethod(psiClass, signature);
            if (method != null) {
                return new ResolvedTarget(psiClass, method, true);
            }
            if (nameFallback == null) {
                PsiMethod fallback = findMethodByName(psiClass, signature);
                if (fallback != null) {
                    nameFallback = new ResolvedTarget(psiClass, fallback, false);
                }
            }
        }
        return nameFallback == null ? classFallback : nameFallback;
    }

    /**
     * 在项目和依赖的完整作用域内查找 Java 类。
     * 调用方必须位于 IDEA 读操作中。
     *
     * @param project   当前项目
     * @param className 完整类名
     * @return PSI 类；找不到时返回 null
     */
    public static PsiClass findClass(Project project, String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        return JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project));
    }

    /**
     * 生成稳定去重的类候选，确保源码类优先、Lab 仅在允许时兜底。
     *
     * @param topic            当前专题
     * @param signature        方法签名
     * @param sourceClass      可选显式源码类
     * @param allowLabFallback 是否加入 Lab 候选
     * @return 类名候选
     */
    private static List<String> classCandidates(
            AtlasTopic topic,
            String signature,
            String sourceClass,
            boolean allowLabFallback
    ) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(topic.resolveSourceClass(signature, sourceClass));
        if (sourceClass != null && !sourceClass.isBlank()) {
            candidates.add(sourceClass);
        }
        topic.allSources().map(AtlasSource::className).forEach(candidates::add);

        if (allowLabFallback && topic.lab() != null) {
            String owner = AtlasMethodMatcher.extractOwnerName(signature);
            if (!owner.isBlank()) {
                String labPackage = packageName(topic.lab().mainClass());
                candidates.add(labPackage + owner);
            }
            candidates.add(topic.lab().mainClass());
        }
        candidates.removeIf(value -> value == null || value.isBlank());
        return List.copyOf(candidates);
    }

    /**
     * 在类的普通方法和构造器中查找精确签名。
     *
     * @param psiClass  目标类
     * @param signature 索引签名
     * @return 精确方法；未命中时返回 null
     */
    private static PsiMethod findExactMethod(PsiClass psiClass, String signature) {
        return candidateMethods(psiClass).stream()
                .filter(method -> AtlasMethodMatcher.matches(method, signature))
                .findFirst()
                .orElse(null);
    }

    /**
     * 当索引只写了省略签名或版本参数有差异时，按简单名称提供兼容回退。
     *
     * @param psiClass  目标类
     * @param signature 索引签名
     * @return 同名方法；未命中时返回 null
     */
    private static PsiMethod findMethodByName(PsiClass psiClass, String signature) {
        String methodName = AtlasMethodMatcher.extractSimpleMethodName(signature);
        return candidateMethods(psiClass).stream()
                .filter(method -> methodName.equals(method.isConstructor() ? psiClass.getName() : method.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 合并当前类声明的方法和构造器，避免构造器入口被普通方法 API 漏掉。
     *
     * @param psiClass 目标类
     * @return 方法候选
     */
    private static List<PsiMethod> candidateMethods(PsiClass psiClass) {
        List<PsiMethod> methods = new ArrayList<>(List.of(psiClass.getMethods()));
        for (PsiMethod constructor : psiClass.getConstructors()) {
            if (!methods.contains(constructor)) {
                methods.add(constructor);
            }
        }
        return methods;
    }

    /**
     * 提取包含末尾点号的包名前缀，便于补全 Lab 同包类和内部类。
     *
     * @param className 完整类名
     * @return 包名前缀；默认包返回空字符串
     */
    private static String packageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot + 1);
    }

    /**
     * 保存 PSI 解析目标以及本次是否完成参数级精确匹配。
     *
     * @param psiClass   目标类
     * @param psiMethod 目标方法；仅找到类时为空
     * @param exact      是否精确匹配签名
     */
    public record ResolvedTarget(PsiClass psiClass, PsiMethod psiMethod, boolean exact) {
    }
}
