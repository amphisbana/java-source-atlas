# Spring 跨专题断点实验：把五条链对齐到同一张时间表

本手册复用两个职责独立的 Spring Lab：`spring-framework-lab` 承载 IOC、AOP、事务和 MVC，`spring-boot-lab` 承载 Boot 启动与自动配置。MVC 专题中保留一个只装配 MVC、事务 AOP 与 H2 的跨专题测试：先分别证明 IOC、AOP、事务、Boot 和 MVC 的公开行为，再用同一个 MockMvc 请求证明 `Controller -> Service proxy -> TransactionInterceptor -> DataSourceTransactionManager`。这样既能降低噪声，也不会把一次偶然断点时序写成框架契约。

## 实验目标

完成后应拿到四组证据：

1. 自动配置候选在 `invokeBeanFactoryPostProcessors` 阶段变成定义，普通业务单例在更晚阶段创建。
2. Setter 循环取到的早期引用与容器最终发布引用保持一致，构造器循环在实例产生前失败。
3. MVC HandlerMethod 最终调用容器 Bean，Controller 跨 Bean 调用能进入 Service proxy、事务 Interceptor 和真实 JDBC 事务管理器。
4. 正常请求提交数据；异常请求回滚数据，而且本次 DataSource 资源先解绑，MVC 异常解析后发生。

## 环境基线

```bash
java -version
mvn -version

mvn test
```

项目基线是 Spring Framework 5.3.39、Spring Boot 2.7.18 和 Java 8 兼容源码。IDE 附加的 sources 必须与 Maven 实际解析版本一致。若 IDE 关联 Spring 6 或 Boot 3 源码，即使方法名相似，局部变量和候选资源也可能对不上。

## 两个 Spring Lab 的五个专题各自负责什么

| 专题 | 模块 | 测试选择器 | 主要行为证据 | 最适合观察的内部过程 |
| --- | --- | --- | --- | --- |
| IOC | `labs/spring-framework-lab` | `-Dtest='BeanFactoryPostProcessorOrderTest,CircularDependencyBehaviorTest,SpringIocLifecycleTest'` | 生命周期、扩展顺序、FactoryBean、循环边界 | refresh、BPP、三级缓存 |
| AOP | `labs/spring-framework-lab` | `-Dtest=SpringAopBehaviorTest` | JDK/CGLIB、链顺序、异常回卷、自调用 | wrapIfNecessary、proceed |
| Transaction | `labs/spring-framework-lab` | `-Dtest='TransactionMetadataTest,TransactionPropagationTest,TransactionProxyBoundaryTest,TransactionRollbackRuleTest'` | rollback rule、传播、挂起、保存点、线程边界 | invokeWithinTransaction、status |
| Boot | `labs/spring-boot-lab` | `-Dtest=AtlasFeatureAutoConfigurationTest` | 属性条件、绑定、用户 Bean 退让、候选发现 | DeferredImportSelector、Condition |
| MVC | `labs/spring-framework-lab` | `-Dtest='SpringMvcBehaviorTest,RequestTransactionIntegrationTest'` | 参数、返回值、异常、真实 JDBC 提交/回滚与资源解绑 | doDispatch、AOP、TransactionInterceptor、Resolver |

Framework 四行的选择器追加到 `mvn -pl labs/spring-framework-lab test`，Boot 选择器追加到 `mvn -pl labs/spring-boot-lab test`。不加选择器时，Framework 模块运行 38 个测试，Boot 模块运行 5 个测试；仓库根目录的 `mvn test` 会聚合运行全部 43 个测试。

## 实验零：先记录公开行为，不下源码断点

第一次运行只记录：

- 测试名称和结果；
- Lab 输出的事件顺序；
- 实际 proxy 类型；
- 异常类型和 HTTP 状态；
- 测试线程是否仍绑定本次实验的 DataSource 资源。

这一步建立“应发生什么”。第二次运行再下断点解释“为什么发生”。如果一开始就在几十个框架方法上暂停，调试器本身会改变并发、超时和类加载时序。

## 路线一：自动配置怎样进入 refresh

### 路线一 A：从 `SpringApplication.main` 进入完整启动链

运行可执行入口：

```bash
mvn -pl labs/spring-boot-lab compile exec:java
```

