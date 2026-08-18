package io.github.javasourceatlas.spring.transaction;

import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 不连接数据库、但真实复用 Spring 事务模板算法的记录型事务管理器。
 *
 * <p>该类只承担教学可观察性：线程绑定模拟资源持有器，保存点模拟 NESTED，
 * begin、suspend、commit 与 rollback 等模板回调全部写入事件列表。</p>
 */
public final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    private final AtomicInteger transactionSequence = new AtomicInteger();
    private final ThreadLocal<ResourceState> boundResource = new ThreadLocal<>();

    /**
     * 创建支持保存点式嵌套事务的实验事务管理器。
     */
    public RecordingTransactionManager() {
        setNestedTransactionAllowed(true);
    }

    /**
     * 返回当前线程绑定的事务编号，未绑定时返回 {@code null}。
     *
     * @return 当前事务编号
     */
    public String currentTransactionId() {
        ResourceState resource = boundResource.get();
        return resource == null ? null : resource.id;
    }

    /**
     * 为本次 getTransaction 调用创建事务对象快照。
     *
     * @return 包装当前线程资源的事务对象
     */
    @Override
    protected Object doGetTransaction() throws TransactionException {
        return new RecordingTransactionObject(boundResource.get());
    }

    /**
     * 判断当前事务对象是否持有仍处于活动状态的资源。
     *
     * @param transaction 记录型事务对象
     * @return 已存在事务时返回 true
     */
    @Override
    protected boolean isExistingTransaction(Object transaction) throws TransactionException {
        ResourceState resource = transactionObject(transaction).resource;
        return resource != null && resource.active;
    }

    /**
     * 新建资源持有器并绑定到当前线程。
     *
     * @param transaction 记录型事务对象
     * @param definition 传播、隔离级别、只读与名称等定义
     */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        RecordingTransactionObject transactionObject = transactionObject(transaction);
        ResourceState resource = new ResourceState("tx-" + transactionSequence.incrementAndGet());
        transactionObject.resource = resource;
        boundResource.set(resource);
        TransactionEvents.record("begin:" + resource.id + ":" + readableName(definition));
    }

    /**
     * 提交当前新事务；参与外层事务的状态不会直接进入此方法。
     *
     * @param status Spring 事务模板维护的状态
     */
    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        ResourceState resource = transactionObject(status.getTransaction()).requiredResource();
        TransactionEvents.record("commit:" + resource.id);
        resource.active = false;
    }

    /**
     * 回滚当前新事务或最外层已被全局标记回滚的事务。
     *
     * @param status Spring 事务模板维护的状态
     */
    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        ResourceState resource = transactionObject(status.getTransaction()).requiredResource();
        TransactionEvents.record("rollback:" + resource.id);
        resource.active = false;
    }

    /**
     * 参与者失败时把共享资源标记为 rollback-only。
     *
     * @param status 参与外层事务的状态
     */
    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
        ResourceState resource = transactionObject(status.getTransaction()).requiredResource();
        resource.rollbackOnly = true;
        TransactionEvents.record("mark-rollback-only:" + resource.id);
    }

    /**
     * 暂时解绑外层资源，让 REQUIRES_NEW 可以在同一线程开始独立事务。
     *
     * @param transaction 当前事务对象
     * @return 等待恢复的外层资源
     */
    @Override
    protected Object doSuspend(Object transaction) throws TransactionException {
        RecordingTransactionObject transactionObject = transactionObject(transaction);
        ResourceState suspended = transactionObject.requiredResource();
        transactionObject.resource = null;
        boundResource.remove();
        TransactionEvents.record("suspend:" + suspended.id);
        return suspended;
    }

    /**
     * 内层事务清理后重新绑定被挂起的外层资源。
     *
     * @param transaction 刚完成的内层事务对象
     * @param suspendedResources 先前挂起的外层资源
     */
    @Override
    protected void doResume(Object transaction, Object suspendedResources) throws TransactionException {
        ResourceState resumed = (ResourceState) suspendedResources;
        boundResource.set(resumed);
        TransactionEvents.record("resume:" + resumed.id);
    }

    /**
     * 新事务完成后解绑当前资源，随后模板才可能恢复外层资源。
     *
     * @param transaction 已完成的事务对象
     */
    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        RecordingTransactionObject transactionObject = transactionObject(transaction);
        ResourceState resource = transactionObject.resource;
        if (resource != null && boundResource.get() == resource) {
            boundResource.remove();
            TransactionEvents.record("cleanup:" + resource.id);
        }
    }

    /**
     * 把事务对象安全转换为本实验类型。
     *
     * @param transaction Spring 传入的事务对象
     * @return 记录型事务对象
     */
    private RecordingTransactionObject transactionObject(Object transaction) {
        return (RecordingTransactionObject) transaction;
    }

    /**
     * 生成适合日志阅读的事务名称。
     *
     * @param definition 当前事务定义
     * @return 事务名称或匿名标记
     */
    private String readableName(TransactionDefinition definition) {
        return definition.getName() == null ? "anonymous" : definition.getName();
    }

    /**
     * 由事务对象持有的模拟资源状态。
     */
    private static final class ResourceState {

        private final String id;
        private final AtomicInteger savepointSequence = new AtomicInteger();
        private boolean active = true;
        private boolean rollbackOnly;

        /**
         * 创建指定编号的活动资源。
         *
         * @param id 事务编号
         */
        private ResourceState(String id) {
            this.id = id;
        }
    }

    /**
     * 同时向 Spring 暴露全局回滚标记与保存点能力的事务对象。
     */
    private static final class RecordingTransactionObject implements SmartTransactionObject, SavepointManager {

        private ResourceState resource;

        /**
         * 包装当前线程已经绑定的资源，资源也可以为空。
         *
         * @param resource 当前资源
         */
        private RecordingTransactionObject(ResourceState resource) {
            this.resource = resource;
        }

        /**
         * 向提交模板报告共享事务是否已被参与者标记回滚。
         *
         * @return 已标记回滚时返回 true
         */
        @Override
        public boolean isRollbackOnly() {
            return resource != null && resource.rollbackOnly;
        }

        /**
         * 模拟资源刷新，并留下可观察事件。
         */
        @Override
        public void flush() {
            TransactionEvents.record("flush:" + requiredResource().id);
        }

        /**
         * 为 NESTED 参与者创建保存点。
         *
         * @return 新保存点令牌
         */
        @Override
        public Object createSavepoint() throws TransactionException {
            ResourceState current = requiredResource();
            SavepointToken savepoint = new SavepointToken(
                    current.id, "sp-" + current.savepointSequence.incrementAndGet());
            TransactionEvents.record("savepoint-create:" + savepoint.transactionId + ":" + savepoint.id);
            return savepoint;
        }

        /**
         * 模拟回滚到保存点，但保持外层资源仍处于活动状态。
         *
         * @param savepoint 目标保存点
         */
        @Override
        public void rollbackToSavepoint(Object savepoint) throws TransactionException {
            SavepointToken token = savepointToken(savepoint);
            TransactionEvents.record("savepoint-rollback:" + token.transactionId + ":" + token.id);
        }

        /**
         * 释放已处理的保存点。
         *
         * @param savepoint 目标保存点
         */
        @Override
        public void releaseSavepoint(Object savepoint) throws TransactionException {
            SavepointToken token = savepointToken(savepoint);
            TransactionEvents.record("savepoint-release:" + token.transactionId + ":" + token.id);
        }

        /**
         * 返回必需存在的资源，不满足时抛出明确的嵌套事务异常。
         *
         * @return 当前活动资源
         */
        private ResourceState requiredResource() {
            if (resource == null || !resource.active) {
                throw new NestedTransactionNotSupportedException("当前线程没有可用的记录型事务资源");
            }
            return resource;
        }

        /**
         * 校验并转换保存点令牌。
         *
         * @param savepoint Spring 传入的保存点对象
         * @return 本实验的保存点令牌
         */
        private SavepointToken savepointToken(Object savepoint) {
            if (!(savepoint instanceof SavepointToken)) {
                throw new NestedTransactionNotSupportedException("无法识别保存点: " + savepoint);
            }
            return (SavepointToken) savepoint;
        }
    }

    /**
     * 保存点只记录所属事务和顺序编号。
     */
    private static final class SavepointToken {

        private final String transactionId;
        private final String id;

        /**
         * 创建不可变保存点令牌。
         *
         * @param transactionId 所属事务编号
         * @param id 保存点编号
         */
        private SavepointToken(String transactionId, String id) {
            this.transactionId = transactionId;
            this.id = id;
        }
    }
}
