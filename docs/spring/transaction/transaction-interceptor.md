# TransactionInterceptor：注解怎样变成一次事务调用

## 源码入口

- 基础设施选择：`TransactionManagementConfigurationSelector.selectImports(...)`
- 自动代理注册：`AutoProxyRegistrar.registerBeanDefinitions(...)`
- Advisor 配置：`ProxyTransactionManagementConfiguration.transactionAdvisor(...)`
- 方法切点：`TransactionAttributeSourcePointcut.matches(...)`
- 拦截入口：`TransactionInterceptor.invoke(MethodInvocation)`
- 通用模板：`TransactionAspectSupport.invokeWithinTransaction(...)`
- 注解属性源：`AnnotationTransactionAttributeSource`
- 注解解析器：`SpringTransactionAnnotationParser`

以下细节针对 Spring Framework 5.3.39 的 proxy 模式。

## @EnableTransactionManagement 注册了什么

在配置类上添加：

```java
@EnableTransactionManagement
class TransactionConfig {
}
```

不等于立即扫描所有方法并开启事务。`@EnableTransactionManagement` 通过 `TransactionManagementConfigurationSelector` 按 `AdviceMode` 选择基础设施：

```text
AdviceMode.PROXY（默认）
  ├─ AutoProxyRegistrar
  │    └─ 注册或升级 InfrastructureAdvisorAutoProxyCreator
  └─ ProxyTransactionManagementConfiguration
       ├─ transactionAttributeSource
       ├─ transactionInterceptor
       └─ transactionAdvisor

AdviceMode.ASPECTJ
  └─ 导入 AspectJ 事务配置（需要对应织入能力）
```

proxy 模式下最关键的三个 Bean 是：

| 基础设施 | 作用 |
| --- | --- |
| `TransactionAttributeSource` | 判断方法是否有事务语义，并返回完整属性 |
| `TransactionInterceptor` | around advice，在目标调用前后取得并完成事务 |
| `BeanFactoryTransactionAttributeSourceAdvisor` | 把属性切点和拦截器组合为可供自动代理创建器使用的 Advisor |

自动代理创建器在 Bean 初始化阶段判断哪些 Bean 命中 Advisor，并返回 JDK 动态代理或类代理。后续客户端持有的是代理引用，事务逻辑才有机会运行。

## Advisor 的切点到底匹配什么

`BeanFactoryTransactionAttributeSourceAdvisor` 持有 `TransactionAttributeSourcePointcut`。核心判断可以概括为：

```text
matches(method, targetClass)
  ├─ 排除 TransactionManager 自身等基础设施类
  └─ transactionAttributeSource.hasTransactionAttribute(method, targetClass)
```

切点不是简单检查 `method.isAnnotationPresent(Transactional.class)`。它复用属性源，因而能处理：

- 类级 `@Transactional`；
- 实现方法覆盖接口方法的情况；
- 组合注解和 `@AliasFor`；
- Spring、JTA、EJB 等已启用解析器所支持的事务注解；
- 自定义 `TransactionAttributeSource`。

这也意味着方法匹配和真正拦截时的属性读取遵守同一套规则，避免“代理阶段认为有事务，执行阶段却拿不到属性”。

## @Transactional 如何解析为 TransactionAttribute

默认 `AnnotationTransactionAttributeSource` 会根据类路径选择可用的 `TransactionAnnotationParser`，Spring 自己的注解由 `SpringTransactionAnnotationParser` 处理。

### 属性映射

| `@Transactional` 属性 | 解析结果 | 运行阶段用途 |
| --- | --- | --- |
| `value` / `transactionManager` | qualifier | 选择指定事务管理器 |
| `propagation` | propagation behavior | 参与、新建、挂起、保存点或拒绝 |
| `isolation` | isolation level | 新事务 begin 时交给具体管理器 |
| `timeout` / `timeoutString` | timeout | 具体管理器转换为资源超时 |
| `readOnly` | read-only hint | 同步状态和具体资源优化提示 |
| `rollbackFor*` | `RollbackRuleAttribute` | 异常完成时判断 rollback |
| `noRollbackFor*` | `NoRollbackRuleAttribute` | 异常完成时显式选择 commit |
| `label` | descriptor labels | 供具体管理器或观测系统解释 |

