# Bean、早期引用与最终代理：一个对象的完整时间轴

理解 IOC 与 AOP 的交界，不能只背三个 Map，也不能只看 `wrapIfNecessary`。必须跟踪同一个 `beanName` 在四种形态之间怎样变化：BeanDefinition、刚实例化的 target、按需产生的早期引用、最终发布到一级缓存的对象。

## 源码入口

| 职责 | 类与方法 |
| --- | --- |
| Bean 查询总控 | `AbstractBeanFactory.doGetBean(...)` |
| 单例创建保护 | `DefaultSingletonBeanRegistry.getSingleton(String,ObjectFactory<?>)` |
| Bean 创建模板 | `AbstractAutowireCapableBeanFactory.createBean(...)` / `doCreateBean(...)` |
| 实例化 | `createBeanInstance(...)` |
| 提前暴露 | `addSingletonFactory(...)` / `getEarlyBeanReference(...)` |
| 属性注入 | `populateBean(...)` / `AutowiredAnnotationBeanPostProcessor.postProcessProperties(...)` |
| 初始化 | `initializeBean(...)` |
| 自动代理 | `AbstractAutoProxyCreator.getEarlyBeanReference(...)` / `postProcessAfterInitialization(...)` |
| 最终注册 | `DefaultSingletonBeanRegistry.addSingleton(...)` |
| 销毁 | `DisposableBeanAdapter.destroy()` / `DefaultSingletonBeanRegistry.destroySingletons()` |

## 容器扩展点的严格顺序

先区分“处理定义”和“处理对象”。定义处理器运行时，普通业务对象原则上还没有创建；Bean 处理器运行时，当前对象已经进入具体生命周期。

### 容器级顺序

```text
invokeBeanFactoryPostProcessors
  1. BeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry
     - PriorityOrdered
     - Ordered
     - 其余，并重复发现新注册的 BDRPP
  2. 所有 BDRPP.postProcessBeanFactory
  3. 普通 BeanFactoryPostProcessor.postProcessBeanFactory

registerBeanPostProcessors
  1. PriorityOrdered BeanPostProcessor
  2. Ordered BeanPostProcessor
  3. 其余 BeanPostProcessor
  4. MergedBeanDefinitionPostProcessor 重新放到链尾附近
  5. ApplicationListenerDetector 最后重新注册
```

“实现了 `Ordered` 就一定按数值执行”不够准确。程序化传给 context 的处理器与通过 BeanDefinition 自动发现的处理器，进入编排器的路径不同；BDRPP 的 registry 回调还允许继续注册新的 BDRPP，需要循环发现。

### 单个 Bean 的扩展点顺序

| 阶段 | 真实扩展点 | 当前对象状态 | 允许改变什么 |
| --- | --- | --- | --- |
| 创建前短路 | `InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation` | 还没有普通 target | 可直接返回替代对象并跳过常规创建 |
| 实例化 | 构造器、工厂方法、Supplier | target 已产生，属性未注入 | 建立对象身份 |
| 合并定义后 | `MergedBeanDefinitionPostProcessor.postProcessMergedBeanDefinition` | target 已有，定义已合并 | 缓存注入/生命周期元数据 |
| 实例化后 | `postProcessAfterInstantiation` | target 字段大多为空 | 可否决后续属性填充 |
| 属性处理 | `postProcessProperties` | 正在注入依赖 | 处理 `@Autowired` 等注入点 |
| Aware | `invokeAwareMethods` + `ApplicationContextAwareProcessor` | 依赖已填充 | 注入容器基础能力 |
| 初始化前 | `postProcessBeforeInitialization` | 即将执行 init | 可包装或调整对象 |
| 初始化 | `afterPropertiesSet` + 自定义 init-method | target 完成自初始化 | 执行公开生命周期回调 |
| 初始化后 | `postProcessAfterInitialization` | 原始 Bean 已完整 | 常规自动代理包装点 |
| 销毁登记 | `registerDisposableBeanIfNecessary` | 最终暴露对象已确定 | 保存销毁适配器，不立即销毁 |

`@PostConstruct` 常由 `CommonAnnotationBeanPostProcessor` 在初始化前处理器阶段触发，不应硬写成 `InitializingBean.afterPropertiesSet` 之后。

## doCreateBean 的对象时间轴

```text
doGetBean("orderService")
  -> getSingleton("orderService", singletonFactory)
      -> beforeSingletonCreation
      -> singletonFactory.getObject()
          -> createBean
              -> resolveBeforeInstantiation
              -> doCreateBean
                  1. createBeanInstance                = raw target
                  2. applyMergedBeanDefinitionPostProcessors
                  3. addSingletonFactory               = early reference factory
                  4. populateBean                       = dependencies injected
                  5. initializeBean                     = possibly wrapped object
                  6. reconcile earlySingletonReference = identity check
                  7. registerDisposableBeanIfNecessary
      -> addSingleton("orderService", exposedObject)
      -> afterSingletonCreation
```

`singletonFactory.getObject()` 外层的 `getSingleton(name, ObjectFactory)` 负责创建中标记、异常时销毁残留依赖、最终加入一级缓存等动作。只在 `doCreateBean` 里找 `singletonObjects.put` 会错过真正注册点。

