# ThreadLocalMap：黄金增量与开放寻址

ThreadLocalMap 是只服务于 ThreadLocal 的定制 map。它知道 key 一定是 ThreadLocal、访问者通常是 map 所属线程，因此可以舍弃通用 Map API、节点链和同步器，使用固定为 2 的幂的 Entry 数组与线性探测。

## 动画：碰撞、stale 与四种清理入口

动画固定展示 16 个槽位，与 JDK 8 的初始容量一致。前几步演示两个 key 落到同一 home slot 后如何线性探测；中间演示弱 key 被回收后 value 仍留在 stale Entry，以及 set 通过 replaceStaleEntry 更新已有 key；后半演示 get、remove 和新插入后的 cleanSomeSlots 如何触发不同范围的清理。

<ThreadLocalMapAnimation />

动画为解释探测协议而选择了可能碰撞的一组 hash 低位，不表示连续创建的几个 ThreadLocal 通常会落到同一槽。全局创建序列中的其他 ThreadLocal、低位周期重复和每个线程只使用部分 key，都可能让某一张 map 出现碰撞。

## 黄金增量如何分散连续 key

每个 ThreadLocal 构造时取得一个不可变的 `threadLocalHashCode`：

```text
hash = nextHashCode.getAndAdd(0x61c88647)
index = hash & (table.length - 1)
```

`0x61c88647` 常被称为黄金比例相关增量。它是奇数，因此对长度为 2 的幂的表，连续低位会在重复前遍历所有槽位；相比直接使用连续整数的低位，它也让相邻创建的 ThreadLocal 在表中拉开距离。

需要区分：

- nextHashCode 是 JVM 内该 ThreadLocal 类的静态全局计数器，不是每个线程一份。
- threadLocalHashCode 属于 ThreadLocal key，不是 `Object.hashCode()`，也不会根据 value 变化。
- 某个线程的 map 通常只使用全局创建序列的一部分 key，所以它看到的索引不一定是连续黄金序列。
- 相隔一个或多个低位周期的 hash 仍可能映射到同一槽，碰撞必须由开放寻址处理。

## 初始容量和阈值

ThreadLocalMap 第一次创建时：

```text
INITIAL_CAPACITY = 16
table.length = 16
threshold = 16 * 2 / 3 = 10
size = 1
```

长度始终保持为 2 的幂，索引才能使用按位与代替取模。threshold 目标是让负载因子最坏不超过约 2/3；真正准备扩容前还会先全表清 stale，只有清理后仍然足够拥挤才扩为两倍。

size 统计数组中尚未被 expunge 的 Entry，包括 key 已被 GC 清空但还占槽的 stale Entry。弱 key 变成 null 时不会有代码立即递减 size，因为没有 ReferenceQueue 或后台回调。

## getEntry 的直接命中快路径

`getEntry(key)` 先计算 home index：

```text
i = key.threadLocalHashCode & (table.length - 1)
e = table[i]
if e != null && e.get() == key:
  return e
return getEntryAfterMiss(key, i, e)
```

key 比较使用对象身份，不调用 equals。ThreadLocal 的语义本来就是“这个 ThreadLocal 实例在当前线程的绑定”，两个配置相同的 ThreadLocal 对象也是两个不同 key。

直接命中只读取一个槽，不扫描全表，也不会顺便清理远处 stale Entry。ThreadLocalMap 的清理是访问驱动且局部的，不能把一次成功 get 当成全局维护周期。

## getEntryAfterMiss 如何继续探测

从 home slot 开始，只要当前 Entry 非 null 就处于同一个连续 run：

```text
while e != null:
  k = e.get()
  if k == key:
    return e
  if k == null:
    expungeStaleEntry(i)
  else:
    i = nextIndex(i, len)
  e = table[i]
return null
```

遇到活 key 但不是目标时向后探测；遇到 stale 时从当前槽清理并重排 run。expunge 可能把后面的活 Entry 搬到当前 i，因此源码保持 i 不变并重新读取 table[i]，避免跳过刚搬回来的目标 key。

遇到 null 才能确认目标不存在。开放寻址的查找依赖“从 home 到首个 null 的连续探测链”，所以删除 Entry 时不能只把槽置 null 而不处理后续碰撞项，否则查找会过早停止。

