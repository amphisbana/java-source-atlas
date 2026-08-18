# ManagedBlocker：阻塞补偿的能力边界

ForkJoinPool 能理解 `join`，因为它知道目标 ForkJoinTask 和依赖链；它无法自动理解任意数据库调用、外部锁、阻塞队列或网络 I/O。`ForkJoinPool.ManagedBlocker` 是把“当前 worker 可能阻塞”显式告知 pool 的协议。

## 两个方法的契约

```java
interface ManagedBlocker {
    boolean isReleasable();
    boolean block() throws InterruptedException;
}
```

| 方法 | 要求 |
| --- | --- |
| `isReleasable()` | 快速、非阻塞、可重复调用；已经无需阻塞时返回 true |
| `block()` | 只在前一次 `isReleasable()` 返回 false 后调用；完成阻塞条件后返回 true，否则允许再次循环 |

`isReleasable` 可以顺便尝试非阻塞获取，例如 `queue.poll()` 或 `lock.tryLock()`，但不能在里面偷偷执行长期阻塞。

## managedBlock 的公开循环

普通非 ForkJoin worker 调用时，行为近似：

```text
do {
  if (isReleasable()) break
} while (!block())
```

ForkJoin worker 调用时，在真正进入 `block()` 前先让所属 pool 尝试补偿；补偿成功后执行相同的 releasable/block 循环，最后恢复活跃计数。

因此 `block()` 可能：

- 一次都不调用，因为条件已经可用；
- 调用一次并返回 true；
- 多次返回 false，直到某轮完成；
- 抛出 `InterruptedException`，由 `managedBlock` 传播。

实现必须能承受这些调用次数，不能把“只调用一次”作为隐含前提。

## JDK 8 tryCompensate 做什么

`tryCompensate` 不是简单的“线程数加一”。它根据 pool 当前 ctl 和 worker 状态选择：

1. 有空闲 worker：优先唤醒它接替工作。
2. 总线程已达到目标，但仍有其他活跃 worker且当前队列为空：减少活跃计数，允许当前 worker 阻塞。
3. 需要保持 parallelism 且资源允许：创建补偿 worker。
4. 状态不稳定或发生竞争：返回失败，调用方重试。
5. 达到实现线程上限或 commonPool spare 上限：可能抛 `RejectedExecutionException`。

离开阻塞区后源码恢复活跃计数。JDK 17/21 重写了 ctl 和返回编码，还为自建 pool 提供更细的 core/max/minRunnable/saturate 配置，但“先尝试释放或补充计算能力，再允许阻塞”的目标保持不变。

## 为什么不是无限扩容

补偿受到多重约束：

- 实现的最大线程上限；
- commonPool 的最大 spare 限制；
- 自建 pool 的 maximumPoolSize 和 saturate 策略（新版 JDK）；
- 线程工厂能否成功创建 worker；
- 系统线程和内存资源；
- pool 是否正在关闭。

`managedBlock` 文档使用的是“possibly arranges for a spare thread”。它提供补偿机会，不保证每次阻塞都创建新线程，也不保证吞吐完全不下降。

## 阻塞队列适配示例

```java
final class QueueTaker<E> implements ForkJoinPool.ManagedBlocker {
    private final BlockingQueue<E> queue;
    private E item;

    public boolean isReleasable() {
        return item != null || (item = queue.poll()) != null;
    }

    public boolean block() throws InterruptedException {
        if (item == null) {
            item = queue.take();
        }
        return true;
    }
}
```

调用方在 `managedBlock(blocker)` 返回后读取 item。真实实现还应考虑：null 是否是合法结果、重复调用、取消、中断处理和资源关闭。

专题实验使用 CountDownLatch 版 blocker，能稳定在断点处观察协议，但它不是建议在生产中再包装一层闩锁。

## ManagedBlocker 不是什么

### 不是业务锁替代品

它不会提供互斥、条件队列、公平性或事务边界。真正的同步仍由 Lock、Condition、BlockingQueue 等机制完成；ManagedBlocker 只向 ForkJoinPool 描述可能阻塞。

### 不是死锁修复器

如果任务持有锁 A 等待锁 B，另一个任务持有 B 等待 A，增加 spare worker 也无法打破依赖环。线程池饥饿与业务死锁是不同问题。

### 不是无限线程承诺

大量长期 I/O 任务即使都正确使用 managedBlock，也可能制造大量补偿压力。持续阻塞工作通常更适合独立、有界、可观测的执行器，或新版本中适合 I/O 的并发模型。

### 不是任意异步等待的统一包装

若依赖本身能表达为 ForkJoinTask 子任务，优先使用 fork/join 的帮助协议。把 join 改成“在 ManagedBlocker 中等待同一任务”反而丢失依赖信息。

## join 的补偿与 managedBlock 的关系

worker 的 `awaitJoin` 在帮助无果、确实需要等待目标时也会调用内部 `tryCompensate`。因此普通 ForkJoinTask join 已经接入 pool 的协作和补偿协议，不需要业务再套 ManagedBlocker。

ManagedBlocker 主要服务 pool 无法识别的阻塞源，例如：

- 从 BlockingQueue 等待外部生产者；
- 等待不可改造成 ForkJoinTask 的旧同步 API；
- 获取可能长期等待的外部锁；
- 某些可中断 I/O 适配层。

## 中断与清理

`managedBlock` 声明 `InterruptedException`，但是否抛出由 blocker 的 `block()` 实现决定。调用方必须：

- 按业务语义传播或恢复中断标记；
- 在 finally 中释放已经取得的锁和资源；
- 避免 blocker 返回后遗留“已取得但无人消费”的资源；
- 给外部操作设置独立超时，不能只依赖测试或线程池关闭。

## 测试应该断言什么

稳定的公开契约包括：

- 条件未满足时调用 `block()`；
- 条件满足后 `managedBlock` 返回；
- `block()` 抛出的中断可以传播；
- 自建 pool 最终可以关闭清理。

不要把以下内部现象写成跨版本单元测试：

- 必定创建一个新 worker；
- pool size 精确增加 1；
- follower 必定在多少毫秒内运行；
- 补偿 worker 的线程名；
- commonPool 可用 spare 的固定数量。

这些值受 JDK 版本、并行度、运行时配置和竞争时机影响。实验可以观察，测试只能依赖公开保证。
