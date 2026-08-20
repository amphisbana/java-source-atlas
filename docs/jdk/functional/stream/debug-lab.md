# Stream 与 Spliterator 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/stream/StreamSpliteratorDebugLab.java
```

测试入口：

```text
labs/jdk-labs/src/test/java/
  io/github/javasourceatlas/jdk/stream/StreamSpliteratorBehaviorTest.java
```

实验源码保持 `--release 8` 编译；JDK 9 的 `takeWhile` 和 JDK 16 的 `mapMulti` 只在运行时通过反射调用公开 API，因此同一产物仍可在 Java 8 启动。自动测试验证公开行为与版本可观察特征；stage 私有字段、任务数量、worker 名称和具体拆分树只在调试器中观察，不进入断言。

## 直接运行

使用当前 JAVA_HOME：

```bash
mvn -pl labs/jdk-labs -DskipTests compile exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.stream.StreamSpliteratorDebugLab
```

只运行本专题测试：

```bash
mvn -pl labs/jdk-labs \
  -Dtest=io.github.javasourceatlas.jdk.stream.StreamSpliteratorBehaviorTest \
  test
```

在本机显式切换 JDK 8、17、21 时，可分别执行：

```bash
JAVA_HOME=/path/to/jdk8 \
PATH=/path/to/jdk8/bin:$PATH \
mvn -pl labs/jdk-labs -Dtest=StreamSpliteratorBehaviorTest test
```

```bash
JAVA_HOME=/path/to/jdk17 \
PATH=/path/to/jdk17/bin:$PATH \
mvn -pl labs/jdk-labs -Dtest=StreamSpliteratorBehaviorTest test
```

```bash
JAVA_HOME=/path/to/jdk21 \
PATH=/path/to/jdk21/bin:$PATH \
mvn -pl labs/jdk-labs -Dtest=StreamSpliteratorBehaviorTest test
```

路径是当前项目开发机示例；其他环境替换为自己的 JDK 安装目录。先用 `java -version` 与 `mvn -version` 确认 Maven 真实使用的 JVM，避免只切换了 shell 中的 `java`，却仍由旧 JAVA_HOME 启动 Maven。

## 场景一：惰性组装与一次消费

运行 `observeLazyPipelineAndSingleUse()`：

1. 创建 `[1,2,3,4]` 的 `filter + map` 流水线。
2. 终止操作前，两个计数器都为 0。
3. `collect` 得到 `[20,40]`，filter 调用 4 次，map 调用 2 次。
4. 再次调用 `count` 被 `IllegalStateException` 拒绝。

建议断点：

| 方法 | 观察内容 |
| --- | --- |
| `AbstractPipeline(AbstractPipeline,int)` | `previousStage/nextStage`、`depth`、`linkedOrConsumed` |
| `ReferencePipeline.filter/map` | 每个中间操作创建的新 stage 和 op flags |
| `AbstractPipeline.evaluate(TerminalOp)` | 末 stage 标记消费、顺序/并行分支 |
| `AbstractPipeline.sourceSpliterator(int)` | source Spliterator 何时取出并清空 |
| `AbstractPipeline.wrapSink(Sink)` | 从 terminal 向 source 反向包装的 Sink 类型 |
| filter/map 匿名 Sink 的 `accept` | 元素从 source 向 terminal 正向流动 |

不要断言匿名内部类编号，例如 `ReferencePipeline$2$1`。编译器和版本可以改变编号，应通过调用栈、外部类和方法语义识别。

## 场景二：filter、map 与 limit 的融合短路

运行 `observeFilterMapLimitPipeline()`。该方法与页面动画使用同一组输入和操作：

```java
AtomicInteger filterCalls = new AtomicInteger();
Arrays.asList(1, 2, 3, 4).stream()
        .filter(value -> {
            filterCalls.incrementAndGet();
            return value % 2 == 0;
        })
        .map(value -> value * 10)
        .limit(1)
        .collect(Collectors.toList());
