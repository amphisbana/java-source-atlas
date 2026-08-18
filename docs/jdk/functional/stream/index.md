# Stream 与 Spliterator：从惰性流水线到并行拆分

Java Stream 不是保存元素的新集合，而是围绕一个数据源组装处理阶段，并在终止操作到来时驱动遍历。`Spliterator` 则描述“剩余元素怎样逐个推进、怎样拆成互不重叠的分区、规模和顺序具有什么特征”。两者合在一起，构成顺序融合遍历和并行任务分解的基础。

本专题以 OpenJDK 8u 为主基线。JDK 17/21 延续 `AbstractPipeline + Sink + Spliterator + ForkJoinTask` 的核心架构，但公开 API、大小优化和部分内部任务实现已有变化；不要把 JDK 8 的私有类签名当成跨版本接口。

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `AbstractPipeline` | [`java/util/stream/AbstractPipeline.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/AbstractPipeline.java) | stage 链、flags、`evaluate`、`wrapSink`、`copyInto` |
| `ReferencePipeline` | [`java/util/stream/ReferencePipeline.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/ReferencePipeline.java) | `filter`、`map`、`peek`、`forEachWithCancel` |
| `Sink` | [`java/util/stream/Sink.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/Sink.java) | `begin`、`accept`、`cancellationRequested`、`end` |
| `StreamOpFlag` | [`java/util/stream/StreamOpFlag.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/StreamOpFlag.java) | 特征的设置、清除与组合 |
| `Spliterator` | [`java/util/Spliterator.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/Spliterator.java) | `tryAdvance`、`trySplit`、`estimateSize`、characteristics |
| `ArrayList` | [`java/util/ArrayList.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/ArrayList.java) | `ArrayListSpliterator` 的延迟绑定和 fail-fast |
| `AbstractTask` | [`java/util/stream/AbstractTask.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/AbstractTask.java) | 目标叶大小、二叉任务树、叶结果 |
| `AbstractShortCircuitTask` | [`java/util/stream/AbstractShortCircuitTask.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/AbstractShortCircuitTask.java) | 共享结果与后续节点取消 |

OpenJDK 源码采用 GPLv2 with Classpath Exception。本专题只保留定位所需的字段关系、调用链和伪代码，统一许可说明见站点源码许可页。

## 先建立完整执行模型

下面的代码实际经历两个阶段：

```java
List<Integer> result = source.stream()
        .filter(value -> value % 2 == 0)
        .map(value -> value * 10)
        .limit(1)
        .collect(Collectors.toList());
```

### 组装阶段

```text
source stage → filter stage → map stage → limit stage
```

每个中间操作创建一个新的 `AbstractPipeline` 子类 stage，记录前后关系、操作 flags 和怎样包装下游 Sink。此时通常没有读取 source 元素，也没有为每一步创建中间 List。

### 求值阶段

```text
collect 创建 terminal sink
  → limit 从终止端包装 terminal
  → map 包装 limit
  → filter 包装 map
  → source Spliterator 把元素推给最外层 filter Sink
```

Sink 在构造时从终止端反向包裹，数据在执行时从源端正向流动。对于 stateless 操作，这种结构让一个元素可以在同一个调用链中完成过滤、映射和下游接收，不必先生成完整的过滤结果再执行 map。

状态型操作是重要例外：`sorted`、`distinct`、并行 `limit/skip` 等可能需要缓冲、建立集合或把并行流水线分段求值，不能把所有 Stream 都概括为“绝不分配中间存储”。

## 中间操作为什么是惰性的

`filter/map/peek` 返回新 Stream stage，只保存函数和连接关系。真正读取源通常从以下动作开始：

- `collect/reduce/count/min/max` 等归约终止操作；
- `forEach/forEachOrdered` 等消费终止操作；
- `findFirst/findAny/anyMatch` 等短路终止操作；
- 显式取得并消费 `iterator()` 或 `spliterator()`。

惰性允许框架：

1. 把多个 stateless 操作融合进一条 Sink 链；
2. 根据终止操作注入短路或无序 flags；
3. 只在需要时取得 source Spliterator；
4. 在并行模式下先拆分源，再对每个叶分区执行流水线。

它也意味着 `peek` 不是“调用到这一行就执行”的日志语句。没有终止操作时，peek 不执行；存在短路或可省略遍历的优化时，也可能只执行一部分甚至完全不执行。

## Stream 为什么只能消费一次

`AbstractPipeline` 使用 `linkedOrConsumed` 同时防止重复链接和重复求值：

- 在某个 stage 后追加新 stage 时，前一个 stage 被标记为已链接；
- 终止求值开始时，末 stage 被标记为已消费；
- 取得 source spliterator 后，source stage 中保存的 spliterator 或 supplier 被清空。