这条路线由 `SpringApplication.run` 创建 context，因此能够命中 `prepareContext` 和 `refreshContext`。默认参数会开启实验自动配置，不需要另行传入配置。

| 步 | 断点 | 记录变量 | 完成本步的证据 |
| --- | --- | --- | --- |
| 1 | `SpringApplication.run` | primarySources、args、listeners | 入口正在准备 Environment 与 ApplicationContext |
| 2 | `SpringApplication.createApplicationContext` | webApplicationType、contextClass | 本实验创建非 Web context |
| 3 | `SpringApplication.prepareContext` | context 类型、sources | 主配置已交给 context，但 refresh 尚未执行 |
| 4 | `SpringApplication.refreshContext` | context.active | 即将进入 Framework refresh 模板 |
| 5 | `AbstractApplicationContext.refresh` | beanFactory 定义数量 | Boot 已进入 Framework 模板 |
| 6 | `PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors` | registryProcessors、regularPostProcessors | 配置类处理属于定义阶段 |
| 7 | `ConfigurationClassPostProcessor.processConfigBeanDefinitions` | candidateNames | 主配置正在展开 |
| 8 | `AutoConfigurationImportSelector.getAutoConfigurationEntry` | configurations、exclusions | 排除和过滤改变候选集合 |
| 9 | `AutoConfigurationImportSelector.AutoConfigurationGroup.selectImports` | 最终 entries | 延迟组统一选择结果 |
| 10 | `ConfigurationClassBeanDefinitionReader.loadBeanDefinitions` | configurationModel | 选中配置被写入 Registry |
| 11 | `DefaultListableBeanFactory.preInstantiateSingletons` | 目标 beanName | 定义在更晚阶段才触发实例化 |

### 路线一 B：用 `ApplicationContextRunner` 隔离自动配置

运行资源发现测试：

```bash
mvn -f labs/spring-boot-lab/pom.xml \
  -Dtest=AtlasFeatureAutoConfigurationTest#shouldDiscoverConfigurationFromImportsResource test
```

`ApplicationContextRunner` 直接创建并刷新测试 ApplicationContext，**不会经过** `SpringApplication.run`、`prepareContext` 或 `refreshContext`。这条路线适合快速验证候选发现、条件匹配和 Bean 退让；不要因为前三个 SpringApplication 断点没有命中而判断自动配置未执行。

| 步 | 断点 | 记录变量 | 完成本步的证据 |
| --- | --- | --- | --- |
| 1 | `AbstractApplicationContextRunner.run` | contextFactory、initializers | Runner 正在创建隔离上下文 |
| 2 | `AbstractApplicationContext.refresh` | context 类型、beanFactory | Runner 直接进入 Framework refresh |
| 3 | `PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors` | registryProcessors | 配置类处理开始 |
| 4 | `ConfigurationClassPostProcessor.processConfigBeanDefinitions` | candidateNames | `ImportsDiscoveryApplication` 正在展开 |
| 5 | `AutoConfigurationImportSelector.getAutoConfigurationEntry` | configurations、exclusions | 从导入资源取得并过滤候选 |
| 6 | `AutoConfigurationImportSelector.AutoConfigurationGroup.selectImports` | entries | 得到最终导入结果 |
| 7 | `ConfigurationClassBeanDefinitionReader.loadBeanDefinitions` | configurationModel | 自动配置定义进入 Registry |
| 8 | `DefaultListableBeanFactory.preInstantiateSingletons` | `atlasGreetingService` | 条件匹配后才创建业务 Bean |

### 两条路线的共同状态快照

```text
entry: SpringApplication main / ApplicationContextRunner
step:
call stack:
context.active:
beanDefinition count:
target configuration definition exists:
target bean in singletonObjects:
AutoConfigurationEntry candidate/exclusion count:
```

通过标准：能解释为什么只有 main 路线经过 `SpringApplication`，同时在两条路线中指出“候选发现”“条件/排除”“定义注册”“实例创建”四个不同位置，并证明它们不在同一个方法中完成。

## 路线二：Bean 生命周期、三级缓存与代理

### 运行目标

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest=CircularDependencyBehaviorTest#shouldResolveSingletonSetterCycle test

