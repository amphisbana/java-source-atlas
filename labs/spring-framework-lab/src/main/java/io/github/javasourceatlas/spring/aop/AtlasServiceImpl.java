package io.github.javasourceatlas.spring.aop;

import org.springframework.aop.framework.AopContext;

/**
 * AOP 实验的真实目标对象。
 */
public class AtlasServiceImpl implements AtlasService {

    private final AopTrace trace;

    /**
     * 创建目标对象并绑定事件记录器。
     *
     * @param trace 事件记录器
     */
    public AtlasServiceImpl(AopTrace trace) {
        this.trace = trace;
    }

    /**
     * 执行普通目标方法并留下目标层事件。
     *
     * @param name 名称
     * @return 问候语
     */
    @Override
    public String greet(String name) {
        trace.record("target:greet");
        return "hello," + name;
    }

    /**
     * 通过 this 直接调用 inner；该调用不会重新穿过外层代理。
     *
     * @param value 输入文本
     * @return 内部方法结果
     */
    @Override
    public String outerDirect(String value) {
        trace.record("target:outerDirect");
        return inner(value);
    }

    /**
     * 从 AopContext 取得当前代理后调用 inner，使调用重新进入拦截器链。
     *
     * @param value 输入文本
     * @return 内部方法结果
     */
    @Override
    public String outerViaCurrentProxy(String value) {
        trace.record("target:outerViaCurrentProxy");
        return ((AtlasService) AopContext.currentProxy()).inner(value);
    }

    /**
     * 记录内部方法确实执行，并返回可断言的结果。
     *
     * @param value 输入文本
     * @return 加工后的文本
     */
    @Override
    public String inner(String value) {
        trace.record("target:inner");
        return "inner:" + value;
    }
}
