# ClassLoader / ServiceLoader 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/runtime/ClassLoaderServiceLoaderDebugLab.java
```

SPI 配置：

```text
labs/jdk-labs/src/main/resources/META-INF/services/
  io.github.javasourceatlas.jdk.runtime.ClassLoaderServiceLoaderDebugLab$GreetingService
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.runtime.ClassLoaderServiceLoaderDebugLab
```

案例只使用 Java 8 公开 API，可在 JDK 8、17、21 编译运行。内部断点名称以当前 SDK 附带源码为准；JDK 9+ ClassLoader 和 ServiceLoader 已重构，不能强找 JDK 8 的每个局部字段。

## 场景一：类加载器父链

运行 `observeLoaderHierarchy()`：

- `Object.class.getClassLoader()` 输出 null，表示 bootstrap。
- 实验类输出其真实应用定义加载器。
- 从实验类加载器沿 getParent 遍历，直到 null。

JDK 8 通常可看到 AppClassLoader -> ExtClassLoader -> null；JDK 17 通常是 AppClassLoader -> PlatformClassLoader -> null。类名属于内部实现细节，自动测试只断言核心类 loader 为 null、应用实验类 loader 非 null。

推荐观察：

| 位置 | 变量 | 目的 |
| --- | --- | --- |
| `Class.getClassLoader()` | 当前 Class | 区分定义加载器与发起加载器 |
| `ClassLoader.getSystemClassLoader()` | scl | 观察系统加载器的延迟初始化 |
| `Thread.getContextClassLoader()` | contextClassLoader | 不要误认为一定等于当前 Class 的 loader |

## 场景二：双亲委派与 findClass

运行 `observeParentDelegation()`。RecordingFindClassLoader 的 parent 是实验类应用加载器：

1. child 请求已由 parent 定义的 DebugLab。
2. 默认 loadClass 在父层命中，child.findClass 调用次数保持 0。
3. child 再请求一个不存在名称。
4. 父链报告 ClassNotFoundException 后，child.findClass 才记录一次并继续失败。

JDK 8 推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `ClassLoader.loadClass(String,boolean)` | this、name、resolve、parent |
| `getClassLoadingLock` | parallelLockMap、lock |
| `findLoadedClass` | 返回 c |
| `parent.loadClass` 调用后 | c 或 ClassNotFoundException |
| `RecordingFindClassLoader.findClass` | findAttempts |

给断点增加类名条件 `io.github.javasourceatlas...`，否则 IDE、JUnit、Maven 本身的加载会产生大量停顿。

## 场景三：initiating loader 与 defining loader

运行 `observeInitiatingAndDefiningLoaders()`。实验让隔离加载器定义 `LoaderInitiatingFixture`，该类继承 bootstrap 定义的 `ArrayList`。JVM 定义并链接子类时，需要通过隔离加载器解析父类：

```text
IsolatedTypeClassLoader define LoaderInitiatingFixture
  -> JVM 请求 IsolatedTypeClassLoader 加载 java.util.ArrayList
  -> parent 链最终返回 bootstrap 定义的 ArrayList
  -> child.findLoadedClass("java.util.ArrayList") 返回 ArrayList.class
