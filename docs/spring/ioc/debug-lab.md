# Spring IOC 断点实验手册

## 实验入口

模块：`labs/spring-framework-lab`

主类：`io.github.javasourceatlas.spring.ioc.SpringIocDebugLab`

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest='BeanFactoryPostProcessorOrderTest,CircularDependencyBehaviorTest,ConfigurationClassParsingTest,DependencyResolutionBehaviorTest,EarlyAopProxyIdentityTest,SpringIocLifecycleTest' test

mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=ioc
```

模块可单独运行：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest='BeanFactoryPostProcessorOrderTest,CircularDependencyBehaviorTest,ConfigurationClassParsingTest,DependencyResolutionBehaviorTest,EarlyAopProxyIdentityTest,SpringIocLifecycleTest' test
```

## 主案例调用链

```text
new AnnotationConfigApplicationContext()
  → register(LabConfiguration.class)
  → refresh()
      → TraceBeanFactoryPostProcessor 修改 traceService.prefix
      → 注册 TraceBeanPostProcessor
      → 创建 TraceService
          → 构造
          → setPrefix
          → BeanNameAware
          → BeanClassLoaderAware
          → BeanFactoryAware
          → ApplicationContextAwareProcessor 调用 ApplicationContextAware
          → BPP before initialization
          → InitializingBean.afterPropertiesSet
          → customInit
          → BPP after initialization
      → 发布 ContextRefreshedEvent
  → getBean("traceProduct")        // FactoryBean 产品
  → getBean("&traceProduct")       // FactoryBean 自身
  → close()
      → 发布 ContextClosedEvent
      → DisposableBean.destroy
      → customDestroy
```

所有事件写入 `LifecycleEvents`，自动测试断言关键相对顺序，而不是依赖整个 Spring 基础设施 Bean 的全局顺序。

## 实验一：定义修改与初始化顺序

运行 `SpringIocLifecycleTest.shouldRunDefinitionAndBeanLifecycleInOrder`。

`TraceBeanFactoryPostProcessor` 把 `traceService` 的 `prefix` 改成 `configured-by-bfpp`。测试同时证明：

- BFPP 在目标 Bean 构造前运行；
- 普通属性填充在构造后、Aware 和初始化前完成；
- `BeanNameAware → BeanClassLoaderAware → BeanFactoryAware` 由 `initializeBean` 直接调用，`ApplicationContextAware` 随后由初始化前处理器调用；
- `afterPropertiesSet` 早于自定义 init-method；
- `DisposableBean.destroy` 早于自定义 destroy-method。

## 实验二：上下文事件

`ContextLifecycleListener` 记录 `ContextRefreshedEvent` 与 `ContextClosedEvent`。刷新完成事件发生在非懒单例初始化后；关闭事件会在单例销毁前发布。

业务代码不要把关闭事件监听器当成唯一资源释放点。真正属于 Bean 的资源应放进标准 destroy 回调，容器才能按依赖关系组织销毁。

## 实验三：FactoryBean

运行 `shouldDistinguishFactoryBeanFromItsProduct`：

```text
getBean("traceProduct")  → TraceProduct
getBean("&traceProduct") → TraceProductFactoryBean
```

连续两次按普通名称获取得到同一产品，因为实验 FactoryBean 的 `isSingleton()` 返回 true，产品被 FactoryBean 专用缓存保存。

## 实验四：循环依赖边界

`CircularDependencyBehaviorTest` 直接使用 `DefaultListableBeanFactory` 和 `RootBeanDefinition`，避免注解扫描遮挡核心缓存路径：

| 测试 | 预期 |
| --- | --- |
| singleton Setter A ↔ B | 成功，B 持有的 A 与最终 A 是同一引用 |
| constructor A ↔ B | 抛出 `BeanCreationException` |
| prototype Setter A ↔ B | 抛出 `BeanCreationException` |
| 关闭 `allowCircularReferences` 后的 singleton Setter | 抛出 `BeanCreationException` |

构造器循环失败与关闭循环引用后的 Setter 循环虽然最终都表现为 `BeanCreationException`，失败时刻不同：前者连 A#raw 都没有，后者有 A#raw 但没有为它登记早期引用工厂。分别在 `createBeanInstance` 与 `earlySingletonExposure` 判断处停住，差异会很直观。

