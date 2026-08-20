# Spring IOC 源码地图

本专题以 **Spring Framework 5.3.39** 为可执行基线。该版本仍支持 Java 8，适合与本项目的 JDK 8 实验放在同一套工具链中调试。文中出现的字段、方法顺序和三级缓存名称均以 5.3.39 为准；Spring 6.x 的重要差异会单独标注。

读完本专题后，进入 [Bean、早期引用与最终代理](/spring/deep-dive/bean-proxy-cycle)，把 IOC 生命周期、三级缓存和 AOP 代理身份放到同一个对象时间轴中。

<TopicStudyPanel
  topic-id="spring-framework-5-3-ioc"
  design-insight="Spring 没有把对象创建写成一个巨型工厂，而是用定义、模板方法、后处理器和缓存把变化点层层打开。"
  focus-question="为什么一个 Bean 要经过定义解析、实例化、填充、初始化和缓存迁移这么多阶段？"
/>

## 源码入口

- 容器模板：`AbstractApplicationContext.refresh()` / `close()`
- 核心工厂：`DefaultListableBeanFactory`
- Bean 查询：`AbstractBeanFactory.doGetBean(...)`
- Bean 创建：`AbstractAutowireCapableBeanFactory.doCreateBean(...)`
- 单例注册：`DefaultSingletonBeanRegistry`
- 扩展编排：`PostProcessorRegistrationDelegate`

## 先建立两个坐标系

阅读 IOC 源码最容易混淆的是把“上下文启动”和“一个 Bean 的创建”当成同一件事：

```text
AnnotationConfigApplicationContext.refresh()
  └─ 组装 BeanFactory、执行容器扩展、初始化基础设施
       └─ finishBeanFactoryInitialization(...)
            └─ preInstantiateSingletons()
                 └─ getBean(beanName)
                      └─ 创建某一个非懒加载单例
```

- `refresh()` 是容器级流程，负责让整套工厂从“拥有一些定义”变成“可以稳定提供 Bean”。
- `getBean()` 是对象级流程，负责从缓存取对象，或根据一个 `BeanDefinition` 创建对象。
- 懒加载 Bean 可以在 `refresh()` 完成后第一次使用时才进入 `getBean()` 创建链。

## BeanFactory 与 ApplicationContext 的分层

| 层次 | 代表接口或实现 | 主要职责 |
| --- | --- | --- |
| 最小容器契约 | `BeanFactory` | 按名称/类型取 Bean，查询作用域和别名 |
| 批量查询 | `ListableBeanFactory` | 枚举定义，按类型或注解查找多个 Bean |
| 父子工厂 | `HierarchicalBeanFactory` | 本地找不到时委托父工厂；父工厂不会反向看到子工厂 |
| 自动装配能力 | `AutowireCapableBeanFactory` | 创建、注入、初始化外部对象 |
| 可配置工厂 | `ConfigurableListableBeanFactory` | 注册处理器、解析依赖、冻结配置、预实例化单例 |
| 默认核心实现 | `DefaultListableBeanFactory` | 同时承担 BeanDefinitionRegistry 与完整 BeanFactory 实现 |
| 应用上下文 | `ApplicationContext` | 在 BeanFactory 上增加资源、环境、国际化、事件和生命周期 |
| 刷新模板 | `AbstractApplicationContext` | 编排 `refresh()` 和 `close()`，具体子类提供内部工厂 |

`ApplicationContext` 不是替代 `BeanFactory` 的另一套容器。它持有并委托一个 `ConfigurableListableBeanFactory`，再在其外层组织应用级能力。

## BeanDefinition：对象创建配方

`BeanDefinition` 保存类名、作用域、构造参数、属性值、工厂方法、懒加载、初始化和销毁方法等元数据。注册定义不等于创建实例。

常见注册入口最终汇入 `BeanDefinitionRegistry.registerBeanDefinition`：

```text
注解配置
  AnnotationConfigApplicationContext.register(...)
    → AnnotatedBeanDefinitionReader.registerBean(...)

类路径扫描
  ClassPathBeanDefinitionScanner.scan(...)
    → doScan(...)

配置类展开
  ConfigurationClassPostProcessor
    → ConfigurationClassParser.parse(...)
    → ConfigurationClassBeanDefinitionReader.loadBeanDefinitions(...)

XML
  XmlBeanDefinitionReader.loadBeanDefinitions(...)

最终
  DefaultListableBeanFactory.registerBeanDefinition(name, definition)
```

