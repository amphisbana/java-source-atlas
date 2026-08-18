package io.github.javasourceatlas.spring.boot.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 使用 Boot Binder 判断实验开关，并把匹配原因写入条件评估报告。
 */
public class AtlasFeatureEnabledCondition extends SpringBootCondition {

    private static final String PROPERTY_NAME = "atlas.feature.enabled";

    /**
     * 从 Environment 绑定开关；属性缺失时按关闭处理。
     *
     * @param context 条件上下文，可读取 Environment 与 BeanFactory
     * @param metadata 当前被条件修饰的配置类或方法元数据
     * @return 包含匹配结果和可读原因的条件结果
     */
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean enabled = Binder.get(context.getEnvironment())
                .bind(PROPERTY_NAME, Boolean.class)
                .orElse(false);
        ConditionMessage message = ConditionMessage
                .forCondition(ConditionalOnAtlasFeatureEnabled.class)
                .because(PROPERTY_NAME + "=" + enabled);
        return enabled ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
    }
}
