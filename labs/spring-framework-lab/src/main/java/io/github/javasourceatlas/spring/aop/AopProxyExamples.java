package io.github.javasourceatlas.spring.aop;

import org.springframework.aop.framework.ProxyFactory;

/**
 * 集中创建实验代理，使主程序和测试共享完全一致的代理配置。
 */
public final class AopProxyExamples {

    /**
     * 工具类不需要创建实例。
     */
    private AopProxyExamples() {
    }

    /**
     * 基于目标对象实现的接口创建 JDK 动态代理。
     *
     * @param target 真实目标对象
     * @param trace 事件记录器
     * @param interceptorNames 按加入顺序排列的通知名称
     * @return 实现 AtlasService 的 JDK 动态代理
     */
    public static AtlasService createJdkProxy(
            AtlasServiceImpl target, AopTrace trace, String... interceptorNames) {
        ProxyFactory factory = new ProxyFactory(target);
        addInterceptors(factory, trace, interceptorNames);
        return (AtlasService) factory.getProxy();
    }

    /**
     * 强制创建目标类子类代理，用于与 JDK 动态代理对照。
     *
     * @param target 真实目标对象
     * @param trace 事件记录器
     * @param interceptorNames 按加入顺序排列的通知名称
     * @return AtlasServiceImpl 的 CGLIB 子类代理
     */
    public static AtlasServiceImpl createCglibProxy(
            AtlasServiceImpl target, AopTrace trace, String... interceptorNames) {
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(target);
        factory.setProxyTargetClass(true);
        addInterceptors(factory, trace, interceptorNames);
        return (AtlasServiceImpl) factory.getProxy();
    }

    /**
     * 创建允许 AopContext.currentProxy 的 JDK 动态代理。
     *
     * @param target 真实目标对象
     * @param trace 事件记录器
     * @param interceptorName 通知名称
     * @return 暴露到当前线程的 JDK 动态代理
     */
    public static AtlasService createExposedJdkProxy(
            AtlasServiceImpl target, AopTrace trace, String interceptorName) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setExposeProxy(true);
        addInterceptors(factory, trace, interceptorName);
        return (AtlasService) factory.getProxy();
    }

    /**
     * 按给定顺序把环绕通知加入 ProxyFactory；加入顺序就是链的进入顺序。
     *
     * @param factory 正在配置的代理工厂
     * @param trace 事件记录器
     * @param interceptorNames 通知名称数组
     */
    private static void addInterceptors(
            ProxyFactory factory, AopTrace trace, String... interceptorNames) {
        for (String interceptorName : interceptorNames) {
            factory.addAdvice(new NamedTraceInterceptor(interceptorName, trace));
        }
    }
}
