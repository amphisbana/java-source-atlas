package io.github.javasourceatlas.spring.boot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证自动配置在开关、属性和用户 Bean 三个边界上的公开行为。
 */
class AtlasFeatureAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtlasFeatureAutoConfiguration.class));

    /**
     * 验证缺少开关时整项自动配置不匹配，属性 Bean 与服务 Bean 都不会注册。
     */
    @Test
    void shouldStayDisabledWhenPropertyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AtlasFeatureProperties.class);
            assertThat(context).doesNotHaveBean(AtlasGreetingService.class);

            ConditionEvaluationReport report = ConditionEvaluationReport.get(context.getBeanFactory());
            ConditionEvaluationReport.ConditionAndOutcomes outcomes = report
                    .getConditionAndOutcomesBySource()
                    .get(AtlasFeatureAutoConfiguration.class.getName());
            assertThat(outcomes).isNotNull();
            assertThat(outcomes.isFullMatch()).isFalse();
        });
    }

    /**
     * 验证显式配置 enabled=false 与属性缺失一样不会创建自动配置 Bean。
     */
    @Test
    void shouldStayDisabledWhenPropertyIsFalse() {
        contextRunner
                .withPropertyValues("atlas.feature.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtlasFeatureProperties.class);
                    assertThat(context).doesNotHaveBean(AtlasGreetingService.class);

                    ConditionEvaluationReport report = ConditionEvaluationReport
                            .get(context.getBeanFactory());
                    ConditionEvaluationReport.ConditionAndOutcomes outcomes = report
                            .getConditionAndOutcomesBySource()
                            .get(AtlasFeatureAutoConfiguration.class.getName());
                    assertThat(outcomes).isNotNull();
                    assertThat(outcomes.isFullMatch()).isFalse();
                    assertThat(outcomes).anySatisfy(conditionAndOutcome ->
                            assertThat(conditionAndOutcome.getOutcome().getMessage())
                                    .contains("atlas.feature.enabled=false"));
                });
    }

    /**
     * 验证开关开启后完成宽松属性绑定，并创建默认服务。
     */
    @Test
    void shouldBindPropertiesAndCreateDefaultService() {
        contextRunner
                .withPropertyValues(
                        "atlas.feature.enabled=true",
                        "atlas.feature.message=深入源码",
                        "atlas.feature.repeat=2")
                .run(context -> {
                    AtlasFeatureProperties properties = context.getBean(AtlasFeatureProperties.class);
                    AtlasGreetingService service = context.getBean(AtlasGreetingService.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getMessage()).isEqualTo("深入源码");
                    assertThat(properties.getRepeat()).isEqualTo(2);
                    assertThat(service.greet("Java"))
                            .isEqualTo("深入源码，Java | 深入源码，Java");
                });
    }

    /**
     * 验证用户先声明同类型 Bean 时，ConditionalOnMissingBean 让默认 Bean 回退。
     */
    @Test
    void shouldBackOffWhenUserProvidesService() {
        AtlasGreetingService userService = new AtlasGreetingService("用户实现", 1);
        contextRunner
                .withPropertyValues("atlas.feature.enabled=true")
                .withBean("userAtlasGreetingService", AtlasGreetingService.class, () -> userService)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtlasGreetingService.class);
                    assertThat(context.getBean(AtlasGreetingService.class)).isSameAs(userService);
                    assertThat(context).hasBean("userAtlasGreetingService");
                    assertThat(context).doesNotHaveBean("atlasGreetingService");
                });
    }

    /**
     * 验证 EnableAutoConfiguration 能从 AutoConfiguration.imports 发现本实验配置。
     */
    @Test
    void shouldDiscoverConfigurationFromImportsResource() {
        new ApplicationContextRunner()
                .withUserConfiguration(ImportsDiscoveryApplication.class)
                .withPropertyValues("atlas.feature.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AtlasGreetingService.class));
    }

    /**
     * 仅用于验证资源发现，不启用组件扫描，避免直接扫描到自动配置类。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ImportsDiscoveryApplication {
    }
}