mvn -pl labs/spring-framework-lab \
  -Dtest=SpringAopBehaviorTest#shouldCreateProxyThroughApplicationContext test
```

第一个测试稳定触发 Setter 循环，第二个测试稳定证明容器暴露 proxy。它们分别隔离 IOC 与 AOP；把断点迁移到一个带 Advisor 的循环最小案例时，再验证早期和最终引用身份。

### 断点顺序

所有断点都限制到实验 beanName：

| 步 | 断点 | 记录变量 |
| --- | --- | --- |
| 1 | `AbstractBeanFactory.doGetBean` | beanName、sharedInstance、args |
| 2 | `DefaultSingletonBeanRegistry.getSingleton(name,ObjectFactory)` | creation 标记、singletonFactory |
| 3 | `AbstractAutowireCapableBeanFactory.doCreateBean` | bean、exposedObject、mbd |
| 4 | `addSingletonFactory` | singletonObjects、earlySingletonObjects、singletonFactories |
| 5 | `populateBean` | 当前注入点、dependentBeanMap |
| 6 | `getSingleton(name,true)` | 具体命中哪一级缓存 |
| 7 | `AbstractAutoProxyCreator.getEarlyBeanReference` | cacheKey、原始 bean、返回 proxy |
| 8 | `initializeBean` | before/after BPP 返回对象身份 |
| 9 | `postProcessAfterInitialization` | earlyProxyReferences.remove 结果 |
| 10 | `doCreateBean` 早期引用协调 | exposedObject、earlySingletonReference |
| 11 | `addSingleton` | 最终对象类型和对象 ID |

### 身份快照

| 引用 | 类型 | 调试器对象 ID | 来自哪个阶段 |
| --- | --- | --- | --- |
| raw target |  |  | `createBeanInstance` |
| early reference |  |  | 三级工厂 |
| injected into peer |  |  | `populateBean` |
| after initialization |  |  | BPP after initialization |
| context result |  |  | `singletonObjects` |

通过标准：能证明同一 beanName 最终只暴露一个稳定身份；能解释没有循环时为什么三级工厂可以从未执行；能在构造器循环失败点指出“实例尚未产生”。

## 路线三：一次正常请求怎样提交事务

这个测试在一个 `AnnotationConfigWebApplicationContext` 中装配 Controller、接口型 Service、`@EnableTransactionManagement`、`DataSourceTransactionManager` 与独立 H2 数据库。MockMvc 不启动网络端口，但请求仍经过真实 DispatcherServlet、Spring AOP 事务代理和 JDBC Connection 提交。

### 运行目标

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest=RequestTransactionIntegrationTest#shouldCommitAndUnbindDataSourceResourceForSuccessfulRequest test
```

### 断点顺序

| 步 | 断点 | 条件/变量 | 目标 |
| --- | --- | --- | --- |
| 1 | `DispatcherServlet.doDispatch` | URI、mappedHandler | 请求进入 MVC |
| 2 | `AbstractHandlerMethodMapping.getHandlerInternal` | lookupPath、HandlerMethod | 路由已在启动期建立 |
| 3 | `HandlerMethod.createWithResolvedBean` | bean 名称、解析对象类型 | 取得 IOC 最终对象 |
| 4 | `InvocableHandlerMethod.getMethodArgumentValues` | resolvers、args | 参数先于 Controller 调用 |
| 5 | `RequestRecordController.create` | code、fail、当前线程 | Controller 调用前本次 DataSource 尚未绑定 |
| 6 | `JdkDynamicAopProxy.invoke` | proxy、method、targetSource | 接口型 Service 调用进入 JDK 代理 |
| 7 | `ReflectiveMethodInvocation.proceed` | currentInterceptorIndex | 找到 TransactionInterceptor 位置 |
| 8 | `TransactionInterceptor.invoke` | txAttr、tm | 事务属性和管理器 |
| 9 | `AbstractPlatformTransactionManager.getTransaction` | propagation、transaction | 新建还是参与 |
| 10 | `DataSourceTransactionManager.doBegin` | conHolder、newConnectionHolder | 取得 Connection 并关闭自动提交 |
| 11 | `TransactionSynchronizationManager.bindResource` | key 是否为本次 dataSource、value、线程名 | 本次 DataSource 绑定到请求线程 |
| 12 | `JdbcRequestRecordService.save` | resourceTrace、ConnectionHolder | target 内 `hasResource(dataSource)` 与事务活动状态都为 true |
| 13 | `AbstractPlatformTransactionManager.processCommit` / `DataSourceTransactionManager.doCommit` | rollbackOnly、newTransaction、Connection | 执行真实 JDBC commit |
| 14 | `DataSourceTransactionManager.doCleanupAfterCompletion` | dataSource、ConnectionHolder | 解绑本次 DataSource 并释放 Connection |
| 15 | `RequestRecordController.create` 的 `finally` | `hasResource(dataSource)` | 代理返回后本次资源已经解绑 |
| 16 | 返回值处理器 | returnValue、response | commit 与清理完成后才写响应 |

