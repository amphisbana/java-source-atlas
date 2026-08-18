# ScheduledThreadPoolExecutor：周期、异常、取消与关闭

周期任务不是提前复制很多份到队列。队列中只有同一个逻辑任务的当前轮次：一轮成功结束后更新 `time`，再把 `outerTask` 放回延迟堆。这一点同时解释了不重入、异常停止和取消竞态。

## run 的三条分支

`ScheduledFutureTask.run()` 先判断任务类型和当前线程池状态：

```text
periodic = period != 0

当前状态与关闭策略不允许运行
  → cancel(false)

单次任务
  → FutureTask.run()

周期任务
  → FutureTask.runAndReset()
      ├─ false：已取消或本轮抛异常，不再排队
      └─ true：setNextRunTime()
               → reExecutePeriodic(outerTask)
```

`runAndReset()` 成功执行 Callable/Runnable，但不把 Future 置为正常完成状态，这样同一个 Future 才能进入下一轮。它只有在任务正常返回且期间没有被取消时才返回 `true`。

## period 正负如何选择下一时刻

`setNextRunTime()` 只看 `period` 的符号：

```text
period > 0  固定频率：time = time + period
period < 0  固定延迟：time = triggerTime(-period)
```

第一种基于上一轮的**计划时间**，第二种在本轮已经结束后读取当前 `nanoTime` 并加延迟。

### 固定频率不是“每隔 period 新建一个任务”

假设 period=3 秒，每轮执行 5 秒：

```text
计划时刻：0 ----- 3 ----- 6 ----- 9
实际执行：[第1轮 0..5][第2轮 5..10][第3轮 10..15]
```

第一轮结束时，下一 `time` 从 0 加到 3，已经逾期，因此重新入堆后可以立即被取出；第二轮同理。这叫追赶计划，不是同一任务并发执行。

OpenJDK 的类契约明确保证同一周期任务的连续执行不重叠。即使线程池有多个 worker，后续轮次也要等当前轮 `runAndReset()` 正常返回后才入队；不同轮次可能由不同线程执行，前一轮动作 happens-before 后一轮。

### 固定延迟从完成时刻重新计算

假设 delay=3 秒，每轮仍执行 5 秒：

```text
实际执行：[第1轮 0..5]---等待3秒---[第2轮 8..13]---等待3秒---
```

执行时间会整体推迟后续轮次，不会追赶初始日程。适合需要“上一轮完成后留出冷却时间”的轮询。

## 周期任务抛异常为什么悄悄停止

用户命令抛出异常时，`FutureTask.runAndReset()` 把异常保存进 Future 并返回 `false`。STPE 因此不会调用 `setNextRunTime`，也不会重新入队：

```text
第 N 轮抛异常
  → Future 进入异常完成状态
  → 后续轮次全部停止
  → ScheduledFuture.get() 抛 ExecutionException
```

异常不会从 worker 线程直接冒出，所以 `afterExecute` 的 `Throwable` 参数通常仍为 `null`。如果业务要求失败后继续，应在任务内部捕获、记录并明确决定是否吞掉异常；不要无条件吞掉所有错误，否则既失去告警，也可能让永久失败任务无限重试。

## cancel 的两个层次

`cancel(mayInterruptIfRunning)` 首先委托 `FutureTask.cancel` 改变状态：

- 尚未运行：以后 `run` 不会执行用户命令；
- 正在运行且参数为 `true`：尝试中断执行线程；任务是否及时结束取决于它是否响应中断；
- 已完成、已异常或已取消：返回 `false`，状态不再改变。

随后只有同时满足以下条件才立即从堆删除：

```text
本次 cancel 成功
&& removeOnCancel == true
&& heapIndex >= 0
```

## removeOnCancel 默认为什么是 false

JDK 8 默认保留已取消任务，直到它到达堆首并到期，便于队列检查和监控；大量远期任务被取消时，这也会长期保留任务对象和关联数据。

```java
ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
executor.setRemoveOnCancelPolicy(true);
```

开启后，内部任务利用 `heapIndex` 直接定位并修复堆，通常是 O(log n)。这是长期运行、频繁取消定时任务时常用的内存保留防护。若自定义 `decorateTask` 返回外层包装，取消是否能命中内部快速路径取决于包装对象和委托方式，需要用真实队列行为验证。

`purge()` 也能批量清理已取消任务，但它是一次主动扫描，不等于取消时立即移除。

## 取消与周期重新入队的竞态

周期任务正常返回与外部取消可以并发发生。关键防线不只有 `runAndReset`：

1. `runAndReset` 检查 Future 状态，取消可能让它返回 `false`。
2. 若已经算出下一时刻，`reExecutePeriodic` 入队前检查当前池状态。
3. 入队后再次检查关闭策略；不再允许时移除并取消。
4. 已取消任务即使暂时留在堆中，之后 `run()` 也不会再次执行用户代码。

因此队列快照短暂看到一个已取消包装对象，不表示用户任务还会运行。

