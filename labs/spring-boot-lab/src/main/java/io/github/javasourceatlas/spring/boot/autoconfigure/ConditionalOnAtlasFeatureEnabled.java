package io.github.javasourceatlas.spring.boot.autoconfigure;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅在 atlas.feature.enabled=true 时启用目标配置或 Bean。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(AtlasFeatureEnabledCondition.class)
public @interface ConditionalOnAtlasFeatureEnabled {
}
