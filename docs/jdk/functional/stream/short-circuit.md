# 终止遍历与短路：何时停止拉取元素

短路的本质不是“某个 lambda 自己 break”，而是 Sink 报告已经不需要更多元素，遍历驱动器在下一次推进前检查该请求。顺序流可以很快停止；并行流还要处理多个在途分区、共享结果和遇见顺序。

## copyInto 的两条遍历路径

`AbstractPipeline.copyInto` 根据合并 flags 选择：

```text
不含 SHORT_CIRCUIT
  wrappedSink.begin(exactSize)
  spliterator.forEachRemaining(wrappedSink)
  wrappedSink.end()

含 SHORT_CIRCUIT
  copyIntoWithCancel(wrappedSink, spliterator)
```

ReferencePipeline 的取消遍历核心近似：

```text
do {
    // 首轮只进入条件判断
} while (!sink.cancellationRequested()
         && spliterator.tryAdvance(sink));
```

每次拉取新元素前先问 Sink 是否取消。已经进入 `accept` 的当前元素不会被回滚；当它让结果成立后，下一轮检查才停止。

## anyMatch、allMatch、noneMatch

`MatchOps` 用两个布尔策略描述三种匹配：谓词何时使结果确定，以及短路时的结果值。

| 操作 | 何时短路 | 空流结果 |
| --- | --- | --- |
| `anyMatch(p)` | 首个 `p == true` | false |
| `allMatch(p)` | 首个 `p == false` | true |
| `noneMatch(p)` | 首个 `p == true` | true |

match sink 的 `accept` 在结果确定时设置 `stop=true` 和最终 value，`cancellationRequested()` 返回 stop。空流的 allMatch/noneMatch 为 true 是逻辑量化的单位值，不代表谓词曾执行。

谓词抛异常会直接中止流水线并向调用者传播，不会转换成“不匹配”。需要容错时必须在业务层显式定义异常如何映射，而不是依赖 short-circuit 吞掉错误。

## findFirst 与 findAny

顺序有序流中，两者通常都能在首个元素处结束；并行语义不同：

- `findFirst` 在有遇见顺序时必须返回第一个元素，需要确认更左侧分区没有更早结果；
- `findAny` 可以返回任一元素，为并行实现提供更大的自由；
- 对无序流，findFirst 不再拥有有意义的“第一个”顺序保证；
- 不要断言并行 findAny 在某台机器上经常返回的具体值。

JDK 8 的 FindSink 收到第一个值后让 `cancellationRequested` 返回 true。并行 FindTask 还会区分当前叶是否位于 encounter order 的左侧，并取消后续节点；这比“所有线程竞争一个 Optional”更复杂。

## limit 与 skip 的不同方向

顺序 `limit(n)` 的 Sink 维护剩余配额，配额归零后请求取消。`skip(n)` 必须先消耗并丢弃 n 个元素，本身不能因为 skip 完成就停止；如果没有下游 limit 或短路 terminal，它仍需遍历剩余元素。

并行有序 limit 需要确定哪些分区属于全局前 n 个元素，可能先构建 Node、统计左侧完成数量并截断。无序流可以选择任意 n 个，更容易并行，但业务必须真的不依赖遇见顺序才能调用 `unordered()`。

边界：

- `limit(0)` 可以不拉取任何元素；
- 负数 limit/skip 抛 `IllegalArgumentException`；
- `limit` 不保证上游昂贵 stateful 操作也只处理 n 个；
- 并行在途任务可能在取消传播前多处理一些元素。

## stateful 操作如何改变短路成本

流水线顺序决定能否提前停止：

```text
filter → limit(10)
  只要找到 10 个通过 filter 的元素即可停止

sorted → limit(10)
  一般必须完成有限输入的排序阶段，才能知道全局前 10 个

distinct → findFirst
  有序语义下仍要确认首个唯一元素；并行去重可能需要共享状态或分段归并
```

无限流尤其需要注意：

