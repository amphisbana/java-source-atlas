package io.github.javasourceatlas.spring.ioc;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 同时实现常用生命周期接口的实验服务。
 */
public class TraceService implements BeanNameAware, BeanClassLoaderAware, BeanFactoryAware,
        ApplicationContextAware, InitializingBean, DisposableBean {

    private String prefix = "constructor-default";
    private String beanName;
    private ClassLoader beanClassLoader;
    private BeanFactory beanFactory;
    private ApplicationContext applicationContext;

    /**
     * 创建服务并记录实例化时机。
     */
    public TraceService() {
        LifecycleEvents.record("constructor:traceService");
    }

    /**
     * 写入由 BeanFactoryPostProcessor 添加到 BeanDefinition 的属性值。
     *
     * @param prefix 消息前缀
     */
    public void setPrefix(String prefix) {
        this.prefix = prefix;
        LifecycleEvents.record("property:setPrefix=" + prefix);
    }

    /**
     * 接收当前 Bean 在容器中的规范名称。
     *
     * @param name Bean 名称
     */
    @Override
    public void setBeanName(String name) {
        this.beanName = name;
        LifecycleEvents.record("aware:BeanNameAware");
    }

    /**
     * 接收 Spring 创建当前 Bean 时使用的 ClassLoader。
     *
     * @param classLoader Bean ClassLoader
     */
    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
        LifecycleEvents.record("aware:BeanClassLoaderAware");
    }

    /**
     * 接收创建当前对象的 BeanFactory。
     *
     * @param beanFactory 当前 BeanFactory
     * @throws BeansException 工厂注入失败时抛出
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
        LifecycleEvents.record("aware:BeanFactoryAware");
    }

    /**
     * 接收外层 ApplicationContext；该回调由 ApplicationContextAwareProcessor 执行。
     *
     * @param applicationContext 当前应用上下文
     * @throws BeansException 上下文注入失败时抛出
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        LifecycleEvents.record("aware:ApplicationContextAware");
    }

    /**
     * 执行 InitializingBean 初始化回调。
     */
    @Override
    public void afterPropertiesSet() {
        LifecycleEvents.record("init:afterPropertiesSet");
    }

    /**
     * 执行 BeanDefinition 配置的自定义初始化方法。
     */
    public void customInit() {
        LifecycleEvents.record("init:customInit");
    }

    /**
     * 返回包含已注入属性和 Bean 名称的可观察结果。
     *
     * @return 实验消息
     */
    public String message() {
        return prefix + ":" + beanName;
    }

    /**
     * 判断 BeanFactoryAware 回调是否已经完成。
     *
     * @return 已注入工厂时返回 true
     */
    public boolean hasBeanFactory() {
        return beanFactory != null;
    }

    /**
     * 判断 BeanClassLoaderAware 回调是否已经完成。
     *
     * @return 已注入 ClassLoader 时返回 true
     */
    public boolean hasBeanClassLoader() {
        return beanClassLoader != null;
    }

    /**
     * 判断 ApplicationContextAwareProcessor 是否已经完成上下文回调。
     *
     * @return 已注入 ApplicationContext 时返回 true
     */
    public boolean hasApplicationContext() {
        return applicationContext != null;
    }

    /**
     * 执行 DisposableBean 销毁回调。
     */
    @Override
    public void destroy() {
        LifecycleEvents.record("destroy:DisposableBean");
    }

    /**
     * 执行 BeanDefinition 配置的自定义销毁方法。
     */
    public void customDestroy() {
        LifecycleEvents.record("destroy:customDestroy");
    }
}
