# Java 内存模型（JMM）：线程之间如何看见数据

Java 内存模型（Java Memory Model，JMM）解决的核心问题是：**多个线程读写同一份数据时，哪些结果是 Java 必须保证的，哪些结果只是这次运行碰巧出现的。**

第一次阅读时先不要背屏障指令，也不用马上理解 Unsafe 和 VarHandle。先用一个例子回答“写线程已经改成 42，读线程为什么不一定看见”，后面的规则才有落点。

::: warning 先排除一个常见误会
JMM 不是 JVM 的堆、栈、方法区等运行时内存区域。

- **JVM 运行时内存区域**回答：对象、局部变量和类信息通常放在哪里。
- **JMM**回答：多个线程共享变量时，读写之间需要什么约束，才能可靠地传递结果。
:::

本专题以 **Java Language Specification 8 第 17 章**和 OpenJDK `jdk8u412-b08` 为主基线；JDK 17/21 的 VarHandle 放在进阶部分，用来解释新版 API 如何表达不同强度的内存顺序。

## 先看这一个例子

两个线程共享一条消息：

```java
class Message {
    int payload = 0;
    boolean ready = false;
}

// 写线程 A
message.payload = 42;
message.ready = true;

// 读线程 B
if (message.ready) {
    System.out.println(message.payload);
}
```

直觉会认为：B 已经看到 `ready=true`，A 肯定更早写过 `payload=42`，所以 B 必然打印 42。

但两个字段都是普通字段时，JMM 没有在 A 和 B 之间建立同步关系。B 可能看到最新结果，也可能看不到；“在我的电脑上一直是 42”只能说明这几次运行碰巧如此，不能成为程序正确性的依据。

把 `ready` 改为 volatile，才形成一条正式发布链：

```java
volatile boolean ready = false;
```

<JmmPublicationAnimation />

整条链只有四个动作：

```text
A1: 写 payload=42
    → A2: volatile 写 ready=true
        → B1: volatile 读 ready=true
            → B2: 读 payload
```

为什么 B2 必须得到 42：

1. A1 在 A2 前面，这是写线程内的**程序顺序**。
2. A2 与 B1 读写同一个 volatile 字段，并且 B1 读到了 A2 发布的值，这是**跨线程同步边**。
3. B1 在 B2 前面，这是读线程内的**程序顺序**。
4. 四个动作通过**传递性**连起来，所以 `A1 happens-before B2`。

这里的 happens-before 可以先理解为：**如果 B2 执行，它必须能观察到 A1 已发布的结果；编译器、JIT 和 CPU 也不能用破坏这个保证的方式重排相关动作。**

## 三个问题不要混在一起

| 问题 | 用人话说 | 本专题中的例子 | volatile 能否单独解决 |
| --- | --- | --- | --- |
| 可见性 | B 是否必须看到 A 写的新值 | B 看到 `ready=true` 后能否看到 `payload=42` | 可以，前提是形成配对的发布链 |
| 原子性 | 一组操作会不会被其他线程插入 | 两个线程同时执行 `count++` 会不会丢失更新 | 不可以，`++` 是读、计算、写三个动作 |
| 有序性 | 相关动作能否被交换顺序 | `payload=42` 能否越过发布 `ready=true` | volatile 只约束与发布链相关的重排 |

最容易记错的是：**volatile 解决“把已经写好的结果告诉别人”，不负责“同一时间只让一个人修改”。**

```text
T1: 读 count=0 ── 计算 1 ── 写 1
T2: 读 count=0 ── 计算 1 ── 写 1
结果：执行两次 count++，最终仍可能只有 1
```

四次 volatile 访问都可以是合法的，但三个动作组成的 `count++` 并没有整体原子性。精确累加应使用 `AtomicInteger.incrementAndGet()` 或锁。

## 不同工具各自负责什么

