# ConcurrentHashMap：put 与协作扩容

## putVal 主循环

JDK 8 的 `put` 进入 `putVal(key, value, onlyIfAbsent)`，主循环反复读取当前 `table` 并根据桶状态选择分支：

```text
table 未初始化
  └─ initTable()

目标桶为空
  └─ CAS 写入新 Node，成功后结束

桶首 hash == MOVED
  └─ helpTransfer() 协助扩容，再用新表重试

普通桶或树桶
  └─ synchronized(桶首节点)
       ├─ 再确认桶首没有变化
       ├─ 链表查找/追加
       └─ TreeBin 查找/插入

新增成功
  └─ addCount(1, binCount)
```

## 动画：三个线程如何在同一张表上接力

演示先让 T1 通过 CAS 写入空桶，再让 T2 在碰撞桶上获得桶首监视器。扩容阶段使用容量 32 的旧表：T1 先领取 `16..31`，T3 在已迁移的 `table[19]` 看见 `ForwardingNode` 后进入 `helpTransfer`，再领取 `0..15`。因此 `table[3]` 只会在低区扫描真正到达下标 3 后变成 `MOVED`。动画表达的是一条满足源码约束的调度，不代表协作者固定为三个。

<ConcurrentHashMapAnimation />

### 每种桶首状态对应一种并发协议

| `f = tabAt(tab, i)` | 当前线程的动作 | 协调手段 | 失败或完成后的去向 |
| --- | --- | --- | --- |
| `null` | 安装首节点 | CAS | CAS 失败则重读桶 |
| 普通 `Node` | 查找或追加链表 | `synchronized(f)` | 锁内复查槽位后修改 |
| `TreeBin` | 查找或插入树节点 | TreeBin 内部协议 + 桶首同步 | 完成后更新计数 |
| `ForwardingNode` | 协助迁移 | `transferIndex` 分区 + CAS | 切换到新表重试 |
| `ReservationNode` | 等待占位计算结束 | `synchronized(f)` | 回到外层循环 |


JDK 8 的 `putVal` 没有针对 `ReservationNode` 的显式递归更新异常分支；其他线程会在占位节点监视器上等待，同一线程递归更新相同槽位甚至可能持续自旋。较新 JDK 增加了部分递归更新检测，因此业务代码在任何版本都不应依赖容器替自己识别这类错误。
关键不是简单记住“CAS + synchronized”，而是先根据桶首节点类型选择协议。外层 `for` 循环是所有竞争失败、表切换和初始化完成后的统一重试点。

### 为什么锁内还要复查 `tabAt(tab, i) == f`

线程第一次读到 `f` 与真正获得 `f` 的监视器之间存在等待窗口。等待期间，其他线程可能完成迁移并把 `table[i]` 改成 `ForwardingNode`。如果不复查就修改旧链，写入可能落在已经退出服务的旧表。

源码因此形成以下顺序：

```text
读取 f
  → synchronized(f)
  → 再读 table[i]
  → 仍然是同一个 f 才允许改链或改树
  → 否则退出同步块并由外层循环重试
```

### 协作扩容不是所有线程搬同一个桶

`transferIndex` 像一个从高下标向低下标推进的任务游标。协作者通过 CAS 领取互不重叠的区间，各自迁移区间内的桶；已完成桶写入 `ForwardingNode`。最后退出的迁移线程负责发布 `nextTable` 为正式 `table`。

调试 `helpTransfer` 时，应同时观察 `table`、`nextTable`、`transferIndex` 和 `sizeCtl`。只看 `sizeCtl < 0` 只能知道处于特殊状态，不能直接把任意负值解释成“线程数量”。

以旧容量 `n = 32`、演示步长 `stride = 16` 为例，领取动作必须满足下面的时间线：

