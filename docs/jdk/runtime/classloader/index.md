# ClassLoader / ServiceLoader：从字节到可插拔实现

ClassLoader 解决“某个二进制名由谁找到、定义并形成 JVM 类型”，ServiceLoader 解决“调用方只依赖服务接口时，怎样按配置发现实现”。两者常在 JDBC、日志门面、序列化、脚本引擎和应用服务器中一起出现：ServiceLoader 读取实现类名，随后仍要交给某个 ClassLoader 加载 provider。

本专题以 OpenJDK 8u 为源码基线，同时标注 JDK 17/21 的模块系统、平台类加载器和新版 ServiceLoader API 差异。文中的“应用加载器”“扩展加载器”是职责称呼；测试不会断言 `sun.misc.Launcher$AppClassLoader` 等内部实现名，因为 JDK 9 已重构这套实现。

[打开 JDK 8 / 17 / 21 版本对比 →](/jdk/version-comparison/)，可并排核对模块感知加载入口、命名类加载器、Provider Stream、ModuleLayer 与平台类加载器变化。

<TopicStudyPanel topic-id="openjdk8-classloader-serviceloader" />

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `ClassLoader` | [`java/lang/ClassLoader.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ClassLoader.java) | `loadClass`、`findLoadedClass`、`findClass`、`defineClass`、`resolveClass`、资源查找 |
| `Class` | [`java/lang/Class.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Class.java) | `forName`、`getClassLoader`、资源入口 |
| `URLClassLoader` | [`java/net/URLClassLoader.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/net/URLClassLoader.java) | `findClass`、`findResource`、`close` |
| `Launcher` | [`sun/misc/Launcher.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/misc/Launcher.java) | ExtClassLoader、AppClassLoader 和启动路径 |
| `ServiceLoader` | [`java/util/ServiceLoader.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/ServiceLoader.java) | `load`、`iterator`、LazyIterator、`reload`、配置解析 |
| `Thread` | [`java/lang/Thread.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Thread.java) | `contextClassLoader` 的继承、读取和设置 |

## 先分清五个动作

日常说“类加载”时常把多个阶段揉在一起。源码阅读前先拆开：

| 动作 | 核心效果 | 是否执行静态初始化 |
| --- | --- | --- |
| 查找字节来源 | 从父加载器、目录、JAR、网络或生成器取得 class 字节 | 否 |
| 定义 define | JVM 用二进制名、字节和定义加载器创建 Class | 否 |
| 验证与准备 | 校验结构，并为静态字段分配空间和放入默认值 | 否 |
| 解析 | 把常量池符号引用转换为直接引用，可按实现需要延后 | 否 |
| 初始化 | 执行类初始化方法，包括静态字段赋值和 static 块 | 是 |

`ClassLoader.loadClass(name)` 默认调用 `loadClass(name, false)`，即使成功返回 Class，也不能据此断言 static 块已经运行。`Class.forName(name)` 的单参数版本会请求初始化；三参数版本可以用 `initialize=false` 只取得 Class。

初始化之前 JVM 会保证类已完成必要的加载、验证和准备。初始化还要遵守父类优先、每个 Class 至多成功初始化一次以及失败状态传播等 JVM 规则，这些并不由业务 ClassLoader 自己重写。

## initiating loader 和 defining loader 不是一个概念

JVM 规范中的两个术语要按“谁被 JVM 记录”和“谁真正创建 Class”区分：

- defining loader：直接调用 `defineClass` 创建该 Class 的加载器；`Class.getClassLoader()` 公开的是它；
- initiating loader：JVM 在创建或解析类型时，记录为曾经发起加载该 Class 的加载器；委派链上的子加载器可能是 initiating loader，却不是 defining loader；
- 每个 defining loader 同时也是自己所定义类型的 initiating loader，反过来不成立。

仅在业务代码中直接调用一次 `child.loadClass(name)` 并拿到父层结果，不足以从调用栈外观断言 JVM 已建立 initiating-loader 记录。`findLoadedClass(name)` 的准确契约是查询“JVM 是否已经把当前 loader 记录为该类的 initiating loader”，而不是查询“当前 loader 是否执行过 defineClass”。

本专题实验让 child 定义的 `LoaderInitiatingFixture` 继承 `ArrayList`。JVM 链接子类时通过 child 解析父类，child 委派到 bootstrap：

```text
IsolatedLoader define LoaderInitiatingFixture
        │ JVM 通过 IsolatedLoader 解析父类 ArrayList
        v
Bootstrap 返回 java.util.ArrayList

ArrayList defining loader   = bootstrap（公开为 null）
ArrayList initiating loader = bootstrap + IsolatedLoader
IsolatedLoader.findLoadedClass("java.util.ArrayList") == ArrayList.class
```

JVM 类型身份不是只有二进制名，而是通常理解为 `<二进制名, 定义加载器>`。两个隔离加载器分别定义完全相同的 `demo.Widget` 字节，会得到两个不可直接互相赋值的 Class；这正是插件隔离有效、也正是很多 `ClassCastException: X cannot be cast to X` 的根源。

