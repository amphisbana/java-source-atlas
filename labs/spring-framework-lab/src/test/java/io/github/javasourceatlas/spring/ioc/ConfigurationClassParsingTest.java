package io.github.javasourceatlas.spring.ioc;

import io.github.javasourceatlas.spring.ioc.configscan.ScannedAtlasComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring 5.3.39 配置类解析和 full/lite @Bean 调用的公开行为。
 */
class ConfigurationClassParsingTest {

    /**
     * 每个测试前清空工厂调用次数，隔离上下文执行顺序。
     */
    @BeforeEach
    void resetCounters() {
        ConfigurationClassFixtures.resetCounters();
    }

    /**
     * 验证 full 配置类中的 @Bean 自调用会路由回 BeanFactory 并复用容器单例。
     */
    @Test
    void shouldRouteFullConfigurationBeanMethodCallThroughContainer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ConfigurationClassFixtures.FullConfiguration.class)) {
            ConfigurationClassFixtures.FullDependency managed =
                    context.getBean(ConfigurationClassFixtures.FullDependency.class);
            ConfigurationClassFixtures.FullClient client =
                    context.getBean(ConfigurationClassFixtures.FullClient.class);

            assertSame(managed, client.getDependency());
            assertEquals(1, ConfigurationClassFixtures.fullDependencyCreations());
        }
    }

    /**
     * 验证 lite 配置类中的 @Bean 自调用只是普通 Java 调用，会创建容器之外的新对象。
     */
    @Test
    void shouldTreatLiteConfigurationBeanMethodCallAsPlainJavaCall() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ConfigurationClassFixtures.LiteConfiguration.class)) {
            ConfigurationClassFixtures.LiteDependency managed =
                    context.getBean(ConfigurationClassFixtures.LiteDependency.class);
            ConfigurationClassFixtures.LiteClient client =
                    context.getBean(ConfigurationClassFixtures.LiteClient.class);

            assertNotSame(managed, client.getDependency());
            assertEquals(2, ConfigurationClassFixtures.liteDependencyCreations());
            assertTrue(managed.getSequence() != client.getDependency().getSequence());
        }
    }

    /**
     * 验证扫描、普通 Import、立即选择器与延迟选择器都会增加定义，条件不匹配会跳过定义。
     */
    @Test
    void shouldExpandConfigurationThroughScanImportsAndConditions() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ConfigurationClassFixtures.ParsingRootConfiguration.class)) {
            assertEquals("component-scan", context.getBean(ScannedAtlasComponent.class).marker());
            assertEquals("direct-import", markerSource(context, "importedMarker"));
            assertEquals("immediate-selector", markerSource(context, "immediateMarker"));
            assertEquals("deferred-selector", markerSource(context, "deferredMarker"));
            assertFalse(context.containsBean("skippedMarker"));
        }
    }

    /**
     * 读取指定 Bean 名称对应的配置解析来源。
     *
     * @param context  已刷新上下文
     * @param beanName 标识 Bean 名称
     * @return 配置解析来源
     */
    private String markerSource(AnnotationConfigApplicationContext context, String beanName) {
        return context.getBean(beanName, ConfigurationClassFixtures.ParsingMarker.class).getSource();
    }
}
