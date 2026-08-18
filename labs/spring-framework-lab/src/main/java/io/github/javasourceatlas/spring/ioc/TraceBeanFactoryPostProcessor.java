package io.github.javasourceatlas.spring.ioc;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * 在目标 Bean 实例化之前修改其 BeanDefinition 属性值。
 */
public class TraceBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    /**
     * 为 traceService 增加 prefix 属性，并记录工厂后处理发生时机。
     *
     * @param beanFactory 已加载 BeanDefinition 的工厂
     * @throws BeansException 定义读取或修改失败时抛出
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        BeanDefinition definition = beanFactory.getBeanDefinition("traceService");
        definition.getPropertyValues().add("prefix", "configured-by-bfpp");
        LifecycleEvents.record("factory:BeanFactoryPostProcessor");
    }
}

