# 数据结构与关键字段

## 类型关系

OpenJDK 8 中，`HashMap<K,V>` 继承 `AbstractMap<K,V>`，实现 `Map<K,V>`、`Cloneable` 和 `Serializable`。核心节点关系如下：

```text
Map.Entry<K,V>
       ▲
       │ implements
HashMap.Node<K,V>              普通单向链表节点
       ▲
       │ extends
LinkedHashMap.Entry<K,V>       增加 before / after
       ▲
       │ extends
HashMap.TreeNode<K,V>          增加 parent / left / right / prev / red
```

`TreeNode` 继承 `LinkedHashMap.Entry` 看起来有些反直觉，这是为了让 `LinkedHashMap` 能复用 HashMap 的树节点实现并维护访问或插入顺序。

## Node 保存什么

普通节点包含四个核心信息：

| 字段 | 含义 | 是否会变化 |
| --- | --- | --- |
| `hash` | 插入时计算出的扰动哈希 | 节点生命周期内不变 |
| `key` | 键引用 | 引用不变，但对象自身可能被错误修改 |
| `value` | 值引用 | 覆盖写入时可以改变 |
| `next` | 同一个桶中的下一个节点 | 插入、删除、扩容时可能改变 |

查找时先比较缓存的 `hash`，再比较键：引用相同直接命中，否则调用 `equals`。因此键对象必须正确实现 `hashCode` 和 `equals`，并且放入 Map 后不要修改参与这两个方法的字段。

## 六个重要常量

| 常量 | JDK 8 值 | 作用 |
| --- | ---: | --- |
| `DEFAULT_INITIAL_CAPACITY` | 16 | 无参 HashMap 第一次分配时使用的默认容量 |
| `MAXIMUM_CAPACITY` | `1 << 30` | 数组最大容量 |
| `DEFAULT_LOAD_FACTOR` | 0.75 | 默认负载因子 |
| `TREEIFY_THRESHOLD` | 8 | 链表达到树化候选条件的阈值 |
| `UNTREEIFY_THRESHOLD` | 6 | 扩容拆树等场景中的反树化阈值 |
| `MIN_TREEIFY_CAPACITY` | 64 | 允许树化的最小数组容量 |

树化使用 8、反树化使用 6，中间留出缓冲区，避免桶大小在临界点附近反复转换。

## 核心字段

| 字段 | 作用 |
| --- | --- |
| `table` | 桶数组，首次写入前通常为 `null` |
| `entrySet` | `entrySet()` 视图的惰性缓存 |
| `size` | 当前键值映射数量，不是数组长度 |
| `modCount` | 结构性修改次数，用于迭代器快速失败检查 |
| `threshold` | 下一次需要扩容的大小边界；分配前还可暂存计划容量 |
| `loadFactor` | 负载因子，构造后不再改变 |

### threshold 的双重含义

这是阅读构造器时最容易忽略的点：

- 数组尚未分配时，带初始容量的构造器把规范化后的计划容量暂存在 `threshold`。
- 数组分配后，`threshold` 才表示通常意义上的扩容阈值。

例如 `new HashMap<>(10)` 不会立刻创建长度为 16 的数组。构造器通过 `tableSizeFor(10)` 得到 16 并存入 `threshold`；第一次 `put` 触发 `resize()`，才分配 16 个桶并把新阈值设为 12。

## 为什么容量是 2 的幂

当容量 `n` 为 2 的幂时，`n - 1` 的低位全部是 1：

```text
n = 16       0001 0000
n - 1 = 15   0000 1111
```

因此桶下标可以用位运算计算：

```text
index = (n - 1) & hash
```

它等价于只保留 hash 的低若干位。`tableSizeFor` 会把构造参数向上规范化为 2 的幂，并在最大容量处封顶。

## hash 为什么要扰动

JDK 8 的核心规则可以表示为：

```java
h = key.hashCode();
hash = h ^ (h >>> 16);
```

桶下标主要使用低位。如果某类对象的差异集中在高 16 位，不做扰动就容易进入同一个桶。异或把高位信息折叠到低位，在计算成本和分布质量之间取平衡。

`null` 键的扰动哈希为 0，所以当前实现会把它放入第 0 个桶；这是实现结果，不是 `Map` 接口要求。

<HashMapIndexCalculator />

## 负载因子为什么默认是 0.75

负载因子越低，空桶越多、碰撞更少，但占用更多内存；越高，数组更紧凑，但链表或树节点数量更容易增加。0.75 是 OpenJDK 默认实现选择的时间与空间折中，不是所有业务场景的最优值。

如果能提前估计元素数量，应设置合适初始容量，减少扩容。注意构造参数是内部容量提示，不等同于“放入这么多元素绝不扩容”。在较新 JDK 中可以使用 `HashMap.newHashMap(expectedMappings)` 表达预期映射数量；JDK 8/17 没有这个静态工厂。

