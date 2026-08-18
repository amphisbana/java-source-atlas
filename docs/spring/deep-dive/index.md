# Spring 深挖：从启动到请求完成的一条真实主线

本专题把两个职责独立的 Spring Lab 中已经分别讲解的五个专题放回同一个运行过程：**Spring Boot 2.7.18 启动 Spring Framework 5.3.39 容器，容器注册 AOP、事务和 MVC 基础设施，业务请求再穿过代理、事务资源与 MVC 返回值处理。**

这里不重复解释 `refresh()` 的十二个阶段、七种事务传播行为或 MVC 每一种参数解析器。重点是已有专题之间最容易断开的五个衔接点：

1. Boot 的自动配置为什么发生在 Framework 的 `refresh()` 内部，而不是 `refresh()` 之前。
2. `BeanFactoryPostProcessor` 注册定义与 `BeanPostProcessor` 包装实例为什么必须分成两个阶段。
3. Setter 循环依赖取到早期代理后，为什么最终不能再创建第二个代理。
4. MVC 调到的 Service 为什么是代理，事务资源又怎样被同一请求线程上的 DAO 取得。
5. 业务异常为什么先完成事务回滚，随后才由 `HandlerExceptionResolver` 决定 HTTP 响应。

## 版本和观察边界

| 层次 | 本专题基线 | 需要按目标版本重新核对的内容 |
| --- | --- | --- |
| Spring Boot | 2.7.18 | `SpringApplication` 私有辅助方法、自动配置候选资源、默认循环依赖策略 |
| Spring Framework | 5.3.39 | 私有缓存字段、后处理器内部集合、CGLIB 回调、事务模板局部变量 |
| Java | 8 | Spring 6 / Boot 3 需要 Java 17，并迁移到 `jakarta.*` |
| Web 实验 | `MockMvc + WebApplicationContext` | 真实 Servlet 容器线程、过滤器和网络层不在 MockMvc 行为证据内 |
| 事务主线 | `DataSourceTransactionManager`，Service 新建并拥有事务 | 外围事务、测试托管事务、OSIV（Open Session/EntityManager in View）和其他线程绑定资源会改变物理完成与资源解绑时点 |

文中的类、方法和状态用于阅读源码与设置断点。业务代码应依赖公开扩展接口，不应反射读取三级缓存、`earlyProxyReferences`、事务 ThreadLocal 或 MVC 内部策略列表。

## 交互图：三条时间线怎样穿过五个专题

<SpringDeepDiveMap />

先切换“启动装配”“正常请求”“异常回滚”，再逐步观察当前控制权在哪个模块。特别注意三条边界：

- 自动配置被选中只表示配置类名进入后续解析与定义注册候选；它仍可能被配置类条件跳过，更不表示目标 Bean 已实例化。
- 本专题的正常/异常主线假设 Service 代理新建并拥有最外层事务。此时 MVC 收到业务异常前，本次事务拥有的资源通常已经完成并解绑。
- 如果 Service 只是参与外围事务，它退出代理时可能只提交参与者状态或标记 rollback-only；物理提交、回滚和资源解绑要等外围 owner 完成，线程资源 Map 也可能保留 OSIV 等其他条目。

## 一条完整调用链

下面把一次典型启动和一次请求压缩成一条可跟踪的主干：

```text
SpringApplication.run
  -> prepareEnvironment
  -> createApplicationContext
  -> prepareContext
  -> refreshContext
      -> SpringApplication.refresh
          -> AbstractApplicationContext.refresh
              -> invokeBeanFactoryPostProcessors
                  -> PostProcessorRegistrationDelegate
                  -> ConfigurationClassPostProcessor
                      -> ConfigurationClassParser
                      -> DeferredImportSelectorHandler
                      -> AutoConfigurationImportSelector.AutoConfigurationGroup
                      -> ConfigurationClassBeanDefinitionReader
              -> registerBeanPostProcessors
                  -> 注册 AnnotationAwareAspectJAutoProxyCreator
              -> finishBeanFactoryInitialization
                  -> DefaultListableBeanFactory.preInstantiateSingletons
                  -> AbstractBeanFactory.doGetBean
                  -> AbstractAutowireCapableBeanFactory.doCreateBean
                      -> addSingletonFactory(getEarlyBeanReference)
                      -> populateBean
                      -> initializeBean
                          -> AbstractAutoProxyCreator.postProcessAfterInitialization
                          -> wrapIfNecessary
                          -> ProxyFactory.getProxy
              -> finishRefresh
  -> listeners.started
  -> callRunners
  -> listeners.running

HTTP request
  -> DispatcherServlet.doDispatch
  -> getHandler
      -> RequestMappingHandlerMapping.getHandler
  -> getHandlerAdapter
      -> RequestMappingHandlerAdapter.handle
      -> invokeHandlerMethod
      -> ServletInvocableHandlerMethod.invokeAndHandle
      -> InvocableHandlerMethod.doInvoke
  -> Controller target
      -> Service proxy
          -> JdkDynamicAopProxy.invoke / CglibAopProxy.intercept
          -> ReflectiveMethodInvocation.proceed
          -> TransactionInterceptor.invoke
          -> TransactionAspectSupport.invokeWithinTransaction
              -> createTransactionIfNecessary
              -> AbstractPlatformTransactionManager.getTransaction
              -> DataSourceTransactionManager.doBegin
              -> TransactionSynchronizationManager.bindResource
              -> invocation.proceed
                  -> Service target -> DAO
              -> commitTransactionAfterReturning
                  -> doCommit
                  -> cleanupAfterCompletion
  -> HandlerMethodReturnValueHandler
  -> DispatcherServlet.processDispatchResult
```

