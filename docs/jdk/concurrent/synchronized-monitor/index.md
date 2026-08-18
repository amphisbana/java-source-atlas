# synchronized / ObjectMonitor / wait-notify：从字节码到条件等待

`synchronized` 同时承担三件事：同一时刻只允许一个线程执行临界区、允许同一线程重入、在解锁与后续加锁之间建立 happens-before。`Object.wait/notify` 则在同一个 monitor 上增加“条件暂时不满足时释放锁并等待”的协作协议。

这两组能力经常被混在一句“对象锁”里，源码阅读时必须拆成三层：

| 层次 | 可以依赖的内容 | 不能当作跨版本契约的内容 |
| --- | --- | --- |
| Java 语言与内存模型 | 同步方法/代码块、互斥、重入、解锁到后续加锁的 HB | 对象头具体位宽、锁记录布局 |
| JVM 字节码与公开 API | `monitorenter`、`monitorexit`、`ACC_SYNCHRONIZED`、`wait/notify` 前置条件 | JIT 最终生成哪条机器指令 |
| JDK 8 HotSpot 实现 | mark word、快速路径、膨胀后的 `ObjectMonitor`、`_EntryList`、`_WaitSet` | 其他 JVM 或 JDK 17/21 必须使用同样队列和策略 |

本站以 OpenJDK `jdk8u412-b08` 为实现基线。动画中的 `_owner / _recursions / _EntryList / _WaitSet` 用来读懂这份 HotSpot 源码；Java 程序正确性只应依赖前两层保证。

## 先分清三种“没有继续执行”

| 线程状态 | 正在等待什么 | 是否持有目标 monitor | 如何恢复 |
| --- | --- | --- | --- |
| `BLOCKED` | 进入 `synchronized` 或从 `wait` 返回前重新取得 monitor | 否 | monitor 释放后重新竞争 |
| `WAITING` | `Object.wait()`、`Thread.join()`、`LockSupport.park()` 等无期限等待 | `Object.wait()` 已完整释放其 monitor | 通知、中断、目标终止、unpark 或伪唤醒后按各自协议处理 |
| `TIMED_WAITING` | `wait(timeout)`、`sleep`、`join(timeout)`、`parkNanos` 等有界等待 | `wait(timeout)` 释放 monitor，`sleep` 不释放 | 超时或对应通知机制 |

`BLOCKED` 不是“线程被阻塞”的泛称；它专指等待进入 Java monitor 的公开状态。`WAITING` 也不等于一定在 `Object.wait`，必须结合线程栈顶部方法判断。

## 固定版本源码入口

| 入口 | 固定版本源码 | 阅读目标 |
| --- | --- | --- |
| Java API 契约 | [`Object.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Object.java) | `wait/notify/notifyAll` 的 owner 前置条件、中断和超时语义 |
| 公开线程状态 | [`Thread.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Thread.java) | `BLOCKED / WAITING / TIMED_WAITING` 的 API 定义 |
| monitor 数据结构 | [`objectMonitor.hpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/runtime/objectMonitor.hpp) | `_owner`、`_recursions`、`_cxq`、`_EntryList`、`_WaitSet` |
| enter/exit/wait/notify | [`objectMonitor.cpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/runtime/objectMonitor.cpp) | 膨胀 monitor 的竞争、park、等待集转移和重入深度恢复 |
| monitor 获取总入口 | [`synchronizer.cpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/runtime/synchronizer.cpp) | 对象 mark、锁记录、膨胀与 `ObjectSynchronizer` 分派 |
| 解释器慢路径 | [`interpreterRuntime.cpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/interpreter/interpreterRuntime.cpp) | 字节码 monitor 操作怎样进入运行时同步器 |

`Object.wait()` 在 Java 源码里是 `final native`，所以只在 `Object.java` 中寻找循环和队列会走错方向。Java 层定义契约，真实等待过程由 JVM 实现。

## 动画：一次完整的重入、wait、notify 与重新竞争

下面 15 帧把入口竞争集合与条件等待集合并排展示。重点观察三个时点：`wait` 完整释放重入深度、`notify` 之后 owner 仍是通知线程、被通知线程重新取得 monitor 后才从 `wait` 返回。

<SynchronizedMonitorAnimation />

图中的 EntryList 是“有资格竞争 monitor 的线程”示意。JDK 8 HotSpot 内部还维护 `_cxq`，notifyee 的具体放置位置受版本与策略影响；Java 规范只保证它离开等待集并在返回前重新取得 monitor，不保证内部链表名称或 FIFO 顺序。

## 一条完整调用链怎样读

### `synchronized` 代码块

```text
Java synchronized (monitor)
  → javac 生成 monitorenter
  → 解释执行 / JIT 快速获取
  → 快速路径失败时进入 ObjectSynchronizer::enter
  → 必要时 inflate 为 ObjectMonitor
  → ObjectMonitor::enter / EnterI
  → 竞争线程 park，公开状态表现为 BLOCKED
```

退出方向是 `monitorexit → 快速释放或 ObjectSynchronizer::exit → ObjectMonitor::exit`。异常退出同样必须释放 monitor，javac 通常用异常处理表生成成对的 `monitorexit`。

### `Object.wait()`

```text
必须已经持有 monitor
  → 校验 owner，否则 IllegalMonitorStateException
  → 保存重入层数
  → 把当前线程加入 WaitSet
  → 完整释放 monitor
  → park，Thread.State = WAITING / TIMED_WAITING
  → notify / notifyAll / interrupt / timeout / 伪唤醒
  → 离开 WaitSet，重新竞争入口
  → 成功取得 monitor 并恢复重入层数
  → wait 正常返回或抛 InterruptedException
  → 调用方 while 再次检查业务条件
```

### `notify()`

```text
必须已经持有 monitor
  → 从该 monitor 的等待集中选择一个等待者
  → 让它转入重新竞争阶段
  → notify 立即返回，当前线程仍持有 monitor
  → 当前线程执行到 monitorexit
  → 被通知线程才有机会重新取得 monitor
```

## 阅读顺序

1. [字节码、重入与释放](./bytecode-reentrancy.md)：先区分同步块和同步方法，理解 owner 与进入次数。
2. [ObjectMonitor 的入口集合与 WaitSet](./objectmonitor-queues.md)：再把 Java 状态映射到 JDK 8 HotSpot 字段。
3. [wait、notify、超时与中断](./wait-notify.md)：完成条件循环和失败路径。
4. [断点与线程状态实验](./debug-lab.md)：用可运行案例观察 `BLOCKED / WAITING` 和 notify 后重新竞争。

完成本专题后再读 [AQS 与 Condition](/jdk/concurrent/locks/)：你会看到 AQS 把“同步队列”和“条件队列”显式做成 Java 节点，而内置 monitor 把相似职责隐藏在 JVM 内部。

## 过关问题

1. 同步方法为什么不一定能在方法体字节码中找到 `monitorenter`？
2. 两层重入只执行一次 `monitorexit` 后，其他线程为什么仍然是 `BLOCKED`？
3. `wait()` 与 `sleep()` 对当前持有的 monitor 有什么根本区别？
4. `notify()` 已调用后，被通知线程为什么还不能马上从 `wait()` 下一行继续？
5. 为什么必须用 `while (!condition) wait()`，而不能只写 `if`？
6. 哪些结论属于 Java 规范，哪些只属于 JDK 8 HotSpot 的 `ObjectMonitor` 实现？
