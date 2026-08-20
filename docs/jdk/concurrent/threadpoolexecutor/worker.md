# ThreadPoolExecutor：Worker 与任务循环

## Worker 的三重职责

跨版本对照入口：[JDK 8 / 17 / 21 ThreadPoolExecutor 版本对比](/jdk/version-comparison/?topic=thread-pool-executor)。Worker 的 AQS 锁和 `runWorker` 主循环在三版保持稳定，但异常转发方式与 JDK 21 的线程容器启动边界需要按目标源码重新定位。

JDK 8 的内部类 `Worker`：

- 实现 `Runnable`，线程启动后进入 `runWorker(this)`；
- 继承 AQS，实现一把不可重入的工作锁；
- 保存 `thread`、首个任务 `firstTask` 和已完成任务数。

工作锁用于区分正在执行任务和空闲等待的 worker，关闭线程池时可以只中断空闲 worker。Worker 初始化时把 AQS 状态设为 -1，在线程真正进入运行循环前抑制中断干扰。

### Worker 为什么继承 AQS

这把锁不是给业务代码使用的可重入锁，而是线程池内部的一位状态标记：

| AQS state | 所处阶段 | `tryLock` 结果 | 关闭时的意义 |
| --- | --- | --- | --- |
| `-1` | Worker 已构造但线程尚未进入 `runWorker` | 失败 | 避免在线程启动前被当成空闲线程中断 |
| `0` | 正在队列中等待，或任务之间的空档 | 成功 | `interruptIdleWorkers` 可以临时持锁并中断该线程 |
| `1` | `runWorker` 正在执行一个任务 | 失败 | `shutdown()` 不打断活跃任务 |

`tryAcquire` 只接受从 0 到 1 的 CAS，并把 owner 设置为当前线程；同一线程再次加锁也失败，因此 Worker 锁不可重入。`tryRelease` 清空 owner 并把 state 写回 0。`interruptIfStarted` 还会检查 `state >= 0`，与初始 `-1` 一起守住“尚未启动”的边界。

## addWorker 的双层检查

`addWorker(firstTask, core)` 分为两层：

1. 外层循环检查运行状态是否允许新增 worker。
2. 内层 CAS 增加 `ctl` 中的 workerCount，并检查核心或最大线程上限。

数量预占成功后才创建 Worker 和实际 Thread。线程启动失败时进入 `addWorkerFailed`，从 workers 集合移除对象、回退计数并重新尝试终止检查。

`SHUTDOWN` 状态下只有一种特殊允许：不再接收新任务，但如果 `firstTask == null` 且队列非空，可以增加 worker 来排空已接收任务。

## runWorker 主循环

```text
task = firstTask
firstTask = null
worker.unlock()

while (task != null || (task = getTask()) != null) {
    worker.lock()
    根据池状态处理中断标记
    beforeExecute(thread, task)
    task.run()
    afterExecute(task, thrown)
    task = null
    completedTasks++
    worker.unlock()
}

processWorkerExit(worker, completedAbruptly)
```

`beforeExecute`、`afterExecute` 和 `terminated` 是模板扩展点。覆写时必须处理父类行为和异常，避免监控代码破坏线程池主流程。

### 动画：锁状态、异常替补和钩子如何串起来

下面三组独立场景依次展示：`execute` 任务异常导致 Worker 替补；`shutdown` 通过 Worker 锁只中断空闲线程；核心线程超时回收；`submit` 异常被 `FutureTask` 保存后如何在 `afterExecute` 中解包。

<ThreadPoolWorkerAnimation />

### runWorker 的中断复查

拿到 Worker 锁后，`runWorker` 会确保两件事：线程池至少到 `STOP` 时，当前线程必须带中断标记；仍低于 `STOP` 时，`Thread.interrupted()` 清理掉任务遗留的中断标记。源码在清理后再次读取 ctl，是为了覆盖“清理中断标记的同时另一个线程执行 `shutdownNow`”的竞争，不能把它简化成一次状态判断。

`shutdown()` 只到 `SHUTDOWN`，不会为了停止当前任务而设置中断；`shutdownNow()` 到 `STOP` 后才调用 `interruptWorkers()`。两者都是中断协作，不是强制终止：用户任务忽略中断时，线程池仍可能长期无法结束。

## getTask 如何决定回收线程

`getTask()` 循环检查池状态、队列和 worker 数量：

- `STOP`，或 `SHUTDOWN` 且队列为空：减少 workerCount 并返回 `null`。
- worker 超过 `maximumPoolSize`，或允许超时且已经发生一次定时等待超时：尝试减少计数。
- 需要超时回收时调用队列的 `poll(keepAliveTime, unit)`。
- 核心线程默认调用 `take()` 持续等待；开启 `allowCoreThreadTimeOut` 后也可以超时退出。

`timedOut` 的两阶段判断值得单独看：第一次 `poll` 返回 `null` 只把 `timedOut=true`，下一轮才在重新读取 ctl、workerCount 和队列状态后 CAS 扣减计数。这避免使用一次已经过期的等待结果直接退出。即使允许回收，`(wc > 1 || workQueue.isEmpty())` 也会避免在队列非空时把最后一个 worker 退出。