## 实验五：早期代理与最终单例的身份一致性

运行 `EarlyAopProxyIdentityTest.shouldReuseEarlyAopProxyAsFinalSingleton`。该测试不是手写一个假代理直接塞进缓存，而是把以下基础设施注册到真实 `GenericApplicationContext`：

- `RecordingAutoProxyCreator`：继承 `DefaultAdvisorAutoProxyCreator`，记录 `getEarlyBeanReference` 的真实返回值；
- `DefaultPointcutAdvisor`：只拦截 A 的 `greet` 方法，返回值增加 `advised:` 前缀；
- `RawA ↔ CycleB`：通过 Setter 形成单例循环，B 以接口类型接收 A 的 JDK 动态代理。

测试同时固定五个结论：A 确实是 Spring AOP 代理；代理方法确实经过 Advisor；B 持有的 A 是早期代理；容器最终 `getBean("proxiedA")` 返回同一引用；代理内部的原始 A 也已经注入完整 B。

建议给 `AbstractAutoProxyCreator.getEarlyBeanReference` 加 `beanName == "proxiedA"` 条件断点。继续到 `postProcessAfterInitialization`，可看到 `earlyProxyReferences.remove(cacheKey) == bean`，所以此处不会再调用 `wrapIfNecessary` 创建第二个代理。最后回到 `doCreateBean`，`earlySingletonReference` 会成为最终 `exposedObject`。

## 实验六：工厂后处理器的真实顺序

运行 `BeanFactoryPostProcessorOrderTest`。三个测试把容易被“统一按 Ordered 排序”一句话掩盖的分支单独固定下来：

| 测试场景 | 可观察结论 |
| --- | --- |
| 无序 BDRPP 在 registry 回调中注册另一个无序 BDRPP | 新处理器会被后续 reiterate 循环发现，两个 registry 回调都不会漏掉 |
| BDRPP 与程序化普通 BFPP 同时存在 | 所有 registry 回调先完成，再执行 BDRPP 的 factory 回调，最后才到程序化普通 BFPP |
| 先注册 `LOWEST_PRECEDENCE` BFPP，再注册 `HIGHEST_PRECEDENCE` BFPP | 程序化列表仍保持注册顺序，证明这条路径忽略 `Ordered` 值 |

这些测试只断言公开扩展回调留下的事件，不反射读取 `PostProcessorRegistrationDelegate` 内部集合。升级 Spring 后可以先复跑行为测试，再用相同断点核对实现是否仍采用相同编排。

## 实验七：配置类怎样扩展 Registry

运行 `ConfigurationClassParsingTest`。第一、第二个测试故意使用结构相同的 full/lite 配置：

| 配置方式 | `client()` 中的 `dependency()` | 对象身份 | 工厂调用次数 |
| --- | --- | --- | --- |
| `@Configuration` 默认 full | 被 CGLIB `BeanMethodInterceptor` 转为 `getBean` | 与容器单例相同 | 1 |
| `@Configuration(proxyBeanMethods = false)` | 普通 Java 自调用 | 与容器单例不同 | 2 |

先在 `ConfigurationClassPostProcessor.processConfigBeanDefinitions` 记录 `candidateNames` 和定义数量，再进入 `ConfigurationClassParser.doProcessConfigurationClass`。第三个测试会让 Registry 依次出现扫描组件、直接导入配置、立即选择器配置和延迟选择器配置；条件不匹配的配置会在定义落库前被跳过。

重点不是背诵注解顺序，而是分清三类状态：

1. Registry 保存当前已登记的 BeanDefinition。
2. parser 保存仍在构建的 ConfigurationClass 模型。
3. reader 把模型中的 `@Bean`、导入配置和 Registrar 结果写回 Registry，写回的新定义可能触发下一轮候选发现。

调试 full 配置时，在 `ConfigurationClassEnhancer.BeanMethodInterceptor.intercept` 观察 `isCurrentlyInvokedFactoryMethod`。容器正创建 `dependency` 时会执行原方法体；创建 `client` 期间发生的 `dependency()` 自调用则改走 BeanFactory。lite 配置不会进入该拦截器。

