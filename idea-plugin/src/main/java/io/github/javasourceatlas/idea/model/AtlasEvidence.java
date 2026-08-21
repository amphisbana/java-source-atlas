package io.github.javasourceatlas.idea.model;

/**
 * 一条可执行学习证据，把源码结论连接到讲解、Lab 与 JUnit 行为测试。
 *
 * @param id          专题内稳定证据编号
 * @param kind        证据类型
 * @param claim       可验证结论
 * @param entryPoint  对应源码入口
 * @param document    对应讲解路径
 * @param labMethod   Lab 中可运行的方法
 * @param testClass   行为测试完整类名
 * @param testMethod  带 Test 注解的测试方法
 * @param expectedOutcome 运行测试后应观察到的结果
 */
public record AtlasEvidence(
        String id,
        String kind,
        String claim,
        String entryPoint,
        String document,
        String labMethod,
        String testClass,
        String testMethod,
        String expectedOutcome
) {
}
