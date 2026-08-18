# Boot 怎样进入 refresh：从配置源到基础设施定义

本章只回答启动阶段的跨模块问题：`SpringApplication.run` 在什么时候把控制权交给 Spring Framework，自动配置怎样进入 BeanDefinition 集合，以及 AOP、事务、MVC 基础设施为什么能赶在业务单例之前注册。

## 源码入口

| 层次 | 类与方法 | 观察目标 |
| --- | --- | --- |
| Boot 总控 | `SpringApplication.run(String...)` | Environment、context、listeners 的建立顺序 |
| 上下文准备 | `prepareContext(...)` | Initializer、主配置源与启动事件 |
| refresh 桥接 | `refreshContext(...)` / `refresh(...)` | Boot 何时进入 Framework 模板 |
| 容器模板 | `AbstractApplicationContext.refresh()` | 定义处理、BPP 注册和单例创建的阶段边界 |
| 配置类处理 | `ConfigurationClassPostProcessor.processConfigBeanDefinitions(...)` | 用户配置和自动配置如何被解析 |
| 延迟导入 | `ConfigurationClassParser.DeferredImportSelectorHandler` | 为什么自动配置晚于普通 `@Import` 汇总 |
| 自动配置选择 | `AutoConfigurationImportSelector.AutoConfigurationGroup.selectImports()` | 候选、排除、条件和排序结果 |
| 定义落库 | `ConfigurationClassBeanDefinitionReader.loadBeanDefinitions(...)` | 配置类、`@Bean` 方法怎样注册为定义 |
| 单例创建 | `DefaultListableBeanFactory.preInstantiateSingletons()` | 哪些非懒加载定义真正变成实例 |

## 第一段：run 还没有进入 Bean 生命周期

Boot 2.7.18 的同步主线可以按四段读：

```text
SpringApplication.run(args)
  1. createBootstrapContext
  2. getRunListeners -> listeners.starting
  3. prepareEnvironment -> listeners.environmentPrepared
  4. createApplicationContext
  5. prepareContext
  6. refreshContext
  7. afterRefresh
  8. listeners.started
  9. callRunners
 10. listeners.running
```

前五步准备的是启动输入和容器外壳：

- `Environment` 聚合命令行、系统属性、配置文件等 PropertySource。
- `ApplicationContextInitializer` 可以在 refresh 前调整 context。
- 主配置类作为 source 被加载进 BeanDefinitionRegistry。
- `ApplicationListener` 接收阶段事件，但事件到达不等于容器已可取全部 Bean。

此时直接把问题描述为“Bean 初始化失败”往往太早。若异常发生在 `prepareEnvironment`，连 BeanFactory 的完整后处理链都没有开始。

## 第二段：Boot 到 Framework 的精确交接

核心桥接是：

```text
SpringApplication.refreshContext(context)
  -> refresh(context)
      -> applicationContext.refresh()
          -> AbstractApplicationContext.refresh()
```

`SpringApplication` 负责选择和准备 ApplicationContext，`AbstractApplicationContext` 负责把它刷新成可用容器。进入 `refresh()` 后，主体已经是 Spring Framework 的模板方法。

### refresh 内部的三个关键闸门

| 闸门 | 方法 | 闸门前 | 闸门后 |
| --- | --- | --- | --- |
| 定义闸门 | `invokeBeanFactoryPostProcessors` | 初始定义仍可被配置类处理器扩展 | 用户配置、自动配置和注册器结果基本展开 |
| 实例处理闸门 | `registerBeanPostProcessors` | 业务 Bean 还不能得到完整后处理 | Aware、注解注入、自动代理等处理器已注册 |
| 单例闸门 | `finishBeanFactoryInitialization` | 大量普通单例仍只是定义 | 非懒加载单例完成创建并发布 |

这三个闸门解释了为什么“注册事务 Advisor”“创建事务代理”“开启事务”是三件发生在不同时刻的事。

## 第三段：自动配置实际发生在哪里

`@EnableAutoConfiguration` 通过 `@Import(AutoConfigurationImportSelector.class)` 进入配置类解析。但 `AutoConfigurationImportSelector` 是 `DeferredImportSelector`，不会在第一次遇到注解时立即返回最终配置集合。

```text
PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors
  -> ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry
  -> processConfigBeanDefinitions
  -> ConfigurationClassParser.parse
      -> processConfigurationClass
      -> processImports
      -> DeferredImportSelectorHandler.handle
  -> DeferredImportSelectorHandler.process
      -> DeferredImportSelectorGroupingHandler.processGroupImports
      -> AutoConfigurationGroup.process
          -> getAutoConfigurationEntry
              -> getCandidateConfigurations
              -> getExclusions
              -> filter
      -> AutoConfigurationGroup.selectImports
  -> ConfigurationClassBeanDefinitionReader.loadBeanDefinitions
```

延迟处理有两个重要结果：