```

稳定结果为 `[20]`，filter 只收到 `1、2`。组装 `limit(1)` 时，`SliceOps` stage 保存的是 `skip=0`、`limit=1` 配置；真正会从 `1` 递减到 `0` 的运行期计数属于求值阶段创建的 Slice Sink。建议依次观察 `SliceOps.makeRef`、`opWrapSink`、匿名 Sink 的 `begin/accept/cancellationRequested`，不要在 stage 组装期寻找尚不存在的 remaining 字段。

## 场景三：顺序 findFirst 短路

运行 `observeShortCircuitTraversal()`。`IntStream.rangeClosed(1,100)` 在 `peek` 中计数，过滤七的倍数并 `findFirst`：

```text
结果 = 7
源端实际推进次数 = 7
```

这是顺序、有限、有序 range 的稳定实验。建议断点：

| 方法 | 观察变量 |
| --- | --- |
| `AbstractPipeline.copyInto` | SHORT_CIRCUIT flag 是否选择取消遍历 |
| `AbstractPipeline.copyIntoWithCancel` | wrapped sink 和 source spliterator |
| `IntPipeline.forEachWithCancel` | 每轮 cancellationRequested 与 tryAdvance |
| `FindOps$FindSink.accept` | `hasValue`、`value` |
| `FindOps$FindSink.cancellationRequested` | 找到 7 后为何停止下一次推进 |

并行 `findFirst` 不断言访问次数等于 7。多个叶任务可能已经在途；有序结果还要确认更靠左分区没有答案。

## 场景四：ArrayList 延迟绑定与 trySplit

运行 `observeLateBindingAndSplit()`：

1. 创建 `[1,2,3,4]` 的 ArrayList Spliterator，此时不调用遍历或 estimateSize。
2. 在同一线程追加 5。
3. 首次 `estimateSize()` 绑定 fence，输出大小 5。
4. 调用一次 `trySplit()`，先遍历返回前缀，再遍历原对象剩余后缀。
5. 合并观察值为 `[1,2,3,4,5]`。
6. source 声明 ORDERED、SIZED、SUBSIZED。

OpenJDK 8 推荐断点：

| 方法 | 观察变量 |
| --- | --- |
| `ArrayList.spliterator()` | 新对象的 `index=0`、`fence=-1` |
| `ArrayList$ArrayListSpliterator.getFence()` | `list.size`、`modCount`、fence 从 -1 绑定 |
| `estimateSize()` | `fence - index` |
| `trySplit()` | `lo`、`hi`、`mid`，返回前缀与原对象后缀 |
| `forEachRemaining()` | 两个分区各自的 index/fence 和元素范围 |

自动测试只验证两部分总大小和合并结果，不断言每次一定二等分。当前 ArrayList 的中点实现可观察，但 Spliterator 公共契约允许其他来源采用不同粒度，甚至返回 null。

## 场景五：绑定后的 fail-fast

运行 `observeFailFastAfterBinding()`：

1. source 为 `[1,2,3]`。
2. `estimateSize()` 先绑定 `fence=3` 和 expectedModCount。
3. source 再追加 4，modCount 改变。
4. `forEachRemaining` 遍历绑定范围，并抛 `ConcurrentModificationException`。

控制台会同时打印“异常前已观察”的元素。这是为了强调 OpenJDK 8 的批量循环可先执行 action，再在末尾检查 modCount。异常不撤销已经发生的副作用。

建议断点：

| 方法 | 观察变量 |
| --- | --- |
| `ArrayList$ArrayListSpliterator.getFence()` | `expectedModCount` 的绑定时刻 |
| `ArrayList.add/ensureCapacityInternal` | `modCount` 怎样改变 |
| `ArrayList$ArrayListSpliterator.forEachRemaining()` | `mc`、`hi`、`i`、末尾版本比较 |

不要用并发线程和 sleep 制造这个场景。同线程的确定步骤足以展示 fail-fast 边界，也不会把调度偶然性混入测试。

## 场景六：受控池内并行归并

运行 `observeParallelReduction()`。实验创建 parallelism=2 的自建 ForkJoinPool，在限定 5 秒内计算 1 到 1000 的平方和，finally 中立即关闭并等待线程退出。

建议断点：

| 方法 | 观察变量 |
| --- | --- |
| `AbstractPipeline.evaluate` | `isParallel()` 与 terminal op |
| `ReduceOps$ReduceOp.evaluateParallel` | 根 ReduceTask、source Spliterator |
| `AbstractTask.suggestTargetSize/getTargetSize` | `sizeEstimate`、leaf target、targetSize |
| `AbstractTask.compute` | `rs/ls`、`forkRight`、left/right child |
| 具体 reducing sink 的 `accept` | 叶分区内部累加值 |
| `ReduceOps$ReduceTask.onCompletion` | 左结果先接收右结果的 combine |
| `ForkJoinTask.fork/CountedCompleter.tryComplete` | 任务提交和完成传播 |

该实验稳定保证的是结果和有界资源生命周期。把并行流提交到自建 pool 后，当前主流 JDK 实现通常在该 pool 的 worker 中派生 Stream 内部任务，但 Stream API 没有“指定 Executor”的正式参数。不要把线程名称、具体 pool 私有字段或这个实现行为写成跨版本契约。

## 场景七：count 精确尺寸快路径

运行 `observeCountSizeOptimization()`。输入是四元素 List，流水线只增加不改变数量的 `peek`：

```text
JDK 8u412：count=4，peek 实际调用=4
JDK 17 GA：count=4，peek 实际调用=0
JDK 21 GA：count=4，peek 实际调用=0
```

JDK 8 推荐从 `ReferencePipeline.count` 进入 `mapToLong(e -> 1L).sum()`；JDK 17/21 从同一公开入口进入 `ReduceOps.makeRefCounting`，在 `evaluateSequential` 查看 `helper.exactOutputSizeIfKnown(spliterator)`。若返回 4，方法直接返回，不会构建逐元素 CountingSink。

建议做一次对照：在 `peek` 前加入 `filter(value -> true)`。结果仍是 4，但 filter 清除 SIZED，JDK 17/21 也必须实际遍历。由此可以确认优化条件是“整条流水线可证明精确输出大小”，不是“运行在新 JDK 就一定跳过”。

## 场景八：takeWhile 与取消原因

运行 `observeTakeWhileCancellation()`：

- JDK 8 打印 API 不存在，内部 `copyIntoWithCancel` 返回 `void`；
- JDK 17/21 通过公开 `takeWhile` 得到 `[1,2]`，谓词调用 3 次；
- 第三次调用检查的是失败元素 3，它不进入结果，却负责建立停止边界。

新版推荐断点：

| 方法 | 观察内容 |
| --- | --- |
| `ReferencePipeline.forEachWithCancel` | `cancelled` 在自然耗尽时为 false、Sink 请求停止时为 true |
| `AbstractPipeline.copyIntoWithCancel` | boolean 怎样从 source pipeline 返回 helper 调用方 |
| `WhileOps$TakeWhileTask.doLeaf` | `shortCircuited`、本叶 Node 和 `cancelLaterNodes()` |
| takeWhile Sink 的 `accept/cancellationRequested` | 谓词首次失败后怎样拒绝当前值并请求停止 |

顺序实验可以稳定断言三次谓词调用。并行 takeWhile 可能有多个叶任务在途，只应断言有序结果前缀，不应断言全局谓词精确调用次数。

## 场景九：skip / limit 的精确尺寸

运行 `observeSliceExactSize()`，流水线为四元素 SIZED 源的 `skip(1).limit(2)`：

```text
JDK 8u412：exactSize=-1，SIZED=false
JDK 17 GA：exactSize=2，SIZED=true
JDK 21 GA：exactSize=2，SIZED=true
```

JDK 8 在 `SliceOps.flags` 观察 `NOT_SIZED`。JDK 17/21 在同一位置观察 `IS_SIZE_ADJUSTING`，再进入 `AbstractPipeline.exactOutputSizeIfKnown` 和每个 Slice stage 的 `exactOutputSize(previousSize)`：

```text
4 --skip(1)--> 3 --limit(2)--> 2
```

不要把这个结论推广到 `filter(...).skip(...).limit(...)`。filter 已让上游尺寸未知，后续切片只能调整“未知值”，无法凭空恢复精确尺寸。

## 场景十：未知尺寸 IteratorSpliterator 批次

运行 `observeUnknownSizeSplit()`。实验用 `spliteratorUnknownSize` 包装四元素 Iterator，并在遍历前记录首个 `trySplit()` 返回值：

| 固定快照 | 实际元素 | estimate | SIZED |
| --- | --- | ---: | --- |
| JDK 8u412 | `[1,2,3,4]` | 4 | true |
| JDK 17 GA | `[1,2,3,4]` | 4 | true |
| JDK 21 GA | `[1,2,3,4]` | `Long.MAX_VALUE / 2` | false |

JDK 21 快照包含 JDK 19 引入的 JDK-8280915。新版不是不知道数组里有四个元素，而是故意用非精确大估计，让 AbstractTask 面对未知尺寸根节点的巨大 leaf threshold 时，仍能把这个数组批次继续二分。`ArraySpliterator.trySplit` 会同步折半 estimate；既然 estimate 不再精确，SIZED/SUBSIZED 就必须清除。

推荐断点顺序：

1. `Spliterators.spliteratorUnknownSize`：父对象 `est=Long.MAX_VALUE` 且无 SIZED。
2. `IteratorSpliterator.trySplit`：观察 `batch/n/j` 和复制出的数组。
3. JDK 8/17 的四参 `ArraySpliterator` 构造器：自动加入 SIZED/SUBSIZED。
4. JDK 21 的五参 `ArraySpliterator` 构造器：清除尺寸特征并保存启发式 estimate。
5. JDK 21 `ArraySpliterator.trySplit`：观察 estimate 与索引范围同时折半。

## 场景十一：真正触发并行 forEachOrdered

运行 `observeParallelForEachOrdered()`。实验在 parallelism=2 的受控 ForkJoinPool 中，对 `0..15` 调用并行 `forEachOrdered`，action 输出必须保持 `[0,1,...,15]`。这次终止操作会进入 `ForEachOps$ForEachOrderedTask`，不是用有序 `collect` 间接说明顺序。

| 固定快照 | 拆分时观察 | 完成时观察 |
| --- | --- | --- |
| JDK 8u412 / 17 GA | `completionMap.put/replace`、`leftPredecessor`、pending count | `completionMap.remove(this)` 后释放 successor |
| JDK 21 GA | `leftChild.next=rightChild`、`NEXT.compareAndSet`、pending count | `NEXT.getAndSet(this,null)` 后释放 successor |

某个右侧 Node 可以先算完，但不能越过左前驱执行 action。稳定测试只断言 action 的遇见顺序；任务实际完成顺序、执行 action 的线程和私有依赖字段只在固定源码断点中观察。

## 场景十二：mapMulti 零到多映射

运行 `observeMapMulti()`：JDK 8 打印 API 不存在；JDK 17/21 把偶数输入展开为自身和十倍值，得到 `[2,20,4,40]`。

建议同时打开两个入口：

- `Stream.mapMulti`：默认实现为每个输入创建 `SpinedBuffer`，再交给 flatMap，保证第三方 Stream 实现兼容；
- `ReferencePipeline.mapMulti`：标准流水线覆盖默认实现，在 Sink 的 `accept` 中把 downstream Consumer 直接交给 mapper。

在 `begin` 观察下游收到 `-1`，因为 mapper 对每个输入可能输出零个或多个元素，无法保留 SIZED。不要把 downstream Consumer 保存到字段、异步任务或 mapper 调用之后；公开规范明确该用法结果未定义。

## 动画案例怎样映射到断点

页面动画前八步与场景二使用同一条顺序流水线：

```java
Arrays.asList(1, 2, 3, 4).stream()
        .filter(value -> value % 2 == 0)
        .map(value -> value * 10)
        .limit(1)
        .collect(Collectors.toList());
