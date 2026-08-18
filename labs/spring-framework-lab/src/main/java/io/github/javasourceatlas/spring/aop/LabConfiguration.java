package io.github.javasourceatlas.spring.aop;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 使用 Advisor 与 AbstractAutoProxyCreator 建立最小自动代理容器。
 */
@Configuration
public class LabConfiguration {

    /**
     * 提前注册自动代理创建器，并开启当前代理暴露以支持专门的边界实验。
     *
     * @return 默认 Advisor 自动代理创建器
     */
    @Bean
    public static DefaultAdvisorAutoProxyCreator autoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setExposeProxy(true);
        return creator;
    }

    /**
     * 创建容器内共享的事件记录器。
     *
     * @return AOP 事件记录器
     */
    @Bean
    public AopTrace aopTrace() {
        return new AopTrace();
    }

    /**
     * 创建将被自动代理创建器检查和包装的业务目标。
     *
     * @param trace 事件记录器
     * @return 真实业务对象
     */
    @Bean
    public AtlasService atlasService(AopTrace trace) {
        return new AtlasServiceImpl(trace);
    }

    /**
     * 创建按方法名匹配的 Advisor，避免引入 AspectJ 表达式解析器干扰核心链路。
     *
     * @param trace 事件记录器
     * @return 匹配实验业务方法的 Advisor
     */
    @Bean
    public Advisor labAdvisor(AopTrace trace) {
        NameMatchMethodPointcutAdvisor advisor = new NameMatchMethodPointcutAdvisor();
        advisor.setMappedNames("greet", "outerDirect", "outerViaCurrentProxy", "inner");
        advisor.setAdvice(new NamedTraceInterceptor("auto", trace));
        return advisor;
    }
}