解析结果通常是 `RuleBasedTransactionAttribute`。注解并不会保留为运行主链的唯一数据源；拦截器消费的是统一 `TransactionAttribute` 接口。

### 查找优先级

`AbstractFallbackTransactionAttributeSource.computeTransactionAttribute` 的关键顺序是：

1. 在 Spring 5.3 默认 public-only 模式下，先排除非 public 方法。
2. 根据传入方法和 `targetClass` 找到最具体实现方法，处理接口方法、桥接方法和实现类方法之间的映射。
3. 查最具体方法上的事务属性。
4. 查最具体方法声明类上的事务属性。
5. 如果具体方法与原始方法不同，再回退查原始方法。
6. 最后查原始方法的声明类。

方法级属性覆盖类级默认。代理拿到接口 `Method` 时仍会结合 target class 查实现方法，所以实验把注解写在实现类 public 方法上也能被 JDK 代理识别。

工程上仍建议把事务边界放在具体业务实现的 public 方法上，并用测试固定代理类型。接口注解、类注解、继承与不同代理策略混合时，可见性和继承语义更难仅凭代码表面判断。

### 缓存

解析需要处理最具体方法、桥接方法和合并注解，不能每次调用都重新做。`AbstractFallbackTransactionAttributeSource` 使用方法与目标类组成的 key 缓存：

- 找到时缓存 `TransactionAttribute`；
- 未找到时也缓存一个空属性哨兵；
- 后续 pointcut 匹配和拦截读取可直接复用。

因此动态修改注解对象或依赖反射更改方法元数据不是受支持的“热更新事务规则”方式。

## TransactionInterceptor.invoke 只做入口适配

同步方法的入口可以压缩为：

```text
TransactionInterceptor.invoke(invocation)
  ├─ targetClass = AopUtils.getTargetClass(invocation.getThis())
  └─ invokeWithinTransaction(
       invocation.getMethod(),
       targetClass,
       invocation::proceed)
```

`TransactionInterceptor` 同时实现 AOP Alliance `MethodInterceptor`，但绝大多数事务编排都下沉到父类 `TransactionAspectSupport`。这样同一套事务模板既可服务代理拦截器，也可被 AspectJ 支持复用。

## invokeWithinTransaction 同步主链

排除响应式和 callback-preferring 分支后，Spring 5.3.39 的经典路径如下：

```text
invokeWithinTransaction(method, targetClass, invocation)
  ├─ txAttr = txAttrSource.getTransactionAttribute(method, targetClass)
  ├─ tm = determineTransactionManager(txAttr)
  ├─ joinpointId = methodIdentification(method, targetClass)
  ├─ txInfo = createTransactionIfNecessary(tm, txAttr, joinpointId)
  │    ├─ 必要时把 joinpointId 作为事务名称
  │    ├─ status = tm.getTransaction(txAttr)
  │    └─ prepareTransactionInfo(...) 绑定当前 TransactionInfo
  ├─ try
  │    └─ returnValue = invocation.proceedWithInvocation()
  ├─ catch throwable
  │    ├─ completeTransactionAfterThrowing(txInfo, throwable)
  │    └─ 重新抛出原异常
  ├─ finally
  │    └─ cleanupTransactionInfo(txInfo)
  ├─ 可选处理 Vavr Try failure
  ├─ commitTransactionAfterReturning(txInfo)
  └─ return returnValue
```

### 为什么正常路径的 commit 在 finally 之后

finally 中的 `cleanupTransactionInfo` 恢复的是拦截器层旧调用栈，避免后续嵌套判断读到错误的 `TransactionInfo`。真正资源清理由事务管理器的 `commit/rollback` 完成流程负责。

目标方法正常返回后才调用 `commitTransactionAfterReturning`。如果 commit 自己失败，调用方看见的是提交异常，而不是业务方法的正常返回值。

### 为什么 catch 后还要重新抛异常

事务 advice 负责决定资源怎样完成，不负责吞掉业务异常。`completeTransactionAfterThrowing` 调用 rollback 或 commit 后，原异常继续传播。只有完成过程本身也抛异常时，事务系统异常才可能覆盖应用异常，并记录被覆盖异常以便诊断。