### 跨层快照

```text
request URI:
thread name:
HandlerMethod bean type:
Service injected reference type:
interceptor chain:
TransactionAttribute:
TransactionStatus flags:
TransactionSynchronizationManager.hasResource(dataSource):
TransactionSynchronizationManager.getResource(dataSource):
TransactionSynchronizationManager.isActualTransactionActive:
target return time:
commit time:
controller finally time:
response write time:
```

通过标准：测试返回 201，H2 中能查到 `committed`；资源快照严格为 `controller-before(false) -> service-target(true) -> controller-after(false)`，且三个阶段线程编号相同。这里只验证本次 DataSource key 已解绑，不要求 `TransactionSynchronizationManager.getResourceMap()` 中不存在其他框架资源。

## 路线四：异常回滚后才进入 MVC Resolver

### 运行目标

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest=RequestTransactionIntegrationTest#shouldRollbackAndUnbindBeforeMvcResolvesException test
```

### 断点顺序

| 步 | 断点 | 记录变量 | 要回答的问题 |
| --- | --- | --- | --- |
| 1 | `JdbcRequestRecordService.save` 抛异常行 | code、ConnectionHolder | insert 已执行，但尚未提交 |
| 2 | `invokeWithinTransaction` catch | throwable、txInfo | 异常是否已离开 target |
| 3 | `RuleBasedTransactionAttribute.rollbackOn` | winningRule | RuntimeException 为什么选择回滚 |
| 4 | `completeTransactionAfterThrowing` | status | 调用了 manager 的哪个完成入口 |
| 5 | `AbstractPlatformTransactionManager.processRollback` | newTransaction、savepoint、unexpected | 本例是否拥有物理回滚权 |
| 6 | `DataSourceTransactionManager.doRollback` | transaction object、Connection | 执行真实 JDBC rollback |
| 7 | `DataSourceTransactionManager.doCleanupAfterCompletion` | dataSource、ConnectionHolder | 解绑本次 DataSource 并释放 Connection |
| 8 | `RequestRecordController.create` 的 `finally` | `hasResource(dataSource)` | 代理重新抛异常前，Controller 已观察到资源解绑 |
| 9 | `DispatcherServlet.doDispatch` catch | dispatchException | MVC 在哪个栈帧接到异常 |
| 10 | `processHandlerException` | resolver 列表 | 谁把异常转成 HTTP 语义 |
| 11 | `RequestRecordExceptionHandler.handleIllegalState` | exception、`hasResource(dataSource)` | Resolver 执行时本次资源仍未绑定 |
| 12 | `triggerAfterCompletion` | ex | 拦截器最终看到已解析的 MVC 结果 |

### 时间线记录模板

| 序号 | 线程 | 方法 | 事务状态 | `hasResource(dataSource)` | Throwable / MVC 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 |  | target throws |  |  |  |
| 2 |  | rollback rule |  |  |  |
| 3 |  | doRollback / mark |  |  |  |
| 4 |  | cleanup |  |  |  |
| 5 |  | MVC catch |  |  |  |
| 6 |  | resolver |  |  |  |

通过标准：测试返回 409，H2 中查不到 `rolled-back`；资源快照严格为 `controller-before(false) -> service-target(true) -> controller-after(false) -> mvc-resolver(false)`，四个阶段线程编号相同。异常处理器只改变 HTTP 结果，不能撤销已经完成的 JDBC rollback。不要把“整个 resource map 必须为空”作为通过条件；只断言本次 DataSource key 已解绑。

## 专项对比：传播与线程边界

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest=TransactionPropagationTest#shouldSuspendAndResumeForRequiresNew test

mvn -pl labs/spring-framework-lab \
  -Dtest=TransactionPropagationTest#shouldRollbackNestedSavepointAndCommitOuterTransaction test

mvn -pl labs/spring-framework-lab \
  -Dtest=TransactionProxyBoundaryTest#shouldNotPropagateTransactionToNewThread test
```

