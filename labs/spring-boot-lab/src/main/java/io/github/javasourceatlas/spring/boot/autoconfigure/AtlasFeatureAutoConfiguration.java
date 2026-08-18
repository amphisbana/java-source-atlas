package io.github.javasourceatlas.spring.boot.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 演示“资源发现、条件判断、属性绑定、用户 Bean 回退”的完整自动配置。
 */
@AutoConfiguration
@ConditionalOnAtlasFeatureEnabled
@EnableConfigurationProperties(AtlasFeatureProperties.class)
public class AtlasFeatureAutoConfiguration {

    /**
     * 在用户没有提供同类型 Bean 时，根据绑定后的属性创建默认服务。
     *
     * @param properties 已完成绑定的 atlas.feature 配置
     * @return 默认问候服务
     */
    @Bean
    @ConditionalOnMissingBean
    public AtlasGreetingService atlasGreetingService(AtlasFeatureProperties properties) {
        return new AtlasGreetingService(properties.getMessage(), properties.getRepeat());
    }
}
