# CompletableFuture：结果容器与依赖图

`CompletableFuture<T>` 同时实现 `Future<T>` 和 `CompletionStage<T>`。前者提供等待、查询和取消，后者把“结果完成后做什么”组织成依赖图。理解源码时不要把它只看成异步线程工具：一个已经完成的阶段、手工调用 `complete` 的阶段，以及完全同步的回调同样会经过它的完成协议。

本专题以 OpenJDK 8u 为主基线。JDK 9 之后增加超时、延迟执行器、只读视图等 API，但 `result + Completion 栈 + 完成传播` 的核心模型保持稳定，内部原子访问工具和局部实现会随版本演进。

[打开 JDK 8 / 17 / 21 版本对比 →](/jdk/version-comparison/)，可逐项核对超时与失败工厂、异常组合、非阻塞结果读取以及内部原子访问方式。

<TopicStudyPanel topic-id="openjdk8-java-util-concurrent-completablefuture" />

## 两个核心字段

| 字段 | 未完成时 | 完成后 | 作用 |
| --- | --- | --- | --- |
| `volatile Object result` | `null` | 普通值或 `AltResult` | 唯一完成状态与安全发布点 |
| `volatile Completion stack` | `null` 或依赖栈顶 | 等待被弹出执行 | 保存依赖当前阶段的动作 |

`result == null` 表示尚未完成。由于用户的正常结果也可能是 `null`，源码使用 `NIL` 这一特殊 `AltResult` 表示“正常完成且值为 null”。异常则保存在 `AltResult.ex` 中。

## result 的四种语义

| 内部表示 | 对外语义 |
| --- | --- |
| `null` | 尚未完成 |
| 普通对象 | 正常完成且结果非 null |
| `NIL` | 正常完成且结果为 null |
| `AltResult(ex != null)` | 异常完成或取消 |

完成方法使用 CAS 把 `result` 从 `null` 改为编码后的结果。因此正常的 `complete`、异步任务结束和依赖动作竞争时，只有第一次成功完成生效。

`obtrudeValue/obtrudeException` 是诊断或错误恢复用途的强制覆盖 API，不遵守“只完成一次”的常规路径，可能让已经触发的依赖和之后的读取观察到不同结果，不应作为普通业务控制流。

## supplyAsync 不等于创建一个专属线程

JDK 8 的默认入口：

```text
supplyAsync(supplier)
  -> asyncSupplyStage(asyncPool, supplier)
  -> new CompletableFuture()
  -> executor.execute(new AsyncSupply(future, supplier))
```

默认 `asyncPool` 通常是 `ForkJoinPool.commonPool()`；当公共池并行度不足以支持并行执行时，JDK 8 退化为每任务新建线程的执行器。不要根据常见线程名把公共池当成 API 契约。

生产代码应明确回答：

- 任务是 CPU 计算还是阻塞 I/O？
- 是否允许和进程内其他公共池任务争抢资源？
- 队列、并发度、拒绝与关闭由谁管理？
- 回调需要继承哪些日志或上下文信息？

对重要业务链路通常应传入受控 `Executor`，而不是默认使用公共池。

## 一个依赖阶段如何注册

以 `source.thenApply(fn)` 为例：

```text
thenApply(fn)
  -> uniApplyStage(null, fn)
  -> 创建 dependent
  -> 若 source 已完成，尝试立即执行
  -> 否则创建 UniApply 并压入 source.stack
  -> 再次 tryFire，处理注册与完成并发
```

注册前后源阶段可能恰好完成，所以源码不能只做一次状态判断。`tryFire(SYNC)` 既覆盖“源已经完成”的快速路径，也关闭“先判断未完成、还没压栈就被另一个线程完成”的竞态窗口。

## 同步方法和 Async 方法的线程语义

| 形式 | 执行位置 |
| --- | --- |
| `thenApply(fn)` | 可能由完成源阶段的线程执行，也可能由注册线程在源已完成时立即执行 |
| `thenApplyAsync(fn)` | 提交给默认异步执行器 |
| `thenApplyAsync(fn, executor)` | 提交给指定执行器 |

非 Async 方法的“同步”不是指调用时一定立刻运行，而是指依赖具备条件后无需强制切换到另一个执行器。回调里执行阻塞操作可能拖慢完成上游阶段的线程，并延迟同一完成栈上的其他动作。

## Completion 不是结果本身

`CompletableFuture` 节点保存阶段结果；`Completion` 节点保存等待触发的动作和上下游引用。常见类型包括：

| 类型 | 对应关系 |
| --- | --- |
| `UniApply` | 一个上游，函数转换结果 |
| `UniCompose` | 一个上游，函数返回另一个阶段并展平 |
| `BiApply` | 两个上游都完成后合并结果 |
| `OrApply` | 两个上游任一完成后执行 |
| `UniWhenComplete` | 观察值或异常 |
| `UniExceptionally` | 仅在异常路径恢复 |
| `Signaller` | `get/join` 等待线程的唤醒节点 |

这些节点通过无锁栈连接到源阶段。动作触发后会尽早清空对函数和上下游的引用，减少长链保留对象的时间。

## 阅读地图

```text
创建异步源阶段
  -> 编码并 CAS 写入 result
  -> postComplete 弹出 Completion
  -> tryFire 执行或提交回调
  -> 写入下游 result
  -> 继续传播
```

继续阅读：

1. [Completion 栈与完成传播](./completion-stack.md)：从 CAS 结果到 `postComplete/tryFire`。
2. [串行、组合与聚合](./composition.md)：区分 apply、compose、combine、allOf 和 anyOf。
3. [异常、取消与阻塞等待](./exception-cancel.md)：理解恢复边界、`get/join` 和取消语义。
4. [断点实验手册](./debug-lab.md)：用可重复案例观察线程和状态。

默认异步执行器与并行协作的下一层实现可继续阅读 [ForkJoinPool](../forkjoinpool/)；需要先掌握单个可取消异步结果时，则对照 [FutureTask](../futuretask/) 的七态状态机。
