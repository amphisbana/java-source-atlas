<div align="center">
  <img src="docs/public/logo-light.svg" width="72" height="72" alt="Java Source Atlas logo">
  <h1>Java Source Atlas</h1>
  <p><strong>一份面向 Java 后端工程师的源码阅读地图</strong></p>
  <p>从公开 API 进入真实实现，用调用链、动画、断点和可运行案例理解 JDK、Spring Framework 与 Spring Boot。</p>

  <p>
    <a href="https://github.com/amphisbana/java-source-atlas/actions/workflows/ci.yml"><img src="https://github.com/amphisbana/java-source-atlas/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
    <a href="https://amphisbana.github.io/java-source-atlas/"><img src="https://img.shields.io/badge/在线文档-GitHub%20Pages-0f766e" alt="Online docs"></a>
    <a href="https://github.com/amphisbana/java-source-atlas/releases"><img src="https://img.shields.io/github/v/release/amphisbana/java-source-atlas?label=IDEA%20Plugin" alt="IDEA plugin release"></a>
    <a href="https://github.com/amphisbana/java-source-atlas/blob/main/LICENSE"><img src="https://img.shields.io/github/license/amphisbana/java-source-atlas" alt="License"></a>
  </p>

  <p>
    <a href="https://amphisbana.github.io/java-source-atlas/learning-path/">开始学习</a>
    &nbsp;&middot;&nbsp;
    <a href="https://amphisbana.github.io/java-source-atlas/source-explorer/">打开源码索引</a>
    &nbsp;&middot;&nbsp;
    <a href="https://github.com/amphisbana/java-source-atlas/releases">下载 IDEA 插件</a>
  </p>
</div>

> 源码学习不是背诵每一行实现，而是把一个现象还原成一条可以验证的路径：问题是什么、公开 API 进入哪里、关键状态如何变化、哪个断点能证明它、不同版本为什么会改变。

## 项目是什么

Java Source Atlas 是一个开源的 Java 源码学习与调试工作台。项目把文档、源码索引、交互动画、可运行 Lab、版本对比和 IDEA 插件放在同一套数据与学习路径之上，帮助你从“会调用”走到“看懂实现、能够定位问题、知道升级边界”。

当前仓库包含 **29 个源码专题**、**152 篇文档**、**37 个交互演示**、**3 个可运行实验模块**，覆盖 24 个 JDK 核心机制专题，以及 Spring IOC、AOP、Transaction、Boot 自动装配和 MVC 五条主线。

## 项目亮点

| 能力 | 你可以得到什么 |
| --- | --- |
| 源码阅读地图 | 每个专题都从一个真实问题出发，给出版本基线、源码入口、调用链和阅读顺序。 |
| 交互式动画 | 通过步骤、自动播放、重置和状态变化，观察扩容、入队、调度、代理、容器刷新等过程。 |
| 可运行调试 Lab | 用最小案例触发目标分支，再进入 JDK 或 Spring 源码验证内部过程，而不是只看伪代码。 |
| 推荐断点 | 为关键方法、触发条件和观察变量提供断点建议，降低第一次读大型框架源码的成本。 |
| JDK 版本对比 | 固定 OpenJDK 8、17、21 源码快照，比较实现变化、兼容边界和升级影响。 |
| Spring 链路深挖 | 把 Boot 启动、IOC 生命周期、AOP 代理、事务资源、MVC 请求和异常处理放回同一条调用栈。 |
| IDEA 插件 | 在编辑器中按当前类和方法匹配专题，直接打开教程、定位源码、添加断点和 Debug Lab。 |
| 学习进度 | 文档站在浏览器本地记录主线阅读、实验运行和过关问题，学习路线可以持续推进。 |

## 为什么要读源码

### 1. API 只能告诉你怎么用

API 文档通常说明输入、输出和约束，但很多工程问题发生在实现细节里：HashMap 为什么需要扩容，线程池什么时候拒绝任务，事务为什么会出现 `rollback-only`，一次 HTTP 请求又经过了哪些拦截器。

### 2. 源码能解释设计取舍

读源码不是为了记住私有字段，而是为了看清楚约束和取舍：吞吐量与一致性、延迟与公平性、内存占用与快照隔离、扩展性与默认行为。理解这些取舍后，遇到新问题时才能迁移经验，而不是依赖搜索结果。

### 3. 源码是定位线上问题的地图

当日志只告诉你“超时、阻塞、代理没有生效或配置没有加载”时，源码入口、状态机和断点可以把问题缩小到一个具体分支。可运行案例则让这个分支能够在本地稳定复现。

### 4. 版本升级需要知道边界

JDK、Spring Framework 和 Spring Boot 的公开 API 可能保持不变，内部实现和默认策略却会变化。固定版本源码、版本对比和兼容提示可以帮助你区分：哪些是公开契约，哪些只是当前实现，哪些行为升级后必须重新验证。

## 一条可验证的学习闭环

