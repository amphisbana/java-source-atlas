# Spring Transaction 断点实验手册

## 实验目标

模块：`labs/spring-framework-lab`

主类：`io.github.javasourceatlas.spring.transaction.SpringTransactionDebugLab`

基线：Java 8、Spring Framework 5.3.39、JUnit 5。

实验解决一个现实问题：直接给 `DataSourceTransactionManager` 配数据库，初学者容易把注意力消耗在连接、DDL 和驱动日志上；完全手写伪代码又无法证明 Spring 的传播模板确实这样运行。

本模块采用中间方案：

```text
Spring 真实部分
  ├─ @EnableTransactionManagement
  ├─ JDK 动态代理
  ├─ AnnotationTransactionAttributeSource
  ├─ TransactionInterceptor
  ├─ TransactionAspectSupport
  └─ AbstractPlatformTransactionManager

教学替身部分
  └─ RecordingTransactionManager
       ├─ ThreadLocal<ResourceState> 模拟线程资源
       ├─ SmartTransactionObject 暴露 global rollback-only
       ├─ SavepointManager 模拟保存点
       └─ doBegin/doCommit/doRollback 等钩子记录事件
```

所以传播和完成分支由 Spring 5.3.39 真实源码选择，只有最底层资源动作被替换为内存事件。

## 运行方式

从仓库根目录执行：

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest='TransactionMetadataTest,TransactionPropagationTest,TransactionProxyBoundaryTest,TransactionRollbackRuleTest' test

mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=transaction
```

模块也能单独运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest='TransactionMetadataTest,TransactionPropagationTest,TransactionProxyBoundaryTest,TransactionRollbackRuleTest' test

mvn -f labs/spring-framework-lab/pom.xml compile exec:java -Dexec.args=transaction
```

在 macOS 上切 Java 8 时，可以先确认：

```bash
java -version
mvn -version
```

两条命令显示的 Java 必须一致。Maven 可能使用与当前 shell 中 `java` 不同的 `JAVA_HOME`。

## 模块结构

| 文件 | 职责 |
| --- | --- |
| `TransactionLabConfiguration` | 启用 proxy 模式事务管理并注册两个业务 Bean |
| `TransactionScenarioServiceImpl` | 外层正常、异常、自调用和线程边界场景 |
| `InnerWorkServiceImpl` | 跨第二个代理触发 REQUIRED、REQUIRES_NEW、NESTED |
| `RecordingTransactionManager` | 复用 Spring 模板，记录资源钩子与保存点事件 |
| `TransactionEvents` | 保存有序事件快照 |
| `SpringTransactionDebugLab` | 可直接运行的五组演示 |
| `TransactionPropagationTest` | 三种关键传播模式 |
| `TransactionRollbackRuleTest` | 正常返回及三种异常规则 |
| `TransactionProxyBoundaryTest` | JDK 代理、自调用、新线程 |
| `TransactionMetadataTest` | 直接解析传播与 rollbackFor 元数据 |

## 为什么需要两个业务 Bean

`TransactionScenarioServiceImpl` 注入 `InnerWorkService` 接口，容器分别为两个 Bean 创建 JDK 代理：

```text
client
  → TransactionScenarioService proxy
    → TransactionScenarioServiceImpl.outer...()
      → InnerWorkService proxy
        → InnerWorkServiceImpl.inner...()
```

第二次代理入口是传播规则生效的必要条件。如果把 inner 方法放在同一个类并用 `this.inner()` 调用，`REQUIRES_NEW` 与 `NESTED` 都不会重新进入事务拦截器。

实验故意同时保留一个 self-invocation 案例，让两个调用形态可以直接对照。

## 记录型事务管理器怎样接入模板

`RecordingTransactionManager` 继承 `AbstractPlatformTransactionManager`，实现以下钩子：

