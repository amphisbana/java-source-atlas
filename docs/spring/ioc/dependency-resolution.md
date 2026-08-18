# 依赖解析：从 DependencyDescriptor 到唯一候选或有序集合

Bean 实例化只回答“对象怎样产生”，自动装配还要回答另一个问题：一个构造器参数、工厂方法参数或字段需要 `T` 时，容器怎样从当前 Registry 中找到正确的 Bean，何时创建它，找不到或找到多个时怎样失败。

本页以 Spring Framework `v5.3.39` 为基线，主线是：

```text
注入点元数据
  → DependencyDescriptor / ResolvableType
  → resolveDependency / doResolveDependency
  → findAutowireCandidates
  → 泛型、Qualifier、Primary、Priority、名称裁决
  → 单值 / 多值 / Optional / Provider / Lazy 分流
  → resolveCandidate(getBean)
  → registerDependentBean
```

## 固定版本源码入口

| 类 | 固定版本源码 | 职责 |
| --- | --- | --- |
| `DependencyDescriptor` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/config/DependencyDescriptor.java) | 保存注入点类型、泛型、注解、required、名称与嵌套层次 |
| `DefaultListableBeanFactory` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java) | `resolveDependency`、`doResolveDependency`、候选收集与单值裁决 |
| `AutowiredAnnotationBeanPostProcessor` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/annotation/AutowiredAnnotationBeanPostProcessor.java) | 扫描字段/方法注入点并构造 descriptor |
| `ConstructorResolver` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/support/ConstructorResolver.java) | 解析构造器与 `@Bean` 工厂方法参数 |
| `GenericTypeAwareAutowireCandidateResolver` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/support/GenericTypeAwareAutowireCandidateResolver.java) | 使用 ResolvableType 检查候选泛型 |
| `QualifierAnnotationAutowireCandidateResolver` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/annotation/QualifierAnnotationAutowireCandidateResolver.java) | 处理 `@Qualifier` 与建议值 |
| `ContextAnnotationAutowireCandidateResolver` | [源码](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/annotation/ContextAnnotationAutowireCandidateResolver.java) | 为 `@Lazy` 注入点创建延迟解析代理 |

## 三条入口最终汇到同一个工厂

### 构造器参数

```text
ConstructorResolver.autowireConstructor
  → createArgumentArray
  → resolveAutowiredArgument
  → beanFactory.resolveDependency(descriptor, beanName, autowiredBeanNames, typeConverter)
```

### @Bean 工厂方法参数

同样由 `ConstructorResolver.instantiateUsingFactoryMethod → createArgumentArray` 构造 descriptor。项目 Lab 的 `resolutionTarget(...)` 用一个 `@Bean` 方法参数列表同时触发 Primary、Qualifier、泛型、List、Optional、ObjectProvider 与 Lazy。

### @Autowired 字段/方法

```text
AutowiredAnnotationBeanPostProcessor.postProcessProperties
  → InjectionMetadata.inject
  → AutowiredFieldElement / AutowiredMethodElement.inject
  → beanFactory.resolveDependency(...)
```

入口不同，但候选搜索和裁决仍落在同一个 `DefaultListableBeanFactory`，所以分析依赖问题时应先找到 DependencyDescriptor，而不是只盯着注解处理器。

## 动画：候选集合怎样收缩

下面 15 帧分别展示单值裁决、多值收集和延迟包装。动画把“过滤”和“选择”拆开：Qualifier/泛型先决定谁有资格，Primary/Priority/名称再在合格候选中裁决唯一项。

<SpringDependencyResolutionAnimation />

## DependencyDescriptor 保存什么

`DependencyDescriptor` 继承 InjectionPoint，核心信息包括：

| 信息 | 来源 | 用途 |
| --- | --- | --- |
| dependencyType | 字段或参数原始类型 | 初步按类型查找名称 |
| resolvableType | 含泛型的字段/MethodParameter | 区分 `Repository<Customer>` 与 `Repository<Order>` |
| annotations | 注入点注解 | Qualifier、Value、Lazy 等解析 |
| required | `@Autowired(required=...)` 或嵌套包装 | 决定缺失时返回 null/empty 还是抛错 |
| dependencyName | 字段名或可发现的参数名 | 后期 Bean 名称匹配线索 |
| containingClass | 实际包含类 | 解析继承和泛型变量 |
| nestingLevel | Optional、集合、Provider 的嵌套层次 | 取得真正元素类型 |

descriptor 还提供 `resolveShortcut`、`resolveCandidate`、`resolveNotUnique` 等模板方法。Spring 可以针对特殊注入元数据定制快捷结果或歧义行为，而 DefaultListableBeanFactory 负责默认算法。

## resolveDependency 先处理特殊包装

Spring 5.3.39 的分流顺序值得单独记：