## 三级缓存保存的不是三个生命周期阶段

| 缓存 | value | 何时写入 | 何时读取 | 关键语义 |
| --- | --- | --- | --- | --- |
| `singletonObjects` | 完整单例或最终代理 | 创建成功后 `addSingleton` | 普通 `getBean` 首选 | 对外稳定身份 |
| `earlySingletonObjects` | 已经生成过的早期引用 | 三级工厂首次执行后迁移 | 创建中依赖查询 | 同一早期身份只生成一次 |
| `singletonFactories` | `ObjectFactory<?>` | target 实例化后、属性填充前 | 发生真实循环查询时 | 延迟决定返回 target 还是代理 |

三级缓存不是按固定时间自动轮转：

- 没有其他 Bean 在创建中请求当前 Bean，三级工厂可能从未执行。
- 工厂执行后，结果才放入二级缓存并删除三级工厂。
- 创建成功后，`addSingleton` 写一级缓存并清理二、三级条目。
- 创建失败时，单例注册器清理相关缓存和创建中标记。

## Setter 循环和早期代理的一次完整执行

假设 `orderService` 与 `auditService` 通过 Setter 互相依赖，且 `orderService` 命中事务 Advisor：

```text
getBean(orderService)
  -> instantiate orderTarget
  -> addSingletonFactory(orderService, getEarlyBeanReference)
  -> populateBean(orderService)
      -> getBean(auditService)
          -> instantiate auditTarget
          -> addSingletonFactory(auditService, getEarlyBeanReference)
          -> populateBean(auditService)
              -> getBean(orderService)
                  -> orderService 正在创建
                  -> singletonFactories[orderService].getObject()
                      -> AbstractAutoProxyCreator.getEarlyBeanReference
                      -> earlyProxyReferences.put(cacheKey, orderTarget)
                      -> wrapIfNecessary(orderTarget)
                      -> orderProxy
                  -> earlySingletonObjects[orderService] = orderProxy
              -> auditService.orderService = orderProxy
          -> initialize auditService
          -> publish auditService
      -> orderService.auditService = auditService
  -> initialize orderTarget
      -> AbstractAutoProxyCreator.postProcessAfterInitialization
          -> earlyProxyReferences.remove(cacheKey) == orderTarget
          -> 不再 wrap 第二次
  -> doCreateBean 取得 earlySingletonReference = orderProxy
  -> exposedObject 仍为 orderTarget，因此替换成 orderProxy
  -> singletonObjects[orderService] = orderProxy
```

### 必须成立的身份关系

```text
auditService.getOrderService()
    == applicationContext.getBean("orderService")
    == singletonObjects["orderService"]
    == orderProxy
```

`orderTarget` 仍是代理内部的目标对象，但不应同时作为另一个 Bean 的注入结果向外扩散。否则同一个 beanName 会出现两种调用语义：拿到 proxy 的调用有事务，拿到 raw target 的调用没有事务。

## earlyProxyReferences 解决什么

自动代理创建器的两个方法形成配对：

```text
getEarlyBeanReference(bean, beanName)
  -> earlyProxyReferences.put(cacheKey, bean)
  -> wrapIfNecessary(bean, beanName, cacheKey)
  -> earlyProxy

postProcessAfterInitialization(bean, beanName)
  -> if (earlyProxyReferences.remove(cacheKey) != bean)
       wrapIfNecessary(bean, beanName, cacheKey)
     else
       return bean
```

Map 保存的是原始 `bean` 标记，不是最终代理缓存。它告诉初始化后处理器：“这个原始对象已经在早期引用路径创建过代理，不要再创建第二个。”最终是否用早期代理替换 `exposedObject`，由 `doCreateBean` 的一致性检查完成。

## doCreateBean 最后的三种结果

| `exposedObject` | `earlySingletonReference` | 结果 |
| --- | --- | --- |
| 仍是原始 bean | 存在 | 用早期引用替换，保证注入和最终发布一致 |
| 已被初始化后处理器包装 | 不存在 | 发布常规最终包装对象 |
| 已被包装成另一个对象 | 存在且有依赖者拿过早期 raw/不同引用 | 可能抛 `BeanCurrentlyInCreationException`，防止静默身份分裂 |

`allowRawInjectionDespiteWrapping` 不是修复代理一致性的通用开关。放宽后可能让依赖者继续持有 raw target，而容器查询返回 proxy，事务、安全或缓存通知将表现不一致。

## 为什么构造器循环仍然失败

构造 `orderService` 时必须先取得构造参数 `auditService`；构造 `auditService` 又先取得 `orderService`。此时 `orderTarget` 尚未产生，`doCreateBean` 没有机会执行 `addSingletonFactory`：

```text
resolve constructor(orderService)
  -> getBean(auditService)
      -> resolve constructor(auditService)
          -> getBean(orderService)
              -> orderService 正在创建
              -> singletonFactories 中没有 orderService
              -> BeanCurrentlyInCreationException
```

代理能力不能创造尚未实例化的 target。`@Lazy` 或 `ObjectProvider` 能通过延迟依赖打断一边，但那是改变依赖形态，不是三级缓存突然支持构造器环。

