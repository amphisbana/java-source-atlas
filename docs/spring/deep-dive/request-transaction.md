# MVC 请求怎样进入事务代理：从 HandlerMethod 到资源提交

本章跟踪一次正常请求。重点不是重新列举 MVC 组件，而是证明三件事：路由表怎样关联 IOC Bean、Controller 怎样拿到 Service 代理、事务资源为什么能被同一线程中的 DAO 复用。

## 源码入口

| 阶段 | 类与方法 |
| --- | --- |
| 路由建立 | `AbstractHandlerMethodMapping.afterPropertiesSet()` / `initHandlerMethods()` |
| Bean 方法检测 | `processCandidateBean(...)` / `detectHandlerMethods(...)` |
| 请求分派 | `DispatcherServlet.doDispatch(...)` |
| Handler 解析 | `AbstractHandlerMethodMapping.getHandlerInternal(...)` |
| Bean 名称解析 | `HandlerMethod.createWithResolvedBean()` |
| 适配器执行 | `RequestMappingHandlerAdapter.handleInternal(...)` / `invokeHandlerMethod(...)` |
| 参数和方法调用 | `InvocableHandlerMethod.invokeForRequest(...)` / `doInvoke(...)` |
| AOP 入口 | `JdkDynamicAopProxy.invoke(...)` / `CglibAopProxy.DynamicAdvisedInterceptor.intercept(...)` |
| 链推进 | `ReflectiveMethodInvocation.proceed()` |
| 事务入口 | `TransactionInterceptor.invoke(...)` / `TransactionAspectSupport.invokeWithinTransaction(...)` |
| 事务获取 | `AbstractPlatformTransactionManager.getTransaction(...)` |
| JDBC 开始 | `DataSourceTransactionManager.doBegin(...)` |
| 连接复用 | `DataSourceUtils.doGetConnection(...)` |
| 返回值 | `HandlerMethodReturnValueHandler.handleReturnValue(...)` |

## refresh 后，MVC 已经拿到哪些 IOC 产物

MVC 请求不是从扫描注解开始。应用启动时已经完成两类准备：

```text
WebApplicationContext refresh
  -> 创建 RequestMappingHandlerMapping
      -> afterPropertiesSet
      -> initHandlerMethods
      -> 扫描候选 Controller Bean 名称/类型
      -> detectHandlerMethods
      -> MappingRegistry.register

DispatcherServlet 初始化
  -> onRefresh
  -> initStrategies
      -> initHandlerMappings
      -> initHandlerAdapters
      -> initHandlerExceptionResolvers
      -> ...
```

路由注册期主要保存 `RequestMappingInfo -> HandlerMethod`。HandlerMethod 可以先持有 Bean 名称；请求匹配后，`createWithResolvedBean()` 通过 BeanFactory 取得当前容器对象。这个对象已经走过完整 Bean 生命周期，因此可能是代理。

### Controller 被代理时的边界

`detectHandlerMethods` 需要从用户类型找到注解方法，请求执行又必须在实际 Bean 上调用一个可调用 Method。调试时区分：

| 对象 | 用途 |
| --- | --- |
| `handlerType` / user type | 扫描 `@RequestMapping`、解析桥接方法 |
| `HandlerMethod.method` | 保存路由对应的 Java Method |
| `HandlerMethod.bean` | Bean 名称或已解析实例 |
| resolved bean | 请求期真正被调用的容器对象，可能是 proxy |

如果用 JDK proxy 包装一个只在实现类声明 Controller 方法的 Bean，该方法不一定能通过代理接口调用。Spring 会检查可调用方法，但工程上更应避免让 Controller 代理类型与路由方法声明互相冲突。

## doDispatch 到 Controller 的主干