1. `Optional<T>`：构造嵌套 descriptor，required=false，最终包装为 Optional。
2. `ObjectFactory<T>` / `ObjectProvider<T>`：返回 DependencyObjectProvider，不立刻解析/创建 T。
3. `javax.inject.Provider<T>`：存在 JSR-330 时返回对应 provider。
4. 普通依赖：交给 `doResolveDependency`。

`@Lazy` 不在这里按 Java 包装类型分支；它由 context 的 AutowireCandidateResolver 在 doResolveDependency 前创建 lazy resolution proxy。

## doResolveDependency 的主线

概念化流程：

```text
descriptor.initParameterNameDiscovery(parameterNameDiscoverer)
  → descriptor.resolveShortcut(beanFactory)
  → autowireCandidateResolver.getSuggestedValue(descriptor)  // @Value
  → resolveMultipleBeans(descriptor, ...)
  → findAutowireCandidates(requestingBeanName, type, descriptor)
  → 0 个：fallback / required 检查
  → 1 个：直接选中
  → 多个：determineAutowireCandidate
  → descriptor.resolveCandidate(beanName, requiredType, beanFactory)
  → converter.convertIfNecessary(result, type, descriptor)
```

这说明自动装配不只等于“按类型 getBean”：建议值、多值集合、父工厂候选、FactoryBean 类型预测、泛型、限定符、延迟代理和类型转换都参与结果。

## findAutowireCandidates 先找名字，再逐个校验资格

主要步骤：

1. `BeanFactoryUtils.beanNamesForTypeIncludingAncestors` 找到本地与祖先工厂中的类型候选名。
2. 把 `resolvableDependencies` 中可直接赋值的基础设施对象加入结果，例如 BeanFactory 类型本身。
3. 排除与 `requestingBeanName` 相同的普通自引用候选。
4. 调用 `isAutowireCandidate(candidateName, descriptor)`。
5. 若首轮为空，按 fallback descriptor 再尝试；集合与部分自引用有额外边界。

候选 value 有时是已创建实例，有时只是类型或 Class 占位。收集候选不应无条件实例化全部 Bean，否则 `@Lazy` 和类型查询都会失去意义。

## 泛型过滤发生在候选资格阶段

注入点：

```java
GenericRepository<Customer> repository
```

两个候选：

```text
customerRepository: GenericRepository<Customer>
orderRepository:    GenericRepository<Order>
```

`GenericTypeAwareAutowireCandidateResolver.checkGenericTypeMatch` 尝试从以下位置取得 target type：

- RootBeanDefinition 已缓存的 targetType；
- factory method return type；
- BeanDefinition 的 beanClass 与泛型父接口；
- FactoryBean 产品类型预测。

若解析到的候选 ResolvableType 与 descriptor 不兼容，候选在 Primary 之前就被淘汰。`@Primary` 不能让错误泛型的 Bean 强行匹配。

原始类型、未解析泛型或装饰定义可能触发回退匹配；设计公共扩展点时应保留准确工厂方法返回类型，不要一律声明成 `Object`。

## Qualifier 是资格过滤，不是最后排序

`QualifierAnnotationAutowireCandidateResolver` 会比较注入点限定注解与候选的：

- BeanDefinition qualifier 元数据；
- 工厂方法注解；
- 实现类注解；
- Bean 名称/别名作为 qualifier 值的回退匹配。

自定义注解可以自身标记 `@Qualifier`，把多字段业务语义封装为类型安全限定符。对重要路由语义，显式限定符比依赖 Java 参数名可靠。

项目 `@Qualifier("batch") Gateway` 注入只保留 `batchGateway`；即使 `primaryGateway` 带 `@Primary`，它已在资格过滤阶段被排除，Primary 不会覆盖显式 Qualifier。

## 单值依赖怎样从多个合格候选中选一个

Spring 5.3.39 的默认裁决顺序可以概括为：

1. **唯一 Primary**：`determinePrimaryCandidate`。多个 primary 会产生异常。
2. **唯一最高 Priority**：`determineHighestPriorityCandidate`。相同最高值仍有歧义。
3. **resolvable dependency 或 Bean 名称匹配**：候选名/别名与 descriptor dependencyName 对照。
4. **仍不唯一**：required 单值调用 `resolveNotUnique`，默认抛 `NoUniqueBeanDefinitionException`。

不要把 `Ordered`、`@Order`、`@Priority` 混成同一规则：

- `Ordered/@Order` 最常用于集合和扩展链排序。
- `@Priority` 可参与单值最高优先级裁决，具体由工厂的 priority comparator 提供。
- `@Primary` 是单值自动装配的明确主候选语义。

项目歧义测试注册 `leftGateway/rightGateway`，没有 Qualifier、Primary 或名称匹配，refresh 明确失败；Spring 不会按注册顺序静默选择。

## 多值依赖不做 Primary 单选

