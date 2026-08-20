# AQS 与 ReentrantLock：独占、共享与条件协作

`AbstractQueuedSynchronizer`（AQS）不直接定义“锁”或“许可”，而是提供同步状态、FIFO 等待队列以及独占/共享获取模板。`ReentrantLock`、`CountDownLatch` 和 `Semaphore` 都把公开 API 交给各自的内部 `Sync`，但对同一个 `state` 给出完全不同的业务含义。

[打开 JDK 8 / 17 / 21 版本对比 →](/jdk/version-comparison/?topic=aqs-reentrantlock)，可并排核对 Node 状态位、统一 acquire、ConditionNode 和 ReentrantLock 初始快速路径的变化。

<TopicStudyPanel topic-id="openjdk8-reentrantlock-aqs" />

```text
ReentrantLock
  └─ Sync extends AbstractQueuedSynchronizer
       ├─ NonfairSync
       └─ FairSync

CountDownLatch / Semaphore
  └─ Sync extends AbstractQueuedSynchronizer
       └─ 使用共享获取与释放
```

## AQS 使用的模板方法模式

AQS 固定实现排队、阻塞、唤醒、取消和中断处理；同步器子类只定义资源语义：

- `tryAcquire`
- `tryRelease`
- `tryAcquireShared`
- `tryReleaseShared`
- `isHeldExclusively`

这是模板方法模式的实际应用：框架控制稳定流程，子类提供少量策略钩子。`ReentrantLock` 使用独占获取与释放，`CountDownLatch` 和 `Semaphore` 使用共享获取与释放。

## 同一个 state 在三个同步器中的含义

| 同步器 | `state` 表示 | 获取成功条件 | 释放动作 |
| --- | --- | --- | --- |
| `ReentrantLock` | 当前线程的重入层数 | 空闲，或当前线程再次重入 | 每次 `unlock` 减一，归零才唤醒后继 |
| `CountDownLatch` | 尚未完成的计数 | `state == 0` | `countDown` 减一，只有归零才传播 |
| `Semaphore` | 当前可用许可数 | 有足够许可可扣减 | `release` 增加许可并触发共享传播 |

所以不能把 AQS 的 `state` 统一解释为“锁是否被占用”。AQS 只负责原子读写和排队协议，数值语义由同步器子类决定。

## state 在 ReentrantLock 中表示什么

| state | 含义 |
| ---: | --- |
| 0 | 未加锁 |
| 1 | 当前线程持有一次 |
| n | 当前线程重入 n 次 |

独占拥有者线程单独保存在 `exclusiveOwnerThread`。首次成功获取时设置拥有者；每次重入增加 state；每次 `unlock` 减一，减到 0 才清空拥有者并真正释放。

因此每次成功 `lock` 都必须有对应的 `unlock`：

```java
lock.lock();
try {
    // 临界区
} finally {
    lock.unlock();
}
```

## 非公平锁路径

默认构造使用 `NonfairSync`：

```text
lock()
  ├─ CAS state: 0 → 1 成功
  │    └─ 设置 owner，直接返回
  └─ 失败 → AQS.acquire(1)
       └─ nonfairTryAcquire(1)
       └─ 失败后进入同步队列
```

新到线程可以在队列线程被唤醒但尚未 CAS 成功时抢到锁，提高吞吐，但等待顺序不稳定。

## 公平锁路径

公平锁的 `tryAcquire` 在空闲时额外检查 `hasQueuedPredecessors()`。只有当前线程前面没有已排队线程，才尝试 CAS 获取。

公平表示尽量按最长等待顺序授予锁，不等于操作系统调度绝对公平，也不保证线程完成顺序。

::: warning 公平锁的 tryLock 特例
无参数 `tryLock()` 使用非公平尝试，即使锁由公平模式构造，也允许在当前空闲时直接插队。需要遵守公平策略的定时尝试可以使用 `tryLock(0, TimeUnit.SECONDS)`。
:::

## lock、lockInterruptibly 与 tryLock

| API | 等待中断 | 超时 | 获取失败结果 |
| --- | --- | --- | --- |
| `lock()` | 获取过程中不抛中断；成功后恢复中断标记 | 否 | 持续等待 |
| `lockInterruptibly()` | 是 | 否 | 抛 `InterruptedException` |
| `tryLock()` | 不等待 | 立即 | 返回 false |
| `tryLock(timeout, unit)` | 是 | 是 | 超时返回 false |

选择 API 时要依据取消和超时语义，而不是只替换方法名。
