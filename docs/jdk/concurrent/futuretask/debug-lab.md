# FutureTask 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/concurrent/FutureTaskDebugLab.java
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.FutureTaskDebugLab
```

实验只使用 FutureTask 公开 API 和受保护扩展点，不反射读取私有 state、runner、outcome 或 waiters。私有变量应在 IDE 附加当前 JDK 源码后通过断点观察，这样不会依赖 `--add-opens`，也不会用错误的反射写入破坏并发协议。

## 实验一：两个 run 调用只执行一次

运行 `observeSingleExecution()`。两个命名线程同时调用同一个 FutureTask 的 `run()`，Callable 用计数器记录真实调用次数。

建议断点顺序：

1. `FutureTask.run()` 入口，同时观察两个线程。
2. runner CAS。
3. `Callable.call()`。
4. `set(V)`。
5. `finishCompletion()`。

应观察到只有一个线程 CAS runner 成功并进入 Callable。另一个线程可能在 runner CAS 失败，也可能稍后读到终态后返回；这两种调度都满足“Callable 只运行一次”，不应把失败线程具体停在哪一行写成固定断言。

## 实验二：两个 get 等待者与正常发布

运行 `observeWaitersAndCompletion()`。W1 和 W2 都调用 get，执行任务的线程等待主线程释放闸门后返回 42。

建议在下面位置设置断点：

| 位置 | 观察变量 | 预期变化 |
| --- | --- | --- |
| `awaitDone` | `q`、`queued`、`waiters` | 每个等待者创建节点并 CAS 压栈 |
| `set` | `state`、`outcome` | NEW -> COMPLETING，写 42，再到 NORMAL |
| `finishCompletion` | `q`、`q.thread`、`q.next` | 整栈摘除后逐个 unpark |
| `report` | `s`、`x` | 两个等待者都读取 NORMAL 和 42 |

若要稳定看清两个节点，可给 `awaitDone` 设置线程过滤，先暂停 W1 入栈，再放行 W2。调试器改变线程调度，不要根据两个 get 的打印先后推断 unpark 或返回的公平顺序。

## 实验三：异常如何延迟到 get

运行 `observeExceptionalCompletion()`。Callable 抛 IllegalStateException，但调用 `task.run()` 的实验线程不会从 run 收到该业务异常；FutureTask 捕获它并进入 setException。

检查：

- setException 是否先 CAS 到 COMPLETING。
- outcome 是否保存原 IllegalStateException 对象。
- state 是否最终为 EXCEPTIONAL。
- get 是否抛 ExecutionException，且 cause 是原异常。

这也是 `ExecutorService.submit` 与 `execute` 的一个关键区别来源：提交成 FutureTask 后，任务异常通常保存在 Future 中；如果业务从不读取 Future，异常可能不会像直接 Runnable 抛错那样到达工作线程的未捕获异常处理器。

## 实验四：超时等待不取消任务

运行 `observeTimedGet()`。第一次 `get(80ms)` 在任务尚未完成时抛 TimeoutException，随后实验释放 Callable 并再次 get 到正常结果。

在 `awaitDone(true, nanos)` 观察：

1. WaitNode 压栈。
2. `parkNanos` 返回后重新计算剩余预算。
3. 预算耗尽时调用 removeWaiter。
4. state 仍为 NEW，`isCancelled()` 仍为 false。
5. Callable 后续完成时同一个 FutureTask 进入 NORMAL。

时间参数用于限定实验，不应断言实际等待恰好等于 80ms。系统调度只能保证不会在观察到结果前成功返回，不能提供毫秒级精准唤醒。

## 实验五：取消前运行与协作中断

运行 `observeCancellation()`，其中包含两个独立场景。

第一个任务先执行 `cancel(false)`，再调用 run。应看到 state 已经是 CANCELLED，run 在入口直接返回，Callable 调用次数保持零。

第二个任务先让 Callable 进入可中断的 CountDownLatch.await，再调用 `cancel(true)`：

```text
NEW -> INTERRUPTING
  -> runner.interrupt()
  -> INTERRUPTED
  -> finishCompletion()
