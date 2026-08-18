package io.github.javasourceatlas.spring.transaction;

/**
 * 汇总事务提交、回滚、传播与代理边界实验入口。
 */
public interface TransactionScenarioService {

    /**
     * 正常返回并提交事务。
     */
    void commitNormally();

    /**
     * 外层事务中调用独立的 REQUIRES_NEW 事务。
     */
    void outerWithRequiresNew();

    /**
     * 捕获 REQUIRED 参与者异常，并在外层完成时触发意外回滚。
     */
    void outerCatchesRequiredFailure();

    /**
     * 捕获 NESTED 参与者异常，让外层事务仍然提交。
     */
    void outerCatchesNestedFailure();

    /**
     * 抛出默认需要回滚的运行时异常。
     */
    void runtimeFailure();

    /**
     * 抛出默认不会触发回滚的受检异常。
     *
     * @throws CheckedBusinessException 固定实验异常
     */
    void checkedFailureWithDefaultRule() throws CheckedBusinessException;

    /**
     * 抛出由 rollbackFor 显式指定回滚的受检异常。
     *
     * @throws CheckedBusinessException 固定实验异常
     */
    void checkedFailureWithRollbackRule() throws CheckedBusinessException;

    /**
     * 从同一对象内部直接调用 REQUIRES_NEW 方法。
     */
    void outerSelfInvocation();

    /**
     * 可从代理外部调用、但也会被 this 直接调用的 REQUIRES_NEW 方法。
     */
    void requiresNewSelfStep();

    /**
     * 在新线程读取事务编号，证明线程本地状态不会自动传播。
     *
     * @return 子线程观察到的事务编号，通常为 null
     * @throws InterruptedException 等待子线程时被中断
     */
    String observeTransactionFromNewThread() throws InterruptedException;
}
