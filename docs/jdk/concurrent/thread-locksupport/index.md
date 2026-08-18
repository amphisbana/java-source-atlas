# Thread 与 LockSupport：线程从哪里开始，又为什么能够安全停下

`Thread` 回答“谁来执行这段代码、线程处于什么生命周期”；`LockSupport` 提供“给指定线程保留一个唤醒许可、必要时暂停它”的底层工具。AQS、FutureTask、CompletableFuture 以及许多阻塞队列最终都会直接或间接落到这两组能力上。

本专题以 OpenJDK 8u 为源码基线，并单独标注 JDK 17/21 的实现边界。`Thread.start()`、中断、六种 `Thread.State` 和一位 permit 是跨版本公开语义；`threadStatus`、`start0`、`parkBlocker` 字段名以及 Unsafe 调用方式属于私有实现，业务代码不能依赖。

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `Thread` | [`java/lang/Thread.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Thread.java) | 构造、`start`、`start0`、`run`、`interrupt`、`join`、`State` |
| `LockSupport` | [`java/util/concurrent/locks/LockSupport.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/locks/LockSupport.java) | `park`、`parkNanos`、`parkUntil`、`unpark`、`getBlocker` |
| `AbstractQueuedSynchronizer` | [`java/util/concurrent/locks/AbstractQueuedSynchronizer.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/locks/AbstractQueuedSynchronizer.java) | `parkAndCheckInterrupt`、`unparkSuccessor` |
| `FutureTask` | [`java/util/concurrent/FutureTask.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java) | `awaitDone`、`finishCompletion` |
| `Unsafe` | [`sun/misc/Unsafe.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/misc/Unsafe.java) | VM 层 `park`、`unpark` 入口；不应由业务直接使用 |

建议先只追四条主线：

```text
new Thread(target)
  -> start()
       -> 检查尚未启动
       -> ThreadGroup.add
       -> native start0()
       -> VM 调度新线程
       -> 新线程执行 run()
       -> target.run()

等待线程：while (!condition) LockSupport.park(blocker)
通知线程：先发布 condition，再 LockSupport.unpark(waiter)
取消线程：waiter.interrupt()
诊断线程：LockSupport.getBlocker(waiter)
```

## start 与 run 不是同一种调用

OpenJDK 8u 的 `Thread.run()` 很短：存在构造时传入的 `target` 就调用 `target.run()`。它仍是普通 Java 方法。直接执行 `worker.run()`：

- 代码在调用方线程同步运行；
- `worker` 仍是 `NEW`，没有创建新线程；
- 方法可以像普通方法一样被再次调用；
- 不建立 `Thread.start` 的 happens-before 边界。

`start()` 才是线程生命周期入口。JDK 8u 的主干可以压缩为：

```text
synchronized start()
  -> threadStatus != 0 ? IllegalThreadStateException
  -> group.add(this)
  -> start0()
  -> 失败时 group.threadStartFailed(this)
```

`start0` 是 native 方法，真正的线程栈创建、VM Thread 建立和系统线程调度不在 Java 源文件里。`start()` 返回也不代表 `run()` 已经开始或结束，只表示启动请求成功交给 VM。

同一个 Thread 对象只能成功 `start` 一次。即使第一次运行已经结束，第二次 `start()` 仍抛 `IllegalThreadStateException`。需要再次执行任务，应创建新 Thread，或把任务提交给 Executor，不应尝试复活已经 `TERMINATED` 的对象。

## Thread 提供的两条内存可见性边界

Java 内存模型为线程启动与汇合提供了明确规则：

1. 对某线程调用 `start()` 之前的动作，happens-before 该线程中的任何动作。
2. 某线程中的所有动作，happens-before 其他线程检测到它已经终止；最常见的正式边界是从成功的 `join()` 返回。

因此可以在 start 前普通写入不可变配置，再由新线程读取；也可以让工作线程写结果，调用方 join 后读取。不能把“多等一会儿”或反复 `getState()` 当成内存屏障。

```java
int[] result = new int[1];
Thread worker = new Thread(() -> result[0] = 42);
worker.start();
worker.join();
System.out.println(result[0]); // join 建立完成后的可见性
```

## LockSupport 解决什么问题

`wait/notify` 把等待与对象 monitor 绑定：调用者必须持有 monitor，wait 会释放它，notify 只能通知同一 monitor 的等待者。LockSupport 改为把一个 permit 绑定到每个线程：

- `unpark(thread)`：让目标线程的 permit 变为可用；
- `park()`：permit 可用时消费后返回，否则可能阻塞；
- permit 最多一位，连续多次 unpark 不会累加；
- unpark 可以早于 park，因此不会出现“通知必须恰好发生在等待之后”的要求；
- park 仍可能因中断或伪唤醒返回，所以业务条件必须放在循环里。

permit 不是锁的所有权，也不是消息队列，更不是计数信号量。它只决定下一次 park 是否需要等待。

## 一张图串起启动、许可和中断

下面 15 帧固定使用 main 与 atlas-worker：先从 `start/start0/run` 进入执行，再演示预发许可、重复 unpark 合并、第二次 park 真正等待、blocker 诊断、中断返回和定时等待。

<ThreadLockSupportAnimation />

## 与现有并发专题的关系

| 上层组件 | 如何使用 Thread / LockSupport | 阅读时要抓住的边界 |
| --- | --- | --- |
| AQS / ReentrantLock | 获取失败的节点通过 `park(this)` 等待，释放线程对后继 `unpark` | unpark 只恢复竞争，不直接转移锁所有权 |
| FutureTask | `get` 等待者压入 WaitNode 栈后 park；完成时逐个 unpark | task 状态才是条件，permit 只负责等待方式 |
| BlockingQueue | Condition/AQS 把队列“非空、未满”条件最终落实到 park/unpark | put/take 的锁和条件协议不能只靠 permit 替代 |
| ThreadPoolExecutor | Worker 用 Thread 执行任务；空闲时通常阻塞在工作队列 | `RUNNABLE` 不等于正在执行用户任务，`WAITING` 也不等于异常 |
| CompletableFuture | 同步等待节点可 park；异步阶段由 Executor 的线程运行 | 完成栈负责条件，LockSupport 不是阶段调度器 |

如果 AQS 已经看过，可以把本专题视为它的底座：AQS 额外解决等待队列、取消节点、前驱 SIGNAL 握手和锁状态；LockSupport 本身不提供这些策略。

## 阅读路径

1. [线程启动、六种状态与阻塞 API](./thread-state.md)：从 `start/start0/run` 到 BLOCKED、WAITING、TIMED_WAITING。
2. [中断：请求、观察、清除与恢复](./interrupt.md)：区分 `interrupt`、`isInterrupted`、`Thread.interrupted`。
3. [park/unpark：一位许可、blocker 与伪唤醒](./park-unpark.md)：理解正确等待循环和内存可见性边界。
4. [断点实验手册](./debug-lab.md)：运行五个有界实验，观察真实状态与源码分支。

建议的串联顺序是：本专题 → [AQS 与 ReentrantLock](../locks/) → [FutureTask](../futuretask/) → [ThreadPoolExecutor](../threadpoolexecutor/)。这样能先理解“线程为什么停下和醒来”，再阅读上层队列如何决定“谁应该停、谁应该醒”。

## JDK 8、17、21 的边界

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| 线程模型 | 平台线程 | 平台线程 | 平台线程 + 正式虚拟线程 |
| 启动主线 | Java `start` 检查后进入 native `start0` | 平台线程核心语义延续 | 平台线程与虚拟线程使用不同内部启动/调度路径 |
| park 底层入口 | `sun.misc.Unsafe.park/unpark` | `jdk.internal.misc.Unsafe`，blocker 写入方式调整 | LockSupport 区分平台线程与虚拟线程，虚拟线程通常由调度器卸载/恢复 |
| 线程构造 API | 构造器 + ThreadFactory | 构造器 + ThreadFactory | 新增 `Thread.Builder`、`Thread.startVirtualThread` 等虚拟线程 API |
| 六种 `Thread.State` | 六个公开枚举值 | 六个公开枚举值 | 仍是六个公开枚举值，不新增“VIRTUAL”状态 |

JDK 21 的虚拟线程改变的是调度与承载方式，不改变“一次 start”“中断是协作请求”“park 必须循环检查条件”等核心规则。虚拟线程在可卸载点 park 时通常不占住 carrier；在 native、外部函数或某些 monitor 场景被固定时，表现与成本会不同。不要把 JDK 8 的“一条 Thread 通常对应一条 OS 线程”直接套到虚拟线程上。

