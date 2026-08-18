# Reflection 与 JDK Dynamic Proxy：从 Method 元数据到运行时分派类

Reflection 解决的是“运行时才知道要读哪个类型、调用哪个成员”的问题；JDK Dynamic Proxy 解决的是“运行时才知道要为哪些接口创建统一分派入口”的问题。两者经常一起出现，但不是同一机制：反射可以直接调用已有类的成员，动态代理会先生成一个新的接口实现类，再由这个类把调用转交给 `InvocationHandler`。

本专题以 OpenJDK 8u 为主基线。JDK 8 的 `Method.invoke` 默认采用 native accessor 到生成字节码 accessor 的 inflation 策略，动态代理采用 `WeakCache + ProxyClassFactory + ProxyGenerator`。JDK 17 已加入模块边界但反射执行器仍保留旧架构；JDK 21 的核心反射默认改为 MethodHandle accessor，不能再用“第 16 次切换生成 accessor”解释现代 JDK 的主路径。

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `Class` | [`java/lang/Class.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Class.java) | `getMethod`、`getDeclaredMethod`、`ReflectionData`、Method copy |
| `AccessibleObject` | [`java/lang/reflect/AccessibleObject.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/reflect/AccessibleObject.java) | `setAccessible`、`override`、访问检查缓存 |
| `Method` | [`java/lang/reflect/Method.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/reflect/Method.java) | `invoke`、`acquireMethodAccessor`、root accessor 共享 |
| `ReflectionFactory` | [`sun/reflect/ReflectionFactory.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/reflect/ReflectionFactory.java) | `newMethodAccessor`、inflation 配置 |
| `NativeMethodAccessorImpl` | [`sun/reflect/NativeMethodAccessorImpl.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/reflect/NativeMethodAccessorImpl.java) | 调用计数、`invoke0`、切换 delegate |
| `MethodAccessorGenerator` | [`sun/reflect/MethodAccessorGenerator.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/reflect/MethodAccessorGenerator.java) | 为高频调用生成 accessor 字节码 |
| `Proxy` | [`java/lang/reflect/Proxy.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/reflect/Proxy.java) | `newProxyInstance`、`getProxyClass0`、`ProxyClassFactory` |
| `WeakCache` | [`java/lang/reflect/WeakCache.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/reflect/WeakCache.java) | loader 主键、接口子键、并发生成占位 |
| `InvocationHandler` | [`java/lang/reflect/InvocationHandler.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/reflect/InvocationHandler.java) | `invoke(proxy, method, args)` 契约 |
| `ProxyGenerator` | [`sun/misc/ProxyGenerator.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/misc/ProxyGenerator.java) | 方法合并、字节码生成、返回与异常适配 |

OpenJDK 源码采用 GPLv2 with Classpath Exception。本专题只保留调用链、字段关系与等价伪代码，统一许可说明见站点源码许可页。

## 先区分四个运行时对象

以一个问候接口为例：

```java
interface GreetingService {
    String welcome(String name, long times);
}

GreetingService proxy = (GreetingService) Proxy.newProxyInstance(
        GreetingService.class.getClassLoader(),
        new Class<?>[]{GreetingService.class},
        handler);

proxy.welcome("atlas", 2);
```

运行过程中至少存在四种不同对象：

| 对象 | 谁创建 | 保存什么 | 是否等于真实目标 |
| --- | --- | --- | --- |
| `Method` | `Class` 查询或代理类静态初始化 | 声明类、名称、参数类型、返回类型、修饰符、底层调用入口 | 否，它是成员描述符 |
| 代理 `Class` | `ProxyGenerator` 生成并由 loader 定义 | `extends Proxy`，实现指定接口，包含具体分派方法 | 否，它是新生成的类 |
| 代理实例 | 代理 Class 构造器 | 父类字段 `Proxy.h` | 否，它只负责接收接口调用 |
| `InvocationHandler` | 应用代码 | 拦截、记录、路由或转发策略 | 不一定；handler 可以完全没有目标对象 |

