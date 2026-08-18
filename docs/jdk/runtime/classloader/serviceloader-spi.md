# ServiceLoader：SPI 发现、惰性实例化与缓存

ServiceLoader 是 JDK 提供的轻量 provider 发现器，不是依赖注入容器。它约定服务接口、配置位置与 provider 创建方式，让调用方不必硬编码实现类名；对象依赖、作用域、条件装配、生命周期回调仍需业务或框架自己处理。

## JDK 8 SPI 契约

假设服务接口为：

```java
package com.example;

public interface Codec {
    byte[] encode(Object value);
}
```

provider JAR 中创建 UTF-8 文本资源：

```text
META-INF/services/com.example.Codec
```

内容是 provider 的二进制名，每行一个：

```text
# 默认 JSON 实现
com.example.json.JsonCodec
com.example.binary.BinaryCodec # 行尾注释
```

JDK 8 classpath provider 必须是可赋给 Codec 的 public 具体类，并具有 public 无参构造器。ServiceLoader 不会猜测 Spring 构造参数、static factory、单例字段或带参构造器。

配置解析会去除井号及其后内容、trim 空白、忽略空行并检查名称字符。重复 provider 名称不会重复返回；但多个配置资源之间的枚举先后由 ClassLoader 和 classpath 决定，不应把跨 JAR 的全局顺序当成可移植优先级机制。

## load 为什么先保存 loader

三个常用入口含义不同：

| 入口 | 用于发现配置与 provider 的加载器 |
| --- | --- |
| `ServiceLoader.load(service)` | 当前线程的 contextClassLoader |
| `ServiceLoader.load(service, loader)` | 调用方显式传入的 loader；JDK 8 的 null 会回退为 system ClassLoader，只有 system loader 本身不可用时才落到 bootstrap/system resource 范围 |
| `ServiceLoader.loadInstalled(service)` | JDK 8 中沿系统加载器父链取得最上层扩展加载器 |

Service Class 本身由调用方传入，不会根据 provider 配置重新加载。Configured loader 负责枚举配置和加载实现，二者必须在最终类型检查处兼容。若确实只允许 bootstrap 定义的 provider，不应把 `load(service, null)` 当成强制隔离开关；应以模块边界、受控 ClassLoader 或直接的 Class.forName 方案表达真实可见范围。

默认入口选择 TCCL，是因为 java.base 或父层框架的定义加载器通常看不见应用/插件层实现。详见 [TCCL、资源查找与模块化差异](./context-module.md)。

## 创建 ServiceLoader 时发生了什么

JDK 8 构造器主要保存三项状态：

```text
service    -> 调用方传入的 Class<S>
loader     -> 用于发现配置和 provider 的 ClassLoader
providers  -> LinkedHashMap<String, S> 实例缓存
```

随后 `reload()` 清空 providers，并创建新的 LazyIterator。没有立即读取所有 JAR，也没有调用 provider 构造器。因此 ServiceLoader 对象创建成功，不能证明部署配置和 provider 都正确。

调用 `iterator()` 也只创建组合迭代器，并把 `knownProviders` 初始化为 `providers.entrySet().iterator()`；这一步仍不会推进 `lookupIterator`。第一次 `hasNext()` 在缓存为空时才调用 `lookupIterator.hasNext()`，枚举和解析配置并把下一个 provider 名称放进 `nextName`，但仍不构造实例。第一次 `next()` 才进入 `nextService()`，加载 Class、检查类型并调用无参构造器。

真正错误经常延迟到第一次 `hasNext` 或 `next`，生产代码若要启动时 fail-fast，就应在受控启动阶段主动完成迭代并验证至少/恰好需要的 provider 数量。

## iterator 如何先读缓存再继续发现

JDK 8 `iterator()` 返回一个组合迭代器：

