# JMM / volatile / final / VarHandle：读懂并发源码的内存语义

Java 内存模型（Java Memory Model，JMM）不描述某一颗 CPU 的缓存结构，而是规定：线程中的动作允许怎样排序、一个线程的写入在什么条件下必须对另一个线程可见，以及数据竞争程序还能得到哪些合法结果。

本专题以 **Java Language Specification 8 第 17 章**和 OpenJDK `jdk8u412-b08` 为主基线，并用 JDK 17/21 的 `VarHandle` 解释后续版本如何把不同内存顺序变成公开 API。读源码时要把规范保证、Java 类库实现和 HotSpot/JIT 的平台实现分开。

## 为什么要先学这一层

本站已有并发类都在依赖 JMM，但它们解决的问题不同：

| 现有专题 | 依赖的内存语义 | 不能误解成 |
| --- | --- | --- |
| `AtomicInteger` | volatile 读写 + CAS | CAS 自动让任意业务动作变成事务 |
| `CopyOnWriteArrayList` | volatile 发布新数组 | 数组中每个元素都是 volatile |
| `ConcurrentLinkedQueue` | CAS 发布节点连接 | 遍历获得某一时刻的全局快照 |
| `FutureTask` | volatile 状态发布结果 | `cancel(true)` 强制停止目标代码 |
| `ThreadPoolExecutor` | 锁、volatile、队列交接 | 任务提交后立即在另一线程执行 |
| Spring 单例容器 | monitor、并发 Map 与安全发布 | Bean 字段天然跨线程可见 |

如果不先建立 happens-before 图，读到 `volatile`、CAS、锁和线程启动时只能记结论，无法判断哪些普通字段被一起发布、哪些复合操作仍然会竞争。

## 三个问题必须分开

| 维度 | 问题 | `volatile int value` 能否单独解决 |
| --- | --- | --- |
| 可见性 | 线程 B 何时必须看到线程 A 的写入 | 单次 volatile 写/读可以建立边 |
| 原子性 | 一个动作能否被观察到中间状态 | 单次读写有保证，`value++` 没有 |
| 有序性 | 编译器、JIT、CPU 能否交换动作顺序 | 只能约束跨过该同步动作的相关重排 |

`volatile` 不是“轻量级锁”。它不提供互斥，也不把一组业务语句合并成不可分割事务。它提供的是特定变量上的同步顺序，以及由程序顺序和传递性扩展出的可见性边界。

## 源码观察入口

| 语义 | 固定版本源码 | 阅读目标 |
| --- | --- | --- |
| start/join 边界 | [`Thread.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Thread.java) | `start` 前动作如何发布给新线程，终止如何被 join 观察 |
| volatile 状态发布 | [`FutureTask.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java) | `state` 终态如何约束结果与等待者清理 |
| CAS 与 volatile 字段 | [`AtomicInteger.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/AtomicInteger.java) | volatile value 与 Unsafe CAS 的职责分工 |
| 快照发布 | [`CopyOnWriteArrayList.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/CopyOnWriteArrayList.java) | 写线程如何发布整份新数组 |
| JDK 8 底层入口 | [`Unsafe.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/misc/Unsafe.java) | volatile、ordered 与 CAS 的 VM 边界 |
| JDK 21 公开入口 | [`VarHandle.java`](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/invoke/VarHandle.java) | plain、opaque、acquire/release、volatile 的分层语义 |

JMM 的完整规范不位于某个 `.java` 文件。源码只能展示类库怎样使用规则，不能代替规范本身。尤其不能从某次反汇编或某个 CPU 的屏障指令倒推出所有 JVM 的公开契约。

## 动画：从无边数据竞争到正式发布边

下面 16 帧先展示普通标志没有跨线程保证，再分别建立 volatile、start、join 与 final 构造边界，最后把 JDK 8 Unsafe 和 JDK 9+ VarHandle 放在同一条演进线上。

<JmmMemoryModelAnimation />

动画中的“结果不保证”不是说读线程一定得到旧值。它表示规范没有给出必须观察到哪一次写入的 happens-before 约束；测试不能把某个偶发旧值当作必然结果。

## 建立 happens-before 图的四步法

1. **列动作**：写字段、读字段、加解锁、volatile 访问、start、join。
2. **连线程内程序顺序**：同一线程中前面的动作 happens-before 后面的动作。
3. **连跨线程同步边**：volatile 写到后续读、unlock 到后续 lock、start 与 join 等。
4. **做传递闭包**：如果 A → B 且 B → C，则 A → C；最终再判断冲突访问是否仍无序。

只有两个冲突访问之间没有 happens-before 顺序，并且至少一个是写，才构成 data race。race-free 程序可以按 sequential consistency 方式理解；含数据竞争的程序不能用“源码从上到下”替代内存模型推理。

## 阅读顺序

1. [happens-before 与安全发布](./happens-before.md)：先会画正式可见性边。
2. [volatile、final 与 DCL](./volatile-final.md)：再区分发布、互斥和复合原子性。
3. [Unsafe 到 VarHandle](./varhandle-version.md)：理解 JDK 8/17/21 内存访问 API 的边界。
4. [断点与并发实验](./debug-lab.md)：用确定性闸门验证能被公开契约保证的结果。

完成后再进入 [Atomic 与 Striped64](/jdk/concurrent/atomic/)、[Thread / LockSupport](/jdk/concurrent/thread-locksupport/) 和 [AQS](/jdk/concurrent/locks/)，会更容易识别每个字段承担的是状态、发布还是互斥职责。

## 过关问题

1. 线程 A 先写普通 `payload` 再写 volatile `ready=true`，线程 B 读到 `ready=true` 后为什么必须看到 payload？
2. `volatile int count` 为什么仍不能让 `count++` 原子？
3. start 前写入和 join 后读取分别通过哪条 happens-before 规则成立？
4. final 字段保证的前提为什么包含“构造期间 this 没有逃逸”？
5. `setRelease/getAcquire` 与 `setVolatile/getVolatile` 的约束强度有什么差别？
6. 为什么不能写一个测试，断言普通非 volatile 标志最终一定导致死循环？
