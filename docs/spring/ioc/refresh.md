# refresh：把定义集合变成可用容器

## 源码入口

- 主入口：`AbstractApplicationContext.refresh()`
- 工厂准备：`prepareBeanFactory(ConfigurableListableBeanFactory)`
- 扩展委派：`PostProcessorRegistrationDelegate`
- 非懒单例：`DefaultListableBeanFactory.preInstantiateSingletons()`
- 收尾：`finishRefresh()`

基线源码位于 `spring-context` 模块的 `org.springframework.context.support.AbstractApplicationContext`。以下顺序针对 Spring Framework 5.3.39。

## 完整调用链

```text
refresh()                         // startupShutdownMonitor 内串行化
  ├─ prepareRefresh()
  ├─ obtainFreshBeanFactory()
  │    ├─ refreshBeanFactory()
  │    └─ getBeanFactory()
  ├─ prepareBeanFactory(beanFactory)
  └─ try                               // 保护范围从这里开始
       ├─ postProcessBeanFactory(beanFactory)
       ├─ invokeBeanFactoryPostProcessors(beanFactory)
       ├─ registerBeanPostProcessors(beanFactory)
       ├─ initMessageSource()
       ├─ initApplicationEventMulticaster()
       ├─ onRefresh()
       ├─ registerListeners()
       ├─ finishBeanFactoryInitialization(beanFactory)
       │    └─ preInstantiateSingletons()
       └─ finishRefresh()
            ├─ initLifecycleProcessor()
            ├─ lifecycleProcessor.onRefresh()
            └─ publishEvent(new ContextRefreshedEvent(this))
     catch BeansException
       ├─ destroyBeans()
       └─ cancelRefresh(exception)
     finally
       ├─ resetCommonCaches()
       └─ contextRefresh.end()
```

这里有一个容易被流程图抹平的边界：`prepareRefresh()`、`obtainFreshBeanFactory()`、`prepareBeanFactory()` 位于源码中的 `try` 之前。只有从 `postProcessBeanFactory()` 开始抛出的 `BeansException` 才进入此处的销毁与取消刷新逻辑，`resetCommonCaches()` 也只属于这个 `try/finally`。外围的同步块仍会正常释放监视器，但不能把前三步失败描述成经过同一套容器回滚。

## 动画：每一步改变了什么

<SpringRefreshAnimation />

动画把 12 个源码动作收敛为六组职责。第 10 步才集中创建大多数非懒单例；在此之前重点是准备工厂、扩展定义和登记处理器。

## 十二个阶段逐段拆解

### 1. prepareRefresh

记录 `startupDate`，把 `closed` 置为 false、`active` 置为 true，执行 `initPropertySources()` 并校验 `Environment` 的必需属性。它还恢复静态监听器集合，并创建 `earlyApplicationEvents` 保存广播器就绪前发布的事件。

如果必需属性缺失，刷新在创建业务 Bean 前失败。

### 2. obtainFreshBeanFactory

该方法先调用子类的 `refreshBeanFactory()`，再取得内部工厂。不同上下文实现语义不同：

- `AbstractRefreshableApplicationContext` 的子类可以为一次刷新创建新的 `DefaultListableBeanFactory`。
- `GenericApplicationContext` 持有单个工厂，通常只允许调用一次 `refresh()`。

所以“refresh 一定新建 BeanFactory”不是跨实现契约。

### 3. prepareBeanFactory

这一步安装容器通用能力：BeanClassLoader、SpEL 解析器、资源属性编辑器、`ApplicationContextAwareProcessor`、忽略自动装配的 Aware 接口、可解析依赖以及环境相关单例。

`ApplicationContextAware` 并不是由 `initializeBean` 中直接判断完成，而是由这里加入的 `ApplicationContextAwareProcessor` 回调。

### 4. postProcessBeanFactory

这是 `AbstractApplicationContext` 留给子类的模板钩子。标准注解上下文没有必须执行的业务逻辑，但 Web 上下文等子类可继续调整作用域或环境。

### 5. invokeBeanFactoryPostProcessors

先处理 `BeanDefinitionRegistryPostProcessor`，让它们有机会继续注册定义，再处理普通 `BeanFactoryPostProcessor`。配置类解析器 `ConfigurationClassPostProcessor` 就在这里把 `@ComponentScan`、`@Import` 和 `@Bean` 转为更多定义。

