# ScheduledThreadPoolExecutor 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.concurrent.ScheduledThreadPoolExecutorDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ScheduledThreadPoolExecutorDebugLab
```

案例只依赖 Java 8 公开 API。源码内部字段用于断点观察，不通过反射读取，因此在 JDK 17、21 下不需要 `--add-opens`。

## 场景一：零延迟命令仍被包装

运行 `observeZeroDelayWrapping()`：

1. 用闩锁占住唯一 worker。
2. 调用 `execute(originalCommand)`。
3. 从 `getQueue().peek()` 观察排队对象。
4. 比较它与原始命令的身份和接口类型。

预期：排队对象不是原始 `Runnable`，而是实现 `RunnableScheduledFuture` 的零延迟包装。

推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `ScheduledThreadPoolExecutor.execute` | `command` |
| `schedule(Runnable,long,TimeUnit)` | `delay`、`t` |
| `decorateTask(Runnable,...)` | 原始命令和内部任务 |
| `delayedExecute` | `task`、队列和关闭状态 |

## 场景二：固定频率不会重入

运行 `observeFixedRateWithoutOverlap()`。实验池有两个 worker，周期远短于第一轮受控阻塞时间：

- 第一轮进入后停在 `releaseFirstRun` 闩锁；
- 主线程用带截止时间的闩锁确认第二轮没有并发进入；
- 释放第一轮后，允许第二轮运行并取消 Future；
- `maxActive` 应保持为 1。

推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `ScheduledFutureTask.run` | `periodic`、Future 状态 |
| `FutureTask.runAndReset` | `state`、`runner`、执行结果 |
| `setNextRunTime` | `time`、`period` |
| `reExecutePeriodic` | `outerTask`、当前运行状态 |

断在第一轮用户代码时，队列中不会预先出现第二份相同任务；只有第一轮正常返回后才重新入堆。

## 场景三：异常停止周期任务

运行 `observePeriodicException()`。任务第一轮直接抛出异常，主线程对返回 Future 调用带超时的 `get()`。

预期：

- `get()` 抛出的 `ExecutionException` 保存原始原因；
- 计数保持为 1；
- `runAndReset()` 返回 false，调用链不会进入 `setNextRunTime`。

调试 `afterExecute` 时还可确认第二个 `Throwable` 参数通常是 `null`，因为异常已被 Future 捕获。

## 场景四：取消任务是否留在堆中

运行 `observeRemoveOnCancel()` 两次取消远期任务：

1. 使用默认策略取消，Future 已取消但队列仍可能保留包装对象。
2. 执行 `purge()` 清理，再开启 `setRemoveOnCancelPolicy(true)`。
3. 取消第二个远期任务，队列立即删除它。

推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `ScheduledFutureTask.cancel` | `cancelled`、`removeOnCancel`、`heapIndex` |
| `DelayedWorkQueue.remove` | `i`、`s`、`replacement` |
| `siftDown` / `siftUp` | `k`、`key`、子节点或父节点 |

## 场景五：shutdown 默认筛选

运行 `observeShutdownPolicies()`：

- 一个受闩锁控制的普通任务占住 worker；
- 队列中同时放入零延迟单次任务和周期任务；
- 调用 `shutdown()` 后检查 Future 状态；
- 默认策略保留单次延迟任务、取消周期任务；释放 worker 后单次任务完成。

推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `onShutdown` | `keepDelayed`、`keepPeriodic`、队列快照 |
| `canRunInCurrentRunState` | `periodic` 和两个策略字段 |
| `tryTerminate` | 线程池状态、worker 数、队列是否为空 |

## 场景六：运行中周期任务的 shutdown 版本差异

运行 `observeRunningPeriodicShutdownDifference()`：

1. 周期任务第一轮进入后等待闸门；
2. 主线程在它仍运行时调用默认 `shutdown()`；
3. 放行第一轮正常返回，观察 `reExecutePeriodic`；
4. JDK 8u 中 Future 保持未完成、带超时 `get` 超时；JDK 17/21 中 Future 被取消，`get` 抛 `CancellationException`。

这个场景与场景五不同：任务已经离开队列，因此 `onShutdown()` 的队列快照看不到它。断点应放在 `ScheduledFutureTask.run` 正常返回后的 `setNextRunTime` 与 `reExecutePeriodic` 首次状态检查。

## 场景七：shutdownNow 返回列表不等于取消 Future

运行 `observeShutdownNowFutureState()`。实验提交一个一天后才到期的任务并立即调用 `shutdownNow()`，确认：

- 返回列表包含对应的 `ScheduledFuture` 包装；
- Future 仍可能 `isDone=false`、`isCancelled=false`；
- 带超时 `get` 会超时；
- 实验最后显式 `cancel(false)`，避免把未完成状态带出场景。

推荐在 `ThreadPoolExecutor.shutdownNow` 的 `drainQueue()` 前后观察队列，并确认父类没有逐项调用 `Future.cancel`。

## DelayedWorkQueue.take 的观察顺序

用至少两个核心线程提交远期任务，在 `take()` 中只挂起当前线程：

1. 队列空时观察 `available.await()`。
2. 第一个 worker 读取正 `delay` 并把自己写入 `leader`。
3. 第二个 worker 发现已有 leader，执行无期限 `await()`。
4. 再提交一个更早任务，观察 `offer` 把 `leader` 清为 null 并 `signal()`。
5. 到期任务通过 `finishPoll` 离开后，观察 `heapIndex=-1` 和下一次 leader 交接。

不要把断点造成的额外延迟当成固定频率算法本身。调试器暂停 10 秒后，多个计划时刻都可能已经过去，固定频率任务会在恢复后连续追赶。

## 测试覆盖

自动化测试位于 `ScheduledThreadPoolExecutorBehaviorTest`，覆盖：

- `execute` 的零延迟 Future 包装；
- 长任务下固定频率不重入；
- 固定延迟从完成后重新等待；
- 周期异常停止后续轮次；
- `removeOnCancel` 的立即清理；
- `shutdown` 默认策略和显式反向策略；
- 运行中周期任务在 JDK 8 与 JDK 17+ 的关闭终态差异；
- `shutdownNow` 返回但不自动取消未开始的定时 Future。

每个并发等待都有超时，测试结束统一关闭线程池；闩锁负责确定先后关系，不用固定 `sleep` 猜测线程是否已经启动。

## 调试注意

- `System.nanoTime()` 只能比较差值，不要把 `time` 当作墙上时钟打印成日期。
- `getQueue()` 是监控快照，不是外部修改调度计划的业务接口。
- 在堆锁或 `FutureTask` 状态切换处断点时只挂起当前线程，避免制造假死。
- 周期任务的 `Future.get()` 正常情况下不会返回；只在取消或异常后结束，实验必须使用带超时版本。
- 最终都调用 `shutdownNow()` 和 `awaitTermination()`，避免调试失败后残留非守护 worker。
