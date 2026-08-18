package io.github.javasourceatlas.spring.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接验证 AnnotationTransactionAttributeSource 的元数据解析结果。
 */
class TransactionMetadataTest {

    /**
     * 验证实现方法上的 REQUIRES_NEW 被解析为事务属性。
     *
     * @throws NoSuchMethodException 实验方法名称错误
     */
    @Test
    void shouldResolvePropagationFromImplementationMethod() throws NoSuchMethodException {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        Method method = TransactionScenarioServiceImpl.class.getMethod("requiresNewSelfStep");

        TransactionAttribute attribute = source.getTransactionAttribute(
                method, TransactionScenarioServiceImpl.class);

        assertNotNull(attribute);
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                attribute.getPropagationBehavior());
    }

    /**
     * 验证默认规则与 rollbackFor 对同一种受检异常作出不同决策。
     *
     * @throws NoSuchMethodException 实验方法名称错误
     */
    @Test
    void shouldResolveRollbackRules() throws NoSuchMethodException {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        Method defaultMethod = TransactionScenarioServiceImpl.class
                .getMethod("checkedFailureWithDefaultRule");
        Method configuredMethod = TransactionScenarioServiceImpl.class
                .getMethod("checkedFailureWithRollbackRule");
        CheckedBusinessException failure = new CheckedBusinessException("metadata-test");

        TransactionAttribute defaultAttribute = source.getTransactionAttribute(
                defaultMethod, TransactionScenarioServiceImpl.class);
        TransactionAttribute configuredAttribute = source.getTransactionAttribute(
                configuredMethod, TransactionScenarioServiceImpl.class);

        assertNotNull(defaultAttribute);
        assertNotNull(configuredAttribute);
        assertFalse(defaultAttribute.rollbackOn(failure));
        assertTrue(configuredAttribute.rollbackOn(failure));
    }
}
