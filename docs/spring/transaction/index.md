# Spring Transaction 源码地图

本专题以 **Spring Framework 5.3.39 + Java 8** 为可执行基线，讨论 Spring 声明式事务从注解解析、AOP 拦截、传播决策、资源绑定到提交或回滚的完整链路。实验不连接数据库，但真实复用 `TransactionInterceptor`、`TransactionAspectSupport` 与 `AbstractPlatformTransactionManager`，所以能观察到 Spring 自己的模板算法，而不是手写一套相似流程。

完成单独事务主线后，沿 [MVC 请求怎样进入事务代理](/spring/deep-dive/request-transaction) 跟踪 Controller 到 Service proxy，再用 [异常、传播与清理边界](/spring/deep-dive/failure-boundaries) 区分事务完成、资源解绑和 HTTP 异常解析的先后。

## 先给出一句话结论

`@Transactional` 不是“看到注解就自动开启数据库事务”。它只提供元数据；真正的运行链是：

```text
调用者
  → Spring AOP 代理
    → TransactionInterceptor
      → TransactionAttributeSource 解析方法规则
      → 选择 TransactionManager
      → getTransaction(...) 取得或创建 TransactionStatus
      → invocation.proceed() 调用目标方法
      → commit(...) 或 rollback(...)
      → 清理本次拦截器和线程资源状态
```

因此一个事务是否生效，至少同时依赖四个条件：

1. 目标对象由 Spring 容器管理并被事务代理包装。
2. 本次方法调用从代理外部进入，没有被 `this` 自调用绕开。
3. `TransactionAttributeSource` 能为目标方法解析到事务属性。
4. 选中的 `TransactionManager` 能管理业务实际使用的资源。

注解存在但其中任何一环不成立，都可能出现“代码上有 `@Transactional`，行为上却没有事务”的现象。

## 动画：一条调用怎样跨过三层状态

<SpringTransactionAnimation />

动画把 13 个源码动作分为四段：事务拦截、`REQUIRES_NEW`、`NESTED`、提交与回滚。先逐步点击，不要急着记方法名；重点观察外层资源是否处于 `ACTIVE`、`SUSPENDED` 或 `GLOBAL_ROLLBACK_ONLY`。

## 三层职责不要混淆

| 层次 | 核心类型 | 负责什么 | 不负责什么 |
| --- | --- | --- | --- |
| 声明与匹配 | `@Transactional`、`TransactionAttributeSource` | 找到传播、隔离、超时、只读与回滚规则 | 不直接操作连接，也不执行提交 |
| 拦截与编排 | `TransactionInterceptor`、`TransactionAspectSupport` | 在目标方法前后组织取事务、异常判定与完成动作 | 不知道 JDBC、JPA 等资源怎样真正提交 |
| 事务策略 | `PlatformTransactionManager` | 提供 `getTransaction`、`commit`、`rollback` 统一契约 | 接口本身不规定底层必须是数据库 |
| 模板算法 | `AbstractPlatformTransactionManager` | 处理传播、挂起、保存点、rollback-only、同步回调与清理 | 具体 begin/commit/rollback 交给子类 |
| 资源实现 | `DataSourceTransactionManager`、`JpaTransactionManager` 等 | 绑定并操作连接、EntityManager 或其他资源 | 不负责 AOP 方法匹配 |

`TransactionInterceptor` 与具体事务管理器是策略协作关系。同一个拦截器可以按限定符选择不同管理器；同一个 `AbstractPlatformTransactionManager` 模板也可以由 JDBC、JPA 或教学用资源实现不同钩子。

## 源码入口

### 注解基础设施

- `@EnableTransactionManagement`
- `TransactionManagementConfigurationSelector`
- `AutoProxyRegistrar`
- `ProxyTransactionManagementConfiguration`
- `BeanFactoryTransactionAttributeSourceAdvisor`
- `TransactionAttributeSourcePointcut`

### 拦截主链

- `TransactionInterceptor.invoke(MethodInvocation)`
- `TransactionAspectSupport.invokeWithinTransaction(...)`
- `AnnotationTransactionAttributeSource.getTransactionAttribute(...)`
- `TransactionAspectSupport.determineTransactionManager(...)`
- `TransactionAspectSupport.createTransactionIfNecessary(...)`
- `TransactionAspectSupport.completeTransactionAfterThrowing(...)`

### 传播与完成

- `AbstractPlatformTransactionManager.getTransaction(...)`
- `AbstractPlatformTransactionManager.handleExistingTransaction(...)`
- `AbstractPlatformTransactionManager.suspend(...)` / `resume(...)`
- `AbstractPlatformTransactionManager.commit(...)`
- `AbstractPlatformTransactionManager.processCommit(...)`
- `AbstractPlatformTransactionManager.processRollback(...)`
- `TransactionSynchronizationManager`

