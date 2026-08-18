# 传播、挂起与恢复：getTransaction 怎样决定边界

## 源码入口

- 统一入口：`AbstractPlatformTransactionManager.getTransaction(TransactionDefinition)`
- 已有事务分流：`handleExistingTransaction(...)`
- 新事务启动：`startTransaction(...)`
- 事务状态创建：`newTransactionStatus(...)`
- 挂起与恢复：`suspend(...)` / `resume(...)`
- 保存点：`AbstractTransactionStatus.createAndHoldSavepoint()`
- 线程上下文：`TransactionSynchronizationManager`
- 资源钩子：`doGetTransaction`、`doBegin`、`doSuspend`、`doResume`

本页以 `AbstractPlatformTransactionManager` 的命令式模板为主。具体管理器可以覆盖钩子或根本不继承该模板，最终要以应用实际使用的管理器为准。

## propagation 不是“是否开启事务”的单一开关

传播规则回答两个问题：

1. 当前线程已经有一个兼容事务时，本次方法是参与、挂起、建立保存点，还是拒绝执行？
2. 当前线程没有事务时，本次方法是开始新事务、无事务运行，还是直接报错？

七种传播模式的完整矩阵：

| 传播模式 | 已有事务 | 没有事务 | 典型用途 |
| --- | --- | --- | --- |
| `REQUIRED` | 参与现有事务 | 开始新事务 | 默认业务原子边界 |
| `SUPPORTS` | 参与现有事务 | 无实际事务运行 | 可同时服务事务/非事务调用，但语义容易含糊 |
| `MANDATORY` | 参与现有事务 | 抛 `IllegalTransactionStateException` | 强制上游建立事务 |
| `REQUIRES_NEW` | 挂起现有事务，开始独立事务 | 开始新事务 | 独立审计、独立状态记录等明确边界 |
| `NOT_SUPPORTED` | 挂起现有事务，无事务运行 | 无事务运行 | 显式避开长事务的非事务工作 |
| `NEVER` | 抛 `IllegalTransactionStateException` | 无事务运行 | 禁止在事务环境调用 |
| `NESTED` | 在现有事务建立保存点，或走管理器的真正嵌套能力 | 开始新事务 | 局部回滚但仍服从外层最终提交 |

“已有事务”不是仅看 `TransactionInfo` 是否存在。模板先调用具体管理器的 `doGetTransaction()` 取得事务对象，再由 `isExistingTransaction(transaction)` 判断其底层资源是否实际活动。

## getTransaction 无现有事务的主干

```text
getTransaction(definition)
  ├─ transaction = doGetTransaction()
  ├─ definition == null ? 使用默认 TransactionDefinition
  ├─ isExistingTransaction(transaction) == false
  │    ├─ MANDATORY
  │    │    └─ 抛 IllegalTransactionStateException
  │    ├─ REQUIRED / REQUIRES_NEW / NESTED
  │    │    ├─ 必要时挂起“空事务上的同步状态”
  │    │    └─ startTransaction(...)
  │    │         ├─ newTransactionStatus(newTransaction=true)
  │    │         ├─ doBegin(transaction, definition)
  │    │         └─ prepareSynchronization(status, definition)
  │    └─ SUPPORTS / NOT_SUPPORTED / NEVER
  │         └─ 创建没有实际事务的 status
  └─ 返回 DefaultTransactionStatus
```

这里的“没有实际事务”不一定等于没有同步范围。`transactionSynchronization` 配置为 `SYNCHRONIZATION_ALWAYS` 时，空事务也可能激活同步管理。业务代码不应仅用“能注册 synchronization”推断数据库事务已经开始。

## getTransaction 已有事务的主干

```text
handleExistingTransaction(definition, transaction, debugEnabled)
  ├─ NEVER
  │    └─ 抛 IllegalTransactionStateException
  ├─ NOT_SUPPORTED
  │    ├─ suspend(transaction)
  │    └─ 返回无实际事务 status
  ├─ REQUIRES_NEW
  │    ├─ suspended = suspend(transaction)
  │    ├─ startTransaction(definition, transaction, suspended)
  │    └─ begin 失败也尝试恢复 suspended
  ├─ NESTED
  │    ├─ 检查 nestedTransactionAllowed
  │    ├─ 保存点模式：创建 status 并 createAndHoldSavepoint
  │    └─ 真嵌套模式：startTransaction，但不挂起外层
  └─ REQUIRED / SUPPORTS / MANDATORY
       ├─ 可选校验隔离级别和只读兼容性
       └─ 返回参与现有事务的 status（newTransaction=false）
```

