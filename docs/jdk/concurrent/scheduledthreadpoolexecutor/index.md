# ScheduledThreadPoolExecutor：从提交到定时任务

`ScheduledThreadPoolExecutor`（下文简称 STPE）在 `ThreadPoolExecutor` 之上补充了延迟执行和周期执行能力。它的关键并不是“工作线程睡到某个时间”，而是把所有命令包装成带触发时间的 `RunnableScheduledFuture`，放进专用延迟堆；工作线程只从堆首取得已经到期的任务。

本文以 **OpenJDK 8u** 为源码基线。对应源码为 `java.util.concurrent.ScheduledThreadPoolExecutor`，路径是 `jdk/src/share/classes/java/util/concurrent/ScheduledThreadPoolExecutor.java`。

<TopicStudyPanel topic-id="openjdk8-java-util-concurrent-scheduledthreadpoolexecutor" />

## 它与普通线程池的结构差异

构造器最终固定传给父类一组特殊参数：

```text
corePoolSize = 调用者指定
maximumPoolSize = Integer.MAX_VALUE
keepAliveTime = 0ns
workQueue = DelayedWorkQueue
```

但这不表示 STPE 会按需扩到 `Integer.MAX_VALUE` 个线程。`DelayedWorkQueue` 是逻辑无界队列，任务总能入队，因此普通 `ThreadPoolExecutor.execute` 中“队列满后扩到 maximumPoolSize”的分支不会出现。STPE 实际主要由 `corePoolSize` 控制并发度，调整 `maximumPoolSize` 通常没有实际作用。

不建议把 `corePoolSize` 设为 0，也不建议让所有核心线程超时。否则队列虽有任务，却可能没有线程在到期时负责唤醒执行。

## 四类调度入口

| 入口 | 内部 `period` | 下一次触发时间 | 返回值语义 |
| --- | ---: | --- | --- |
| `schedule(command, delay, unit)` | `0` | 只执行一次 | 成功、失败或取消后完成 |
| `scheduleAtFixedRate(command, initialDelay, period, unit)` | 正数 | 上一次**计划时间**加 period | 取消或异常前持续运行 |
| `scheduleWithFixedDelay(command, initialDelay, delay, unit)` | 负数 | 上一次**执行完成后**加 delay | 取消或异常前持续运行 |
| `execute/submit` | `0` | 当前时间，即零延迟 | 仍使用定时任务包装 |

`period` 的正负是内部编码，调用者传给两个周期 API 的参数都必须大于 0。`scheduleWithFixedDelay` 在构造 `ScheduledFutureTask` 时才把纳秒值取负。

## schedule 的完整调用链

一次单次延迟提交经过：

```text
schedule(command, delay, unit)
  → triggerTime(delay, unit)
      → 负 delay 按 0 处理
      → System.nanoTime() + delayNanos
  → new ScheduledFutureTask(command, null, triggerTime)
  → decorateTask(command, scheduledTask)
  → delayedExecute(decoratedTask)
      → DelayedWorkQueue.add(task)
      → 并发关闭后二次检查
      → ensurePrestart()
```

`time` 使用 `System.nanoTime()` 所在的单调时间域，只用于计算相对延迟，不是可转换为日期的时间戳。任务“到期”只表示允许被取出，并不承诺实时调度；线程不足、前序任务阻塞和操作系统调度都可能让实际开始时间更晚。

### delayedExecute 为什么先入队再启动线程

任务很可能尚未到期，不能把它作为 `firstTask` 直接交给新 worker，否则 `runWorker` 会立即调用它。源码先把任务加入 `DelayedWorkQueue`，再调用父类 `ensurePrestart()` 保证至少有 worker 进入队列的 `take()` 等待。

加入队列与线程池关闭不是一个原子动作，因此源码还要二次检查：

```text
提交线程：检查未关闭 → queue.add(task) → 再检查状态
关闭线程：                 shutdown() / shutdownNow()
```

如果复查发现当前关闭状态和策略不再允许该任务运行，源码尝试 `remove(task)`，移除成功后执行 `task.cancel(false)`。否则保留任务并确保有 worker。

## ScheduledFutureTask 的五个关键字段

内部类 `ScheduledFutureTask<V>` 继承 `FutureTask<V>`，并实现 `RunnableScheduledFuture<V>`：

| 字段 | 含义 | 阅读注意 |
| --- | --- | --- |
| `time` | 下一次允许执行的绝对 `nanoTime` | 周期任务每轮会更新 |
| `period` | 0 为单次，正数为固定频率，负数为固定延迟 | 符号是调度模式，不是“负延迟” |
| `sequenceNumber` | 全局递增序号 | `time` 相同时维持 FIFO |
| `outerTask` | 周期任务重新入队的实际对象 | 使用 `decorateTask` 后可能不是内部任务本身 |
| `heapIndex` | 在 `DelayedWorkQueue` 堆数组中的下标 | 支持 O(log n) 定位并删除未装饰任务 |

`getDelay(unit)` 返回 `time - now()` 的换算值；小于等于 0 才说明任务到期。`compareTo` 先比较 `time`，相同再比较 `sequenceNumber`，所以相同触发时刻按提交顺序启用。

## 周期任务为什么要保存 outerTask

固定频率入口的主链是：