## 先建立三个不同的“当前事务”概念

### TransactionAttribute：规则

这是从注解或其他配置解析出的统一语义输入。常见内容包括：

- `propagationBehavior`
- `isolationLevel`
- `timeout`
- `readOnly`
- `qualifier` / 事务管理器名称
- `rollbackRules`
- 用于监控和诊断的事务名称与标签

它回答“这次方法调用希望怎样管理事务”，不代表事务已经存在。

### TransactionStatus：本次参与状态

`PlatformTransactionManager.getTransaction` 返回一个 `TransactionStatus`。它描述的是**本次调用相对于资源事务的身份**：

- 本次是否创建了新事务；
- 是否持有保存点；
- 是否设置了本地 rollback-only；
- 是否已经完成；
- 具体 status 实现还会记录同步范围、底层事务对象和挂起资源等完成信息。

两个 `REQUIRED` 方法可以拥有两个不同的逻辑 `TransactionStatus`，却参与同一个底层物理事务。不要把“每次 getTransaction 都返回 status”理解成“每次都新开数据库事务”。

### 线程资源：物理上下文

传统命令式事务通常由 `TransactionSynchronizationManager` 使用 ThreadLocal 保存：

- 资源工厂到资源持有器的 Map，例如 `DataSource → ConnectionHolder`；
- 当前激活的 `TransactionSynchronization` 列表；
- 当前事务名称、只读标记、隔离级别；
- 是否存在实际活动事务。

这个上下文回答“当前线程正在使用哪个连接或会话”。它不会自动传播到新线程。响应式事务使用 Reactor Context，不应套用 ThreadLocal 模型。

## TransactionInfo 与 TransactionStatus 也不是一个对象

`TransactionAspectSupport` 还维护一个 `TransactionInfo` ThreadLocal。它属于拦截器层，保存：

- 当前 `TransactionManager`；
- 当前 `TransactionAttribute`；
- joinpoint 标识；
- 当前 `TransactionStatus`；
- 进入本次调用之前的旧 `TransactionInfo`。

每次代理嵌套调用都会先绑定新的 `TransactionInfo`，finally 中再恢复旧值。即使某个传播模式没有实际事务，仍可能创建 `TransactionInfo` 来维持正确的调用栈。它与 `TransactionSynchronizationManager` 绑定的资源 ThreadLocal 是两套用途不同的状态。

## 一次正常调用的完整时间线

```text
客户端调用代理方法
  │
  ├─ Advisor 的 pointcut 判断方法是否存在 TransactionAttribute
  ├─ TransactionInterceptor.invoke
  │    └─ TransactionAspectSupport.invokeWithinTransaction
  │         ├─ txAttrSource.getTransactionAttribute(method, targetClass)
  │         ├─ determineTransactionManager(txAttr)
  │         ├─ createTransactionIfNecessary(...)
  │         │    └─ transactionManager.getTransaction(txAttr)
  │         ├─ prepareTransactionInfo(...) 绑定 TransactionInfo
  │         ├─ invocation.proceedWithInvocation()
  │         ├─ cleanupTransactionInfo(...) 恢复旧 TransactionInfo
  │         └─ commitTransactionAfterReturning(...)
  │              └─ transactionManager.commit(status)
  └─ 返回目标方法结果
```

这里有个不直观的细节：经典同步路径中，`cleanupTransactionInfo` 恢复的是拦截器自己的 ThreadLocal 调用栈；事务管理器在 `commit` 或 `rollback` 的完成清理中解绑资源。两种清理不能合并成一句“finally 清空所有 ThreadLocal”。

## 异常调用的两个判断层

目标方法抛出异常时，首先由事务属性判断：

```text
txAttr.rollbackOn(exception)
  ├─ true  → transactionManager.rollback(status)
  └─ false → transactionManager.commit(status)
```

第二层在 `AbstractPlatformTransactionManager.commit(status)` 内。即使拦截器选择了 commit，只要 status 或共享资源已经是 rollback-only，事务管理器仍会改走回滚。受检异常默认“提交”也不是无条件强制提交，它仍服从已有 rollback-only 标记。

## 三个传播模式先这样理解

| 模式 | 已有外层事务时 | 是否独立物理事务 | 内层回滚对外层的直接影响 |
| --- | --- | --- | --- |
| `REQUIRED` | 参与外层 | 否 | 参与者失败通常把共享资源标记 rollback-only |
| `REQUIRES_NEW` | 挂起外层并开始新事务 | 是 | 内层提交/回滚独立，完成后恢复外层 |
| `NESTED` | 在外层事务创建保存点 | 否 | 可只回滚到保存点；是否支持取决于管理器和资源 |

`NESTED` 不是轻量版 `REQUIRES_NEW`。前者共享同一物理事务，外层最终回滚会连同嵌套阶段一起撤销；后者一旦内层提交，外层随后回滚通常不会撤销已经提交的独立事务。