```

Callable 捕获 InterruptedException、记录观察结果并退出，这属于协作停止。把 await 换成一个忽略中断的无限循环后，FutureTask 仍会显示已取消，但工作线程不会被 FutureTask 强制杀死；不要在实验中真的留下这种非守护死循环。

建议断点：`cancel`、`Thread.interrupt` 调用前、`handlePossibleCancellationInterrupt` 和 `finishCompletion`。确认 `CancellationException` 来自 FutureTask 取消状态，而不是把 Callable 捕获的 InterruptedException 作为 EXCEPTIONAL 结果发布。

## 实验六：done 与 runAndReset

运行 `observeCompletionHooks()`。实验子类公开一个窄的 `runAndResetOnce()` 方法，并记录 `done()` 次数：

1. 前两次 runAndReset 正常调用 Callable，但 state 保持 NEW，done 次数仍为零。
2. 最后调用普通 run，第三次调用 Callable并发布 NORMAL。
3. finishCompletion 调用 done 一次，get 返回第三次结果。

在 ScheduledThreadPoolExecutor 中，周期任务使用的正是这种“本轮成功但不完成 Future”的能力。若 runAndReset 某轮抛异常，setException 会把任务永久推进到 EXCEPTIONAL，后续不能继续复位运行。

实验子类仅用于观察受保护扩展点。普通业务不要为了反复复用一个 FutureTask 而暴露 runAndReset；周期调度、失败策略和重新入队应交给调度器统一管理。

## 实验七：get 等待者被中断

运行 `observeInterruptedWaiter()`。实验先让一个线程在尚未运行的 FutureTask 上调用 `get()`，确认它进入 park 等待后再中断该线程：

1. `awaitDone` 观察到中断并抛 `InterruptedException`；
2. `removeWaiter` 尽力把退出节点从 Treiber 链中解开；
3. 等待者退出不等于任务取消，Future 仍是未完成、未取消；
4. 其他线程随后调用 `run()`，同一个 FutureTask 仍能正常发布 42。

这与 `cancel(true)` 不同：这里中断的是等待结果的线程，不是执行 Callable 的 runner。建议同时观察 `WaitNode.thread` 被清空和清理循环的重试，不要把等待者中断解释成任务中断。

## JDK 8 与 JDK 17/21 断点差异

| 目标 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| state CAS | `UNSAFE.compareAndSwapInt` | `STATE.compareAndSet` |
| runner CAS | `UNSAFE.compareAndSwapObject` | `RUNNER.compareAndSet` |
| waiters CAS | `UNSAFE.compareAndSwapObject` | `WAITERS.weakCompareAndSet` |
| 最终状态发布 | `UNSAFE.putOrderedInt` | `STATE.setRelease` |
| timed await 局部变量 | `deadline`、`nanos` | `startTime`、`elapsed`、`parkNanos` |
| JDK 21 新查询 | 无 | `resultNow`、`exceptionNow`、`state` 只在 JDK 21 可见 |

私有字段名目前相对稳定，但 IDE 断点应以正在运行的 SDK 源码为准。Java 8 实验代码不能直接调用 JDK 21 新增方法，否则项目的 `--release 8` 编译会失败。

## 并发调试注意事项

- 在 runner CAS、waiters CAS 和 state CAS 处优先使用“仅挂起当前线程”，挂起全部线程会冻结负责释放闸门的线程。
- 调试 cancel(true) 时不要在 INTERRUPTING 状态长期挂起取消线程，否则 runner 会在 handlePossibleCancellationInterrupt 中持续 yield。
- 不通过反射修改 state 来跳步骤。outcome 非 volatile，其安全性依赖真实发布协议，伪造状态得到的结果没有教学意义。
- 所有手工启动的线程都要设置退出闸门和最长等待时间；断点实验结束前确认没有残留的非守护线程。

## 实验完成标准

- 能解释 state 保持 NEW 时 Callable 仍可能已经在执行。
- 能指出 runner CAS 与 state CAS 分别控制什么。
- 能画出 W2 -> W1 的 Treiber 栈，并解释 unpark 顺序不等于返回顺序。
- 能说明 outcome 为什么可以是普通字段，COMPLETING 为什么不能直接 report。
- 能区分等待者中断、等待超时、cancel(false) 和 cancel(true)。
- 能证明 cancel(true) 是协作中断请求，不是强制停止。
- 能说明 done 的调用时点，以及 runAndReset 为什么成功后不唤醒 get 等待者。
