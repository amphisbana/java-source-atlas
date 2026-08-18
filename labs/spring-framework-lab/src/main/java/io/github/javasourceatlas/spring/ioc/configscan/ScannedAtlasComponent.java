package io.github.javasourceatlas.spring.ioc.configscan;

import org.springframework.stereotype.Component;

/**
 * 只用于验证 ConfigurationClassParser 能通过 ComponentScan 增加 BeanDefinition。
 */
@Component
public class ScannedAtlasComponent {

    /**
     * 返回扫描组件的稳定标识，方便 Lab 和测试确认取得的是实际实例。
     *
     * @return 扫描组件标识
     */
    public String marker() {
        return "component-scan";
    }
}
