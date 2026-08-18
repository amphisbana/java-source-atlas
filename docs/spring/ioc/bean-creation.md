# Bean 创建：从 getBean 到初始化与销毁

## 源码入口

- 查询模板：`AbstractBeanFactory.doGetBean(...)`
- 创建前短路：`AbstractAutowireCapableBeanFactory.createBean(...)`
- 创建主干：`AbstractAutowireCapableBeanFactory.doCreateBean(...)`
- 实例化：`createBeanInstance(...)`
- 属性填充：`populateBean(...)`
- 初始化：`initializeBean(...)`
- 销毁适配：`DisposableBeanAdapter.destroy()`

## 完整调用链

```text
getBean(name)
  └─ doGetBean(name, requiredType, args, typeCheckOnly=false)
       ├─ transformedBeanName(name)
       ├─ getSingleton(beanName)                 // 完整或早期缓存
       ├─ 父 BeanFactory 回退（本地没有定义时）
       ├─ getMergedLocalBeanDefinition(beanName)
       ├─ 先创建 dependsOn 依赖
       ├─ 按 singleton / prototype / custom scope 取或创建
       │    └─ getSingleton(beanName, singletonFactory) // singleton 分支
       │         ├─ beforeSingletonCreation(beanName)
       │         ├─ createBean(beanName, mbd, args)
       │         │    ├─ resolveBeforeInstantiation() // 处理器可直接返回代理
       │         │    └─ doCreateBean(...)
       │         │         ├─ createBeanInstance(...)
       │         │         ├─ applyMergedBeanDefinitionPostProcessors(...)
       │         │         ├─ addSingletonFactory(...) // 满足条件时提前暴露
       │         │         ├─ populateBean(...)
       │         │         ├─ initializeBean(...)
       │         │         └─ registerDisposableBeanIfNecessary(...)
       │         ├─ afterSingletonCreation(beanName)
       │         └─ addSingleton(beanName, singletonObject)
       ├─ getObjectForBeanInstance(...)          // FactoryBean 产品适配
       └─ adaptBeanInstance(...)                 // requiredType 检查/转换
```

singleton 的外层 `getSingleton(beanName, ObjectFactory)` 不只是一个 Map 查询。它在单例互斥区内标记“正在创建”，调用对象工厂，失败时清除本轮产生的单例状态，成功后再正式写入一级缓存。`doCreateBean` 返回对象不等于它已经进入 `singletonObjects`；正式发布发生在外层对象工厂成功返回之后。

<a id="spring-bean-lifecycle-animation"></a>

## 动画：把对象身份与三级缓存放在同一条时间轴

<SpringBeanLifecycleAnimation />

动画使用 Setter 循环 A ↔ B 展示完整路径。重点观察第 10、11、17 帧：三级工厂只在第一次需要时物化 A 的代理，B 注入这份代理，A 初始化完成后容器仍把同一份代理放入一级缓存，没有再创建第二个代理。

## doGetBean 先解决“取什么”

### 名称规范化

`transformedBeanName` 会解析别名并去掉 FactoryBean 解引用前缀 `&`，得到容器内部规范名称。但原始调用是否带 `&` 会保留给后面的 `getObjectForBeanInstance`：

- `getBean("reportFactory")` 默认返回 FactoryBean 生产的对象。
- `getBean("&reportFactory")` 返回 FactoryBean 实例本身。

### 缓存与父工厂

`doGetBean` 首先尝试单例缓存。若本地不存在 BeanDefinition，则才委托父工厂。父子关系是名称查找回退，不是把两个定义表合并成一张表。

### 合并定义与显式依赖

父子 BeanDefinition、默认值等会合并为 `RootBeanDefinition`。`dependsOn` 表示显式创建顺序和销毁依赖，不等同于字段注入；容器还会检测显式依赖图中的循环。

合并定义仍然是“创建配方”，不是对象。`markBeanAsCreated` 还会清理此前只为类型检查缓存的合并定义，保证正式创建使用当前元数据；合并后的 `RootBeanDefinition` 可被缓存，但应用不应把它当作稳定公开模型。

### 作用域分流

- singleton：使用 `getSingleton(beanName, ObjectFactory)` 保证注册与失败清理。
- prototype：用 ThreadLocal 记录正在创建的原型，创建后立即清除标记，不进入单例缓存。
- custom scope：委托已注册的 `Scope.get`，生命周期由该 Scope 与调用方契约共同决定。

## createBean 可以在实例化前短路

`createBean` 先解析 BeanClass、校验 method override，再调用 `resolveBeforeInstantiation` 询问 `InstantiationAwareBeanPostProcessor`。如果某个处理器在实例化前直接返回对象，容器只继续执行初始化后的处理器链，然后跳过常规 `doCreateBean`。

