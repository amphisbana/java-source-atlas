# ForkJoinPool：分治任务与工作窃取

`ForkJoinPool` 面向可以递归拆分的计算：一个大任务 fork 出可并行的子任务，当前线程继续处理一部分，空闲 worker 从其他 worker 的队列窃取剩余工作，最后通过 join 汇总结果。

本专题以 **OpenJDK 8u** 为主基线，重点阅读：

- `ForkJoinPool` 与内部 `WorkQueue`；
- `ForkJoinTask` 的 `fork/doExec/join/doJoin`；
- `RecursiveTask` 的结果式分治；
- `ManagedBlocker` 与阻塞补偿。

ForkJoinPool 不是“换一种线程池就自动更快”。只有任务可拆分、子任务足够独立、粒度合适且阻塞较少时，工作窃取才容易发挥优势。

<TopicStudyPanel topic-id="openjdk8-java-util-concurrent-forkjoinpool" />

## 四个核心角色

| 角色 | 职责 | 源码观察点 |
| --- | --- | --- |
| `ForkJoinPool` | 接收外部提交、创建和唤醒 worker、扫描任务、管理活跃度 | `externalPush`、`scan`、`awaitJoin` |
| `ForkJoinWorkerThread` | 绑定一个 pool 和一条工作队列 | `workQueue`、`runWorker` |
| `WorkQueue` | 保存本 worker fork 的任务，也作为其他 worker 的窃取目标 | `base`、`top`、`push/pop/poll` |
| `ForkJoinTask` | 表示可执行、可 join、可异常完成的轻量任务 | `fork`、`doExec`、`doJoin`、`join` |

`RecursiveAction` 用于无结果任务，`RecursiveTask<V>` 用于返回结果的任务，`CountedCompleter` 用 pending count 表达更灵活的完成图。本阶段先把普通 fork/join 主线讲清，再标出 `CountedCompleter` 的专用帮助分支。

## 一次分治的推荐骨架

```java
protected Long compute() {
    if (size <= threshold) {
        return computeDirectly();
    }

    RangeSumTask right = splitRight();
    right.fork();
    long leftResult = splitLeft().compute();
    return leftResult + right.join();
}
```

这里通常只 fork 一侧，当前 worker 直接计算另一侧：

1. 减少两个子任务都入队的调度开销。
2. 保证当前 worker 有立即可做的工作。
3. fork 的任务留在队列里，空闲 worker 可以从另一端窃取。
4. 如果没人窃取，后续 join 可能把它从本地 top 撤回并直接执行。

立即执行 `task.fork().join()` 往往没有创造有效并行，只增加入队、出队和状态检查成本。

## 动画：拆分、窃取、帮助与汇总

动画使用默认 LIFO 本地模式，展示一条具体但不唯一的合法执行路径：

1. 外部线程把 Root 提交到 pool 的共享提交队列。
2. W1 从共享队列取得 Root。
3. Root fork Right，W1 直接计算 Left。
4. W2 从 W1 的 `base` 窃取 Right。
5. Right 再 fork R2，W2 直接计算 R1。
6. W1 join Right 时发现目标已被 W2 窃取，于是沿帮助链取得 R2。
7. W2 汇总 Right，W1 再汇总 Root。

<ForkJoinPoolAnimation />

动画中的 W1/W2 只是讲解标签，不是可依赖的线程名。真实 victim 由扫描和竞争时机决定，任务也可能完全由一个 worker 完成。

## 外部提交与任务内部 fork 不是同一路径

```text
外部线程 pool.submit(task)
  → ForkJoinPool.externalPush(task)
  → 共享 submission WorkQueue
  → worker 从 base 端 poll

pool 内 worker 执行 task.fork()
  → 当前 ForkJoinWorkerThread.workQueue.push(task)
  → 当前 worker 的 top 端

普通外部线程直接 task.fork()
  → ForkJoinPool.commonPool().externalPush(task)
```

最后一条很容易踩坑：`ForkJoinTask.fork()` 没有 pool 参数。在 ForkJoin worker 内，它使用当前 worker 所属 pool；在普通线程中，它进入 commonPool。要把根任务明确交给自建 pool，应调用 `pool.invoke/submit/execute`。

## 为什么 owner LIFO、stealer FIFO

