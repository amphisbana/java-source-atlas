package io.github.javasourceatlas.spring.ioc;

import org.springframework.beans.factory.FactoryBean;

/**
 * 演示 FactoryBean 实例与其产品对象的不同取值语义。
 */
public class TraceProductFactoryBean implements FactoryBean<TraceProduct> {

    /**
     * 创建一个产品对象，单例缓存由 Spring 的 FactoryBeanRegistrySupport 管理。
     *
     * @return 新创建的产品
     */
    @Override
    public TraceProduct getObject() {
        LifecycleEvents.record("factoryBean:getObject");
        return new TraceProduct("spring-ioc-product");
    }

    /**
     * 返回 FactoryBean 能生产的对象类型，帮助容器避免不必要的提前创建。
     *
     * @return 产品类型
     */
    @Override
    public Class<?> getObjectType() {
        return TraceProduct.class;
    }

    /**
     * 声明产品采用单例语义，同名多次获取应得到同一产品引用。
     *
     * @return 始终返回 true
     */
    @Override
    public boolean isSingleton() {
        return true;
    }
}

