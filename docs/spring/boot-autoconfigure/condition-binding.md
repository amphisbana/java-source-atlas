# 条件评估、属性绑定与用户 Bean 回退

候选自动配置进入 Spring 配置类解析后，还要回答三个问题：

1. 当前运行环境是否满足配置类条件？
2. 外部属性如何成为有类型的 Java 对象？
3. 用户已经提供实现时，默认 Bean 为什么不会抢占？

本页用 Lab 的 `AtlasFeatureAutoConfiguration` 串起这三段源码。

## 实验配置的决策树

```text
AtlasFeatureAutoConfiguration 被候选机制导入
  ↓
类级 @ConditionalOnAtlasFeatureEnabled
  ├─ atlas.feature.enabled 缺失或 false
  │    └─ 整个配置类跳过，属性 Bean 和服务 Bean 都不存在
  └─ true
       ├─ @EnableConfigurationProperties
       │    └─ 注册 AtlasFeatureProperties BeanDefinition
       └─ @Bean atlasGreetingService(properties)
            └─ @ConditionalOnMissingBean
                 ├─ 已有 AtlasGreetingService → 跳过默认定义
                 └─ 没有 → 注册默认定义
                              ↓
                 创建 AtlasFeatureProperties
                   → Binder 绑定 message / repeat / enabled
                              ↓
                 调用 @Bean 方法创建默认服务
```

条件控制的是**定义是否进入容器**，属性绑定控制的是**目标对象得到什么值**，IOC 创建控制的是**对象何时实例化**。三者互相衔接，但不是一个动作。

## SpringBootCondition：匹配结果必须带原因

Boot 的大多数条件继承 `SpringBootCondition`。其 final `matches(...)` 模板方法执行：

```text
SpringBootCondition.matches(context, metadata)
  → getClassOrMethodName(metadata)
  → getMatchOutcome(context, metadata)
  → logOutcome(source, outcome)
  → ConditionEvaluationReport.recordConditionEvaluation(...)
  → return outcome.isMatch()
```

子类实现的重点不是只返回 boolean，而是返回 `ConditionOutcome`：

```text
match = true / false
message = 为什么匹配或不匹配
```

Lab 的自定义条件直接使用 Binder 读取 Environment：

```java
boolean enabled = Binder.get(context.getEnvironment())
        .bind("atlas.feature.enabled", Boolean.class)
        .orElse(false);
```

条件评估时不能假设 `AtlasFeatureProperties` Bean 已经创建。读取开关应依赖 `ConditionContext.getEnvironment()`，而不是在 Condition 中调用业务 Bean。

## 配置类条件与 Bean 方法条件

| 放置位置 | 不匹配时的影响 | 适合表达 |
| --- | --- | --- |
| 自动配置类 | 整个配置类不参与后续解析 | 模块总开关、关键类路径、应用类型 |
| 嵌套配置类 | 只跳过一组相关定义 | 同一模块的不同运行模式 |
| `@Bean` 方法 | 只跳过这个 BeanDefinition | 用户实现回退、某项可选组件 |

应尽量把廉价、范围大的条件放在外层，把具体 Bean 竞争条件放在方法上。条件层级越清晰，报告越容易读懂。

## 常用条件的真实判断对象

| 注解 | 主要读取 | 常见用途 | 容易误解的边界 |
| --- | --- | --- | --- |
| `@ConditionalOnClass` | ClassLoader 与类元数据 | 可选依赖存在才启用 | 放在 `@Bean` 返回类型上时可能过早解析缺失类型，常用隔离配置类规避 |
| `@ConditionalOnMissingClass` | 类名字符串 | 兼容没有某库的降级配置 | 传的是名称，不要求编译期类型可用 |
| `@ConditionalOnBean` | 已处理的 BeanDefinition 与类型信息 | 依赖某能力存在 | 只能可靠看到当前已经处理到的定义 |
| `@ConditionalOnMissingBean` | 已处理的 BeanDefinition 与类型信息 | 默认实现回退 | 不是允许同名定义覆盖，也不是实例创建后再删除 |
| `@ConditionalOnProperty` | Environment | 功能开关、模式值 | 默认 havingValue 不是单纯“必须等于 true” |
| `@ConditionalOnResource` | ResourceLoader | 某资源存在才启用 | 打包方式会影响资源是否可见 |
| `@ConditionalOnWebApplication` | ApplicationContext / Web 类型信息 | Servlet、Reactive 分支 | 不等同于只看一个 web 依赖类 |
| `@ConditionalOnExpression` | SpEL 与 Environment | 少量复合表达式 | 复杂表达式可读性差，引用 Bean 还可能触发过早初始化 |

## ConditionalOnProperty 的默认语义

当 `havingValue` 保持默认空字符串时，属性存在且值不等于字符串 `false` 才匹配：