## 事务管理器怎样选择

`determineTransactionManager` 大致按以下优先级选择：

1. `TransactionAttribute` 上存在 qualifier 时，从 BeanFactory 取得对应限定事务管理器。
2. 拦截器配置了 `transactionManagerBeanName` 时，按名称查找。
3. 拦截器直接注入了默认 `TransactionManager` 时使用它。
4. 否则从 BeanFactory 按类型寻找默认 `TransactionManager` 并缓存。

多数据源系统不能只看“容器里有事务管理器”。必须确认注解限定符、默认 Bean、实际 DAO 使用的数据源三者是否对齐。事务 A 管理器无法自动回滚事务 B 数据源上的连接。

## TransactionInfo 的栈式绑定

`prepareTransactionInfo` 无论是否真正拿到 `TransactionStatus`，都会把本次 `TransactionInfo` 绑定到静态 ThreadLocal，并保存旧值：

```text
外层代理 TransactionInfo A
  → 内层代理绑定 TransactionInfo B，B.old = A
    → 内层完成恢复 A
  → 外层完成恢复进入 A 之前的值
```

`TransactionAspectSupport.currentTransactionStatus()` 读取的是这里的当前 `TransactionInfo`。它只在 AOP 调用线程和 advice 范围内可靠；业务代码用它手动 `setRollbackOnly()` 会与 Spring 紧耦合，通常优先通过异常边界或 `TransactionTemplate` 表达。

## 非事务方法也可能经过同一代理

一个代理可以包含多个 Advisor。某个 Bean 因为一个方法命中事务 Advisor 而被代理，不代表这个 Bean 的所有方法都开启事务。

对于当前调用，`txAttr == null` 时事务 advice 不创建事务，只继续目标调用。是否还能经过该 advice 取决于 Advisor method matcher 的实际匹配与代理链缓存，不要用“对象已经是代理”推断“每个方法都有事务”。

## 自调用为什么绕过

```java
@Transactional
public void outer() {
    this.inner();
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void inner() {
}
```

客户端调用 `proxy.outer()` 时只在入口经过一次代理。目标对象内部的 `this.inner()` 是普通 Java 虚调用，接收者仍是目标对象，不会回到外层代理，所以：

- 不会重新执行 pointcut；
- 不会调用第二次 `TransactionInterceptor`；
- `REQUIRES_NEW` 不会挂起外层；
- `inner` 抛出后若在 `outer` 内部被完全捕获，事务 advice 甚至看不到该异常。

常见解决方式按优先级考虑：

1. 把真正独立的事务职责拆到另一个 Bean，从代理外部调用。
2. 使用 `TransactionTemplate` 在方法内部显式划分程序式边界。
3. 必须处理 self-invocation 时评估 AspectJ weaving，而不是默认开启暴露代理并到处调用 `AopContext.currentProxy()`。

拆 Bean 通常最直观，也让传播关系可以独立测试。

## final、private 与对象来源边界

| 情况 | 为什么可能不生效 |
| --- | --- |
| `new Service()` 自己创建对象 | 不经过 Spring Bean 生命周期和自动代理 |
| private 方法 | 代理无法把外部调用路由到该方法；Spring 5.3 标准属性源默认也只认 public |
| final 类/方法 + 类代理 | 子类代理无法覆盖 |
| self-invocation | 调用接收者是 target，不是 proxy |
| 注解写在未被实际调用的重载方法 | 属性 key 对应另一 Method |
| 只给 Controller 加事务而异步下发工作 | 新线程已经越过原事务边界 |

JDK 动态代理围绕接口方法建立调用面；类代理通过子类覆盖建立调用面。两者都不能让 `this` 调用自动跳回代理。

## Advisor 顺序会改变事务包裹范围

事务 Advisor 默认顺序较低，可以通过 `@EnableTransactionManagement(order = ...)` 调整。多个 advice 的顺序决定谁在外层：

```text
retry advice 在外，transaction advice 在内
  → 每次重试可开启一个新事务

transaction advice 在外，retry advice 在内
  → 多次尝试可能共享同一个事务，第一次失败已标记 rollback-only
```