| 钩子 | 实验动作 | 对应真实管理器概念 |
| --- | --- | --- |
| `doGetTransaction` | 包装当前线程 `ResourceState` | 从 ThreadLocal 资源创建事务对象 |
| `isExistingTransaction` | 检查资源是否 active | 连接/会话是否已有活动事务 |
| `doBegin` | 创建 `tx-N` 并绑定 ThreadLocal | 取得连接、设置属性、begin |
| `doSuspend` | 解绑并返回外层资源 | 暂存 ConnectionHolder 等资源 |
| `doResume` | 重新绑定外层资源 | 恢复挂起资源 |
| `doSetRollbackOnly` | 标记共享资源 | 参与者把物理事务标记回滚 |
| `doCommit` | 记录 commit，关闭资源状态 | 提交具体资源事务 |
| `doRollback` | 记录 rollback，关闭资源状态 | 回滚具体资源事务 |
| `doCleanupAfterCompletion` | 移除当前 ThreadLocal | 解绑并重置资源持有器 |

内部事务对象实现 `SmartTransactionObject`，让 `DefaultTransactionStatus.isGlobalRollbackOnly()` 能看到共享回滚标记；同时实现 `SavepointManager`，让模板真实调用 `createAndHoldSavepoint`、`rollbackToHeldSavepoint` 和保存点释放。

## 先运行主类观察事件

主类按顺序运行：

1. `REQUIRES_NEW` 挂起与恢复。
2. `NESTED` 保存点回滚。
3. self-invocation。
4. 新线程事务边界。
5. REQUIRED 参与者导致的意外回滚。

输出中的事务编号在同一 ApplicationContext 内递增，核心形态如下。

### REQUIRES_NEW

```text
begin:tx-1:...outerWithRequiresNew
business:outer-before-requires-new:tx-1
suspend:tx-1
begin:tx-2:...requiresNewSuccess
business:inner-requires-new:tx-2
commit:tx-2
cleanup:tx-2
resume:tx-1
business:outer-after-requires-new:tx-1
commit:tx-1
cleanup:tx-1
```

断言重点不是编号，而是 tx-1 被挂起后出现独立 tx-2，tx-2 清理完成才恢复 tx-1。

### NESTED

```text
begin:tx-3:...outerCatchesNestedFailure
business:outer-before-nested:tx-3
savepoint-create:tx-3:sp-1
business:inner-nested-failure:tx-3
savepoint-rollback:tx-3:sp-1
savepoint-release:tx-3:sp-1
business:outer-caught-nested:tx-3
commit:tx-3
cleanup:tx-3
```

全程没有 tx-4，也没有 suspend/resume；保存点与外层共用 tx-3。

### self-invocation

```text
begin:tx-4:...outerSelfInvocation
business:self-outer:tx-4
business:self-inner:tx-4
commit:tx-4
cleanup:tx-4
```

`requiresNewSelfStep` 明明标注 `REQUIRES_NEW`，事件中却只有一个 begin，也没有 suspend。这不是事务管理器忽略传播，而是调用从未再次经过代理。

### 新线程

```text
begin:tx-5:...observeTransactionFromNewThread
business:child-thread:null
commit:tx-5
cleanup:tx-5
```

外层线程有 tx-5，子线程读取同一个管理器的 ThreadLocal 得到 null。

### REQUIRED rollback-only

```text
begin:tx-6:...outerCatchesRequiredFailure
business:inner-required-failure:tx-6
mark-rollback-only:tx-6
business:outer-caught-required:tx-6
rollback:tx-6
cleanup:tx-6
client:UnexpectedRollbackException
```

外层捕获业务异常后正常返回，但无法清除 tx-6 的共享标记；最外层 commit 被改写为 rollback。

## 12 个自动测试验证什么

| 测试类 | 数量 | 核心断言 |
| --- | ---: | --- |
| `TransactionPropagationTest` | 3 | 挂起恢复、保存点、REQUIRED 意外回滚 |
| `TransactionRollbackRuleTest` | 4 | 正常提交、运行时回滚、受检默认提交、rollbackFor 回滚 |
| `TransactionProxyBoundaryTest` | 3 | JDK 代理、自调用只有一次 begin、新线程无事务 |
| `TransactionMetadataTest` | 2 | REQUIRES_NEW 属性和受检异常规则被正确解析 |

测试断言公开可观察事件的相对顺序，不依赖 Spring 内部日志文本，也不把完整事务名称写死。

## 实验一：观察元数据解析

