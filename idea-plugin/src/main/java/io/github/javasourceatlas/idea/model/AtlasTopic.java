package io.github.javasourceatlas.idea.model;

import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 插件使用的源码专题模型，与仓库 source-index 字段保持一致。
 *
 * @param topicId            稳定专题编号
 * @param title              专题标题
 * @param primaryVersion     主要讲解版本
 * @param sourceRef          固定源码 Tag
 * @param compatibleVersions 可参考的兼容版本
 * @param lab                配套的可运行调试实验
 * @param source             专题主源码类
 * @param relatedSources     关联源码类
 * @param entryPoints        关键方法入口
 * @param breakpoints        推荐断点
 */
public record AtlasTopic(
        String topicId,
        String title,
        String primaryVersion,
        String sourceRef,
        List<String> compatibleVersions,
        AtlasLab lab,
        AtlasSource source,
        List<AtlasSource> relatedSources,
        List<AtlasEntryPoint> entryPoints,
        List<AtlasBreakpoint> breakpoints
) {

    /**
     * 规范化 Gson 对缺省数组字段产生的空值，避免界面层重复判空。
     */
    public AtlasTopic {
        compatibleVersions = immutableOrEmpty(compatibleVersions);
        relatedSources = immutableOrEmpty(relatedSources);
        entryPoints = immutableOrEmpty(entryPoints);
        breakpoints = immutableOrEmpty(breakpoints);
    }

    /**
     * 判断指定完整类名是否属于当前专题。
     *
     * @param className IDEA 当前 Java 类名
     * @return 是否命中主类或关联类
     */
    public boolean containsSourceClass(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        return allSources().anyMatch(sourceItem -> className.equals(sourceItem.className())
                || className.startsWith(sourceItem.className() + "."));
    }

    /**
     * 根据签名中的所属类和显式 sourceClass 解析实际 PSI 类名，兼容内部类入口。
     *
     * @param signature           人类可读方法签名
     * @param explicitSourceClass 索引显式声明的源码类
     * @return 实际源码类名
     */
    public String resolveSourceClass(String signature, String explicitSourceClass) {
        String owner = AtlasMethodMatcher.extractOwnerName(signature);
        if (explicitSourceClass != null && !explicitSourceClass.isBlank()) {
            return qualifyNestedOwner(explicitSourceClass, owner);
        }
        if (owner.isBlank()) {
            return source.className();
        }

        List<AtlasSource> sources = allSources().toList();
        for (AtlasSource sourceItem : sources) {
            if (simpleClassName(sourceItem.className()).equals(owner)
                    || sourceItem.className().endsWith("." + owner)) {
                return sourceItem.className();
            }
        }
        for (AtlasSource sourceItem : sources) {
            String qualified = qualifyNestedOwner(sourceItem.className(), owner);
            if (!qualified.equals(sourceItem.className())) {
                return qualified;
            }
        }
        return source.className();
    }

    /**
     * 按标题、版本、源码类、Lab 主类和入口方法执行不区分大小写的专题搜索。
     *
     * @param query 用户输入
     * @return 是否匹配
     */
    public boolean matchesQuery(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return Stream.concat(
                        Stream.of(
                                title,
                                primaryVersion,
                                sourceRef,
                                lab == null ? null : lab.mainClass(),
                                lab == null ? null : lab.module()
                        ),
                        Stream.concat(
                                allSources().map(AtlasSource::className),
                                entryPoints.stream().map(AtlasEntryPoint::method)
                        )
                )
                .filter(value -> value != null)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(normalized));
    }

    /**
     * 按主类优先的顺序返回当前专题全部源码类。
     *
     * @return 源码类流
     */
    public Stream<AtlasSource> allSources() {
        return Stream.concat(Stream.of(source), relatedSources.stream())
                .filter(sourceItem -> sourceItem != null && sourceItem.className() != null);
    }

    /**
     * 当 owner 以外部类名开头时，将其补全为完整内部类名。
     *
     * @param outerClassName 外部类完整名称
     * @param owner          签名中的所属类
     * @return 补全后的类名，无法判断时返回原外部类名
     */
    private static String qualifyNestedOwner(String outerClassName, String owner) {
        if (owner == null || owner.isBlank()) {
            return outerClassName;
        }
        String simpleName = simpleClassName(outerClassName);
        if (owner.equals(simpleName)) {
            return outerClassName;
        }
        if (owner.startsWith(simpleName + ".")) {
            int packageEnd = outerClassName.length() - simpleName.length();
            return outerClassName.substring(0, packageEnd) + owner;
        }
        if (!owner.contains(".")) {
            return outerClassName + "." + owner;
        }
        return outerClassName;
    }

    /**
     * 提取完整类名最后一段，供索引中的短类名比较。
     *
     * @param className 完整类名
     * @return 简单类名
     */
    private static String simpleClassName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? className : className.substring(lastDot + 1);
    }

    /**
     * 把可能为空的反序列化集合转换为不可变集合。
     *
     * @param values 原始集合
     * @param <T>    元素类型
     * @return 不可变集合
     */
    private static <T> List<T> immutableOrEmpty(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * 返回适合列表展示的专题标题。
     *
     * @return 专题标题
     */
    @Override
    public String toString() {
        return title;
    }
}
