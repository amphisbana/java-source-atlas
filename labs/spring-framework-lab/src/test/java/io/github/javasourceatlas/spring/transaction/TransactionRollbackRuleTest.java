package io.github.javasourceatlas.spring.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证正常返回、运行时异常与受检异常的完成决策。
 */
class TransactionRollbackRuleTest extends TransactionLabTestSupport {

    /**
     * 验证正常返回进入提交路径。
     */
    @Test
    void shouldCommitAfterNormalReturn() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            context.getBean(TransactionScenarioService.class).commitNormally();

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:commit-normally:tx-1",
                    "commit:tx-1");
            assertNoEvent(events, "rollback:");
        }
    }

    /**
     * 验证 RuntimeException 使用默认规则回滚。
     */
    @Test
    void shouldRollbackForRuntimeException() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            TransactionScenarioService service = context.getBean(TransactionScenarioService.class);

            assertThrows(IllegalStateException.class, service::runtimeFailure);

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:runtime-failure:tx-1",
                    "rollback:tx-1");
            assertNoEvent(events, "commit:");
        }
    }

    /**
     * 验证没有 rollbackFor 的受检异常仍调用提交。
     */
    @Test
    void shouldCommitForCheckedExceptionByDefault() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            TransactionScenarioService service = context.getBean(TransactionScenarioService.class);

            assertThrows(CheckedBusinessException.class, service::checkedFailureWithDefaultRule);

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:checked-default:tx-1",
                    "commit:tx-1");
            assertNoEvent(events, "rollback:");
        }
    }

    /**
     * 验证 rollbackFor 可以让受检异常进入回滚路径。
     */
    @Test
    void shouldRollbackForConfiguredCheckedException() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            TransactionScenarioService service = context.getBean(TransactionScenarioService.class);

            assertThrows(CheckedBusinessException.class, service::checkedFailureWithRollbackRule);

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:checked-rollback-rule:tx-1",
                    "rollback:tx-1");
            assertNoEvent(events, "commit:");
        }
    }

    /**
     * 创建单测试独占的上下文，使事务编号从 tx-1 开始。
     *
     * @return 已刷新上下文
     */
    private AnnotationConfigApplicationContext createContext() {
        return new AnnotationConfigApplicationContext(TransactionLabConfiguration.class);
    }
}