```text
DispatcherServlet.doDispatch
  -> checkMultipart
  -> getHandler
      -> HandlerMapping.getHandler
      -> AbstractHandlerMethodMapping.getHandlerInternal
      -> HandlerMethod.createWithResolvedBean
      -> HandlerExecutionChain
  -> getHandlerAdapter
      -> RequestMappingHandlerAdapter.supports
  -> mappedHandler.applyPreHandle
  -> HandlerAdapter.handle
      -> RequestMappingHandlerAdapter.handleInternal
      -> invokeHandlerMethod
          -> ServletInvocableHandlerMethod.invokeAndHandle
              -> invokeForRequest
                  -> getMethodArgumentValues
                  -> doInvoke
  -> mappedHandler.applyPostHandle
  -> processDispatchResult
  -> mappedHandler.triggerAfterCompletion
```

### 参数解析与事务的先后

`getMethodArgumentValues` 先询问 `HandlerMethodArgumentResolverComposite`。路径变量转换、请求体反序列化和参数校验都可能在 `doInvoke` 前失败。

因此：

- Controller 方法没有执行时，Controller 内部调用的 Service 事务自然还没开始。
- 若 Controller 本身带事务注解且它是可用代理，事务边界可能包住 Controller 方法，但仍不会包住进入该代理之前的 MVC 参数解析。
- 常规设计把事务放在 Service，使 HTTP 参数处理和数据库原子边界分离。

## Controller 到 Service：调用对象必须是 proxy

假设 Controller 字段注入 `OrderApplicationService`：

```text
doCreateBean(controller)
  -> populateBean
      -> resolveDependency(OrderApplicationService)
      -> beanFactory.getBean(orderService)
      -> 返回 singletonObjects 中的 orderServiceProxy
  -> controller.orderService = orderServiceProxy
```

请求期执行：

```text
Controller.placeOrder(request)
  -> this.orderService.placeOrder(command)
      -> Service proxy
          -> AOP invocation entry
```

Controller 内的 `this.orderService` 是另一个 Bean 的代理引用，属于跨 Bean 外部调用。它与 Service target 内部的 `this.reserve()` 不同；后者不会重新经过 proxy。

## AOP 链怎样找到 TransactionInterceptor

代理入口先取得本次方法链：

```text
proxy invocation
  -> targetSource.getTarget
  -> advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass)
      -> DefaultAdvisorChainFactory
          -> 遍历 Advisor
          -> Pointcut.classFilter.matches(targetClass)
          -> MethodMatcher.matches(method, targetClass)
          -> AdvisorAdapterRegistry.getInterceptors(advisor)
  -> new ReflectiveMethodInvocation(..., chain)
  -> invocation.proceed()
```

事务 Advisor 的 `TransactionAttributeSourcePointcut` 复用 `TransactionAttributeSource` 判断方法是否具有事务属性。匹配后，`BeanFactoryTransactionAttributeSourceAdvisor` 提供 `TransactionInterceptor`。

链中可能同时存在安全、缓存、重试、观测和事务 Interceptor。它们的顺序决定：

- 哪个逻辑位于事务内部；
- 异常被哪个 Interceptor 转换或吞掉；
- 重试是每次新开事务，还是在同一事务里重复目标调用。

不能仅凭 `@Order` 注解文字推断最终链，应在当前 method 的 `interceptorsAndDynamicMethodMatchers` 上确认。

## TransactionInterceptor 到物理资源

经典同步入口如下。`getTransaction` 既可能新建事务，也可能返回参与外层事务的 status；只有新事务 owner 才会执行后文示例中的物理 `doCommit/doRollback` 和资源解绑。

```text
TransactionInterceptor.invoke(invocation)
  -> invokeWithinTransaction(method, targetClass, invocation::proceed)
      -> txAttrSource.getTransactionAttribute
      -> determineTransactionManager
      -> createTransactionIfNecessary
          -> transactionManager.getTransaction(txAttr)
              -> AbstractPlatformTransactionManager.getTransaction
                  -> doGetTransaction
                  -> isExistingTransaction
                  -> existing: handleExistingTransaction
                      -> 参与、挂起后新建，或按传播行为拒绝
                  -> not existing: startTransaction
                      -> doBegin
                      -> prepareSynchronization
          -> prepareTransactionInfo
      -> invocation.proceedWithInvocation
      -> cleanupTransactionInfo
      -> commitTransactionAfterReturning
```