## 代理类型对依赖解析的影响

| 场景 | JDK 代理 | CGLIB 代理 |
| --- | --- | --- |
| 按业务接口注入 | 可用 | 可用 |
| 按具体实现类注入 | 通常不可把 JDK proxy 当实现类 | 类代理通常可赋值给实现类 |
| final 类/方法 | JDK 可代理接口调用 | final 类不能生成子类代理，final 方法不能覆盖拦截 |
| target 内 `this.inner()` | 绕过代理 | 同样绕过 Spring 外部代理入口 |

在循环依赖中，若注入点要求具体实现类型，但早期引用是 JDK proxy，类型匹配可能直接失败。不要把“三级缓存支持 Setter 循环”理解为无条件支持任意代理类型和注入声明。

## 常见误判

| 误判 | 实际情况 |
| --- | --- |
| 三级缓存每次创建 Bean 都会走一遍 | 没有循环查询时工厂可以从未执行 |
| 二级缓存保存 raw target | 保存三级工厂第一次产生的早期引用，可能是 proxy |
| `earlyProxyReferences` 缓存最终 proxy | 它主要记录原始 Bean 已走过早期包装路径 |
| 初始化后总会再创建正式代理 | 早期代理已产生时必须跳过重复包装 |
| 注入成功就说明代理一致 | 应比较依赖者持有引用与 context 查询结果的对象身份 |
| 开启循环引用可以解决构造器环 | 当前对象尚未实例化，三级工厂无从注册 |
| 使用 CGLIB 就没有自调用问题 | target 内部 `this` 调用仍不经过外部代理 |

## 断点路线：只跟一个 beanName

以 `beanName.equals("orderService")` 为条件，建议顺序如下：

| 顺序 | 断点 | 观察变量 |
| --- | --- | --- |
| 1 | `AbstractBeanFactory.doGetBean` | `beanName`、`sharedInstance`、`mbd` |
| 2 | `beforeSingletonCreation` | `singletonsCurrentlyInCreation` |
| 3 | `AbstractAutowireCapableBeanFactory.doCreateBean` | `bean`、`exposedObject`、`earlySingletonExposure` |
| 4 | `addSingletonFactory` | 三个缓存是否包含目标名称 |
| 5 | `populateBean` | `pvs`、注入点和 dependentBeans |
| 6 | `DefaultSingletonBeanRegistry.getSingleton(name,true)` | `singletonObject` 从哪个缓存取得 |
| 7 | `AbstractAutoProxyCreator.getEarlyBeanReference` | `cacheKey`、`earlyProxyReferences`、返回对象身份 |
| 8 | `postProcessAfterInitialization` | `remove(cacheKey)` 与当前 bean 是否同一对象 |
| 9 | `doCreateBean` 早期引用协调段 | `exposedObject`、`earlySingletonReference` |
| 10 | `addSingleton` | 最终写入一级缓存的对象类型和 identity hash |

使用调试器的对象 ID 或 `System.identityHashCode` 做观察，不要在业务代码里依赖 identity hash 作为稳定标识。

## 可运行案例映射

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest=CircularDependencyBehaviorTest test

mvn -pl labs/spring-framework-lab \
  -Dtest=SpringAopBehaviorTest#shouldCreateProxyThroughApplicationContext test
```

| 案例 | 能证明什么 | 不能单独证明什么 |
| --- | --- | --- |
| `shouldResolveSingletonSetterCycle` | Setter 单例环能取得可用引用 | 该引用一定是 AOP proxy |
| `shouldRejectConstructorCycle` | 构造器环在实例产生前失败 | 所有循环依赖都失败 |
| `shouldRejectSetterCycleWhenCircularReferencesAreDisabled` | 工厂策略能关闭早期引用路径 | Framework 不再拥有三级缓存实现 |
| `shouldCreateProxyThroughApplicationContext` | BPP 能把容器暴露 Bean 包装成 proxy | 循环依赖中的早期/最终 proxy 身份 |

要验证“早期代理就是最终代理”，应在带 AOP 的 Setter 循环最小案例中同时断言依赖者字段与 `context.getBean` 使用 `assertSame`。自动测试只断言公开对象身份，缓存迁移仍由上述断点观察。

## 过关问题

1. 为什么三级缓存保存 `ObjectFactory` 比直接保存 raw target 更适合 AOP 场景？
2. `earlySingletonObjects` 中的对象一定是未初始化 target 吗？
3. `postProcessAfterInitialization` 如何知道早期路径已经创建过代理？
4. 为什么最终一致性替换发生在 `initializeBean` 之后，而不是三级工厂执行时？
5. `allowRawInjectionDespiteWrapping` 可能让同一 beanName 出现哪两种调用语义？
6. 一个 JDK proxy 的早期引用为什么可能无法注入声明为具体实现类的字段？
7. 销毁回调最终针对 target、proxy 还是适配器保存的对象执行，应到哪个类确认？

下一章从已经稳定发布的 Controller 和 Service proxy 出发，跟踪一次真实 MVC 请求如何建立并完成事务。
