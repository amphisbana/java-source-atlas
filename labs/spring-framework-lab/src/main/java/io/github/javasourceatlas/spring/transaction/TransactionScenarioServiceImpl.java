package io.github.javasourceatlas.spring.transaction;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 实现教程使用的事务场景，跨 Bean 调用交给 InnerWorkService 代理。
 */
public final class TransactionScenarioServiceImpl implements TransactionScenarioService {

    private final InnerWorkService innerWorkService;
    private final RecordingTransactionManager transactionManager;

    /**
     * 注入内层代理与记录型事务管理器。
     *
     * @param innerWorkService 内层事务服务代理
     * @param transactionManager 记录型事务管理器
     */
    public TransactionScenarioServiceImpl(InnerWorkService innerWorkService,
                                          RecordingTransactionManager transactionManager) {
        this.innerWorkService = innerWorkService;
        this.transactionManager = transactionManager;
    }

    /**
     * 正常执行业务方法，由拦截器在返回后提交。
     */
    @Override
    @Transactional
    public void commitNormally() {
        recordBusinessEvent("commit-normally");
    }

    /**
     * 在外层事务中经过另一个代理调用 REQUIRES_NEW，观察挂起和恢复。
     */
    @Override
    @Transactional
    public void outerWithRequiresNew() {
        recordBusinessEvent("outer-before-requires-new");
        innerWorkService.requiresNewSuccess();
        recordBusinessEvent("outer-after-requires-new");
    }

    /**
     * 捕获参与者异常，但共享资源已经被内层拦截器标记 rollback-only。
     */
    @Override
    @Transactional
    public void outerCatchesRequiredFailure() {
        recordBusinessEvent("outer-before-required");
        try {
            innerWorkService.requiredFailure();
        } catch (IllegalStateException expected) {
            // REQUIRED 内层代理已经完成回滚判定；外层捕获异常不会清除共享 rollback-only 标记。
            TransactionEvents.record("business:outer-caught-required:" + transactionManager.currentTransactionId());
        }
    }

    /**
     * 捕获嵌套参与者异常，内层只回滚保存点，外层仍可以提交。
     */
    @Override
    @Transactional
    public void outerCatchesNestedFailure() {
        recordBusinessEvent("outer-before-nested");
        try {
            innerWorkService.nestedFailure();
        } catch (IllegalArgumentException expected) {
            // NESTED 回滚发生在保存点边界，不会把外层资源整体标记为 rollback-only。
            TransactionEvents.record("business:outer-caught-nested:" + transactionManager.currentTransactionId());
        }
    }

    /**
     * 抛出 RuntimeException，触发默认回滚规则。
     */
    @Override
    @Transactional
    public void runtimeFailure() {
        recordBusinessEvent("runtime-failure");
        throw new IllegalStateException("运行时异常触发默认回滚");
    }

    /**
     * 抛出受检异常但不配置 rollbackFor，默认完成路径会调用 commit。
     *
     * @throws CheckedBusinessException 固定实验异常
     */
    @Override
    @Transactional
    public void checkedFailureWithDefaultRule() throws CheckedBusinessException {
        recordBusinessEvent("checked-default");
        throw new CheckedBusinessException("受检异常默认提交");
    }

    /**
     * 通过 rollbackFor 把受检异常加入回滚规则。
     *
     * @throws CheckedBusinessException 固定实验异常
     */
    @Override
    @Transactional(rollbackFor = CheckedBusinessException.class)
    public void checkedFailureWithRollbackRule() throws CheckedBusinessException {
        recordBusinessEvent("checked-rollback-rule");
        throw new CheckedBusinessException("受检异常显式回滚");
    }

    /**
     * 使用 this 直接调用同类方法，展示代理模式下不会再次进入拦截器。
     */
    @Override
    @Transactional
    public void outerSelfInvocation() {
        recordBusinessEvent("self-outer");
        this.requiresNewSelfStep();
    }

    /**
     * 声明 REQUIRES_NEW；只有从代理外部进入时传播属性才会生效。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requiresNewSelfStep() {
        recordBusinessEvent("self-inner");
    }

    /**
     * 启动新线程读取线程本地事务资源，外层事务不会自动复制过去。
     *
     * @return 子线程观察到的事务编号
     * @throws InterruptedException 等待子线程时被中断
     */
    @Override
    @Transactional
    public String observeTransactionFromNewThread() throws InterruptedException {
        AtomicReference<String> childTransactionId = new AtomicReference<>();
        Thread child = new Thread(
                () -> childTransactionId.set(transactionManager.currentTransactionId()),
                "transaction-boundary-lab");
        child.start();
        child.join();
        TransactionEvents.record("business:child-thread:" + childTransactionId.get());
        return childTransactionId.get();
    }

    /**
     * 记录业务动作及其执行时绑定的事务编号。
     *
     * @param action 业务动作
     */
    private void recordBusinessEvent(String action) {
        TransactionEvents.record("business:" + action + ":" + transactionManager.currentTransactionId());
    }
}