```mermaid
flowchart LR
    A[提出问题] --> B[阅读公开 API]
    B --> C[定位源码入口]
    C --> D[沿调用链阅读]
    D --> E[运行最小 Lab]
    E --> F[命中推荐断点]
    F --> G[观察状态与变量]
    G --> H[用测试验证行为]
    H --> I[比较版本差异]
    I --> A
```

每个专题都尽量遵循这条闭环：先形成问题，再用源码和实验互相校验，最后把结论放回版本和公开契约中。

## 内容覆盖

### JDK 核心机制

| 方向 | 专题 |
| --- | --- |
| 集合与数据结构 | [ArrayList](docs/jdk/collections/arraylist/index.md)、[HashMap](docs/jdk/collections/hashmap/index.md)、[LinkedHashMap](docs/jdk/collections/linkedhashmap/index.md)、[TreeMap](docs/jdk/collections/treemap/index.md) |
| 内存模型与锁 | [JMM、volatile、final 与 VarHandle](docs/jdk/concurrent/jmm/index.md)、[synchronized 与 ObjectMonitor](docs/jdk/concurrent/synchronized-monitor/index.md)、[AQS 与 ReentrantLock](docs/jdk/concurrent/locks/index.md)、[Atomic 与 Striped64](docs/jdk/concurrent/atomic/index.md) |
| 线程与并发容器 | [Thread 与 LockSupport](docs/jdk/concurrent/thread-locksupport/index.md)、[ThreadLocal](docs/jdk/concurrent/threadlocal/index.md)、[ConcurrentHashMap](docs/jdk/concurrent/concurrenthashmap/index.md)、[ConcurrentLinkedQueue](docs/jdk/concurrent/concurrentlinkedqueue/index.md)、[CopyOnWriteArrayList](docs/jdk/concurrent/copyonwritearraylist/index.md)、[BlockingQueue](docs/jdk/concurrent/blockingqueue/index.md) |
| 任务与线程池 | [CompletableFuture](docs/jdk/concurrent/completablefuture/index.md)、[FutureTask](docs/jdk/concurrent/futuretask/index.md)、[ThreadPoolExecutor](docs/jdk/concurrent/threadpoolexecutor/index.md)、[ScheduledThreadPoolExecutor](docs/jdk/concurrent/scheduledthreadpoolexecutor/index.md)、[ForkJoinPool](docs/jdk/concurrent/forkjoinpool/index.md) |
| 函数式与 NIO | [Stream 与 Spliterator](docs/jdk/functional/stream/index.md)、[ByteBuffer 与 Selector](docs/jdk/io/nio/index.md) |
| 运行时机制 | [Reference 与 WeakHashMap](docs/jdk/runtime/reference-weakhashmap/index.md)、[ClassLoader 与 ServiceLoader](docs/jdk/runtime/classloader/index.md)、[Reflection 与 JDK Dynamic Proxy](docs/jdk/runtime/reflection-proxy/index.md) |

### Spring Framework 与 Spring Boot

| 专题 | 主要问题 |
| --- | --- |
| [Spring 学习路线](docs/spring/index.md) | 把 IOC、AOP、事务、Boot 和 MVC 放进同一条启动与请求链路。 |
| [Spring IOC](docs/spring/ioc/index.md) | `refresh()`、BeanDefinition、依赖注入、生命周期、三级缓存和早期代理。 |
| [Spring AOP](docs/spring/aop/index.md) | Advisor 匹配、JDK/CGLIB 代理、拦截器链和自调用边界。 |
| [Spring Transaction](docs/spring/transaction/index.md) | 事务元数据、传播行为、资源绑定、提交回滚和 `rollback-only`。 |
| [Spring Boot 自动装配](docs/spring/boot-autoconfigure/index.md) | 启动流程、候选加载、条件过滤、配置绑定和用户 Bean 回退。 |
| [Spring MVC](docs/spring/mvc/index.md) | 路由匹配、参数解析、Controller 调用、返回值和异常处理。 |
| [Spring 核心链路深挖](docs/spring/deep-dive/index.md) | 跨专题观察 Bean 身份、代理边界、事务资源和请求异常清理。 |

## 学习入口

| 入口 | 适合什么时候使用 |
| --- | --- |
| [完整学习路线](docs/learning-path/index.md) | 第一次系统学习，从集合和并发基础逐步进入 Spring。 |
| [源码索引工作台](docs/source-explorer/index.md) | 已经遇到一个类、方法或变量，想快速定位专题和源码入口。 |
| [JDK 8 / 17 / 21 版本对比](docs/jdk/version-comparison/index.md) | 正在升级 JDK，想确认同一机制在不同版本中的实现变化。 |
| [JDK 专题首页](docs/index.md) | 按分类浏览全部 JDK 与 Spring 专题。 |
| [如何阅读一个专题](docs/guide/reading.md) | 不确定应该先看文档、源码、动画还是 Lab。 |

## 技术栈