1. 用户配置和普通导入先进入候选集合，自动配置条件能看到更多用户定义。
2. 同一 Group 能统一去重、排除和排序，而不是每个选择器各自立刻注册。

### 条件判断的输入和输出

| 输入 | 例子 | 它能回答什么 | 它不能回答什么 |
| --- | --- | --- | --- |
| Environment | `atlas.feature.enabled=true` | 属性条件是否命中 | Bean 是否已实例化成功 |
| ClassLoader | 某类是否在 classpath | `@ConditionalOnClass` 是否命中 | 该类对应组件是否可用 |
| BeanDefinitionRegistry | 用户是否已定义某类型/名称 | 缺失 Bean 条件是否退让 | 运行时对象状态是否健康 |
| 自动配置元数据 | 候选注解摘要 | 批量预过滤 | 所有自定义 Condition 的最终结果 |
| 排除集合 | 注解和属性排除 | 哪些候选必须移除 | 剩余配置是否一定创建所有 Bean |

`selectImports` 输出的是配置类名。配置类被读取后，其中每个 `@Bean` 仍要单独满足方法级条件，并在后续 `getBean` 时完成实例化。

## 第四段：五类基础设施怎样进入同一个 BeanFactory

以下是典型来源，不表示每个应用都会启用全部内容：

| 能力 | 常见配置入口 | 注册结果 | 真正运行时机 |
| --- | --- | --- | --- |
| IOC 注解处理 | context 自带或配置类处理 | `AutowiredAnnotationBeanPostProcessor` 等 | Bean 属性填充和初始化阶段 |
| AOP | `@EnableAspectJAutoProxy` / AOP 自动配置 | 自动代理创建器 | 业务 Bean 初始化前后 |
| 声明式事务 | `@EnableTransactionManagement` / 事务自动配置 | 属性源、Interceptor、Advisor、APC | 代理创建及每次代理调用 |
| MVC | `@EnableWebMvc` / `WebMvcAutoConfiguration` | Mapping、Adapter、Resolver | refresh 建立策略，请求期执行 |
| Servlet 注册 | `DispatcherServletAutoConfiguration` | DispatcherServlet 和 RegistrationBean | WebServer 启动、Servlet 初始化和请求期 |

这些定义最终都进入当前 ApplicationContext 的 BeanFactory。Boot 没有另建一套“自动配置容器”；Framework IOC 仍然负责它们的依赖注入、生命周期和销毁。

## 定义阶段与实例阶段状态表

| 观察点 | `beanDefinitionNames` | `beanPostProcessors` | `singletonObjects` | 能否已有业务代理 |
| --- | --- | --- | --- | --- |
| `prepareContext` 结束 | 包含主配置等初始定义 | 只有早期基础能力 | 少量基础设施 | 通常不能 |
| 配置类解析开始 | 数量继续增长 | 尚未完整注册 | 不应批量创建普通 Bean | 不应依赖 |
| 自动配置选择结束 | 命中配置及 `@Bean` 定义陆续注册 | 尚未完成排序注册 | 仍可能为空 | 不能据此判断 |
| `registerBeanPostProcessors` 结束 | 基本稳定 | APC、注入处理器等已就位 | BPP 本身已实例化 | 业务 Bean 尚未必创建 |
| `preInstantiateSingletons` 中 | 通常已冻结/稳定 | 对新 Bean 生效 | 逐个增加 | 是，初始化后或早期引用路径 |
| `finishRefresh` 后 | 稳定 | 稳定 | 非懒单例可用 | 是 |

调试时若在配置类解析阶段看到某个业务单例已经创建，应检查谁调用了 `getBean`、按类型查询是否带 `allowEagerInit`，以及某个后处理器是否错误地持有普通 Bean 依赖。

## Boot 的循环依赖策略怎样作用于 Framework

Spring Framework 5.3 的 BeanFactory 具备 Setter 单例循环引用机制，但 Boot 2.6+ 默认策略会禁止应用中的循环引用。阅读时应分开两层：

```text
Boot 配置 spring.main.allow-circular-references
  -> SpringApplication 在上下文准备阶段配置 BeanFactory
  -> AbstractAutowireCapableBeanFactory.allowCircularReferences
  -> doCreateBean 是否执行 earlySingletonExposure
```

因此：

- “三级缓存机制存在”是 Framework 实现事实。
- “当前 Boot 应用是否允许使用”是 Boot 配置策略。
- 打开开关只能让部分 Setter/字段单例环进入早期引用路径，不能解决构造器环、prototype 环或错误的代理身份。

## 常见误判

