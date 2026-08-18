# 查询、删除与遍历

## get 与 getNode

JDK 8 的读取主流程是：

```text
get(key)
  └─ getNode(hash(key), key)
       ├─ 计算桶下标
       ├─ 检查桶首节点
       ├─ TreeNode.getTreeNode(...)   树桶
       └─ 沿 next 遍历               链表桶
```

桶首节点单独检查是一条常见的快速路径。若未命中，才区分树和链表。

## null 值带来的判断陷阱

`get(key)` 返回 `null` 有两种可能：键不存在，或者键存在且映射值就是 `null`。

```java
if (map.get(key) == null) {
    // 无法单独证明 key 不存在
}
```

需要区分时使用 `containsKey(key)`。它同样通过节点查找判断键是否存在，而不是依赖返回值。

## remove 的四个关键参数

公开的 `remove` 最终进入 `removeNode`，内部参数控制：

- 目标哈希与键；
- 是否还要求值相等；
- 删除后是否执行 `LinkedHashMap` 扩展钩子。

删除流程先定位桶，再区分桶首、链表节点和树节点。找到目标后修改数组或节点链接，随后减少 `size`、增加 `modCount`，并调用 `afterNodeRemoval`。

`remove(key, value)` 只有键和值都匹配才删除；这是复合条件 API，不等价于先 `get` 再 `remove` 的并发语义。

## keySet、values 和 entrySet 是视图

这些集合不是数据副本，而是 HashMap 的动态视图：

- 通过视图删除元素会修改原 Map；
- Map 的后续修改会反映到视图；
- `keySet` 和 `entrySet` 不支持新增任意元素；
- `Map.Entry#setValue` 可以更新当前映射的值。

视图对象会被惰性创建并缓存，但视图中的内容始终来自当前 Map。

## 迭代顺序与成本

迭代器按桶数组从低到高扫描，并沿每个桶的节点链移动。当前观察到的顺序会受容量、哈希、扩容和版本实现影响，不能作为业务顺序使用。

迭代成本与 `capacity + size` 成正比。仅仅把初始容量设得极大，即使元素很少，也可能拖慢完整遍历。

需要稳定插入顺序或访问顺序时使用 `LinkedHashMap`；需要按键排序时使用 `TreeMap`。

## fail-fast 的真实含义

迭代器创建时保存 `expectedModCount`。迭代过程中若发现它和 Map 的 `modCount` 不一致，会抛出 `ConcurrentModificationException`。

需要注意：

- 这是尽力而为的错误检测，不是线程安全机制。
- 覆盖已有键的值通常不属于结构性修改，可能不会触发检查。
- 使用迭代器自身的 `remove()` 会同步期望计数，是支持的删除方式。
- 不应编写依赖该异常来保证业务正确性的代码。

## clone 与序列化边界

`clone()` 会创建独立的内部桶结构，但键和值对象仍然是浅拷贝引用。序列化只保存有效映射，不依赖把当前桶数组原样写出；反序列化会根据映射数量和负载因子重建容量并重新插入。

因此不应把内部容量、桶形态或遍历顺序当成持久化协议的一部分。

