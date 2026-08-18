# 配置类解析：Registry 怎样从一个入口持续增长

`AnnotationConfigApplicationContext.register(AppConfig.class)` 并不会立刻执行全部扫描、导入和 `@Bean` 方法。它只先注册一个带注解元数据的 BeanDefinition；真正把配置入口展开成完整定义图的是 refresh 中的 `ConfigurationClassPostProcessor`。

本页以 Spring Framework `v5.3.39` 为基线，追踪下面六类输入：

- `@Configuration` 与 `proxyBeanMethods`；
- `@ComponentScan`；
- 直接 `@Import`；
- `ImportSelector` 与 `DeferredImportSelector`；
- `@Conditional`；
- `@Bean`、`@ImportResource` 与 `ImportBeanDefinitionRegistrar`。

## 固定版本源码入口

| 类 | 固定版本源码 | 职责 |
| --- | --- | --- |
| `ConfigurationClassPostProcessor` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassPostProcessor.java) | 从 Registry 发现候选，驱动 parser/reader，并在工厂阶段增强 full 配置 |
| `ConfigurationClassParser` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassParser.java) | 递归处理扫描、Import、条件、BeanMethod、接口与父类 |
| `ConfigurationClassBeanDefinitionReader` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassBeanDefinitionReader.java) | 把 ConfigurationClass 模型转回 BeanDefinitionRegistry |
| `ConfigurationClassUtils` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassUtils.java) | 判断配置候选以及 full/lite 属性 |
| `ConfigurationClassEnhancer` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassEnhancer.java) | 为 full 配置生成 CGLIB 子类并拦截 `@Bean` 自调用 |
| `ConditionEvaluator` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/annotation/ConditionEvaluator.java) | 在解析和注册阶段按条件跳过配置或 BeanMethod |

## 从 register 到 parser 的真实时机

```text
new AnnotationConfigApplicationContext()
  → 构造 AnnotatedBeanDefinitionReader
      → AnnotationConfigUtils.registerAnnotationConfigProcessors(registry)
          → 注册 ConfigurationClassPostProcessor BeanDefinition
  → context.register(AppConfig.class)
      → reader.registerBean(AppConfig.class)
          → registry.registerBeanDefinition(appConfig, annotatedDefinition)
  → context.refresh()
      → invokeBeanFactoryPostProcessors(beanFactory)
          → PostProcessorRegistrationDelegate
              → ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry
                  → processConfigBeanDefinitions(registry)
```

两个容易混淆的时间点：

1. reader 初始化时已把配置类处理器本身注册为基础设施定义。
2. 用户调用 `register` 时只把入口定义加入 Registry；`@ComponentScan` 和 `@Import` 的展开要等 BDRPP 阶段。

因此在 `register` 后、`refresh` 前统计定义数量，只能看到初始集合，不能据此判断扫描失败。

## 动画：候选、配置模型与 Registry 的三段变化

下面 16 帧使用项目 Lab 的真实场景：扫描得到组件，普通 Import 与立即选择器递归展开，延迟选择器排队，条件配置被跳过，reader 最后统一注册 `@Bean` 定义。

<SpringConfigurationClassAnimation />

## processConfigBeanDefinitions 为什么要循环

核心过程不是一次 `for`，而是“发现 → 解析 → 注册 → 再发现”：

```text
candidateNames = registry.getBeanDefinitionNames()
  → checkConfigurationClassCandidate(...)
  → parser.parse(candidates)
  → parser.validate()
  → reader.loadBeanDefinitions(configClasses)
  → configClasses.removeAll(alreadyParsed)
  → 若 Registry 名称数量增长
       检查新定义中尚未处理的配置候选
       回到 parser.parse(newCandidates)
  → 直到没有新候选
```

循环存在的原因是配置解析本身会增加定义：`@ComponentScan` 先直接注册扫描结果，reader 又会注册导入配置和 `@Bean` 定义；其中任何新定义都可能再次具备配置候选特征。