### JDBC 示例：连接怎样绑定

`DataSourceTransactionManager.doBegin` 的关键状态变化：

1. 从事务对象取得或新建 `ConnectionHolder`。
2. 必要时从 DataSource 取得 Connection。
3. 应用隔离级别和 read-only 提示。
4. 若原本自动提交，执行 `setAutoCommit(false)`。
5. 把 holder 标为 transaction active。
6. 用 `TransactionSynchronizationManager.bindResource(dataSource, holder)` 绑定当前线程。

DAO 随后通过 `DataSourceUtils.getConnection(dataSource)` 进入 `doGetConnection`，以规范化后的资源 key 查询线程资源。`TransactionSynchronizationManager` 会通过 `TransactionSynchronizationUtils.unwrapResourceIfNecessary` 解开常见资源代理，因此这个 key 通常是同一个 DataSource 实例，也可能是两个包装对象共同指向的底层资源。Map 查找依赖规范化 key 的 `equals/hashCode`，不是 Java 对象身份比较。

因此“使用相同 JDBC URL”仍然远远不够：两个独立连接池即使 URL 相同，也通常是两个资源 key。事务管理器和 DAO 必须对齐到同一个可规范化资源工厂；反过来，不能仅凭包装 DataSource 不是同一个外层对象就断定无法加入事务。

## 两个 ThreadLocal 不要混淆

| 状态 | 所属类 | 保存内容 | 作用 |
| --- | --- | --- | --- |
| `transactionInfoHolder` | `TransactionAspectSupport` | 当前拦截器调用的 `TransactionInfo` 链 | 让嵌套事务 advice 恢复上一层调用上下文 |
| `resources` | `TransactionSynchronizationManager` | DataSource 到 ConnectionHolder 等资源映射 | 让同线程 DAO 复用当前事务资源 |

`TransactionInfo` 存在不一定表示有新物理事务；例如 SUPPORTS 无事务运行或 REQUIRED 参与外层时，仍需要正确维护调用栈。反过来，直接手工操作 `resources` 也不会创建完整的事务 advice 语义。

## 正常返回的完成顺序

先看本专题主线：Service 代理新建并拥有最外层事务。目标返回后不是立即把结果交给 MVC：

```text
Service target returns
  -> inner interceptors unwind
  -> TransactionAspectSupport.cleanupTransactionInfo
     恢复上一层 TransactionInfo
  -> commitTransactionAfterReturning
      -> PlatformTransactionManager.commit
          -> processCommit
          -> doCommit
          -> afterCommit / afterCompletion callbacks
          -> cleanupAfterCompletion
              -> unbind resource
              -> doCleanupAfterCompletion
  -> outer interceptors unwind
  -> Controller receives service result
  -> Controller returns
  -> HandlerMethodReturnValueHandler 处理返回值
      -> ResponseBody 场景写 body；视图场景留到后续渲染
```

这里有一个容易忽略的细节：`cleanupTransactionInfo` 恢复的是 advice 调用栈；物理资源解绑由事务管理器的 `cleanupAfterCompletion` 负责。这是两套清理。

如果当前 REQUIRED 只是参与外层事务，局部顺序不同：

```text
Service target returns
  -> cleanupTransactionInfo
  -> commitTransactionAfterReturning
      -> transactionManager.commit(participatingStatus)
      -> 不调用物理 doCommit
      -> 不解绑外层 owner 的资源
  -> 返回外层调用者
  -> 外层 owner 在自己的完成点决定 commit / rollback 和资源清理
```

