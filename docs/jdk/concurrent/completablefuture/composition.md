# 串行、组合与聚合

`CompletionStage` 的方法很多，但可以先按“等待几个上游、是否消费值、是否返回新阶段”分类。分类比记忆全部方法名更可靠。

## thenApply、thenAccept、thenRun

三者都只等待一个上游正常完成：

| 方法 | 能读取上游值 | 回调有返回值 | 下游类型 |
| --- | --- | --- | --- |
| `thenApply(Function)` | 是 | 是 | `CompletableFuture<U>` |
| `thenAccept(Consumer)` | 是 | 否 | `CompletableFuture<Void>` |
| `thenRun(Runnable)` | 否 | 否 | `CompletableFuture<Void>` |

如果上游异常完成，普通转换或消费回调不会执行，异常结果会传播到下游。

## thenApply 与 thenCompose

假设 `loadUser(id)` 已返回 `CompletableFuture<User>`，而 `loadOrders(user)` 也返回异步阶段：

```java
CompletableFuture<CompletableFuture<List<Order>>> nested =
        userFuture.thenApply(this::loadOrders);

CompletableFuture<List<Order>> flat =
        userFuture.thenCompose(this::loadOrders);
```

`thenApply` 只转换一次值，因此函数返回的 future 会成为普通结果，形成两层容器。`thenCompose` 使用 `UniCompose` 把函数返回阶段的最终结果转发到新的 dependent，得到平坦链路。

判断规则：

- 函数返回普通值，用 `thenApply`。
- 函数返回 `CompletionStage<U>`，通常用 `thenCompose`。

不要在 `thenApply` 中对另一个 future 调用 `join()` 来模拟 compose；这会把非阻塞依赖关系改成线程阻塞，还可能占满有限执行器。

## thenCombine 等待两个上游

```text
priceFuture ----\
                 BiApply -> totalFuture
countFuture ----/
```

`thenCombine` 使用 `BiCompletion`。只有两个源阶段的 `result` 都非 null 时，合并函数才具备执行条件。一个 BiCompletion 主要挂在一个源的栈上，另一个源通过 `CoCompletion` 转发触发，避免重复持有完整动作。

两个上游并行启动并不由 `thenCombine` 保证，而取决于它们之前如何创建和使用执行器。`thenCombine` 只定义依赖关系。

## both 与 either 两组语义

| 等待关系 | 有返回值 | 消费值 | 只运行动作 |
| --- | --- | --- | --- |
| 两者都完成 | `thenCombine` | `thenAcceptBoth` | `runAfterBoth` |
| 两者任一满足条件 | `applyToEither` | `acceptEither` | `runAfterEither` |

`either` 系列在异常竞争下不应被当成“自动选择第一个成功结果”的容错工具。JDK 版本和完成时序可能影响异常如何传播；需要“两个请求竞速，失败者不影响成功者”的业务语义时，应先把每个分支显式转换为可比较的成功/失败结果，再定义选择规则。

## allOf 如何构建依赖树

`CompletableFuture.allOf(cfs...)` 不按数组顺序串成长链，而是在 JDK 8 中通过 `andTree` 递归构建较平衡的 `BiRelay` 树。这样大量阶段完成时的传播深度保持在对数级附近。

它返回 `CompletableFuture<Void>`：

```java
CompletableFuture<Void> all = CompletableFuture.allOf(a, b, c);
all.join();
ResultA ra = a.join();
ResultB rb = b.join();
```

`allOf` 只表达“所有输入都已完成”，不会自动把不同泛型结果收集成列表。常见做法是在 `allOf` 完成后逐个 `join` 已完成的输入；此时 `join` 不再等待，但仍可能抛出各自异常。

如果任一输入异常完成，聚合阶段也会异常完成。不要把 JDK 8 的 `allOf` 当成首错立即返回的 fail-fast 操作；内部 relay 需要两个分支都有结果才能继续向上完成。

边界：

- `allOf()` 没有输入时返回已正常完成且值为 null 的阶段。
- 传入数组或其中元素为 null 会抛出 `NullPointerException`。

## anyOf 的结果类型与空输入

`anyOf(cfs...)` 通过 `orTree` 建立任一完成传播树，返回 `CompletableFuture<Object>`，因为输入阶段可能具有不同结果类型。

空输入时，JDK 8 返回一个尚未完成的 future，而不是一个 null 结果。没有任何输入能触发它，除非调用者之后手工完成该对象。

同样不要把 `anyOf` 直接等同于“第一个成功值”。某个输入首先异常完成时，聚合阶段可以异常完成。

## Async 后缀放在哪一段

以下链路包含两个不同的调度问题：

```java
CompletableFuture
        .supplyAsync(this::load, ioExecutor)
        .thenApply(this::parse)
        .thenApplyAsync(this::calculate, cpuExecutor);
```

- `load` 由 `ioExecutor` 执行。
- `parse` 是非 Async 动作，通常由完成 `load` 的线程继续执行；如果注册时上游已完成，也可能由注册线程执行。
- `calculate` 明确提交给 `cpuExecutor`。

每一个 Async 后缀只约束对应动作，不会让整条链永久绑定同一个执行器。

## 同一源上的多个依赖没有顺序保证

```java
source.thenRun(actionA);
source.thenRun(actionB);
```

A 和 B 都压入 `source.stack`，不要依赖谁先运行。需要 B 严格在 A 之后时应写成：

```java
source.thenRun(actionA).thenRun(actionB);
```

即使显式成链，也应避免用共享可变状态暗中传值；把上一步结果作为阶段结果传给下一步更清晰。

## 组合图的资源边界

依赖图不会自动提供背压。循环创建大量尚未完成的 future 会同时保留：

- 上游和下游阶段。
- Completion 节点。
- 捕获业务对象的 lambda。
- 执行器队列中的异步任务。

对无限流、批量请求或高基数任务，应在 CompletableFuture 之外限制并发度、队列长度和超时，或选择具有背压语义的模型。

## 选择方法的简表

| 需求 | 推荐入口 |
| --- | --- |
| 同步转换一个结果 | `thenApply` |
| 发起下一个异步阶段并展平 | `thenCompose` |
| 两个结果都到齐后合并 | `thenCombine` |
| 观察结果但尽量保留原结果 | `whenComplete` |
| 同时把成功或失败转换成新值 | `handle` |
| 仅在失败时给出替代值 | `exceptionally` |
| 等待一组阶段全部结束 | `allOf` |
| 任一阶段结束就继续 | `anyOf`，并自行定义异常策略 |

