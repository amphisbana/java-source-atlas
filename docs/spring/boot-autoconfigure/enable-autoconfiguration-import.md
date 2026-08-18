# EnableAutoConfiguration：候选发现、过滤与排序

## 从组合注解进入源码

典型启动类使用 `@SpringBootApplication`。在 Boot 2.7.18 中，它组合了三项核心能力：

```text
@SpringBootConfiguration    → 本质是 @Configuration，标记主配置源
@EnableAutoConfiguration   → 导入自动配置选择器
@ComponentScan             → 扫描启动类所在包及子包
```

自动配置主线来自第二项：

```java
@Import(AutoConfigurationImportSelector.class)
public @interface EnableAutoConfiguration {
}
```

`AutoConfigurationImportSelector` 实现 `DeferredImportSelector`。它不是看到注解后立刻创建 Bean，而是在配置类解析的延迟导入阶段返回一组配置类名。

## 为什么要延迟导入

普通用户配置、组件扫描结果和常规 `@Import` 需要先被解析，自动配置才更有机会看到用户已经声明的 BeanDefinition。这个顺序正是“约定默认值可以回退”的基础。

延迟并不意味着所有用户 Bean 已经实例化。条件通常读取元数据、类路径、Environment 和已经处理到的 BeanDefinition；尤其 `@ConditionalOnMissingBean` 判断的是当前可见定义，而非遍历一批已创建实例。

```text
解析用户配置
  → 收集 DeferredImportSelector
  → AutoConfigurationImportSelector 选出自动配置类
  → 解析自动配置类及其中的 @Bean 方法
  → 注册最终 BeanDefinition
  → refresh 后段创建非懒单例
```

## getAutoConfigurationEntry 完整算法

Boot 2.7.18 的主干可压缩为：

```text
getAutoConfigurationEntry(annotationMetadata)
  ├─ isEnabled(metadata)
  │    └─ spring.boot.enableautoconfiguration，默认 true
  ├─ getAttributes(metadata)
  ├─ getCandidateConfigurations(metadata, attributes)
  ├─ removeDuplicates(configurations)
  ├─ getExclusions(metadata, attributes)
  │    ├─ @EnableAutoConfiguration.exclude
  │    ├─ @EnableAutoConfiguration.excludeName
  │    └─ spring.autoconfigure.exclude
  ├─ checkExcludedClasses(configurations, exclusions)
  ├─ configurations.removeAll(exclusions)
  ├─ getConfigurationClassFilter().filter(configurations)
  ├─ fireAutoConfigurationImportEvents(configurations, exclusions)
  └─ new AutoConfigurationEntry(configurations, exclusions)
```

注意 `spring.boot.enableautoconfiguration=false` 是整套自动配置总开关；`spring.autoconfigure.exclude` 只是排除指定候选。二者的粒度不同。

## 动画：一个候选怎样成为 Bean

<SpringBootAutoConfigurationAnimation />

动画采用一个缩小的教学候选集。真实 Boot 应用会加载更多候选，但每个候选仍经历同类阶段：资源发现、显式排除、元数据快速过滤、分组排序、配置类条件、Bean 方法条件和对象创建。

## 2.7.18 从两个位置加载候选

`getCandidateConfigurations(...)` 在 2.7.18 中明确合并两种来源：

```text
旧来源（兼容）
META-INF/spring.factories
key = org.springframework.boot.autoconfigure.EnableAutoConfiguration

新来源（推荐）
META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

源码顺序是：

1. `SpringFactoriesLoader.loadFactoryNames(EnableAutoConfiguration.class, classLoader)` 读取旧注册。
2. `ImportCandidates.load(AutoConfiguration.class, classLoader)` 读取 `.imports`。
3. 两组结果加入同一列表，随后用 `LinkedHashSet` 语义去重。

Lab 使用新格式，文件内容只有自动配置类全限定名：

```text
io.github.javasourceatlas.spring.boot.autoconfigure.AtlasFeatureAutoConfiguration
```

`.imports` 每行一个候选类名，可使用 `#` 写注释。资源可以来自多个依赖 JAR；`ImportCandidates` 会通过 ClassLoader 枚举同名资源并合并，而不是只读应用自身文件。

