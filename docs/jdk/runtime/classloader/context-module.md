# TCCL、资源查找与模块化差异

ClassLoader 的父委派天然适合“应用依赖平台”：子加载器能请求父层 API。SPI 的方向相反：平台或公共框架需要发现应用提供的实现。线程上下文类加载器（Thread Context ClassLoader，TCCL）就是 JDK 1.2 以来用于表达调用环境可见范围的桥梁。

## TCCL 如何反转可见方向

假设容器结构为：

```text
CommonLoader
  定义框架和 Codec 接口
       ↑ parent
WebAppLoader-A
  定义应用 A 的 JsonCodec 与 META-INF/services
```

CommonLoader 按正常父委派看不到 WebAppLoader-A。容器执行应用 A 请求前，把 worker 的 TCCL 设置为 WebAppLoader-A；框架内部调用 `ServiceLoader.load(Codec.class)` 时，就能从 TCCL 枚举应用资源并加载 provider。

类型仍然成立，因为 JsonCodec 的加载过程会把共享 Codec 接口委派给 CommonLoader。若应用 A 自己又定义一份 Codec，最终 isAssignableFrom 会失败，TCCL 不会把两个 Class 自动合并。

## getContextClassLoader 的继承边界

新 Thread 通常从创建它的父线程继承 contextClassLoader。线程池 worker 往往只创建一次，因此后续提交任务不会自动把提交线程当时的 TCCL 复制过去。容器和框架若需要按任务传播，必须显式捕获并恢复：

```java
ClassLoader callerLoader = Thread.currentThread().getContextClassLoader();
executor.execute(() -> {
    Thread worker = Thread.currentThread();
    ClassLoader previous = worker.getContextClassLoader();
    try {
        worker.setContextClassLoader(callerLoader);
        invokePlugin();
    } finally {
        worker.setContextClassLoader(previous);
    }
});
```

finally 不只是避免下一任务读错配置。worker.thread -> contextClassLoader -> 插件 Class/Class 静态字段/资源，可能形成旧部署加载器的强引用链，让热部署后 JAR、实例和静态缓存长期无法回收。

安全管理器存在时，读取或设置 TCCL 可能触发 RuntimePermission 检查。SecurityManager 在 JDK 17 已标记为废弃待移除，但 JDK 8 部署仍可能启用；不能为了兼容而吞掉权限异常后悄悄使用错误加载器。

## Class.getResource 与 ClassLoader.getResource

这两组 API 对名称解释不同：

```java
// 相对 Class 所在包：com/example/config.properties
Codec.class.getResource("config.properties");

// 从加载器资源根开始：config.properties
Codec.class.getResource("/config.properties");

// ClassLoader 名称始终不以 / 开头
loader.getResource("config.properties");
```

`Class.getResource` 会先把相对名转换为包路径，再委托定义该 Class 的加载器；对 bootstrap 类型还走 JVM 的资源路径。`ClassLoader.getResource` 直接处理绝对资源名，前导 `/` 通常导致找不到。

ServiceLoader 固定使用：

```text
META-INF/services/<服务接口二进制名>
```

它不是相对于 provider 包的资源。排查时应直接调用与 ServiceLoader 相同 configured loader 的 `getResources(fullName)`，枚举并打印全部 URL，而不是只用接口 Class 的 getResource 看首个结果。

## getResource 和 getResources 的顺序边界

JDK 8 ClassLoader 默认 `getResource` 先问 parent/bootstrap，未命中再 `findResource`。`getResources` 组合父层枚举与本地 `findResources`。自定义加载器和容器可以重写；JDK 9 模块资源规则也增加了封装条件。

可稳定依赖的是：

- getResource 返回一个 URL 或 null；
- getResources 返回当次加载器实现认为可见的枚举；
- 同一个 JAR 内配置行按文本次序解析；
- ServiceLoader 会忽略已经返回过的同名 provider。

不应依赖：

- 多个 JAR 中同名资源的跨环境绝对顺序；
- AppClassLoader 一定能转为 URLClassLoader 并暴露全部 classpath URL；
- IDE、测试运行器、fat JAR、容器使用相同资源 URL 协议；
- `java.class.path` 完整代表所有模块层和自定义 loader 可见内容。

## 配置文件合并是构建问题

普通 JAR 可以各带一份同名 `META-INF/services/...`，ClassLoader.getResources 会枚举多份。打成一个 uber/fat JAR 时，ZIP 中不能保留两个完全相同路径；若构建工具简单“后者覆盖前者”，只剩一个 provider 配置。

需要启用 Maven Shade ServicesResourceTransformer、Gradle service file merge 等对应能力，或在产物阶段直接检查最终 JAR：

```bash
jar tf app.jar | grep 'META-INF/services'
unzip -p app.jar META-INF/services/com.example.Codec
```

源码目录里文件正确但发布物缺失，属于真实高频故障。诊断必须看运行中的最终产物，而不是只看 IDE resources。

## 模块系统怎样改变加载器布局

JDK 9 的 JPMS 移除了扩展机制，并把运行时镜像组织为模块。典型内置加载器职责变为：

```text
Bootstrap loader
  定义 java.base 及若干核心模块中的类
        ↑
Platform ClassLoader
  定义其余平台模块中的类
        ↑
System/Application ClassLoader
  定义 classpath 与应用模块中的类
```

模块与加载器不是一对一：一个加载器可以定义多个模块；自定义 ModuleLayer 也可以选择“每模块一个加载器”或“多个模块共用一个加载器”。所以 JDK 9+ 诊断类型时除了 `clazz.getClassLoader()`，还应查看 `clazz.getModule()`。

