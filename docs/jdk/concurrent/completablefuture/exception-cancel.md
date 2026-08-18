# 异常、取消与阻塞等待

## 异常也存放在 result 中

`CompletableFuture` 没有一套独立的“失败状态机”。正常值、null、异常和取消都编码进 `result`：

```text
正常非 null -> result = value
正常 null    -> result = NIL
异常          -> result = AltResult(exception)
取消          -> result = AltResult(CancellationException)
```

依赖节点读取上游 `result` 后决定执行函数还是传播异常。普通 `thenApply/thenAccept/thenRun` 只处理正常路径，上游异常时用户回调不会执行。

## exceptionally、handle、whenComplete

| 方法 | 正常完成时调用 | 异常完成时调用 | 能改变结果类型 | 典型用途 |
| --- | --- | --- | --- | --- |
| `exceptionally` | 否，直接传正常值 | 是 | 否，返回同类型替代值 | 失败兜底 |
| `handle` | 是 | 是 | 是 | 把成功/失败统一转换 |
| `whenComplete` | 是 | 是 | 否，保留上游值类型 | 日志、指标、资源收尾 |

`whenComplete` 不是可靠的恢复操作：它返回的新阶段通常保留上游结果。若上游正常而观察动作抛异常，新阶段会异常完成；若上游本来已异常且观察动作也抛异常，源码会优先保留上游异常结果。

双异常的附加信息存在版本差异：JDK 8u432 会丢弃观察动作抛出的第二个异常；JDK 17 会把第二个异常加入上游异常的 `suppressed` 列表。跨版本代码只能依赖“上游异常优先”这一公开结果，不应依赖 suppressed 的具体内容。

如果确实要把异常转换为正常值，应使用 `exceptionally` 或 `handle` 并明确返回替代值。

## 异常恢复放置位置会改变范围

```text
source
  -> thenApply(parse)
  -> exceptionally(fallback)
  -> thenApply(render)
```

这里的 `exceptionally` 可以恢复 source 或 parse 产生的异常，恢复后 render 会收到正常替代值。若把 `exceptionally` 放到 render 之后，它覆盖的失败范围也会包含 render。

每个阶段都是新对象。调用 `source.exceptionally(...)` 不会改变 source 自身，只会返回一个带恢复策略的下游阶段；如果丢弃返回值，后续使用 source 仍会观察原异常。

## get 与 join 的异常外观

| 方法 | 中断 | 业务异常 | 超时 |
| --- | --- | --- | --- |
| `get()` | 抛 `InterruptedException` | 包装为受检 `ExecutionException` | 不支持超时参数 |
| `get(timeout, unit)` | 抛 `InterruptedException` | 包装为 `ExecutionException` | 抛 `TimeoutException` |
| `join()` | 不因中断提前结束；完成后恢复中断标记 | 抛非受检 `CompletionException` | JDK 8 无超时参数 |

直接取消的 future 在 `get/join` 时抛 `CancellationException`。依赖于被取消阶段的下游通常以 `CompletionException` 包装取消原因异常完成。

等待中的 `get()` 检测到中断后清除线程的中断状态并抛出 `InterruptedException`；`join()` 会记录中断但继续等待，取得结果后重新设置当前线程的中断标记。业务不能把 `join()` 当成吞掉中断，也不能期望它以受检异常提前返回。

在声明受检异常的边界代码中可使用 `get`；在函数式阶段链或已经明确统一异常策略的位置通常使用 `join`。不要只为了少写 catch 就忽略异常类型和中断语义。

## waitingGet 与 Signaller

当 `result` 尚未完成时，JDK 8 的 `get/join` 最终进入 `waitingGet`。等待线程创建 `Signaller` 并把它作为 Completion 压到当前 future 的栈上。

`Signaller` 同时实现 `ForkJoinPool.ManagedBlocker`：

1. 完成前先进行有限自旋。
2. 仍未完成则把等待节点压栈。
3. 通过 `ForkJoinPool.managedBlock` 阻塞。
4. future 完成时 `postComplete` 触发 Signaller，调用 `LockSupport.unpark`。

ManagedBlocker 让 ForkJoinPool 有机会对阻塞作补偿，但不代表在公共池工作线程里任意 `join` 都没有饥饿或吞吐风险。尤其是相互等待、外部锁和受限自定义线程池仍可能形成死锁。

## 为什么阶段链优于中途 join

危险模式：

```java
CompletableFuture<Result> result = CompletableFuture.supplyAsync(() -> {
    Data data = anotherFuture.join();
    return calculate(data);
}, smallExecutor);
```

如果 `anotherFuture` 的任务也必须在已经占满的 `smallExecutor` 中运行，所有线程可能都在等待尚未获得执行机会的任务。

优先表达为依赖关系：

```java
CompletableFuture<Result> result =
        anotherFuture.thenApplyAsync(this::calculate, smallExecutor);
```

只有在系统边界确实需要把异步结果转换为同步返回时，再集中 `get/join`。

## cancel 不会中断正在执行的任务

JDK 8 的 `cancel(boolean mayInterruptIfRunning)` 文档明确说明参数在本实现中没有效果。它尝试把尚未完成的 `result` 设置为 `CancellationException` 并触发依赖传播，但不使用中断控制底层处理。

因此：

- `cancel(true)` 不等价于 `Thread.interrupt()`。
- Supplier 可能仍在执行并产生外部副作用，只是它之后无法覆盖已取消的结果。
- 依赖阶段会沿异常路径完成。

需要可停止任务时，业务函数必须协作检查独立的取消令牌、超时预算或线程中断，并确保执行器和底层 I/O 支持相应取消机制。

## 超时能力的版本边界

JDK 8 只有阻塞端的 `get(timeout, unit)`；超时不会自动完成原 future。常见做法是用调度器完成一个超时阶段，再与业务阶段组合，但必须管理调度任务的取消和执行器生命周期。

JDK 9 增加：

- `orTimeout`：超时后让当前 future 异常完成。
- `completeOnTimeout`：超时后以给定值完成。
- `delayedExecutor`：延迟提交执行器。

这些方法改变的是完成时机，不会自动中断底层业务任务。

## CancellationException 也是异常完成

`isCancelled()` 只在内部异常是 `CancellationException` 时返回 true；`isCompletedExceptionally()` 对取消、显式 `completeExceptionally` 和回调抛错都返回 true。

监控时应分别统计：

- 正常完成。
- 业务或系统异常。
- 主动取消。
- 调用方等待超时。

等待超时可能发生时原任务仍在运行，不能简单等同于任务失败。

## 常见异常处理错误

| 错误 | 后果 | 改进 |
| --- | --- | --- |
| 调用 `exceptionally` 后丢弃返回阶段 | 原 future 仍然异常 | 继续使用返回的新阶段 |
| 每层都记录同一异常 | 日志重复且缺少责任边界 | 在明确边界记录一次并保留上下文 |
| 在 `whenComplete` 中做耗时阻塞 | 拖慢完成传播线程 | 提交到合适执行器或拆分阶段 |
| 把 `cancel(true)` 当作强制停止 | 底层任务继续执行 | 使用协作式取消和超时预算 |
| 公共池回调里大量 join | 线程饥饿与尾延迟 | 用组合方法表达依赖并隔离执行器 |

## JDK 17/21 提示

较新 JDK 使用 VarHandle 等内部机制并扩展 API，但取消不等于中断、非 Async 回调可由完成线程执行、Async 默认通常使用公共池等核心语义仍需关注。虚拟线程能降低阻塞线程成本，却不会自动修复阶段图中的循环依赖、无界并发或被取消任务的外部副作用。
