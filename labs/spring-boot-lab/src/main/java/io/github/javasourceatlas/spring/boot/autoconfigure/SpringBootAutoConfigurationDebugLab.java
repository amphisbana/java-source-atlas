package io.github.javasourceatlas.spring.boot.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * 启动非 Web Boot 应用，输出自动配置、属性绑定与条件报告的关键结果。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class SpringBootAutoConfigurationDebugLab {

    /**
     * 创建并刷新最小 Boot 上下文；未传参数时使用一组便于观察的默认参数。
     *
     * @param args 可覆盖 atlas.feature 属性的命令行参数
     */
    public static void main(String[] args) {
        String[] runArguments = defaultArgumentsWhenNecessary(args);
        SpringApplication application = new SpringApplication(SpringBootAutoConfigurationDebugLab.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);

        ConfigurableApplicationContext context = application.run(runArguments);
        try {
            AtlasFeatureProperties properties = context.getBean(AtlasFeatureProperties.class);
            AtlasGreetingService service = context.getBean(AtlasGreetingService.class);
            System.out.println("environment.atlas.feature.message="
                    + context.getEnvironment().getProperty("atlas.feature.message"));
            System.out.println("bound.repeat=" + properties.getRepeat());
            System.out.println("service=" + service.greet("源码读者"));
            printAtlasConditionReport(context);
        } finally {
            context.close();
        }
    }

    /**
     * 没有命令行参数时补充默认开关和值，保证直接运行即可观察成功装配路径。
     *
     * @param args 原始命令行参数
     * @return 实际交给 SpringApplication 的参数
     */
    private static String[] defaultArgumentsWhenNecessary(String[] args) {
        if (args != null && args.length > 0) {
            return args;
        }
        return new String[]{
                "--atlas.feature.enabled=true",
                "--atlas.feature.message=欢迎阅读",
                "--atlas.feature.repeat=2"
        };
    }

    /**
     * 只输出本实验自动配置对应的报告项，避免大量 Boot 内置配置淹没观察目标。
     *
     * @param context 已完成刷新的应用上下文
     */
    private static void printAtlasConditionReport(ConfigurableApplicationContext context) {
        ConditionEvaluationReport report = ConditionEvaluationReport.get(context.getBeanFactory());
        for (Map.Entry<String, ConditionEvaluationReport.ConditionAndOutcomes> entry
                : report.getConditionAndOutcomesBySource().entrySet()) {
            if (entry.getKey().startsWith(AtlasFeatureAutoConfiguration.class.getName())) {
                System.out.println("condition.source=" + entry.getKey());
                System.out.println("condition.fullMatch=" + entry.getValue().isFullMatch());
                entry.getValue().forEach(conditionAndOutcome -> System.out.println(
                        "condition.detail=" + conditionAndOutcome.getCondition().getClass().getSimpleName()
                                + ":" + conditionAndOutcome.getOutcome()));
            }
        }
    }
}