运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest=TransactionMetadataTest test
```

建议断点：

1. `AbstractFallbackTransactionAttributeSource.getTransactionAttribute`
2. `computeTransactionAttribute`
3. `AnnotationTransactionAttributeSource.findTransactionAttribute(Method)`
4. `SpringTransactionAnnotationParser.parseTransactionAnnotation`

观察：

| 变量 | 预期 |
| --- | --- |
| `method` | 可能来自接口调用面 |
| `targetClass` | `TransactionScenarioServiceImpl` |
| `specificMethod` | 实现类上的 public 方法 |
| `txAttr.propagationBehavior` | self step 为 `PROPAGATION_REQUIRES_NEW` |
| `txAttr.rollbackOn(CheckedBusinessException)` | 默认方法 false，rollbackFor 方法 true |

第一次调用会解析并写缓存，后续可能直接命中缓存。需要观察解析过程时，使用新建测试 JVM 或在第一次调用前设置断点。

## 实验二：观察 TransactionInterceptor

运行一个最小正常提交测试：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest=TransactionRollbackRuleTest#shouldCommitAfterNormalReturn test
```

断点顺序：

```text
TransactionInterceptor.invoke
  → TransactionAspectSupport.invokeWithinTransaction
    → createTransactionIfNecessary
      → AbstractPlatformTransactionManager.getTransaction
        → RecordingTransactionManager.doBegin
    → TransactionScenarioServiceImpl.commitNormally
    → commitTransactionAfterReturning
      → AbstractPlatformTransactionManager.commit
        → RecordingTransactionManager.doCommit
```

在 `invokeWithinTransaction` 记录：

- `method`
- `targetClass`
- `txAttr`
- `tm`
- `joinpointIdentification`
- `txInfo.transactionStatus.newTransaction`

目标方法调用栈上方应能看到 JDK `$Proxy`、Spring AOP 拦截器链和 `TransactionInterceptor`。

## 实验三：观察 REQUIRED 参与者标记

运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest=TransactionPropagationTest#shouldReportUnexpectedRollbackForCaughtRequiredFailure test
```

设置条件断点：

| 位置 | 条件或观察 |
| --- | --- |
| `getTransaction` | definition name 包含 `requiredFailure` |
| `handleExistingTransaction` | propagation 为 REQUIRED |
| `processRollback` | `status.isNewTransaction() == false` |
| `RecordingTransactionManager.doSetRollbackOnly` | resource id 为 tx-1 |
| 外层 `commit` | `status.isGlobalRollbackOnly() == true` |

内层 `TransactionStatus` 和外层 status 不是同一个对象，但它们的事务对象都引用 tx-1 资源。内层 mark 后，外层通过 `SmartTransactionObject.isRollbackOnly` 读到全局标记。

## 实验四：观察 REQUIRES_NEW

运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest=TransactionPropagationTest#shouldSuspendAndResumeForRequiresNew test
```

断点：

1. `handleExistingTransaction` 的 REQUIRES_NEW 分支。
2. `AbstractPlatformTransactionManager.suspend`。
3. `RecordingTransactionManager.doSuspend`。
4. `startTransaction` 与 `doBegin`。
5. `cleanupAfterCompletion`。
6. `AbstractPlatformTransactionManager.resume` 与 `doResume`。

关键快照：

| 时刻 | manager.currentTransactionId() | status.suspendedResources |
| --- | --- | --- |
| 外层业务进入 | tx-1 | 无 |
| doSuspend 后 | null | 正在组装 tx-1 挂起状态 |
| 内层 begin 后 | tx-2 | 内层 status 保存 tx-1 holder |
| 内层 cleanup 后、resume 前 | null | tx-1 仍等待恢复 |
| resume 后 | tx-1 | 已消费挂起状态 |

## 实验五：观察 NESTED

运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest=TransactionPropagationTest#shouldRollbackNestedSavepointAndCommitOuterTransaction test
```

断点顺序：

```text
handleExistingTransaction(NESTED)
  → DefaultTransactionStatus.createAndHoldSavepoint
    → RecordingTransactionObject.createSavepoint
  → inner target throws
  → processRollback
    → rollbackToHeldSavepoint
      → rollbackToSavepoint
      → releaseSavepoint
  → outer target catches
  → outer doCommit
```

确认 nested status：

- `isNewTransaction=false`
- `hasSavepoint=true`
- 资源 id 与外层相同
- 没有 `SuspendedResourcesHolder`

## 实验六：观察异常回滚规则

分别运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest=TransactionRollbackRuleTest test
```

