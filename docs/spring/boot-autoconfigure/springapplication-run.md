# SpringApplication.run：从参数到可用上下文

## 源码坐标

- 静态入口：`SpringApplication.run(Class<?>, String...)`
- 实例入口：`SpringApplication.run(String...)`
- 环境准备：`prepareEnvironment(...)`
- 上下文创建：`createApplicationContext()`
- 上下文装载：`prepareContext(...)`
- IOC 刷新：`refreshContext(...)`
- Runner：`callRunners(...)`
- 失败清理：`handleRunFailure(...)`

`SpringApplication.run(PrimarySource.class, args)` 只是便捷入口：创建 `SpringApplication`，再调用实例 `run(args)`。真正的应用级生命周期都在实例方法里。

## 完整成功路径

以下调用链针对 Spring Boot 2.7.18：

```text
SpringApplication.run(args)
  ├─ 记录 startTime
  ├─ createBootstrapContext()
  ├─ configureHeadlessProperty()
  ├─ getRunListeners(args)
  ├─ listeners.starting(bootstrapContext, mainApplicationClass)
  └─ try
       ├─ new DefaultApplicationArguments(args)
       ├─ prepareEnvironment(...)
       │    ├─ getOrCreateEnvironment()
       │    ├─ configureEnvironment(environment, args)
       │    │    ├─ configurePropertySources(...)
       │    │    └─ configureProfiles(...)
       │    ├─ ConfigurationPropertySources.attach(environment)
       │    ├─ listeners.environmentPrepared(...)
       │    ├─ bindToSpringApplication(environment)       // spring.main.*
       │    └─ attach ConfigurationPropertySources again
       ├─ configureIgnoreBeanInfo(environment)
       ├─ printBanner(environment)
       ├─ createApplicationContext()
       ├─ context.setApplicationStartup(...)
       ├─ prepareContext(...)
       │    ├─ context.setEnvironment(environment)
       │    ├─ postProcessApplicationContext(context)
       │    ├─ applyInitializers(context)
       │    ├─ listeners.contextPrepared(context)
       │    ├─ bootstrapContext.close(context)
       │    ├─ 注册 applicationArguments / printedBanner 单例
       │    ├─ 配置定义覆盖与懒初始化策略
       │    ├─ load(context, primarySources)
       │    └─ listeners.contextLoaded(context)
       ├─ refreshContext(context)
       │    └─ context.refresh()
       ├─ afterRefresh(context, applicationArguments)
       ├─ listeners.started(context, timeTakenToStartup)
       ├─ callRunners(context, applicationArguments)
       ├─ listeners.ready(context, timeTakenToReady)
       └─ return context
```

这里最重要的分界不是日志里的 “Started”，而是 `refreshContext(context)`：它把前面准备好的环境、上下文和主配置源交给 Spring IOC 刷新。

## 构造 SpringApplication 时已经决定什么

`new SpringApplication(primarySources)` 尚未创建 `ApplicationContext`，但会建立启动策略：

| 决策 | 依据 | 影响 |
| --- | --- | --- |
| primarySources | 传入的启动类或配置源 | `prepareContext` 最终装载哪些初始定义 |
| WebApplicationType | 特征类是否在类路径 | 创建 Servlet、Reactive 或普通上下文 |
| BootstrapRegistryInitializer | `spring.factories` | Environment 前可准备延迟对象 |
| ApplicationContextInitializer | `spring.factories` | refresh 前定制上下文 |
| ApplicationListener | `spring.factories` | 接收早期与后续启动事件 |
| mainApplicationClass | 调用栈推断 | 启动日志和部分诊断展示 |

类路径推断是一种默认策略，不是不可覆盖的结论。实验代码显式调用 `setWebApplicationType(WebApplicationType.NONE)`，保证不会因为引入其他依赖意外启动 Web 容器。

## Environment 为什么必须先于 ApplicationContext

自动配置条件和配置属性绑定都要读 Environment，所以 Boot 必须先完成配置数据准备，再刷新容器。

```text
命令行参数 / 系统属性 / 环境变量 / application 配置文件 / 默认属性
                         ↓
                  PropertySource 链
                         ↓
                 ConfigurableEnvironment
                    ↙             ↘
      条件：是否导入配置类        Binder：给属性对象赋值
```

`prepareEnvironment` 不只是 `new StandardEnvironment()`：

1. 根据 WebApplicationType 选择环境实现。
2. 加入默认属性与命令行属性。
3. 通过 `ConfigurationPropertySources.attach` 提供 Binder 能识别的配置属性视图。
4. 发布 `environmentPrepared`，Boot 的环境后处理器会在监听器链中处理 Config Data 等配置来源。
5. 把 `spring.main.*` 反向绑定到当前 `SpringApplication`，所以一些启动策略能由外部配置控制。

命令行 `--atlas.feature.enabled=true` 会成为命令行属性源，通常拥有很高优先级。判断属性最终取值时，应直接检查 `environment.getProperty(...)` 和属性源顺序，不能只盯某一个 YAML 文件。

## Environment 与 ApplicationContext 的职责边界

