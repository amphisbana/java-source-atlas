# LinkedHashMap：一份数据，两套连接关系

`LinkedHashMap` 在 `HashMap` 的哈希表能力之上，为全部映射维护了一条双向链表。因此它既能按键接近常数时间查找，也能提供稳定的遍历顺序。

本专题以 OpenJDK 8u 为主基线。阅读前建议先掌握 [HashMap 的数据结构](../hashmap/data-structure.md) 和 [`putVal` 主流程](../hashmap/put.md)，因为 `LinkedHashMap` 没有重写整套哈希算法，而是通过 `HashMap` 预留的节点工厂和回调 Hook 接入。

## 先建立正确的结构模型

同一个映射节点同时位于两套结构中：

```text
哈希表维度（用于按 key 查找）
table[i] -> Entry A --next--> Entry C
table[j] -> Entry B

顺序维度（用于遍历）
head -> Entry A <==> Entry B <==> Entry C <- tail
          before / after
```

这里不是“HashMap 保存一份、双向链表再复制一份”。`Entry A` 在两个视图中是同一个对象：

- 继承自 `HashMap.Node` 的 `next` 只连接同一个桶中的后继节点。
- `LinkedHashMap.Entry` 新增的 `before`、`after` 连接全局顺序中的相邻节点。
- 扩容会改变桶位置和部分 `next` 关系，但不应改变 `before/after` 定义的遍历顺序。

把两套指针混在一起，是阅读这个类时最常见的误区。

## 类型关系

JDK 8 的核心关系可以压缩成下面几行：

```text
HashMap<K,V>
  └─ LinkedHashMap<K,V>

HashMap.Node<K,V>
  └─ LinkedHashMap.Entry<K,V>       增加 before / after
       └─ HashMap.TreeNode<K,V>     再增加 parent / left / right / prev / red
```

最后一层看起来有些反直觉：`TreeNode` 定义在 `HashMap` 中，却继承 `LinkedHashMap.Entry`。这样树桶节点也天然拥有 `before/after`，同一个节点才能同时参加红黑树和全局顺序链表。

用最小签名表示普通节点：

```java
static class Entry<K, V> extends HashMap.Node<K, V> {
    Entry<K, V> before;
    Entry<K, V> after;
}
```

这只是结构摘要，不是对 OpenJDK 源码的复制。

## 三个本类字段

| 字段 | 含义 | 空映射时 |
| --- | --- | --- |
| `head` | 顺序链表中的最老节点，也是迭代起点 | `null` |
| `tail` | 顺序链表中的最新节点 | `null` |
| `accessOrder` | `false` 为插入顺序，`true` 为访问顺序 | 构造后不再改变 |

`table`、`size`、`threshold`、`loadFactor`、`modCount` 等字段仍由 `HashMap` 提供。

“最老”和“最新”要结合顺序模式理解：

- 插入顺序模式：`head` 是最早插入且仍存在的映射，`tail` 是最后插入的映射。
- 访问顺序模式：`head` 是最久未访问的映射，`tail` 是最近访问的映射。

## 双向链表不变量

每次节点新增、访问移动、删除和树/链表节点替换之后，都应恢复以下不变量：

1. 空映射满足 `head == null && tail == null`。
2. 非空映射满足 `head.before == null`、`tail.after == null`。
3. 若 `x.after == y`，则 `y.before == x`；反向关系同样成立。
4. 从 `head` 不断沿 `after` 前进，恰好访问 `size` 个节点并最终到达 `null`。
5. 顺序链表中的每个节点，也必须能从哈希表的某个桶中找到；反之亦然。

这些不变量比记忆单行赋值更重要。调试 `afterNodeAccess` 或 `afterNodeRemoval` 时，可以在每次指针变化后用它们检查链表是否断裂。

## LinkedHashMap 如何接入 HashMap

`HashMap` 在通用算法里预留了两类扩展点：

| 扩展点 | HashMap 中的默认行为 | LinkedHashMap 的职责 |
| --- | --- | --- |
| `newNode` / `newTreeNode` | 创建哈希节点 | 创建后连接到顺序链表尾部 |
| `replacementNode` / `replacementTreeNode` | 更换普通/树节点表示 | 更换节点的同时转移前后链接 |
| `afterNodeAccess` | 空实现 | 访问顺序模式下移到尾部 |
| `afterNodeInsertion` | 空实现 | 插入后询问是否淘汰最老节点 |
| `afterNodeRemoval` | 空实现 | 从顺序链表中摘除已删除节点 |

这是一种模板方法式的扩展：`HashMap` 控制查找、碰撞、扩容和删除的主流程，子类只实现特定步骤。它避免 `LinkedHashMap` 复制一份容易漂移的 `putVal` 或 `removeNode`。

### 新节点：newNode

普通空桶或链表新增映射时，`HashMap.putVal` 会通过动态分派调用 `LinkedHashMap.newNode`。该工厂完成两件事：