把代理实例称为“真实对象的壳”只适用于 handler 确实持有并转发某个目标的场景。RPC 客户端、配置接口、延迟加载器等 handler 可以直接根据 `Method` 组装请求或计算结果，没有本地 target。

## 两条核心调用链

### Method.invoke

```text
Class.getDeclaredMethod
  -> 从当前类声明方法元数据中查找
  -> 返回可独立设置 accessible 标记的 Method 副本

Method.invoke(target, args)
  -> 未设置 override 时执行访问检查
  -> 读取或 acquireMethodAccessor
  -> MethodAccessor.invoke(target, args)
  -> 校验接收者和参数，执行目标方法
  -> 正常结果统一为 Object
  -> 目标异常包装为 InvocationTargetException
```

OpenJDK 8 默认创建 `DelegatingMethodAccessorImpl(NativeMethodAccessorImpl)`。native accessor 的调用计数超过默认阈值 15 时生成字节码 accessor，并替换 delegating accessor 的 delegate。阈值、内部类名和切换策略都不是 Java API 契约。

### Proxy.newProxyInstance 与代理方法调用

```text
Proxy.newProxyInstance(loader, interfaces, handler)
  -> clone interfaces
  -> WeakCache.get(loader, orderedInterfaces)
  -> miss: ProxyClassFactory 校验接口与 loader 可见性
  -> ProxyGenerator 生成 $ProxyN class bytes
  -> defineClass0(loader, ...)
  -> 调用 $ProxyN(InvocationHandler) 构造器
  -> Proxy.h = handler

稍后 client 调用 proxy.welcome(...)
  -> $ProxyN.welcome(...) 生成方法
  -> 装箱参数并读取静态 Method 字段
  -> h.invoke(proxy, method, Object[] args)
  -> 强转或拆箱 handler 返回值
  -> 按接口 throws 契约传播或包装异常
```

代理创建路径可以命中缓存，业务调用路径不会每次重新生成类。一次代理调用也不一定会使用 `Method.invoke`：只有 handler 选择反射转发到 target 时，两个机制才在这一点连接。

## 动画：查找、执行器 inflation、代理生成与 handler 分派

下面前六步严格对应 OpenJDK 8 的 `Method.invoke` 主线，后六步对应代理 Class 首次生成和实例调用。JDK 21 的反射执行器差异在版本章节单独说明，动画不会用 JDK 8 私有实现冒充跨版本规范。

<ReflectionProxyAnimation />

## 十条阅读不变量

1. `getMethod` 与 `getDeclaredMethod` 的搜索范围不同，不是“是否允许访问”的开关。
2. `Method` 描述一个 JVM 成员，但反复查询返回的 Java 对象身份不应作为缓存协议。
3. `setAccessible(true)` 只请求抑制语言访问检查；JDK 9+ 的模块开放边界仍可能拒绝它。
4. `Method.invoke` 允许拆箱后进行基本类型拓宽，不执行窄化转换。
5. 目标方法抛出的异常由 `InvocationTargetException` 包装；参数或访问错误在进入目标前直接抛出。
6. 动态代理只为接口生成实现类，不能直接代理一个没有接口的普通类。
7. 代理 Class 的缓存键包含 class loader 和有顺序的接口列表；接口顺序既影响缓存，也影响重复方法选择。
8. `equals`、`hashCode`、`toString` 会进入 handler；`getClass`、`wait`、`notify` 等 final Object 方法不会。
9. handler 返回值仍要经过生成代理方法的强转或拆箱；返回 `null` 给原始类型会在代理方法中触发 `NullPointerException`。
10. handler 抛出的未声明受检异常会变成 `UndeclaredThrowableException`，不是由 `Method.invoke` 产生的 `InvocationTargetException`。

## 最容易混淆的三类异常