## 实验八：依赖候选怎样筛选和裁决

运行 `DependencyResolutionBehaviorTest`。测试把单值、多值和延迟访问拆开，避免一次断点里混入太多分支：

| 测试 | 关键结果 | 应观察的方法 |
| --- | --- | --- |
| Primary 与 Qualifier | 普通 `Gateway` 选 primary，`@Qualifier("batch")` 只保留 batch | `isAutowireCandidate`、`determineAutowireCandidate` |
| 泛型候选 | `GenericRepository<Customer>` 排除 Order 仓库 | `checkGenericTypeMatch` |
| `List<Handler>` | 保留两个候选并按 `Ordered` 得到 `first → second` | `resolveMultipleBeans` |
| Optional / Provider / Lazy | 缺失依赖不报错，Lazy 目标首次调用才创建 | `resolveDependency`、`buildLazyResolutionProxy` |
| 无唯一候选 | 两个等价 Gateway 使 refresh 失败 | `determineAutowireCandidate` |

在 `DefaultListableBeanFactory.doResolveDependency` 先确认 `descriptor.getResolvableType()`，再单步到 `findAutowireCandidates`。`candidateNames` 是按类型找到的原始名字，`matchingBeans` 才是经过自动装配资格、泛型和 Qualifier 过滤后的集合。单值集合仍有多个元素时，才进入 Primary、Priority、依赖名称裁决；多值依赖不会选出一个 Primary，而是保留全部合格候选后排序。

`ObjectProvider` 与 `@Lazy` 都会延迟目标获取，但实现路径不同：Provider 把查询交给调用方，`@Lazy` 注入的是代理。实验用创建计数器证明 refresh 后真实 `HeavyService` 尚未实例化，第一次调用 `load()` 时才进入 `getBean`。

## 推荐断点与变量

| 类与方法 | 条件或变量 | 目的 |
| --- | --- | --- |
| `AbstractApplicationContext.refresh` | `active`、`closed`、定义数量 | 建立容器级时间线 |
| `TraceBeanFactoryPostProcessor.postProcessBeanFactory` | `traceService` 定义的 propertyValues | 确认改的是定义而非实例 |
| `PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors` | `processedBeans`、`registryProcessors`、`regularPostProcessors` | 观察 BDRPP 多轮发现及两类 factory 回调顺序 |
| `DefaultListableBeanFactory.preInstantiateSingletons` | `beanName` | 找到目标非懒单例入口 |
| `AbstractBeanFactory.doGetBean` | `beanName == "traceService"` | 观察缓存、scope 与合并定义 |
| `AbstractAutowireCapableBeanFactory.doCreateBean` | `earlySingletonExposure`、`exposedObject` | 观察创建五阶段 |
| `initializeBean` | `wrappedBean` 类型和引用 | 核对 Aware、init、BPP |
| `DefaultSingletonBeanRegistry.getSingleton` | `beanName == "setterA"` | 看三级缓存迁移 |
| `AbstractAutoProxyCreator.getEarlyBeanReference` | `beanName == "proxiedA"` | 观察早期代理只创建一次 |
| `AbstractAutoProxyCreator.postProcessAfterInitialization` | `beanName == "proxiedA"` | 核对 earlyProxyReferences 避免二次包装 |
| `FactoryBeanRegistrySupport.getObjectFromFactoryBean` | `beanName == "traceProduct"` | 区分产品缓存 |
| `DisposableBeanAdapter.destroy` | destroyMethodName | 核对销毁顺序 |
| `ConfigurationClassPostProcessor.processConfigBeanDefinitions` | `candidateNames`、`alreadyParsed`、定义数量 | 观察 Registry 多轮增长 |
| `ConfigurationClassParser.doProcessConfigurationClass` | `configClass`、`sourceClass` | 区分 Scan、Import、条件和 BeanMethod 构模 |
| `DeferredImportSelectorHandler.process` | `deferredImportSelectors`、`handler` | 观察延迟选择器统一展开 |
| `ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsForBeanMethod` | `configClass`、`beanMethod`、`beanName` | 观察配置模型写回 BeanDefinition |
| `ConfigurationClassEnhancer.BeanMethodInterceptor.intercept` | `beanMethod`、`beanName`、`isCurrentlyInvokedFactoryMethod` | 对比 full 自调用与容器工厂调用 |
| `DefaultListableBeanFactory.doResolveDependency` | `descriptor`、`matchingBeans`、`autowiredBeanName` | 观察特殊包装、多值和单值解析主线 |
| `GenericTypeAwareAutowireCandidateResolver.checkGenericTypeMatch` | `dependencyType`、`targetType` | 观察泛型候选被保留或排除 |
| `QualifierAnnotationAutowireCandidateResolver.checkQualifiers` | `annotations`、`bdHolder` | 观察 Qualifier 资格过滤 |
| `DefaultListableBeanFactory.determineAutowireCandidate` | `candidates`、`descriptor` | 判断 Primary、Priority、名称或歧义结果 |
| `ContextAnnotationAutowireCandidateResolver.buildLazyResolutionProxy` | `descriptor`、`targetSource` | 观察 Lazy 代理创建与首次目标解析 |