## 阅读路线

1. [TransactionInterceptor 主链](./transaction-interceptor.md)：理解注解怎样变成一次 around advice。
2. [传播、挂起与恢复](./propagation-suspension.md)：逐条跟 `getTransaction` 的分支和三种关键传播模式。
3. [提交、回滚与 rollback-only](./commit-rollback.md)：理解异常规则、意外回滚与同步回调。
4. [断点实验](./debug-lab.md)：运行 12 个测试，把每条结论映射到真实事件。

如果同时学习 Spring AOP，建议先理解代理和拦截器链，再进入本专题；事务 advice 本质上就是一个有资源生命周期的 `MethodInterceptor`。

## 第一次断点只观察这些变量

| 位置 | 变量 | 要回答的问题 |
| --- | --- | --- |
| `TransactionInterceptor.invoke` | `invocation.method`、`targetClass` | 本次是否真的经过事务代理 |
| `invokeWithinTransaction` | `txAttr`、`tm`、`joinpointIdentification` | 解析到什么规则，选择哪个管理器 |
| `getTransaction` | `definition`、`transaction`、`debugEnabled` | 线程上是否已经存在事务 |
| `handleExistingTransaction` | `propagationBehavior` | 参与、挂起、保存点还是拒绝 |
| `commit` | `status.isLocalRollbackOnly()`、`isGlobalRollbackOnly()` | 为什么表面上 commit 却实际 rollback |
| `cleanupAfterCompletion` | `status.completed`、`suspendedResources` | 当前资源怎样解绑，外层怎样恢复 |

## 常见误区快速校正

### “方法抛异常就一定回滚”

错误。默认只对 `RuntimeException` 和 `Error` 回滚。受检异常默认提交，除非配置 `rollbackFor` 或事务已经被标记 rollback-only。

### “异常被 catch 就一定不会回滚”

错误。如果异常穿过一个参与共享事务的内层代理，内层拦截器可能已经把资源标记 rollback-only；外层捕获只改变 Java 控制流，不能清除该标记。

### “REQUIRES_NEW 就是在新线程执行”

错误。它通常仍在当前线程，只是解绑外层资源并绑定一套新资源。新线程与新事务是两个完全不同的概念。

### “readOnly=true 会阻止所有写 SQL”

错误。它首先是事务定义提示。具体管理器、ORM 和数据库驱动可能据此调整 flush 或连接只读状态，但 Spring 统一事务接口不承诺成为安全写屏障。

### “事务注解放在 private 方法也会生效”

代理模式下通常不会。Spring 5.3 的标准注解属性源默认只考虑 public 方法，且代理必须能够拦截本次外部调用。

## Spring 5.3 与 Spring 6 的边界

| 主题 | Spring 5.3.39 | Spring 6.x |
| --- | --- | --- |
| Java 基线 | Java 8 | Java 17 |
| 同步拦截主链 | `TransactionInterceptor → TransactionAspectSupport` | 核心结构延续，内部细节需按目标小版本核对 |
| class-based proxy 方法可见性 | 标准 `@Transactional` 主要面向 public 方法 | 6.0 起类代理可支持 protected/package-visible 方法；接口代理仍要求可代理的 public 接口方法 |
| `Future` 异常结果 | 5.3 同步路径不按 `Future` 的异常完成状态自动判回滚 | 6.1 起支持方法返回时已 exceptionally completed 的 `Future`/`CompletableFuture` 触发回滚 |
| 全局异常默认规则 | 主要通过每个属性或自定义属性源配置 | 6.2 增加更直接的全局 rollback 规则配置能力，使用时核对具体 API |
| Jakarta 迁移 | 仍兼容 Java EE 时代生态 | Java/Jakarta 基线变化会影响 JPA 等资源集成，但事务 SPI 主体连续 |

“Spring 6 支持非 public 事务方法”不能简化成所有代理都支持 private 方法：private、final、self-invocation 和接口代理的可见性限制仍需分别判断。

## 公开契约与实现细节

业务代码可以稳定依赖：

- `@Transactional` 的公开属性语义；
- `PlatformTransactionManager` / `TransactionManager`；
- `TransactionDefinition`、`TransactionStatus`；
- `TransactionSynchronization` 与编程式事务 API。

以下内容适合源码学习和断点诊断，不应在业务代码中反射依赖：

- `TransactionInfo` ThreadLocal 的字段结构；
- `AbstractPlatformTransactionManager` 内部分支的精确排列；
- 事务属性缓存 key 和具体 Map；
- `SuspendedResourcesHolder` 的内部字段；
- 自动代理创建器与 Advisor 的实际 Bean 名称。

升级 Spring 小版本时，应针对目标 tag 重新核对实现细节，并用本专题实验固定真正依赖的公开行为。
