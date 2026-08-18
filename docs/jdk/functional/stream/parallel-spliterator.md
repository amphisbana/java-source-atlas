# Spliterator 与并行任务树：拆分、叶计算和有序归并

`Spliterator` 不是“支持并行的 Iterator”这么简单。它同时向遍历框架提供三类信息：怎样推进元素、怎样把剩余范围拆成互不重叠的部分、当前范围有哪些可依赖的特征。并行 Stream 再把这些分区包装为 Fork/Join 任务，在叶节点执行同一条流水线，最后按终止操作的语义归并结果。

本页以 OpenJDK 8u 的 `ArrayListSpliterator`、`AbstractTask` 和 `ReduceTask` 为主线。`Spliterator` 的公开契约是跨版本依据；任务类、阈值公式和具体执行池选择属于实现细节，不能当作业务接口。

## 四个核心入口各自回答什么

| 入口 | 回答的问题 | 调用方应依赖的边界 |
| --- | --- | --- |
| `tryAdvance(action)` | 能否把下一个元素交给 action | 返回 false 表示已无剩余元素；action 异常向上传播 |
| `forEachRemaining(action)` | 怎样消费当前分区的全部剩余元素 | 等价语义通常比逐次 tryAdvance 更容易做循环优化 |
| `trySplit()` | 能否从尚未遍历的范围分出一部分 | 返回 null 表示此刻不再适合拆；非 null 分区不得与原对象的剩余范围重叠 |
| `estimateSize()` | 当前还可能遍历多少元素 | SIZED 时需满足精确大小约束，否则可以只是递减估计或 `Long.MAX_VALUE` |
| `characteristics()` | 哪些结构事实可供上层优化 | 位声明必须真实；错误的 SIZED、SORTED、DISTINCT 会使上层作出错误推理 |

`Iterator` 主要描述串行推进；`Spliterator` 把“可拆分性”和“数据特征”也纳入协议。名字来自 *splittable iterator*，但它不承诺一定能均匀拆分，也不承诺调用 `trySplit` 就创建线程。

## characteristics 不是性能装饰

常见特征及其约束如下：

| 特征 | 含义 | 容易写错的地方 |
| --- | --- | --- |
| `ORDERED` | 元素存在定义好的遇见顺序 | 有顺序不等于并行 lambda 按该顺序开始或结束 |
| `DISTINCT` | 任意两个元素按 equals 语义互异 | 不能因当前样本无重复就声明 |
| `SORTED` | 遇见顺序符合某个 Comparator 或自然顺序 | 必须同时包含 ORDERED，并正确实现 `getComparator()` |
| `SIZED` | 未结构性修改时，当前完整遍历数量是精确有限值 | 过滤后的输出一般不再 SIZED，即使源是 ArrayList |
| `SUBSIZED` | 本对象及所有直接、间接拆分结果都 SIZED | 知道根大小但不知道子树大小时不能声明 |
| `NONNULL` | 保证元素不为 null | 容器“通常不放 null”不是保证 |
| `IMMUTABLE` | 遍历期间源不能结构性修改 | 只读视图不一定意味着底层不可变 |
| `CONCURRENT` | 可在指定并发修改策略下安全遍历 | 与 fail-fast 是不同模型，需说明并发源的可见性策略 |

特征会参与 `StreamOpFlag` 推理，但二者不是同一个位集合的机械透传。例如 ArrayList source 是 SIZED，经过 `filter` 后无法预知有多少元素通过，因此流水线会清除输出 SIZED；`map` 保持数量，却可能清除 DISTINCT 和 SORTED。

自定义 Spliterator 的原则是宁可少报优化特征，也不要报错。少报通常损失预分配或优化机会；多报可能直接破坏结果语义。

## ArrayListSpliterator 如何延迟绑定

OpenJDK 8 的 `ArrayListSpliterator` 主要保存：

```text
list             源 ArrayList
index            下一个待推进索引
fence            上界；-1 表示尚未绑定
expectedModCount 绑定时记录的结构版本
```

`ArrayList.spliterator()` 创建对象时传入 `fence=-1`。第一次调用 `getFence()` 的操作才读取当前 `size` 和 `modCount`：

```text
若 fence < 0
  expectedModCount = list.modCount
  fence = list.size
返回 fence
```

会触发绑定的典型入口包括 `estimateSize()`、`trySplit()`、`tryAdvance()` 和遍历。于是下面的追加发生在首次绑定前，当前实现会把 3 纳入范围：

```java
ArrayList<Integer> source = new ArrayList<>(Arrays.asList(1, 2));
Spliterator<Integer> spliterator = source.spliterator();
source.add(3);
spliterator.forEachRemaining(System.out::println); // 1, 2, 3
```