| 异常 | 产生位置 | 意味着什么 |
| --- | --- | --- |
| `IllegalAccessException` | `Method.invoke` 访问检查 | 调用者无访问权，或未成功抑制检查 |
| `InvocationTargetException` | 反射成功进入目标后 | 目标本身抛错，真实异常在 `getCause()` |
| `UndeclaredThrowableException` | 生成代理方法处理 handler 异常 | handler 抛出接口 `throws` 不允许的受检异常 |

一个 handler 若用 `method.invoke(target, args)` 转发，应捕获 `InvocationTargetException` 并抛出 `getCause()`。否则接口调用者可能先看到反射包装，再被生成代理方法按异常声明二次处理，异常边界会变得难以理解。

## 反射不是动态代理的必选执行器

handler 常见的三种实现分别是：

```text
本地装饰器：记录耗时 -> Method.invoke(target, args) -> 解包 cause
RPC stub：Method + args -> 编码请求 -> 远端响应 -> 转换返回值
声明式配置：读取 Method 注解/名称 -> 查询配置 -> 直接返回结果
```

因此优化动态代理时，要先定位耗时到底发生在生成代理类、handler 逻辑、反射转发、网络 I/O，还是目标方法本身。把所有成本统称为“反射慢”无法指导修复。

## 一条完整阅读路径

1. [Method 元数据、调用与 accessor](./reflection-invoke.md)：从成员发现读到访问检查、参数转换、JDK 8 inflation 与异常外观。
2. [Proxy 生成、缓存与分派](./dynamic-proxy.md)：理解接口校验、代理类形态、Object/default 方法、返回值与异常适配。
3. [访问控制与 JDK 8/17/21 差异](./access-version.md)：区分语言访问、模块开放、SecurityManager 与 JEP 416。
4. [断点实验手册](./debug-lab.md)：用 Java 8 公共 API 验证稳定行为，再进入当前 JDK 私有实现观察。

## 什么时候适合用

Reflection 适合框架启动期的类型扫描、序列化映射、依赖注入、测试工具和明确受控的运行时调用。JDK Dynamic Proxy 适合接口已经表达稳定边界，且日志、事务、重试、远程调用或延迟解析可以统一放入 handler 的场景。

以下情况需要谨慎：

- 热循环中每次重复查找 Method，而不是在边界处缓存已验证的调用计划；
- 通过 `setAccessible` 强依赖 JDK 内部非开放包；
- handler 中把 `proxy.toString()` 当日志参数，导致递归再次进入 handler；
- 用一个万能 handler 隐藏复杂业务分支和事务语义；
- 依赖 `$Proxy0`、`com.sun.proxy`、accessor 内部类名或 inflation 次数做业务判断；
- 把接口 default 方法误认为会自动执行默认实现。

## JDK 8、17、21 总览

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| `Method.invoke` 主执行器 | native accessor，达到阈值后生成字节码 accessor | 仍保留 `jdk.internal.reflect` inflation 架构 | JEP 416 后默认使用 MethodHandle accessor |
| 深反射边界 | 语言访问检查 + SecurityManager | 模块强封装，`setAccessible` 可能抛 `InaccessibleObjectException` | 延续模块强封装 |
| 访问探测 API | `isAccessible` 表示 suppress 标记 | `canAccess`、`trySetAccessible` 可表达实际能力 | 延续 |
| public 代理类位置 | 通常 `com.sun.proxy.$ProxyN` | 模块感知的动态代理模块/包，名称不可假设 | 延续并继续演进 |
| 代理 default 方法 | 仍分派到 handler，没有标准 API 直接调用默认体 | Java 16 起可用 `InvocationHandler.invokeDefault` | 延续 |
| `Proxy.getProxyClass` | 可用 | 自 Java 9 起 deprecated | deprecated，优先 `newProxyInstance` |

跨版本稳定的是 Java Reflection 与 Proxy 的公开行为，不是私有 accessor、包名、生成类编号或缓存容器实现。
