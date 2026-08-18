package io.github.javasourceatlas.spring.ioc;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证循环依赖场景中，Spring AOP 早期代理与容器最终暴露对象保持身份一致。
 */
class EarlyAopProxyIdentityTest {

    private static final String PROXIED_A_BEAN_NAME = "proxiedA";

    /**
     * 使用真实自动代理创建器验证三级缓存取出的早期代理不会在初始化后被第二次包装。
     *
     * @throws Exception 读取代理目标对象失败时抛出
     */
    @Test
    void shouldReuseEarlyAopProxyAsFinalSingleton() throws Exception {
        GenericApplicationContext context = createContextWithProxiedSetterCycle();

        try {
            context.refresh();

            GreetingService exposedA = context.getBean(PROXIED_A_BEAN_NAME, GreetingService.class);
            CycleB b = context.getBean(CycleB.class);
            RecordingAutoProxyCreator proxyCreator = context.getBean(RecordingAutoProxyCreator.class);
            Object target = ((Advised) exposedA).getTargetSource().getTarget();

            assertTrue(AopUtils.isAopProxy(exposedA));
            assertSame(exposedA, b.getA());
            assertSame(exposedA, proxyCreator.getEarlyReference());
            assertSame(b, ((RawA) target).getB());
            assertEquals("advised:raw-A", exposedA.greet());
        } finally {
            context.close();
        }
    }

    /**
     * 创建带 Setter 环、匹配 Advisor 和自动代理创建器的最小真实 Spring 容器。
     *
     * @return 已注册定义但尚未 refresh 的上下文
     */
    private GenericApplicationContext createContextWithProxiedSetterCycle() {
        GenericApplicationContext context = new GenericApplicationContext();

        RootBeanDefinition creatorDefinition = new RootBeanDefinition(RecordingAutoProxyCreator.class);
        creatorDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        context.registerBeanDefinition("recordingAutoProxyCreator", creatorDefinition);
        context.registerBeanDefinition("greetingAdvisor", createGreetingAdvisorDefinition());

        RootBeanDefinition aDefinition = new RootBeanDefinition(RawA.class);
        aDefinition.getPropertyValues().add("b", new RuntimeBeanReference("cycleB"));

        RootBeanDefinition bDefinition = new RootBeanDefinition(CycleB.class);
        bDefinition.getPropertyValues().add("a", new RuntimeBeanReference(PROXIED_A_BEAN_NAME));

        // 先登记完整依赖图，refresh 预实例化时才会走 A -> B -> early A 的真实创建链。
        context.registerBeanDefinition(PROXIED_A_BEAN_NAME, aDefinition);
        context.registerBeanDefinition("cycleB", bDefinition);
        return context;
    }

    /**
     * 创建只拦截 greet 方法的 Advisor 定义，避免把无关实验 Bean 包装成代理。
     *
     * @return 包含固定 Advisor 实例供应器的定义
     */
    private RootBeanDefinition createGreetingAdvisorDefinition() {
        RootBeanDefinition definition = new RootBeanDefinition(DefaultPointcutAdvisor.class);
        definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        definition.setInstanceSupplier(() -> {
            NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
            pointcut.setMappedName("greet");

            // 返回值前缀使测试能够证明调用确实经过 Spring AOP 拦截器链。
            MethodInterceptor interceptor = invocation -> "advised:" + invocation.proceed();
            return new DefaultPointcutAdvisor(pointcut, interceptor);
        });
        return definition;
    }

    /**
     * 代理对外暴露的最小服务契约。
     */
    interface GreetingService {

        /**
         * 返回可观察的目标方法结果。
         *
         * @return 原始目标结果或经过拦截器包装后的结果
         */
        String greet();
    }

    /**
     * 需要注入 B、并被 Advisor 包装的 A 原始对象。
     */
    static final class RawA implements GreetingService {

        private CycleB b;

        /**
         * 注入环中的 B。
         *
         * @param b 已完成创建的 B
         */
        public void setB(CycleB b) {
            this.b = b;
        }

        /**
         * 返回 A 持有的 B，供测试核对另一侧依赖已完成填充。
         *
         * @return 环中的 B
         */
        CycleB getB() {
            return b;
        }

        /**
         * 返回原始目标值，由 Advisor 在代理边界增加前缀。
         *
         * @return 原始目标值
         */
        @Override
        public String greet() {
            return "raw-A";
        }
    }

    /**
     * 通过 Setter 依赖 A 的 B；属性类型使用接口以接收 JDK 动态代理。
     */
    static final class CycleB {

        private GreetingService a;

        /**
         * 注入三级缓存返回的 A 早期代理。
         *
         * @param a A 的代理契约
         */
        public void setA(GreetingService a) {
            this.a = a;
        }

        /**
         * 返回 B 实际持有的 A 引用。
         *
         * @return A 的早期代理
         */
        GreetingService getA() {
            return a;
        }
    }

    /**
     * 记录自动代理创建器在循环依赖窗口实际生成的早期引用。
     */
    static final class RecordingAutoProxyCreator extends DefaultAdvisorAutoProxyCreator {

        private Object earlyReference;

        /**
         * 委托 Spring 创建早期代理，并记录目标 A 对应的返回引用。
         *
         * @param bean 尚未完成初始化的原始 Bean
         * @param beanName Bean 名称
         * @return 原始对象或 Spring AOP 早期代理
         */
        @Override
        public Object getEarlyBeanReference(Object bean, String beanName) {
            Object reference = super.getEarlyBeanReference(bean, beanName);
            if (PROXIED_A_BEAN_NAME.equals(beanName)) {
                this.earlyReference = reference;
            }
            return reference;
        }

        /**
         * 返回本轮循环依赖解析时生成的早期引用。
         *
         * @return A 的早期代理；尚未发生提前引用时为 null
         */
        Object getEarlyReference() {
            return earlyReference;
        }
    }
}