`Object.class.getClassLoader()` 仍返回 null。Platform ClassLoader 可通过公开 API `ClassLoader.getPlatformClassLoader()` 取得；该方法在 JDK 8 不存在，跨版本实验不能直接编译调用。

## readability 不等于 class loader 可见性

JPMS 同时存在两类约束：

1. ClassLoader 是否能按名称找到并定义 Class。
2. 模块是否读取目标模块，以及目标包是否 export/open。

加载器找到字节并不自动让模块访问合法。`exports` 控制普通编译/运行时访问，`opens` 主要控制深反射。ClassNotFoundException、NoClassDefFoundError、IllegalAccessError、InaccessibleObjectException 分别可能指向不同阶段，不能统一归因于“classpath 少 JAR”。

服务绑定用 `uses` 和 `provides` 建模，消费者通常不需要读取 provider 模块或访问实现包。ServiceLoader 根据模块图建立 provider 目录，再通过受控机制创建实现。

## META-INF/services 与 module-info 的边界

部署位置决定发现方式：

| provider 位置 | 声明方式 | 创建规则 |
| --- | --- | --- |
| JDK 8 classpath | `META-INF/services` | public 具体类 + public 无参构造器 |
| JDK 9+ classpath/未命名模块 | `META-INF/services` | 仍沿用 classpath 规则 |
| 自动模块 | `META-INF/services` 可参与服务发现 | provider 类构造规则 |
| 显式命名模块 | module-info `provides ... with ...` | public 无参构造器或模块 provider 方法 |

显式命名模块里的 `META-INF/services` 不能替代 module-info 的 provides 声明。反过来，把同一 JAR 放回 classpath 时 module-info 不负责 classpath 发现，若需要双模式发布，通常还要保留正确的 META-INF/services。

模块消费者应声明 `uses service.Type`。这既描述运行时绑定需要，也让 jlink 等工具知道服务关系。不要用 requires provider.module 把消费者重新耦合到具体实现。

## ServiceLoader.load(ModuleLayer, service)

JDK 9 新增按 ModuleLayer 查找：

```java
ServiceLoader<Codec> codecs = ServiceLoader.load(pluginLayer, Codec.class);
```

它从给定层及其可达父层定位 provider，适合模块化插件。ModuleLayer 本身可能使用多个 ClassLoader，因此不能再用“一个插件层等于一个 URLClassLoader”简化。

动态卸载仍没有单个 Class 的 unload API。要让插件层可回收，需要该层、其加载器、ServiceLoader/provider、线程和其他引用整体不可达。boot layer 与系统加载器通常贯穿进程生命周期。

## JDK 17 与 21 的额外观察点

### 应用加载器不再保证 URLClassLoader

JDK 8 常见代码：

```java
URLClassLoader app = (URLClassLoader) ClassLoader.getSystemClassLoader();
```

在 JDK 17/21 不可靠。读取 classpath 使用受支持的启动参数和配置；插件需要 URL 来源时创建并管理自己的 URLClassLoader，或使用模块层。

### 强封装影响反射，不改写类型身份

JDK 17 对 JDK 内部包实施强封装。`--add-opens` 只放开特定反射访问，不会让两个加载器定义的同名 Class 变成同一个类型，也不会让 ServiceLoader 自动发现原本不可见的配置。

### hidden class 是另一条定义路径

JDK 15+ 的 `MethodHandles.Lookup.defineHiddenClass` 面向框架生成的不可发现实现。hidden class 没有可供 ClassLoader 按普通二进制名再次查找的稳定身份，不应把它写进 META-INF/services。普通 SPI provider 仍应是可发现、可实例化的正常类。

### 虚拟线程没有改变 SPI 选择规则

JDK 21 的虚拟线程仍有 contextClassLoader 视图，ServiceLoader.load(service) 仍读取当前 Thread 的 TCCL。不要把大量虚拟线程当成动态插件注册表；加载器/provider 缓存通常应由应用作用域管理，并在任务中显式保持正确上下文。

## loadInstalled 的版本语义

JDK 8 `loadInstalled(service)` 从系统加载器向上找到最顶层非 bootstrap 父加载器，通常对应 Extension ClassLoader，用于查找已安装扩展 provider。JDK 9 移除扩展机制后，该入口使用 Platform ClassLoader 语义。

它不会搜索普通应用 classpath 的所有 provider。应用扩展通常应使用 load(service) 或显式 loader；模块化应用优先使用模块层与 uses/provides。

## 一套可执行诊断顺序

遇到“ServiceLoader 找不到实现”时，按证据逐层缩小：

1. 打印 service Class 的名称、定义加载器和 JDK 9+ module。
2. 打印当前线程 TCCL，以及创建 ServiceLoader 时实际传入的 loader。
3. 用该 loader 枚举完整 `META-INF/services/<service-name>` URL。
4. 读取最终发布物中的配置内容，核对 provider 二进制名和重复/覆盖。
5. 对 provider 执行 `Class.forName(name, false, loader)`，区分找不到类与初始化失败。
6. 打印 provider Class 的定义加载器/module，并检查 `service.isAssignableFrom(providerClass)`。
7. 最后检查 public 无参构造器或模块 provider 方法及其异常。

不要一开始就修改 TCCL 或加 `--add-opens`。先确定失败发生在资源发现、类查找、类型身份、模块访问还是实例创建，修复才不会扩大可见范围。