```text
创建 LinkedHashMap.Entry
linkNodeLast(entry)
```

`linkNodeLast` 保存旧 `tail`，让新节点成为新 `tail`；如果此前为空，同时让 `head` 指向它。这个过程只维护顺序链，桶中的 `next` 由 `HashMap` 主流程处理。

### 节点换壳：replacementNode

树化和退化可能需要把普通节点替换成树节点，或者把树节点替换回普通节点。不能只创建新对象，否则顺序链上的相邻节点仍会指向旧对象。

`replacementNode` 与 `replacementTreeNode` 都会调用等价于下面的步骤：

```text
dst.before = src.before
dst.after  = src.after
让前驱.after 和后继.before 改为指向 dst
必要时把 head 或 tail 改为 dst
```

这个动作只替换节点身份，不改变它在遍历顺序中的位置。

### 为什么 resize 不需要重建顺序链

JDK 8 的 `HashMap.resize` 迁移的是已有节点，主要重组 `table` 和桶内 `next`。节点对象的 `before/after` 不参与桶拆分，因此扩容前后遍历顺序保持不变。

树化或退化会创建替代节点，才需要 `replacementTreeNode` / `replacementNode` 转移链接。

## 三条主要调用链

### 新增映射

`LinkedHashMap` 没有重写公开的 `put`；下面第一行表示“对 LinkedHashMap 实例调用继承自 HashMap 的方法”。真正发生差异的是节点工厂和插入后 Hook 的动态分派。

```text
LinkedHashMap.put(key, value)
  -> HashMap.putVal(..., evict=true)
     -> LinkedHashMap.newNode(...) 或 newTreeNode(...)
        -> linkNodeLast(...)
     -> HashMap 更新 size / modCount，必要时 resize
     -> LinkedHashMap.afterNodeInsertion(true)
        -> removeEldestEntry(head)
```

### 命中已有映射

```text
HashMap.putVal(...) 找到已有节点
  -> 更新 value（是否更新取决于调用参数）
  -> LinkedHashMap.afterNodeAccess(node)

LinkedHashMap.get(key)
  -> HashMap.getNode(...)
  -> accessOrder 为 true 时 afterNodeAccess(node)
```

插入顺序模式下 Hook 会直接结束；访问顺序模式下，非尾节点会被移到 `tail`。

### 删除映射

```text
HashMap.removeNode(...)
  -> 从桶链或树中删除
  -> size-- / modCount++
  -> LinkedHashMap.afterNodeRemoval(node)
     -> 修复 before / after / head / tail
```

无论删除来自公开 `remove`、迭代器 `remove`，还是 LRU 淘汰，最终都会走同一个移除 Hook。

## 顺序模式对照

假设依次写入 `A、B、C`，再执行 `get(A)` 和 `put(B, newValue)`：

| 模式 | `get(A)` 后 | 再次 `put(B, ...)` 后 |
| --- | --- | --- |
| 插入顺序 `accessOrder=false` | `A, B, C` | `A, B, C` |
| 访问顺序 `accessOrder=true` | `B, C, A` | `C, A, B` |

“再次放入已有 key 不改变插入顺序”是公开契约；在访问顺序模式下，成功命中已有映射则属于一次访问，会移动位置。

## 遍历为什么只与 size 成正比

`LinkedHashIterator` 从 `head` 开始，每次令 `next = current.after`，不需要扫描 `table` 的空桶。因此完整遍历的时间与 `size` 成正比，而不是与哈希表容量成正比。

代价是每个节点多出两条引用，并且插入、删除、部分读取操作需要维护链表。`LinkedHashMap` 仍然不是线程安全容器。

## 版本边界

| 版本 | 阅读时应注意 |
| --- | --- |
| JDK 7 及更早 | 内部曾使用头结点式结构；不要把旧文章中的 `header` 字段套到 JDK 8 |
| JDK 8 | HashMap 引入树桶后，形成本文分析的 `Entry`、`head/tail` 和 Hook 结构 |
| JDK 17 | 核心双链与 Hook 思路基本不变，部分 HashMap 私有方法签名和辅助实现有调整 |
| JDK 21 | 集合顺序 API 引入 `SequencedMap`，可使用首尾及反向视图等新能力；本文的源码行号和 API 清单仍以 JDK 8 为准 |

## 学习路径

1. 本页先区分桶链与顺序链，并理解节点工厂 Hook。
2. 阅读 [访问顺序与 afterNodeAccess](./access-order.md)，逐步观察一次 `get` 如何移动节点。
3. 阅读 [removeEldestEntry 与 LRU](./lru.md)，理解插入后的淘汰时机。
4. 按 [断点实验手册](./debug-lab.md) 运行公开 API 案例，再进入本机 JDK 源码单步调试。

::: tip 源码阅读边界
文中的代码块只保留方法签名或控制流骨架。具体赋值顺序、异常行为和版本差异应以对应 OpenJDK 标签的 `java.util.LinkedHashMap` 为准。
:::
