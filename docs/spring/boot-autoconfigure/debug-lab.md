# Spring Boot 自动装配断点实验

## 实验目标

模块：`labs/spring-boot-lab`

版本：Spring Boot 2.7.18，Java 8

主类：

```text
io.github.javasourceatlas.spring.boot.autoconfigure.SpringBootAutoConfigurationDebugLab
```

实验没有 Web 服务器、数据库或外部服务，专门观察：

```text
SpringApplication.run
  → Environment
  → @EnableAutoConfiguration
  → AutoConfiguration.imports
  → 自定义条件
  → @ConfigurationProperties
  → @ConditionalOnMissingBean
  → ConditionEvaluationReport
```

## 文件与职责

| 文件 | 职责 |
| --- | --- |
| `SpringBootAutoConfigurationDebugLab` | 非 Web 启动入口，打印 Environment、绑定结果、服务输出和目标条件报告 |
| `AtlasFeatureAutoConfiguration` | 自定义自动配置，组合总开关、属性注册和默认 Bean 回退 |
| `ConditionalOnAtlasFeatureEnabled` | 自定义条件注解 |
| `AtlasFeatureEnabledCondition` | 用 Binder 读取开关并生成 ConditionOutcome |
| `AtlasFeatureProperties` | 绑定 `atlas.feature` 配置组 |
| `AtlasGreetingService` | 可被自动配置或用户配置提供的服务类型 |
| `AutoConfiguration.imports` | 让 EnableAutoConfiguration 发现自定义候选 |
| `AtlasFeatureAutoConfigurationTest` | 固定五条关键行为路径 |

## 运行自动测试

模块保持可独立执行，不依赖根聚合 POM：

```bash
mvn -f labs/spring-boot-lab/pom.xml -Dtest=AtlasFeatureAutoConfigurationTest test
```

测试包含五个场景：

| 测试方法 | 输入 | 关键断言 |
| --- | --- | --- |
| `shouldStayDisabledWhenPropertyIsMissing` | 无 enabled 属性 | 属性 Bean 和服务 Bean 均不存在，类级报告不匹配 |
| `shouldStayDisabledWhenPropertyIsFalse` | enabled=false | Bean 均不存在，条件报告明确记录 false |
| `shouldBindPropertiesAndCreateDefaultService` | enabled=true，并配置 message/repeat | 宽松绑定成功，默认服务按绑定结果运行 |
| `shouldBackOffWhenUserProvidesService` | enabled=true，用户预先提供同类型 Bean | 容器只有用户服务，默认 beanName 不存在 |
| `shouldDiscoverConfigurationFromImportsResource` | 仅启用 `@EnableAutoConfiguration` | 不直接导入目标类也能从 `.imports` 发现并装配 |

前四个测试使用 `AutoConfigurations.of(...)` 聚焦单个候选；第五个测试特意走 `@EnableAutoConfiguration`，证明资源发现链不是只写在文档里的假设。

## 运行主程序

```bash
mvn -pl labs/spring-boot-lab compile exec:java
```

无参数时主程序补充：

```text
--atlas.feature.enabled=true
--atlas.feature.message=欢迎阅读
--atlas.feature.repeat=2
```

预期关键输出类似：

```text
environment.atlas.feature.message=欢迎阅读
bound.repeat=2
service=欢迎阅读，源码读者 | 欢迎阅读，源码读者
condition.source=io.github...AtlasFeatureAutoConfiguration
condition.fullMatch=true
condition.detail=AtlasFeatureEnabledCondition:...
```

日志时间、内置自动配置数量和生成 Bean 名称可能因运行环境不同而变化，实验只依赖上述公开行为。

## 自定义参数运行

```bash
mvn -pl labs/spring-boot-lab compile exec:java \
  -Dexec.args="--atlas.feature.enabled=true --atlas.feature.message=调试 --atlas.feature.repeat=3"
```

若显式传 `--atlas.feature.enabled=false`，主程序随后按类型取属性 Bean 时会失败。这是刻意保留的可观察失败路径：配置类被条件跳过后，相关 Bean 确实不存在。日常观察关闭路径更适合直接运行对应自动测试。

## 第一轮断点：SpringApplication.run

按顺序设置断点：

| 断点 | 观察变量 | 预期 |
| --- | --- | --- |
| `SpringApplication.run(String...)` | args、webApplicationType | 三个默认参数，类型为 NONE |
| `prepareEnvironment` 返回前 | propertySources | commandLineArgs 中包含 atlas 属性 |
| `createApplicationContext` 返回后 | context 类型、active | 普通注解上下文，尚未 active |
| `prepareContext` 的 `load` 后 | beanDefinitionNames | 主配置源已注册，自动配置尚未全部展开 |
| `refreshContext` 入口 | environment 是否同一引用 | 准备好的环境已设置给 context |
| `refreshContext` 返回 | context.active、definitionCount | 自动配置解析和非懒单例创建完成 |

不要在 Evaluate Expression 中调用 `context.getBean(...)` 探测尚未创建的对象；这会真实触发创建，改变后续观察时序。

## 第二轮断点：候选发现

在 Boot 源码设置：

