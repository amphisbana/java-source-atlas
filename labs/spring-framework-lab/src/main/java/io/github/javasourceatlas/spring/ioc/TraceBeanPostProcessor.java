package io.github.javasourceatlas.spring.ioc;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 只跟踪 traceService 初始化前后的 BeanPostProcessor 回调。
 */
public class TraceBeanPostProcessor implements BeanPostProcessor {

    /**
     * 在目标 Bean 初始化回调之前记录事件。
     *
     * @param bean 当前 Bean 实例
     * @param beanName 当前 Bean 名称
     * @return 不包装对象，原样返回
     * @throws BeansException 后处理失败时抛出
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if ("traceService".equals(beanName)) {
            LifecycleEvents.record("processor:beforeInitialization");
        }
        return bean;
    }

    /**
     * 在目标 Bean 初始化回调之后记录事件。
     *
     * @param bean 当前 Bean 实例
     * @param beanName 当前 Bean 名称
     * @return 不包装对象，原样返回
     * @throws BeansException 后处理失败时抛出
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if ("traceService".equals(beanName)) {
            LifecycleEvents.record("processor:afterInitialization");
        }
        return bean;
    }
}
