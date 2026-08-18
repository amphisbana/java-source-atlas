package io.github.javasourceatlas.spring.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 REQUIRED、REQUIRES_NEW 与 NESTED 的关键传播差异。
 */
class TransactionPropagationTest extends TransactionLabTestSupport {

    /**
     * 验证 REQUIRES_NEW 挂起外层资源、提交内层后再恢复外层资源。
     */
    @Test
    void shouldSuspendAndResumeForRequiresNew() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            context.getBean(TransactionScenarioService.class).outerWithRequiresNew();

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:outer-before-requires-new:tx-1",
                    "suspend:tx-1",
                    "begin:tx-2:",
                    "business:inner-requires-new:tx-2",
                    "commit:tx-2",
                    "cleanup:tx-2",
                    "resume:tx-1",
                    "business:outer-after-requires-new:tx-1",
                    "commit:tx-1");
        }
    }

    /**
     * 验证 NESTED 只回滚到保存点，外层事务仍能提交。
     */
    @Test
    void shouldRollbackNestedSavepointAndCommitOuterTransaction() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            context.getBean(TransactionScenarioService.class).outerCatchesNestedFailure();

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:outer-before-nested:tx-1",
                    "savepoint-create:tx-1:sp-1",
                    "business:inner-nested-failure:tx-1",
                    "savepoint-rollback:tx-1:sp-1",
                    "savepoint-release:tx-1:sp-1",
                    "business:outer-caught-nested:tx-1",
                    "commit:tx-1");
            assertNoEvent(events, "mark-rollback-only:");
        }
    }

    /**
     * 验证 REQUIRED 参与者即使被外层捕获，也会让共享事务最终意外回滚。
     */
    @Test
    void shouldReportUnexpectedRollbackForCaughtRequiredFailure() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            TransactionScenarioService service = context.getBean(TransactionScenarioService.class);

            assertThrows(UnexpectedRollbackException.class, service::outerCatchesRequiredFailure);

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:inner-required-failure:tx-1",
                    "mark-rollback-only:tx-1",
                    "business:outer-caught-required:tx-1",
                    "rollback:tx-1");
            assertNoEvent(events, "commit:tx-1");
        }
    }

    /**
     * 创建包含事务代理基础设施的独立上下文。
     *
     * @return 已刷新上下文
     */
    private AnnotationConfigApplicationContext createContext() {
        return new AnnotationConfigApplicationContext(TransactionLabConfiguration.class);
    }
}
