package io.github.javasourceatlas.spring.aop;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 运行 Spring AOP 自动代理、CGLIB 代理、拦截器链与自调用实验。
 */
public final class SpringAopDebugLab {

    /**
     * 调试入口类不需要创建实例。
     */
    private SpringAopDebugLab() {
    }

    /**
     * 依次执行容器自动代理和手工 CGLIB 代理，方便沿两条源码入口设置断点。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        runAutoProxyExample();
        runCglibExample();
    }

    /**
     * 启动最小容器，触发 AbstractAutoProxyCreator.wrapIfNecessary 与 JDK 代理调用链。
     */
    private static void runAutoProxyExample() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LabConfiguration.class);
        try {
            AtlasService service = context.getBean(AtlasService.class);
            AopTrace trace = context.getBean(AopTrace.class);

            System.out.println("auto-proxy-type=" + service.getClass().getName());
            System.out.println("is-jdk-proxy=" + AopUtils.isJdkDynamicProxy(service));
            System.out.println("greet-result=" + service.greet("atlas"));
            System.out.println("greet-events=" + trace.snapshot());

            trace.clear();
            service.outerDirect("direct");
            System.out.println("direct-self-invocation=" + trace.snapshot());

            trace.clear();
            service.outerViaCurrentProxy("proxy");
            System.out.println("proxy-self-invocation=" + trace.snapshot());
        } finally {
            context.close();
        }
    }

    /**
     * 手工创建类代理并叠加两层通知，直观看到 proceed 的入栈和回卷顺序。
     */
    private static void runCglibExample() {
        AopTrace trace = new AopTrace();
        AtlasServiceImpl target = new AtlasServiceImpl(trace);
        AtlasServiceImpl proxy = AopProxyExamples.createCglibProxy(target, trace, "outer", "inner");

        String result = proxy.greet("cglib");
        System.out.println("cglib-proxy-type=" + proxy.getClass().getName());
        System.out.println("is-cglib-proxy=" + AopUtils.isCglibProxy(proxy));
        System.out.println("cglib-result=" + result);
        System.out.println("cglib-events=" + trace.snapshot());
    }
}
