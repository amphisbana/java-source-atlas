# LinkedHashMap：removeEldestEntry 与 LRU

`LinkedHashMap` 提供了一个受保护的策略 Hook：`removeEldestEntry`。父类在普通插入完成后调用它，子类只需回答“是否删除当前最老节点”。

它与 `accessOrder=true` 组合后，可以实现最小可用的固定条目数 LRU 映射。

## 最小实现

```java
final class FixedSizeLruMap<K, V> extends LinkedHashMap<K, V> {
    private final int maxEntries;

    /**
     * 创建按访问顺序维护的固定容量映射。
     */
    FixedSizeLruMap(int maxEntries) {
        super(maxEntries, 0.75f, true);
        this.maxEntries = maxEntries;
    }

    /**
     * 新映射加入后，如果条目数超过上限，则淘汰最久未访问项。
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxEntries;
    }
}
```

这里使用了 `LinkedHashMap` 已设计好的模板方法 Hook，不需要重写 `put`。完整可运行版本在 `LinkedHashMapDebugLab` 中，并额外校验了容量必须大于零。

## 为什么判断是大于而不是大于等于

`removeEldestEntry` 在新映射已经插入、`size` 已经增加之后被询问。

最大条目数为 3 时：

| 时刻 | 顺序 | `size` | 策略结果 |
| --- | --- | --- | --- |
| 已有三项 | `A, C, B` | 3 | 尚未调用 |
| 插入 D | `A, C, B, D` | 4 | `4 > 3`，返回 true |
| 删除 eldest | `C, B, D` | 3 | 恢复容量上限 |

如果写成 `size() >= maxEntries`，插入第三项后 `size == 3` 就会删除一项，稳定状态只能保留 2 个条目。

## afterNodeInsertion 的调用位置

JDK 8 的新增主路径可概括为：

```text
HashMap.putVal(..., evict=true)
  -> 创建并放入新节点
  -> modCount++
  -> size++
  -> 必要时 resize
  -> LinkedHashMap.afterNodeInsertion(true)
     -> first = head
     -> removeEldestEntry(first)
     -> 返回 true 时 removeNode(first.key)
```

`removeNode` 从哈希表删除节点并更新 `size/modCount`，随后回调 `afterNodeRemoval`，由后者修复顺序链的 `head` 和相邻节点。淘汰不是只从链表上隐藏节点；两套结构都会同步删除。

## eldest 到底是谁

传给策略方法的 `eldest` 就是当时的 `head`：

- 插入顺序模式下，它是最早插入且尚未删除的映射。
- 访问顺序模式下，它是最久未访问的映射，也就是 LRU 候选。

所以真正的 LRU 必须同时满足：

```text
accessOrder = true
removeEldestEntry(...) 在超限时返回 true
```

只覆盖淘汰方法但使用默认构造器，得到的是近似 FIFO 的“最早插入项淘汰”，不是最近最少使用策略。

## 哪些操作会触发淘汰判断

淘汰 Hook 面向“新增映射之后”，不是任意操作之后。

| 操作 | 是否调用淘汰判断 | 说明 |
| --- | --- | --- |
| `put` 新 key | 是 | 新节点入尾后判断 |
| `putAll` 中的新 key | 是 | 每个新映射分别进入插入流程 |
| `putIfAbsent` 新 key | 是 | 只有真正创建新节点时进入插入 Hook |
| `computeIfAbsent` 产生新映射 | 是 | 计算结果非 null 且原 key 不存在时创建节点 |
| `compute` 产生新映射 | 是 | 原 key 不存在且重映射结果非 null 时创建节点 |
| `merge` 产生新映射 | 是 | 原 key 不存在且传入 value 非 null 时创建节点 |
| `put` 已存在 key | 否 | 走访问 Hook，不是插入 Hook |
| `get` | 否 | 可能改变访问顺序，但不直接淘汰 |
| `remove` / `clear` | 否 | 容量只会减少 |