开启 `allowCoreThreadTimeOut(true)` 前，`keepAliveTime` 必须大于 0；开启后核心线程也使用定时 `poll`，所以空闲池最终可以收缩到 0。之后有新任务到来时，`execute` 会重新创建核心 Worker。

## execute 与 submit 的异常差异

直接 `execute(runnable)` 时，任务抛出的未检查异常会沿 `runWorker` 传播，当前 worker 异常结束，`processWorkerExit` 按状态决定是否补充线程。

`submit` 会把任务包装为 `FutureTask`。`FutureTask.run` 捕获异常并保存到 Future，因此 `afterExecute` 收到的 `Throwable` 通常为 `null`。要统一监控 submit 异常，需要检查 `Future.get()` 的结果，同时避免阻塞未完成 Future。

| 提交方式 | 真正交给 worker 的 Runnable | 任务异常去向 | `afterExecute` 的 `Throwable` | Worker 是否异常退出 |
| --- | --- | --- | --- | --- |
| `execute(runnable)` | 原始 Runnable | 沿 `runWorker` 继续抛出 | 原始 `RuntimeException` 或 `Error` | 是，通常触发替补 |
| `submit(runnable/callable)` | `RunnableFuture`，通常是 `FutureTask` | 保存到 Future 的 outcome | 通常为 `null` | 否，可以继续复用 |

可靠的 `afterExecute` 监控应先调用 `super.afterExecute`，再仅对“已完成的 Future”执行 `get()`，分别处理取消、`ExecutionException` 和中断。在线程池自己的 worker 中，`afterExecute` 发生在 `FutureTask.run` 返回后，所以这个 Future 已完成；如果把同一段逻辑抽成通用工具用于别处，仍应显式检查 `isDone()`，避免阻塞。

`beforeExecute` 自己抛出异常时，用户任务根本不会执行，Worker 也会异常退出；`afterExecute` 自己抛出异常同样会让 Worker 退出，并可能覆盖任务原先抛出的异常。因此钩子适合轻量、可靠的清理和观测，不应包含会阻塞或频繁失败的业务操作。JDK 17/21 还把成功路径的 `afterExecute(task, null)` 放在统一 `try` 内：若该调用自身抛错，会进入 `catch`，随后再次执行 `afterExecute(task, ex)`。钩子若不可靠，甚至可能执行两次并让第二个异常遮盖第一个。

### JDK 17/21 的异常路径变化

JDK 8 在 `runWorker` 中分别捕获 `RuntimeException`、`Error` 和其他 `Throwable`。这里必须分清两个观察位置：局部变量 `thrown` 保存并传给 `afterExecute` 的始终是原异常；只有非 `RuntimeException/Error` 的罕见 Throwable（通常只能通过 sneaky throw 制造）向 Worker 外传播时，才包装成 `Error`。JDK 17/21 用一个 `catch (Throwable ex)` 收敛路径，钩子仍看到原异常，之后原对象也直接向外传播。因此真正的跨版本差异出现在 Worker 线程的 `UncaughtExceptionHandler`，而不是 `afterExecute`。

常规 `Runnable` 抛出的 `RuntimeException/Error`、`execute` 导致 Worker 异常退出、`submit` 由 `FutureTask` 保存异常，这三条公开主线没有变化。不要为了利用边缘传播差异在业务代码中使用 sneaky throw；它只适合帮助理解编译期 throws 边界和运行时真实异常对象并非一回事。

JDK 21 还在 `addWorker` 中通过 `SharedThreadContainer.start(t)` 登记线程，并在池进入 `TERMINATED` 的 finally 中关闭容器。它是 JDK 内部运行时细节，不是自定义 ThreadFactory 或 Executor 的扩展点。

## processWorkerExit

worker 退出后：

1. 异常退出时补减 workerCount，正常退出通常已在 `getTask` 中减过。
2. 在主锁保护下汇总完成任务数并移除 Worker。
3. 调用 `tryTerminate` 推进生命周期。
4. 如果线程池仍需要工作线程，根据核心超时配置、最小数量和队列状态决定是否补充。

因此单个任务异常不一定让线程池永久少一个工作线程。

替补数量不是无条件回到 `corePoolSize`：正常退出时最小值由 `allowCoreThreadTimeOut ? 0 : corePoolSize` 决定；即使最小值是 0，只要队列非空也至少保留一个 Worker。异常退出则直接尝试 `addWorker(null, false)`，因为原 workerCount 没有在 `getTask` 的正常退出路径中扣减。

## terminated 钩子的边界

`terminated()` 由成功把 ctl 从 `SHUTDOWN/STOP` CAS 到 `TIDYING` 的唯一线程调用，此时 workerCount 已为 0，且满足队列终止条件。钩子执行完毕后，源码在 `finally` 中写入 `TERMINATED` 并 `signalAll`；因此 `awaitTermination` 返回时，钩子已经完成。