默认模式下，本 worker 从 `top - 1` 取最近 fork 的任务，其他 worker 从 `base` 取最早任务：

- owner LIFO 有利于深度优先，最近子问题通常仍在缓存中；
- stealer FIFO 倾向拿走更老、更大的上层子树，更可能获得足够工作量；
- 两端操作降低 owner 与 stealer 争用同一槽位的概率。

这是一种调度倾向，不保证业务任务完成顺序。详细见 [WorkQueue：base、top 与工作窃取](./workqueue-steal.md)。

## commonPool 的边界

`ForkJoinPool.commonPool()` 是 JVM 进程内共享实例，常被以下能力复用：

- 外部线程直接调用 `ForkJoinTask.fork()`；
- 未指定执行器的 `CompletableFuture.*Async`；
- 并行 Stream。

JDK 8 可通过系统属性配置 commonPool 的并行度、线程工厂和异常处理器。它是进程级配置，读取时机和运行环境都会影响结果，因此库代码不应擅自修改，测试也不应断言固定并行度或固定线程名。

`shutdown()` 和 `shutdownNow()` 对 commonPool 的运行状态没有作用；进程退出也不会等待未完成的 commonPool 异步任务。需要隔离容量、生命周期或阻塞风险时，使用自建 pool 并明确关闭。

## asyncMode 改变什么

四参数构造器的 `asyncMode=true` 把 worker 的本地任务选择改为 FIFO，更适合“fork 后通常不 join”的事件式任务：

```java
new ForkJoinPool(
        parallelism,
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        null,
        true);
```

| 模式 | owner 本地选择 | stealer 选择 | 典型用途 |
| --- | --- | --- | --- |
| 默认 `false` | top 端 LIFO | base 端 FIFO | 递归分治并 join |
| `true` | base 端 FIFO | base 端 FIFO | 通常不 join 的异步事件任务 |

`asyncMode` 不会把任务变成异步 API，也不改变 `join` 的完成语义；它只改变本地排队选择策略。

## 监控值为什么只能作为估计

`getStealCount`、`getQueuedTaskCount`、`getQueuedSubmissionCount`、`getRunningThreadCount` 等方法在并发变化的队列上采样。它们适合趋势监控和调优，不适合业务同步：

- 读取后任务可能立即被 push、pop 或 steal；
- 某些计数会延迟汇总；
- `getPoolSize()` 可能因补偿线程大于目标 parallelism；
- “当前队列为零”不表示整个计算图已经完成。

等待任务结果应使用 `join/get/invoke`，等待 pool 静止可使用 `awaitQuiescence`，关闭自建 pool 则使用 `shutdown` 与 `awaitTermination`。

## 阅读路径

1. [WorkQueue：base、top 与工作窃取](./workqueue-steal.md)
2. [ForkJoinTask：fork、执行、join 与异常](./fork-join.md)
3. [ManagedBlocker：阻塞补偿的能力边界](./managed-blocking.md)
4. [断点实验手册](./debug-lab.md)

## JDK 8、17、21 实现边界

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| 队列核心概念 | `base/top/array`，owner 与 stealer 两端操作 | 概念保留，扫描、队列与 ctl 协议重写 | 概念保留，内部方法继续演进 |
| 原子访问 | `sun.misc.Unsafe` | 主要改为 `VarHandle` | ForkJoin 实现改用 `jdk.internal.misc.Unsafe`，避免初始化依赖 |
| 异常保存 | 全局弱引用异常表 | 每任务辅助节点等结构重写 | 延续新版任务辅助结构 |
| 自建 pool 构造 | 最高到四参数构造器 | 增加 core/max/minRunnable、saturate、keepAlive 等高级参数 | 延续高级配置 |
| 公开主契约 | fork/join、工作窃取、ManagedBlocker | 保持 | 保持 |

不要通过反射依赖 `ctl` 位布局、WorkQueue 在数组中的奇偶位置、私有状态值或具体帮助方法。跨版本稳定的是公开任务结果、异常、取消、模式查询和执行器生命周期。

## 源码与许可证

- `ForkJoinPool.java`：<https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ForkJoinPool.java>
- `ForkJoinTask.java`：<https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ForkJoinTask.java>
- OpenJDK 许可证说明见 [源码许可证与引用边界](/reference/source-license.md)。
