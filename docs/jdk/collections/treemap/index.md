# TreeMap：有序映射与红黑树骨架

`TreeMap` 同时实现 `Map`、`SortedMap` 和 `NavigableMap`。它不依赖哈希桶，而是把每个键值对保存为红黑树节点；键的比较结果决定查找方向，中序遍历天然得到排序后的键序列。

本专题以 **OpenJDK 8u** 的 `java.util.TreeMap` 为主基线。阅读时先分清两个层面：

1. 二叉搜索树规则负责“向左还是向右”和有序导航。
2. 红黑树规则负责限制树高，避免递增插入退化成链表。

推荐顺序：

1. 本页先建立字段、比较契约和 `put` 主路径。
2. 阅读 [插入平衡、旋转与删除修复](./put-balance.md)。
3. 阅读 [导航查询与范围视图](./navigation-view.md)。
4. 最后按 [断点实验手册](./debug-lab.md) 进入本机 JDK 源码。

## 类型关系与核心字段

```text
AbstractMap<K,V>
  └─ TreeMap<K,V>
       implements NavigableMap<K,V>, Cloneable, Serializable

SortedMap<K,V>
  └─ NavigableMap<K,V>
```

JDK 8 的核心状态很集中：

| 字段 | 作用 | 阅读时的边界 |
| --- | --- | --- |
| `comparator` | 自定义键顺序；为 `null` 时使用键的自然顺序 | 排序规则创建后不会更换 |
| `root` | 红黑树根节点 | 空树时为 `null` |
| `size` | 当前映射数量 | 覆盖已有键不会增加 |
| `modCount` | 结构性修改计数 | 供迭代器尽力快速失败，不是并发控制 |

内部 `Entry<K,V>` 同时是树节点和键值对，保存：

| 字段 | 含义 |
| --- | --- |
| `key` / `value` | 当前映射的键和值 |
| `left` / `right` | 左、右孩子 |
| `parent` | 父节点，供旋转和向上修复使用 |
| `color` | 红或黑；新接入节点会在插入修复开始时标红 |

TreeMap 没有为每个空孩子创建哨兵对象。源码中的 `colorOf(null)` 把 `null` 视为黑色叶子，因此分析黑高时仍要把这些逻辑叶子算进去。

## 红黑树维护的五条不变量

把空孩子也当成黑色叶子，可以用下面五条规则检查任意快照：

1. 每个真实节点只有红、黑两种颜色。
2. 根节点为黑色。
3. 所有 `null` 叶子按黑色处理。
4. 红节点的两个孩子都不能是红色，也就是不能出现相邻红节点。
5. 从任一节点到其所有后代 `null` 叶子的路径，经过的黑节点数相同。

第 4 条阻止连续红节点无限拉长，第 5 条限制左右子树高度差。二者共同保证含 `n` 个节点的红黑树高度为 O(log n)，因此查找、插入和删除都能保持对数级上界。

::: tip 红黑树不是严格平衡树
左右子树高度不必相等，根节点也不一定是排序后的中位数。它维护的是颜色和黑高约束，以较少旋转换取稳定的 O(log n) 操作。
:::

## Comparator 与 Comparable 两条比较路径

TreeMap 只相信**比较结果**，不会在定位键时再用 `equals` 做第二次确认。

### 显式 Comparator

构造时传入 `Comparator<? super K>` 后，查找和写入都调用比较器：

```java
NavigableMap<String, Integer> map =
        new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
```

此时 `"Java"` 与 `"JAVA"` 的比较结果为 0，TreeMap 把它们视为同一个键位置。第二次 `put` 更新原节点的 value，节点中保留的 key 仍是第一次写入的 `"Java"`。

### 自然顺序

没有显式比较器时，键必须能够按 `Comparable` 相互比较。JDK 8 的主路径会把待写入键当作 `Comparable<? super K>`，并调用 `compareTo`：

- 比较结果小于 0：进入左子树。
- 比较结果大于 0：进入右子树。
- 比较结果等于 0：命中已有映射。

空树第一次写入也会执行一次 `compare(key, key)`。这一步没有查找意义，目的是尽早检查键类型以及 `null` 是否被当前排序规则接受。

| 场景 | 结果 |
| --- | --- |
| 自然顺序写入 `null` | 抛出 `NullPointerException` |
| 比较器明确支持 `null` | 可以保存 `null` 键 |
| 键之间不能相互比较 | 比较发生时抛出 `ClassCastException` |
| `compare(a, b) == 0` 但 `!a.equals(b)` | TreeMap 仍把二者当作同一键 |

::: warning 排序与 equals 最好保持一致
比较结果为 0 就代表 TreeMap 的键相等。若比较器与 `equals` 不一致，TreeMap 自身仍能工作，但与其他按 `equals` 判断键身份的 Map 互换、比较或组装时，可能出现违反直觉的结果。
:::

