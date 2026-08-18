# ConcurrentHashMap：结构与并发读写

`ConcurrentHashMap` 提供线程安全的哈希映射。JDK 8 取消了 JDK 7 的固定 `Segment[]` 主结构，改为桶数组、CAS、桶首同步和协作扩容的组合。

[打开 JDK 8 / 17 / 21 版本对比 →](/jdk/version-comparison/)，可核对原子槽位访问、递归更新检测、并发视图删除和树桶竞争逻辑的演进。

## 核心结构

```text
table: Node<K,V>[]
  ├─ 普通 Node 链表
  ├─ TreeBin → TreeNode 红黑树
  └─ ForwardingNode → nextTable 扩容转发节点
```

普通 `Node` 的 `hash`、`key` 固定，`val` 和 `next` 使用可见性语义支持并发读取。数组槽位通过底层原子访问方法读取和更新。

## 关键字段

| 字段 | 作用 |
| --- | --- |
| `table` | 当前桶数组，延迟初始化 |
| `nextTable` | 扩容期间的新数组 |
| `sizeCtl` | 初始化容量、扩容阈值或初始化/扩容状态编码 |
| `transferIndex` | 多线程协作迁移时待领取区间的边界 |
| `baseCount` | 低竞争时的基础计数 |
| `counterCells` | 高竞争时分散更新的计数单元 |

`sizeCtl` 不能简单理解为 HashMap 的 `threshold`：

- 0 表示使用默认初始化策略；
- 正数可表示计划容量或正常扩容阈值；
- -1 表示有线程正在初始化；
- 其他负数在扩容时编码扩容戳和参与迁移的信息。

## get 为什么不锁桶

```text
get(key)
  └─ spread(key.hashCode())
  └─ tabAt(table, index)
       ├─ 桶首直接命中
       ├─ hash < 0 → 特殊节点 find
       └─ 普通链表顺序查找
```

读取依赖数组槽位与节点字段的可见性设计，不会为普通查询获取桶首监视器。扩容期间遇到 `ForwardingNode` 时，`get` 会调用它的 `find(int h, Object k)`，从当前转发节点持有的 `nextTable` 继续查找。

“读取不加锁”不等于整个类无锁。写入碰撞桶、树操作和部分初始化流程仍需要同步或 CAS 协调。

## ForwardingNode.find 如何跨表读取

`ForwardingNode` 不是“扩容已全部完成”的标志，而是“这个旧桶已经完成迁移”的路标。它保存本轮扩容的 `nextTable`，其 `find` 流程可以简化为：

```text
tab = forwardingNode.nextTable
  → 按新容量重新计算下标
  → 普通 Node：匹配当前节点或沿链表查找
  → TreeBin：委托 TreeBin.find
  → 又一个 ForwardingNode：切换到它的 nextTable，继续外层循环
```

最后一条用于处理连续扩容：一个读取可能先从旧表进入中间表，又发现对应桶已经迁往更大的表。源码使用外层循环切表，避免用递归层层调用转发节点。查找期间没有找到、键为 `null`、新表为空或目标桶为空时返回 `null`。

需要区分读写两条路径：

- `get` 遇到 `ForwardingNode` 调用 `ForwardingNode.find`，目标是继续完成本次读取；
- `putVal` 遇到 `MOVED` 调用 `helpTransfer`，目标是校验扩容状态、尝试协助，然后基于返回的新表重试写入。

## 为什么禁止 null

`ConcurrentHashMap` 不允许 `null` 键或值。这样 `get(key) == null` 可以明确表示当前没有映射，而不会在并发变化下混淆“键不存在”和“值就是 null”。

```java
map.put(null, value); // NullPointerException
map.put(key, null);   // NullPointerException
```

## 迭代器是弱一致的

迭代器不会抛出 `ConcurrentModificationException`，可以与更新并发执行。它反映创建时或创建后某个时刻的元素状态，但不承诺提供整个 Map 的单一原子快照。

### Traverser 如何避免跨表漏走路径

`containsValue`、迭代器和 Spliterator 等遍历能力都建立在内部 `Traverser` 上。`Traverser.advance()` 正常情况下按桶前进；遇到特殊桶首时按节点类型处理：

| 桶首 | `advance()` 的动作 |
| --- | --- |
| 普通 `Node` | 沿 `next` 返回下一个有效节点 |
| `TreeBin` | 从 `TreeBin.first` 的链表视图遍历，不在遍历时走红黑树搜索 |
| `ForwardingNode` | 切到 `nextTable`，并用 `pushState` 保存旧表、旧下标和旧容量 |
| 其他负哈希控制节点 | 本轮不返回用户映射 |

进入新表后，同一个旧桶可能拆到 `index` 和 `index + oldCapacity`。`recoverState` 根据保存的 `TableStack` 恢复旧表位置，保证遍历能继续覆盖拆分路径，而不是沿新表一直前进后忘记旧表进度。连续扩容时这个栈可以保存多层表状态。

这套账本解决的是“在结构迁移中继续走图”，不是把遍历变成快照。已经访问过的桶后来新增元素仍可能看不到；遍历期间的并发删除或新增也不能用于推断某一瞬间的精确大小。

## 与 Collections.synchronizedMap 的区别

同步包装器通常用一个互斥锁保护 Map 操作，遍历时还要求调用方手动同步。`ConcurrentHashMap` 则允许更高程度的读写并发，并提供 `computeIfAbsent`、`merge` 等原子复合操作。

选择并发容器后仍需设计业务原子边界。多个 Map 调用组合成的业务事务不会自动变成原子操作。
