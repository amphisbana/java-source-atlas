package io.github.javasourceatlas.spring.ioc;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明 IOC 生命周期实验使用的最小配置。
 */
@Configuration(proxyBeanMethods = false)
public class LabConfiguration {

    /**
     * 创建工厂后处理器；static 避免为了取得处理器而过早实例化配置类。
     *
     * @return 定义属性修改器
     */
    @Bean
    public static BeanFactoryPostProcessor traceBeanFactoryPostProcessor() {
        return new TraceBeanFactoryPostProcessor();
    }

    /**
     * 创建负责记录目标 Bean 初始化前后阶段的处理器。
     *
     * @return Bean 生命周期处理器
     */
    @Bean
    public static BeanPostProcessor traceBeanPostProcessor() {
        return new TraceBeanPostProcessor();
    }

    /**
     * 创建同时实现 Aware、初始化和销毁接口的实验服务。
     *
     * @return 生命周期实验服务
     */
    @Bean(initMethod = "customInit", destroyMethod = "customDestroy")
    public TraceService traceService() {
        return new TraceService();
    }

    /**
     * 创建上下文刷新和关闭事件监听器。
     *
     * @return 上下文事件监听器
     */
    @Bean
    public ContextLifecycleListener contextLifecycleListener() {
        return new ContextLifecycleListener();
    }

    /**
     * 注册名为 traceProduct 的 FactoryBean，而不是直接注册产品。
     *
     * @return 产品工厂
     */
    @Bean(name = "traceProduct")
    public TraceProductFactoryBean traceProductFactoryBean() {
        return new TraceProductFactoryBean();
    }
}

