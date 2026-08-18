package io.github.javasourceatlas.spring.ioc;

import io.github.javasourceatlas.spring.ioc.configscan.ScannedAtlasComponent;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.stream.Collectors;

/**
 * 在现有 IOC Lab 中追加配置类解析与依赖解析的可观察场景。
 */
final class SpringIocDeepDiveScenarios {

    /**
     * 工具类不需要创建实例。
     */
    private SpringIocDeepDiveScenarios() {
    }

    /**
     * 顺序执行配置类 full/lite、注册表增长、依赖筛选和歧义失败场景。
     */
    static void run() {
        observeFullAndLiteConfiguration();
        observeConfigurationClassExpansion();
        observeDependencyResolution();
        observeAmbiguousDependencyFailure();
    }

    /**
     * 对比 full 配置代理与 lite 普通 Java 方法调用的对象身份。
     */
    private static void observeFullAndLiteConfiguration() {
        ConfigurationClassFixtures.resetCounters();

        try (AnnotationConfigApplicationContext fullContext =
                     new AnnotationConfigApplicationContext(ConfigurationClassFixtures.FullConfiguration.class)) {
            ConfigurationClassFixtures.FullDependency managed =
                    fullContext.getBean(ConfigurationClassFixtures.FullDependency.class);
            ConfigurationClassFixtures.FullClient client =
                    fullContext.getBean(ConfigurationClassFixtures.FullClient.class);
            System.out.printf("full: sameDependency=%s, factoryCalls=%d%n",
                    managed == client.getDependency(), ConfigurationClassFixtures.fullDependencyCreations());
        }

        try (AnnotationConfigApplicationContext liteContext =
                     new AnnotationConfigApplicationContext(ConfigurationClassFixtures.LiteConfiguration.class)) {
            ConfigurationClassFixtures.LiteDependency managed =
                    liteContext.getBean(ConfigurationClassFixtures.LiteDependency.class);
            ConfigurationClassFixtures.LiteClient client =
                    liteContext.getBean(ConfigurationClassFixtures.LiteClient.class);
            System.out.printf("lite: sameDependency=%s, factoryCalls=%d, managedSequence=%d, directSequence=%d%n",
                    managed == client.getDependency(),
                    ConfigurationClassFixtures.liteDependencyCreations(),
                    managed.getSequence(),
                    client.getDependency().getSequence());
        }
    }

    /**
     * 观察 ComponentScan、普通 Import、立即选择器、延迟选择器和条件跳过的最终定义结果。
     */
    private static void observeConfigurationClassExpansion() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ConfigurationClassFixtures.ParsingRootConfiguration.class)) {
            ScannedAtlasComponent scanned = context.getBean(ScannedAtlasComponent.class);
            System.out.printf(
                    "configuration-expansion: scanned=%s, direct=%s, immediate=%s, deferred=%s, skipped=%s%n",
                    scanned.marker(),
                    context.getBean("importedMarker", ConfigurationClassFixtures.ParsingMarker.class).getSource(),
                    context.getBean("immediateMarker", ConfigurationClassFixtures.ParsingMarker.class).getSource(),
                    context.getBean("deferredMarker", ConfigurationClassFixtures.ParsingMarker.class).getSource(),
                    context.containsBean("skippedMarker"));
        }
    }

    /**
     * 打印 Primary、Qualifier、泛型、集合、Optional、ObjectProvider 与 Lazy 的解析结果。
     */
    private static void observeDependencyResolution() {
        DependencyResolutionFixtures.resetHeavyServiceCreations();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                DependencyResolutionFixtures.ResolutionConfiguration.class)) {
            DependencyResolutionFixtures.ResolutionTarget target =
                    context.getBean(DependencyResolutionFixtures.ResolutionTarget.class);
            String handlers = target.getHandlers().stream()
                    .map(DependencyResolutionFixtures.Handler::name)
                    .collect(Collectors.joining(" -> "));

            System.out.printf(
                    "resolution: primary=%s, qualifier=%s, generic=%s, handlers=%s, optionalPresent=%s, providerValue=%s%n",
                    target.getGateway().name(),
                    target.getBatchGateway().name(),
                    target.getCustomerRepository().domain(),
                    handlers,
                    target.getMissingService().isPresent(),
                    target.getMissingProvider().getIfAvailable());
            System.out.printf("lazy: before=%d, loadResult=%d, after=%d%n",
                    DependencyResolutionFixtures.heavyServiceCreations(),
                    target.getHeavyService().load(),
                    DependencyResolutionFixtures.heavyServiceCreations());
        }
    }

    /**
     * 刷新故意歧义的上下文，并打印最具体异常类型。
     */
    private static void observeAmbiguousDependencyFailure() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.register(DependencyResolutionFixtures.AmbiguousConfiguration.class);
            context.refresh();
            throw new IllegalStateException("歧义依赖上下文不应刷新成功");
        } catch (BeansException exception) {
            System.out.println("ambiguous: " + exception.getMostSpecificCause().getClass().getSimpleName());
        } finally {
            context.close();
        }
    }
}