## 为什么自动配置类不应依赖组件扫描

第三方 starter 的包通常不在应用启动类的扫描根包下。把自动配置类放进 `.imports` 有三个好处：

- 无需扩大用户的 `@ComponentScan` 范围。
- 候选集合是明确契约，可被排序、排除和预过滤。
- 配置类可以只在条件满足时解析，避免无关类路径触发加载问题。

自动配置包还不应成为普通组件扫描目标，否则同一个配置可能绕过候选过滤机制被直接发现，条件报告与排除行为都会变得难以解释。

## 三类排除来源

| 来源 | 例子 | 适用场景 |
| --- | --- | --- |
| 类型安全注解属性 | `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)` | 编译期可引用目标类 |
| 类名注解属性 | `excludeName = "...DataSourceAutoConfiguration"` | 目标类可能不在编译类路径 |
| 外部属性 | `spring.autoconfigure.exclude=...` | 不改代码按环境排除 |

`checkExcludedClasses` 会识别“类确实存在，但它根本不是候选自动配置”的错误排除并抛异常。若排除类本身不在类路径，则不会用加载失败覆盖原本的可选依赖语义。

排除发生在条件过滤之前。被显式排除的类不会靠后续条件重新回来。

## 第一层条件：候选元数据快速过滤

`ConfigurationClassFilter` 从 `spring.factories` 加载 `AutoConfigurationImportFilter`。Boot 2.7.18 内置：

```text
OnBeanCondition
OnClassCondition
OnWebApplicationCondition
```

过滤器接收候选类名数组和构建时生成的 `AutoConfigurationMetadata`，批量返回布尔结果。被判定不匹配的位置会置为 null，最后重新收集幸存类名。

这一层的目的主要是性能：在完整读取、解析每个配置类之前，先用元数据淘汰明显不可能生效的候选。例如类路径缺少 JDBC API 时，可以尽早移除相关候选。

它不是全部条件的唯一执行点。配置类真正被 Spring 解析时，`ConditionEvaluator` 仍会处理类级和方法级 `@Conditional`；自定义条件通常就在后一个阶段执行。

## AutoConfigurationGroup：合并与排序

选择器通过 `getImportGroup()` 返回 `AutoConfigurationGroup`。组处理分两段：

```text
process(metadata, selector)
  → 为每个导入来源调用 getAutoConfigurationEntry
  → 保存候选与它来自哪个配置类

selectImports()
  → 合并所有 exclusions
  → 合并并去重所有 configurations
  → 再次移除全局排除项
  → AutoConfigurationSorter.getInPriorityOrder(...)
  → 返回 DeferredImportSelector.Group.Entry
```

排序主要读取：

- `@AutoConfigureBefore`
- `@AutoConfigureAfter`
- `@AutoConfigureOrder`
- 构建生成的自动配置元数据

`before/after` 约束的是配置处理顺序，不保证 Bean 初始化顺序，也不应被当成业务调用依赖。若 Bean A 运行时需要 Bean B，应使用正常依赖注入表达关系。

`@ConditionalOnMissingBean` 只能看到目前已处理的定义，所以自动配置之间如果存在默认 Bean 竞争，应通过明确的自动配置顺序让检查发生在正确位置。

## 第二层条件：配置类与 Bean 方法

候选类幸存并不等于其中全部定义都会注册。以 Lab 为例：