延迟绑定缩小了“创建 Spliterator 到固定遍历范围”之间的干扰窗口，但它不是并发安全承诺。ArrayList 仍不是并发容器；绑定前修改能否被纳入，要看具体来源及其 Spliterator 文档，不能推广成所有集合的统一行为。

## ArrayList trySplit 返回前缀

绑定后的索引范围假设是 `[lo, hi)`，`trySplit()` 的核心可以概括为：

```text
mid = (lo + hi) >>> 1
若 lo >= mid，返回 null
否则：
  当前对象 index = mid，继续持有 [mid, hi)
  返回新 Spliterator(lo, mid, expectedModCount)
```

对 `[0,8)` 连续拆分可以得到：

```text
                    [0,8)
                  /       \
              [0,4)       [4,8)
              /   \       /   \
          [0,2) [2,4) [4,6) [6,8)
```

ArrayList 返回的是左侧前缀，原 Spliterator 留下右侧后缀。这个方向是当前实现事实，不是所有 Spliterator 的统一要求；框架只需要拆分结果和原对象剩余部分共同覆盖原范围、没有重复，并各自继续遵守自己的顺序与大小契约。

ArrayList 分区是连续索引区间，所以拆分开销低、大小精确且通常较均衡，能够声明 `ORDERED | SIZED | SUBSIZED`。链表、I/O、生成器或未知大小来源可能难以做到这一点。

## fail-fast 是尽力检测，不是事务回滚

绑定后，ArrayListSpliterator 会用 `expectedModCount` 检测结构性修改。但检查时机不保证发生在每个副作用前：

- `tryAdvance` 交付一个元素后检查版本；
- OpenJDK 8 的 `forEachRemaining` 可先循环处理绑定范围，再在末尾比较 modCount；
- 多线程没有额外同步时，本身还存在 Java 内存模型的数据竞争问题。

因此 `ConcurrentModificationException` 只用于尽早暴露错误用法：

```text
可能已执行 action 若干次 → 发现 modCount 不同 → 抛异常
```

不能把它理解为“任何修改都会立刻被发现”，也不能在捕获异常后认为之前的日志、计数或外部调用已经回滚。需要并发遍历时，应选择具有明确弱一致或快照语义的数据结构，而不是依赖 ArrayList 的异常检测。

## 拆分结果如何交给线程

Spliterator 的一般并行使用模型是：一个线程拥有某个实例并调用 `trySplit`，然后把拆出的分区交给另一个任务；每个分区在开始遍历后由单个线程独占。规范将这称为串行线程约束下的拆分与移交：

1. 在一个线程内创建或取得 source Spliterator。
2. 在开始 `tryAdvance/forEachRemaining` 前拆分。
3. 通过 Fork/Join 提交等具有 happens-before 保证的方式把子 Spliterator 移交给任务。
4. 移交后，原线程不再同时操作那个子实例。

`Spliterator` 本身通常不是线程安全对象。把同一个实例放给多个线程并发调用 `tryAdvance`，与“把互不重叠的 split 结果交给不同线程”完全不同。

## AbstractTask 怎样长出任务树

多数并行归约任务基于 `AbstractTask`。根任务持有 `PipelineHelper` 和 source Spliterator；子任务继承 helper、目标叶大小，并分别持有一个分区。

OpenJDK 8u432 的 `compute()` 主干可以概括为：

```text
rs = 当前 Spliterator
sizeEstimate = rs.estimateSize()
sizeThreshold = getTargetSize(sizeEstimate)

while sizeEstimate > sizeThreshold && (ls = rs.trySplit()) != null
  leftChild  = makeChild(ls)
  rightChild = makeChild(rs)
  pendingCount = 1
  交替选择一个 child fork
  在当前线程循环处理另一个 child
  更新该分区 estimateSize

当前 task.doLeaf()
保存叶结果
tryComplete()
```

这里有三个容易忽略的设计点：

1. 循环沿一个子节点继续，不为每一层递归调用 compute，降低调用栈和对象外的控制开销。
2. fork 左右方向交替，避免某些系统性偏斜的 Spliterator 总让一个方向留在本线程。
3. `trySplit()==null` 会立即形成叶节点，即使估计大小仍大于阈值；并行度最终受 source 可拆分性限制。

### 目标叶大小只是启发式

`suggestTargetSize(sizeEstimate)` 通常把初始估计除以一个约为并行度数倍的 leaf target，并把下限限制为 1。目的不是严格生成固定数量的叶子，而是适度过度分区，为工作窃取留出负载均衡空间。