## 一次建议的变量快照

在 `traceService` 刚完成实例化、尚未填充时记录：

| 变量 | 预期值 |
| --- | --- |
| `beanName` | `traceService` |
| `mbd.scope` | 空字符串（等价 singleton） |
| `bean.prefix` | 构造默认值，尚未应用 BFPP 写入的属性值 |
| `earlySingletonExposure` | true |
| `singletonFactories` | 即将加入 `traceService` 工厂 |

刚进入 `initializeBean` 时再次记录：`prefix` 已是 `configured-by-bfpp`，但直接 Aware 尚未执行。继续越过 `invokeAwareMethods` 后，`beanName`、`beanClassLoader` 和 `beanFactory` 已写入；`applicationContext` 仍要等 `ApplicationContextAwareProcessor` 在 BPP before 链中写入，随后才到实验自己的 `TraceBeanPostProcessor`。

配置解析实验建议再做两次快照。第一次停在 reader 写回前：扫描组件可能已经进入 Registry，但导入配置中的 `@Bean` 仍只存在于 parser 模型；第二次停在下一轮候选检查时：定义数量已经增长，`alreadyParsed` 能阻止前一轮配置重复处理。

依赖解析实验在 `findAutowireCandidates` 返回后记录 `descriptor.getResolvableType()`、`candidateNames` 与 `matchingBeans.keySet()`。继续到 `resolveCandidate` 前，候选可能只是类型占位；越过它后才会调用 `getBean` 取得实例，并由调用方执行 `registerDependentBean` 建立销毁顺序所需的依赖关系。

## 调试注意

- Maven 依赖固定为 Spring Framework 5.3.39；IDE 附加的源码也必须是同一版本。
- 方法断点开销较大，优先使用源码行断点和 `beanName` 条件。
- Evaluate Expression 中调用 `getBean` 会真实创建对象并改变缓存，不要用它“只看一下”。
- 配置解析阶段不要在 Evaluate Expression 中主动实例化配置或业务 Bean；parser/reader 本来只处理元数据与定义。
- 查看 Lazy/Provider 时先记录创建计数，再触发代理方法或 `getIfAvailable`；否则无法证明实例化确实延迟。
- 循环依赖断点需要限制为 `setterA`/`setterB` 或代理实验的 `proxiedA`/`cycleB`，否则 Spring 内部基础设施的缓存访问会淹没时间线。
- 上下文必须关闭；否则看不到销毁回调，也可能让测试间静态事件残留。

## 公开契约与实现边界

实验断言的是公开可观察行为：生命周期回调的相对次序、FactoryBean 名称语义、单例 Setter 循环的支持边界、早期 AOP 代理与最终容器对象身份一致、full/lite 配置方法调用的对象身份差异，以及依赖解析对泛型、Qualifier、Primary、多值和延迟包装的结果。测试不会反射读取三级缓存、配置解析器集合或候选解析器内部状态。

缓存字段、parser/reader 集合和 protected 方法只用于断点观察。升级 Spring 6.x 时，需要切换 Java 17、重新附加对应源码并复跑测试；不要仅凭 5.3.39 的字段名称、集合形态或局部变量布局判断 6.x 行为。