## set 如何线性探测

`ThreadLocalMap.set(key, value)` 从 home slot 顺序处理三种情况：

1. `e.get() == key`：直接替换 value，size 不变。
2. `e.get() == null`：遇到 stale，进入 `replaceStaleEntry`。
3. Entry 是其他活 key：继续 `nextIndex`，直到首个 null。

首个 null 表示当前 run 结束，源码在该槽创建新 Entry 并递增 size。随后：

```text
if (!cleanSomeSlots(insertedIndex, size) && size >= threshold)
  rehash()
```

先做局部启发式清理；本轮没有清掉 stale 且达到阈值时，才执行全表 rehash 与可能扩容。插入成本因此会随碰撞长度和清理工作变化，不能简单认为 set 永远 O(1)。

## replaceStaleEntry 的完整决策

set 探测到的 `staleSlot` 不一定是当前 run 中最早的 stale，也不一定意味着目标 key 尚不存在。方法分三段处理。

### 一、向后找到本 run 最早 stale

从 staleSlot 前一个槽逆向扫描，直到 null。每遇到 stale 就更新 `slotToExpunge`，最终保留 run 中最靠前的 stale 位置。这样一次清理可以覆盖整段，而不是 GC 批量清 key 后反复局部重排。

### 二、向前寻找目标 key 或尾部 null

从 staleSlot 后一个槽继续线性探测：

- 找到目标 key：先更新其 value，再把目标 Entry 与 staleSlot 的 Entry 交换。
- 遇到其他 stale：若向后扫描没有找到更早 stale，记录第一个后续 stale 为清理起点。
- 遇到 null：说明目标 key 不存在。

找到已有 key 时，把活 Entry 交换到最先遇到的 staleSlot，可让它留在合法探测 run 中；原 stale 被换到目标原位置。如果没有更早 stale，清理起点改为这个被换过去的位置。之后 expunge 并继续 cleanSomeSlots。

### 三、目标不存在时复用 staleSlot

若一直扫描到 null 仍未找到 key：

```text
table[staleSlot].value = null
table[staleSlot] = new Entry(key, value)
```

这是用一个 live Entry 替换一个已经计入 size 的 stale Entry，因此 size 不增不减。若 run 中还有其他 stale，再从记录的最早位置 expunge；只有当前 staleSlot 一个失效项时，新 Entry 已经完成复用，无需额外减 size。

## 为什么 expunge 后必须重新哈希 run

假设 A、B 的 home slot 都是 5，A 在 5，B 碰撞后在 6。删除 A 后若只做 `table[5] = null`：

```text
get(B) 从 home=5 开始
  -> 看到 null
  -> 错误地认为 B 不存在
```

`expungeStaleEntry(5)` 会清掉槽 5，然后扫描到本 run 尾部 null。活的 B 根据自己的 home 重新插入，此时可以从 6 搬回 5。多个 key stale 时逐个清 value、清槽并递减 size。

这与带链表的 HashMap 删除不同：开放寻址把碰撞关系隐含在连续数组区间中，删除必须维护探测链。

## rehash 和 resize 的先后顺序

`rehash()` 先调用 `expungeStaleEntries()` 扫描全表，而不是直接扩容。清理后若：

```text
size >= threshold - threshold / 4
```

才把容量翻倍。初始表 threshold=10，因此清理后 size 至少为 8 才扩容。这个较低扩容判断用于避免刚到阈值、清掉少量 stale 后很快再次触发 rehash 的抖动。

resize 只搬运 key 仍存活的 Entry。stale Entry 的 value 会被显式置 null；活 Entry 按新掩码重新计算 home，并在线性探测到的空槽落位。

## JDK 17 和 21 的实现提示

JDK 17/21 用 `e.refersTo(key)` 和 `e.refersTo(null)` 替代对 WeakReference.get 结果的身份比较，核心查找与清理决策保持一致。JDK 21 为虚拟线程和 JDK 内部 CarrierThreadLocal 把部分公开方法拆成接受 Thread 参数的私有辅助方法，但普通 ThreadLocal 仍选择当前 Thread 对象上的 threadLocals。

调试时应以当前 SDK 源码的方法名和局部变量为准，业务代码不要通过反射读取 hash 或 Entry[] 来实现功能。
