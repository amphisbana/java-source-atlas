# FutureTask 七态状态机与一次执行权

`FutureTask` 用一个 volatile `state` 同时回答“是否还能执行”“结果能否读取”“取消是否要求中断”三个问题。理解源码的关键不是只记住七个数字，而是区分三个中间状态与四个终态，并看清 `runner` 不属于 state 状态机。

## 七个状态各自保护什么

| 状态 | JDK 8 数值 | 是否终态 | 含义 |
| --- | ---: | --- | --- |
| `NEW` | 0 | 否 | 尚未完成；也可能正在执行 Callable |
| `COMPLETING` | 1 | 否 | 某线程已赢得正常或异常结果发布权，正在写 outcome |
| `NORMAL` | 2 | 是 | outcome 保存正常结果，包括 null |
| `EXCEPTIONAL` | 3 | 是 | outcome 保存 Callable 抛出的 Throwable |
| `CANCELLED` | 4 | 是 | `cancel(false)` 成功，不请求中断 runner |
| `INTERRUPTING` | 5 | 否 | `cancel(true)` 已赢得取消权，正在尝试中断 runner |
| `INTERRUPTED` | 6 | 是 | 中断请求阶段已经结束，不代表 Callable 必然停止 |

允许的状态迁移只有四条：

```text
NEW -> COMPLETING -> NORMAL
NEW -> COMPLETING -> EXCEPTIONAL
NEW -------------> CANCELLED
NEW -> INTERRUPTING -> INTERRUPTED
```

所有终态都不可逆。`NORMAL` 之后不能取消，`CANCELLED` 之后 Callable 即使返回也不能发布正常值，`EXCEPTIONAL` 之后再次调用 `run` 也直接返回。

## 为什么没有 RUNNING 状态

执行线程取得 `runner` 后，`state` 仍然保持 `NEW`。源码有意把“谁正在执行”和“谁赢得最终状态”分开：

```text
runner：控制 Callable 的一次执行权
state：控制正常、异常、取消的最终提交权
```

因此运行中的 FutureTask 仍可能成功执行 `cancel(false)` 或 `cancel(true)`。取消线程通过 CAS 把 state 从 NEW 推到取消路径；执行线程之后调用 `set/setException` 时 CAS 失败，计算结果不会覆盖取消状态。

这也解释了两个公开查询：

- `isDone()` 实现为 `state != NEW`。Callable 正在运行但还未发布结果时通常返回 false。
- `isCancelled()` 实现为 `state >= CANCELLED`。`INTERRUPTING` 这个短暂中间状态也被视为已取消。

`COMPLETING` 时 `isDone()` 已经返回 true，但 outcome 可能仍在写入。`get()` 不会直接调用 report，而是在 `awaitDone` 中等待 state 大于 COMPLETING，避免读取半发布结果。

## run 如何只让一个线程执行 callable

JDK 8 的 `run()` 入口包含两道门：

```text
if (state != NEW ||
    !CAS(runner, null, currentThread))
    return
```

第一道状态检查拒绝已经完成或取消的任务，第二道 runner CAS 决定并发调用 `run()` 时谁进入 Callable。CAS 成功后仍要再次检查 `callable != null && state == NEW`，用于关闭下面的取消竞态：

```text
执行线程：读到 state == NEW ---- CAS runner 成功 ---- 再读 state
取消线程：                  CAS state -> CANCELLED
```

如果取消发生在两次 state 检查之间，执行线程已经成为 runner，但不会调用 Callable。若取消发生在 Callable 已经开始之后，Callable 可以继续运行；只是最后的 `set` 或 `setException` 无法再从 NEW 取得结果发布权。

Callable 成功与失败分支是：

```text
try:
  result = callable.call()
  ran = true
catch Throwable error:
  ran = false
  setException(error)

if ran:
  set(result)
```

源码捕获的是 `Throwable`，所以普通异常和 Error 都会成为 EXCEPTIONAL 结果。`get()` 再统一用 `ExecutionException` 包装保存的 Throwable。业务仍不应把 Error 当成可常规恢复的失败类型。

## runner 为什么最后才清空

`run` 的 finally 先执行 `runner = null`，再重新读取 state：

```text
runner = null
s = state
if (s >= INTERRUPTING)
  handlePossibleCancellationInterrupt(s)
```

runner 必须保持非 null，直到结果或取消状态稳定，否则另一个 `run()` 可能在第一个 Callable 尚未退出时取得执行权。清空 runner 后重新读 state，则是为了检测并等待一个已经赢得 `cancel(true)`、但尚未完成 interrupt 调用的线程。

