# WeakHashMap：弱 key、强 value 与 expunge

`WeakHashMap` 仍是分离链接哈希表。它与 `HashMap` 的核心差别不是桶算法，而是 Entry 本身继承 `WeakReference<Object>`，并把 key 的生命周期交给 GC 与 ReferenceQueue 协作。

## Entry 的真实所有权

JDK 8 的 Entry 形态可简化为：

```java
private static class Entry<K,V> extends WeakReference<Object>
        implements Map.Entry<K,V> {
    V value;
    final int hash;
    Entry<K,V> next;
}
```

所有权图是：

```text
WeakHashMap.table -> Entry -> value      // 强引用
                         ~> key          // WeakReference referent
                         -> next         // 强引用桶链
```

所以“WeakHashMap 不会保留数据”是错的。它不会通过 Entry 直接强保留普通 key，但在 Entry 被 expunge 前仍强保留 value；value 还可能间接把 key 保活。

## null key 为什么特殊

WeakHashMap 允许 null key。实现不能把真实 null 直接放进 WeakReference，否则无法区分“合法 null key”和“referent 已被 GC 清除”，所以用静态哨兵：

```text
maskNull(null)     -> NULL_KEY
unmaskNull(NULL_KEY) -> null
```

`NULL_KEY` 被类静态字段强引用，因此对应 Entry 不会因 GC 自动失效。只有显式 remove、clear 或覆盖结构变化才会删除它。

## get 的完整路径

```text
get(key)
  -> maskNull(key)
  -> hash(maskedKey.hashCode())
  -> getTable()
       -> expungeStaleEntries()
       -> return table
  -> indexFor(hash, table.length)
  -> 遍历 Entry 链
       hash 相同 && eq(maskedKey, entry.get())
  -> 返回 entry.value 或 null
```

`getTable()` 不是普通 getter。它在返回 table 前先清 ReferenceQueue，因此读操作也可能修改桶链和 size。WeakHashMap 本来就不是线程安全容器，不能把 `get` 当无副作用的并发读取。

`eq` 使用对象身份或 `equals`，所以 key 活着时仍遵循普通 Map 的等价语义。GC 清 referent 后 `entry.get()` 为 null，不会与普通非 null 查询 key 命中。

## put 为什么先清队列

`put` 同样通过 `getTable()` 先 expunge，再定位桶：

1. mask null 并计算 hash；
2. 清理已入队 stale Entry；
3. 若桶内存在等价 key，只更新 value；
4. 否则建立 `new Entry(key, value, queue, hash, table[index])`；
5. size 达阈值时 resize。

Entry 构造器把 Map 自己的 `ReferenceQueue<Object>` 传给 WeakReference。GC 后，进入队列的是这个 Entry 本身，所以 expunge 不需要从已清 referent 恢复 key。

## expungeStaleEntries 怎样安全摘链

核心循环：

```text
while ((entry = queue.poll()) != null)
  synchronized (queue)
    index = indexFor(entry.hash, table.length)
    在 bucket[index] 中按 Entry 对象身份寻找 entry
    前驱.next = entry.next 或 table[index] = entry.next
    entry.value = null
    size--
```

必须按 Entry 身份，而不是 key 相等性摘链，因为 referent 已经为 null。Entry 保存的 final hash 仍可计算原桶位置。

JDK 8 在 queue 上同步，是为了让 expunge 的桶摘链在同一个 WeakHashMap 实例的调用之间互斥；它没有把整个 Map 变成线程安全。普通 put/remove/resize 仍可能并发破坏结构。

清空 `entry.value` 很重要：即使调用方或迭代器暂时还持有 Entry，value 的强路径也能尽快断开。

## resize 为什么可能撤销扩容

WeakHashMap resize 会先把旧桶转移到新桶。转移过程中发现 `entry.get() == null` 时，会直接清 value、丢弃 Entry 并减少 size。

扩容后如果 size 已达到原 threshold 的一半，保留新表并更新 threshold；否则说明本轮 GC 清掉了很多 key，实现会把 Entry 转回旧容量的 table，避免因为一批已经死亡的键浪费双倍数组。

这是 WeakHashMap 独有的时间边界：开始 put 时看起来需要扩容，转移期间的弱键清除却可能让扩容不再值得。

## size 为什么只能是瞬时观察

`size()` 会先 expunge，因此连续两次调用可能在没有显式 remove 的情况下变小。即使一次调用返回 n，下一刻 GC 也可能清除更多 key；公开 Map 契约允许这种行为。

这不意味着 size 是“近似计数器”。单线程、固定可达性条件下，它仍返回当前表中未 expunge Entry 的数量；只是 key 的生命周期由 GC 异步改变，观察条件不稳定。

## 迭代器怎样避免当前 key 中途消失

迭代器在推进时会把下一个 key 保存到强字段 `nextKey`，返回当前项时再保存为 `currentKey`。这使 key 在 `hasNext` 与 `next` 之间保持强可达，避免刚检查非 null 就被 GC 清除。

但它仍是 fail-fast 的普通迭代器：显式结构修改会比较 `modCount`。GC 驱动的 stale expunge 不等同于调用方显式修改，应用不应依赖迭代期间看到固定快照。

## value 回指 key 的经典陷阱

```java
class Metadata {
    Object owner;
}

Object key = new Object();
Metadata value = new Metadata();
value.owner = key;
map.put(key, value);
key = null;
```

此时仍有：

```text
map -> Entry -> value -> owner(key)
```

key 强可达，Entry 的弱边根本不会生效。间接回指同样危险，例如 value 引用 listener，listener 再引用 key。

解决方案不是手动多调用几次 GC，而是改变所有权：value 只保存 key 的 ID、不可回指的数据，或也使用合适的弱引用。

## 与其他容器的选择

| 需求 | 选择 |
| --- | --- |
| 外部 key 消失后附属元数据可丢 | `WeakHashMap`，并保证 value 不回指 key |
| 并发弱键缓存、容量/过期/统计 | 使用成熟缓存库提供的 weakKeys 与淘汰策略 |
| value 也应弱持有 | 明确组合 WeakReference 与 ReferenceQueue，不能只靠 WeakHashMap |
| 确定性释放文件/连接 | `AutoCloseable` 与 try-with-resources |
| ClassLoader 元数据缓存 | 需要同时审计 key、value、Class、Method 与 loader 的引用图 |

## 版本边界

JDK 17/21 的 WeakHashMap 总体结构仍是弱 Entry + ReferenceQueue + 操作时 expunge，但 Reference API 增加 `refersTo`、`reachabilityFence` 等能力，GC 与 Reference Handler 私有协作也已重构。

跨版本稳定的是：普通 key 不被 Map 强持有、value 是强引用、null key 被支持、清理时机不确定、容器非线程安全。不要把 `pending` 字段、队列哨兵或某次 GC 后的精确 size 写成业务契约。

## 断点建议

1. `WeakHashMap.put`：观察 masked key、hash、bucket 和 queue。
2. `WeakHashMap.getTable`：确认每次访问都先 expunge。
3. `WeakHashMap.expungeStaleEntries`：观察 queue poll、index、prev、p、size。
4. `WeakHashMap.transfer`：观察 stale Entry 在 resize 中被丢弃。
5. `WeakHashMap.HashIterator.hasNext`：观察 `nextKey` 怎样形成临时强引用。
6. `ReferenceQueue.enqueue`：确认队列元素就是 WeakHashMap.Entry。

