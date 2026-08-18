package io.github.javasourceatlas.spring.ioc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 IOC 生命周期案例依赖的公开可观察行为。
 */
class SpringIocLifecycleTest {

    /**
     * 每个测试开始前清空静态事件，避免上下文之间相互污染。
     */
    @BeforeEach
    void clearEvents() {
        LifecycleEvents.clear();
    }

    /**
     * 验证定义后处理、属性填充、Aware、初始化和销毁的关键相对顺序。
     */
    @Test
    void shouldRunDefinitionAndBeanLifecycleInOrder() {
        AnnotationConfigApplicationContext context = createContext();
        try {
            TraceService service = context.getBean(TraceService.class);

            assertEquals("configured-by-bfpp:traceService", service.message());
            assertTrue(service.hasBeanFactory());
            assertTrue(service.hasBeanClassLoader());
            assertTrue(service.hasApplicationContext());

            List<String> started = LifecycleEvents.snapshot();
            assertBefore(started, "factory:BeanFactoryPostProcessor", "constructor:traceService");
            assertBefore(started, "constructor:traceService", "property:setPrefix=configured-by-bfpp");
            assertBefore(started, "property:setPrefix=configured-by-bfpp", "aware:BeanNameAware");
            assertBefore(started, "aware:BeanNameAware", "aware:BeanClassLoaderAware");
            assertBefore(started, "aware:BeanClassLoaderAware", "aware:BeanFactoryAware");
            assertBefore(started, "aware:BeanFactoryAware", "aware:ApplicationContextAware");
            assertBefore(started, "aware:ApplicationContextAware", "processor:beforeInitialization");
            assertBefore(started, "processor:beforeInitialization", "init:afterPropertiesSet");
            assertBefore(started, "init:afterPropertiesSet", "init:customInit");
            assertBefore(started, "init:customInit", "processor:afterInitialization");
            assertBefore(started, "processor:afterInitialization", "event:ContextRefreshedEvent");
        } finally {
            context.close();
        }

        List<String> closed = LifecycleEvents.snapshot();
        assertBefore(closed, "event:ContextRefreshedEvent", "event:ContextClosedEvent");
        assertBefore(closed, "event:ContextClosedEvent", "destroy:DisposableBean");
        assertBefore(closed, "destroy:DisposableBean", "destroy:customDestroy");
    }

    /**
     * 验证普通名称返回 FactoryBean 产品，带 &amp; 前缀的名称返回工厂自身。
     */
    @Test
    void shouldDistinguishFactoryBeanFromItsProduct() {
        AnnotationConfigApplicationContext context = createContext();

        try {
            Object firstProduct = context.getBean("traceProduct");
            Object secondProduct = context.getBean("traceProduct");
            Object factory = context.getBean("&traceProduct");

            assertTrue(firstProduct instanceof TraceProduct);
            assertSame(firstProduct, secondProduct);
            assertTrue(factory instanceof TraceProductFactoryBean);
            assertEquals("spring-ioc-product", ((TraceProduct) firstProduct).getLabel());
        } finally {
            context.close();
        }
    }

    /**
     * 创建并刷新本测试使用的最小注解上下文。
     *
     * @return 已刷新且可用的上下文
     */
    private AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(LabConfiguration.class);
        context.refresh();
        return context;
    }

    /**
     * 断言两个生命周期事件都存在，且第一个严格早于第二个。
     *
     * @param events 生命周期事件快照
     * @param earlier 应先发生的事件
     * @param later 应后发生的事件
     */
    private void assertBefore(List<String> events, String earlier, String later) {
        int earlierIndex = events.indexOf(earlier);
        int laterIndex = events.indexOf(later);
        assertTrue(earlierIndex >= 0, () -> "未找到事件: " + earlier + "，实际事件=" + events);
        assertTrue(laterIndex >= 0, () -> "未找到事件: " + later + "，实际事件=" + events);
        assertTrue(earlierIndex < laterIndex,
                () -> earlier + " 应早于 " + later + "，实际事件=" + events);
    }
}