因此以下两种用法都不合法：

```java
Stream<Integer> base = source.stream();
Stream<Integer> evens = base.filter(value -> value % 2 == 0);
base.map(value -> value * 10); // base 已经链接到 evens

evens.count();
evens.findFirst();             // evens 已经被终止操作消费
```

典型结果是 `IllegalStateException`，但异常文字不是兼容性接口。需要重复计算时应重新从可重复的数据源创建 Stream；若源本身是 I/O 通道等一次性资源，还要遵守它自己的生命周期。

## 三类操作不要混淆

| 分类 | 代表操作 | 是否可能需要全局状态 | 是否天然短路 |
| --- | --- | --- | --- |
| stateless 中间操作 | `filter`、`map`、`peek` | 否，每个元素可独立处理 | 否 |
| stateful 中间操作 | `distinct`、`sorted`、`limit/skip` | 可能需要缓存、计数或分段 | `limit` 可以请求短路，`sorted` 通常不能 |
| terminal 操作 | `collect`、`reduce`、`findFirst`、`anyMatch` | 决定最终遍历和归并方式 | 由具体 terminal 决定 |

`limit` 同时是 stateful 和 short-circuiting；分类不是互斥的性能标签。`sorted().limit(1)` 仍需先确定全局最小顺序，有限无序输入通常要完成排序阶段，不能因为下游只要一个元素就停止所有上游工作。

## 非干扰、无状态与副作用边界

Stream 函数应当不修改数据源，并尽量无状态：

- 遍历 ArrayList 时结构性修改可能触发 `ConcurrentModificationException`，但 fail-fast 只是尽力检测；
- 并行 lambda 修改普通 `ArrayList`、普通计数器等共享可变状态会产生数据竞争；
- 即便改用线程安全容器，副作用出现的顺序和次数也可能受短路、并行与优化影响；
- `forEach` 在并行有序流中也不保证遇见顺序，要求顺序时使用 `forEachOrdered`，并评估同步成本。

优先把业务逻辑写成输入到输出的纯转换，通过 `collect/reduce` 明确汇总。调试用 `peek` 可以观察某次执行，不应承担扣款、发送通知或唯一审计记录。

## 并行不等于必然更快

`parallel()` 或 `parallelStream()` 只把 source stage 的求值模式切换为并行。真正收益取决于：

- 源能否低成本、相对均衡地 `trySplit`；
- 元素数量和单元素计算成本是否足以覆盖拆分、调度和归并；
- 操作是否要求遇见顺序或全局状态；
- 公共 ForkJoinPool 是否同时承载其他任务；
- lambda 是否阻塞 I/O、争用锁或访问共享热点。

小列表、轻量 map、强顺序 limit、阻塞调用和不易拆分的链式源都可能让并行更慢。应使用真实数据规模和 JMH 基准测量，不能用线程数推导固定加速比。

## 一条完整学习路径

1. [AbstractPipeline 与 Sink 链](./pipeline-sink.md)：理解 stage 组装、flags、反向包装与正向推送。
2. [终止遍历与短路](./short-circuit.md)：理解 `copyIntoWithCancel`、match/find/limit 和副作用边界。
3. [Spliterator 与并行任务树](./parallel-spliterator.md)：理解特征、延迟绑定、拆分、叶计算和有序归并。
4. [断点实验手册](./debug-lab.md)：在 JDK 8/17 上运行可重复行为案例，再进入私有实现观察。

ArrayList 的集合结构与普通迭代器可先阅读 [ArrayList：迭代器与版本差异](/jdk/collections/arraylist/iterator-version)。ForkJoin 工作队列、窃取和 join 的内部机制由独立 ForkJoinPool 专题继续展开。

## JDK 8、17、21 的版本边界

| 观察点 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| 核心架构 | AbstractPipeline stage、Sink 链、Spliterator、ForkJoin tasks | 总体保持 |
| 常用新增 API | 初始 Stream API | Java 9 增加 `takeWhile/dropWhile/ofNullable` 等；Java 16 增加 `mapMulti/toList` |
| `count()` | 通常通过遍历归约 | 对保持 `SIZED` 的流水线可直接读取已知大小，可能跳过 `peek` |
| ArrayList Spliterator | 延迟绑定 fence，ORDERED/SIZED/SUBSIZED，modCount 检查 | 核心语义稳定，内部循环和辅助方法可调整 |
| 并行任务 | CountedCompleter/AbstractTask 家族 | 类族仍在演进，私有任务结构不是 API |

尤其不要写依赖“`peek` 在 `count` 前一定执行”的跨版本测试。Stream 规范允许实现省略不影响结果的 stage 遍历，公开返回值和副作用约束比 JDK 8 的某条内部路径更稳定。
