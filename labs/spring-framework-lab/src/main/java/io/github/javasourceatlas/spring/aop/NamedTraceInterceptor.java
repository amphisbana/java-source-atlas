package io.github.javasourceatlas.spring.aop;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * 以名称区分多层通知，并记录 proceed 前后的嵌套顺序。
 */
public final class NamedTraceInterceptor implements MethodInterceptor {

    private final String name;
    private final AopTrace trace;

    /**
     * 创建一个具名环绕通知。
     *
     * @param name 通知名称
     * @param trace 事件记录器
     */
    public NamedTraceInterceptor(String name, AopTrace trace) {
        this.name = name;
        this.trace = trace;
    }

    /**
     * 在 proceed 前后记录事件；finally 保证目标抛异常时仍能观察调用栈回卷。
     *
     * @param invocation 当前方法调用
     * @return 后续通知或目标方法的结果
     * @throws Throwable 后续调用抛出的原始异常
     */
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String methodName = invocation.getMethod().getName();
        trace.record("advice:" + name + ":before:" + methodName);
        try {
            return invocation.proceed();
        } finally {
            trace.record("advice:" + name + ":after:" + methodName);
        }
    }
}