```

这里 child 是 `ArrayList` 的 initiating loader，但 `ArrayList.class.getClassLoader()` 仍为 null。推荐同时观察：

| 位置 | 变量 | 结论 |
| --- | --- | --- |
| `IsolatedTypeClassLoader.loadClass` | name | JVM 解析父类时会请求 ArrayList |
| `findLoadedClass0` 返回后 | 当前 child、返回 Class | child 已被记录为 initiating loader |
| `ArrayList.class.getClassLoader()` | 返回 null | defining loader 仍为 bootstrap |

不要只凭“谁调用了 Java 方法 `loadClass`”命名 initiating loader；以 JVM 记录和 `findLoadedClass` 契约为准。

## 场景四：两个定义加载器的同名类型

运行 `observeDefiningLoaderIdentity()`。实验先读取 `LoaderIdentityFixture.class` 字节，再让 loaderA 与 loaderB 各 define 一次：

```text
loaderA.loadClass(name)       -> typeA
loaderA.loadClass(name) 再次  -> typeA，同一个 Class
loaderB.loadClass(name)       -> typeB，名称相同但 Class 不同
```

推荐断点：

- `IsolatedTypeClassLoader.loadClass` 的目标名称分支；
- `findLoadedClass` 第一次和第二次返回值；
- `defineClass` 调用前后的 loader；
- JVM 抛类型转换异常的位置（可自行尝试反射创建实例）。

实验加载器只对一个无依赖探针使用 child-first，其他类型仍委派 parent。这不是通用插件加载器模板；生产实现还需处理 package、ProtectionDomain、资源关闭、并行注册和共享 API 包边界。

## 场景五：加载不等于初始化

运行 `observeInitializationBoundary()`：

1. 清空一个实验专用系统属性。
2. `Class.forName(name, false, loader)` 返回 Class，属性仍为 null。
3. `Class.forName(name, true, loader)` 请求初始化。
4. LoaderInitializationFixture 的 static 块写入属性。

建议断点：

| 位置 | 观察内容 |
| --- | --- |
| `Class.forName(String,boolean,ClassLoader)` | initialize 参数 |
| LoaderInitializationFixture static 块 | 首次触发栈 |
| 第二个 forName 返回后 | 两次 Class 是否同一对象 |

一个 Class 初始化失败后会进入错误状态，后续主动使用可能得到 NoClassDefFoundError；不要在同一个 JVM 中反复把失败初始化当成可重试构造流程。

## 场景六：SPI 惰性与缓存

运行 `observeServiceDiscoveryAndCache()`。配置故意包含 Alpha 的重复行和一条 Beta：

```text
Alpha
Alpha # duplicate
Beta
```

观察结果：

1. `ServiceLoader.load` 前后 provider 构造计数不变。
2. `iterator()` 只创建 `knownProviders`，构造计数仍不变。
3. 第一次 `hasNext()` 枚举并解析名称，但构造计数仍不变。
4. 第一次 `next()` 才加载并创建 Alpha。
5. 继续迭代只得到一次 Beta，重复 Alpha 被去重。
6. 同一 ServiceLoader 的新 iterator 先通过 `knownProviders.next().getValue()` 返回缓存中的同一 Alpha 实例。
7. `reload()` 后下一次迭代创建新的 Alpha 实例。

JDK 8 推荐断点：

| 方法 | 变量 |
| --- | --- |
| `ServiceLoader.load(Class,ClassLoader)` | service、loader |
| `ServiceLoader.reload` | providers、lookupIterator |
| `ServiceLoader.iterator` | `knownProviders = providers.entrySet().iterator()` |
| `LazyIterator.hasNextService` | configs、pending、nextName |
| `ServiceLoader.parse` | url、line number、provider name |
| `LazyIterator.nextService` | cn、c、service.isAssignableFrom(c) |
| Alpha/Beta 构造器 | INSTANCES |

JDK 17/21 不再有完全相同的 LazyIterator 结构，应在当前 ServiceLoader 源码中寻找 provider cache、lookup iterator 和 ProviderImpl 等对应路径。公开结果相同，不要求内部字段同名。

## 场景七：TCCL 决定默认发现范围

运行 `observeContextClassLoaderBoundary()`：

1. 保存当前线程原 TCCL。
2. 临时设置一个只隐藏教学 SPI 描述文件的 loader。
3. 调用无显式 loader 的 `ServiceLoader.load(GreetingService.class)`，结果为空。
4. finally 恢复原 TCCL。
5. 显式传入实验类定义加载器，再次发现 Alpha 与 Beta。

隐藏 loader 仍把普通 Class 请求委派给 parent，所以结果差异只来自配置资源可见性，不是服务接口加载失败。推荐断点：

- `Thread.getContextClassLoader`；
- `ServiceLoader.load(Class)` 取得 loader 的位置；
- `ServiceResourceHidingClassLoader.getResources`；
- configured loader 的 `getResources(fullName)`。

调试器暂停时不要手动改 worker TCCL 后忘记恢复。真实线程池里这会污染后续任务，并可能保留旧部署加载器。

## 自动化测试覆盖

测试类：

```text
labs/jdk-labs/src/test/java/
  io/github/javasourceatlas/jdk/runtime/ClassLoaderServiceLoaderBehaviorTest.java
```

运行单专题：

```bash
mvn -pl labs/jdk-labs \
  -Dtest=ClassLoaderServiceLoaderBehaviorTest test
```

测试只断言这些跨版本公开行为：

- bootstrap ClassLoader 的 null 表示；
- 默认 parent-first 与 findClass 兜底；
- initiating loader 与 defining loader 可以不同；
- 同一 loader 复用 Class、不同定义 loader 产生不同类型；
- initialize=false/true 的初始化区别；
- SPI 配置注释与重复名称处理；
- provider 惰性创建、实例缓存与 reload；
- 默认 ServiceLoader 使用当前 TCCL。

测试不绑定：

- AppClassLoader/PlatformClassLoader 的内部类名；
- 多个 JAR 配置资源的全局顺序；
- provider Class 在 hasNext 还是 next 的具体内部加载时点；
- JDK 8 LazyIterator 在 JDK 17 中仍存在；
- 旧 loader 何时发生 GC 和 Class unloading。

## 使用本地 OpenJDK 源码

IDE 中把调试 SDK 的 src.zip 关联为源码即可进入 JDK 类。命令行也可准备源码树：

```bash
git clone https://github.com/openjdk/jdk8u.git /path/to/jdk8u
git clone https://github.com/openjdk/jdk17u.git /path/to/jdk17u
```

源码路径对照：

```text
JDK 8 : jdk/src/share/classes/java/lang/ClassLoader.java
        jdk/src/share/classes/java/util/ServiceLoader.java
JDK 17: src/java.base/share/classes/java/lang/ClassLoader.java
        src/java.base/share/classes/java/util/ServiceLoader.java
```

运行实验使用哪个 JDK，就以哪个 JDK 的真实源码、启动参数和模块图为准；不要用 JDK 8 局部字段解释 JDK 17 的内部对象布局。
