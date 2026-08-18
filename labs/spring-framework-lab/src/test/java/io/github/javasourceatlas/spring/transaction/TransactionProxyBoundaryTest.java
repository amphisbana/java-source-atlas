package io.github.javasourceatlas.spring.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 JDK 代理、自调用和线程切换形成的事务边界。
 */
class TransactionProxyBoundaryTest extends TransactionLabTestSupport {

    /**
     * 验证接口服务默认由 JDK 动态代理包装。
     */
    @Test
    void shouldUseJdkProxyForInterfaceService() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            Object service = context.getBean(TransactionScenarioService.class);
            assertTrue(Proxy.isProxyClass(service.getClass()));
        }
    }

    /**
     * 验证 this 调用没有再次进入代理，因此 REQUIRES_NEW 不会生效。
     */
    @Test
    void shouldBypassRequiresNewOnSelfInvocation() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            context.getBean(TransactionScenarioService.class).outerSelfInvocation();

            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:self-outer:tx-1",
                    "business:self-inner:tx-1",
                    "commit:tx-1");
            assertEventCount(events, "begin:", 1);
            assertNoEvent(events, "suspend:");
        }
    }

    /**
     * 验证新建线程不会自动继承 TransactionSynchronizationManager 资源。
     *
     * @throws InterruptedException 等待实验线程时被中断
     */
    @Test
    void shouldNotPropagateTransactionToNewThread() throws InterruptedException {
        try (AnnotationConfigApplicationContext context = createContext()) {
            TransactionEvents.clear();
            String childTransactionId = context.getBean(TransactionScenarioService.class)
                    .observeTransactionFromNewThread();

            assertNull(childTransactionId);
            List<String> events = TransactionEvents.snapshot();
            assertEventsInOrder(events,
                    "begin:tx-1:",
                    "business:child-thread:null",
                    "commit:tx-1");
        }
    }

    /**
     * 创建启用 proxy 模式事务管理的上下文。
     *
     * @return 已刷新上下文
     */
    private AnnotationConfigApplicationContext createContext() {
        return new AnnotationConfigApplicationContext(TransactionLabConfiguration.class);
    }
}