```text
AtlasFeatureAutoConfiguration（候选已导入）
  ├─ 类级 @ConditionalOnAtlasFeatureEnabled
  │    ├─ false → 整个配置类跳过
  │    └─ true  → 继续解析
  ├─ @EnableConfigurationProperties
  │    └─ 注册 AtlasFeatureProperties 定义
  └─ atlasGreetingService(...)
       ├─ @ConditionalOnMissingBean 匹配 → 注册默认服务定义
       └─ 已有用户服务 → 方法定义跳过
```

条件评估可能在配置解析阶段与 BeanDefinition 注册阶段分批发生。调试时按报告的 source 区分：

- 类级 source 通常是自动配置类全限定名。
- 方法级 source 通常形如 `配置类全限定名#beanMethod`。

## ConditionEvaluationReport 在导入阶段得到什么

`ConditionEvaluationReportAutoConfigurationImportListener` 是 `AutoConfigurationImportListener`。`fireAutoConfigurationImportEvents` 会把幸存候选和 exclusions 交给它，报告由此记录：

- 哪些类属于本轮评估候选；
- 哪些类被显式排除；
- 后续每个 `SpringBootCondition` 的 match/no-match 与说明。

无条件候选会进入 `unconditionalClasses`；一旦某 source 有具体条件结果，它会从无条件集合移出并进入 outcomes 映射。报告是解释工具，不负责反向控制装配。

## Boot 2.7 与 Boot 3.x 的候选资源差异

| 对比项 | Boot 2.7.18 | Boot 3.x |
| --- | --- | --- |
| 自动配置推荐资源 | `AutoConfiguration.imports` | `AutoConfiguration.imports` |
| 旧 `spring.factories` 自动配置 key | 仍兼容读取 | 不再作为自动配置候选来源 |
| `@AutoConfiguration` | 2.7 新增，可用 | 继续使用 |
| Java 基线 | Java 8 | Java 17 |
| Spring 基线 | Framework 5.3 | Framework 6.x |
| EE 命名空间 | 以 `javax.*` 为主 | Jakarta API 使用 `jakarta.*` |

Boot 3.x 并没有删除整个 `spring.factories` 文件机制；某些其他扩展类型仍可能使用它。准确说法是：**自动配置类注册**不再读取 `EnableAutoConfiguration` 旧 key，候选以 `.imports` 为准。

迁移自定义 starter 时，应先在 Boot 2.7 同时具备可工作的 `.imports`，再升级 Boot 3。这样可以把“资源未迁移”和“Java/Spring/Jakarta API 不兼容”拆成两个独立问题。

## 推荐断点

| 类与方法 | 变量 | 观察目标 |
| --- | --- | --- |
| `ConfigurationClassParser.parse` | configurationClasses | 普通配置与延迟选择器的解析边界 |
| `AutoConfigurationImportSelector.getAutoConfigurationEntry` | configurations、exclusions | 候选数每一步如何变化 |
| `getCandidateConfigurations` | 两种资源结果 | 2.7 的兼容加载来源 |
| `ConfigurationClassFilter.filter` | candidates、match、skipped | 快速过滤器淘汰了谁 |
| `AutoConfigurationGroup.process` | autoConfigurationEntries、entries | 多个导入来源如何合并 |
| `AutoConfigurationGroup.selectImports` | processedConfigurations | 排除合并与最终排序 |
| `ConditionEvaluator.shouldSkip` | metadata、phase | 幸存配置类为何仍可能被跳过 |

## 阅读时不要做的事

- 不要用候选列表长度判断最终 Bean 数量；一个配置类可注册零个或多个 Bean。
- 不要把 `.imports` 顺序当作最终处理顺序；排序器会应用 before/after/order 关系。
- 不要在自动配置类上使用宽泛组件扫描寻找业务实现；应通过条件与明确导入保持边界。
- 不要用 `Class.forName` 自己重写 `@ConditionalOnClass`；条件的元数据读取设计就是为了避免在缺少可选类时过早加载失败。

候选配置确定后，下一页继续拆解 [条件评估、属性绑定与用户 Bean 回退](./condition-binding.md)。
