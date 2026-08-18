# Completion 栈与完成传播

## 动画：结果如何沿依赖链传播

动画使用一条成功链路展示同步和异步边界：`supplyAsync` 产生 21，普通 `thenApply` 在完成线程中算出 42，`thenApplyAsync` 再提交到指定执行器生成文本，最后 `join` 取得结果。

<CompletableFuturePipelineAnimation />

## 为什么依赖使用 Treiber 栈

每个未完成阶段都可能被多个线程同时追加依赖。JDK 8 使用 `stack` 和 CAS 实现 Treiber 栈：

```text
h = stack
completion.next = h
CAS(stack, h, completion)
```

CAS 失败说明其他线程先修改了栈顶，当前线程重新读取并重试。这里需要支持的是高并发注册和完成，不保证依赖动作按注册先后执行。

因此多个互相独立的 `thenRun` 挂在同一个源阶段上时，不能依赖其执行顺序。需要顺序时应显式链接为阶段链。

## UniCompletion 保存什么

一元依赖节点通常持有：

| 引用 | 作用 |
| --- | --- |
| `src` | 等待完成的上游阶段 |
| `dep` | 要被完成的下游阶段 |
| `fn` | 用户函数或动作 |
| `executor` | Async 形式的执行器，非 Async 时为 null |

`tryFire` 成功触发后会把这些字段清空。长链中如果节点一直保留上游、下游和闭包，哪怕业务已完成也会延长大量对象的生命周期。

## UniApply 的触发条件

`UniApply.tryFire(mode)` 最终尝试的核心条件是：

1. `src.result` 已非 null。
2. `dep.result` 仍为 null。
3. 当前节点成功取得执行权，避免重复运行函数。
4. 上游正常完成时调用 `fn.apply(value)`；上游异常时不调用函数，直接把异常传给下游。

函数成功返回后，下游通过 CAS 完成；函数抛出异常则把异常编码为 `CompletionException` 路径。无论哪种结果，下游完成后都需要继续触发自己的依赖。

## claim 如何切换执行器

带执行器的 Completion 第一次被触发时不会直接执行业务函数。`claim()` 尝试取得节点执行权，并把节点自身提交给 executor。执行器随后调用节点的 `run/exec`，再次进入 `tryFire(ASYNC)`，这时才真正执行函数。

可以把它理解为两段：

```text
完成源阶段的线程：发现依赖 -> 提交 Completion
目标执行器线程：tryFire(ASYNC) -> 执行用户函数 -> 完成下游
```

提交执行器本身也可能抛出拒绝异常，届时下游会异常完成，而不是静默跳过。

## postComplete 为什么不用递归到底

源阶段完成后调用 `postComplete()`。它不断从 Completion 栈弹出节点并触发 `tryFire(NESTED)`。如果每完成一个下游都递归调用下一个阶段，超长同步链可能耗尽线程栈。

JDK 8 通过循环、返回待传播的 dependent，以及在必要时把尚未处理的节点重新压回根栈，把深度优先递归改造成受控的迭代传播。

三个模式的意义：

| 模式 | 值 | 典型来源 | 含义 |
| --- | --- | --- | --- |
| `SYNC` | 0 | 注册后立即探测 | 当前线程可以同步尝试动作 |
| `ASYNC` | 1 | executor/ForkJoinTask 调用 | 已处于异步执行阶段 |
| `NESTED` | -1 | `postComplete` 传播 | 返回下游让外层循环继续，限制递归深度 |

这些数值和类名属于实现细节，稳定语义是阶段完成会触发满足条件的依赖，并把结果继续传播。

## 注册和完成的竞态

考虑两个线程：

```text
线程 A：判断 source 尚未完成
线程 B：完成 source，并发现 stack 为空
线程 A：把 UniApply 压入 stack
```

如果线程 A 压栈后不再检查，动作将永远留在一个已经完成的源阶段上。JDK 的 stage 构造方法在压栈后调用 `tryFire(SYNC)`；若源已完成，当前线程会触发动作。反过来，如果 B 在压栈之后完成，`postComplete` 会弹出该节点。

源码阅读时应把“压栈 + 再次探测”看成一个完整协议，而不是多余调用。

## 一个阶段只能正常完成一次

`completeValue`、`completeThrowable`、异步任务和依赖动作都通过 CAS 把 `result` 从 null 改为最终编码。多个完成者竞争时：

- 第一个 CAS 成功者确定最终结果。
- 后续正常完成尝试返回 false 或发现已有结果。
- 每个完成入口仍可能调用 `postComplete`，帮助处理尚未触发的依赖。

`postComplete` 可以被多个线程并发调用；弹栈 CAS 确保同一节点不会被普通路径重复取走，Completion 自身的执行权声明再防止用户函数重复执行。

## 已完成阶段的快速路径

在一个已经完成的 future 上调用非 Async 的 `thenApply` 时，源码可以直接计算并返回一个已经完成的新阶段，不必把节点长期留在栈中。由此带来两个容易忽略的现象：

- 回调可能在调用 `thenApply` 的当前线程里立刻执行。
- 如果回调耗时，注册阶段本身就会阻塞。

Async 形式即使源已完成，也应通过执行器调度函数。

## 内存可见性

上游任务先产生结果对象，再通过 CAS/volatile 语义发布到 `result`。依赖动作读取到非 null 的 `result` 后，可以看到结果发布前的写入。下游再以相同方式发布自己的结果。

这保证阶段间结果传递的内存可见性，但不会自动使结果对象之后的任意可变操作线程安全。最好传递不可变值，或明确对象自己的同步协议。

## 断点变量

JDK 8 建议观察：

- `uniApplyStage`：`e`、`d`、`c` 与源 `result`。
- `tryPushStack`：旧 `stack`、`c.next`、CAS 结果。
- `UniApply.tryFire`：`mode`、`src.result`、`dep.result`、`executor`。
- `postComplete`：当前 future `f`、弹出的节点 `h`、返回的 dependent `d`。

不要把栈内顺序写成业务依赖；它只服务于完成调度。