```

其稳定轨迹是：

```text
组装 source/filter/map/limit
→ terminal sink 从后向前被包装
→ 1 被 filter 拒绝
→ 2 映射为 20，limit 配额归零
→ cancellationRequested 阻止继续拉取 3、4
```

动画后两步改用独立的 `[0,8)` 分区示例说明 `trySplit` 与有序归并。两段图不是同一次真实执行：前者解释 Sink 融合短路，后者解释并行分区，页面正文已显式标明边界。

## JDK 8、17、21 的断点差异

| 目标 | JDK 8u412 | JDK 17 GA | JDK 21 GA |
| --- | --- | --- | --- |
| pipeline 主干 | AbstractPipeline、PipelineHelper、Sink | 架构保持 | 架构保持 |
| `count()` | `mapToLong(1).sum`，实际遍历 | `ReduceOps.makeRefCounting` 可直接读精确尺寸 | 与 17 相同 |
| 取消结果 | `forEachWithCancel/copyIntoWithCancel` 返回 void | 返回 boolean，WhileOps 消费结果 | 与 17 相同 |
| 切片尺寸 | `SliceOps.NOT_SIZED` | `SIZE_ADJUSTING + exactOutputSize` | 与 17 相同 |
| 未知尺寸批次 | SIZED，estimate 为实际批次 | 与 8 相同 | JDK 19 起非 SIZED，使用启发式大估计 |
| 叶阈值 | 8u412 已识别当前 ForkJoin worker pool | 固定快照保持，但仍是私有启发式 | 同样不能当 API |
| 有序输出 | completionMap + Node 缓冲 | 与 8 相同 | next/VarHandle + Node 缓冲 |
| 零到多映射 | 无 mapMulti | Stream 默认回退 + ReferencePipeline 直接推送 | 与 17 相同 |

不要用 `peek(...).count()` 比较 JDK 8/17 的“正确性”。Java 9+ 允许在结果只依赖已知大小时省略元素遍历；peek 本来就只适合调试观察，不是业务回调保证。

## 稳定断言与脆弱观察

| 可以进入测试 | 只适合调试观察 |
| --- | --- |
| 中间操作在终止操作前未被调用 | 匿名 Sink 的精确类名和编号 |
| 同一 Stream 二次消费抛 IllegalStateException | 异常消息全文 |
| 顺序 findFirst 返回首个遇见元素 | 并行谓词精确调用次数 |
| ArrayList 拆分两部分无丢失、无重复 | 固定拆分次数、固定任务树形状 |
| SIZED 分区大小之和保持 | leaf target 的精确数值 |
| 未知尺寸批次实际元素无丢失、无重复 | `trySplit` 子结果跨版本一定带 SIZED 或 estimate 等于实际数量 |
| 绑定后结构修改尽力抛 CME | CME 发生前 action 已调用几次的跨版本固定值 |
| 有序 collect 返回遇见顺序 | 中间 lambda 的线程和执行先后 |
| 并行 forEachOrdered action 保持遇见顺序 | completionMap/next 字段作为业务接口 |
| 自建 pool 总能被关闭且有限等待结束 | worker 名称、steal 次数、commonPool parallelism |

## 自动化测试覆盖

`StreamSpliteratorBehaviorTest` 共覆盖十三类行为：

1. 惰性求值与一次消费。
2. 顺序 findFirst 的确定性短路。
3. filter、map、limit 的融合结果与停止位置。
4. ArrayList Spliterator 首次遍历前的延迟绑定。
5. 一次 trySplit 两部分的完整覆盖、大小与特征。
6. fence 绑定后的结构修改检测。
7. 有界自建 ForkJoinPool 中的并行有序归并结果。
8. JDK 8 与新版 JDK 的 `peek + count` 遍历差异。
9. JDK 9 起 `takeWhile` 的公开前缀与停止边界。
10. JDK 17/21 的 `skip/limit` 精确尺寸调整。
11. JDK 19 起未知尺寸批次的估计与特征变化。
12. 内部依赖重构前后的并行 `forEachOrdered` 公开顺序。
13. JDK 16 起 `mapMulti` 的零到多结果。

测试不访问 JDK 私有字段，不使用反射打开模块，不依赖 sleep、线程名、固定拆分数或私有任务类型，因此可以在 JDK 8 与 17/21 上作为公开行为回归。

## 实验完成标准

- 能画出 stage 链和反向包装后的 Sink 链，并说明数据为何正向流动。
- 能从 cancellationRequested 追到下一次 source 推进停止的位置。
- 能解释 ArrayList 的 fence 何时绑定、trySplit 返回哪一段、原对象留下哪一段。
- 能说明 fail-fast 为什么可能晚于 action 副作用。
- 能从 AbstractTask 的拆分循环追到叶 doLeaf 和父节点 combine。
- 能区分任务完成顺序、上游 lambda 执行顺序、结果遇见顺序和 forEachOrdered action 顺序。
- 能说明自建 ForkJoinPool 实验的价值，同时不把当前执行池选择写成 Stream API 契约。
- 能根据 SIZED、SIZE_ADJUSTING 和终止操作解释 count 是否真正遍历。
- 能并排说明未知尺寸批次的实际元素、estimate 和 characteristics，不把估计当计数。
- 能画出 forEachOrdered 的前驱依赖，并区分 8/17 completionMap 与 21 next/VarHandle。
- 调试结束后所有自建 pool 均已 shutdown，进程没有遗留教学任务。