## JDK 8 的三层可见性

常见 JDK 8 启动结构可简化为：

```text
Bootstrap ClassLoader
  负责核心类；Java 层通常用 null 表示
          ↑ parent
Extension ClassLoader
  负责扩展目录
          ↑ parent
Application ClassLoader
  负责应用 classpath
          ↑ parent
业务自定义 ClassLoader
  负责插件、热部署或生成类
```

这不是“所有 JVM 永远只有三层”的规范承诺。应用可创建任意加载器图；父加载器关系也不是 Java 对象继承关系。JDK 9 以后扩展机制被移除，公开的 `ClassLoader.getPlatformClassLoader()` 表达平台层，bootstrap 仍通过 `Class.getClassLoader()==null` 暴露。

## ClassLoader 的最小阅读主线

JDK 8 `loadClass(name, resolve)` 的决策顺序是：

```text
取得该名称的 class loading lock
  -> findLoadedClass(name)
  -> 未加载：parent.loadClass(name)
       parent == null 时尝试 bootstrap
  -> 父链失败：findClass(name)
  -> resolve=true 时 resolveClass(result)
  -> 返回 Class
```

默认 `findClass` 只抛 ClassNotFoundException。真正从 URL、加密包、数据库或生成器得到字节的自定义加载器，应主要重写 `findClass`，让父类保留已加载检查、委派和并发锁协议。完整变量轨迹见 [loadClass：委派、定义与类型身份](./loading-delegation.md)。

## ServiceLoader 的最小阅读主线

JDK 8 classpath 模式约定：

```text
服务接口：com.example.Codec
配置资源：META-INF/services/com.example.Codec
资源内容：com.example.JsonCodec
```

`ServiceLoader.load(Codec.class)` 先取得当前线程上下文类加载器，但此时不扫描全部资源，也不创建 JsonCodec。迭代器推进时才逐步完成：

```text
读取 META-INF/services/... 配置
  -> 解析并去重 provider 二进制名
  -> Class.forName(name, false, configuredLoader)
  -> service.isAssignableFrom(providerClass)
  -> public 无参构造器创建实例
  -> 放入当前 ServiceLoader 的 provider 缓存
```

因此，接口可见并不意味着 provider 或配置文件可见；反过来，配置文件可见但接口由另一个隔离加载器定义，也会在可赋值检查处失败。完整协议见 [ServiceLoader：SPI 发现、惰性实例化与缓存](./serviceloader-spi.md)。

## 为什么默认使用线程上下文类加载器

父加载器能看见父层类，却通常看不见子层应用类。JDK 或容器中的通用框架若直接使用自己的定义加载器，就无法发现部署在应用层的 provider。线程上下文类加载器（TCCL）把一个“从调用环境向下看”的加载器显式放在线程上，让父层框架可以按调用方上下文发现实现。

这是一种受控的委派方向反转，不是绕过类型安全。ServiceLoader 最终仍检查 provider 是否可赋给传入的服务 Class。线程池中临时切换 TCCL 时也必须在 finally 恢复，否则后续任务会继承错误的应用可见范围，并可能让旧应用加载器无法卸载。

## 阅读路径

1. [loadClass：委派、定义与类型身份](./loading-delegation.md)：逐行理解 JDK 8 的加载锁、父链、findClass、defineClass 和 resolve。
2. [ServiceLoader：SPI 发现、惰性实例化与缓存](./serviceloader-spi.md)：跟踪配置枚举、LazyIterator、类型检查与 provider 缓存。
3. [TCCL、资源查找与模块化差异](./context-module.md)：处理容器可见性、资源路径和 JDK 17/21 模块边界。
4. [断点实验手册](./debug-lab.md)：在 JDK 8 与 JDK 17 上运行相同公开行为实验。

## JDK 8、17、21 的总览

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| 平台层 | Extension ClassLoader 与扩展目录机制 | Platform ClassLoader，扩展机制已移除 | 同 JDK 17 |
| 应用加载器 | 通常为 URLClassLoader 子类 | 内部 BuiltinClassLoader 体系，不应强转 URLClassLoader | 同 JDK 17 |
| bootstrap 表示 | 核心 Class 的 getClassLoader 返回 null | 公开观察仍返回 null | 公开观察仍返回 null |
| 服务配置 | classpath/JAR 中 `META-INF/services` | 同时支持模块 `uses/provides`、ModuleLayer、Provider stream | 模块协议延续 |
| provider 创建 | public 无参构造器 | 显式模块还可声明 public static `provider()` 工厂 | 协议延续 |
| 隐藏类 | 无 | Lookup.defineHiddenClass，不能按普通二进制名发现 | 协议延续 |

升级后仍然可靠的断言是公开契约：父委派默认顺序、类型身份与定义加载器关联、bootstrap 对 Java API 表现为 null、ServiceLoader 惰性且按实例缓存。加载器具体类名、classpath 是否能转 URL、多个配置资源的全局枚举顺序都不应写成业务前提。