## put 的查找、覆盖与接入

JDK 8 的 `put(K key, V value)` 可以拆成四段：

```text
put(key, value)
  ├─ root == null
  │    ├─ compare(key, key) 自比较校验
  │    ├─ 建立默认黑色的根节点
  │    ├─ size = 1，modCount++
  │    └─ return null
  └─ root != null
       ├─ 选择 comparator 或 Comparable 路径
       ├─ 从 root 循环比较并记录 parent
       ├─ cmp == 0 → 只替换 value，返回旧值
       └─ 到达 null → 新 Entry 接到 parent 左侧或右侧
            ├─ fixAfterInsertion(entry)
            ├─ size++
            └─ modCount++
```

### 为什么循环要保留 parent 和最后一次 cmp

搜索变量 `t` 最终会走到 `null`，这个位置本身不是对象，无法记录新节点应该挂到哪里。因此循环还保存最后访问的非空节点 `parent`，并复用最后一次比较结果：

- `cmp < 0`：新节点成为 `parent.left`。
- `cmp > 0`：新节点成为 `parent.right`。

新 Entry 在构造时写入 `parent`，左右孩子起初为 `null`。修复逻辑随后把它设为红色，并根据父、叔、祖父的颜色决定是否旋转。

### 覆盖为什么不是结构性修改

当比较结果为 0 时，`put` 直接调用目标 Entry 的 `setValue` 并返回旧值：

- `size` 不变；
- `modCount` 不变；
- 树形和节点颜色不变；
- 节点中原有 key 不会被新 key 对象替换。

这也解释了为什么创建迭代器后，仅替换已有 key 的 value 通常不会触发 `ConcurrentModificationException`；新增或删除映射才是结构性修改。

## 查询为什么也是 O(log n)

`getEntry` 使用同样的比较方向从根向下走。每比较一次都会排除一整棵不可能命中的子树。红黑约束把树高限制在 O(log n)，因此 `get`、`containsKey` 与 `put` 的定位阶段都有对数上界。

值没有参与树的排序。`containsValue` 只能从第一个节点开始按后继遍历全部 Entry，最坏需要 O(n)。

## 复杂度地图

| 操作 | 时间复杂度 | 主要路径 |
| --- | --- | --- |
| `get` / `containsKey` | O(log n) | 根到叶的比较路径 |
| `put` / `remove` | O(log n) | 查找加有限次旋转、向上重着色 |
| `firstKey` / `lastKey` | O(log n) | 一直向左或向右 |
| `lower` / `floor` / `ceiling` / `higher` | O(log n) | 向下比较，必要时沿 parent 回退 |
| `containsValue` | O(n) | 按后继遍历所有节点 |
| 完整有序遍历 | O(n) | 每个节点恰好访问一次 |
| `size` | O(1) | 直接读取字段 |

TreeMap 每个映射都需要 Entry 对象以及父子引用和颜色位，空间复杂度为 O(n)。它不会像 HashMap 那样预留整段桶数组，但单节点的链接开销更高。

## 迭代顺序、线程安全与快速失败

`entrySet()`、`keySet()` 和 `values()` 的普通迭代顺序都是键的升序；`descendingMap()` 和 `descendingKeySet()` 提供反向视图。迭代器通过 `successor` 或 `predecessor` 在节点间移动，不需要先把键复制到数组。

TreeMap 不是线程安全容器。多个线程至少有一个线程修改结构时，需要外部同步或改用适合业务语义的并发结构。`modCount` 只能让迭代器尽力发现结构在迭代期间被修改，不能提供可见性、原子性或确定性的并发错误检测。

## 实现细节与公开契约

| 可以依赖的公开行为 | 不应依赖的内部细节 |
| --- | --- |
| 键按比较规则排序 | 某组键对应的精确根节点和颜色 |
| 基本操作 O(log n) | 插入一定执行几次旋转 |
| 导航方法的严格/包含边界 | `Entry` 字段布局和私有辅助方法名 |
| 范围 Map 是受边界约束的视图 | JDK 8 的内部节点对象身份 |
| 结构性修改后迭代器尽力快速失败 | 异常一定在某个固定时刻抛出 |

## JDK 版本边界

- 本文的方法名、字段名和断点变量以 OpenJDK 8u 为准。
- JDK 17/21 仍使用基于比较的红黑树并保留同类导航语义，但私有辅助方法、序列化代码和视图实现可以调整。
- Java 21 的集合接口增加了有序集合相关的统一 API；用 Java 8 编译实验时不能直接使用较新版本新增的接口方法。
- 红黑树颜色、旋转次数和某次插入后的具体形状都不是 Java API 契约。升级 JDK 后应重新在当前源码上核对断点，而不是让业务代码反射这些字段。

下一步进入 [插入平衡、旋转与删除修复](./put-balance.md)，逐分支观察红黑树如何恢复不变量。
