# IOC 扩展点：在正确的时间改变正确的对象

## 源码入口

- 工厂后处理：`PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(...)`
- Bean 后处理器注册：`PostProcessorRegistrationDelegate.registerBeanPostProcessors(...)`
- Bean 初始化：`AbstractAutowireCapableBeanFactory.initializeBean(...)`
- 实例化感知扩展：`InstantiationAwareBeanPostProcessor`
- 工厂产品适配：`FactoryBeanRegistrySupport.getObjectFromFactoryBean(...)`

## 四类名称相近但职责不同的扩展

| 扩展 | 操作对象 | 典型时机 | 适合做什么 |
| --- | --- | --- | --- |
| `BeanDefinitionRegistryPostProcessor` | 定义注册表 | 普通 BFPP 之前 | 新增更多 BeanDefinition |
| `BeanFactoryPostProcessor` | 工厂和定义元数据 | 普通 Bean 创建前 | 修改属性值、作用域、占位符 |
| `BeanPostProcessor` | 每个 Bean 实例 | 初始化前后 | 注解回调、校验、包装代理 |
| `FactoryBean<T>` | 一个产品对象 | `getBean` 产品适配时 | 把复杂创建过程封装成一个 Bean |

口诀可以是：“Registry 处理器增加配方，Factory 处理器修改配方，Bean 处理器加工成品，FactoryBean 负责生产一种成品。”

## 调用链与顺序

```text
refresh()
  ├─ invokeBeanFactoryPostProcessors
  │    ├─ BeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry
  │    ├─ BeanDefinitionRegistryPostProcessor.postProcessBeanFactory
  │    └─ BeanFactoryPostProcessor.postProcessBeanFactory
  └─ registerBeanPostProcessors
       └─ Bean 创建时
            ├─ postProcessBeforeInstantiation
            ├─ postProcessAfterInstantiation
            ├─ postProcessProperties
            ├─ postProcessBeforeInitialization
            ├─ init callbacks
            ├─ postProcessAfterInitialization
            └─ postProcessBeforeDestruction
```

“同一大类都按 `PriorityOrdered`、`Ordered`、普通对象排序”只适用于容器按 Bean 类型扫描出来的处理器，程序化传入的处理器是重要例外。`@Order` 是否生效也取决于具体收集路径；实现 `PriorityOrdered`/`Ordered` 是基础设施代码更明确的选择，但仍不能改变程序化处理器按注册顺序执行的规则。

### BDRPP 与 BFPP 的精确执行顺序

Spring 5.3.39 的 `invokeBeanFactoryPostProcessors` 不是简单地把所有处理器放进一个列表排序，而是分阶段执行：

1. 先遍历通过 `addBeanFactoryPostProcessor` 程序化加入的处理器。若它是 BDRPP，立即执行 registry 回调；否则先放入普通程序化 BFPP 列表。这里不读取 `Ordered`，完全保持注册顺序。
2. 再从 BeanDefinition 中查找 BDRPP，依次执行 `PriorityOrdered`、`Ordered` 两组。
3. 对所有尚未处理的 BDRPP 循环查找。某个 BDRPP 在 registry 回调中注册的新 BDRPP，会在下一轮被发现并执行；即使新处理器实现了 `PriorityOrdered` 或 `Ordered`，只要错过前两轮分组，也会进入这个 reiterate 阶段。每轮内部仍使用工厂的依赖比较器排序。
4. 所有 BDRPP 的 registry 回调结束后，统一执行这些 BDRPP 的 `postProcessBeanFactory` 回调。
5. 接着执行第 1 步暂存的程序化普通 BFPP，仍按注册顺序且忽略它们声明的 `Ordered` 值。
6. 最后处理 BeanDefinition 中尚未执行的普通 BFPP，依次为 `PriorityOrdered`、`Ordered`、无序三组。

因此，BDRPP 的两个回调不是紧邻发生的；registry 阶段可以多轮扩充定义，所有 registry 工作完成后才进入 factory 回调。若多个扩展必须有稳定先后关系，优先把它们声明为容器 Bean 并实现顺序接口，或在一个处理器内部显式编排，不要误以为给程序化实例实现 `Ordered` 就会被重排。

## BeanFactoryPostProcessor：此时只改定义

BFPP 的关键价值是普通 Bean 尚未批量创建，可以统一改写定义。常见例子是属性占位符解析和配置类展开。

BFPP 内主动调用普通 Bean 的 `getBean()` 会造成提前实例化：该 Bean 可能错过尚未注册的 BeanPostProcessor。除非处理器职责本身要求创建基础设施对象，否则应读取和修改 BeanDefinition，而不是取得业务实例。

声明 BFPP 的 `@Bean` 方法通常应为 `static`。这样容器无需过早实例化配置类即可创建处理器，避免配置类错过完整后处理链。

## BeanPostProcessor：一次调用会经过多个处理器

BeanPostProcessor 是容器级链，不是只作用于声明它的配置类。方法允许返回不同对象，后续处理器接收上一个处理器的返回值。

重要子接口：

| 子接口 | 关键方法 | 用途 |
| --- | --- | --- |
| `InstantiationAwareBeanPostProcessor` | 实例化前后、`postProcessProperties` | 参与实例化短路与依赖注入 |
| `SmartInstantiationAwareBeanPostProcessor` | 预测类型、候选构造器、早期引用 | 自动代理与循环依赖协调 |
| `MergedBeanDefinitionPostProcessor` | `postProcessMergedBeanDefinition` | 缓存注入或生命周期元数据 |
| `DestructionAwareBeanPostProcessor` | `postProcessBeforeDestruction` | 执行销毁前回调，如生命周期注解 |