这是一条基础设施扩展路径，不代表每个 AOP 代理都必然在这里产生。常规自动代理创建器经常在初始化后包装对象，并通过早期引用钩子处理循环依赖。

## doCreateBean 的五段生命周期

### 1. 实例化

`createBeanInstance` 先确认 BeanClass 可实例化，再按下面的优先路径选择策略：

1. 定义了 `instanceSupplier`：调用 Supplier。
2. 定义了 `factoryMethodName`：走实例或静态工厂方法解析。
3. 复用已经解析并缓存的构造器或工厂方法参数。
4. 询问 `SmartInstantiationAwareBeanPostProcessor.determineCandidateConstructors`，再结合显式参数、构造器自动装配模式和候选构造器进入 `autowireConstructor`。
5. 没有特殊条件时使用默认无参构造器路径。

最终结果装进 `BeanWrapper`，容器可在这里初始化属性编辑器。构造器参数解析本身可能触发其他 Bean 的 `getBean`，所以构造器循环依赖发生在当前对象产生可暴露引用之前。

### 2. 合并定义后处理

`MergedBeanDefinitionPostProcessor` 可以缓存注入与生命周期元数据。`AutowiredAnnotationBeanPostProcessor` 会解析字段和方法注入点；`InitDestroyAnnotationBeanPostProcessor` 会查找生命周期注解方法。`RootBeanDefinition.postProcessed` 保证同一份合并定义通常只完成一次该阶段，真正对实例的注入和回调仍发生在后面。

### 3. 提前暴露单例工厂

只有当前 Bean 是 singleton、允许循环引用并且正在创建，才会把一个 `ObjectFactory` 放入三级缓存。这个工厂延迟调用 `getEarlyBeanReference`，让 `SmartInstantiationAwareBeanPostProcessor` 有机会返回与最终代理一致的早期引用。

### 4. 属性填充

`populateBean` 的核心顺序是：

1. `InstantiationAwareBeanPostProcessor.postProcessAfterInstantiation` 可否决后续属性填充。
2. 按定义执行 by-name 或 by-type 自动装配。
3. `postProcessProperties` 处理 `@Autowired` 等注入元数据。
4. 做依赖检查、值解析和类型转换。
5. 通过 `BeanWrapper` 写入属性。

字段/Setter 注入发生在对象已经实例化之后，这正是它可能取得早期单例引用的前提。

`postProcessProperties` 是 Spring 5.3 的主扩展入口；旧的 `postProcessPropertyValues` 只作为兼容回退。`@Autowired` 字段写入可能绕过 JavaBean Setter，而 BeanDefinition 中的普通 property values 最终经 `applyPropertyValues` 做引用解析、类型转换，再交给 `BeanWrapper`。

### 5. 初始化

```text
initializeBean(beanName, bean, mbd)
  ├─ invokeAwareMethods
  │    ├─ BeanNameAware
  │    ├─ BeanClassLoaderAware
  │    └─ BeanFactoryAware
  ├─ BeanPostProcessor.postProcessBeforeInitialization（按注册顺序串行）
  │    ├─ ApplicationContextAwareProcessor 处理更多 context Aware
  │    └─ CommonAnnotationBeanPostProcessor 调用 @PostConstruct
  ├─ InitializingBean.afterPropertiesSet
  ├─ 自定义 init-method
  └─ BeanPostProcessor.postProcessAfterInitialization
       └─ 常见自动代理包装点
```

`EnvironmentAware`、`EmbeddedValueResolverAware`、`ResourceLoaderAware`、`ApplicationEventPublisherAware`、`MessageSourceAware`、`ApplicationStartupAware` 和 `ApplicationContextAware` 由 `ApplicationContextAwareProcessor` 处理，不要误归到 `invokeAwareMethods` 的三个直接判断中。

`@PostConstruct` 不是 `initializeBean` 中的硬编码分支。Spring 5.3.39 由 `CommonAnnotationBeanPostProcessor`（其父类为 `InitDestroyAnnotationBeanPostProcessor`）在自己的 `postProcessBeforeInitialization` 位置反射调用。因此精确顺序应理解为：直接 Aware → 整条 BPP before 链（其中包含 context Aware 与 `@PostConstruct`）→ `InitializingBean` → 自定义 init → 整条 BPP after 链。自定义 BPP 位于 `@PostConstruct` 前还是后取决于处理器注册顺序，不能脱离具体链条写死。

注解上下文会通过标准注解配置处理器注册 `CommonAnnotationBeanPostProcessor`；一个裸的 `DefaultListableBeanFactory` 不会凭空识别 `@PostConstruct`。Spring 5.3 使用 `javax.annotation`，在不再内置该 API 的较新 JDK 上还要显式提供相应依赖；Spring 6 改为 `jakarta.annotation`。

同一个初始化方法如果同时被声明为 `InitializingBean.afterPropertiesSet` 和自定义 `init-method`，适配逻辑会避免重复调用相同方法；生命周期注解方法也会登记为 externally managed init method，避免再按另一种元数据重复执行。