| 属性状态 | 默认 `havingValue=""` | `havingValue="true"` |
| --- | --- | --- |
| 缺失 | 不匹配，除非 `matchIfMissing=true` | 同左 |
| `true` | 匹配 | 匹配 |
| `false` | 不匹配 | 不匹配 |
| `foo` | 匹配 | 不匹配 |

如果业务语义就是布尔开关，显式写 `havingValue="true"` 往往更直观。Lab 使用自定义条件，是为了直接观察 `SpringBootCondition`、Binder 与报告记录，不代表实际项目必须重写标准条件。

## OnBeanCondition 为什么与顺序有关

`@ConditionalOnMissingBean` 可按类型、名称、注解、泛型容器等维度检查 Bean。放在 `@Bean` 方法上且未显式指定类型时，默认使用方法返回类型。

它只能匹配当前 BeanFactory 已处理到的定义。因此自动配置作者需要保证：

- 用户配置先于自动配置延迟导入阶段处理；
- 互相依赖的自动配置用 `@AutoConfigureAfter` / `Before` 表达顺序；
- 条件放在自动配置上，而不是随意放到普通业务配置里依赖偶然解析顺序。

用户 Bean 回退的真实过程是：

```text
用户 AtlasGreetingService BeanDefinition 已存在
  → 解析 atlasGreetingService @Bean 方法
  → OnBeanCondition 搜索到同类型定义
  → ConditionOutcome = noMatch
  → 默认 @Bean 方法不注册
```

这里没有发生“两个定义同名，后者覆盖前者”。用户 Bean 名称甚至可以是 `userAtlasGreetingService`，类型条件仍会让默认方法回退。

## @EnableConfigurationProperties 做了什么

`@EnableConfigurationProperties(AtlasFeatureProperties.class)` 通过 registrar 注册：

- `AtlasFeatureProperties` 对应的 BeanDefinition；
- `ConfigurationPropertiesBindingPostProcessor` 等绑定基础设施；
- 用于 Binder、已绑定属性记录和方法校验排除等内部组件。

实验运行日志中的属性 Bean 名称类似：

```text
atlas.feature-io.github.javasourceatlas.spring.boot.autoconfigure.AtlasFeatureProperties
```

业务代码应按类型注入，不应依赖这个生成名称。

## JavaBean 属性绑定调用链

Lab 使用带无参构造语义、getter/setter 的 JavaBean 绑定方式。Boot 2.7.18 的关键调用链是：

```text
IOC 创建 AtlasFeatureProperties
  → ConfigurationPropertiesBindingPostProcessor
      .postProcessBeforeInitialization(bean, beanName)
  → ConfigurationPropertiesBean.get(...)
  → ConfigurationPropertiesBinder.bind(propertiesBean)
  → propertiesBean.asBindTarget()
  → 构造 BindHandler 链
      ├─ ConfigurationPropertiesBindHandler
      ├─ IgnoreErrorsBindHandler（按注解设置，可选）
      ├─ NoUnboundElementsBindHandler（按注解设置，可选）
      ├─ ValidationBindHandler（存在校验器时）
      └─ 用户提供的 Advisor
  → Binder.bind(prefix, target, bindHandler)
  → setter 写入 enabled / message / repeat
  → 后续初始化回调
```

`ConfigurationPropertiesBindingPostProcessor` 是 `PriorityOrdered` 的 BeanPostProcessor，绑定发生在初始化前回调阶段。对于 Boot 2.7 的构造器绑定值对象，创建路径不同，不应强行套用 JavaBean setter 时序；本专题的精确断点针对 Lab 的 JavaBean 方式。

## Environment 到字段的转换

```text
PropertySource 原始键值
  → ConfigurationPropertySources 适配视图
  → ConfigurationPropertyName 规范名
  → Binder 按 prefix 递归定位目标属性
  → PlaceholdersResolver 处理占位符
  → ConversionService / PropertyEditor 转换类型
  → BindHandler 处理错误、未知字段和校验
  → 写入目标对象
```

Lab 中以下常见形式都围绕同一个规范属性：

| 外部来源 | 写法示例 | 目标字段 |
| --- | --- | --- |
| properties / 命令行 | `atlas.feature.repeat=2` | `repeat` |
| YAML 层级 | `atlas: { feature: { repeat: 2 } }` | `repeat` |
| 环境变量 | `ATLAS_FEATURE_REPEAT=2` | `repeat` |

配置属性前缀应采用小写 kebab 风格。宽松绑定是输入兼容能力，不应反过来让项目到处混用难以搜索的命名。

## 默认值、缺失值与类型错误

`AtlasFeatureProperties` 在字段声明处提供：

```text
enabled = false
message = "你好"
repeat = 1
```

