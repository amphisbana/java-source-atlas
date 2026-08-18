package io.github.javasourceatlas.spring.transaction;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内层事务业务实现，所有注解都放在实现类公开方法上。
 */
public final class InnerWorkServiceImpl implements InnerWorkService {

    private final RecordingTransactionManager transactionManager;

    /**
     * 注入用于读取当前实验事务编号的事务管理器。
     *
     * @param transactionManager 记录型事务管理器
     */
    public InnerWorkServiceImpl(RecordingTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * 参与外层 REQUIRED 后失败，使共享资源被标记 rollback-only。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void requiredFailure() {
        recordBusinessEvent("inner-required-failure");
        throw new IllegalStateException("REQUIRED 参与者失败");
    }

    /**
     * 挂起外层事务，完成一个独立的新事务。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requiresNewSuccess() {
        recordBusinessEvent("inner-requires-new");
    }

    /**
     * 在外层资源的保存点范围内失败。
     */
    @Override
    @Transactional(propagation = Propagation.NESTED)
    public void nestedFailure() {
        recordBusinessEvent("inner-nested-failure");
        throw new IllegalArgumentException("NESTED 参与者失败");
    }

    /**
     * 把业务动作和当前事务编号组合为可断言事件。
     *
     * @param action 业务动作
     */
    private void recordBusinessEvent(String action) {
        TransactionEvents.record("business:" + action + ":" + transactionManager.currentTransactionId());
    }
}