`alreadyParsed` 与 candidate 名称集合共同避免重复解析。调试时只看 parser 当前参数会误以为某些配置丢失，应该同时观察：

- `candidateNames`：本轮开始时 Registry 快照；
- `candidates`：本轮真正需要 parse 的定义持有者；
- `parser.getConfigurationClasses()`：累计配置模型；
- `alreadyParsed`：前面轮次已落库的模型；
- `registry.getBeanDefinitionCount()`：reader 执行前后变化。

## full 与 lite 是配置方法调用语义，不是功能等级

### full 配置

典型条件是 `@Configuration(proxyBeanMethods = true)`，Spring 5.3 默认值为 true。`checkConfigurationClassCandidate` 把 BeanDefinition 属性标记为 `full`，后续 `postProcessBeanFactory` 调用 `enhanceConfigurationClasses`，把 beanClass 替换为实现 `EnhancedConfiguration` 的 CGLIB 子类。

```java
@Configuration
class FullConfig {
    @Bean
    Dependency dependency() {
        return new Dependency();
    }

    @Bean
    Client client() {
        return new Client(dependency());
    }
}
```

创建 `client` 时，自调用 `dependency()` 会被 `BeanMethodInterceptor` 拦截：

1. 从增强实例中取得已注入的 BeanFactory。
2. 计算目标 `@Bean` 方法对应的 beanName。
3. 判断当前调用是否正是 BeanFactory 为创建该 bean 而发起的工厂方法调用。
4. 若是容器创建入口，调用原始方法体产生实例。
5. 若是其他 `@Bean` 方法的自调用，转为 `beanFactory.getBean(beanName)`。

因此项目测试中 `FullClient` 持有的依赖与容器 `getBean(FullDependency.class)` 是同一引用，工厂方法只执行一次。

### lite 配置

下列情况常被标记为 lite：

- `@Configuration(proxyBeanMethods = false)`；
- 普通 `@Component` 类中声明 `@Bean`；
- 只有 `@Import`、`@ComponentScan`、`@ImportResource` 或 `@Bean` 特征的独立类。

lite 仍会解析并注册 `@Bean` 定义，但不保证同类方法自调用回到容器：

```java
@Configuration(proxyBeanMethods = false)
class LiteConfig {
    @Bean
    Dependency dependency() {
        return new Dependency();
    }

    @Bean
    Client client() {
        return new Client(dependency()); // 普通 Java 调用，新建对象
    }
}
```

项目测试稳定得到两次 `dependency()` 执行：一次创建容器定义对应单例，一次由 `client()` 直接调用。推荐把依赖写成工厂方法参数：

```java
@Bean
Client client(Dependency dependency) {
    return new Client(dependency());
}
```

这样 full/lite 都通过容器依赖解析取得对象，语义更明确，也避免 CGLIB 代理成本。`static @Bean` 本身不依赖配置实例，也不参与实例方法自调用拦截；这正是 BFPP 等早期基础设施常声明为 static 的原因。

## doProcessConfigurationClass 的主要顺序

Spring 5.3.39 的主要阅读顺序：

1. 处理配置类自身的 `@Component` 成员类。
2. 处理 `@PropertySource`。
3. 处理 `@ComponentScan`，立即调用 scanner 注册扫描结果，再递归解析其中的配置候选。
4. 收集并处理 `@Import`，区分配置类、ImportSelector、DeferredImportSelector 与 Registrar。
5. 处理 `@ImportResource`。
6. 读取当前类的 `@Bean` 方法元数据，加入 ConfigurationClass 模型。
7. 处理接口中的默认 `@Bean` 方法。
8. 沿非 `java.*` 父类继续解析。

这是一条“元数据构模”链。parser 不调用 `@Bean` 方法，也不创建组件实例；reader 只注册定义，实例化还要等 `finishBeanFactoryInitialization` 或后续显式 `getBean`。

## ComponentScan 为什么会立刻改变 Registry

`ComponentScanAnnotationParser.parse` 创建/配置 `ClassPathBeanDefinitionScanner`，`doScan` 发现候选后直接执行：

