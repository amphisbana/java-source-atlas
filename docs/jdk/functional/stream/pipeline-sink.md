# AbstractPipeline 与 Sink：反向包装，正向流动

`AbstractPipeline` 负责描述“有哪些 stage、彼此怎样连接、当前已知哪些流特征”；`Sink` 负责在求值时接收元素并把它传给下游。把组装结构与执行结构分开，是理解惰性和操作融合的关键。

## 一个 stage 保存什么

OpenJDK 8u 的每个 `AbstractPipeline` 主要持有：

| 字段 | 含义 |
| --- | --- |
| `sourceStage` | 整条流水线的头 stage |
| `previousStage / nextStage` | 双向 stage 链 |
| `sourceOrOpFlags` | 本 source 或本操作声明的 flags |
| `combinedFlags` | 从 source 到当前 stage 合并后的已知/清除状态 |
| `depth` | 当前 stage 距本段 source 的深度 |
| `linkedOrConsumed` | 防止重复链接或终止后重用 |
| source 上的 `sourceSpliterator/sourceSupplier` | 延迟取得的元素来源，消费后清空 |
| source 上的 `parallel/sourceAnyStateful` | 求值模式以及是否含 stateful stage |

头 stage 由 source Spliterator 或 supplier 构造，`depth=0`，`sourceStage=this`。追加中间操作时，新 stage 构造器执行：

```text
检查 previousStage 尚未 linkedOrConsumed
previousStage.linkedOrConsumed = true
previousStage.nextStage = this
this.previousStage = previousStage
this.combinedFlags = combineOpFlags(opFlags, previousStage.combinedFlags)
this.sourceStage = previousStage.sourceStage
this.depth = previousStage.depth + 1
若本操作 stateful，标记 sourceAnyStateful
```

这里“把 previous 标为已链接”不是说整条流水线已经执行，而是禁止从同一个 stage 再分叉出另一条链。调用者应始终接住中间操作返回的新 Stream。

## flags 不是一组简单 boolean

`StreamOpFlag` 同时表达某特征已知存在、已知被清除或仍需保留上游状态。常见特征包括：

- `DISTINCT`：元素已知互异；
- `SORTED`：已知排序；
- `ORDERED`：有遇见顺序；
- `SIZED`：输出规模可精确得知；
- `SHORT_CIRCUIT`：内部操作或终止操作可能提前结束。

操作会改变这些推理：

| 操作 | 典型 flags 影响 | 原因 |
| --- | --- | --- |
| `filter` | 清除 SIZED | 不能预先知道多少元素通过谓词 |
| `map` | 清除 SORTED、DISTINCT | 映射可能改变顺序关系并产生重复值 |
| `unordered` | 清除 ORDERED | 允许后续忽略遇见顺序约束 |
| `skip/limit` | JDK 8 清除 SIZED；JDK 17/21 注入 SIZE_ADJUSTING，有限 limit 还注入 SHORT_CIRCUIT | 切片数量可由上游精确尺寸和边界公式计算，但 JDK 8 尚未保留这项推理 |
| match/find terminal | 注入 SHORT_CIRCUIT，部分还可清除 ORDERED | 允许遍历循环检查取消 |

`combinedFlags` 是内部优化协议，不是 `Spliterator.characteristics()` 的简单原样复制。source 特征、每个 stage 和 terminal flags 会按各自适用范围组合；错误地给自定义 Spliterator 声明 SIZED/SORTED 等特征，可能让上层作出不成立的优化。

### SIZE_ADJUSTING 与 SIZED 不是同一个含义

`SIZED` 表示当前输出数量已经精确可知；`SIZE_ADJUSTING` 表示本 stage 会按确定公式改变上游数量。JDK 17/21 的顺序流水线会在 `AbstractPipeline.exactOutputSizeIfKnown` 中沿 stage 调用 `exactOutputSize(previousSize)`：

```text
skip(s):  max(0, previousSize - s)
limit(l): min(previousSize, l)
```

