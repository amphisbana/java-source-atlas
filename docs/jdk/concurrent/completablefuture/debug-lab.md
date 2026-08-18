# CompletableFuture 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/concurrent/CompletableFutureDebugLab.java
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.CompletableFutureDebugLab
```

## 实验一：同步与异步回调线程

案例使用两个有明确线程名前缀的单线程执行器：源任务在 `atlas-io` 上完成，普通 `thenApply` 通常继续使用完成线程，`thenApplyAsync(..., cpuExecutor)` 切换到 `atlas-cpu`。

建议断点：

1. `asyncSupplyStage`。
2. `AsyncSupply.run()`。
3. `uniApplyStage`。
4. `UniApply.tryFire(int)`。
5. `postComplete()`。

观察：

- `result` 从 null 变为普通值。
- `source.stack` 中的 `UniApply` 何时被弹出。
- 非 Async 节点的 `executor` 为 null。
- Async 节点第一次触发只提交任务，随后以 `ASYNC` 模式真正执行函数。

线程调度不是绝对顺序保证，案例输出只用于观察本次运行，不应写成业务断言。

## 实验二：异常跳过普通转换

源 Supplier 抛出异常后，在 `UniApply.tryFire` 观察上游 `result` 为带异常的 `AltResult`。普通转换函数不会调用，下游复制异常结果；`exceptionally` 节点随后执行并返回替代值。

重点变量：

- 上游 `result` 与 `AltResult.ex`。
- 普通转换对应节点的 `fn` 是否进入。
- 恢复阶段的返回值如何写入新 dependent。

## 实验三：thenCompose 展平

在 `UniCompose.tryFire` 观察用户函数返回第二个 `CompletableFuture`。如果返回阶段已经完成，可以直接转发结果；尚未完成时会注册 relay，等待它完成后再完成 compose 的 dependent。

不要把 `thenCompose` 理解为“自动创建线程”，它只建立阶段到阶段的依赖。

## 实验四：allOf 聚合

对三个独立 future 调用 `allOf`，在 `andTree` 和 `BiRelay.tryFire` 观察平衡依赖树。聚合阶段只产生 null 结果；真实值仍保存在原输入 future 中。

## 实验五：join 等待节点

创建一个尚未完成的 future，让一个线程先调用 `join`，另一个线程稍后 `complete`。建议断点：

- `waitingGet(false)`。
- `Signaller` 构造方法。
- `tryPushStack`。
- `Signaller.tryFire`。

确认等待线程通过 Completion 节点进入同一个栈，并在完成传播时被 `unpark`。

## 版本提示

JDK 8 使用 `sun.misc.Unsafe` 操作 `result/stack`。较新 JDK 的原子访问实现不同，私有节点类型也可能调整。跨版本调试应以公开阶段方法、结果发布和完成传播语义定位，不要依赖字段偏移量。

## 实验完成标准

- 能画出 source、Completion 和 dependent 三者关系。
- 能解释为何注册节点后还要再次 `tryFire`。
- 能区分普通与 Async 回调的调度边界。
- 能说明 `thenApply` 和 `thenCompose` 的结果层级差异。
- 能说明 `allOf` 不收集结果、取消不强制中断任务。
- 能解释 `get`、`join` 和超时等待的异常差异。

