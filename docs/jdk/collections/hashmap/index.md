# HashMap 源码解析

`HashMap` 是一个基于哈希表的 `Map` 实现。它允许一个 `null` 键和多个 `null` 值，不保证遍历顺序，也不提供线程安全保证。

本专题以 OpenJDK 8 为主要分析基线，因为这一版本引入了链表树化和新的扩容拆分方式。JDK 17/21 的主体结构仍然延续这套设计，差异集中在局部实现、辅助 API 和平台内部接口。

<HashMapStructure />

## 从一次 put 看全局

```text
HashMap.put(key, value)
  └─ hash(key)                         计算扰动后的 hash
  └─ putVal(hash, key, value, false, true)
       ├─ resize()                     首次分配或容量扩张
       ├─ newNode(...)                 空桶直接创建节点
       ├─ TreeNode.putTreeVal(...)     红黑树桶插入
       ├─ treeifyBin(...)              长链表尝试树化
       └─ afterNodeInsertion(...)      给 LinkedHashMap 的扩展钩子
```

`HashMap` 的主要复杂度都集中在三个问题：

1. 如何把一个任意 `hashCode` 均匀映射到有限的数组下标。
2. 多个键进入同一个桶时，如何保证查找性能和键语义正确。
3. 容量不够时，如何用尽量低的成本迁移已有节点。

## 公开契约与实现细节

| 分类 | 可以依赖 | 不应该依赖 |
| --- | --- | --- |
| 键值行为 | 一个 `null` 键；键相等遵循 `equals` | `null` 键一定存放在数组第 0 项，这是实现细节 |
| 性能 | 哈希分布合理时，`get`/`put` 预期为常数时间 | 每次操作一定是 O(1) |
| 遍历 | 可以遍历所有映射 | 插入顺序、哈希顺序或跨版本稳定顺序 |
| 并发 | 外部同步后可以使用 | 多线程并发写入安全、`fail-fast` 能替代同步 |
| 容量 | 构造参数表达预期容量 | 构造后立刻分配数组、内部容量等于传入值 |

## 关键不变量

- `table` 的长度是 2 的幂，最大为 `1 << 30`。
- 普通容量下，扩容阈值通常为 `capacity × loadFactor`。
- 桶中元素可能是单节点、链表或红黑树；数组元素类型统一为 `Node`。
- 结构性修改会增加 `modCount`，覆盖已有键的值通常不会。
- 键放入后，其 `hashCode` 和参与 `equals` 的字段应保持稳定。

## 阅读地图

1. [数据结构与关键字段](./data-structure.md)
2. [`put` 写入流程](./put.md)
3. [`resize` 扩容机制](./resize.md)
4. [链表树化](./treeify.md)
5. [查询、删除与遍历](./read-remove.md)
6. [断点实验手册](./debug-lab.md)
7. [版本差异与边界](./version-diff.md)

::: tip 阅读原则
文中的伪代码用于突出控制流，不替代 OpenJDK 源文件。确认细节时，以对应版本的 `java.util.HashMap` 和测试结果为准。
:::
