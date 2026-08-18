# put 写入流程

## 入口只有两件事

`put(K key, V value)` 本身很薄：先计算扰动哈希，再调用内部的 `putVal`。

```text
put(key, value)
  ├─ hash(key)
  └─ putVal(hash, key, value, onlyIfAbsent=false, evict=true)
```

`onlyIfAbsent` 控制已有键是否覆盖非空旧值，`evict` 给 `LinkedHashMap` 的扩展钩子区分正常插入和构造/反序列化场景。

## 动画：跟踪一次碰撞写入

下面固定一个可复现快照：`table.length=4`、`size=2`、`threshold=3`，键 `C` 的扰动哈希为 5。它会落入已有节点 `A` 所在的 `table[1]`，因此能完整经过定位、碰撞比较、链尾追加和结构状态更新。

<HashMapPutAnimation />

### 动画里四个数字分别来自哪里

| 观察值 | 计算或写入位置 | 本例结果 |
| --- | --- | ---: |
| `hash` | `HashMap.hash(key)` | 5 |
| `i` | `(n - 1) & hash` | 1 |
| `size` | 只有新增映射才执行 `++size` | 3 |
| `threshold` | 当前容量与负载因子确定 | 3 |

这里故意让新增后的 `size` 等于 `threshold`。源码的扩容条件是 `++size > threshold`，所以本次不扩容；再新增一个不同键才会越过阈值。

### 在源码中逐步核对

1. 在 `putVal` 入口记录 `tab`、`n`、`hash`、`key` 和 `size`。
2. 执行 `(n - 1) & hash` 后确认 `i=1`，并观察 `p=tab[1]` 不是 `null`。
3. 在桶首判等分支观察 `p.hash`、`p.key == key` 和 `key.equals(p.key)`，确认没有命中旧键。
4. 进入链表循环，每次只让 `p` 前进一步；到达尾节点时 `p.next == null`。
5. 执行 `p.next = newNode(...)` 后，先观察链表已经改变，再观察 `modCount` 和 `size` 增加。
6. 最后检查 `size > threshold`，不要把“调用了 `resize` 检查”误写成“发生了扩容”。

::: warning 调试时不要调用会修改 Map 的表达式
IDE 的 Evaluate Expression 也会真正执行代码。断点处不要调用 `put`、`compute` 或会改变键字段的方法，否则看到的结构不再是原始执行路径。
:::

## putVal 的五个阶段

下面是保留关键分支的结构化伪代码：

```java
if (table 尚未分配或长度为 0) {
    通过 resize() 完成首次分配;
}

定位 bucket = table[(length - 1) & hash];
if (bucket 为空) {
    创建普通 Node;
} else if (桶首节点就是目标键) {
    记录待覆盖节点;
} else if (bucket 是 TreeNode) {
    在红黑树中插入或找到目标键;
} else {
    遍历链表;
    找不到相同键则追加节点，并在达到条件时尝试树化;
}

if (找到了已有键) {
    按 onlyIfAbsent 规则更新 value;
    返回旧值;
}

记录结构性修改并增加 size;
if (size 超过 threshold) {
    resize();
}
执行插入后的扩展钩子;
```

### 阶段一：首次分配

无参构造的 `HashMap` 通常没有立即分配桶数组。第一次写入进入 `putVal` 后，发现 `table` 为空，于是调用 `resize()` 分配默认的 16 个桶，扩容阈值为 12。

这意味着创建大量空 `HashMap` 不会立刻为每个对象分配默认数组。

### 阶段二：空桶直接写入

`(n - 1) & hash` 得到下标。如果该位置为空，创建 `Node` 放入数组。这是最短路径，不需要调用任何已有键的 `equals`。

### 阶段三：处理碰撞

桶不为空时，按以下顺序处理：

1. 桶首节点的 `hash` 相同，并且键引用相同或 `equals` 成立：命中已有键。
2. 桶首节点是 `TreeNode`：调用 `putTreeVal` 在红黑树中查找或插入。
3. 其他情况：遍历链表，查找相同键；找不到则追加到尾部。

先比较缓存的 `hash` 能过滤大部分无关节点，只有哈希相同时才需要进一步比较键。

### 阶段四：覆盖与新增分开

找到已有键时，`putVal` 更新节点的 `value` 并返回旧值。这个操作：

- 不增加 `size`；
- 通常不增加 `modCount`；
- 不触发因元素数量增长而发生的扩容；
- 会调用 `afterNodeAccess`，让 `LinkedHashMap` 有机会调整访问顺序。

只有新增映射才进入结构性修改路径，增加 `modCount` 和 `size`。

### 阶段五：新增后检查扩容

实现顺序是先增加 `size`，再判断 `size > threshold`。因此默认容量 16、默认阈值 12 时，第 13 个映射插入后触发扩容。

阈值判断使用“大于”而不是“大于等于”。第 12 个元素写入后，`size == threshold`，不会因为这个判断扩容。

## 键判等规则

源码中的语义可以概括为：

```text
节点 hash == 目标 hash
并且
(节点 key 与目标 key 是同一引用，或者目标 key 与节点 key equals)
```

因此：

- `hashCode` 不同的两个对象不会被当作相同键。
- `equals` 相等的对象必须返回相同 `hashCode`，否则违反 Java 通用契约。
- 键放入后如果参与哈希的字段发生变化，后续 `get` 可能无法在原桶找到它。

## 树化判断的准确含义

链表追加后，`putVal` 根据遍历计数决定是否调用 `treeifyBin`。调用 `treeifyBin` 不等于一定变成树：如果数组容量小于 64，它会优先扩容。

所以“链表长度达到 8 就一定树化”是不准确的。完整条件至少还涉及数组容量与本次插入后的链表状态。实验中通常使用初始容量 64 并插入 9 个同哈希键，稳定进入树化代码。

## putIfAbsent、compute 和 merge

- `putIfAbsent` 复用 `putVal`，把 `onlyIfAbsent` 设为 `true`。
- `computeIfAbsent`、`compute`、`merge` 也直接操作桶结构，并按各自条件调用用户函数、处理返回 `null`、已有值和结构性修改。
- 映射函数不应在计算期间递归地修改同一个 Map；不同 JDK 版本会尽力检测部分并发修改，但不应依赖检测来保证正确性。
- `computeIfAbsent` 在键已有非空值时不会调用映射函数；`merge` 在键缺失时直接安装给定 value，不调用 remapping function。

这些 API 的对外语义应优先参考 `Map` 和 `HashMap` Javadoc，内部复用方式属于版本实现细节。