这个时序不是在 finally 中“顺便清字段”。它与 `INTERRUPTING -> INTERRUPTED` 握手共同避免针对旧任务的迟到中断泄漏到同一工作线程随后执行的新任务。完整取消流程见 [cancel false 和 cancel true 的准确边界](./waiters-cancel.md#cancel-false-和-cancel-true-的准确边界)。

## set 和 setException 的两阶段发布

正常与异常完成使用相同的两阶段协议：

```text
set(value):
  CAS state: NEW -> COMPLETING
  outcome = value
  ordered write state: NORMAL
  finishCompletion()

setException(error):
  CAS state: NEW -> COMPLETING
  outcome = error
  ordered write state: EXCEPTIONAL
  finishCompletion()
```

第一阶段 CAS 有两个职责：

1. 在正常、异常和取消竞争者中选出唯一完成者。
2. 用 COMPLETING 告诉读取者“完成权已确定，但 outcome 还不能解释”。

第二阶段先写普通字段 outcome，再以 release/ordered 语义发布唯一终态。等待线程读取到 NORMAL 或 EXCEPTIONAL 后，可以看到此前写入的 outcome。由于状态只能从 COMPLETING 走到一个唯一终态，这一步不需要再做 CAS。

不要把过程简化为 `outcome = value; state = NORMAL`。如果没有中间状态，`isDone/get/cancel` 很难在并发下区分尚未开始写结果、正在写结果和结果已经安全发布。

## report 如何解释 outcome

`get()` 得到终态后调用私有 `report(s)`：

| 终态 | outcome 内容 | 对外结果 |
| --- | --- | --- |
| `NORMAL` | V，包括 null | 强制转换并返回 V |
| `EXCEPTIONAL` | Throwable | 抛 `ExecutionException(cause)` |
| `CANCELLED/INTERRUPTED` | 不作为结果读取 | 抛新的 `CancellationException` |

`FutureTask` 不需要像 CompletableFuture 那样用特殊对象编码正常 null，因为 state 已经单独区分 NEW 和 NORMAL。

## finishCompletion 在哪个时点执行

`set`、`setException` 和成功的 `cancel` 都只在 state 已经到达最终状态后调用 `finishCompletion()`。它按下面顺序收尾：

1. CAS 把共享 `waiters` 栈顶替换为 null，取得整条旧链。
2. 从旧栈顶开始，清空每个节点的 thread 并调用 `LockSupport.unpark`。
3. 断开节点 next，帮助 GC 尽早回收。
4. 调用受保护的 `done()`。
5. 把 `callable` 置为 null，减少闭包和任务输入的保留时间。

等待者被 unpark 后会重新检查 state，不是直接接收一个传递过来的结果对象。完成线程逐个调用 unpark，也不代表等待线程按栈顺序恢复；操作系统调度决定它们之后的运行顺序。

## done 钩子的时序与约束

`done()` 是给子类的完成通知模板方法，正常、异常和取消都会调用一次。钩子执行时 state 已经是终态，因此可以调用 `isCancelled()`，也可以用非阻塞的 `get()` 读取已发布结果。

需要特别注意实际时序：`finishCompletion` 先 unpark 所有等待者，再调用 done。某个等待线程可能在 done 尚未结束时已经从 `get()` 返回，所以 done 不是“所有结果消费者之前必定完成”的屏障。

一个可靠的 done 实现应当：

- 快速、非阻塞，避免拖住执行 `run/cancel` 的线程。
- 自己处理回调异常；若 unchecked 异常从 done 冒出，FutureTask 虽已到终态，但当前 `run/cancel` 调用可能异常返回，后续 callable 清理也可能被跳过。
- 不假定具体执行线程。正常完成时通常是 runner，取消完成时通常是调用 cancel 的线程。
- 不再次调用可能形成外部锁循环的业务流程。

## runAndReset 为何成功后仍是 NEW

`runAndReset()` 是受保护扩展点，供本身需要重复执行的子类使用。它同样用 runner CAS 取得执行权，但 Callable 正常返回时不调用 `set(result)`：

```text
CAS runner
  -> callable.call()
  -> 正常：不保存结果，state 保持 NEW
  -> 异常：setException，进入 EXCEPTIONAL
finally:
  runner = null
  处理可能的取消中断
return ran && state == NEW
```

因此一次成功的 reset 运行不会：

- 产生可供 `get()` 返回的 outcome；
- 调用 finishCompletion 或 done；
- 唤醒已经错误地等待这个仍为 NEW 的任务的线程；
- 清空 callable。

`ScheduledThreadPoolExecutor` 的周期任务在每一轮到期时调用 `runAndReset()`。只有本轮正常结束且没有被并发取消，返回值才为 true，调度器才计算下一次触发时间并重新入队。某一轮抛异常或被取消后，状态进入终态，不再安排下一轮。

这个方法不是公开的“复活已完成 FutureTask”。NORMAL、EXCEPTIONAL 或取消状态都不能重置回 NEW；只有子类在任务一直保持 NEW 的周期执行协议中使用它。

## 正常完成与取消竞争的线性化点

Callable 返回不等于 FutureTask 已经成功完成。真正决定最终结果的是接下来的 state CAS：

```text
执行线程 set:    CAS NEW -> COMPLETING
取消线程 cancel: CAS NEW -> CANCELLED / INTERRUPTING
```

谁先成功，谁决定对外结果。可能出现：

- Callable 已经算出值，但 cancel 先 CAS 成功，最终仍是取消。
- cancel 读到 NEW，但 set 先 CAS 成功，cancel 返回 false，最终是正常结果。
- Callable 抛异常，但 cancel 先赢，`setException` 失败，调用方只看到取消。

因此自动测试应断言公开结果和人为控制的先后关系，不应依靠一次随机竞争去猜测某个分支一定获胜。

## JDK 17 和 21 的访问差异

OpenJDK 8u 用 `Unsafe.compareAndSwapInt/Object` 操作 state、runner、waiters，并用 `putOrderedInt` 发布最终 state。JDK 17/21 改用三个 `VarHandle`：`STATE`、`RUNNER`、`WAITERS`，分别调用 `compareAndSet`、`weakCompareAndSet` 和 `setRelease`。

这些底层 API 名称变化没有改写核心协议：先竞争 NEW，写 outcome，再 release 发布终态；run 仍以 runner CAS 控制重叠执行。JDK 21 还把内部状态映射到新的 `Future.State`，但 `Future.State.RUNNING` 只是公开查询分类，不表示 FutureTask 新增了一个私有 RUNNING 整数状态。