## 初始化结果与原始对象可能不同

`initializeBean` 返回值可能是处理器包装后的代理。`doCreateBean` 会检查是否有人取过早期引用：

- 最终对象仍是原始实例时，可以把已经产生的早期引用作为暴露结果。
- 如果其他 Bean 注入了原始早期对象，而最终对象又被包装成不同代理，默认可能抛出 `BeanCurrentlyInCreationException`，避免依赖方永久持有错误版本。

这段一致性检查是“三级缓存能解决所有代理循环依赖”这一说法不成立的原因之一。

常见的 `AbstractAutoProxyCreator` 会在 `getEarlyBeanReference` 中记录原始对象并提前执行 `wrapIfNecessary`。稍后的 `postProcessAfterInitialization` 看到同一个原始对象已经生成过早期代理，就不再包第二层；最后 `doCreateBean` 用早期代理替换仍为原对象的 `exposedObject`。因此正常协作下有：

```text
B 中注入的 A
  === earlySingletonObjects["A"]
  === 创建结束后 singletonObjects["A"]
  === context.getBean("A")
```

## 销毁链

singleton 创建成功后，容器根据定义与处理器判断是否登记 `DisposableBeanAdapter`。上下文关闭时按依赖关系销毁：依赖当前 Bean 的对象先销毁，随后处理当前 Bean；当前 Bean 的适配器回调结束后，再销毁登记在它名下的 contained beans，并清理依赖图。

单个 Bean 的典型销毁顺序：

```text
DestructionAwareBeanPostProcessor.postProcessBeforeDestruction
  └─ CommonAnnotationBeanPostProcessor 调用 @PreDestroy
  → DisposableBean.destroy()
  → 显式或推断出的 destroy-method
```

`@PreDestroy` 与 `@PostConstruct` 一样由注解生命周期处理器完成，只是它实现 `DestructionAwareBeanPostProcessor` 并在销毁前阶段回调。随后 `DisposableBean.destroy()` 才执行，最后调用不重复的自定义销毁方法。

并非每个 Bean 都会经过图中的全部步骤。`DisposableBean.destroy()` 只在实现接口且没有被标记为外部管理时调用；自定义方法可能来自显式 `destroyMethod`，也可能按定义规则推断为 `AutoCloseable.close()`、公开的 `close()` 或 `shutdown()`。同一个 `destroy` 方法不会因为接口、注解与自定义配置重复执行。

prototype 不由容器自动跟踪完整销毁生命周期；调用方取得原型后通常要自行管理资源。自定义 Scope 由 Scope 负责登记和触发 destruction callback。

## 变量快照

| 阶段 | 变量 | 应观察的变化 |
| --- | --- | --- |
| `doGetBean` 入口 | `name`、`beanName`、`args`、`typeCheckOnly` | 别名与 `&` 被怎样解释 |
| 合并定义后 | `mbd.scope`、`mbd.beanClass`、`dependsOn` | 创建策略是否已经确定 |
| `createBeanInstance` 后 | `instanceWrapper`、`bean`、`beanType` | 原始对象已存在但尚未注入 |
| 提前暴露判断 | `earlySingletonExposure` | 是否具备三级缓存条件 |
| `populateBean` | `pvs`、注入描述符 | 依赖解析和属性值怎样写入 |
| `initializeBean` | `wrappedBean` | 处理器前后返回引用是否变化 |
| 销毁登记 | `requiresDestruction`、scope | 容器是否会负责关闭资源 |

## 断点建议

1. `AbstractBeanFactory.doGetBean`：给 `beanName == "traceService"` 加条件，避免框架基础 Bean 噪声。
2. `createBeanInstance`：观察本例采用工厂方法还是构造器路径。
3. `AutowiredAnnotationBeanPostProcessor.postProcessProperties`：查看 InjectionMetadata 与 DependencyDescriptor。
4. `populateBean` 与 `applyPropertyValues`：区分注解注入和普通属性值写入。
5. `initializeBean`：记录每个处理器前后的对象类型与引用是否相同。
6. `DisposableBeanAdapter.destroy`：关闭上下文后核对销毁接口和自定义方法顺序。

## 公开契约与实现边界

初始化接口、Aware 接口、BeanPostProcessor、作用域和 FactoryBean 是公开扩展契约。`doGetBean`、`doCreateBean`、`BeanWrapper` 的阶段划分与 `RootBeanDefinition` 字段属于实现细节。

业务代码不应通过反射访问缓存，也不应把 BeanPostProcessor 的具体内部类顺序写死。Spring 6.x 中 Jakarta 生命周期注解、AOT 生成元数据和代理机制需要按目标环境复核，但“实例化、填充、初始化、销毁”的概念仍然适用。
