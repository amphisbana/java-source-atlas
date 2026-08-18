# ThreadPoolExecutor 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.concurrent.ThreadPoolExecutorDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ThreadPoolExecutorDebugLab
```

## 推荐断点

| 方法 | 观察变量 | 目标 |
| --- | --- | --- |
| `execute` | `c`、worker 数、队列状态 | 三步提交决策 |
| `addWorker` | `rs`、`wc`、`core`、`workerStarted` | 预占数量与启动回滚 |
| `runWorker` | `task`、`completedAbruptly` | 任务循环与钩子 |
| `getTask` | `rs`、`wc`、`timed`、`timedOut` | 空闲回收 |
| `processWorkerExit` | `completedAbruptly`、`min` | worker 退出与补充 |
| `tryTerminate` | `ctl`、队列、worker 数 | 状态收口 |
| `interruptIdleWorkers` | `w.getState()`、`w.tryLock()`、`t.isInterrupted()` | 工作锁如何区分空闲和运行中 Worker |
| `afterExecute` | `r`、`t`、`r instanceof Future` | execute 与 submit 异常的不同去向 |

## 实验一：execute 三步路径

实验池参数为核心 1、最大 2、有界队列 1：

1. 第一个阻塞任务创建核心 worker。
2. 第二个任务进入队列。
3. 第三个任务因队列已满创建非核心 worker。
4. 第四个任务同时超过线程和队列容量，进入拒绝策略。

使用闩锁稳定占住 worker，不依赖 `sleep` 猜测线程调度。

## 实验二：CallerRuns 背压

运行 `observeCallerRuns()`，记录任务实际执行线程。池饱和时任务在提交线程运行，提交动作被任务耗时占用，生产速度自然降低。

## 实验三：有序关闭

运行 `observeGracefulShutdown()`，先提交任务再调用 `shutdown`。观察池拒绝新任务，但已入队任务继续完成，最后 `awaitTermination` 返回 true。

## 实验四：offer 后 shutdown 的复查回滚

`observeOfferShutdownRecheck()` 使用一个只在 `offer` 成功后暂停提交线程的测试队列：

1. `execute` 在 RUNNING 状态下成功把任务放入队列。
2. 关闭线程等待“已经 offer”的闩锁后调用 `shutdown()`。
3. 提交线程恢复，重新读取 ctl，命中 `!isRunning(recheck)`。
4. `remove(command)` 成功并进入拒绝策略；任务执行次数保持 0。

这个实验控制的是源码中的真实并发窗口，不依赖 `sleep` 猜测 shutdown 是否刚好插入两次读取之间。

## 实验五：Worker 锁与核心线程超时

`observeIdleWorkerInterruptBoundary()` 让任务在 Worker 锁内等待，随后调用 `shutdown`。任务不会收到中断，释放闩锁后正常结束；这与 `shutdownNow` 中断所有 Worker 的场景形成对照。

`observeCoreThreadTimeout()` 开启 `allowCoreThreadTimeOut(true)`，预启动一个核心线程并等待其从 `poll(keepAliveTime)` 超时退出。观察 `poolSize` 从 1 变为 0；实验使用带截止时间的条件等待，只把短暂休眠用于轮询，不用它决定并发先后。

## 实验六：execute 异常后的 Worker 替补

`observeAbruptExitReplacement()` 让第一个 `execute` 任务抛出运行时异常，并提前排入第二个任务。记录线程工厂的创建次数：W1 异常退出后，`processWorkerExit(completedAbruptly=true)` 创建 W2，第二个任务仍会完成。

## 实验七：三个生命周期钩子与 submit 异常

`observeLifecycleHooks()` 使用 `HookTrackingExecutor` 记录 `beforeExecute`、`afterExecute` 和 `terminated`：

- 直接 `execute` 的失败会把非空 Throwable 传给 `afterExecute`，并导致 Worker 替补。
- `submit` 的失败由 FutureTask 保存，`afterExecute` 的原始 Throwable 是 `null`。
- 钩子确认 Future 已完成后调用 `get()`，从 `ExecutionException` 提取真实原因。
- `awaitTermination` 返回前，`terminated` 已经记录完成。

对应自动测试：

```bash
mvn -pl labs/jdk-labs -Dtest=ThreadPoolExecutorBehaviorTest test
```

## 调试注意

- 在持锁区断点时优先只挂起当前线程。
- `getPoolSize()` 等监控值是观察结果，不是业务同步工具。
- 断点会改变竞争时序，只用来确认分支和变量，不用来做性能结论。
