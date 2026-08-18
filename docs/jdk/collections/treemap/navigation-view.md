# TreeMap：导航查询与范围视图

红黑树限制高度，二叉搜索树顺序则直接支撑 `NavigableMap`。TreeMap 不仅能判断 key 是否存在，还能回答“严格小于”“小于等于”“大于等于”和“严格大于”的最近键。

## first、last 与四个邻近方向

| API | 数学条件 | 命中 key 时是否可返回自身 |
| --- | --- | --- |
| `lowerKey(k)` / `lowerEntry(k)` | 最大的 `key < k` | 否 |
| `floorKey(k)` / `floorEntry(k)` | 最大的 `key <= k` | 是 |
| `ceilingKey(k)` / `ceilingEntry(k)` | 最小的 `key >= k` | 是 |
| `higherKey(k)` / `higherEntry(k)` | 最小的 `key > k` | 否 |

`firstKey` 从 root 开始一直走 left，`lastKey` 一直走 right。空 Map 上 `firstKey/lastKey` 抛出 `NoSuchElementException`，而 `firstEntry/lastEntry` 返回 `null`。

假设键集合为 `{10, 20, 30, 40}`：

| 查询 | lower | floor | ceiling | higher |
| ---: | ---: | ---: | ---: | ---: |
| 5 | `null` | `null` | 10 | 10 |
| 20 | 10 | 20 | 20 | 30 |
| 25 | 20 | 20 | 30 | 30 |
| 40 | 30 | 40 | 40 | `null` |
| 50 | 40 | 40 | `null` | `null` |

## getCeilingEntry 如何保留候选答案

`ceiling(k)` 要找最小的 `key >= k`。从根比较时有三种情况：

1. `k == p.key`：p 就是精确答案。
2. `k < p.key`：p 是当前候选；若有左孩子，继续向左寻找更小但仍不小于 k 的节点，否则返回 p。
3. `k > p.key`：p 太小；若有右孩子继续向右，否则沿 parent 向上，找到第一次“从左子树回到父节点”的祖先。

最后一步的祖先回退很关键。搜索可能在一个偏小叶子处结束，但最近可用答案在上层：

```text
       30
      /
    20
      \
       25   ← 查询 27 在这里发现右侧为空
```

25 小于 27，沿 parent 回到 20 时是从右侧回来，仍然太小；继续回到 30 时是从左侧回来，30 才是 ceiling。

`getHigherEntry` 与 ceiling 的唯一区别是比较相等时也必须去右侧找更大节点。`getFloorEntry/getLowerEntry` 则把方向完全镜像。

## successor 与 predecessor

有序迭代器不会从 root 为每个元素重新搜索。给定节点 t，它的后继规则是：

1. t 有右子树：后继是右子树最左节点。
2. t 没有右子树：沿 parent 上升，直到第一次从某个父节点的左孩子位置返回；该父节点就是后继。

前驱规则镜像：有左子树时取左子树最右节点，否则向上找到第一次从右孩子位置返回的父节点。

每次后继跳转可能向上多层，但完整遍历中每条树边只会被有限次经过，因此遍历 n 个节点的总成本是 O(n)，不需要 O(n log n)。

## Entry 返回值的可变性边界

JDK 8 的 `firstEntry`、`lastEntry`、`lowerEntry`、`floorEntry`、`ceilingEntry`、`higherEntry` 会导出一个不可变快照，而不是把内部树节点直接交给调用方。对返回值调用 `setValue` 会抛出 `UnsupportedOperationException`。

`pollFirstEntry` 和 `pollLastEntry` 先导出快照，再从树中删除对应节点；返回 Entry 记录删除前的键值。相对地，通过 `entrySet()` 迭代得到的 Entry 支持 `setValue`，该操作只替换 value，不改变键位置和树结构。

不要长期持有 Entry 并假设所有视图都拥有同样的可变性；调用具体 API 前应以接口契约为准。

## subMap 是范围视图，不是副本

`subMap`、`headMap` 和 `tailMap` 返回包装同一棵 TreeMap 的视图。JDK 8 的 `NavigableSubMap` 保存：

- 后备 TreeMap `m`；
- 下界 `lo`、是否从开头无界 `fromStart`、是否包含下界 `loInclusive`；
- 上界 `hi`、是否到末尾无界 `toEnd`、是否包含上界 `hiInclusive`。

例如：

```java
NavigableMap<Integer, String> map = new TreeMap<>();
map.put(10, "A");
map.put(20, "B");
map.put(30, "C");
map.put(40, "D");

NavigableMap<Integer, String> window = map.subMap(20, true, 40, false);
```

