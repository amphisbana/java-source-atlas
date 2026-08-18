package io.github.javasourceatlas.spring.mvc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 声明实验所需的 MVC 基础设施和应用组件。
 */
@Configuration
@EnableWebMvc
public class WebMvcLabConfiguration implements WebMvcConfigurer {

    /**
     * 注册示例控制器。
     *
     * @return 订单控制器
     */
    @Bean
    public OrderController orderController() {
        return new OrderController();
    }

    /**
     * 注册统一异常处理器。
     *
     * @return 订单异常处理器
     */
    @Bean
    public OrderExceptionHandler orderExceptionHandler() {
        return new OrderExceptionHandler();
    }

    /**
     * 注册请求跟踪拦截器。
     *
     * @return 跟踪拦截器
     */
    @Bean
    public TraceHandlerInterceptor traceHandlerInterceptor() {
        return new TraceHandlerInterceptor();
    }

    /**
     * 把跟踪拦截器加入 HandlerExecutionChain。
     *
     * @param registry MVC 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(traceHandlerInterceptor());
    }
}