`ConfigurationClassPostProcessor` 自己是 `BeanDefinitionRegistryPostProcessor`。因此 `@Configuration`、`@ComponentScan`、`@Import` 和 `@Bean` 的大部分解析发生在 `refresh()` 的后处理器阶段，而不是 `register(...)` 调用瞬间全部完成。

## 推荐阅读顺序

1. [refresh 启动全流程](./refresh.md)：先看容器级骨架和动画。
2. [配置类解析](./configuration-class.md)：观察扫描、Import、条件和 `@Bean` 怎样让 Registry 逐轮增长，并区分 full/lite。
3. [依赖候选解析](./dependency-resolution.md)：从 DependencyDescriptor 进入泛型、Qualifier、Primary、多值和延迟解析。
4. [Bean 创建全流程](./bean-creation.md)：再跟踪选中候选怎样通过 `getBean → doCreateBean` 产生实例。
5. [循环依赖与三级缓存](./circular-dependency.md)：理解为什么需要提前引用工厂。
6. [IOC 扩展点](./extension-points.md)：区分 BFPP、BPP、Aware 和 FactoryBean。
7. [断点实验](./debug-lab.md)：用最小案例把顺序变成可观察事件。

## 第一次阅读时只跟五个对象

| 对象 | 建议观察值 | 回答的问题 |
| --- | --- | --- |
| `context` | `active`、`closed`、`startupDate` | 上下文是否已经进入可用状态 |
| `beanFactory` | 类型、处理器数量、定义数量 | 当前内部工厂装配到哪一步 |
| `BeanDefinition` / `mbd` | beanClass、scope、lazyInit、factoryMethod | 这个 Bean 准备怎样创建 |
| `beanName` | 原始名称、规范名称、是否带 `&` | 当前取得的是产品还是 FactoryBean 本身 |
| 单例缓存 | 一、二、三级缓存是否包含该名称 | 当前对象是完整实例、早期引用还是引用工厂 |

## 建议断点

| 类与方法 | 首次观察目标 |
| --- | --- |
| `AbstractApplicationContext.refresh` | 容器模板方法的阶段边界与失败清理 |
| `PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors` | 配置类为何在实例化前展开为定义 |
| `DefaultListableBeanFactory.preInstantiateSingletons` | 哪些定义会在 refresh 期间触发 getBean |
| `AbstractBeanFactory.doGetBean` | 缓存命中、父工厂、作用域和 FactoryBean 适配 |
| `AbstractAutowireCapableBeanFactory.doCreateBean` | 实例化、提前暴露、填充、初始化和销毁登记 |
| `DefaultSingletonBeanRegistry.getSingleton` | 三级缓存之间的查询和迁移 |

## 公开契约与实现边界

稳定公开契约包括 `BeanFactory`/`ApplicationContext` API、`BeanPostProcessor`、`BeanFactoryPostProcessor`、`FactoryBean`、各类 `Aware` 与生命周期接口。应用代码可以依赖这些契约。

以下内容是 5.3.39 的实现细节，适合学习和调试，不应由业务代码反射依赖：

- `DefaultSingletonBeanRegistry` 三个缓存字段的具体名字与容器类型；
- `refresh()` 内部方法的精确排列；
- 处理器内部缓存和 `RootBeanDefinition` 的合并字段；
- `AbstractAutowireCapableBeanFactory` 的 protected 模板方法。

## Spring 6.x 边界

- Spring Framework 6 要求 Java 17，并迁移到 Jakarta EE 9+ 的 `jakarta.*` 命名空间；例如生命周期注解应使用 `jakarta.annotation.PostConstruct`，不能照搬 5.3 时代的 `javax.annotation` 依赖。
- `refresh()`、`getBean()`、定义后处理、Bean 后处理和单例注册等核心概念仍然连续，但字段与内部实现可变化，应切换到目标版本源码重新核对。
- Spring 6 加强 AOT 和原生镜像支持；运行时反射、动态代理和提前类型推断可能受到 AOT 元数据约束。
- “默认禁止循环依赖”常来自 Spring Boot 2.6+ 的应用配置，不是 Spring Framework 5.3/6 核心容器默认值发生了同义变化。不要混淆 Framework 与 Boot 的策略层。