同理，OSIV 或其他 Web 基础设施可以在事务外继续绑定自己的资源。判断清理是否正确，应检查“本次 owner 拥有的资源 key 是否按时解绑”，不能要求 `TransactionSynchronizationManager.getResourceMap()` 在所有应用中整体为空。

## 请求状态快照

| 时刻 | MVC | AOP | `TransactionInfo` | `resources` | Connection |
| --- | --- | --- | --- | --- | --- |
| `doDispatch` 入口 | 尚未选 handler | 无业务调用 | 空 | 主线为空；OSIV/外围资源可能已绑定 | 未取得或由外围持有 |
| 参数解析完成 | HandlerMethod 已解析 | 无 Service 调用 | 空 | 与请求入口一致 | 未取得或由外围持有 |
| Service proxy 入口 | Controller 正在执行 | 链已取得 | 准备创建 | 本事务 key 尚未绑定；外围资源可能已绑定 | 未取得或由外围持有 |
| `doBegin` 完成 | 等待 Controller | 位于 TX Interceptor | 当前层已绑定 | `{ds -> holder}` | active，autoCommit=false |
| DAO 执行 | 等待 Controller | target 内部 | 当前层可见 | 同一 holder | 执行 SQL |
| owner commit 完成 | 等待 Controller 返回 | 链回卷 | 已恢复上一层 | 本事务资源 key 已解绑 | 已提交/按 owner 规则释放 |
| 参与者退出 | 仍在外层调用中 | 局部链回卷 | 已恢复上一层 | 外层资源继续绑定 | 尚未物理提交 |
| 返回值处理 | 写 body 或视图 | Service 调用结束 | 通常为空 | 本事务 key 已解绑；其他资源可能仍在 | 不再属于已完成的 Service owner 事务 |

## 传播发生在“再次经过代理”时

```text
Controller
  -> orderServiceProxy.placeOrder()          REQUIRED
      -> OrderService target
          -> auditServiceProxy.record()      REQUIRES_NEW
              -> AuditService target
```

第二个事务边界成立，是因为 target 调用了另一个 Bean 的 proxy。若写成：

```text
OrderService target.placeOrder()
  -> this.recordAudit()                      REQUIRES_NEW 注解
```

`this` 调用没有回到 proxy，`TransactionInterceptor` 不会再次执行，也就没有传播行为判断、挂起或新事务。

## Spring 5.3 的方法可见性边界

本专题固定在 Spring Framework 5.3.39。代理式注解事务默认使用 `publicMethodsOnly=true` 的 `AnnotationTransactionAttributeSource`，因此只有 public 方法会成为候选事务方法。把 `@Transactional` 写在 protected、包可见或 private 方法上，即使从外部调用，也不能按 Spring 5.3 默认代理模式推断事务一定生效。

还要继续区分代理类型：

- JDK 动态代理只能拦截代理接口暴露的 public 方法。
- CGLIB 能代理类上的 public 方法，但 final 方法不能被子类覆盖，private 方法也不存在可覆盖入口。
- 不论 JDK 还是 CGLIB，target 内部的 `this.method()` 都没有重新进入外部代理。
- Spring 6 对基于类的代理放宽了部分 protected/包可见方法支持；迁移时必须按目标版本和代理类型重新核对，不能把 Spring 6 的结论倒套到 5.3。

## 常见误判

| 误判 | 更准确的判断 |
| --- | --- |
| 请求命中 Controller 就说明事务已开启 | 参数解析和 Controller 前半段可以发生在事务之外 |
| `@Transactional` 方法执行就一定经过代理 | 5.3 默认先要求 public，再确认调用对象、代理类型和 Advisor 链 |
| HandlerMethod 永远直接持有 Controller 实例 | 可能先持 Bean 名称，请求期解析容器 Bean |
| Controller 返回后事务还包着 JSON 序列化 | Service 自己拥有的事务通常已完成；外围事务必须按实际 owner 另查 |
| DAO 用相同数据库就自动加入事务 | 需要通过管理器对应的资源 key 取得绑定资源 |
| `TransactionInfo` 就是 ConnectionHolder | 一个描述 advice 调用栈，一个保存具体资源 |
| self-invocation 的 REQUIRES_NEW 会挂起外层 | 没重新进入代理就不会评估传播 |

