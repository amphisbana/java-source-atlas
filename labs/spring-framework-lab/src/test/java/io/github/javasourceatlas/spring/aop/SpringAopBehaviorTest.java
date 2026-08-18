package io.github.javasourceatlas.spring.aop;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopContext;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AOP 文档依赖的代理选择、链式调用与自调用公开行为。
 */
class SpringAopBehaviorTest {

    /**
     * 验证有用户业务接口且未强制类代理时，ProxyFactory 选择 JDK 动态代理。
     */
    @Test
    void shouldCreateJdkProxyForInterfaceBasedTarget() {
        AopTrace trace = new AopTrace();
        AtlasService proxy = AopProxyExamples.createJdkProxy(
                new AtlasServiceImpl(trace), trace, "trace");

        assertTrue(AopUtils.isJdkDynamicProxy(proxy));
        assertFalse(AopUtils.isCglibProxy(proxy));
        assertEquals("hello,jdk", proxy.greet("jdk"));
    }

    /**
     * 验证 proxyTargetClass=true 时，即使目标实现接口也会创建 CGLIB 子类代理。
     */
    @Test
    void shouldCreateCglibProxyWhenProxyTargetClassIsEnabled() {
        AopTrace trace = new AopTrace();
        AtlasServiceImpl proxy = AopProxyExamples.createCglibProxy(
                new AtlasServiceImpl(trace), trace, "trace");

        assertTrue(AopUtils.isCglibProxy(proxy));
        assertFalse(AopUtils.isJdkDynamicProxy(proxy));
        assertEquals("hello,cglib", proxy.greet("cglib"));
    }

    /**
     * 验证拦截器按加入顺序进入，并按相反顺序从 proceed 调用栈退出。
     */
    @Test
    void shouldInvokeInterceptorChainInNestedOrder() {
        AopTrace trace = new AopTrace();
        AtlasService proxy = AopProxyExamples.createJdkProxy(
                new AtlasServiceImpl(trace), trace, "outer", "inner");

        proxy.greet("chain");

        assertEquals(Arrays.asList(
                "advice:outer:before:greet",
                "advice:inner:before:greet",
                "target:greet",
                "advice:inner:after:greet",
                "advice:outer:after:greet"), trace.snapshot());
    }

    /**
     * 验证目标方法抛出异常时，finally 仍按调用栈逆序记录通知回卷事件。
     */
    @Test
    void shouldUnwindInterceptorChainWhenTargetThrows() {
        AopTrace trace = new AopTrace();
        AtlasServiceImpl target = new AtlasServiceImpl(trace) {
            /**
             * 记录目标方法已进入，然后抛出固定异常以触发通知链异常回卷。
             *
             * @param name 名称
             * @return 本实验始终不会正常返回
             */
            @Override
            public String greet(String name) {
                trace.record("target:greet");
                throw new IllegalStateException("boom");
            }
        };
        AtlasService proxy = AopProxyExamples.createJdkProxy(target, trace, "outer", "inner");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> proxy.greet("failure"));

        assertEquals("boom", exception.getMessage());
        assertEquals(Arrays.asList(
                "advice:outer:before:greet",
                "advice:inner:before:greet",
                "target:greet",
                "advice:inner:after:greet",
                "advice:outer:after:greet"), trace.snapshot());
    }

    /**
     * 验证 this.inner 不经过代理，而 currentProxy.inner 会重新进入拦截器链。
     */
    @Test
    void shouldExposeSelfInvocationBoundary() {
        AopTrace trace = new AopTrace();
        AtlasService proxy = AopProxyExamples.createExposedJdkProxy(
                new AtlasServiceImpl(trace), trace, "self");

        assertEquals("inner:direct", proxy.outerDirect("direct"));
        List<String> directEvents = trace.snapshot();
        assertTrue(directEvents.contains("advice:self:before:outerDirect"));
        assertTrue(directEvents.contains("target:inner"));
        assertFalse(directEvents.contains("advice:self:before:inner"));

        trace.clear();
        assertEquals("inner:proxy", proxy.outerViaCurrentProxy("proxy"));
        List<String> proxyEvents = trace.snapshot();
        assertTrue(proxyEvents.contains("advice:self:before:outerViaCurrentProxy"));
        assertTrue(proxyEvents.contains("advice:self:before:inner"));
    }

    /**
     * 验证 currentProxy 只在线程正处于 exposeProxy 调用范围内时可用。
     */
    @Test
    void shouldRejectCurrentProxyOutsideAnInvocation() {
        assertThrows(IllegalStateException.class, AopContext::currentProxy);
    }

    /**
     * 验证 DefaultAdvisorAutoProxyCreator 能根据容器中的 Advisor 包装业务 Bean。
     */
    @Test
    void shouldCreateProxyThroughApplicationContext() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LabConfiguration.class);
        try {
            AtlasService service = context.getBean(AtlasService.class);
            AopTrace trace = context.getBean(AopTrace.class);

            assertTrue(AopUtils.isJdkDynamicProxy(service));
            assertEquals("hello,context", service.greet("context"));
            assertEquals(Arrays.asList(
                    "advice:auto:before:greet",
                    "target:greet",
                    "advice:auto:after:greet"), trace.snapshot());
        } finally {
            context.close();
        }
    }
}