1. `iterator()` 创建 `providers.entrySet().iterator()`，不扫描资源。
2. `hasNext()` 先检查已有 entry；缓存耗尽后，交给共享 LazyIterator 解析尚未发现的 provider 名称。
3. 新实例按名称放入 LinkedHashMap。
4. `next()` 在缓存命中时执行 `knownProviders.next().getValue()`；缓存未命中时才由 LazyIterator 创建新实例。
5. 之后从同一个 ServiceLoader 再取迭器，会先得到同一批缓存实例。

这意味着缓存属于 ServiceLoader 实例，不是 JVM 全局 singleton：

```java
ServiceLoader<Codec> a = ServiceLoader.load(Codec.class);
ServiceLoader<Codec> b = ServiceLoader.load(Codec.class);

Codec a1 = a.iterator().next();
Codec a2 = a.iterator().next(); // 同一 loader 缓存，通常就是 a1
Codec b1 = b.iterator().next(); // 另一个 ServiceLoader，可创建新实例
```

provider 若要求业务单例，应自己定义明确作用域，不能把 ServiceLoader 的实例缓存误当成全局生命周期容器。

## hasNextService 怎样枚举配置

LazyIterator 首次需要更多名称时构造：

```text
fullName = "META-INF/services/" + service.getName()
configs = loader.getResources(fullName)
```

它逐个 URL 打开 UTF-8 文本，解析为名称 Iterator，并用集合去重。当当前配置耗尽时继续下一个 URL；全部耗尽才报告 false。

打开资源、读取内容和解析格式都可能在迭代阶段失败。JAR 存在不等于配置可读，尤其要检查：

- 打包插件是否真的包含 META-INF/services，而不是只存在于源码目录；
- fat JAR/shading 是否合并了同名 service 文件，还是后一个覆盖前一个；
- 文件是否以服务接口二进制名精确命名；
- provider 行是否使用二进制名；嵌套类使用 `$`，不是源码里的点号写法；
- TCCL 的 getResources 是否能枚举目标 JAR。

## nextService 在哪里创建 provider

JDK 8 主线为：

```text
cn = next provider name
  -> Class.forName(cn, false, loader)
  -> service.isAssignableFrom(c)
  -> c.newInstance()
  -> service.cast(instance)
  -> providers.put(cn, instance)
```

`initialize=false` 先加载 Class 而不主动初始化。随后 `newInstance` 属于主动使用，会完成类初始化并调用 public 无参构造器。

类型检查先于实例转换。若 provider 实现了另一个加载器定义的同名服务接口，错误信息看似“实现类不是子类型”，真实原因是两份服务 Class 身份不同。诊断时同时输出：

```text
service.getClassLoader()
providerClass.getClassLoader()
service == providerClass.getInterfaces()[...]
配置 URL
```

不要只比较 `getName()`。

## ServiceConfigurationError 的失败边界

ServiceLoader 把配置和 provider 装配失败包装为 ServiceConfigurationError。常见原因包括：

- 配置文件语法非法或读取失败；
- provider 类不存在；
- provider 不是服务子类型；
- provider 不是可实例化的 public 具体类；
- public 无参构造器不存在或不可访问；
- 类初始化器或构造器抛异常；
- 安全策略拒绝所需操作。

ServiceConfigurationError 继承 Error，但它表示部署/配置错误，不等于 JVM 已不可恢复。基础设施可以在应用启动边界捕获后给出包含 service、配置 URL、provider 名称的诊断并终止启动；不应在每次业务调用中吞掉它再静默回退到未知实现。

JDK 8 的 `Class.newInstance` 会直接传播部分初始化异常，ServiceLoader 再统一包装。JDK 17/21 的实现改用 Constructor.newInstance 创建普通 provider，显式模块的 `provider()` 则经 Method.invoke 调用；异常 cause 的包装细节可能不同，测试应断言失败类型和业务诊断，不绑定完整内部堆栈文本。

## reload 清理什么

`reload()` 清空当前 ServiceLoader 的 provider 缓存，并重置后续发现游标。下一次迭代会重新枚举配置并创建 provider：

```text
reload 前：providers = { Alpha -> alpha#1, Beta -> beta#1 }
reload 后：providers = {}
再次迭代：Alpha -> alpha#2 ...
```

