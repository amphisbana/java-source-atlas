package io.github.javasourceatlas.spring.ioc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring 5.3.39 单值、多值、可选与延迟依赖解析的公开行为。
 */
class DependencyResolutionBehaviorTest {

    /**
     * 每个测试前清空真实延迟目标创建次数。
     */
    @BeforeEach
    void resetHeavyServiceCounter() {
        DependencyResolutionFixtures.resetHeavyServiceCreations();
    }

    /**
     * 验证按类型的单值注入使用 Primary，显式 Qualifier 能选择另一个候选。
     */
    @Test
    void shouldChoosePrimaryAndQualifierCandidates() {
        try (AnnotationConfigApplicationContext context = createResolutionContext()) {
            DependencyResolutionFixtures.ResolutionTarget target = target(context);

            assertEquals("primary", target.getGateway().name());
            assertEquals("batch", target.getBatchGateway().name());
        }
    }

    /**
     * 验证 ResolvableType 会用泛型参数把 Customer 仓库与 Order 仓库区分开。
     */
    @Test
    void shouldFilterCandidateByGenericType() {
        try (AnnotationConfigApplicationContext context = createResolutionContext()) {
            assertEquals("customer", target(context).getCustomerRepository().domain());
        }
    }

    /**
     * 验证 List 注入收集全部匹配候选，并按 Ordered 值排序。
     */
    @Test
    void shouldCollectAndSortMultipleCandidates() {
        try (AnnotationConfigApplicationContext context = createResolutionContext()) {
            List<String> names = target(context).getHandlers().stream()
                    .map(DependencyResolutionFixtures.Handler::name)
                    .collect(Collectors.toList());

            assertEquals(Arrays.asList("first", "second"), names);
        }
    }

    /**
     * 验证 Optional 与 ObjectProvider 允许依赖缺失，Lazy 代理在第一次方法调用时才创建真实目标。
     */
    @Test
    void shouldSupportOptionalProviderAndLazyDependency() {
        try (AnnotationConfigApplicationContext context = createResolutionContext()) {
            DependencyResolutionFixtures.ResolutionTarget target = target(context);

            assertFalse(target.getMissingService().isPresent());
            assertNull(target.getMissingProvider().getIfAvailable());
            assertEquals(0, DependencyResolutionFixtures.heavyServiceCreations());
            assertEquals(1, target.getHeavyService().load());
            assertEquals(1, DependencyResolutionFixtures.heavyServiceCreations());
        }
    }

    /**
     * 验证两个等价单值候选没有 Primary、Qualifier 或名称匹配时，刷新因歧义失败。
     */
    @Test
    void shouldFailWhenSingleDependencyRemainsAmbiguous() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(DependencyResolutionFixtures.AmbiguousConfiguration.class);

        try {
            BeansException exception = assertThrows(BeansException.class, context::refresh);
            assertTrue(exception.getMostSpecificCause() instanceof NoUniqueBeanDefinitionException);
        } finally {
            context.close();
        }
    }

    /**
     * 创建并刷新包含完整依赖筛选夹具的上下文。
     *
     * @return 已刷新上下文
     */
    private AnnotationConfigApplicationContext createResolutionContext() {
        return new AnnotationConfigApplicationContext(
                DependencyResolutionFixtures.ResolutionConfiguration.class);
    }

    /**
     * 从上下文取得聚合依赖解析结果的目标 Bean。
     *
     * @param context 已刷新上下文
     * @return 依赖解析目标
     */
    private DependencyResolutionFixtures.ResolutionTarget target(
            AnnotationConfigApplicationContext context) {
        return context.getBean(DependencyResolutionFixtures.ResolutionTarget.class);
    }
}