| 对象 | 此时拥有什么 | 此时还没有什么 |
| --- | --- | --- |
| Environment 准备完成 | 属性源、profiles、类型转换、配置数据结果 | 业务 Bean、完整 BeanFactory 生命周期 |
| ApplicationContext 刚创建 | 与应用类型匹配的上下文对象、空或基础 BeanFactory | 主配置定义尚未全部装载，未 refresh |
| prepareContext 完成 | Environment、初始化器结果、主配置源定义 | 自动配置尚未完成解析，非懒单例未批量创建 |
| refresh 完成 | 完整定义、处理器、自动配置结果、非懒单例 | Runner 尚未执行 |
| ready 事件后 | Runner 已执行，应用对 Boot 生命周期而言已就绪 | 不代表外部依赖一定健康，需业务健康检查确认 |

`ApplicationContext` 持有同一个准备好的 Environment。后续条件评估与属性绑定看到的值来自这里，而不是重新读取一遍配置文件。

## prepareContext：主配置源只是先注册

`prepareContext` 先设置 Environment、执行 `ApplicationContextInitializer`，再通过 `BeanDefinitionLoader` 装载 primary sources。

对于 Java 启动类，`load` 的结果是把启动配置注册为 BeanDefinition。此时不会立刻把所有自动配置和业务 Bean 都实例化：

```text
load(primarySource)
  → 启动类 BeanDefinition 进入 registry
  → contextLoaded 事件
  → refresh()
      → ConfigurationClassPostProcessor 解析启动类
      → 发现 @EnableAutoConfiguration
      → 延迟导入自动配置候选
      → 注册更多 BeanDefinition
      → 创建非懒单例
```

这与 IOC 专题中的“注册定义不等于创建对象”完全一致。

## refresh 内自动配置在哪里发生

`refreshContext` 最终调用 Spring Framework 的 `ApplicationContext.refresh()`。自动配置的导入主要发生在：

```text
AbstractApplicationContext.refresh()
  → invokeBeanFactoryPostProcessors(beanFactory)
      → ConfigurationClassPostProcessor
          → ConfigurationClassParser
              → 解析 @SpringBootApplication
              → 处理 DeferredImportSelector
                  → AutoConfigurationImportSelector
```

因此在 `SpringApplication.createApplicationContext()` 后立即查看 BeanDefinition 数量，只能看到初始集合。要观察自动配置注册结果，应对比 `ConfigurationClassPostProcessor` 执行前后，或等 `refresh()` 完成。

## started、Runner、ready 的精确顺序

Boot 2.7.18 在 refresh 完成后：

1. 调用 `afterRefresh` 扩展钩子。
2. 发布 `started` 生命周期回调。
3. 按顺序调用所有 `ApplicationRunner` 与 `CommandLineRunner`。
4. 发布 `ready` 生命周期回调。

所以：

- `ContextRefreshedEvent` 早于 Boot 的 `started`。
- Runner 抛出异常时，应用不会到达 `ready`。
- 耗时初始化放进 Runner 会增加 “ready” 时间；若服务必须在接流量前完成该动作，这是合理语义，否则应重新评估启动阻塞。

`ApplicationRunner` 得到解析后的 `ApplicationArguments`；`CommandLineRunner` 得到原始字符串数组。二者会通过 `AnnotationAwareOrderComparator` 的有序流执行，但不要让多个 Runner 依赖偶然的 Bean 注册顺序。

## 失败路径不是简单抛异常

`run` 主体捕获任意 `Throwable` 后进入 `handleRunFailure`：

```text
启动阶段异常
  → 计算 ExitCode（如果异常或映射器提供）
  → listeners.failed(context, exception)
  → SpringBootExceptionReporter 尝试输出更友好的失败分析
  → context.close()（如果已经创建）
  → 注销失败上下文的 shutdown hook 记录
  → 重新抛出原始运行时异常语义
```

如果异常发生在 Environment 阶段，`context` 仍可能是 null；如果发生在 refresh 中，Spring IOC 自身还会执行对应刷新失败清理。定位失败时先确认阶段，不要假设任何错误都拥有完整 BeanFactory 或条件报告。

## 推荐断点与观察变量

| 断点 | 变量 | 要确认的事实 |
| --- | --- | --- |
| `SpringApplication.run` 入口 | `args`、`primarySources`、`webApplicationType` | 启动输入与应用类型 |
| `prepareEnvironment` 返回前 | `propertySources`、activeProfiles | 条件能读取的最终环境视图 |
| `createApplicationContext` 返回后 | `context.getClass()` | 上下文类型是否符合预期 |
| `prepareContext` 的 `load` 前后 | `sources`、definitionCount | 主配置源只先注册为定义 |
| `refreshContext` | context active 状态 | Boot 编排进入 IOC 的边界 |
| `callRunners` | runner 类型和 order | refresh 后的应用回调顺序 |
| `handleRunFailure` | `context`、exception、reporters | 失败发生在哪个阶段、能否关闭上下文 |

## 实验映射

`SpringBootAutoConfigurationDebugLab` 显式创建 `SpringApplication`，关闭 Banner 和启动信息并固定为非 Web 类型。无参数运行时补充三个命令行属性；在 `prepareEnvironment` 返回处可看到：

```text
atlas.feature.enabled = true
atlas.feature.message = 欢迎阅读
atlas.feature.repeat = 2
```

继续到 `prepareContext` 时，主配置类已经成为定义，但 `AtlasGreetingService` 尚未创建；越过 `refreshContext` 后，自动配置、属性 Bean 和默认服务才全部可取。

下一步进入 [EnableAutoConfiguration 候选导入](./enable-autoconfiguration-import.md)，沿 `refresh()` 内部继续跟踪自动配置类名如何被选出。
