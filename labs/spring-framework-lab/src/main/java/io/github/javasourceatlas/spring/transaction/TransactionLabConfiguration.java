package io.github.javasourceatlas.spring.transaction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 注册事务基础设施和两层业务服务的最小配置。
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = false)
public class TransactionLabConfiguration {

    /**
     * 创建无外部数据库依赖的记录型事务管理器。
     *
     * @return 实验事务管理器
     */
    @Bean
    public RecordingTransactionManager transactionManager() {
        return new RecordingTransactionManager();
    }

    /**
     * 创建承载 REQUIRED、REQUIRES_NEW 与 NESTED 的内层服务。
     *
     * @param transactionManager 记录型事务管理器
     * @return 内层服务目标对象
     */
    @Bean
    public InnerWorkService innerWorkService(RecordingTransactionManager transactionManager) {
        return new InnerWorkServiceImpl(transactionManager);
    }

    /**
     * 创建从代理外部进入的主场景服务。
     *
     * @param innerWorkService 已由容器代理的内层服务
     * @param transactionManager 记录型事务管理器
     * @return 主场景目标对象
     */
    @Bean
    public TransactionScenarioService transactionScenarioService(
            InnerWorkService innerWorkService,
            RecordingTransactionManager transactionManager) {
        return new TransactionScenarioServiceImpl(innerWorkService, transactionManager);
    }
}
