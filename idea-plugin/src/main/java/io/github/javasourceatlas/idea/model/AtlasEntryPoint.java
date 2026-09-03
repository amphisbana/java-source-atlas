package io.github.javasourceatlas.idea.model;

import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 描述专题中的一个源码方法入口。
 *
 * @param method      索引中的方法签名
 * @param document    对应教程路由及锚点
 * @param purpose     阅读该入口要回答的问题
 * @param sourceClass 方法所属源码类；为空时使用专题主类
 * @param summary     方法职责的详细摘要
 * @param process     按执行顺序整理的关键步骤
 * @param designInsights 方法级设计亮点
 * @param pitfalls    阅读时容易误解的边界
 * @param relatedMethods 建议关联阅读的方法
 */
public record AtlasEntryPoint(
        String method,
        String document,
        String purpose,
        String sourceClass,
        String summary,
        List<String> process,
        List<String> designInsights,
        List<String> pitfalls,
        List<AtlasMethodRelation> relatedMethods
) {

    /**
     * 规范化 Gson 对可选讲解字段产生的空值。
     */
    public AtlasEntryPoint {
        summary = summary == null ? "" : summary;
        process = immutableOrEmpty(process);
        designInsights = immutableOrEmpty(designInsights);
        pitfalls = immutableOrEmpty(pitfalls);
        relatedMethods = immutableOrEmpty(relatedMethods);
    }

    /**
     * 保留旧索引和测试使用的四参数构造方式。
     *
     * @param method      索引中的方法签名
     * @param document    对应教程路由及锚点
     * @param purpose     阅读目的
     * @param sourceClass 方法所属源码类
     */
    public AtlasEntryPoint(String method, String document, String purpose, String sourceClass) {
        this(
                method,
                document,
                purpose,
                sourceClass,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    /**
     * 提取不含类名和参数的方法名称，供 PSI 方法匹配使用。
     *
     * @return 简单方法名
     */
    public String simpleMethodName() {
        return AtlasMethodMatcher.extractSimpleMethodName(method);
    }

    /**
     * 取得该方法实际所属的源码类。
     *
     * @param topic 当前专题
     * @return 完整源码类名
     */
    public String effectiveSourceClass(AtlasTopic topic) {
        return topic.resolveSourceClass(method, sourceClass);
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
     * 返回适合列表展示的入口摘要。
     *
     * @return 方法与阅读目的
     */
    @Override
    public String toString() {
        return method + " - " + purpose;
    }
}