| 误判 | 为什么错 | 应检查的证据 |
| --- | --- | --- |
| `run()` 一开始就在创建业务 Bean | Environment 和 context 外壳先建立 | 异常发生在 `prepareEnvironment`、`prepareContext` 还是 `refresh` |
| 自动配置类被选中等于 Bean 已存在 | 选择器只输出配置类名 | Registry 中的定义、方法级条件、后续 `getBean` |
| `@ConditionalOnMissingBean` 在运行中持续监控 | 条件主要在配置解析/注册时评估 | ConditionEvaluationReport 与定义注册时刻 |
| Boot 绕过了 `ApplicationContext.refresh` | Boot 最终调用 Framework refresh | `refreshContext -> refresh -> context.refresh` 断点栈 |
| Runner 是 Bean 初始化回调 | Runner 在 refresh 成功后统一调用 | `callRunners` 与 `finishRefresh` 的先后 |
| Framework 6 删除了三级缓存 | 常把 Boot 默认策略误写成 Framework 实现变化 | 目标版本的 BeanFactory 实现和 Boot 属性 |

## 一条低噪声断点路线

这条路线需要分两次运行，不能只运行候选发现测试：

1. 在 IDE 中以 Debug 方式运行 `SpringBootAutoConfigurationDebugLab.main`，观察 `SpringApplication.prepareContext` 和 `refreshContext`。命令行验证可运行：

   ```bash
   mvn -pl labs/spring-boot-lab compile exec:java
   ```

2. 运行 `AtlasFeatureAutoConfigurationTest#shouldDiscoverConfigurationFromImportsResource`，观察配置类解析、条件筛选和定义实例化。`ApplicationContextRunner` 会自己创建并 refresh 测试上下文，但**不会调用 `SpringApplication.run`**，所以它不会命中前两个 Boot 桥接断点。

| 顺序 | 断点 | 适用入口 | 条件/变量 | 要证明的事实 |
| --- | --- | --- | --- | --- |
| 1 | `SpringApplication.prepareContext` | Debug main | `context.getClass()`、`sources` | 主配置何时进入 context |
| 2 | `SpringApplication.refreshContext` | Debug main | `context.isActive()` | 进入 refresh 前容器尚未 active |
| 3 | `AbstractApplicationContext.refresh` | 两者 | `beanFactory`、定义数量、调用栈 | main 证明 Boot 已交权；测试只证明测试 context 进入 Framework 模板 |
| 4 | `ConfigurationClassPostProcessor.processConfigBeanDefinitions` | 两者 | 候选配置集合 | 配置类解析属于 BFPP 阶段 |
| 5 | `AutoConfigurationImportSelector.getAutoConfigurationEntry` | 两者 | `configurations`、`exclusions` | 候选怎样缩小 |
| 6 | `AutoConfigurationGroup.selectImports` | 两者 | 最终 entries | 延迟组的统一输出 |
| 7 | `registerBeanPostProcessors` | 两者 | 当前 BPP 类型和顺序 | APC 何时进入处理器链 |
| 8 | `preInstantiateSingletons` | 两者 | 目标 beanName | 定义何时真正触发创建 |

不要在完整业务系统里给 `getBean` 设置无条件方法断点。先把条件限制到实验 Bean 或目标自动配置类，否则大量基础设施创建会掩盖顺序。

## 可运行案例映射

```bash
mvn -pl labs/spring-boot-lab \
  -Dtest=AtlasFeatureAutoConfigurationTest test
```

| 测试 | 对应源码事实 |
| --- | --- |
| `shouldDiscoverConfigurationFromImportsResource` | 候选资源能让配置类进入自动配置选择链 |
| `shouldStayDisabledWhenPropertyIsMissing` | 候选存在不代表属性条件命中 |
| `shouldBindPropertiesAndCreateDefaultService` | 条件命中后，定义最终能被实例化并完成属性绑定 |
| `shouldBackOffWhenUserProvidesService` | 用户定义先可见时，缺失 Bean 条件退让 |

这些测试用行为证明条件结果，不反射读取 `AutoConfigurationGroup` 私有缓存。断点负责观察“为什么”，断言负责固定“对外发生了什么”。

主程序与测试的证据不能互相冒充：只有主程序调用栈能证明 `SpringApplication -> ApplicationContext.refresh` 的桥接；`ApplicationContextRunner` 更适合低噪声地验证自动配置候选、条件和 Bean 结果。

## 过关问题

1. `Environment` 已准备好但 `ApplicationContext` 尚未 refresh，此时哪些条件可以判断，哪些 Bean 状态不能判断？
2. 为什么 `AutoConfigurationImportSelector` 使用延迟导入组有利于用户配置优先？
3. 在 `registerBeanPostProcessors` 前错误创建业务 Bean，会给 `@Autowired`、AOP 和事务带来什么差异？
4. 如何用调用栈证明 Boot 没有绕过 Framework 的 `refresh()`？
5. Runner 抛异常与 Bean init-method 抛异常在调用链和清理时机上有什么不同？
6. Boot 禁止循环引用时，具体影响 `doCreateBean` 的哪个判断，而不是“删除”哪个缓存？

下一章把单个业务 Bean 的 target、三级缓存早期引用和最终代理放到同一条对象时间轴中。
