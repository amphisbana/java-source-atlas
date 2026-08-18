package io.github.javasourceatlas.spring.transaction;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * 依次运行 Spring 声明式事务的关键传播与回滚场景。
 */
public final class SpringTransactionDebugLab {

    /**
     * 调试入口类不需要创建实例。
     */
    private SpringTransactionDebugLab() {
    }

    /**
     * 启动上下文并打印每个场景的有序事务事件。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(TransactionLabConfiguration.class);
        try {
            TransactionScenarioService service = context.getBean(TransactionScenarioService.class);

            printScenario("REQUIRES_NEW 挂起与恢复", service::outerWithRequiresNew);
            printScenario("NESTED 保存点回滚", service::outerCatchesNestedFailure);
            printScenario("self-invocation 代理边界", service::outerSelfInvocation);
            printScenario("新线程事务边界", () -> runThreadBoundary(service));
            printUnexpectedRollback(service);
        } finally {
            context.close();
        }
    }

    /**
     * 清空事件、运行无受检异常场景并输出快照。
     *
     * @param title 场景名称
     * @param scenario 业务入口
     */
    private static void printScenario(String title, Runnable scenario) {
        TransactionEvents.clear();
        scenario.run();
        System.out.println(title + " -> " + TransactionEvents.snapshot());
    }

    /**
     * 适配会抛 InterruptedException 的线程边界方法。
     *
     * @param service 事务场景代理
     */
    private static void runThreadBoundary(TransactionScenarioService service) {
        try {
            service.observeTransactionFromNewThread();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("线程边界实验被中断", exception);
        }
    }

    /**
     * 单独捕获并打印 REQUIRED 参与者导致的意外回滚。
     *
     * @param service 事务场景代理
     */
    private static void printUnexpectedRollback(TransactionScenarioService service) {
        TransactionEvents.clear();
        try {
            service.outerCatchesRequiredFailure();
        } catch (UnexpectedRollbackException expected) {
            TransactionEvents.record("client:UnexpectedRollbackException");
        }
        System.out.println("REQUIRED rollback-only -> " + TransactionEvents.snapshot());
    }
}