| 场景 | 必须观察到的差异 |
| --- | --- |
| REQUIRES_NEW | 外层解绑、内层新资源、内层清理、外层恢复 |
| NESTED | 同一物理事务、保存点创建/回滚/释放，没有 tx-2 |
| 新线程 | 原线程绑定实验事务资源，新线程对该资源 key 的 `hasResource(dataSource)` 为 false；不要求整个 resource map 为空 |

## 调试降噪规则

- 方法断点优先改为源码行断点；大型框架方法断点会拖慢整个 JVM。
- 对 `getBean`、`doCreateBean`、`wrapIfNecessary` 使用 beanName 条件。
- 对 MVC 使用固定 URI、HTTP method 或 Controller 类型条件。
- 对事务使用目标方法名、传播枚举和管理器类型条件。
- 在连接锁、单例锁或并发队列内部断点时，只挂起当前线程。
- 不在 Evaluate Expression 中调用 `getBean`、业务方法、`commit`、`rollback` 或任何会改变容器/事务状态的方法。
- 调试器造成的超时、重试和线程调度变化不能当成正常运行结论。

## 证据分层

| 证据 | 适合证明 | 不适合证明 |
| --- | --- | --- |
| 自动测试断言 | 公开返回、异常、对象身份、事件顺序 | 私有字段长期稳定 |
| 源码断点 | 本次执行分支、局部变量、缓存迁移 | 所有并发运行都走同一时序 |
| 日志/事件记录器 | 跨层先后、线程名、事务事件 | 没记录到的内部判断 |
| proxy 类型检查 | 当前容器暴露 JDK/CGLIB proxy | 每个方法都一定命中某 Advisor |
| Condition 报告 | 自动配置为何命中/未命中 | Bean 实例运行时一定健康 |

## 最终过关清单

不看文档，按方法链回答：

1. Boot 在哪一个调用点进入 Framework 的 `refresh()`？
2. `AutoConfigurationImportSelector` 的输出是什么，谁把它变成 BeanDefinition？
3. BDRPP、BFPP、BPP 分别处理 Registry、Factory 还是 Bean 实例？
4. 三级工厂在什么条件下注册，为什么没有循环时可以不执行？
5. `getEarlyBeanReference` 与 `postProcessAfterInitialization` 如何避免双重代理？
6. 最终发布对象与依赖者持有的早期引用不一致时，容器为什么宁可失败？
7. HandlerMethod 怎样取得 IOC 中的最终 Controller 对象？
8. MVC 参数解析失败为什么通常发生在 Service 事务之前？
9. TransactionInterceptor 在 AOP 链中从哪里得到 TransactionAttribute？
10. `TransactionInfo` 和 `TransactionSynchronizationManager.resources` 的职责有何不同？
11. REQUIRED 参与者、REQUIRES_NEW、NESTED 分别由谁执行物理完成动作？
12. 为什么 `commit(status)` 可能最终抛 `UnexpectedRollbackException`？
13. Service RuntimeException 到达 MVC Resolver 前经过了哪些事务方法？
14. 为什么 JSON 写响应失败通常不能回滚已完成事务？
15. 为什么不能把 ConnectionHolder 复制到 `@Async` 线程？

通过标准不是背出类名，而是能画出对象和状态变化：定义何时增加、target 何时产生、proxy 何时替换、资源何时绑定、异常何时退出代理、MVC 何时接手。

## 继续阅读

- [Spring IOC 专题](../ioc/)
- [Spring AOP 专题](../aop/)
- [Spring Transaction 专题](../transaction/)
- [Spring MVC 专题](../mvc/)
- [Spring Boot 自动装配专题](../boot-autoconfigure/)