`window` 的合法键范围是 `[20, 40)`，当前看到 20 和 30：

- `window.put(25, "X")` 会写入原 map。
- `map.put(35, "Y")` 会立即出现在 window。
- `window.remove(20)` 会从原 map 删除 20。
- `window.put(40, "Z")` 越过排他上界，抛出 `IllegalArgumentException`。
- `window.clear()` 只删除范围内映射，不会清空范围外的 10 和 40。

::: tip 与 ArrayList.subList 的一个重要差异
TreeMap 范围视图会动态反映后备 Map 的合法范围内容。直接修改后备 Map 不会让视图对象永久失效；但已经创建的迭代器仍会通过 `modCount` 尽力快速失败。
:::

## 默认边界与显式边界

来自 `SortedMap` 的两个参数版本：

```java
map.subMap(fromKey, toKey)
```

固定为 `[fromKey, toKey)`，即包含下界、不包含上界。`NavigableMap` 增加四参数版本，可以分别选择两端是否包含：

```java
map.subMap(fromKey, fromInclusive, toKey, toInclusive)
```

`headMap(toKey)` 默认不包含上界，`tailMap(fromKey)` 默认包含下界；带 boolean 的重载可以显式改变。

构造双边界视图时，如果按当前比较规则 `fromKey > toKey`，立即抛出 `IllegalArgumentException`。从已有子视图继续切分时，新范围不能越过父视图边界；即使越界部分当前没有元素，也仍属于非法请求。

## NavigableSubMap 如何复用整棵树

范围视图不会建立子树副本。它把操作翻译成“绝对导航 + 边界检查”：

- 最低元素：下界包含时用 `ceiling(lo)`，不包含时用 `higher(lo)`。
- 最高元素：上界包含时用 `floor(hi)`，不包含时用 `lower(hi)`。
- 范围内 `ceiling`：先在整棵树找候选，再用 `tooHigh` 排除越过上界的结果。
- 范围内 `floor`：先在整棵树找候选，再用 `tooLow` 排除越过下界的结果。

迭代器还会计算一个范围外 fence Entry，遍历到 fence 即停止。因此范围遍历的成本通常是 O(log n + m)：先定位起点，再访问范围中的 m 个节点。

## descendingMap 如何改变方向

`descendingMap()` 仍是后备 TreeMap 的视图，但对调用者暴露反向比较顺序：

- 反向视图的 `firstKey` 是原 Map 的 `lastKey`。
- 反向视图的 `lowerKey` 对应原顺序中的 `higherKey`。
- 反向视图的范围参数必须按反向比较器理解。
- 在反向视图写入或删除，仍会修改同一个后备 Map。

`navigableKeySet()` 和 `descendingKeySet()` 也是可变视图：删除 key 会删除映射，但不能通过 Set 的 `add` 凭空构造缺失 value。

## 范围视图与快速失败

视图不复制节点，也不维护一套脱离后备 Map 的独立可变 `size`。JDK 8 的 `EntrySetView` 可以把一次范围计数连同当时的 `modCount` 缓存起来；后备树发生结构性修改后，下一次读取大小会重新统计。迭代器创建时同样记录 TreeMap 当前的 `modCount`：

- 通过当前迭代器自己的 `remove` 删除，迭代器会同步期望计数。
- 通过原 map 或另一个视图新增、删除映射，已有迭代器下一次检查时通常抛出 `ConcurrentModificationException`。
- 只替换已有 key 的 value 不是结构性修改，通常不会触发该异常。

快速失败是错误检测，不是线程安全保证，也不能依赖它检测每一次未同步并发修改。

## 复杂度与使用判断

| 需求 | TreeMap 表现 |
| --- | --- |
| 任意键查找 | O(log n) |
| 最近大于/小于键 | O(log n)，原生导航 API |
| 小范围扫描 | O(log n + m)，视图不复制 |
| 全量有序遍历 | O(n) |
| 只需要无序精确查找 | 通常 HashMap 的平均常数时间更合适 |

业务如果需要“按时间找下一条任务”“按分数取某一区间”“按版本号找不大于目标的最近配置”，TreeMap 的导航和范围视图往往比每次排序或线性过滤更直接。若比较器成本很高，它会进入每次树搜索的主路径，应避免在 `compare` 中执行 I/O、修改对象或依赖可变外部状态。

接下来按 [断点实验手册](./debug-lab.md) 验证这些公开行为和内部路径。