缓存、重试、监控、安全与事务 advice 混用时，不能只确认它们“都生效”；还要检查代理链顺序是否符合资源边界。

## callback-preferring 与响应式分支

### CallbackPreferringPlatformTransactionManager

如果事务管理器偏好回调式执行，`invokeWithinTransaction` 会把目标调用包装为回调交给管理器执行，再在回调边界协调异常规则。经典 `getTransaction/commit/rollback` 路径不是唯一实现形式。

### ReactiveTransactionManager

当选择的是响应式事务管理器时，Spring 根据方法返回类型进入响应式支持路径。事务上下文跟随 Publisher 的订阅链和 Reactor Context，不绑定普通 ThreadLocal。

返回响应式类型却选择命令式管理器，或返回普通值却选择响应式管理器，都会产生契约不匹配。调试时先确认 manager 类型，再判断应该观察 ThreadLocal 还是 Reactor Context。

## Spring 5.3 的返回值特殊处理

5.3.39 在目标方法正常返回后可对类路径上的 Vavr `Try` failure 应用回滚规则并设置 rollback-only。它不会像 Spring 6.1+ 那样检查返回时已经异常完成的 `Future`/`CompletableFuture`。

无论哪个版本，事务 advice 都不会为了等待未来结果而无限延长同步数据库事务。异步方法的真实事务边界应在执行数据库工作的线程中重新建立。

## 变量快照

| 断点 | 关键变量 | 典型判断 |
| --- | --- | --- |
| `TransactionAttributeSourcePointcut.matches` | `method`、`targetClass`、`tas` | 是否命中事务 Advisor |
| `AbstractFallbackTransactionAttributeSource.computeTransactionAttribute` | `specificMethod`、`txAttr` | 属性最终来自实现方法还是类 |
| `TransactionInterceptor.invoke` | `invocation.getThis()`、`targetClass` | 当前代理背后的真实目标类型 |
| `invokeWithinTransaction` | `txAttr`、`tm` | 传播规则和管理器是否正确 |
| `prepareTransactionInfo` | `txInfo.oldTransactionInfo` | 嵌套代理调用栈怎样恢复 |
| `completeTransactionAfterThrowing` | `ex`、`txAttr.rollbackOn(ex)` | 为什么本次选择 commit 或 rollback |

## 推荐断点路径

1. 给实验方法名加条件，在 `TransactionAttributeSourcePointcut.matches` 确认它进入代理候选。
2. 进入 `computeTransactionAttribute`，记录 `method` 与 `specificMethod` 是否不同。
3. 在 `TransactionInterceptor.invoke` 检查代理调用只出现一次还是发生跨 Bean 二次调用。
4. 在 `determineTransactionManager` 确认多管理器场景的 qualifier。
5. 在 `createTransactionIfNecessary` 观察返回 status 是否为新事务。
6. 在目标方法内查看调用栈，确认上方存在 `TransactionInterceptor`。
7. 异常场景进入 `completeTransactionAfterThrowing`，记录 `rollbackOn` 结果。

## Spring 6.x 差异

- Spring 6 要求 Java 17，事务代理和 `TransactionAspectSupport` 主干仍可辨认。
- 6.0 起，类代理默认可识别 protected/package-visible 事务方法；接口代理仍围绕 public 接口方法。private、final 和 self-invocation 并未因此消失。
- 6.1 增加对返回时已异常完成的 `Future`（包括 `CompletableFuture`）的回滚适配，常用于 `@Async` 方法；5.3.39 不应照搬该结论。
- 6.2 提供更直接的全局 rollback 规则配置。升级后要核对全局规则与方法级 `rollbackFor/noRollbackFor` 的组合，而不是只看旧注解。

## 公开契约与实现边界

`TransactionAttributeSource`、`TransactionInterceptor`、Advisor 顺序配置和事务管理器选择是可扩展契约。具体基础设施 Bean 名称、缓存哨兵、`TransactionInfo` 字段与 `invokeWithinTransaction` 内部分支属于实现细节。

业务代码应通过公开注解与事务 SPI 表达边界；诊断工具可以观察内部实现，但不应反射修改其 ThreadLocal 或属性缓存。