## 断点路线：从一个 URL 跟到一次 commit

使用 `/orders/42` 或实验中的确定路径过滤请求：

| 顺序 | 断点 | 条件/变量 |
| --- | --- | --- |
| 1 | `DispatcherServlet.doDispatch` | URI、`mappedHandler` |
| 2 | `AbstractHandlerMethodMapping.getHandlerInternal` | lookupPath、返回 HandlerMethod |
| 3 | `HandlerMethod.createWithResolvedBean` | 原 bean 字段、解析后对象类型 |
| 4 | `InvocableHandlerMethod.getMethodArgumentValues` | 参数解析器、`args` |
| 5 | `InvocableHandlerMethod.doInvoke` | Controller bean 是否为 proxy |
| 6 | JDK/CGLIB AOP 入口 | method、targetClass、chain |
| 7 | `TransactionInterceptor.invoke` | method、targetClass、txAttr |
| 8 | `AbstractPlatformTransactionManager.getTransaction` | propagation、existing transaction |
| 9 | `DataSourceTransactionManager.doBegin` | ConnectionHolder、autoCommit |
| 10 | `TransactionSynchronizationManager.bindResource` | key 是否为 DAO 使用的 DataSource |
| 11 | `processCommit` / `doCommit` | rollback-only、newTransaction |
| 12 | 返回值处理器 | 此时 resources 是否已清理 |

断点停在连接或同步锁持有区时，只挂起当前线程。调试器暂停会改变超时和连接池行为，不应用来得出性能结论。

## 可运行案例映射

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest=SpringMvcBehaviorTest#shouldResolveArgumentsAndWriteResponse test

mvn -pl labs/spring-framework-lab \
  -Dtest=TransactionRollbackRuleTest#shouldCommitAfterNormalReturn test

mvn -pl labs/spring-framework-lab \
  -Dtest=TransactionProxyBoundaryTest#shouldUseJdkProxyForInterfaceService test
```

| 案例 | 固定的公开行为 | 推荐衔接断点 |
| --- | --- | --- |
| MVC 正常请求 | 参数、Controller、返回值处理顺序和响应 | `doDispatch -> invokeForRequest` |
| 事务正常返回 | begin、business、commit、cleanup 的事件顺序 | `invokeWithinTransaction -> processCommit` |
| 接口 Service 代理 | 容器返回 JDK proxy 且调用进入事务 advice | AOP 入口和 `TransactionInterceptor.invoke` |

Spring Framework Lab 中的 MVC 与 Transaction 专题使用两个最小上下文，用于分别消除 Web 和事务噪声。把两条断点路线迁移到真实应用时，应以同一个请求 trace、线程名和目标 beanName 对齐，而不是把两次独立测试的对象身份混在一起。

## 过关问题

1. HandlerMethod 在什么时候从 Bean 名称解析为容器对象，为什么这一步能拿到 proxy？
2. 参数校验失败时，Service 事务为什么不会开始？Controller 自身事务又有什么边界？
3. AOP 链里事务 Interceptor 的排序怎样影响重试和缓存语义？
4. `TransactionInfo` 已恢复旧值时，数据库 Connection 是否一定已经解绑？
5. 为什么响应 JSON 序列化失败通常不能回滚已经提交的 Service 事务？
6. 两个 DataSource 对象连接到相同 URL，为什么仍可能不共享事务资源？
7. 如何用一次断点证明 `REQUIRES_NEW` 是否真的经过了第二个 proxy？

下一章沿异常路径继续，重点区分事务回滚规则、rollback-only、MVC 异常解析和线程边界。
