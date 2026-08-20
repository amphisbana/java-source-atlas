# BlockingQueue：容量、等待与四组方法语义

`BlockingQueue` 把普通 `Queue` 的立即操作扩展为可等待、可超时的生产者与消费者协议。它解决的是“队列暂时满或空时，调用线程应该怎样继续”，不是自动决定线程池容量、业务丢弃策略或端到端流量上限。

本专题以 OpenJDK 8u 为主基线，同时标出 JDK 17/21 的实现边界。`ArrayBlockingQueue`、`LinkedBlockingQueue` 和 `SynchronousQueue` 都实现同一接口，但存储结构、锁粒度以及“容量”的含义完全不同。

<TopicStudyPanel topic-id="openjdk8-java-util-concurrent-blockingqueue" />

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `BlockingQueue` | [`java/util/concurrent/BlockingQueue.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/BlockingQueue.java) | 四组插入、移除与查看方法 |
| `ArrayBlockingQueue` | [`java/util/concurrent/ArrayBlockingQueue.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ArrayBlockingQueue.java) | `put`、`take`、`enqueue`、`dequeue` |
| `LinkedBlockingQueue` | [`java/util/concurrent/LinkedBlockingQueue.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/LinkedBlockingQueue.java) | 双锁、原子 `count`、跨锁通知 |
| `SynchronousQueue` | [`java/util/concurrent/SynchronousQueue.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/SynchronousQueue.java) | `transfer`、`TransferStack`、`TransferQueue` |

这些文件采用 GPLv2 并带 Classpath Exception。本专题只摘取调用链与状态转换，不复制大段源码；许可证与上游地址的统一说明见站点源码许可页。

## 四组方法如何选择

接口 Javadoc 用四种失败策略组织 API：抛异常、返回特殊值、无限等待、限时等待。

| 操作 | 抛异常 | 返回特殊值 | 一直等待 | 限时等待 |
| --- | --- | --- | --- | --- |
| 插入 | `add(e)`：满时 `IllegalStateException` | `offer(e)`：满时 `false` | `put(e)`：等待空间，可中断 | `offer(e,time,unit)`：超时 `false`，可中断 |
| 移除队首 | `remove()`：空时 `NoSuchElementException` | `poll()`：空时 `null` | `take()`：等待元素，可中断 | `poll(time,unit)`：超时 `null`，可中断 |
| 查看队首 | `element()`：空时 `NoSuchElementException` | `peek()`：空时 `null` | 不提供 | 不提供 |

`add/remove/element` 的异常形式主要来自 `Queue` 与 `AbstractQueue`：典型的 `add` 会调用 `offer`，返回 false 时抛 `IllegalStateException`；`remove/element` 分别基于 `poll/peek`，遇到 null 时抛 `NoSuchElementException`。源码调试时不要只在具体队列类中查找全部九个入口。

### 特殊值为什么不会歧义

`BlockingQueue` 不接受 null 元素。实现通常在插入入口直接抛 `NullPointerException`，所以 `poll()` 或 `peek()` 返回 null 可以稳定表示“当前没有元素”，不会与一个真实的 null 元素混淆。

空或满只是调用瞬间的状态：

- `offer` 返回 false 后，另一个线程可能立刻取走元素；
- `poll` 返回 null 后，生产者可能立刻插入元素；
- `remainingCapacity()` 是观察值，不能用“先检查再插入”替代一次 `offer`；
- `size()` 适合监控，不是建立并发协议的条件判断。

### 阻塞方法仍然需要取消协议

`put` 与 `take` 可以无限等待容量条件，但线程中断会让它们抛出 `InterruptedException`。定时方法还会在截止时间耗尽后返回失败值。调用方应明确：

1. 中断是否向上抛出，还是恢复中断标记后结束当前任务；
2. 超时是重试、降级、拒绝还是记录失败；
3. 生产者停机时，消费者通过中断、结束标记还是队列中的终止消息退出。