```text
scheduleAtFixedRate(command, initialDelay, period, unit)
  → new ScheduledFutureTask(..., +periodNanos)
  → t = decorateTask(command, sft)
  → sft.outerTask = t
  → delayedExecute(t)
```

固定延迟入口相同，只是保存 `-delayNanos`。第一轮和后续轮次都必须使用装饰后的对象，所以 `ScheduledFutureTask.run()` 成功执行后调用 `reExecutePeriodic(outerTask)`，而不是固定把 `this` 放回队列。

## decorateTask 才是正确扩展点

STPE 覆盖了 `execute` 和全部 `submit` 方法。子类若再次覆盖这些入口，很容易破坏零延迟包装与周期重入队。源码提供两个模板方法：

```java
protected <V> RunnableScheduledFuture<V> decorateTask(
        Runnable runnable, RunnableScheduledFuture<V> task)

protected <V> RunnableScheduledFuture<V> decorateTask(
        Callable<V> callable, RunnableScheduledFuture<V> task)
```

可以在这里增加追踪、上下文或指标。若返回自定义包装器，它必须正确委托 `isPeriodic`、`getDelay`、`compareTo`、`run`、`cancel` 和 `Future` 状态；否则会直接破坏堆排序、取消或周期调度。装饰任务不再是内部 `ScheduledFutureTask` 时，`DelayedWorkQueue` 不能读取 `heapIndex`，删除会退化为线性查找。

## execute 和 submit 也是零延迟调度

STPE 没有把普通命令交给父类 `execute`：

```text
execute(command)         → schedule(command, 0, NANOSECONDS)
submit(Runnable)         → schedule(task, 0, NANOSECONDS)
submit(Runnable, result) → schedule(Executors.callable(...), 0, NANOSECONDS)
submit(Callable)         → schedule(task, 0, NANOSECONDS)
```

这带来三个容易忽略的结果：

1. `getQueue()` 看到的是零延迟 `ScheduledFuture`，不是原始 `Runnable`。
2. `shutdownNow()` 返回的也是这些包装对象。
3. 任务异常被 `FutureTask` 捕获，因此 `afterExecute(runnable, throwable)` 的第二个参数通常为 `null`；需要对返回的 `Future` 调用 `get()` 才能取得原因。

## 动画：计划时间、实际执行与堆首等待

动画把两个问题放在同一条时间线上：

- 固定频率任务周期为 3 个时间单位，但每轮执行 5 个单位。后续轮次会在上一轮结束后立即追赶，**同一个周期任务不会并发重入**。
- 固定延迟任务每轮执行 5 个单位，结束后再等待 3 个单位，因此相邻执行区间始终留出延迟。
- 延迟堆加入更早任务时会清空旧 leader 并唤醒一个等待线程，重新计算堆首等待。

<ScheduledExecutorAnimation />

下一步阅读 [DelayedWorkQueue：最小堆与 leader-follower](./delayed-work-queue.md)，再到 [周期、异常、取消与关闭策略](./periodic-cancel.md) 跟踪重新入队条件。

## 版本边界

- 本专题源码和字段名以 OpenJDK 8u 为准；JDK 9 以后源码移动到 `java.base` 模块目录。
- JDK 17、21 仍保留 `ScheduledFutureTask`、`period` 符号编码、`DelayedWorkQueue` 最小堆和 leader-follower 等核心结构，具体辅助代码与注释可能调整。
- JDK 8u 构造器传给父类的 keep-alive 是 0ns；JDK 17、21 改为 10ms 的非零默认值。上游注释针对的是 `corePoolSize=0` 且延迟队列非空的特殊配置：为保证队列仍有消费者，最后一个 worker 不会直接退出，而 0ns 会让它在 `getTask()` 中零等待热轮询；10ms 用于节流这条轮询路径。正常核心线程配置下不改变本文调度主线。
- `onShutdown` 也有可观察差异：关闭“执行既有延迟任务”策略时，JDK 8u 会取消仍在队列中的全部单次任务；JDK 17、21 只取消尚未到期的单次任务，已经到期的零延迟任务仍可执行。跨版本测试应使用明确未到期的任务验证该策略。
- 运行中的周期任务遇到默认 `shutdown()` 时，JDK 8u 在本轮结束后不再入队却可能让 Future 留在 `NEW`；JDK 17、21 会在 `reExecutePeriodic` 的退出路径取消 Future。详见 [运行中关闭的版本边界](./periodic-cancel.md#reexecuteperiodic-与首次提交的差异)。
- JDK 21 中 `ExecutorService` 具备 `AutoCloseable` 关闭能力；为保持 Java 8 编译，本专题案例仍显式调用 `shutdown`、`shutdownNow` 和 `awaitTermination`。
- 虚拟线程不改变 STPE 的定时堆算法；STPE 自身仍是按核心线程数执行到期任务的线程池，不应把 `maximumPoolSize` 当成虚拟线程并发开关。

## 源码与许可证

- OpenJDK 8u 源码仓库：<https://github.com/openjdk/jdk8u>
- 目标文件：`jdk/src/share/classes/java/util/concurrent/ScheduledThreadPoolExecutor.java`
- OpenJDK 许可证说明见 [源码许可证与引用边界](/reference/source-license.md)。本文只保留调用链和等价伪代码，不复制大段实现。