`resolveMultipleBeans` 识别：

- `Stream<T>`；
- 数组 `T[]`；
- `Collection<T>` / `List<T>` / `Set<T>`；
- `Map<String, T>`。

它们收集全部合格候选，随后按目标形态转换。数组和 List 可使用工厂的 dependencyComparator 排序；Map 的 key 必须是 String，值为候选 Bean。

项目两个 Handler 分别返回 Ordered 10 和 20，注入结果稳定为 `first → second`。这不意味着 BeanDefinition 注册顺序被修改，只是注入结果按比较器组织。

## Optional、ObjectProvider 与 @Lazy 的差别

| 方式 | 注入时是否完成候选解析 | 目标是否立即创建 | 缺失/歧义语义 |
| --- | --- | --- | --- |
| `Optional<T>` | 是 | 需要 T 时按普通规则创建 | 缺失为空；多候选仍可能歧义 |
| `ObjectProvider<T>` | 否，注入 provider | 调用 provider 时解析 | `getIfAvailable` 可容忍缺失；`getObject` 要求结果 |
| `@Lazy T` | 注入时创建代理和基本 descriptor | 首次代理调用时创建/取得目标 | 运行期仍可能因缺失/歧义失败 |

ObjectProvider 每次调用可以重新查容器，适合 prototype、可选插件和延迟遍历；不要在构造器里立刻 `provider.getObject()`，那会抵消延迟价值。

`@Lazy` 注入由 `ContextAnnotationAutowireCandidateResolver` 构建 TargetSource 和代理。项目使用接口 HeavyService，refresh 后真实实现创建数仍为 0；第一次 `load()` 才变为 1。

## 选中候选后才真正取得实例

确定 beanName 之后，`descriptor.resolveCandidate` 默认调用：

```java
beanFactory.getBean(beanName)
```

随后校验 NullBean、required type 与类型转换，并把候选名加入 `autowiredBeanNames`。调用方再执行 `registerDependentBean(autowiredBeanName, requestingBeanName)`，建立依赖关系，用于：

- 销毁时先销毁依赖者；
- 查询 dependent/dependencies；
- 部分循环依赖与创建顺序诊断。

“候选定义存在”与“实例已经创建”必须分开看。断点停在 findAutowireCandidates 时，许多 value 仍是类型占位；继续到 resolveCandidate 才可能进入目标 Bean 的 `doGetBean/doCreateBean`。

## 常见失败怎样定位

### NoSuchBeanDefinitionException

检查：

1. descriptor 的 ResolvableType 是否是预期泛型。
2. BeanDefinition 是否已在当前/父 Registry 注册。
3. 条件、profile 或扫描边界是否跳过定义。
4. autowireCandidate 标志与 Qualifier 是否排除了所有候选。
5. 当前依赖是否 required，是否应改为 Optional/Provider。

### NoUniqueBeanDefinitionException

检查异常列出的候选名称，然后依次确认：

1. 是否应该用业务 Qualifier 明确选择。
2. 是否确有一个合理的默认实现可标记 Primary。
3. 注入点本来是否应该是 List/Map，而不是单值。
4. 是否误把多个环境配置同时注册。

不要通过改字段名碰巧匹配 Bean 名来掩盖核心歧义；重构参数名或关闭 `-parameters` 后可能再次失败。

## 推荐断点

| 方法 | 变量 | 回答的问题 |
| --- | --- | --- |
| `DefaultListableBeanFactory.resolveDependency` | `descriptor`, `requestingBeanName` | 是否走 Optional/Provider/普通分支 |
| `doResolveDependency` | `type`, `matchingBeans`, `autowiredBeanName` | 候选在何时从 0/1/多个变为结果 |
| `findAutowireCandidates` | `candidateNames`, `result` | 类型查询找到了谁，资格过滤留下谁 |
| `isAutowireCandidate` | `beanName`, `descriptor` | 泛型/Qualifier 为什么排除某候选 |
| `determineAutowireCandidate` | `candidates`, `descriptor.dependencyName` | Primary/Priority/名称哪一步裁决 |
| `resolveMultipleBeans` | `type`, `elementType`, `matchingBeans` | List/Map 为什么保留全部候选 |
| `ContextAnnotationAutowireCandidateResolver.buildLazyResolutionProxy` | `descriptor`, `targetSource` | Lazy 代理何时创建、何时取真实 Bean |
| `DependencyDescriptor.resolveCandidate` | `beanName`, `requiredType` | 何时真正进入 getBean |

项目 `DependencyResolutionBehaviorTest` 在 Java 8/17 上固定 Primary、Qualifier、泛型、List 排序、Optional、ObjectProvider、Lazy 和歧义失败八类结果。完成后回到 [Bean 创建完整链路](./bean-creation.md)，把“选择谁”和“怎样创建”连成一条调用栈。
