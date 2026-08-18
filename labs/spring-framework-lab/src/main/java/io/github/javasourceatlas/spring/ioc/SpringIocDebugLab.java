package io.github.javasourceatlas.spring.ioc;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 启动最小 Spring IOC 容器并打印完整生命周期事件。
 */
public final class SpringIocDebugLab {

    /**
     * 调试入口类不需要创建实例。
     */
    private SpringIocDebugLab() {
    }

    /**
     * 显式执行 register、refresh、getBean 和 close，方便逐段设置源码断点。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        LifecycleEvents.clear();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        try {
            context.register(LabConfiguration.class);
            context.refresh();

            TraceService service = context.getBean(TraceService.class);
            TraceProduct product = context.getBean("traceProduct", TraceProduct.class);
            TraceProductFactoryBean factory = context.getBean("&traceProduct", TraceProductFactoryBean.class);

            System.out.println("service=" + service.message());
            System.out.println("product=" + product.getLabel());
            System.out.println("factory=" + factory.getClass().getSimpleName());
            System.out.println("events-before-close=" + LifecycleEvents.snapshot());
        } finally {
            context.close();
        }

        System.out.println("events-after-close=" + LifecycleEvents.snapshot());

        // 2026-08-17：保留上方原有生命周期实验，在同一 IOC 入口追加配置类与依赖解析深挖。
        System.out.println("\n=== 配置类解析与依赖解析深挖 ===");
        SpringIocDeepDiveScenarios.run();
    }
}
