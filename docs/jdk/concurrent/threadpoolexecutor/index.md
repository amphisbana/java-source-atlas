# ThreadPoolExecutor：ctl 与 execute 决策

`ThreadPoolExecutor` 把任务提交、工作线程管理、阻塞队列、生命周期和拒绝策略组合成一个可配置执行器。理解它的入口不是背参数，而是跟踪 `execute` 如何根据状态作出三步决策。

<TopicStudyPanel
  topic-id="openjdk8-java-util-concurrent-threadpoolexecutor"
/>

[打开 JDK 8 / 17 / 21 版本对比 →](/jdk/version-comparison/?topic=thread-pool-executor)，可并排核对 ctl/execute 主协议、finalize 生命周期、动态参数校验与 JDK 21 线程容器的变化。

## 七个构造参数

| 参数 | 作用 |
| --- | --- |
| `corePoolSize` | 核心工作线程目标数量 |
| `maximumPoolSize` | 队列无法接收任务时允许扩展到的最大线程数 |
| `keepAliveTime` | 非核心空闲线程的回收等待时间 |
| `unit` | 存活时间单位 |
| `workQueue` | 保存待执行任务的阻塞队列 |
| `threadFactory` | 创建工作线程 |
| `handler` | 饱和或关闭后的拒绝策略 |

核心线程默认也是按需创建的。需要提前建立可以调用 `prestartCoreThread` 或 `prestartAllCoreThreads`。

## ctl 为什么合并两个状态

JDK 8 用一个 `AtomicInteger ctl` 同时编码：

```text
高 3 位：线程池运行状态 runState
低 29 位：工作线程数量 workerCount
```

这样状态检查和工作线程数量变更可以围绕同一个原子值 CAS，避免两个独立变量出现组合状态不一致。

CAS 的期望值、失败重试和单次原子边界可先参考 [AtomicInteger 与 CAS](../atomic/atomic-integer.md)。这里的重点是在一个整数中同时编码两部分状态，再用 CAS 验证整个旧快照仍然有效。

辅助方法负责拆装：

```text
runStateOf(c)    取高位状态
workerCountOf(c) 取低位数量
ctlOf(rs, wc)    合并状态和数量
```

## 生命周期状态

状态常量直接占用 `ctl` 的高 3 位。`RUNNING` 使用 `111`，按补码解释后整个 `ctl` 为负数，因此 `isRunning(c)` 只需判断 `c < SHUTDOWN`；其余状态按数值递增，`advanceRunState` 可以用一次 CAS 保留低位 workerCount，只替换高位状态。

| runState | 高 3 位 | 新任务 | 队列旧任务 | 对 worker 的中断 |
| --- | --- | --- | --- | --- |
| `RUNNING` | `111` | 接收 | 处理 | 无 |
| `SHUTDOWN` | `000` | 拒绝 | 继续处理 | `shutdown` 只尝试中断空闲 worker |
| `STOP` | `001` | 拒绝 | 不再处理 | `shutdownNow` 尝试中断所有已启动 worker |
| `TIDYING` | `010` | 拒绝 | 已为空 | workerCount 已为 0，单个线程执行 `terminated()` |
| `TERMINATED` | `011` | 拒绝 | 已为空 | `terminated()` 已完成，唤醒终止等待者 |

状态按数值单向推进，但一次关闭流程不一定经过全部五态：正常 `shutdown` 常走 `RUNNING → SHUTDOWN → TIDYING → TERMINATED`，直接 `shutdownNow` 常走 `RUNNING → STOP → TIDYING → TERMINATED`。为了把全部分支放进同一条时间线，下面动画先调用 `shutdown`，随后再调用合法的 `SHUTDOWN → STOP` 升级路径。

```text
RUNNING
  └─ shutdown() ─────────────→ SHUTDOWN
  └─ shutdownNow() ──────────→ STOP

SHUTDOWN --队列空且 worker 为 0--→ TIDYING
STOP     --worker 为 0----------→ TIDYING
TIDYING  --terminated() 完成----→ TERMINATED
```

`TIDYING` 不是一个可长期停留的“等待任务状态”。成功 CAS 到该状态的线程持有 `mainLock` 调用 `terminated()`，并在 `finally` 中设置 `TERMINATED`，所以钩子即使抛出异常也不会阻止最终状态写入。

## execute 的三步决策

JDK 8 的 `execute(command)` 不是“先排队再建线程”：

```text
1. workerCount < corePoolSize
   └─ addWorker(command, core=true)，成功则返回

2. 线程池仍 RUNNING 且 workQueue.offer(command) 成功
   └─ 重新检查运行状态
       ├─ 已关闭且能移除任务 → reject
       └─ workerCount == 0 → addWorker(null, false)

3. 队列无法接收任务
   └─ addWorker(command, core=false)
       ├─ 成功：扩展到 maximumPoolSize 范围
       └─ 失败：reject
```

### 动画：提交七个任务并穿过五个 ctl 状态

演示使用 `corePoolSize=2`、`maximumPoolSize=3`、容量为 2 的有界队列。T1 至 T6 稳定触发 `execute` 的三个分支和饱和拒绝；W1 取走 T3 后，T7 用来展示 `offer` 已成功但复查前发生 `shutdown` 的并发窗口。最后通过 `shutdownNow` 把 `SHUTDOWN` 升级到 `STOP`，继续观察 `TIDYING` 和 `TERMINATED`。

<ThreadPoolExecutorAnimation />

#### 每次提交后的完整快照

