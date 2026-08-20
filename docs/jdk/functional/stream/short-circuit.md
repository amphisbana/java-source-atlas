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

## 取消遍历为什么从 void 变成 boolean

JDK 8 的 `ReferencePipeline.forEachWithCancel` 和 `AbstractPipeline.copyIntoWithCancel` 都返回 `void`。调用方只知道当前遍历已经结束，无法区分两种原因：

```text
原因 A：Spliterator 自然耗尽
原因 B：某个 Sink 的 cancellationRequested() 返回 true
```

JDK 9 引入 `takeWhile` 后，这个区别会影响并行有序流。某个叶分区若自然耗尽，遇见顺序更晚的分区仍可能属于结果；若它因为谓词首次返回 false 而取消，那么后续分区都必须排除。JDK 17/21 的主线因此返回取消结果：

```text
ReferencePipeline.forEachWithCancel(...) → boolean cancelled
AbstractPipeline.copyIntoWithCancel(...) → boolean cancelled
WhileOps.TakeWhileTask.doLeaf()
  → cancelled == true 时 cancelLaterNodes()
```

这里的 boolean 是内部遍历协议，不是线程中断，也不会撤销已经进入 mapper/predicate 的调用。顺序 `takeWhile(value < 3)` 遍历 `[1,2,3,4]` 时返回 `[1,2]`，谓词仍会检查 3，因为正是这个失败元素建立了停止边界。

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

## skip / limit 为什么在新版 JDK 仍能保持精确尺寸

切片结果相同，内部 flags 却有一个直接影响 `count` 和预分配的变化：

| 固定快照 | `SliceOps.flags(limit)` | 四元素源经过 `skip(1).limit(2)` |
| --- | --- | --- |
| JDK 8u412 | `NOT_SIZED`，有限 limit 再加 `IS_SHORT_CIRCUIT` | `getExactSizeIfKnown() == -1` |
| JDK 17 GA | `IS_SIZE_ADJUSTING`，有限 limit 再加 `IS_SHORT_CIRCUIT` | `getExactSizeIfKnown() == 2` |
| JDK 21 GA | 与 JDK 17 相同 | `getExactSizeIfKnown() == 2` |

JDK 17/21 的顺序路径先从 SIZED source 取得精确大小，再沿 stage 计算：

```text
source size = 4
skip(1)      → max(0, 4 - 1) = 3
limit(2)     → min(3, 2)     = 2
```

这项优化有两个边界：上游经过 `filter/flatMap/mapMulti` 后已经无法证明数量，仍然返回未知；并行 stateful stage 会先求值为新的 Spliterator，不能简单照搬顺序逐 stage 公式。`SIZE_ADJUSTING` 表示“能从上游尺寸算出新尺寸”，不表示任何来源经过 limit 都自动成为 SIZED。

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

## count 为什么在新版 JDK 可能完全不遍历

JDK 8 的引用流把 count 写成 `mapToLong(e -> 1L).sum()`，四个元素必须逐一进入流水线。JDK 17/21 的 `ReduceOps.makeRefCounting` 会先调用 `helper.exactOutputSizeIfKnown(spliterator)`：

```text
size != -1 → 直接返回 size
size == -1 → 创建 CountingSink 并逐元素归约
```

因此下面的代码在三版都返回 4，但观察结果不同：

```java
AtomicInteger calls = new AtomicInteger();
long count = Stream.of(1, 2, 3, 4)
        .peek(value -> calls.incrementAndGet())
        .count();
```

| JDK 8u412 | JDK 17 GA | JDK 21 GA |
| --- | --- | --- |
| `count=4, calls=4` | `count=4, calls=0` | `count=4, calls=0` |

`peek` 不会改变元素数量，所以精确尺寸证明仍成立。若在前面加 `filter`，SIZED 被清除，三版都需要实际判断元素。JDK 17/21 的 `skip/limit` 又可通过 SIZE_ADJUSTING 保留可计算尺寸，所以 `peek().skip(...).limit(...).count()` 也可能不执行 peek。

这不是语义破坏。Stream 规范允许实现省略不影响终止结果的 stage；`peek` 本来就是调试观察入口，`map` 等函数也必须遵守非干扰、无状态要求。

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

同样不要用 `peek` 发送消息、更新业务状态或验证每个元素“必定处理”。上面的 count 快路径会让调用次数变成 0，短路和并行执行又会让其他终止操作的访问次数只成为一次运行的观察值。

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
2. `ReferencePipeline.forEachWithCancel`：比较 JDK 8 的 void 与新版 boolean，并观察取消检查和 tryAdvance 的先后顺序。
3. `WhileOps.TakeWhileTask.doLeaf`：观察返回 true 后怎样调用 `cancelLaterNodes`。
4. `ReduceOps.makeRefCounting/evaluateSequential`：确认 count 是直接返回尺寸还是创建 CountingSink。
5. `SliceOps.flags/exactOutputSize`：比较 NOT_SIZED 与 SIZE_ADJUSTING 的尺寸传播。
6. `MatchOps.BooleanTerminalSink.cancellationRequested`：观察 stop 和 value。
7. `FindOps.FindSink.accept/cancellationRequested`：观察首值如何建立结果。
8. `AbstractShortCircuitTask.compute/shortCircuit`：观察 sharedResult 与叶任务停止。
9. `cancelLaterNodes`：只在需要 encounter order 的并行案例中观察右侧取消。

下一步阅读 [Spliterator 与并行任务树](./parallel-spliterator.md)，理解短路任务所依赖的源分区是怎样建立的。
