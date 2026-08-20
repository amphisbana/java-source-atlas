# Spring Boot 自动装配源码地图

本专题以 **Spring Boot 2.7.18 + Spring Framework 5.3.x + Java 8** 为可执行基线。2.7.18 是 Boot 2.x 的最终维护版本：既保留 Java 8 运行边界，又已经引入 `@AutoConfiguration` 与 `AutoConfiguration.imports`，很适合看清从 2.x 迁往 3.x 时真正变化的部分。

读完候选发现与条件装配后，进入 [Boot 怎样进入 refresh](/spring/deep-dive/startup-refresh)，把 `SpringApplication.run`、配置类解析、基础设施后处理器和业务单例创建连成一条启动调用链。

<TopicStudyPanel topic-id="spring-boot-2-7-autoconfiguration" />

## 一句话理解自动装配

自动装配不是“把某个包里的类全部扫描成 Bean”，而是：

> `@EnableAutoConfiguration` 延迟导入一组框架维护的候选配置类，Spring 再依据类路径、属性、已有 Bean、应用类型等条件，决定哪些配置类和 `@Bean` 方法真正生效。

因此它仍然使用 Spring IOC 的 `BeanDefinition` 注册与 Bean 创建机制。Boot 增加的是候选配置发现、条件判断、配置数据准备、失败分析和约定组合，不是另一套容器。

## 先分清三条主线

```text
应用启动线
SpringApplication.run(args)
  → 准备 Environment
  → 创建 ApplicationContext
  → 装载主配置类
  → refresh(context)
  → 调用 Runner

自动配置导入线（发生在 refresh 内部）
ConfigurationClassPostProcessor
  → 解析 @SpringBootApplication
  → @EnableAutoConfiguration
  → AutoConfigurationImportSelector
  → 候选发现、排除、快速过滤、排序
  → 注册幸存配置类的 BeanDefinition

Bean 创建线
finishBeanFactoryInitialization
  → 创建 @ConfigurationProperties Bean
  → ConfigurationPropertiesBindingPostProcessor 绑定属性
  → 判断 @ConditionalOnMissingBean
  → 创建默认业务 Bean，或因用户 Bean 已存在而回退
```

三条线在 `ApplicationContext.refresh()` 处汇合：

- `SpringApplication.run` 是应用级编排，负责环境、上下文类型、监听器、主配置源和 Runner。
- `AutoConfigurationImportSelector` 是配置类解析阶段的延迟导入器，输出的是**配置类名**，不是已经创建好的 Bean。
- 最终实例仍由 Spring IOC 按普通 Bean 生命周期创建，属性绑定也借助 Bean 后处理器或值对象绑定路径完成。

## 一个最小例子先看结果

本专题的 Lab 提供如下自动配置：

```java
@AutoConfiguration
@ConditionalOnAtlasFeatureEnabled
@EnableConfigurationProperties(AtlasFeatureProperties.class)
public class AtlasFeatureAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AtlasGreetingService atlasGreetingService(AtlasFeatureProperties properties) {
        return new AtlasGreetingService(properties.getMessage(), properties.getRepeat());
    }
}
```

它被写入：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

三个输入会得到三个不同结果：

| 输入状态 | 自动配置类条件 | 属性 Bean | 默认服务 Bean |
| --- | --- | --- | --- |
| 未配置 `atlas.feature.enabled` | 不匹配 | 不注册 | 不注册 |
| `atlas.feature.enabled=true` | 匹配 | 绑定并注册 | 注册 |
| 开启功能且用户已声明同类型服务 | 匹配 | 绑定并注册 | `@ConditionalOnMissingBean` 不匹配，主动回退 |

“自动”只代表框架按约定替应用做决定，并不代表决定不可解释。每个条件结果都能进入 `ConditionEvaluationReport`。

## 核心源码入口