- `Stream.iterate(...).filter(...).findFirst()` 在确实存在匹配值时可以结束；
- `Stream.iterate(...).limit(n).collect(...)` 可以结束；
- 无限流先 `sorted()` 再 `findFirst()` 无法完成排序；
- 谓词永远不满足时，findFirst/anyMatch 也不会凭“短路能力”自动结束。

## 并行短路的共享结果

`AbstractShortCircuitTask` 让整棵任务树共享一个 `AtomicReference<R> sharedResult`：

```text
每个任务循环：
  sharedResult 已有值 → 停止继续拆分
  当前任务或祖先 canceled → 使用空结果
  分区足够小/不能再 split → doLeaf
  否则继续建立左右子任务

某叶找到全局可用结果：
  sharedResult.compareAndSet(null, result)
```

CAS 只让一个结果成为全局结果。其他任务会在下一次检查时看到它，但已经运行的用户函数不能被瞬间撤销。

有序 findFirst/limit 还会沿父链取消当前节点右侧的兄弟分支，即 `cancelLaterNodes`。取消标记是协作协议，不等同于对每个工作线程调用 `Thread.interrupt`。

## 副作用为什么不能依赖访问次数

下面的计数只适合调试一次顺序执行：

```java
AtomicInteger visited = new AtomicInteger();
OptionalInt first = IntStream.rangeClosed(1, 100)
        .peek(value -> visited.incrementAndGet())
        .filter(value -> value % 7 == 0)
        .findFirst();
```

顺序模式会访问 1..7。换成 parallel 后，多个分区可能先执行谓词，visited 通常大于 7，且数值不稳定。即使用 AtomicInteger 保证计数不丢失，也只解决数据竞争，不提供固定访问次数。

更重要的跨版本边界是 `count()`：JDK 9 以后，如果 source 和 stage 保持精确 SIZED，运行时可直接计算 count 而不遍历元素，因而合法地跳过 `peek`。不要用 peek 发送消息、更新业务状态或验证每个元素“必定处理”。

## forEach 与 forEachOrdered

两者通常都遍历全部元素，不是 short-circuit terminal：

| 操作 | 并行遇见顺序 | 代价边界 |
| --- | --- | --- |
| `forEach` | 不保证 | 分区可更独立地消费 |
| `forEachOrdered` | 对有序流按 encounter order 执行 action | 用完成依赖连接左右任务，可能降低并行度 |

`collect(toList())` 在有序流和非 UNORDERED collector 下会按规则归并结果，但不表示每个 map/filter lambda 按单线程顺序发生。结果顺序和中间副作用执行顺序必须分开理解。

## 自动测试应断言什么

稳定断言：

- 顺序有序流的 findFirst 返回首个匹配值；
- 顺序 `filter/map/limit` 只拉取获得限定结果所需的前缀；
- allMatch/noneMatch 在空流上的单位值；
- parallel findFirst 对有序源仍返回遇见顺序第一个元素；
- 终止操作返回值与异常传播。

不稳定断言：

- parallel short-circuit 精确调用了多少次谓词；
- findAny 返回某个固定值；
- 某个分区或线程先找到结果；
- 取消后所有其他 lambda 立即停止；
- peek 在所有 JDK 和所有 terminal 中执行固定次数。

## 推荐断点

1. `AbstractPipeline.copyInto/copyIntoWithCancel`：确认 flags 选择哪条遍历路径。
2. `ReferencePipeline.forEachWithCancel`：观察取消检查和 tryAdvance 的先后顺序。
3. `MatchOps.BooleanTerminalSink.cancellationRequested`：观察 stop 和 value。
4. `FindOps.FindSink.accept/cancellationRequested`：观察首值如何建立结果。
5. `SliceOps` 的 limit Sink：观察 n/m 计数和取消。
6. `AbstractShortCircuitTask.compute/shortCircuit`：观察 sharedResult 与叶任务停止。
7. `cancelLaterNodes`：只在需要 encounter order 的并行案例中观察右侧取消。

下一步阅读 [Spliterator 与并行任务树](./parallel-spliterator.md)，理解短路任务所依赖的源分区是怎样建立的。