## reExecutePeriodic 与首次提交的差异

首次 `delayedExecute` 在池已关闭时调用拒绝策略，因为这是调用者试图提交新任务。周期任务的后续轮次不是新提交：`reExecutePeriodic` 在状态不允许时不调用拒绝策略。

允许继续时，它同样执行“入队、再次检查、必要时移除取消、ensurePrestart”四步，处理重新入队与关闭并发发生的窗口。

这里不能把“不再入队”直接等同于“Future 一定取消”，因为运行中关闭存在明确版本差异：

```text
周期任务第一轮已经进入 runAndReset
  → 另一线程调用默认 shutdown()
  → 第一轮正常返回
  → reExecutePeriodic 发现周期策略不允许继续
```

- **JDK 8u**：首次 `canRunInCurrentRunState(true)` 为 false 时方法直接返回，没有调用 `cancel`。任务不再运行，线程池可以终止，但该 `ScheduledFuture` 仍停在 `NEW`，`isDone()` 与 `isCancelled()` 都为 false，无截止的 `get()` 可以永久等待。
- **JDK 17/21**：方法在不允许重新入队的路径补充 `task.cancel(false)`，同一场景 Future 进入取消终态，`get()` 抛 `CancellationException`。

这与 `onShutdown()` 取消**仍在队列里**的周期任务是两条路径。跨版本代码不应把 `shutdown()` 当成取得周期 Future 终态的唯一手段；生命周期所有者需要保留 Future 并在必要时显式取消。

## shutdown 的三项策略

| 策略 | JDK 8 默认值 | `shutdown()` 后的行为 |
| --- | ---: | --- |
| `executeExistingDelayedTasksAfterShutdownPolicy` | `true` | 已进入队列的单次延迟任务仍可在到期后执行 |
| `continueExistingPeriodicTasksAfterShutdownPolicy` | `false` | 周期任务不再继续下一轮 |
| `removeOnCancelPolicy` | `false` | 控制取消时是否立即移除，不直接决定能否在关闭后运行 |

配置入口分别是：

```java
executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);
executor.setRemoveOnCancelPolicy(true);
```

开启“关闭后继续周期任务”会让普通 `shutdown()` 无法自然终止，除非任务自行取消、抛异常、把策略改回 `false`，或外部调用 `shutdownNow()`。这项策略必须配套明确的生命周期所有者。

## onShutdown 如何筛选队列

父类进入关闭流程时调用 STPE 覆盖的 `onShutdown()`：

- 两类任务都不保留：取消队列快照中的所有定时 Future，然后清空队列。
- 只保留其中一类：遍历 `q.toArray()` 快照，移除并取消策略不允许的任务和已经取消的任务。
- 最后调用 `tryTerminate()`，让父类在线程与队列条件满足时推进到 `TERMINATED`。

使用数组快照是为了避免边遍历边删除带来的迭代问题。正在执行的任务不在队列快照中，它结束后是否重新入队还会经过 `run` 和 `reExecutePeriodic` 的状态检查。

以上筛选条件以 OpenJDK 8u 为准。JDK 17、21 在“只禁用既有单次延迟任务”的分支多检查了 `getDelay(NANOSECONDS) > 0`：尚未到期的单次任务会取消，已经到期但还没获得 worker 的任务会保留执行。周期任务的策略判断不受这个差异影响。

## shutdownNow 的边界

`shutdownNow()` 推进到 STOP，尝试中断 worker，并返回尚未开始的队列元素。返回元素是 `RunnableScheduledFuture` 包装，不一定是原始命令。

父类这里只执行 `drainQueue()`，**不会逐个调用这些 Future 的 `cancel`**。因此一个被返回、从未开始的 `ScheduledFuture` 仍可能保持 `NEW`：`isDone()` 和 `isCancelled()` 都为 false，无截止的 `get()` 仍会等待。调用方若保留了这些 Future，应根据业务语义显式取消，或把返回列表交给负责重排任务的组件处理。

与普通线程池相同，中断只是协作信号：忽略中断的用户任务仍可能继续运行。`shutdownNow()` 不受“关闭后执行延迟任务”或“继续周期任务”策略保护，STOP 状态不允许这些任务继续调度。

## 选择固定频率还是固定延迟

| 需求 | 更合适的入口 | 风险 |
| --- | --- | --- |
| 尽量贴近既定节拍 | `scheduleAtFixedRate` | 慢任务会连续追赶，几乎没有间隔 |
| 每轮完成后留出间隔 | `scheduleWithFixedDelay` | 执行耗时会推迟整体节奏 |
| 只在未来执行一次 | `schedule` | 大量远期任务需关注保留和取消清理 |
| 需要日历语义或持久化补偿 | 通常不是裸 STPE | `nanoTime` 调度不表达时区、错过触发和进程重启恢复 |

STPE 提供进程内相对时间调度，不是持久化调度系统。任务是否允许重复、失败如何处理、应用重启后是否补跑，都应由上层明确设计。