自动装配和 AOP 都建立在处理器体系上，但它们进入的具体钩子不同。不要把 BeanPostProcessor 简化为只有初始化前后两个方法。

### BeanPostProcessor 注册链的两个收尾动作

BeanPostProcessor Bean 会按 `PriorityOrdered`、`Ordered`、无序三组实例化和注册。不过，Spring 还会把实现 `MergedBeanDefinitionPostProcessor` 的内部处理器重新注册一次：`addBeanPostProcessor` 会先移除旧位置再添加，因此它们最终被移动到处理器链靠后位置。

最后，容器再注册一个新的 `ApplicationListenerDetector`，使它处在链尾，用于识别创建出来的内部监听器 Bean。这里说的是 Spring 5.3.39 的内部编排细节，业务代码不应依赖具体索引；它解释的是为什么只看前三组排序仍无法还原最终处理器链。

## Aware：把容器基础设施交给 Bean

直接由 `initializeBean` 判断的三个接口是：

1. `BeanNameAware`
2. `BeanClassLoaderAware`
3. `BeanFactoryAware`

ApplicationContext 在 `prepareBeanFactory` 注册 `ApplicationContextAwareProcessor`，由该处理器在初始化前回调更多 context Aware 接口。

在 Spring 5.3.39 中，这组回调的完整顺序是：`EnvironmentAware`、`EmbeddedValueResolverAware`、`ResourceLoaderAware`、`ApplicationEventPublisherAware`、`MessageSourceAware`、`ApplicationStartupAware`、`ApplicationContextAware`。其中 `ApplicationStartupAware` 容易在旧版流程图中遗漏。

Aware 是公开契约，但会让对象感知 Spring。领域对象更适合构造器注入明确依赖；基础设施适配器才更常需要 Aware。

## FactoryBean：名称有两种取值语义

假设容器中 Bean 名称是 `client`，其实例实现 `FactoryBean<Client>`：

| 调用 | 返回 |
| --- | --- |
| `getBean("client")` | `FactoryBean.getObject()` 产生的 Client |
| `getBean("&client")` | FactoryBean 实例 |
| `getType("client")` | 尽量通过泛型、`getObjectType()` 或必要的提前检查推断产品类型 |

FactoryBean 自己是否 singleton 与产品是否 singleton 是两个问题。`FactoryBean.isSingleton()` 描述产品是否具备单例语义；FactoryBean 实例仍由它自身 BeanDefinition 的 scope 管理。

Spring 进入全局 `factoryBeanObjectCache` 还要求 FactoryBean 自身的名称已经存在于普通 singleton registry。也就是说，prototype FactoryBean 即使让 `isSingleton()` 返回 true，产品也不会跨多个工厂实例进入这份全局缓存；每次取得新的 FactoryBean 实例仍会重新调用 `getObject()`。

`getObject()` 返回的产品还会经过 `postProcessObjectFromFactoryBean`，因此产品也可被部分 BeanPostProcessor 加工。产品缓存位于 `FactoryBeanRegistrySupport`，不要与普通单例三级缓存混为一谈。

## 生命周期快照

| 时刻 | 定义是否可改 | 普通实例是否已存在 | 可见扩展 |
| --- | --- | --- | --- |
| BDRPP registry 回调 | 是，可新增 | 通常否 | 注册表、定义名称 |
| BFPP factory 回调 | 是，可修改 | 不应批量存在 | ConfigurableListableBeanFactory |
| BPP 注册阶段 | 定义已基本稳定 | 处理器实例存在 | BeanPostProcessor 列表 |
| populateBean | 当前定义只读使用 | 原始实例存在 | InstantiationAware BPP |
| initializeBean | 是实例加工阶段 | 已注入 | Aware、init、BPP |
| close | 不再创建新业务依赖 | 单例待销毁 | DestructionAware BPP、destroy 回调 |

## 断点建议

1. `PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors`：记录每组处理器名称与顺序。
2. `ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry`：观察注册表回调如何增加定义。
3. `registerBeanPostProcessors`：比较注册前后 `beanPostProcessorCount`。
4. `AbstractAutowireCapableBeanFactory.applyBeanPostProcessorsBefore/AfterInitialization`：记录同一 Bean 的引用变化。
5. `ApplicationContextAwareProcessor.invokeAwareInterfaces`：区分 context Aware 与直接 Aware。
6. `AbstractBeanFactory.getObjectForBeanInstance`：分别用普通名称和 `&` 前缀进入。
7. `FactoryBeanRegistrySupport.getObjectFromFactoryBean`：观察 singleton 产品缓存。

## 公开契约与实现边界

上述接口及其 Javadoc 是公开扩展契约；具体收集类 `PostProcessorRegistrationDelegate`、内部检查器、处理器缓存和 FactoryBean 产品缓存是实现细节。

自定义扩展应保持小而明确，并避免依赖同级无序处理器的偶然顺序。Spring 6.x 要求 Java 17，并使用 Jakarta 生命周期注解；AOT 模式下动态注册、反射构造和代理可能需要运行时提示。扩展接口仍在，但能否被 AOT 静态分析应单独验证。
