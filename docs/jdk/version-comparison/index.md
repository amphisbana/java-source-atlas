---
title: JDK 8 / 17 / 21 源码版本对比
description: 从固定 OpenJDK Tag 对比 HashMap、ConcurrentHashMap、ThreadLocal、CompletableFuture、ClassLoader 与 ServiceLoader 的关键变化。
pageClass: jdk-version-comparison-page
---

<script setup lang="ts">
import JdkVersionComparison from '../../.vitepress/theme/JdkVersionComparison.vue'
</script>

# JDK 版本对比实验室

同一个类在不同 JDK 中通常不是“全部推倒重写”。更常见的情况是：核心不变量保持稳定，方法边界、内部原子操作、运行时能力和安全检查逐步演进。版本阅读的目标不是背 diff，而是回答三个问题：**什么没有变、什么变了、这项变化会不会影响迁移与调试。**

本页使用三份不会随默认分支漂移的源码快照：

| 展示版本 | 仓库与固定 Tag | 取样含义 |
| --- | --- | --- |
| JDK 8u412 | `openjdk/jdk8u@jdk8u412-b08` | JDK 8 更新线快照，包含长期维护期修复，不等同于 2014 年 GA 源码 |
| JDK 17 | `openjdk/jdk@jdk-17+35` | JDK 17 GA 快照 |
| JDK 21 | `openjdk/jdk@jdk-21+35` | JDK 21 GA 快照 |

<JdkVersionComparison />

> 工作台展示的是经过筛选、可以沿真实入口阅读的关键差异，不是源码全文 diff。每个源码按钮都同时固定仓库、Tag、文件路径和起始行。

## 四类差异怎么判断

**新增**与**移除**取决于当前比较方向。如果从 JDK 21 反向比较 JDK 8，原本在新版本“新增”的入口会显示为“移除”，这样标签始终描述左侧到右侧发生了什么。

**签名变化**不只指 public 方法参数改变，也包括公开类型被标记为 `final`、新增可调用入口，以及 JDK 内部但会影响断点与深度调试的方法边界。业务代码是否受影响，需要继续看该入口的可见性。

**实现变化**表示两侧入口都存在，但关键路径不同。例如 `HashMap.readObject` 仍然负责反序列化，三版对 `loadFactor` 的读取与归一化策略却不相同。

## 五个专题分别看什么

### HashMap：先确认骨架稳定

数组、链表、红黑树以及 `putVal → resize` 主干没有因为版本升级而消失。应把注意力放在 `getNode` 的职责移动、集合视图数组导出、反序列化边界和 JDK 21 的容量工厂。这样可以避免把一次局部签名变化误判成数据结构重写。

### ConcurrentHashMap：区分协议与工具

无锁读取、空桶 CAS、桶头加锁和协作扩容是稳定协议；`Unsafe` 方法名、视图的条件删除、`TreeBin.waiter` 的发布方式属于实现工具与竞态窗口的改进。迁移结论应建立在协议层，而性能诊断必须继续下钻到具体版本实现。

### ThreadLocal：虚拟线程没有改变泄漏原则

JDK 21 为虚拟线程增加 carrier 访问与写入追踪，但 `ThreadLocalMap.Entry` 仍是弱 key、强 value，清理仍然是惰性的。线程池任务中的 `finally remove()` 不会因为 `refersTo` 或虚拟线程出现而失效。

### CompletableFuture：补齐状态机周围的能力

JDK 8 的 `result + Completion stack` 是后续版本的基础。JDK 17 已拥有执行器定制、延迟执行和超时能力，JDK 21 再通过 `Future.State`、`resultNow`、`exceptionNow` 提供非阻塞观察。阅读时要区分“完成协议”与“围绕协议新增的 API”。

### ClassLoader / ServiceLoader：类路径与模块层并存

模块系统没有删除双亲委派与 `META-INF/services`。JDK 17/21 增加平台类加载器、模块感知查找、`ModuleLayer` 服务目录与 `Provider` 流，同时保留类路径兼容路径。模块化插件出错时，需要同时核对类加载器、模块可读性、`uses/provides` 和服务实例化时点。

但两条兼容路径不是每个入口都会无条件串行执行：

- `ServiceLoader.load(ModuleLayer, Class)` 只在给定 Layer 及其父 Layer 的命名模块中定位 provider，不会查找 unnamed module，也不会继续解析 classpath 上的 `META-INF/services`。
- `ServiceLoader.load(Class, ClassLoader)` 才以指定 ClassLoader 为边界，先发现该加载器可见的模块 provider，再保留 unnamed module / `META-INF/services` 兼容查找。
- 显式命名模块可以通过 public static `provider()` 工厂提供服务；classpath 和自动模块 provider 仍应准备 public 无参构造，不能把模块工厂规则直接搬回类路径。

因此排查 SPI 时第一步不是问“配置文件为什么没被读取”，而是先确认调用了哪个 `load` 重载、服务类型所在模块是否声明 `uses`，以及 provider 位于命名模块还是 unnamed module。

## 完成标准

读完一个专题后，不看页面也应能完成以下判断：

1. 说出三个版本共同保持的核心不变量。
2. 从类名和方法名定位到固定 Tag 的真实源码，而不是默认分支。
3. 判断一项变化属于公共 API、内部签名还是实现细节。
4. 反向比较时正确解释新增与移除，不把时间方向看反。
5. 给出至少一个迁移测试或调试断点，而不是只复述代码差异。
