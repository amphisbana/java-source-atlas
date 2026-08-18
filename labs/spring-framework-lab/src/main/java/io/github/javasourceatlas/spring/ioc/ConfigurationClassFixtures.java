package io.github.javasourceatlas.spring.ioc;

import io.github.javasourceatlas.spring.ioc.configscan.ScannedAtlasComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 提供配置类解析、full/lite 模式、Import 与条件评估实验使用的夹具。
 */
public final class ConfigurationClassFixtures {

    private static final AtomicInteger FULL_DEPENDENCY_CREATIONS = new AtomicInteger();
    private static final AtomicInteger LITE_DEPENDENCY_CREATIONS = new AtomicInteger();

    /**
     * 工具类只承载配置夹具，不需要创建实例。
     */
    private ConfigurationClassFixtures() {
    }

    /**
     * 清空 full/lite 工厂调用次数，避免多个上下文之间相互影响。
     */
    public static void resetCounters() {
        FULL_DEPENDENCY_CREATIONS.set(0);
        LITE_DEPENDENCY_CREATIONS.set(0);
    }

    /**
     * 返回 full 配置中依赖工厂方法的实际执行次数。
     *
     * @return full 依赖创建次数
     */
    public static int fullDependencyCreations() {
        return FULL_DEPENDENCY_CREATIONS.get();
    }

    /**
     * 返回 lite 配置中依赖工厂方法的实际执行次数。
     *
     * @return lite 依赖创建次数
     */
    public static int liteDependencyCreations() {
        return LITE_DEPENDENCY_CREATIONS.get();
    }

    /**
     * 使用默认 proxyBeanMethods=true 的 full 配置类。
     */
    @Configuration
    public static class FullConfiguration {

        /**
         * 创建由容器管理的 full 依赖。
         *
         * @return full 依赖
         */
        @Bean
        public FullDependency fullDependency() {
            return new FullDependency(FULL_DEPENDENCY_CREATIONS.incrementAndGet());
        }

        /**
         * 直接调用同类 @Bean 方法；增强后的配置类会把调用路由回 BeanFactory。
         *
         * @return 持有容器单例依赖的客户端
         */
        @Bean
        public FullClient fullClient() {
            return new FullClient(fullDependency());
        }
    }

    /**
     * 关闭 @Bean 方法代理的 lite 配置类。
     */
    @Configuration(proxyBeanMethods = false)
    public static class LiteConfiguration {

        /**
         * 创建由容器注册的 lite 依赖。
         *
         * @return lite 依赖
         */
        @Bean
        public LiteDependency liteDependency() {
            return new LiteDependency(LITE_DEPENDENCY_CREATIONS.incrementAndGet());
        }

        /**
         * 以普通 Java 方法调用创建依赖，故不会自动复用容器中的 liteDependency 单例。
         *
         * @return 持有直接创建依赖的客户端
         */
        @Bean
        public LiteClient liteClient() {
            return new LiteClient(liteDependency());
        }
    }

    /**
     * 聚合 ComponentScan、普通 Import、ImportSelector、DeferredImportSelector 与条件跳过。
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ScannedAtlasComponent.class)
    @Import({
            ImportedConfiguration.class,
            ImmediateSelector.class,
            DeferredSelector.class,
            SkippedConfiguration.class
    })
    public static class ParsingRootConfiguration {
    }

    /**
     * 由普通 @Import 直接加入配置模型的配置类。
     */
    @Configuration(proxyBeanMethods = false)
    public static class ImportedConfiguration {

        /**
         * 创建普通 Import 标识。
         *
         * @return 普通 Import 标识
         */
        @Bean
        public ParsingMarker importedMarker() {
            return new ParsingMarker("direct-import");
        }
    }

    /**
     * 由普通 ImportSelector 立即返回的配置类。
     */
    @Configuration(proxyBeanMethods = false)
    public static class ImmediateSelectedConfiguration {

        /**
         * 创建立即选择器标识。
         *
         * @return 立即选择器标识
         */
        @Bean
        public ParsingMarker immediateMarker() {
            return new ParsingMarker("immediate-selector");
        }
    }

    /**
     * 由 DeferredImportSelector 在普通配置解析完成后返回的配置类。
     */
    @Configuration(proxyBeanMethods = false)
    public static class DeferredSelectedConfiguration {