| 真实需求 | 通常选择 | 它负责的边界 |
| --- | --- | --- |
| 发布一个已准备好的状态或不可变快照 | volatile 字段/引用 | 可见性与必要顺序，不提供互斥 |
| 对一个数值做原子递增、CAS 更新 | `AtomicInteger` 等原子类 | 单次读改写的原子性 |
| 多个字段必须一起满足业务不变量 | `synchronized` 或 `Lock` | 互斥执行，并在解锁/加锁间发布结果 |
| 发布构造完成后不再变化的对象 | final 字段 + 安全发布 | 稳定构造状态；不能在构造期间泄漏 `this` |
| 在线程结束后读取结果 | `join()`、`Future.get()` | 完成检测与结果可见性 |

不要先看到关键字再猜作用。先问“需要可见性、一次原子更新，还是多字段互斥”，再选工具。

## 第一次阅读只走基础线

1. 当前页：理解 `payload + ready` 的四动作发布链。
2. [happens-before 与安全发布](./happens-before.md)：认识 volatile、锁、start 和 join 提供的跨线程边。
3. [volatile、final 与 DCL](./volatile-final.md)：先读 volatile 与 `count++`，final 和 DCL 可放到第二遍。
4. [断点与并发实验](./debug-lab.md)：先完成实验一和实验二，把可见性与原子性分开。

理解基础线后，再读 [Unsafe 到 VarHandle](./varhandle-version.md)。它解释的是“底层 API 怎样表达内存顺序”，不是理解 JMM 的前置条件。

## 读并发源码时怎样画图

1. **列动作**：写字段、读字段、加解锁、volatile 访问、start、join。
2. **连线程内顺序**：同一线程中，哪些动作按程序顺序相连。
3. **寻找跨线程边**：volatile 写/读、unlock/lock、start、join 或并发容器契约。
4. **做传递连接**：从目标读操作反向检查，能否一路连到需要看见的写操作。

如果两个线程访问同一个变量、至少一个是写，并且无法建立 happens-before 顺序，就存在数据竞争。此时不能再用“源码看起来先写后读”证明结果可靠。

## 源码与实验入口

到这里已经理解主线，再使用下面的源码入口和断点地图会更有效：

<TopicStudyPanel topic-id="openjdk8-jmm-volatile-final" />

| 语义 | 固定版本源码 | 阅读目标 |
| --- | --- | --- |
| start/join 边界 | [`Thread.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Thread.java) | `start` 前动作如何发布给新线程，终止如何被 join 观察 |
| volatile 状态发布 | [`FutureTask.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java) | `state` 终态如何约束结果与等待者清理 |
| CAS 与 volatile 字段 | [`AtomicInteger.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/AtomicInteger.java) | volatile value 与 Unsafe CAS 的职责分工 |
| 快照发布 | [`CopyOnWriteArrayList.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/CopyOnWriteArrayList.java) | 写线程如何发布整份新数组 |
| JDK 8 底层入口 | [`Unsafe.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/misc/Unsafe.java) | volatile、ordered 与 CAS 的 VM 边界 |
| JDK 21 公开入口 | [`VarHandle.java`](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/invoke/VarHandle.java) | plain、opaque、acquire/release、volatile 的分层语义 |

JMM 的完整规范不位于某个 `.java` 文件。源码展示的是类库怎样使用规则，不能代替规范本身；某一台机器反汇编出的屏障指令，也不能代表所有 JVM 的公开契约。

## 基础过关问题

1. 如果 `ready` 是普通字段，为什么 B 即使读到 true，也不能据此证明 payload 必然为 42？
2. `ready` 改为 volatile 后，A1、A2、B1、B2 之间分别依靠哪条规则连接？
3. `volatile int count` 为什么仍不能让 `count++` 原子？
4. 需要同时修改余额和流水状态时，为什么通常应使用锁，而不只是把两个字段都声明为 volatile？

能用自己的话回答这四题，再进入 final、DCL 和 VarHandle 会顺畅很多。