- 属性缺失时保留默认值。
- 属性存在时经过转换后覆盖对应值。
- 例如 `repeat=abc` 无法转换为 int，默认情况下会导致绑定失败并阻止上下文正常启动，而不是悄悄退回 1。
- 如需范围校验，可在属性类上使用 `@Validated` 并添加 Bean Validation 约束；类型转换成功不等于业务范围合理。

Lab 的 `AtlasGreetingService` 还把小于 1 的 repeat 收敛到 1，目的是让服务方法自身保持安全。但真实配置类更适合通过校验让错误配置在启动期明确失败。

## 属性绑定与 @Value 的取舍

| 需求 | `@ConfigurationProperties` | `@Value` |
| --- | --- | --- |
| 一组有层级的配置 | 适合 | 容易散落 |
| 类型转换 | Binder 统一处理 | 支持，但逐字段表达 |
| IDE 元数据提示 | 配合 configuration processor | 较弱 |
| 批量校验 | 自然 | 需要单独组织 |
| 单个简单表达式或 SpEL | 偏重 | 适合 |

自动配置通常应把一组公开配置契约建模为 `@ConfigurationProperties`，让条件和 Bean 创建共享明确前缀与类型。

## ConditionEvaluationReport 的数据结构

报告作为名为 `autoConfigurationReport` 的单例保存在 BeanFactory 中，核心信息包括：

```text
SortedMap<String, ConditionAndOutcomes> outcomes
List<String> exclusions
Set<String> unconditionalClasses
ConditionEvaluationReport parent
```

`outcomes` 以 source 分组。每个 `ConditionAndOutcomes` 包含多个 `ConditionAndOutcome`，只有全部 outcome 都匹配时 `isFullMatch()` 才为 true。

读取 Lab 报告时重点看：

```text
类级 source
io.github...AtlasFeatureAutoConfiguration
  AtlasFeatureEnabledCondition → match / no-match

方法级 source
io.github...AtlasFeatureAutoConfiguration#atlasGreetingService
  OnBeanCondition → match / no-match
```

若父配置类不匹配，报告可能给嵌套 source 加入“祖先未匹配”的结果，避免把子项看成独立失败。

## 如何让报告可见

常见方式有三种：

1. 启动参数加入 `--debug`，让条件评估日志监听器输出报告。
2. 使用 Actuator 的 `conditions` 端点查看结构化结果，生产环境必须正确保护管理端点。
3. 像 Lab 一样从 `ConditionEvaluationReport.get(beanFactory)` 读取，并只打印目标 package，避免内置配置淹没观察点。

报告说明“条件如何决定”，不保证外部系统真的可用。例如 classpath 有 JDBC 驱动且属性齐全，只说明数据源配置条件可能匹配，不等于数据库网络与账号已经验证。

## 三个场景的断点快照

### 场景一：没有 enabled 属性

| 位置 | 值 |
| --- | --- |
| `AtlasFeatureEnabledCondition.getMatchOutcome` | bind 结果 empty，`orElse(false)` |
| `ConditionOutcome` | no-match，message 包含 `enabled=false` |
| 配置类定义 | 跳过 |
| 属性 / 服务 Bean | 都不存在 |

### 场景二：enabled=true

| 位置 | 值 |
| --- | --- |
| 类级 condition | match |
| 属性 Bean 创建前 | 字段仍是声明默认值 |
| `postProcessBeforeInitialization` 后 | message、repeat 已从 Environment 写入 |
| 默认服务工厂方法 | 收到绑定完成的 properties |

### 场景三：用户 Bean 已存在

| 位置 | 值 |
| --- | --- |
| 类级 condition | match |
| `OnBeanCondition` 搜索结果 | 找到用户 `AtlasGreetingService` 定义 |
| 方法级 condition | no-match |
| `atlasGreetingService` 默认定义 | 不注册 |
| 按类型取得服务 | 返回用户对象 |

## 设计自动配置时的检查清单

- 用独立 package 放置自动配置，避免被应用组件扫描意外发现。
- 使用 `.imports` 注册候选；若还支持 Boot 2.6 及更早版本，再明确制定兼容资源策略。
- 类路径条件使用 `name` 或隔离配置类，避免可选类型在条件判断前已经被 JVM 解析。
- `@ConditionalOnMissingBean` 尽量放在返回类型明确的 `@Bean` 方法上。
- 用 `@ConfigurationProperties` 表达成组配置，前缀保持规范命名。
- 为默认关闭、显式开启、用户覆盖和错误配置分别写测试。
- 条件结果提供可读 message；自定义条件优先继承 `SpringBootCondition`。
- 不在 Condition 中创建业务 Bean，也不做网络请求或其他有副作用的探测。

下一页使用 [断点实验](./debug-lab.md) 验证以上三种决策路径。