同一个 `TransactionDefinition` 只表达期望。最终 status 是否为新事务，取决于进入时的上下文。例如 `REQUIRED` 在根调用中 `isNewTransaction=true`，在内层参与调用中为 false。

## REQUIRED：多个逻辑范围共享一个物理事务

设外层和内层都是 `REQUIRED`：

```text
proxy.outer()
  ├─ getTransaction(REQUIRED)
  │    └─ 没有现有资源 → begin tx-1，statusOuter.newTransaction=true
  └─ target.outer()
       └─ proxy.inner()
            ├─ getTransaction(REQUIRED)
            │    └─ 已有 tx-1 → 参与，statusInner.newTransaction=false
            └─ target.inner()
```

内外层拥有独立的逻辑 status，资源却都是 tx-1。结果是：

- 内层正常返回时，其 `commit(statusInner)` 不会真正提交 tx-1，只完成参与范围。
- 内层按规则回滚时，无法单独回滚共享物理事务，通常调用 `doSetRollbackOnly` 标记 tx-1。
- 外层随后正常返回并调用 commit，模板发现 global rollback-only，改为回滚并抛 `UnexpectedRollbackException`。

这保证外层不会误以为自己的正常返回已经提交成功。

### 捕获异常为什么仍可能回滚

```java
@Transactional
public void outer() {
    try {
        otherBean.innerRequired();
    } catch (RuntimeException ignored) {
        // Java 异常被捕获，但内层事务拦截器已经看见异常并标记共享资源。
    }
}
```

关键是异常有没有穿过**内层代理**：

- 穿过：内层 `completeTransactionAfterThrowing` 已经执行回滚判定，参与事务被标记 rollback-only。
- 在同一目标对象内部抛出又被捕获，未穿过代理：事务 advice 看不到它，除非业务显式设置 rollback-only，否则外层可能提交。

不要把 Java 的 catch 位置与事务完成位置混为一谈。

## REQUIRES_NEW：外层仍活动，但暂时不绑定当前线程

已有 tx-1 时调用 `REQUIRES_NEW`：

```text
当前线程：resource = tx-1
  │
  ├─ suspend(tx-1)
  │    ├─ 暂停当前 TransactionSynchronization
  │    ├─ 具体管理器解绑 tx-1 资源
  │    ├─ 暂存事务名称、只读、隔离级别、active 标志
  │    └─ 生成 SuspendedResourcesHolder
  │
  ├─ begin(tx-2)，当前线程 resource = tx-2
  ├─ 执行内层业务
  ├─ commit/rollback(tx-2)
  ├─ cleanup(tx-2)，线程暂时无绑定资源
  │
  └─ resume(tx-1)
       ├─ 具体管理器重新绑定 tx-1
       ├─ 恢复同步上下文
       └─ 逐个恢复被暂停的 TransactionSynchronization
```

外层 tx-1 没有被提交或回滚，只是当前线程暂时无法通过资源工厂取得它。内层结束后恢复的是原资源身份，不是重新创建一个等价事务。

### begin 失败也必须恢复外层

如果 tx-1 已挂起，但 tx-2 在 `doBegin` 阶段失败，模板会尝试恢复 tx-1，再把开始失败异常抛出。否则线程会丢失原事务上下文，外层后续清理也可能混乱。

### 连接池容量风险

JDBC `REQUIRES_NEW` 常意味着：外层连接仍被 tx-1 占用，内层还要从池中借第二条连接。并发线程都持有外层连接、又同时等待内层连接时，连接池可能耗尽甚至形成等待僵局。

评估时不能只看平均 SQL 数量，要计算最坏并发下的嵌套深度和每线程同时占用资源数。Spring 文档通常建议池容量至少高于并发外层线程数；复杂嵌套需要更严格的容量模型和超时保护。

### 独立事务不等于可靠消息

用 `REQUIRES_NEW` 写审计表，只能让审计事务和业务事务独立完成。它不自动解决：

- 进程在两次提交之间崩溃；
- 多数据库原子一致性；
- 消息发布与数据库提交的一致性；
- 重复调用和幂等。

需要跨资源可靠性时，应根据需求评估 outbox、幂等、补偿或分布式事务，而不是把传播模式当作一致性协议。

## NESTED：一个物理事务中的保存点范围

已有 tx-1 且 `useSavepointForNestedTransaction()` 返回 true 时：