```text
创建 nextTable[64]，transferIndex = 32
  → T1 CAS: 32 -> 16，领取 [16, 31]
     此时 table[3] 仍是原链
  → 高区 table[19] 迁移完成，old table[19] = ForwardingNode
  → T3 在 table[19] 看到 MOVED，经 helpTransfer 登记
  → T3 CAS: 16 -> 0，领取 [0, 15]
     领取后从 15 向 0 扫描
  → 扫描到 3：先写 nextTable[3/35]，再写 old table[3] = ForwardingNode
  → 全部线程退出，末位线程 finishing 复扫后发布新表
```

`transferIndex` 表示“尚未领取区间的右边界”，不是“已经迁移完成到哪个桶”。所以 `transferIndex = 16` 只能说明 `16..31` 已被某个线程领取，不能据此把 `0..15` 中的 `table[3]` 画成 `MOVED`；同样，游标降到 0 时，低区也只是已领取，线程仍可能正在从 15 向 0 处理。

## 空桶为什么用 CAS

空桶没有已有链表需要保护。线程通过 CAS 把 `null` 替换为新节点，失败说明其他线程已经抢先写入，外层循环重新读取状态即可。

这条路径避免为无碰撞写入创建或获取锁。

## 碰撞桶为什么锁桶首

链表或树的修改涉及多个指针，JDK 8 使用 `synchronized(f)` 锁住当前桶首节点。进入同步块后必须再次确认 `tabAt(tab, i) == f`，因为等待锁期间扩容或其他写入可能改变数组槽位。

锁粒度是单个桶，不是整个 Map。不同桶上的写入通常可以并行。

## 特殊 hash 节点

普通节点的哈希为非负值；负值用于标识内部控制节点：

| 标识 | 节点 | 作用 |
| ---: | --- | --- |
| `MOVED (-1)` | `ForwardingNode` | 当前桶已迁移，指向新表 |
| `TREEBIN (-2)` | `TreeBin` | 红黑树桶的容器与锁状态 |
| `RESERVED (-3)` | `ReservationNode` | `computeIfAbsent` 等计算期间的占位 |

内部标识会随实现演进，业务代码不能依赖。

## initTable 如何避免重复初始化

多个线程发现表为空时，通过 CAS 把 `sizeCtl` 从非负值改为 -1。成功线程创建数组并设置阈值，其他线程发现初始化进行中后让出执行并重试。

初始化完成在 `finally` 中恢复 `sizeCtl`，避免异常路径永久留下初始化标记。

## transfer 的协作迁移

扩容不是固定由一个线程完成：

1. 发起线程创建 `nextTable`，容量通常为旧表两倍。
2. 线程通过 `transferIndex` 领取一段旧桶区间。
3. 每个桶按 `hash & oldCap` 拆成低位和高位两组。
4. 迁移完成的旧桶写入 `ForwardingNode`。
5. 后续写线程遇到它，通过 `helpTransfer` 加入迁移。
6. 最后一批迁移完成后发布新表和新阈值。

`ForwardingNode` 同时解决两件事：告诉写线程协助迁移，告诉读线程到 `nextTable` 继续查找。

### 单个桶的发布顺序

普通链表桶迁移时，源码按旧容量位 `hash & n` 把节点拆为低位链和高位链。发布顺序不能颠倒：

```text
计算 lo / hi
  → setTabAt(nextTab, i, lo)
  → setTabAt(nextTab, i + n, hi)
  → setTabAt(tab, i, fwd)
```

只有新表两个目标槽位准备好后，旧槽位才安装 `ForwardingNode`。因此读取线程一旦在旧表看到路标，就能沿 `nextTable` 查到已发布的目标桶；写线程也不会继续修改已经退出服务的旧链。

空桶同样要 CAS 安装 `fwd`，避免协作者重复处理；已经是 `MOVED` 的桶直接跳过。树桶迁移时先按同一个容量位拆分，分组数量不超过 `UNTREEIFY_THRESHOLD` 时可能退化为链表，否则保留或重建 `TreeBin`。

### 最后一位线程为什么还要复扫