这条链不是说所有项目都使用 JDBC、注解 Controller 或 `@Aspect`。它给出的是本项目实验可以复现的典型组合。JPA、JTA、WebFlux 或 AspectJ weaving 会替换局部实现，但“定义阶段、实例阶段、代理调用阶段、资源完成阶段”仍必须分开判断。

## 三个时间轴不能混在一起

| 时间轴 | 起点 | 终点 | 核心产物 | 最常见误判 |
| --- | --- | --- | --- | --- |
| 定义时间轴 | 配置源进入容器 | BeanDefinition 集合稳定 | 类、工厂方法、条件装配结果 | 把条件命中写成“Bean 已创建” |
| 实例时间轴 | `getBean` | 单例发布或创建失败 | target、早期引用、最终 proxy | 把 target、早期 proxy、最终 proxy 当三个独立 Bean |
| 调用时间轴 | 客户端调用 proxy | 返回值或异常离开代理 | interceptor chain、事务状态 | 只看注解，不确认调用是否经过代理 |

MVC 请求时间轴建立在前三者已经完成之后。`HandlerMapping` 持有的 HandlerMethod 能定位到容器 Bean，但每次请求仍需要解析参数、取得当前 Bean、执行拦截器并处理返回值。

## 五个关键交接点

### 1. Boot 把配置交给 IOC

`SpringApplication.prepareContext` 完成 Initializer 和配置源加载后，通过 `refreshContext` 进入 `AbstractApplicationContext.refresh`。`AutoConfigurationImportSelector` 是 `DeferredImportSelector`，由 `ConfigurationClassPostProcessor` 在 `invokeBeanFactoryPostProcessors` 阶段驱动。

交接的数据不是 Bean，而是配置类和 BeanDefinition：

```text
Environment + primarySources
  -> ConfigurationClassParser
  -> DeferredImportSelector.Group
  -> selected auto-configuration class names
  -> ConfigurationClassBeanDefinitionReader
  -> BeanDefinitionRegistry
```

### 2. IOC 把实例交给 AOP

自动代理创建器先作为基础设施 BeanDefinition 注册，再在 `registerBeanPostProcessors` 阶段成为工厂处理器。普通业务 Bean 随后进入 `doCreateBean`，初始化后的原始对象被传给 `postProcessAfterInitialization`。

```text
BeanDefinitionRegistryPostProcessor 先注册代理基础设施
  -> BeanPostProcessor 注册完成
  -> 创建业务 target
  -> AbstractAutoProxyCreator 判断 Advisor
  -> 返回 proxy 作为容器暴露对象
```

如果顺序反过来，业务 Bean 已经创建完成后才注册自动代理创建器，它就不会被该处理器补做一遍完整生命周期。

### 3. IOC 把策略 Bean 交给 MVC

MVC 配置或 Boot 自动配置向同一个 BeanFactory 注册 `HandlerMapping`、`HandlerAdapter`、`HandlerExceptionResolver` 等定义。它们在单例预实例化和 Servlet 初始化期间建立策略表。

`DispatcherServlet` 不在每次请求中扫描所有 BeanDefinition：

```text
WebApplicationContext refresh
  -> 创建 RequestMappingHandlerMapping
  -> afterPropertiesSet
  -> detectHandlerMethods
  -> 保存 RequestMappingInfo -> HandlerMethod 映射

DispatcherServlet.onRefresh
  -> initStrategies
  -> 从 WebApplicationContext 取得并排序策略 Bean
```

### 4. AOP 把调用交给事务

声明式事务不是 MVC 的内置阶段。`@EnableTransactionManagement` 或 Boot 事务自动配置注册事务 Advisor 和 `TransactionInterceptor`；自动代理创建器把 Advisor 加到匹配的业务 Bean 代理中。