不同 8u 更新的实现并不完全一样：较早代码可直接基于缓存的 common-pool parallelism；本机 Corretto 8u432 在当前线程是 `ForkJoinWorkerThread` 时，会从该 worker 所属 pool 取得 parallelism，否则回退到 common-pool 基线。JDK 17/21 仍可继续调整这些内部公式。

因此不要测试：

- “1000 个元素一定拆成 8 个叶子”；
- “每个叶子一定有 125 个元素”；
- “并行度为 2 就只创建两个任务”。

稳定测试应验证结果、无丢失/重复、顺序契约和资源收口。

## 叶节点仍执行完整流水线

拆分发生在 source Spliterator 层。每个叶任务进入 `doLeaf()` 后，仍通过同一个 `PipelineHelper` 包装 Sink，并把自己的分区元素推过 `filter/map/...`：

```text
叶分区 Spliterator
  → helper.wrapAndCopyInto(leaf sink, split)
  → filter Sink
  → map Sink
  → leaf reducing sink / node builder
  → 叶局部结果
```

所以并行 Stream 不是“先由一个线程完成 filter，再由另一个线程完成 map”。通常是多个叶任务分别对各自元素执行整条 stateless 流水线。状态型操作可能先形成求值边界和新 Spliterator，不能套用单段模型。

## 完成顺序与结果顺序是两件事

假设四个叶分区分别计算：

```text
L0=[0,1]  L1=[2,3]  L2=[4,5]  L3=[6,7]
```

运行时完全可能先完成 L3，再完成 L0。对有序 `collect(toList())`，归并树仍按左结果在前、右结果在后组合：

```text
combine(L0, L1) → [0,1,2,3]
combine(L2, L3) → [4,5,6,7]
combine(left, right) → [0,1,2,3,4,5,6,7]
```

OpenJDK 8 的 `ReduceTask.onCompletion` 取 left child 的 sink，再 `combine(right child sink)`。这解释了当前实现如何保存遇见顺序，但业务代码应依赖 Collector、reduce 和 Stream 规范，而不是依赖私有类名。

归约函数必须满足并行规约：identity 真正是单位元，accumulator/combiner 兼容，combiner 具结合性，并且函数非干扰。减法这类非结合运算会因树形分组得到不同结果；给并行 collect 共享同一个可变容器则会产生数据竞争。

## forEach 与 forEachOrdered 的边界

| 终止操作 | 并行有序流中的保证 | 代价形态 |
| --- | --- | --- |
| `forEach` | 不保证遇见顺序，action 可并发发生 | 更容易直接消费叶分区 |
| `forEachOrdered` | action 按遇见顺序执行，并建立前一元素 action 对后一元素 action 的 happens-before | 需要依赖协调，部分叶结果可能先缓冲 |

`forEachOrdered` 保证的是终止 action 的顺序。上游 `map/filter/peek` 的 lambda 仍可能在不同叶任务并发执行，不能据此实现有序外部副作用。OpenJDK 8 的 `ForEachOrderedTask` 使用 completion map 和 pending count 表达左分区先于右分区，未轮到输出的叶子可先写入 Node，等前驱完成后再倾倒到 action。

要求稳定顺序时，优先让流水线计算纯结果，再在边界串行执行真正的外部动作。并行 `forEachOrdered` 并不会把副作用天然变成事务。

## 并行短路为何仍可能多做工作

`findAny/anyMatch` 等并行短路任务共享一个原子结果。某个叶任务发布答案后，其他任务在下一次检查时退出；已经在执行 action 的任务不会被撤销。

有序 `findFirst` 还要确认没有更靠左的分区拥有结果。`AbstractShortCircuitTask.cancelLaterNodes()` 沿父链取消当前节点之后的右侧兄弟分区，但取消是协作标记，不等同于中断所有线程。

因此并行短路可以减少工作，却不能保证谓词只执行到“理论最少次数”。不要把调用次数、副作用顺序或特定被取消叶子写成断言。

## 并行流在哪个池执行

JDK API 只公开 `parallel()` 切换求值模式，并没有让调用者为某条 Stream 显式传入 Executor 的标准入口。普通调用通常由 common ForkJoinPool 承载。把并行流水线放进自建 ForkJoinPool 的 worker 内，当前主流 JDK 实现通常让由该 worker fork 的内部任务留在所属 pool，本专题实验用这种方式隔离教学任务。

但这不是 Stream API 对自定义执行池的可移植契约：

- 不应通过线程名称断言内部池身份；
- 不应把当前 JDK 行为封装成跨厂商、跨版本承诺；
- 框架或业务服务若需要明确隔离、限流、超时和拒绝策略，优先显式拆分任务并使用自己的 Executor；
- common pool 是进程级共享资源，阻塞 I/O 会影响其他并行流、CompletableFuture 和 Fork/Join 用户。

