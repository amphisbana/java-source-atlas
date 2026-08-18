package io.github.javasourceatlas.idea.model;

import java.util.List;

/**
 * 描述一个推荐断点及其观察变量。
 *
 * @param method      推荐停留的方法
 * @param scenario    断点对应的实验场景
 * @param variables   建议观察的变量
 * @param sourceClass 方法所属源码类；为空时表示教学 Lab 或专题主类
 */
public record AtlasBreakpoint(String method, String scenario, List<String> variables, String sourceClass) {

    /**
     * 把断点信息压缩为工具窗口中的单行摘要。
     *
     * @return 断点方法与实验场景
     */
    @Override
    public String toString() {
        return method + " - " + scenario;
    }
}