迁移线程完成已领取区间后通过 CAS 减少 `sizeCtl` 中的参与者计数。判断自己是最后退出者的线程不会立即发布新表，而会把 `finishing` 和 `advance` 设为 `true`，从旧表末端再扫一遍，确认没有遗漏后才执行：

```text
nextTable = null
table = nextTab
sizeCtl = 新容量 - 新容量 / 4
```

这一步把“部分桶可通过转发访问”的迁移期切换成“所有新操作直接从新表开始”的稳定期。

## helpTransfer 如何决定是否加入迁移

严格源码签名为：

```java
final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f)
```

它不是看到任意负哈希节点就无条件搬运，而是依次确认：

1. `tab` 非空，`f` 确实是 `ForwardingNode`，且路标中的 `nextTable` 非空；
2. 循环期间实例字段 `table` 仍是传入的旧表，`nextTable` 仍是路标指向的新表；
3. `sizeCtl < 0`，说明同一轮扩容仍未发布结束；
4. 未达到最大协作者数量，扩容未进入末位迁移线程的收尾状态，并且 `transferIndex > 0`；
5. CAS 把 `sizeCtl` 从 `sc` 改为 `sc + 1` 成功，才调用 `transfer(tab, nextTab)` 领取区间。

若没有剩余区间，线程不会虚构一段工作；方法仍可返回从 `ForwardingNode` 取得的 `nextTab`，让 `putVal` 的外层循环按新容量重新定位。若传入节点已不满足转发条件，则返回实例当前的 `table`。

## ForwardingNode.find 如何连续转发

严格源码签名为：

```java
Node<K,V> ForwardingNode.find(int h, Object k)
```

读取从路标持有的 `nextTable` 开始，按新长度重新计算 `(n - 1) & h`。普通节点走键匹配和链表，`TreeBin` 等其他特殊节点委托它们自己的 `find`；如果又遇到 `ForwardingNode`，说明新表对应桶已经进入下一轮扩容，外层循环继续切到更大的 `nextTable`。源码特意用循环处理连续转发，避免扩容层数变成递归深度。

## 树化边界

碰撞链达到阈值后会调用 `treeifyBin`，但数组容量小于 `MIN_TREEIFY_CAPACITY` 时优先扩容。达到容量条件后，桶被转换为 `TreeBin` 管理的红黑树节点。

这个策略与 HashMap 思路相近，但 TreeBin 还需要协调并发读写，不能把两者的树节点锁机制等同。

### TreeBin.find 的读路径

严格源码签名为 `final Node<K,V> TreeBin.find(int h, Object k)`。它会根据 `lockState` 选择两种查找方式：

- 已存在 `WRITER` 或 `WAITER` 时，不阻塞等待树锁，而是沿 `first` 链表线性查找；
- 没有写者或等待写者时，CAS 增加 `READER` 计数，从 `root` 走红黑树查找，最后减少读计数；如果自己是最后一个读者且写者在等候，就唤醒 `waiter`。

所以“ConcurrentHashMap 的 get 不加锁”准确含义是不会获得桶首的 `synchronized` 监视器；树查找仍使用 `lockState` 的读者计数与写者协调。

### TreeBin.putTreeVal 的写路径

严格源码签名为 `final TreeNode<K,V> TreeBin.putTreeVal(int h, K k, V v)`。`putVal` 已经在 `synchronized(f)` 内确认桶首仍是同一个 `TreeBin`，然后调用它：

1. 先按哈希、可比较类型和比较结果寻找方向；
2. 哈希相同但键不可比较时，先搜索左右子树以免漏掉已有键，再用 `tieBreakOrder` 决定新节点方向；
3. 找到同键时返回已有 `TreeNode`，由外层决定是否覆盖值；
4. 新增时同时接入 `first` 链和树父子关系；父节点为红色时获取 TreeBin 根写锁并做红黑树平衡，最后返回 `null` 表示确实新增。

`first` 链不是多余结构：写锁竞争时的 `find`、遍历器以及扩容拆分都需要它。红黑树负责理想情况下的查找复杂度，链表视图负责并发协议中的退路和结构迁移。