它不会：

- 卸载 provider Class；
- 创建新 ClassLoader；
- 关闭旧 provider 持有的线程、文件或连接；
- 自动调用 close/destroy；
- 修改线程 TCCL；
- 保证正在并发使用的旧实例立即消失。

如果 provider 有资源生命周期，调用方必须在 reload 前建立停止和关闭协议。仅清 map 仍可能由业务引用、线程、ThreadLocal 或全局注册表保留旧实例和旧加载器。

## ServiceLoader 不是线程安全容器

JDK 文档明确指出 ServiceLoader 实例不保证多个线程并发安全。providers、延迟迭代状态和 reload 都是可变状态。常见安全做法：

- 启动线程中完成发现，再发布不可变 provider 列表；
- 用外部锁串行迭代与 reload；
- 每个隔离上下文创建自己的 ServiceLoader；
- 不在业务高并发路径共享一个正在惰性推进的 iterator。

JDK 9+ 的旧 iterator/stream 在 reload 后有更明确的并发修改检测；JDK 8 内部状态不同。跨版本代码不要依赖“reload 以后旧迭代器还能走几步”。

## provider 顺序不等于优先级系统

单个配置文件内的行顺序可以被观察，但真实应用往往有多个 JAR、多个父子加载器和打包合并工具。若业务需要明确选择，建议在服务接口上定义可测试的元数据，例如：

```java
interface Codec {
    String id();
    int priority();
    boolean supports(String mediaType);
}
```

发现后由调用方验证 id 唯一、按 priority 排序并处理冲突。这是业务策略；ServiceLoader 只负责发现，不应让 classpath 偶然顺序替代明确规则。

## JDK 9 以后模块 provider 的两种形态

显式命名模块使用描述符声明：

```java
module codec.api {
    exports com.example.api;
}

module codec.consumer {
    requires codec.api;
    uses com.example.api.Codec;
}

module codec.json {
    requires codec.api;
    provides com.example.api.Codec
        with com.example.json.JsonCodecProvider;
}
```

模块 provider 可以有 public 无参构造器，也可以声明 public static、无参数、名为 `provider` 的方法并返回可赋给服务的类型。provider 方法是模块描述符发现路径的能力；部署在 classpath/未命名模块并通过 `META-INF/services` 声明时，仍按 provider 类和 public 无参构造器规则创建，不会自动调用同名 static 工厂。

命名模块中的 provider 不依赖把实现包 export 给消费者；服务绑定由 `uses/provides` 表达。消费者只依赖 API 模块，ServiceLoader 负责取得 provider。若调用者处于显式模块，应正确声明 uses，不要靠 classpath 时代的隐式可见性。

## stream 与 Provider 的惰性边界

JDK 9 新增 `ServiceLoader.stream()`，流元素是 `ServiceLoader.Provider<S>`：

```java
List<Codec> codecs = ServiceLoader.load(Codec.class)
    .stream()
    .filter(provider -> hasMarker(provider.type()))
    .map(ServiceLoader.Provider::get)
    .collect(toList());
```

`type()` 允许先检查 provider 类型元数据，`get()` 才取得实例。但扫描配置、加载 provider Class 和读取注解仍有成本，也可能失败；“没有构造实例”不等于完全没有类加载或 I/O。

JDK 8 没有这组 API，兼容源码应直接迭代实例，或把版本特性隔离在单独模块。

## 何时不应使用 ServiceLoader

以下需求超出它的职责：

- provider 构造需要复杂依赖图；
- 需要条件配置、配置属性绑定和完整生命周期；
- 需要动态远程注册与健康检查；
- 需要严格版本协商和沙箱边界；
- 需要每请求/每租户作用域。

可以让 DI 容器先通过 ServiceLoader 发现“模块入口”，再由入口向容器注册组件；也可以直接使用框架自己的扩展机制。关键是把发现、选择、创建、生命周期四个阶段分开，不要把一个轻量发现器当成完整插件平台。