        /**
         * 创建延迟选择器标识。
         *
         * @return 延迟选择器标识
         */
        @Bean
        public ParsingMarker deferredMarker() {
            return new ParsingMarker("deferred-selector");
        }
    }

    /**
     * 条件恒为 false 的配置类，用于验证跳过结果不会注册 @Bean 定义。
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(NeverMatchCondition.class)
    public static class SkippedConfiguration {

        /**
         * 创建理论上的跳过标识；条件不匹配时本方法不应被调用。
         *
         * @return 跳过标识
         */
        @Bean
        public ParsingMarker skippedMarker() {
            return new ParsingMarker("should-not-exist");
        }
    }

    /**
     * 立即导入选择器，在当前配置类解析轮次返回目标配置。
     */
    public static class ImmediateSelector implements ImportSelector {

        /**
         * 返回立即加入解析队列的配置类名称。
         *
         * @param importingClassMetadata 声明 @Import 的配置元数据
         * @return 立即选择的配置类名称
         */
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[]{ImmediateSelectedConfiguration.class.getName()};
        }
    }

    /**
     * 延迟导入选择器，在普通配置类处理完成后统一执行。
     */
    public static class DeferredSelector implements DeferredImportSelector {

        /**
         * 返回延迟加入配置模型的配置类名称。
         *
         * @param importingClassMetadata 声明 @Import 的配置元数据
         * @return 延迟选择的配置类名称
         */
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[]{DeferredSelectedConfiguration.class.getName()};
        }
    }

    /**
     * 永远不匹配的条件，稳定触发 ConfigurationConditionEvaluator 的跳过分支。
     */
    public static class NeverMatchCondition implements Condition {

        /**
         * 固定返回 false，使目标配置类不会注册 skippedMarker。
         *
         * @param context  条件上下文
         * @param metadata 目标配置元数据
         * @return 固定为 false
         */
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return false;
        }
    }

    /**
     * full 配置创建的依赖，序号用于观察工厂方法调用次数。
     */
    public static final class FullDependency {
        private final int sequence;

        /**
         * 创建 full 依赖。
         *
         * @param sequence 创建序号
         */
        FullDependency(int sequence) {
            this.sequence = sequence;
        }

        /**
         * 返回创建序号。
         *
         * @return 创建序号
         */
        public int getSequence() {
            return sequence;
        }
    }

    /**
     * 持有 full 依赖的客户端。
     */
    public static final class FullClient {
        private final FullDependency dependency;

        /**
         * 创建 full 客户端。
         *
         * @param dependency 配置方法提供的依赖
         */
        FullClient(FullDependency dependency) {
            this.dependency = dependency;
        }

        /**
         * 返回客户端实际持有的依赖。
         *
         * @return full 依赖
         */
        public FullDependency getDependency() {
            return dependency;
        }
    }

    /**
     * lite 配置创建的依赖，序号用于区分容器实例与直接调用实例。
     */
    public static final class LiteDependency {
        private final int sequence;

        /**
         * 创建 lite 依赖。
         *
         * @param sequence 创建序号
         */
        LiteDependency(int sequence) {
            this.sequence = sequence;
        }

        /**
         * 返回创建序号。
         *
         * @return 创建序号
         */
        public int getSequence() {
            return sequence;
        }
    }

    /**
     * 持有 lite 配置直接调用结果的客户端。
     */
    public static final class LiteClient {
        private final LiteDependency dependency;

        /**
         * 创建 lite 客户端。
         *
         * @param dependency 普通 Java 方法调用创建的依赖
         */
        LiteClient(LiteDependency dependency) {
            this.dependency = dependency;
        }

        /**
         * 返回客户端实际持有的依赖。
         *
         * @return lite 依赖
         */
        public LiteDependency getDependency() {
            return dependency;
        }
    }

    /**
     * 标记不同配置类解析来源的简单值对象。
     */
    public static final class ParsingMarker {
        private final String source;

        /**
         * 创建解析来源标识。
         *
         * @param source 配置来源
         */
        ParsingMarker(String source) {
            this.source = source;
        }

        /**
         * 返回配置来源。
         *
         * @return 配置来源
         */
        public String getSource() {
            return source;
        }
    }
}