```text
AutoConfigurationImportSelector.getAutoConfigurationEntry
AutoConfigurationImportSelector.getCandidateConfigurations
AutoConfigurationImportSelector$ConfigurationClassFilter.filter
AutoConfigurationImportSelector$AutoConfigurationGroup.process
AutoConfigurationImportSelector$AutoConfigurationGroup.selectImports
```

建议记录四次快照：

| 时点 | 记录内容 |
| --- | --- |
| 初始 candidates | 是否包含 `AtlasFeatureAutoConfiguration` |
| removeDuplicates 后 | 候选是否唯一 |
| exclusions 后 | 实验配置是否被排除 |
| filter 与 group 后 | 实验配置是否留在最终 imports |

第五个自动测试最适合这组断点。前四个测试通过 `AutoConfigurations.of` 直接提供目标自动配置，主要用于隔离条件行为，不适合证明全量候选资源发现。

## 第三轮断点：自定义条件

在 `AtlasFeatureEnabledCondition.getMatchOutcome` 观察：

| 场景 | Binder 结果 | outcome |
| --- | --- | --- |
| 属性缺失 | empty → `orElse(false)` | no-match |
| enabled=false | false | no-match |
| enabled=true | true | match |

继续进入 `SpringBootCondition.matches`，确认同一个 outcome 先被记录进 `ConditionEvaluationReport`，再以 boolean 返回给 Spring 条件解析器。

推荐变量：

```text
context.environment
metadata（类还是方法）
enabled
outcome.match
outcome.message
classOrMethodName
```

## 第四轮断点：属性绑定

启用成功场景，并设置：

```text
ConfigurationPropertiesBindingPostProcessor.postProcessBeforeInitialization
ConfigurationPropertiesBinder.bind
Binder.bind
AtlasFeatureProperties.setMessage
AtlasFeatureProperties.setRepeat
AtlasFeatureAutoConfiguration.atlasGreetingService
```

观察时序：

```text
AtlasFeatureProperties 实例化
  → 字段为声明默认值
  → BPP beforeInitialization
  → Binder 定位 atlas.feature 前缀
  → setter 写入 message / repeat / enabled
  → 属性 Bean 初始化完成
  → 作为参数注入 atlasGreetingService 工厂方法
```

在工厂方法入口，`properties` 必须已经是绑定完成的对象：

```text
enabled = true
message = 深入源码（测试场景）
repeat = 2
```

## 第五轮断点：用户 Bean 回退

运行 `shouldBackOffWhenUserProvidesService`，在以下位置观察：

```text
OnBeanCondition.getMatchOutcome
DefaultListableBeanFactory.getBeanNamesForType
ConditionEvaluationReport.recordConditionEvaluation
```

需要确认的不是两个 Bean 谁覆盖谁，而是：

1. `userAtlasGreetingService` 定义已先进入 BeanFactory。
2. 条件按 `AtlasGreetingService` 类型搜索到用户定义。
3. 自动配置方法的 outcome 为 no-match。
4. `atlasGreetingService` 默认 BeanDefinition 从未注册。

即使用户 Bean 名与默认 Bean 完全不同，按类型回退仍然成立。

## 读取报告的最小代码

主程序只输出实验 package：

```java
ConditionEvaluationReport report =
        ConditionEvaluationReport.get(context.getBeanFactory());

report.getConditionAndOutcomesBySource().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(AtlasFeatureAutoConfiguration.class.getName()))
        .forEach(entry -> {
            // 查看 entry.getValue().isFullMatch() 和每个 ConditionOutcome。
        });
```

完整报告可能有大量 Boot 内置项。首次调试先按 source 过滤；确认目标行为后，再观察它依赖的前置配置。

## 在 IDE 中附加正确源码

必须确保依赖和源码均为 2.7.18。建议确认：

```text
spring-boot-2.7.18.jar
spring-boot-autoconfigure-2.7.18.jar
对应的 2.7.18 sources
```

若 IDE 附加了 2.6 或 3.x 源码，断点可能偏移，`getCandidateConfigurations` 的内容也会不同。尤其 Boot 3 不再拥有 2.7 的 `spring.factories` 自动配置兼容加载分支。

## 可继续扩展的实验

理解基础路径后，可在本地分支逐项增加：

1. 给默认 Bean 加 `@ConditionalOnClass`，移除可选依赖后观察快速过滤。
2. 用 `spring.autoconfigure.exclude` 排除实验配置，观察报告 exclusions。
3. 把 repeat 改为非法字符串，观察 `ConfigurationPropertiesBindException` 与失败分析。
4. 添加 `@Validated` 与范围约束，区分类型转换失败和业务校验失败。
5. 再建一个自动配置，用 `@AutoConfigureAfter` 固定顺序，观察 Group 排序结果。

每次只改变一个条件并增加自动测试，才能知道结果来自哪个决策，而不是被全量启动日志带偏。

## 实验边界

- Lab 验证的是 Boot 2.7.18 JVM 运行时路径，不覆盖 Boot 3 AOT 或原生镜像。
- `ApplicationContextRunner` 用于隔离自动配置测试，不代表完整应用所有 starter 组合。
- 条件报告说明元数据决策，不替代数据库、消息中间件等外部连接验证。
- 私有缓存与局部变量只用于断点，不应通过反射写入生产代码。