实验中的自建 pool 只提供有限等待和 finally shutdown 的可控边界，并不把“自定义池支持”描述成 Stream 的正式配置能力。

## 性能判断必须同时看源、操作和终止语义

适合尝试并行的常见条件：

- 数据量足够大，单元素计算明显重于拆分与调度；
- source 能低成本、较均匀地拆分，如数组和 ArrayList；
- lambda 主要是 CPU 计算且无共享写热点；
- 归约函数结合且合并成本可控；
- 不要求强顺序或全局状态。

常见反例：

- 小集合上的简单 map/filter；
- `LinkedList`、迭代器包装或未知大小生成源；
- 每个元素发 HTTP、查数据库、等待锁；
- `sorted/distinct` 等全局状态操作；
- 强顺序 `limit/findFirst/forEachOrdered`；
- 每个叶任务竞争同一个锁、Atomic 热点或共享容器。

并行的正确结论只能来自目标机器、真实数据分布和 JMH 基准。不要用 `availableProcessors()` 或一次控制台计时推导固定加速比；JIT 预热、GC、池竞争和断点都会显著改变结果。

## 自定义 Spliterator 的检查清单

1. `tryAdvance` 每次成功只交付一个元素，耗尽后稳定返回 false。
2. 拆分结果与原对象剩余范围不重叠、不丢失，且拆分后估计值合理下降。
3. 若声明 ORDERED，拆分与遍历能重建定义好的遇见顺序。
4. 若声明 SIZED/SUBSIZED，所有对应估计均满足精确数量约束。
5. 若声明 SORTED，实现正确的 Comparator 语义并同时声明 ORDERED。
6. 在遍历开始前完成拆分，并通过安全发布把每个分区移交给单个线程。
7. 对不可拆源及时返回 null，不用制造大量空分区。
8. 用性质测试验证随机大小、奇偶边界、空源、单元素和多轮拆分，而不是只测八元素整齐二分。

## JDK 8、17、21 的实现边界

| 观察点 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| ArrayListSpliterator | fence 延迟绑定，索引中点拆分，末端 modCount 检查 | 公开特征和整体语义稳定，内部辅助方法及循环可调整 |
| AbstractTask | CountedCompleter 任务树，目标叶大小启发式，交替 fork | 架构仍在，但私有字段和启发式不是兼容接口 |
| 当前 worker pool 影响 leaf target | 早期 8u 与较新更新存在实现差异；8u432 会识别当前 ForkJoin worker | 继续以所运行版本源码为准 |
| `count()` 对 SIZED 流 | JDK 8 常见路径会实际遍历 | Java 9+ 可直接使用已知大小并跳过不影响结果的 stage，包括 `peek` |
| 新 Stream API | 初始 API | 9 增加 takeWhile/dropWhile/ofNullable；16 增加 mapMulti/toList 等 |
| 私有任务类 | ReduceTask、ForEachTask、FindTask 等 | 类族可能重构，断点方法需按当前 src.zip 重新定位 |

跨版本测试只断言公开返回值和规范保证。需要研究内部调度时，应把 IDE 附加到实际运行 JDK 的 `src.zip`，不要用 JDK 8 行号去调试 JDK 17 类。

## 推荐断点调用链

| 层次 | OpenJDK 8u 位置 | 重点变量 |
| --- | --- | --- |
| source 绑定 | `ArrayList$ArrayListSpliterator.getFence` | `fence`、`expectedModCount`、`list.size/modCount` |
| source 拆分 | `ArrayList$ArrayListSpliterator.trySplit` | `lo`、`hi`、`mid`、两侧 index/fence |
| 任务阈值 | `AbstractTask.suggestTargetSize/getTargetSize` | `sizeEstimate`、leaf target、`targetSize` |
| 构建任务树 | `AbstractTask.compute` | `rs`、`ls`、`forkRight`、left/right child、pending count |
| 叶计算 | 具体任务的 `doLeaf` | leaf Spliterator、wrapped Sink、local result |
| 归并 | `ReduceOps$ReduceTask.onCompletion` | left/right local result、combine 前后状态 |
| 有序输出 | `ForEachOps$ForEachOrderedTask` | completionMap、leftPredecessor、pending count、node |
| 并行短路 | `AbstractShortCircuitTask.compute/shortCircuit/cancelLaterNodes` | sharedResult、canceled、右侧兄弟 |

下一步按 [断点实验手册](./debug-lab.md) 先验证公开行为，再进入这些私有方法。Fork/Join 的 WorkQueue、steal 和 join 帮助机制见 [ForkJoinPool 专题](/jdk/concurrent/forkjoinpool/)。