```text
scanCandidate
  → checkCandidate(beanName, beanDefinition)
  → BeanDefinitionHolder
  → registerBeanDefinition(holder, registry)
```

parser 随后检查扫描返回的 BeanDefinitionHolder：若某个扫描类本身是配置候选，就调用 `parse` 递归处理。于是同一次调用里同时存在：

- Registry 已经新增组件定义；
- ConfigurationClass 模型还在继续增长；
- 组件对象仍未实例化。

## 四种 Import 路径

| Import 类型 | 何时决定 | 产物 |
| --- | --- | --- |
| 直接配置类 | 当前 `processImports` | 递归 ConfigurationClass |
| `ImportSelector` | 当前解析轮次调用 `selectImports` | 返回类名继续递归 |
| `DeferredImportSelector` | 普通配置解析结束后统一 process | 可分组排序/去重后继续递归 |
| `ImportBeanDefinitionRegistrar` | parser 先保存 registrar，reader 阶段回调 | registrar 自己直接注册 BeanDefinition |

Deferred 不等于懒加载 Bean，也不等到 refresh 完成后执行。它只是把“选择导入配置”的时点推迟到普通配置候选处理之后、reader 落库之前。Spring Boot 的 `AutoConfigurationImportSelector` 正是 DeferredImportSelector。

## 条件有两个评估阶段

`ConfigurationCondition.ConfigurationPhase` 区分：

- `PARSE_CONFIGURATION`：决定整个配置类是否进入 parser 模型；适合影响扫描/导入的条件。
- `REGISTER_BEAN`：reader 准备注册配置类或 BeanMethod 时再次判断；适合只影响定义落库的条件。

条件返回 false 的语义是“跳过相关配置/BeanDefinition”，不是先注册再销毁实例。条件实现不应依赖尚未创建的普通业务 Bean；它工作在定义阶段，主要使用 Environment、ResourceLoader、ClassLoader、Registry 与元数据。

## reader 怎样把模型写回 Registry

`loadBeanDefinitionsForConfigurationClass` 依次处理：

1. 被 import 的配置类本身需要时注册为 BeanDefinition。
2. 每个 `BeanMethod` 转为 `ConfigurationClassBeanDefinition`，保存 factoryBeanName、factoryMethodName、返回类型、作用域、lazy、autowire 等元数据。
3. 加载 `@ImportResource` 指定资源。
4. 回调 ImportBeanDefinitionRegistrar。

`@Bean` 方法重载、别名、作用域代理、`@Lazy`、init/destroy 和条件都在这里参与定义构造。定义覆盖是否允许由 Registry 策略决定；不能因为后注册就默认一定静默替换。

## 实验如何验证

项目 `ConfigurationClassParsingTest` 固定三类结果：

| 场景 | 行为断言 |
| --- | --- |
| full `@Bean` 自调用 | Client 依赖与容器单例同引用，工厂调用 1 次 |
| lite `@Bean` 自调用 | Client 持有直接创建对象，与容器对象不同，工厂调用 2 次 |
| scan/import/condition | 扫描、直接 Import、立即/延迟选择器 Bean 都存在，条件 Bean 不存在 |

推荐断点：

1. `ConfigurationClassPostProcessor.processConfigBeanDefinitions`：前后对比 Registry 名称。
2. `ConfigurationClassParser.doProcessConfigurationClass`：观察 sourceClass、componentScans、imports、beanMethods。
3. `DeferredImportSelectorHandler.handle/process`：看 holder 何时入队和展开。
4. `ConditionEvaluator.shouldSkip`：看 phase、conditions 和最终 match。
5. `ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsForBeanMethod`：看 factoryBeanName 与 factoryMethodName。
6. `ConfigurationClassEnhancer.BeanMethodInterceptor.intercept`：对比 full 自调用与容器工厂调用。

下一步进入 [依赖解析](./dependency-resolution.md)，跟踪这些定义怎样成为候选并被注入点逐轮筛选。