| 提交后 | worker 中运行 | 队列 | 结果 |
| --- | --- | --- | --- |
| T1 | T1 | 空 | 创建核心 W1 |
| T2 | T1、T2 | 空 | 创建核心 W2 |
| T3 | T1、T2 | T3 | 入队 |
| T4 | T1、T2 | T3、T4 | 入队，队列满 |
| T5 | T1、T2、T5 | T3、T4 | 创建非核心 W3 |
| T6 | T1、T2、T5 | T3、T4 | worker 与队列均满，拒绝 |
| T7 | T3、T2、T5 | T4、T7 | `offer` 成功；若复查前关闭，则移除 T7 并拒绝 |

这张表成立有一个前提：提交期间任务没有提前完成。真实运行中如果 T1 很快结束并取走 T3，后续 T4 或 T5 可能重新成功入队。因此线程池分支由提交瞬间的快照决定，不是只由构造参数静态决定。

#### offer 成功后为什么必须二次检查

任务成功入队不代表线程池始终处于 RUNNING。`offer` 与 `shutdown` 可以并发发生：

```text
提交线程：检查 RUNNING ── offer(command) ── recheck
关闭线程：               shutdown() 推进到 SHUTDOWN
```

如果复查发现池已关闭，`execute` 会尝试从队列移除刚入队的任务并拒绝它；如果池仍运行但 workerCount 为 0，则创建一个 `firstTask=null` 的 worker 来消费队列。这两个补偿分支防止“关闭后仍悄悄接收任务”和“队列有任务却无人执行”。注意条件是“状态已关闭并且 `remove(command)` 成功”：如果某个 worker 已经抢先取走任务，提交线程不能把一个已经开始的任务再当作拒绝任务处理。

JDK 8 对应源码可以压缩成下面三行：

```java
int recheck = ctl.get();
if (!isRunning(recheck) && remove(command)) reject(command);
else if (workerCountOf(recheck) == 0) addWorker(null, false);
```

`remove` 内部还会调用 `tryTerminate()`。这很重要：线程池可能正处于 `SHUTDOWN`，而刚移除的任务恰好是队列最后一项；移除动作本身就可能让终止条件成立。

入队后必须重新检查，因为 `offer` 与线程池关闭不是同一个原子动作。若提交期间发生关闭，代码尝试把任务从队列移除并拒绝。

## 为什么无界队列会弱化 maximumPoolSize

使用容量近似无界的 `LinkedBlockingQueue` 时，达到核心线程数后的任务通常都能成功入队，第三步不会发生，因此线程数长期停留在 `corePoolSize`。

这不是最大线程数失效，而是队列策略让“队列满后扩线程”的条件很难出现。无界队列还可能积压大量任务，带来延迟和内存风险。

## JDK 8、17、21：主协议稳定，生命周期边界收紧

三版都保留同一条提交主线：先尝试创建核心 Worker，核心线程已满时 `offer` 入队并再次检查 `ctl`，队列无法接收才尝试创建非核心 Worker，最后交给拒绝策略。`ctl` 仍把高 3 位运行状态和低 29 位 workerCount 放在一个 `AtomicInteger` 中；Worker 仍以不可重入锁区分“正在执行任务”和“可以被 shutdown 中断”。

真正需要按版本重新定位的，是协议周围的资源和诊断边界：

| 观察点 | JDK 8u412 | JDK 17 | JDK 21 |
| --- | --- | --- | --- |
| 忘记关闭线程池 | `finalize` 可能按权限调用 `shutdown()` | `finalize` 保留兼容签名但为空 | 空实现并标记 `forRemoval`，不能再当兜底 |
| 显式资源作用域 | `ExecutorService` 不能用于 try-with-resources | 仍没有 `close()` | 已包含 JDK 19 新增的 `AutoCloseable + close()` |
| workerCount 回退 | CAS 失败重试的 decrement 循环 | `ctl.addAndGet(-1)` | 延续 JDK 17 |
| 动态 `setCorePoolSize` | 只检查非负，允许临时超过 max | 强制 `core <= maximumPoolSize` | 延续 JDK 17 |
| sneaky checked Throwable | 钩子见原异常，向 Worker 外包装成 `Error` | 钩子见原异常，向外原样传播 | 延续 JDK 17 |
| Worker 启停归属 | 直接 `Thread.start()` | 直接 `Thread.start()` | `SharedThreadContainer.start/close` 管理内部归属 |

这些变化不代表业务代码可以绕过生命周期管理、随意反射 `ctl` 或依赖内部线程容器。JDK 19+ 的 `close()` 会先 `shutdown()` 并等待任务终止；它适合 try-with-resources，但不是立即取消。兼容 JDK 8/17 时仍应在 `finally` 中显式关闭。升级测试应覆盖动态调参、资源收口和异常传播；源码断点则分别进入当前版本的 `finalize`、`ExecutorService.close`、`decrementWorkerCount`、`tryTerminate`、`runWorker` 和 `addWorker`。

## 常见队列影响

| 队列 | execute 行为倾向 | 风险与用途 |
| --- | --- | --- |
| `SynchronousQueue` | 不存任务，直接交接；失败时扩线程 | 需要直接移交，必须严格限制最大线程数 |
| 有界 `ArrayBlockingQueue` | 核心线程后排队，队满再扩线程 | 容量明确，便于形成背压 |
| 无界 `LinkedBlockingQueue` | 核心线程后持续排队 | 最大线程数通常不参与，需关注堆积 |
| `PriorityBlockingQueue` | 按优先级取任务，逻辑上无界 | 相同优先级顺序和任务堆积需单独设计 |

下一步先阅读 [Worker 与任务循环](./worker.md)。理解线程池本体后，可以继续对照 [BlockingQueue](../blockingqueue/) 的等待与交接协议、[FutureTask](../futuretask/) 的结果状态机，以及 [ScheduledThreadPoolExecutor](../scheduledthreadpoolexecutor/) 如何在本类之上增加延迟堆和周期重排。
