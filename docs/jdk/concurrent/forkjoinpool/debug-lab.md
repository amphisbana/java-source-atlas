# ForkJoinPool 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.concurrent.ForkJoinPoolDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ForkJoinPoolDebugLab
```

案例使用自建 pool，并在 finally 中关闭。`observeExternalSubmission()` 只读取 commonPool 身份，不把教学任务留在全局 pool 中。

## 场景一：递归求和与工作窃取

运行 `observeRecursiveSplitAndJoin()`，根任务计算闭区间 `[1, 16]`：

1. 区间长度大于阈值时拆为左右任务。
2. fork 右侧，当前 worker 直接 compute 左侧。
3. join 右侧并合并结果。
4. 并发事件写入线程安全队列，输出只用于观察，不用于断言固定顺序。

推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `RecursiveTask.exec` | `result`、`compute()` 返回值 |
| `ForkJoinTask.fork` | 当前线程是否为 ForkJoinWorkerThread |
| `WorkQueue.push` | `base`、`top`、`task`、数组槽位 |
| `WorkQueue.pop` | `top - 1`、`base`、槽位 CAS |
| `WorkQueue.pollAt` | victim `base`、目标槽位、CAS 结果 |
| `ForkJoinTask.doJoin` | `status`、`tryUnpush` 结果 |
| `ForkJoinPool.awaitJoin` | `currentJoin`、目标状态 |
| `ForkJoinPool.helpStealer` | `currentSteal`、`currentJoin`、victim base/top |

断点会改变窃取时机。一次运行没有发生 steal，不表示算法没有窃取路径；增加断点停顿本身也可能让另一个 worker 更容易窃取。

## 场景二：LIFO 与 asyncMode

运行 `observeLocalSchedulingModes()`。parallelism 固定为 1，根任务依次 fork A、B、C 且不 join，子任务把执行标签写入列表。

- 默认模式通常从本地 top 执行 C、B、A。
- asyncMode 从 base 端处理，通常是 A、B、C。

这段实验用于把公开模式与 WorkQueue 方向对应起来。不要把具体列表顺序复制成多 worker 业务保证；外部提交、steal、任务提前完成都会改变全局顺序。

在构造器和 `WorkQueue.nextLocalTask()` 断点观察配置位如何选择 `pop` 或 `poll`。

## 场景三：join 与 get 的异常报告

运行 `observeExceptionReporting()`，两个独立任务都抛出 `IllegalStateException`：

- `join()` 直接报告 unchecked 异常类型；
- `get()` 使用 `ExecutionException` 包装计算原因。

推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `ForkJoinTask.doExec` | `status`、`completed`、`rex` |
| `recordExceptionalCompletion` | 异常记录和终态 |
| `reportException` | DONE_MASK 后的状态 |
| `getThrowableException` | 抛出线程与 join 线程是否相同 |

JDK 17/21 的异常存储结构不同，不要只按 JDK 8 的全局 exceptionTable 找字段。

## 场景四：ManagedBlocker 与补偿观察

运行 `observeManagedBlocking()`：

1. parallelism=1 的 worker 进入一个受闩锁控制的 ManagedBlocker。
2. 外部线程提交 follower 任务。
3. 在释放 blocker 前，用有限等待观察 follower 是否获得执行机会。
4. 无论是否观察到补偿，都释放闩锁、等待两个 Future 并关闭 pool。

推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `ForkJoinPool.managedBlock` | 当前线程、`isReleasable` 结果 |
| `tryCompensate` | ctl、活跃数、总线程数、idle stack |
| `ManagedBlocker.block` | 条件何时真正阻塞和释放 |

观察到 pool size 增加只能说明这一次运行选择了创建补偿 worker。源码也可能唤醒空闲 worker或只调整活跃计数；不要把某一条路径当成公开保证。

## 场景五：外部提交和 commonPool 边界

运行 `observeExternalSubmission()`：

- 用 `pool.submit(Callable)` 把根任务交给自建 pool；
- 在任务内读取 `ForkJoinTask.inForkJoinPool()` 和 `ForkJoinTask.getPool()`；
- 确认外部 Future 得到结果；
- 只打印 commonPool 是否为静态共享实例，不断言并行度或线程名。

推荐在 `externalPush/externalSubmit` 观察共享 submission WorkQueue。普通线程直接 `task.fork()` 时则进入 commonPool，不会自动使用旁边创建的自建 pool。

## 自动化测试覆盖

`ForkJoinPoolBehaviorTest` 只断言公开契约：

- RecursiveTask 分治结果；
- `fork()` 返回任务自身并可 join；
- 外部 submit 的结果；
- 默认与 asyncMode 查询；
- join 与 get 的异常类型差异；
- ManagedBlocker 的 releasable/block 协议；
- 自建 pool 的有序关闭。

测试不会断言具体 steal 次数、worker 名称、commonPool 并行度、队列 base/top 数值或补偿线程数量。

## 调试注意

- 所有断点优先只挂起当前线程，冻结整个进程会破坏 work-stealing 时序。
- WorkQueue 数组槽位是循环映射，逻辑下标要与 mask 一起解释。
- `getQueuedTaskCount` 等值只是采样，不用于判断断点前置条件。
- `join()` 可能由当前线程直接执行目标，也可能帮助别的 worker；不要只盯一个预设线程。
- 调试结束确保自建 pool 已 shutdown，避免非预期后台任务影响下一场实验。