| 层次 | 技术与版本 |
| --- | --- |
| 文档站 | VitePress 1.6、Vue 3、TypeScript、原生 CSS，支持本地搜索和响应式阅读。 |
| JDK 实验 | Java 8 release 目标，使用 JUnit 5 验证公开行为和边界条件。 |
| Spring Framework Lab | Spring Framework 5.3.39，统一承载 IOC、AOP、Transaction 和 MVC 调试代码。 |
| Spring Boot Lab | Spring Boot 2.7.18，覆盖 `SpringApplication`、自动配置、条件和属性绑定。 |
| IDEA 插件 | IntelliJ Platform 2024.2 至 2026.2，Java 21、Gradle、Gson、JCEF 回退和 PSI 导航。 |
| 源码索引 | JSON 作为唯一专题数据源，构建时同时提供给文档站和 IDEA 插件。 |
| 自动化质量 | GitHub Actions、Maven 测试、索引校验、文档构建和内部链接检查。 |

## 快速开始

### 1. 获取仓库

```bash
git clone https://github.com/amphisbana/java-source-atlas.git
cd java-source-atlas
```

### 2. 启动文档站

环境要求：Node.js 18 或更高版本。

```bash
npm ci
npm run docs:dev
```

浏览器打开终端输出的本地地址。构建和完整校验使用：

```bash
npm run verify:docs
```

### 3. 运行 Java Lab

环境要求：Java 8 或更高版本、Maven 3.8+。

```bash
# 运行全部 Java 测试
mvn test

# 运行一个 JDK 实验，默认入口可以通过 exec.mainClass 覆盖
mvn -pl labs/jdk-labs exec:java

# 运行统一 Spring Lab 的 IOC 专题
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=ioc
```

Spring Framework Lab 的 AOP、Transaction、MVC 和 Spring Boot Lab 的启动方式见各模块 README：

- [Spring Framework 统一调试 Lab](labs/spring-framework-lab/README.md)
- [Spring Boot 自动装配调试 Lab](labs/spring-boot-lab/README.md)
- [JDK Labs](labs/jdk-labs/README.md)

### 4. 安装或开发 IDEA 插件

插件需要 Java 21 构建，支持 IntelliJ IDEA 2024.2 至 2026.2。

```bash
cd idea-plugin
./gradlew test buildPlugin
./gradlew runIde
```

可安装 ZIP 位于 `idea-plugin/build/distributions/`。正式版本也可以从 [GitHub Releases](https://github.com/amphisbana/java-source-atlas/releases) 下载，然后在 IDEA 的 `Settings | Plugins | Install Plugin from Disk...` 中安装。

插件默认连接公开文档站；开发仓库内容时，可在 IDEA 设置中临时使用 `http://127.0.0.1:4180`。要使用 Lab 打开和 Debug，需要在 IDEA 中导入完整仓库及对应 Maven 模块。

## 仓库结构

```text
java-source-atlas/
├── docs/                     # VitePress 文档、专题文章和交互动画
├── source-index/             # JDK / Spring 专题的唯一 JSON 索引
├── labs/
│   ├── jdk-labs/             # JDK 可运行案例与断点实验
│   ├── spring-framework-lab/ # IOC / AOP / Transaction / MVC 统一 Lab
│   └── spring-boot-lab/      # Spring Boot 自动装配 Lab
├── idea-plugin/              # IntelliJ IDEA Source Atlas 插件
├── scripts/                  # 索引校验和文档链接检查
├── pom.xml                   # Maven 多模块入口
└── package.json              # 文档站与质量检查脚本
```

## 一个专题应该包含什么

项目欢迎可以运行、可以定位、可以验证的源码解析。新增专题建议至少包含：

1. 明确的 JDK 或框架版本基线。
2. 一个能够触发目标分支的最小案例。
3. 从公开 API 到核心实现的调用链。
4. 推荐断点、触发条件和预期观察变量。
5. 关键源码的逐步解释，以及必要的动画或状态图。
6. 自动化测试，用来验证对外可观察行为。
7. 版本差异、适用范围和不应依赖的内部细节。
8. 第三方源码地址与许可证说明。

详细规范见 [贡献指南](CONTRIBUTING.md)。

## 版本基线与许可证

- JDK 专题以 OpenJDK 8u 为主要讲解基线，并额外提供 OpenJDK 17、21 的固定快照对比。
- Spring Framework 专题以 5.3.39 为主要基线。
- Spring Boot 自动装配以 2.7.18 为可执行基线，并单独标记 Spring 6 / Boot 3 的重要变化。
- 项目原创文档和示例代码采用 [Apache License 2.0](LICENSE) 许可。
- OpenJDK、Spring Framework 和其他第三方源码仍遵循各自的原始许可证，项目不重新许可第三方源码。详见 [源码引用与许可证说明](docs/reference/source-license.md)。

## 参与贡献

你可以通过新增专题、补充版本对比、完善 Lab、修正文档链接或改进交互动画参与项目。提交前建议运行：

```bash
mvn test
npm run verify:docs
```

欢迎提交 Issue 和 Pull Request，一起把 Java 源码阅读从“看过”变成“验证过”。