容器从 BeanDefinition 中查找到的处理器会按 `PriorityOrdered`、`Ordered`、无顺序接口分阶段执行；通过 `addBeanFactoryPostProcessor` 程序化加入的处理器则保持注册顺序，不参与这三组排序。BDRPP 还存在动态发现的循环，完整规则见 [IOC 扩展点](./extension-points.md#bdrpp-与-bfpp-的精确执行顺序)。

### 6. registerBeanPostProcessors

把实现 `BeanPostProcessor` 的 Bean 实例化并注册进工厂。之后创建普通 Bean 时，实例化前后、属性填充和初始化前后才有完整扩展链可用。

这解释了为何一个 Bean 被过早创建时可能提示“不适合被所有 BeanPostProcessor 处理”：它错过了尚未注册的处理器。

### 7. 初始化消息源与事件广播器

容器分别查找名称固定为 `messageSource`、`applicationEventMulticaster` 的 Bean。不存在时使用默认实现。这里的名称是公开约定，具体默认实现属于版本细节。

事件广播器在此时已经存在，但 `earlyApplicationEvents` 仍非 null。此后到 `registerListeners()` 之前调用 `publishEvent`，事件仍只会加入早期事件集合，不会立刻广播；这样可以避免事件在监听器尚未登记完整时丢失。

### 8. onRefresh

另一个留给上下文子类的模板方法。它位于监听器注册和非懒单例创建之前，适合子类建立必须先存在的基础设施。

### 9. registerListeners

先登记通过 API 添加的监听器，再登记工厂中的 `ApplicationListener` Bean 名称。使用名称可以保持懒获取与后处理机会。最后广播之前暂存的早期事件，并清空早期集合。

### 10. finishBeanFactoryInitialization

这一步会：

1. 采用名称为 `conversionService` 的转换服务（如果类型匹配）。
2. 补充嵌入值解析器。
3. 提前初始化 `LoadTimeWeaverAware`。
4. 清除临时 ClassLoader。
5. `freezeConfiguration()`，缓存稳定定义元数据。
6. `preInstantiateSingletons()`，创建非抽象、非懒加载的单例。

`SmartInitializingSingleton.afterSingletonsInstantiated()` 在普通单例预实例化循环结束后统一回调，适合依赖“所有常规单例已就绪”的基础设施。

对于普通 `FactoryBean`，预实例化首先保证工厂 Bean 自身就绪，不代表产品一定在此刻创建；只有 `SmartFactoryBean.isEagerInit()` 明确要求提前初始化，或后续有人按普通名称取值，才会创建产品。

### 11. finishRefresh

清理资源缓存，初始化 `LifecycleProcessor`，调用其 `onRefresh()`，再发布 `ContextRefreshedEvent`。收到刷新完成事件时，非懒单例通常已经创建完毕。

### 12. 成功与失败清理

如果从 `postProcessBeanFactory` 开始的 try 块抛出 `BeansException`，`refresh()` 会通过当前 BeanFactory 的 `destroySingletons()` 销毁其中已经登记的单例，并通过 `cancelRefresh` 取消 active 状态，然后重新抛出。这里不只限于“本轮新建”的对象；对于允许预先注册单例的上下文，工厂中已有的单例也属于该清理范围。这个 try 块无论成功还是失败，finally 都执行 `resetCommonCaches()` 并结束本轮 `StartupStep`。

前三个准备步骤不在该 try/catch/finally 内，因此它们失败时不会走上述 `destroyBeans`、`cancelRefresh` 和公共缓存重置路径。阅读异常栈时要先确认失败点，再判断能否套用“刷新失败会执行单例清理与 `cancelRefresh`”这一结论。

不要把刷新失败理解成数据库事务回滚：Spring 会清理它管理的单例，但 Bean 构造或初始化过程中产生的外部副作用必须由应用自己保证幂等与补偿。

## 变量快照

| 断点位置 | 关键变量 | 典型状态 |
| --- | --- | --- |
| `refresh` 入口 | `active`、`closed`、`startupDate` | 尚未切换到本轮刷新状态 |
| `obtainFreshBeanFactory` 返回 | `beanFactory`、定义数量 | 已有初始配置类定义，未必已展开全部 `@Bean` |
| BFPP 执行后 | `beanDefinitionNames` | 扫描、导入和 `@Bean` 定义通常已加入 |
| BPP 注册后 | `beanPostProcessorCount` | 自动装配、常见注解和代理基础处理器已经登记 |
| `preInstantiateSingletons` | `beanName`、`bd.isLazyInit()` | 逐个创建合格的非懒单例 |
| `finishRefresh` | `lifecycleProcessor`、事件类型 | 生命周期处理器就绪，准备发布刷新完成事件 |

## 断点建议

1. 在 `refresh()` 每个 protected 调用处使用方法断点或逐步进入，先只记录定义数量和单例数量。
2. 在 `ConfigurationClassPostProcessor.processConfigBeanDefinitions` 前后对比 BeanDefinition 名称集合。
3. 在 `registerBeanPostProcessors` 返回后查看处理器数量，不要在 Evaluate 中主动调用 `getBean`。
4. 在 `preInstantiateSingletons` 对实验 Bean 名称加条件断点，避开 Spring 自身基础设施 Bean。
5. 在 `finishRefresh` 的事件发布处确认监听器回调发生在非懒单例创建之后。
6. 增加一个初始化失败场景，观察 `destroyBeans`、`cancelRefresh` 和 finally 清理顺序。

## 公开契约与实现边界

应用可以依赖 `ConfigurableApplicationContext.refresh/close`、上下文事件和标准扩展接口的公开语义。通常不应在业务代码中手动调用 `AbstractApplicationContext` 的 protected 阶段方法，也不应假设某个内部处理器一定排在具体索引。

Spring 6.x 仍保留可辨认的刷新模板，但要求 Java 17，内部启动指标、AOT 处理与缓存实现可能变化。定位目标版本问题时，应以对应 6.x tag 的 `AbstractApplicationContext` 为准，而不是把本页阶段中的字段细节当作兼容契约。