| 阅读问题 | Boot 2.7.18 源码入口 |
| --- | --- |
| 应用启动到底做了什么 | `SpringApplication.run(String...)` |
| Environment 何时准备完成 | `SpringApplication.prepareEnvironment(...)` |
| ApplicationContext 何时创建 | `SpringApplication.createApplicationContext()` |
| 主配置类何时装载为定义 | `SpringApplication.prepareContext(...)` → `load(...)` |
| 谁触发自动配置 | `@EnableAutoConfiguration` → `AutoConfigurationImportSelector` |
| 候选配置从哪里来 | `AutoConfigurationImportSelector.getCandidateConfigurations(...)` |
| 排除与快速过滤在哪里 | `getAutoConfigurationEntry(...)` / `ConfigurationClassFilter.filter(...)` |
| 条件为何匹配或不匹配 | `SpringBootCondition.matches(...)` |
| 属性怎样进入 JavaBean | `ConfigurationPropertiesBindingPostProcessor.postProcessBeforeInitialization(...)` |
| 用户 Bean 为何能覆盖默认值 | `OnBeanCondition` 对 `@ConditionalOnMissingBean` 的判断 |
| 条件结果存在哪里 | `ConditionEvaluationReport` |

## 推荐阅读顺序

1. [SpringApplication.run 启动生命周期](./springapplication-run.md)：先建立 Environment 与 ApplicationContext 的时间线。
2. [EnableAutoConfiguration 候选导入](./enable-autoconfiguration-import.md)：跟完整候选发现、过滤、排序动画。
3. [条件评估、属性绑定与用户回退](./condition-binding.md)：解释为什么某个 Bean 最终存在或缺失。
4. [断点实验](./debug-lab.md)：用五个自动测试和一个主程序把结论变成可观察行为。

## 第一次调试只盯六个对象

| 对象 | 建议观察值 | 能回答的问题 |
| --- | --- | --- |
| `SpringApplication` | `webApplicationType`、`primarySources`、listeners | Boot 准备启动哪一种应用 |
| `Environment` | propertySources、activeProfiles | 条件和绑定实际能读到哪些值 |
| `ApplicationContext` | 具体类型、active 状态 | 上下文何时创建、何时刷新完成 |
| `configurations` | 候选类名列表、数量变化 | 哪些自动配置在导入阶段被排除 |
| `ConditionOutcome` | match、message | 某个配置类或 Bean 方法为何生效 |
| `BeanFactory` | 用户 Bean 与默认 Bean 的定义 | `@ConditionalOnMissingBean` 看到了什么 |

## 五个常见误解

### 误解一：`@SpringBootApplication` 会扫描所有依赖包

它的 `@ComponentScan` 默认只扫描启动类所在包及子包。第三方自动配置通常由 `.imports` 或 2.7 兼容的 `spring.factories` 候选机制导入，不依赖组件扫描。

### 误解二：候选配置被读取就一定会创建其中的 Bean

候选类先经过排除、导入过滤和配置类条件；即使配置类保留，内部每个 `@Bean` 方法还可以有独立条件。

### 误解三：属性绑定发生在读取配置文件的同时

配置文件先成为 `Environment` 的属性源。绑定是在目标 `@ConfigurationProperties` 对象创建过程中，由 Binder 把规范化后的属性映射到目标对象。

### 误解四：用户 Bean 覆盖等于允许同名 BeanDefinition 覆盖

`@ConditionalOnMissingBean` 是自动配置在注册默认 Bean 前主动检查并回退；它不要求打开 `spring.main.allow-bean-definition-overriding`，也不是让后注册定义强行替换先注册定义。

### 误解五：加 `debug=true` 会改变装配结果

调试开关主要让条件报告日志可见。它帮助解释结果，不应成为业务条件本身。

## 版本边界

本专题所有精确方法顺序以 2.7.18 为准。Spring Boot 3.x 的核心概念仍然连续，但有三条必须单独记住：

- Boot 3 要求 Java 17，并基于 Spring Framework 6 与 Jakarta EE 9+。
- Boot 3 的自动配置候选只从 `AutoConfiguration.imports` 机制加载；2.7 仍会为了兼容同时读取 `spring.factories` 中以 `EnableAutoConfiguration` 为键的旧注册。
- AOT、原生镜像和运行时提示会影响反射、资源与代理的可达性；不能只凭 2.7 的 JVM 运行时现象推断原生镜像行为。

自动配置作者应依赖公开注解、`.imports` 约定与条件语义，不应反射读取 `AutoConfigurationImportSelector` 的内部字段或缓存。