```text
Controller -> Service proxy
  -> interceptor chain
  -> TransactionInterceptor
  -> PlatformTransactionManager
  -> target Service
```

所以“Controller 已经进入”不能证明事务已开启。还需要证明调用对象是代理、目标方法能解析出事务属性、链中存在事务拦截器并选择了正确管理器。

### 5. 事务把异常交还 MVC

目标方法抛异常后，Java 调用栈先回到 `TransactionInterceptor`。在本专题的主线中，Service 代理拥有新事务，因此事务模板按回滚规则完成物理 `commit` 或 `rollback`，清理 `TransactionInfo`，并由事务管理器解绑本次事务拥有的资源，然后重新抛出异常。异常继续穿过 Controller，最后才到 `DispatcherServlet.processHandlerException`。

```text
Service target throws
  -> completeTransactionAfterThrowing
  -> rollback / commit according to rule
  -> cleanupTransactionInfo
  -> exception leaves proxy
  -> exception leaves Controller
  -> DispatcherServlet.processHandlerException
  -> HandlerExceptionResolver
```

因此 `@ControllerAdvice` 返回 200 或 4xx 只是在决定 HTTP 结果，不能反向改变已经完成的事务决定。

参与外层事务时要把“事务决定”和“物理完成”继续拆开：内层 Service advice 可以把共享事务标记为 rollback-only，但不能解绑外层 owner 的资源；若某个事务过滤器把整个 `DispatcherServlet` 包在事务中，MVC Resolver 甚至可能在外层事务仍活动时执行。无论哪种情况，异常解析都不能撤销已经发生的物理回滚，也不能清除已经设置的 rollback-only，只是最终完成权所在位置不同。

## 跨模块状态快照

| 时刻 | BeanDefinition | 单例缓存 | AOP | 事务线程状态 | MVC |
| --- | --- | --- | --- | --- | --- |
| `prepareContext` 后 | 主配置源已加载 | 业务单例未创建 | 基础设施可能尚未注册 | 空 | Servlet 策略未初始化 |
| 配置类解析后 | 自动配置和用户定义已展开 | 仍不应批量创建业务 Bean | Advisor/APC 定义可见 | 空 | MVC 策略定义可见 |
| BPP 注册后 | 基本稳定 | 基础设施 Bean 已有一部分 | APC 已进入处理器链 | 空 | 尚未处理请求 |
| `doCreateBean` 提前暴露后 | 当前定义不变 | 三级缓存保存 ObjectFactory | 可能按需创建早期代理 | 空 | 无请求 |
| `refresh` 成功后 | 已冻结或稳定 | 非懒单例已发布 | 业务 proxy 可注入 | 空 | 路由和策略可用 |
| 事务方法执行中 | 不变 | 不变 | 链游标正在推进 | 绑定 ConnectionHolder | 请求等待 Controller 返回 |
| MVC 异常解析时 | 不变 | 不变 | Service 事务调用已经退出 | 主线中本事务资源 key 已解绑；外围/OSIV 资源可能仍在 | Resolver 决定响应 |

## 深挖阅读顺序

1. [Boot 怎样进入 refresh](./startup-refresh.md)：跟踪定义集合怎样形成，以及基础设施为什么先于业务单例创建。
2. [Bean、早期引用与最终代理](./bean-proxy-cycle.md)：把完整生命周期、三级缓存和自动代理一致性放在一个对象时间轴中。
3. [MVC 请求怎样进入事务代理](./request-transaction.md)：从 HandlerMethod 一直跟到资源绑定、提交和响应写入。
4. [异常、传播与清理边界](./failure-boundaries.md)：区分 rollback-only、MVC 异常处理、线程切换和响应失败。
5. [跨专题断点实验](./debug-lab.md)：使用 Spring Framework Lab 与 Spring Boot Lab 的五个专题建立可复现证据，并完成过关问题。

## 读完应能先回答这六问

1. 为什么 `AutoConfigurationImportSelector` 命中后还不能在三级缓存里找到对应 Bean？
2. 为什么 `ConfigurationClassPostProcessor` 必须在普通业务单例创建前执行？
3. Setter 循环中 B 取得 A 的早期代理后，A 初始化完成为什么不能再包一层新代理？
4. `DispatcherServlet` 取得的 HandlerMethod 与请求中实际调用的 Bean 之间是什么关系？
5. `TransactionInfo` ThreadLocal 与 `TransactionSynchronizationManager.resources` 分别保存什么？
6. 为什么 `HttpMessageConverter` 写响应失败通常不能让已经返回的 Service 事务回滚？

如果不能用方法调用链和状态变化回答，应回到对应章节做一次受控断点，而不是继续增加注解记忆。