判断标准不是 API 名称里有没有 `put`，而是这次调用最终有没有创建新节点。上述复合方法若只命中或更新已有映射，就不会调用淘汰 Hook；它们还可能按访问顺序移动节点，或在重映射结果为 null 时删除节点。

构造、克隆或反序列化等内部建表路径还会通过 `evict=false` 抑制淘汰策略，避免对象尚未恢复完成时执行子类策略。不要把 `removeEldestEntry` 当作每次 Map 操作都会触发的通用回调。

## 一次完整 LRU 时间线

设容量上限为 3：

```text
put(A), put(B), put(C)  -> [A, B, C]
get(B)                  -> [A, C, B]
put(D)                  -> [A, C, B, D]
remove eldest A         -> [C, B, D]
```

此处 `B` 虽然比 `C` 更早插入，但刚刚被访问，因此移动到 `tail` 并避开本轮淘汰。

上一页的 [访问顺序动画](./access-order.md#动画-get-b-后移与下一次-lru-淘汰) 把这条时间线拆成了可单步观察的指针状态。

## 策略方法的副作用边界

推荐让 `removeEldestEntry` 只计算并返回布尔值。JDK 8 的契约允许方法自行修改映射，但要求这种情况下返回 `false`；如果一边修改映射一边返回 `true`，结果未定义。

保持策略纯粹有三个好处：

1. 真正的删除统一走 `HashMap.removeNode`，两套结构由既有 Hook 维护。
2. 避免重入修改造成 `size`、`modCount` 和链表状态难以推断。
3. 子类只描述淘汰条件，结构操作仍由父类模板流程负责。

## 容量参数不是内存字节数

示例中的 `maxEntries` 限制的是映射条目数量，不限制 key/value 对象大小，也不限制：

- HashMap 桶数组占用的容量。
- 节点、键、值及其引用对象的总内存。
- 过期时间、加载中对象或外部资源。

若需求是按权重、字节数或过期时间淘汰，需要额外策略和并发控制，而不是把条目数误当成内存上限。

## 为什么它通常不等于生产级缓存

最小 LRU 示例适合学习和简单的单线程、小规模本地场景，但缺少常见缓存能力：

| 能力 | 最小 LinkedHashMap LRU |
| --- | --- |
| 线程安全 | 不提供；命中的 `get` 也可能写链表 |
| 过期时间 | 不提供 |
| 权重淘汰 | 不提供 |
| 并发加载与防击穿 | 不提供 |
| 命中率、淘汰统计 | 不提供 |
| 异步刷新 | 不提供 |

生产系统应根据并发量、过期语义和监控要求评估成熟缓存库。即使使用 `Collections.synchronizedMap` 包装，也只是提供同步访问，不会自动补齐上述能力。

## 与 remove/clear 的关系

LRU 自动删除、主动 `remove` 和迭代器 `remove` 都会落到 `afterNodeRemoval`：

```text
p = 被删除节点
b = p.before
a = p.after

让 b 与 a 互相连接
若 b 为空则 head = a
若 a 为空则 tail = b
清空 p.before / p.after
```

`clear` 则先清空 HashMap，再直接把 `head`、`tail` 都设为 `null`。它不需要逐个维护剩余链，因为所有映射同时消失。

## 可依赖与不可依赖

| 可以依赖 | 不应该依赖 |
| --- | --- |
| 覆盖 `removeEldestEntry` 可在新增后请求删除 eldest | Hook 的精确源码行号跨版本不变 |
| 访问顺序遍历从最久未访问到最近访问 | `modCount` 的具体数值是公开 API |
| 超限判断发生在新节点加入之后 | LinkedHashMap 是并发缓存 |
| 默认实现永不自动删除 eldest | 构造器容量参数等于最大条目数策略 |

接下来按照 [断点实验手册](./debug-lab.md) 同时观察 `afterNodeInsertion` 与 `afterNodeRemoval`，确认淘汰如何穿过 HashMap 和顺序链两层结构。