```text
getTransaction(NESTED)
  ├─ statusNested.newTransaction=false
  ├─ statusNested.createAndHoldSavepoint()
  │    └─ transactionObject.createSavepoint() → sp-1
  ├─ 执行内层业务
  ├─ 失败：rollbackToHeldSavepoint()
  │    ├─ rollbackToSavepoint(sp-1)
  │    └─ releaseSavepoint(sp-1)
  └─ 外层仍持有 tx-1
```

保存点回滚后，外层可决定继续并最终提交 tx-1。但最终提交之前的一切仍属于同一物理事务：

- 外层最终回滚时，保存点之前和之后尚未提交的修改都会回滚。
- 数据库锁在保存点回滚后是否释放取决于数据库实现，不能假定局部回滚立即释放全部锁。
- 保存点能力取决于驱动、资源与事务管理器。

### 没有外层事务时

`NESTED` 与 `REQUIRED` 一样开始一个新事务。此时没有“先建一个空保存点再开始事务”的必要；它就是本次根物理事务。

### 管理器支持边界

- `DataSourceTransactionManager` 可利用 JDBC Savepoint，常见本地 JDBC 场景能够支持。
- `JpaTransactionManager` 对 JPA EntityManager 的嵌套语义受限；即使开放 JDBC 保存点，也不能想当然认为整个持久化上下文可以局部还原。
- JTA/XA 管理器是否支持真正嵌套或挂起由环境能力决定。
- 自定义管理器必须显式实现保存点或嵌套 begin 语义，并允许 nested。

看到 `Propagation.NESTED` 编译通过，不代表运行环境一定支持。必须以集成测试验证实际管理器和数据库。

## NESTED 与 REQUIRES_NEW 对照

| 维度 | `NESTED` | `REQUIRES_NEW` |
| --- | --- | --- |
| 物理资源 | 通常共享外层资源 | 通常取得新资源 |
| 外层是否挂起 | 否 | 是 |
| 核心机制 | 保存点或真正嵌套事务 | 独立事务 |
| 内层提交 | 通常只是释放保存点，尚未物理提交 | 真正提交内层事务 |
| 外层最终回滚 | 会撤销整个共享事务 | 不撤销已经提交的内层事务 |
| 连接池压力 | 通常不额外占一条连接 | 常额外占用连接 |
| 支持范围 | 高度依赖保存点能力 | 依赖挂起和新资源能力 |

选择传播模式应先回答一致性需求，而不是性能偏好。

## 其他四种传播模式的实际边界

### SUPPORTS

有事务就参与，没有就非事务运行。它适合真正可选的读取逻辑，但会让调用者环境影响方法语义。若同一方法在有无事务时读到不同快照或延迟加载行为不同，测试必须覆盖两种入口。

### MANDATORY

用来表达“本方法只是一个更大原子操作的组成部分”。没有外层事务立即失败，能比隐式开启新事务更早暴露错误调用路径。

### NOT_SUPPORTED

已有事务时挂起，方法以无实际事务状态执行，结束后恢复外层。它可能仍借用资源或受自动提交行为影响，不等于操作没有副作用。

### NEVER

检测到实际事务立即失败，适合明确禁止事务上下文的接口。它不是“忽略事务”，而是运行时断言。

## 挂起的内容不只有连接

`AbstractPlatformTransactionManager.suspend` 协调两组状态：

1. 具体事务管理器通过 `doSuspend` 返回的资源，例如连接持有器。
2. `TransactionSynchronizationManager` 中的同步器、事务名称、只读、隔离级别和实际事务 active 标记。

这些内容封装到 `SuspendedResourcesHolder`。恢复时需要以匹配顺序还原，且在新事务完成的 finally 清理后执行。

应用代码不要手工调用 `TransactionSynchronizationManager.clear()` 试图“修复事务泄漏”。这会破坏模板持有的挂起/恢复配对。应从异常栈和具体管理器 cleanup 钩子定位真正未配对的边界。

## newTransaction 与 newSynchronization 是两条轴

`DefaultTransactionStatus` 中：

- `newTransaction` 表示本逻辑范围是否负责一个新物理事务；
- `newSynchronization` 表示本逻辑范围是否新激活事务同步管理。

一个参与外层的 `REQUIRED` status 通常两者都为 false，因为外层已经创建资源和同步范围。一个没有实际事务但配置为 always-synchronization 的范围可能 `newTransaction=false`、`newSynchronization=true`。

完成时只有相应 owner 才应该清理它创建的状态，这也是 status 不能只用一个 boolean 表示的原因。

## 隔离级别、超时和只读何时生效

这些属性主要在**开始新事务**时交给 `doBegin`：