吞掉 `InterruptedException` 并无条件重试，会让线程池关闭和任务取消失效。反过来，使用无限 `put` 也不等于系统已经具备完整背压：如果生产者本身占满了必须执行消费者的同一个小线程池，仍可能形成线程饥饿。

## BlockingQueue 的内存一致性保证

接口规定：线程把对象放入 `BlockingQueue` 之前的动作，happen-before 另一个线程通过队列访问或移除该对象之后的动作。典型消息传递因此不需要再给消息字段逐个增加 `volatile` 才能发布初始化结果：

```text
生产者：构造 message → 写入字段 → put(message)
                                      happens-before
消费者：                        take() → 读取 message 字段
```

这个保证围绕成功交接的元素建立，不代表队列外任意共享变量都自动安全，也不把一次业务处理和数据库提交变成事务。对象交接后若多个线程继续可变地共享它，仍需要额外同步。

## 容量、背压与批量操作边界

有界队列把积压数量限制在构造容量内，生产者可以通过 `put` 等待或通过 `offer` 得到明确失败。容量选择仍需结合到达速率、处理速率、任务大小和允许延迟：

```text
长期到达速率 > 长期处理速率
  → 有界队列最终满并触发等待/失败
  → 近似无界队列持续占用内存并扩大延迟
```

`drainTo` 可以减少逐个获取锁的开销，但接口没有承诺所有 `Collection` 批量操作都是原子的；把队列 drain 到自身会抛 `IllegalArgumentException`，目标集合在操作过程中被并发修改则属于未定义行为。容量与批量边界应以具体实现 Javadoc 为准。

## 三种实现应该怎样选

| 需求 | 更合适的起点 | 关键取舍 |
| --- | --- | --- |
| 固定容量、低额外分配、明确公平选项 | `ArrayBlockingQueue` | 单锁保护数组，生产与消费不能同时修改队列 |
| 链式存储、生产与消费希望在不同锁下并行 | 有界 `LinkedBlockingQueue` | 每个元素分配节点，跨边界时需要另一把锁发信号 |
| 不允许缓存，只能由生产者和消费者当场交接 | `SynchronousQueue` | 容量恒为 0，每次成功插入都必须匹配一次移除 |
| 线程池任务排队 | 结合线程池容量策略选择 | 队列会改变 `ThreadPoolExecutor.execute` 的扩线程路径 |

`LinkedBlockingQueue()` 的默认容量是 `Integer.MAX_VALUE`，常被称为“无界”，但它仍受整数上限与可用内存限制。生产系统通常应显式给出容量和饱和策略，避免把延迟与内存风险隐藏在默认值后。

线程池中的队列影响可回看 [ThreadPoolExecutor 的 execute 决策](../threadpoolexecutor/index.md#execute-的三步决策)。条件等待的节点转移基础见 [Condition：条件队列与重新竞争](../locks/condition.md)。

## 一条完整学习路径

1. [ArrayBlockingQueue 与 LinkedBlockingQueue](./array-linked.md)：理解单锁环形数组、双锁链表和跨锁 signal。
2. [SynchronousQueue](./synchronousqueue.md)：理解零容量、DATA/REQUEST 配对以及公平与非公平传输器。
3. [断点实验手册](./debug-lab.md)：使用闸门稳定触发满队列等待、容量边界通知和直接交接。

## JDK 8、17、21 的整体边界

`BlockingQueue` 的公开四组方法、null 禁止和内存一致性保证在三个版本中保持稳定。`ArrayBlockingQueue` 的单锁双条件、`LinkedBlockingQueue` 的双锁与原子计数也保持同一总体设计。

变化最大的类是 `SynchronousQueue`：JDK 8/17 仍以独立的 `TransferStack` 和 `TransferQueue` 实现非公平与公平模式，JDK 21 则让内部 `Transferer` 复用 `LinkedTransferQueue` 的 FIFO 传输并补充 LIFO 路径。跨版本断点必须先确认当前 SDK 的真实内部类和方法名；业务代码只能依赖公开交接语义。