因此四元素 SIZED 源经过 `skip(1).limit(2)` 后仍能推导出 2。JDK 8 的 `SliceOps.flags` 直接设置 `NOT_SIZED`，同一流水线只能报告未知大小。这里是尺寸推理能力变化，不是切片结果变化；详细执行路径见 [终止遍历与短路](./short-circuit.md#skip-limit-为什么在新版-jdk-仍能保持精确尺寸)。

## evaluate 如何启动终止操作

终止操作到来时，`AbstractPipeline.evaluate(terminalOp)`：

```text
检查末 stage 尚未消费
linkedOrConsumed = true
取得并清空 source Spliterator 或 supplier
合并 terminalOp flags

isParallel == false
  → terminalOp.evaluateSequential(helper, spliterator)

isParallel == true
  → terminalOp.evaluateParallel(helper, spliterator)
```

顺序归约通常创建 terminal sink，再调用 `helper.wrapAndCopyInto`。并行操作会根据 terminal 类型创建 ReduceTask、FindTask、MatchTask、ForEachTask 等任务；并不是所有终止操作都共享完全相同的任务子类。

若并行流水线包含 stateful stage，`sourceSpliterator` 会在 stage 边界调用 `opEvaluateParallelLazy` 等路径，把流水线切成若干段并重新计算 depth 和 flags。`sorted/distinct/limit` 的并行行为因此不能只看顺序 `opWrapSink`。

## wrapSink 为什么从终止端反向走

考虑：

```text
source → filter → map → limit → findFirst
```

终止操作先提供最内层 `FindSink`。`wrapSink` 从当前末 stage 向 source 循环调用 `opWrapSink`：

```text
sink = FindSink
sink = limit.opWrapSink(flags, sink)   // LimitSink(FindSink)
sink = map.opWrapSink(flags, sink)     // MapSink(LimitSink(...))
sink = filter.opWrapSink(flags, sink)  // FilterSink(MapSink(...))
```

最终返回的最外层 Sink 接受 source 元素。反向包装是为了得到正向调用链：

```text
source element
  → FilterSink.accept(sourceValue)
  → MapSink.accept(mappedValue)
  → LimitSink.accept(mappedValue)
  → FindSink.accept(resultValue)
```

如果按 source 到 terminal 的方向逐个包装，最后得到的外层反而会是 limit，source 元素就无法先经过 filter。

## 动画：stage、Sink、短路与拆分

下面先用 `[1,2,3,4] → filter(even) → map(x10) → limit(1)` 展示惰性组装和 Sink 融合，再切换为 `[0,8)` 的 ArrayList 索引范围展示 `trySplit` 二叉分区。两段示例用于解释不同层次，不代表一次真实运行中 limit 后又重新拆分同一个四元素源。

<StreamSpliteratorAnimation />

## Sink 的四段生命周期

`Sink` 除了 `accept`，还定义三个控制入口：

```text
begin(size)
accept(element) ...
cancellationRequested()  // 短路路径反复查询
end()
```

### begin

`copyInto` 在遍历前调用最外层 Sink 的 `begin(exactSizeIfKnown)`。Chained Sink 默认向下游传播 size，但操作可修正它：

- `map` 不改变数量，可以继续传递 size；
- `filter` 不知道通过数量，会调用 `downstream.begin(-1)`；
- `limit` 可以根据上游 size、skip、limit 计算自己的最大输出规模；
- 数组或 Node builder 可利用精确大小预分配。

### accept

每次 `tryAdvance` 或 `forEachRemaining` 把一个源元素交给最外层 Sink。stateless stage 立即处理并选择是否调用 downstream；这就是操作融合发生的位置。

### cancellationRequested

Chained Sink 默认把取消查询转发给 downstream。`limit` 在剩余配额为 0 时返回 true，`FindSink` 在已有值后返回 true，match sink 在结果已确定后返回 true。返回 true 是请求遍历驱动器停止拉取，不会撤销已经执行的 action。

### end

遍历结束或短路退出后调用 `end()`，默认继续向下游传播。需要缓冲的 Sink 可在 end 时排序、刷新或完成结果。若用户 action 抛异常，正常 end 路径可能不会完成；Stream 不提供事务回滚。

## mapMulti 为什么能直接向下游推送

JDK 16 增加 `mapMulti`，让一个输入通过调用下游 Consumer 零次、一次或多次来产生结果：

```java
Stream.of(1, 2, 3, 4)
        .<Integer>mapMulti((value, downstream) -> {
            if (value % 2 == 0) {
                downstream.accept(value);
                downstream.accept(value * 10);
            }
        });
// 2, 20, 4, 40
```

它与 `map` 的“一进一出”不同，也不要求像 `flatMap` 那样为每个输入显式创建一个临时 Stream。实现分两层：

| 层次 | JDK 17/21 路径 | 作用 |
| --- | --- | --- |
| `Stream` 默认方法 | 每个输入先写入 `SpinedBuffer`，再把缓冲包装为 Stream 交给 `flatMap` | 第三方 Stream 实现不覆盖新方法也能获得正确语义 |
| `ReferencePipeline` 覆盖 | `mapper.accept(input, downstream)` | 标准流水线直接把零到多个结果送进现有 Sink 链，省去逐输入临时 Stream |

`mapMulti` 无法预先知道 mapper 会输出多少元素，因此 stage 会清除 SIZED，并在 `begin` 时向下游报告 `-1`。mapper 收到的 Consumer 只是当前调用期间的下游入口；把它保存起来、跨线程或在 mapper 返回后继续调用，结果未定义。

JDK 8 没有该 API。需要兼容 Java 8 的源码应继续使用 `flatMap`，或在适配层按运行版本选择实现；不要为了调用新版方法去反射并缓存内部 Sink。

## 原始类型 Sink 为什么存在

`Sink.OfInt/OfLong/OfDouble` 提供原始类型 `accept`，IntPipeline 等可以在内部避免每个元素装箱。错误地让对象形态入口接收原始专用数据会触发桥接或 Tripwire 诊断。

选择 `mapToInt/sum` 不只让 API 更贴近数值语义，也能减少装箱；但函数成本、数据量和 JIT 优化仍需实际测量，不能只凭类型推导固定性能差距。

## 一次融合遍历的变量轨迹

示例 source 为 `[1,2,3,4]`：

| 源元素 | filter even | map x10 | limit 剩余量 | terminal 结果 | 是否继续 |
| ---: | --- | ---: | ---: | --- | --- |
| 1 | false | 不调用 | 1 | 空 | 是 |
| 2 | true | 20 | 0 | `[20]` | cancellation=true |
| 3 | 不再拉取 | - | 0 | `[20]` | 否 |
| 4 | 不再拉取 | - | 0 | `[20]` | 否 |

这张表只适用于顺序、已知操作顺序的示例。并行短路允许多个叶任务同时在途，观察到的谓词调用次数可能大于找到结果所需的最小数量。

## 推荐断点

1. `AbstractPipeline(AbstractPipeline previousStage,int opFlags)`：观察 stage 链、depth 和 combinedFlags。
2. `AbstractPipeline.evaluate(TerminalOp)`：确认一次消费标记与顺序/并行分支。
3. `sourceSpliterator(int)`：观察 supplier 何时兑现、source 引用何时清空。
4. `wrapSink(Sink)`：从末 stage 向 previousStage 记录每次 opWrapSink 返回类型。
5. `copyInto`：区分 `forEachRemaining` 与 short-circuit 分支。
6. `ReferencePipeline.filter/map` 的匿名 `accept`：跟踪一个元素怎样正向流过。
7. terminal sink 的 `begin/accept/cancellationRequested/end`：记录生命周期和最终结果。

下一步阅读 [终止遍历与短路](./short-circuit.md)，把 cancellation 请求和实际停止位置连接起来。
