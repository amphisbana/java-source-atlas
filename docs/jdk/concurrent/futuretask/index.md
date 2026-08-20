# FutureTask：把一次计算连接到执行、等待与取消

`FutureTask<V>` 同时扮演两个角色：对执行器来说，它是可以调用 `run()` 的 `Runnable`；对提交者来说，它是可以 `get()`、查询和取消的 `Future<V>`。这层桥接使线程池只需要调度一个 `Runnable`，调用方仍能拿到同一个对象观察计算结果。

本专题以 OpenJDK 8u 为主基线。JDK 17/21 延续了七个内部状态、`runner` 执行权、WaitNode Treiber 栈和完成清理协议，但原子字段访问已从 `sun.misc.Unsafe` 迁移到 `VarHandle`，定时等待的局部实现也经过调整。文中会把公开契约和版本相关私有实现分开说明。

<TopicStudyPanel topic-id="openjdk8-java-util-concurrent-futuretask" />

[打开 JDK 8 / 17 / 21 版本对比 →](/jdk/version-comparison/?topic=future-task)，可并排核对 Unsafe 到 VarHandle、定时等待边界、`toString` 诊断与 JDK 21 `Future.State/resultNow/exceptionNow`。

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `Future` | [`java/util/concurrent/Future.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/Future.java) | `get`、`cancel`、`isDone`；JDK 21 另有非阻塞观察 API |
| `FutureTask` | [`java/util/concurrent/FutureTask.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java) | `run`、`set`、`setException`、`get`、`cancel` |
| `RunnableFuture` | [`java/util/concurrent/RunnableFuture.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/RunnableFuture.java) | `Runnable` 与 `Future` 的组合接口 |
| `AbstractExecutorService` | [`java/util/concurrent/AbstractExecutorService.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/AbstractExecutorService.java) | `newTaskFor`、`submit` |
| `ScheduledThreadPoolExecutor` | [`java/util/concurrent/ScheduledThreadPoolExecutor.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ScheduledThreadPoolExecutor.java) | 周期任务对 `runAndReset` 的复用 |

`FutureTask` 不是线程，也不会自行选择执行器。直接调用 `task.run()` 时，当前线程同步执行 Callable；调用 `executor.execute(task)` 时，执行器决定何时、在哪个工作线程调用同一个 `run()`。

## submit 如何得到 FutureTask

以 `ExecutorService.submit(callable)` 为例，JDK 8 的典型调用链是：

```text
AbstractExecutorService.submit(callable)
  -> newTaskFor(callable)
       -> new FutureTask(callable)
  -> execute(futureTask)
  -> 返回同一个 futureTask

工作线程稍后：
  -> futureTask.run()
  -> callable.call()
  -> set(value) 或 setException(error)
```

这里有两个容易混淆的边界：

- `submit` 返回不表示任务已经开始，它只表示任务对象已经创建并交给 `execute`。
- `FutureTask.run()` 捕获 Callable 抛出的 `Throwable` 并保存为异常结果；因此调用方通常在 `get()` 时看到 `ExecutionException`，而不是让异常直接从线程池工作线程冒出。

`submit(Runnable, result)` 会通过 `Executors.callable(runnable, result)` 把 Runnable 包成 Callable。Runnable 正常结束后，`get()` 返回构造时提供的固定结果；传入 `null` 时正常结果就是 `null`，不能据此判断任务是否尚未完成。

## 五个核心字段

| 字段 | 作用 | 并发边界 |
| --- | --- | --- |
| `volatile int state` | 记录 NEW 到终态的状态机 | 完成、异常和取消竞争的唯一状态提交点 |
| `Callable<V> callable` | 真正要运行的用户计算 | `run` 先复制到局部变量，完成清理时置空 |
| `Object outcome` | 保存正常值或异常对象 | 本身非 volatile，由 state 的发布与读取保护 |
| `volatile Thread runner` | 标识当前取得执行权的线程 | 通过 CAS 从 null 设置，防止重叠执行 |
| `volatile WaitNode waiters` | `get` 等待线程组成的 Treiber 栈顶 | CAS 压栈，完成时整栈摘除 |

不要把 `runner != null` 当成完成状态。Callable 执行期间 `state` 仍然是 `NEW`，而 `runner` 记录哪个线程正在执行；只有 `set`、`setException` 或 `cancel` 才推进 `state`。

## 先建立六条不变量

1. 普通运行期间没有单独的 `RUNNING` 状态；Callable 执行时 `state` 仍是 `NEW`。
2. `runner` CAS 只允许一个线程在同一时刻进入 Callable，终态又阻止之后再次运行，因此普通 `FutureTask` 最多成功执行一次。
3. 正常值和异常先写入非 volatile 的 `outcome`，再把 state 从 `COMPLETING` 发布为 `NORMAL` 或 `EXCEPTIONAL`；读取终态后才能安全解释 outcome。
4. `cancel(false)` 不发送中断；`cancel(true)` 只尝试中断当时的 runner，不能强制停止不协作的计算。
5. `get(timeout, unit)` 超时只让本次等待退出，不会自动取消 FutureTask，其他执行者和等待者可以继续。
6. `finishCompletion` 会逐个发放 unpark 许可，但等待线程真正恢复和取得 CPU 的顺序不受保证。

## 三条主调用链

### 正常完成

```text
run
  -> CAS runner: null -> currentThread
  -> callable.call()
  -> set(value)
       -> CAS state: NEW -> COMPLETING
       -> outcome = value
       -> ordered write state: NORMAL
       -> finishCompletion()
  -> runner = null
```

### 异常完成

```text
run
  -> callable.call() 抛 Throwable
  -> setException(error)
       -> CAS state: NEW -> COMPLETING
       -> outcome = error
       -> ordered write state: EXCEPTIONAL
       -> finishCompletion()
```

### 等待结果

```text
get
  -> 读取 state
  -> 若 state <= COMPLETING，进入 awaitDone
       -> 创建 WaitNode
       -> CAS 压入 waiters
       -> park / parkNanos
  -> report(terminalState)
       -> NORMAL: 返回 outcome
       -> EXCEPTIONAL: 抛 ExecutionException(outcome)
       -> 取消状态: 抛 CancellationException
```

## FutureTask 与 CompletableFuture 的职责差异

| 维度 | `FutureTask` | `CompletableFuture` |
| --- | --- | --- |
| 核心模型 | 一次 Callable/Runnable 的执行与结果 | 结果容器加依赖阶段图 |
| 执行入口 | 自身实现 `Runnable.run` | 异步任务、手工完成或上游阶段传播 |
| 等待节点 | `WaitNode` Treiber 栈 | `Signaller` 也是 Completion 节点 |
| 回调扩展 | 受保护的 `done()` 钩子 | 丰富的 CompletionStage 组合 API |
| 取消运行中任务 | `cancel(true)` 尝试中断 runner | `cancel(true)` 不控制底层 Supplier 中断 |
| 重复执行支持 | 子类可使用 `runAndReset` | 阶段只能完成一次，不提供复位执行 |

两者都使用无锁栈保存等待或依赖节点，但节点职责不同，不能把 `FutureTask.WaitNode` 理解成简化版阶段回调。需要一条可调度、可取消的独立任务时 FutureTask 很合适；需要组合多个异步结果时应优先使用 CompletableFuture 或更高层结构化并发 API。

## 阅读路径

1. [七态状态机与一次执行权](./state-machine.md)：跟踪 `run`、`set`、`setException`、`done` 和 `runAndReset`。
2. [等待栈、完成唤醒与取消](./waiters-cancel.md)：理解 `get/awaitDone`、`finishCompletion`、`removeWaiter` 和取消中断握手。
3. [断点实验手册](./debug-lab.md)：用受控案例观察正常、异常、超时、取消和复位执行。

如果还不熟悉 CAS，可先阅读 [AtomicInteger 与 CAS](../atomic/atomic-integer.md)；如果要理解 FutureTask 在线程池中的位置，可对照 [ThreadPoolExecutor 的 execute 决策](../threadpoolexecutor/)。

## JDK 8、17、21 的实现边界

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| 字段原子访问 | `sun.misc.Unsafe`、字段 offset | `VarHandle` | `VarHandle` |
| 终态发布 | `putOrderedInt` | `VarHandle.setRelease` | `VarHandle.setRelease` |
| 等待栈 CAS | `compareAndSwapObject` | `weakCompareAndSet` | `weakCompareAndSet` |
| 定时等待 | 先计算绝对 deadline | 改进溢出、零值和 `nanoTime` 调用处理 | 延续改进实现 |
| Future 新查询 API | 无 | 无 | 实现 `resultNow`、`exceptionNow`、`state` |

七个私有整数值不是业务协议，业务代码不应通过反射依赖它们。跨版本稳定契约是：任务只完成一次，`get` 按正常、异常、取消三类结果返回或抛出，成功取消后不能再用计算结果覆盖取消状态。
