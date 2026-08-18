# ThreadLocal：数据跟着线程，而不是跟着调用

`ThreadLocal<T>` 为每个访问它的线程保存一份独立绑定。看上去是 `threadLocal.get()` 从对象里取值，JDK 实际采用相反关系：每个 `Thread` 持有自己的 ThreadLocalMap，`ThreadLocal` 对象只是进入这张 map 的弱引用 key。

本专题以 OpenJDK 8u 为主基线。JDK 17/21 的公开 `get/set/remove` 语义、黄金增量哈希、开放寻址和陈旧 Entry 清理协议基本延续；较新版本改用 `Reference.refersTo` 判断弱引用身份，JDK 21 还要区分虚拟线程与承载线程，并提供 ScopedValue 预览能力作为部分上下文场景的替代选择。

[打开 JDK 8 / 17 / 21 版本对比 →](/jdk/version-comparison/)，可并排查看弱引用身份判断、载体线程内部入口和虚拟线程诊断支持。

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `ThreadLocal` | [`java/lang/ThreadLocal.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ThreadLocal.java) | `get`、`set`、`remove`、ThreadLocalMap |
| `Thread` | [`java/lang/Thread.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Thread.java) | `threadLocals`、`inheritableThreadLocals`、线程构造与退出 |
| `InheritableThreadLocal` | [`java/lang/InheritableThreadLocal.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/InheritableThreadLocal.java) | 独立 map 选择与 `childValue` |
| `WeakReference` | [`java/lang/ref/WeakReference.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ref/WeakReference.java) | Entry 对 key 的弱引用语义 |

## 先看清对象关系

```text
共享 ThreadLocal 对象 context
          │ 作为 key
          ├──────────────┐
          v              v
Thread-A.threadLocals   Thread-B.threadLocals
  Entry(context, A值)     Entry(context, B值)
```

同一个 ThreadLocal 可以被许多线程共享，但它不在自身字段中保存 A 值或 B 值。每次公开操作先取得 `Thread.currentThread()`，再访问当前线程的 map，因此两个线程不会覆盖彼此的 Entry。

这种隔离只覆盖“绑定关系”。如果两个线程都把同一个可变对象引用 set 进去，它们仍在并发修改同一对象；ThreadLocal 不会复制 value，也不会让 value 自身自动线程安全。

## Thread 中的两张 map

OpenJDK 8 的 Thread 包含两个包级字段：

| Thread 字段 | 使用者 | 作用 |
| --- | --- | --- |
| `ThreadLocal.ThreadLocalMap threadLocals` | 普通 ThreadLocal | 当前线程自己的普通绑定 |
| `ThreadLocal.ThreadLocalMap inheritableThreadLocals` | InheritableThreadLocal | 构造子线程时可以复制的绑定 |

两张 map 都是懒创建：字段初始为 null，第一次 set 或第一次缺失的 get 才建立初始容量为 16 的 ThreadLocalMap。普通 ThreadLocal 不会意外出现在可继承 map 中；InheritableThreadLocal 通过重写 `getMap/createMap` 选择另一字段。

线程正常退出时，Thread 会把这两个字段清空，整张 map 随线程一起变得可回收。真正需要警惕的是线程池工作线程、常驻事件循环等长期存活线程，它们的 map 也会长期存活。

## get 的当前线程查找链

JDK 8 的 `get()` 主线是：

```text
Thread t = Thread.currentThread()
map = getMap(t)
  ├─ map != null 且 getEntry(this) 命中
  │    -> 返回 entry.value
  └─ map 不存在或 key 未命中
       -> setInitialValue()
            -> value = initialValue()
            -> map.set(this, value) 或 createMap(t, value)
            -> 返回 value
```

Entry 存在且 value 为 null 与 Entry 不存在是两种状态。前者直接返回 null，后者调用 `initialValue()` 并创建绑定。

`ThreadLocal.withInitial(supplier)` 在 Java 8 返回一个 SuppliedThreadLocal，重写 `initialValue` 为 `supplier.get()`。Supplier 不是在 ThreadLocal 构造时执行，而是每个线程第一次缺失 get 时各自执行。

## initialValue 何时会再次调用

通常一个线程只初始化一次，但下面的顺序会再次初始化：