- 内层 `REQUIRED` 参与现有事务时，不会把已经开始的物理事务重新设置成另一隔离级别。
- 内层声明更短 timeout，通常不会重设外层已经运行的事务超时。
- 内层声明 `readOnly=true` 参与一个可写外层，不会把外层资源可靠切成只读事务。

默认 `validateExistingTransaction=false`，参与者与外层属性不完全相同通常仍允许加入。开启验证后，模板可拒绝不兼容的非默认隔离级别，或拒绝可写定义参与只读外层。

验证能尽早发现错误假设，但它不是动态重配置机制。

## 线程边界

命令式事务资源默认绑定当前线程：

```text
线程 A：TransactionSynchronizationManager resources = {dataSource -> connectionHolder}
线程 B：自己的 ThreadLocal Map，初始为空
```

因此以下操作不会自动携带事务：

- `new Thread(...)`
- 普通线程池 `submit`
- `CompletableFuture.supplyAsync`
- `@Async`
- 消息队列消费者

不要用普通 `TaskDecorator` 复制连接持有器到另一个线程。数据库连接和同步状态通常具有线程使用约束，复制 ThreadLocal 引用会让两个线程并发操作同一资源并破坏清理顺序。

需要异步数据库操作时，应在异步执行的方法上建立自己的事务边界；需要跨线程一致性时，应使用消息、幂等与一致性方案明确表达，而不是传递 ThreadLocal。

响应式事务是另一模型：事务上下文沿 Reactor Context 传播，可以跨线程切换但必须留在同一响应式订阅链中。

## 传播调用图怎样设计才可读

建议把事务边界放在用例服务层，并让需要不同传播语义的职责跨 Bean：

```text
OrderApplicationService.placeOrder()         REQUIRED
  ├─ inventoryService.reserve()              REQUIRED
  ├─ auditService.recordAttempt()            REQUIRES_NEW
  └─ optionalAllocation.tryAllocate()         NESTED（仅在明确支持保存点时）
```

方法命名应反映独立性，测试应同时验证业务结果和事务事件。仅在一个大类里给多个 private 方法写不同传播注解，会制造看似精细、实际上被 self-invocation 全部绕过的配置。

## 实验事件对照

`labs/spring-framework-lab` 中的记录型管理器输出：

### REQUIRES_NEW

```text
begin:tx-1
business:outer-before-requires-new:tx-1
suspend:tx-1
begin:tx-2
business:inner-requires-new:tx-2
commit:tx-2
cleanup:tx-2
resume:tx-1
business:outer-after-requires-new:tx-1
commit:tx-1
```

### NESTED

```text
begin:tx-1
savepoint-create:tx-1:sp-1
business:inner-nested-failure:tx-1
savepoint-rollback:tx-1:sp-1
savepoint-release:tx-1:sp-1
business:outer-caught-nested:tx-1
commit:tx-1
```

两个序列最大的视觉差异是：`REQUIRES_NEW` 出现 tx-2 和 suspend/resume；`NESTED` 始终是 tx-1，只多出保存点。

## 断点建议

1. `doGetTransaction`：查看具体管理器怎样从线程资源构造事务对象。
2. `isExistingTransaction`：确认判断的是底层资源活动状态，而非注解或代理存在。
3. `handleExistingTransaction`：给 propagation behavior 加条件，分别观察三种关键模式。
4. `suspend`：记录 synchronization 列表与具体资源持有器。
5. `startTransaction`：比较外层和 `REQUIRES_NEW` 的 status 标志。
6. `createAndHoldSavepoint`：确认事务对象实现 `SavepointManager`。
7. `cleanupAfterCompletion`：查看内层清理后何时恢复外层。

## Spring 6.x 边界

Spring 6 中 `AbstractPlatformTransactionManager` 的传播、挂起、保存点与 status 模型总体连续，但具体管理器随 Jakarta 生态和实现版本变化。尤其是 JPA、JTA 和响应式场景，不应仅凭 5.3 的 JDBC 实验推断支持能力。

升级时至少重测：`REQUIRES_NEW` 的资源数量、`NESTED` 保存点支持、existing transaction 属性验证、同步回调顺序和线程切换行为。

## 公开契约与实现边界

七种传播枚举、`TransactionDefinition`、`TransactionStatus`、管理器公开 API 是稳定契约。`SuspendedResourcesHolder`、status 内部 boolean 组合、挂起步骤的精确字段顺序和具体 ThreadLocal 数量属于 Spring 5.3.39 实现细节。

业务代码可以依赖传播语义，不能反射取 `SuspendedResourcesHolder` 或手工搬运线程资源。
