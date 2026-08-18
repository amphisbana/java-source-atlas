package io.github.javasourceatlas.idea.model;

import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;

/**
 * 描述专题中的一个源码方法入口。
 *
 * @param method      索引中的方法签名
 * @param document    对应教程路由及锚点
 * @param purpose     阅读该入口要回答的问题
 * @param sourceClass 方法所属源码类；为空时使用专题主类
 */
public record AtlasEntryPoint(String method, String document, String purpose, String sourceClass) {

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
     * 返回适合列表展示的入口摘要。
     *
     * @return 方法与阅读目的
     */
    @Override
    public String toString() {
        return method + " - " + purpose;
    }
}