```text
第一次 get -> initialValue -> 保存 value#1
remove     -> 删除当前线程 Entry
再次 get   -> initialValue -> 保存 value#2
```

如果先调用 `set(value)`，之后 get 直接返回该值，不调用 initialValue。初始化函数应当明确成本和失败策略，不应暗含无法重复执行的外部副作用。

## set 的当前线程写入链

```text
Thread t = Thread.currentThread()
map = getMap(t)
  ├─ map 已存在 -> map.set(this, value)
  └─ map 不存在 -> createMap(t, value)
```

ThreadLocalMap 不使用链表或红黑树解决哈希冲突，而是在 `Entry[]` 中线性探测。set 可能更新已有 key、在首个 null 槽插入、遇到弱 key 已清空的 stale Entry 后替换并清理，或在清理不足且达到阈值时扩容。完整路径见 [ThreadLocalMap：哈希与开放寻址](./threadlocalmap.md)。

## remove 是生命周期协议的一部分

`remove()` 查找当前 ThreadLocal 的 Entry，清空 Entry 的弱引用 key，再从该槽调用 `expungeStaleEntry`。这会确定性地清掉 value，并重新整理同一连续探测区间。

在线程池请求边界，推荐固定写法：

```java
try {
    requestContext.set(context);
    handleRequest();
} finally {
    requestContext.remove();
}
```

finally 不是只为防内存占用。没有 remove 时，下一个复用同一工作线程的任务会直接读到上一个任务的用户、租户、事务或日志上下文，形成数据串用。详见 [线程池污染、继承与上下文边界](./threadpool-leak.md)。

## 为什么 map 本身没有锁

普通 get/set/remove 只能操作 `Thread.currentThread()` 对应的 map，map 的所有者线程也是执行操作的线程，所以不需要让多个线程并发修改同一数组。ThreadLocal 实例可共享，不代表某一张 ThreadLocalMap 被共享写入。

这不是允许通过反射从外部线程读取或修改另一个 Thread 的 map。那会绕过所有者线程模型、模块封装和清理协议，得到的结果没有公开线程安全保证。

## ThreadLocal 能解决和不能解决什么

适合：

- 一个同步调用链内反复读取的请求上下文，且边界能可靠清理。
- 线程独占、创建成本较高但可复用的辅助对象。
- 框架内部明确绑定到当前线程的事务资源或日志上下文。

不适合直接解决：

- 异步切换线程后的自动上下文传播。
- 多线程共同修改同一业务状态。
- 在线程池中没有明确 finally 清理的用户身份或租户信息。
- 用大量静态 ThreadLocal 缓存不可控体积对象。

异步任务提交时应显式捕获需要的上下文，并在执行线程建立、恢复或清理边界。把普通 ThreadLocal 当成跨线程参数，会让行为依赖任务碰巧落到哪个线程。

## 阅读路径

1. [ThreadLocalMap：哈希与开放寻址](./threadlocalmap.md)：理解黄金增量、线性探测、replaceStaleEntry 和扩容。
2. [弱 key、强 value 与陈旧清理](./stale-cleanup.md)：跟踪 expunge、启发式扫描和 remove。
3. [线程池污染、继承与上下文边界](./threadpool-leak.md)：处理任务复用、InheritableThreadLocal 和虚拟线程。
4. [断点实验手册](./debug-lab.md)：运行不依赖反射和 GC 偶然性的行为案例。

## JDK 8、17、21 的边界

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| key 身份判断 | `e.get() == key` | `e.refersTo(key)` | `e.refersTo(key)` |
| map 与清理算法 | 开放寻址、stale 清理 | 核心协议延续 | 核心协议延续 |
| 线程模型 | 平台线程 | 平台线程 | 平台线程与虚拟线程都各自持有普通 ThreadLocal 绑定 |
| 继承控制 | 内部构造参数控制 | Java 9 起公开五参数 Thread 构造器可关闭 | Thread.Builder 可为平台或虚拟线程关闭继承 |
| 有界上下文替代 | 无 ScopedValue | 无正式 ScopedValue | ScopedValue 为预览 API，适合部分只读、词法作用域场景 |

`Reference.refersTo` 的变化没有把 value 改成弱引用，也没有新增后台清理线程。无论哪个版本，都应把业务生命周期清理建立在显式 remove 上，而不是等待 GC 与启发式扫描碰巧发生。