在 `RuleBasedTransactionAttribute.rollbackOn` 查看 `winner` 和 `deepest`：

| 方法 | 异常 | 预期 rollbackOn |
| --- | --- | --- |
| `runtimeFailure` | `IllegalStateException` | true（默认） |
| `checkedFailureWithDefaultRule` | `CheckedBusinessException` | false（默认） |
| `checkedFailureWithRollbackRule` | 同上 | true（显式规则） |

受检异常默认方法仍会在 `completeTransactionAfterThrowing` 调用 commit，随后原异常重新抛出。不要因测试捕获到异常就误判发生了 rollback。

## 实验七：观察代理边界

运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest=TransactionProxyBoundaryTest test
```

三个断言：

1. `Proxy.isProxyClass(service.getClass()) == true`。
2. `outerSelfInvocation` 只有一次 `TransactionInterceptor.invoke`，内部 step 与外层事务 id 相同。
3. 新线程读取事务 id 为 null，外层线程仍正常提交。

在 IDE 中给 `TransactionInterceptor.invoke` 设置方法断点时，Spring 基础设施初始化可能产生额外噪声。可以按方法名 `outerSelfInvocation` 或 `requiresNewSelfStep` 加条件；后者在 self-invocation 场景不会命中。

## 推荐 IntelliJ 调试配置

- JDK：Corretto/OpenJDK 8。
- Main class：`SpringTransactionDebugLab`。
- Use classpath of module：`spring-framework-lab`。
- 禁用“所有异常都暂停”，只对 `UnexpectedRollbackException` 或实验异常设置断点。
- 第一次跟源码时关闭异步调试代理，避免调用栈被额外框架包装。

如果 Maven 依赖没有附带 sources，可让 IDE 下载 Spring 5.3.39 source artifact，确保断点落在与运行 jar 相同版本的源码上。

## 可继续修改的实验参数

### 开启 existing transaction 验证

在 `RecordingTransactionManager` 构造时调用：

```java
setValidateExistingTransaction(true);
```

再添加外层只读、内层可写或不同隔离级别场景，观察 `handleExistingTransaction` 提前拒绝不兼容定义。

### 改变参与者回滚策略

```java
setGlobalRollbackOnParticipationFailure(false);
```

重新运行 REQUIRED 测试。注意该开关只改变模板标记策略，不证明真实数据库资源在错误后仍可安全提交。

### 提前报告全局 rollback-only

```java
setFailEarlyOnGlobalRollbackOnly(true);
```

增加三层 REQUIRED 调用，比较异常是在中间参与范围还是最外层边界报告。

这些变体应写成新测试，不要改掉当前基准场景；基准用于长期对照默认行为。

## 实验边界

记录型事务管理器刻意不模拟：

- 数据库隔离级别与并发可见性；
- JDBC 锁、死锁和连接池等待；
- ORM flush、一级缓存和脏检查；
- 真实数据库 savepoint 对锁和序列的行为；
- JTA/XA 两阶段提交；
- 提交失败后的启发式结果；
- 响应式 Reactor Context。

因此本 Lab 用于验证 Spring 代理、传播模板和完成算法。要回答“某数据库在保存点回滚后是否释放某类锁”等问题，必须增加真实集成测试。

## 从教学 Lab 迁移到真实数据库测试

建议保留当前测试，再新增独立集成模块：

1. 使用目标数据库和正式驱动，不用 H2 替代数据库特有语义。
2. 配置与生产一致的 `PlatformTransactionManager`。
3. 用两条真实连接验证隔离与锁，而不仅断言方法调用次数。
4. 为 REQUIRES_NEW 设置小连接池和超时，压测资源占用。
5. 对 NESTED 明确断言保存点后的数据、锁和 ORM 上下文。
6. 让测试在 commit 阶段制造约束错误，观察异常与回调顺序。

记录型单元测试回答“Spring 选择了哪条分支”，集成测试回答“资源系统执行这条分支后发生了什么”。两者不能互相替代。

## 完成标准

一次修改满足以下条件才算没有破坏教程契约：

- Java 8 下编译通过。
- 12 个测试全部通过。
- 主类五组事件可以运行且顺序与文档一致。
- 测试不依赖外部数据库、网络和执行顺序。
- 新增 Java 方法保留中文注释。
- Spring 版本变化时重新核对源码入口与差异说明。
