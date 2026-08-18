# ConcurrentLinkedQueue：poll、逻辑删除与弱一致遍历

`poll` 不必先把节点从链上摘掉才返回元素。它先用 CAS 把 `item` 从非 null 改为 null，完成唯一认领；随后才尽力推进 `head`、切断旧链。这个“先逻辑删除，后物理清理”的顺序让并发取队首不需要争用一把全局锁。

## poll 的完整扫描

OpenJDK 8u 的主循环可以压缩为：

```text
restartFromHead:
  h = head
  p = h

  循环：
    item = p.item

    item != null 且 p.casItem(item, null) 成功
      -> 当前线程取得 item
      -> p != h 时尝试 updateHead
      -> 返回 item

    q = p.next 为 null
      -> 没有后继存活节点可找
      -> 尝试 updateHead(h, p)
      -> 返回 null

    p == q
      -> 命中过期自链接节点，从新 head 重启

    否则
      -> p = q，继续跳过已删除节点
```

多个消费者可以读到同一个非 null `item`，但只有一个 `casItem(item,null)` 成功。失败者重新读取链表并寻找下一个存活节点，不会把同一元素返回两次。

## casItem 是移除线性化点

考虑两个线程竞争 A：

```text
T1: A.casItem(A, null) -> true  // 逻辑上移除 A
T2: A.casItem(A, null) -> false // 不能返回 A，继续扫描
```

T1 的 CAS 成功后 A 已不属于队列，即使 `head` 还停在旧位置、A 节点仍物理可达。`size`、迭代器和其他读取通过检查 `item != null` 识别逻辑元素，因此结构中暂存 null item 节点不破坏唯一消费。

`remove(Object)` 使用同一个 item CAS 认领匹配元素，并尝试让前驱越过已删除节点。`Iterator.remove()` 在 JDK 8 中直接把最后返回节点的 volatile item 写为 null；这些入口共享“null item 表示已删除”的表示法，但具体 CAS/写入形式属于版本实现。

## head 为什么也允许滞后

`head` 的目标不是每次都精确指向第一个存活元素，而是保证从它经 `succ()` 能快速到达第一个存活节点。源码只在本轮扫描至少前进了一步时尝试 `updateHead(h,p)`，同样使用松弛的两跳更新策略。

这意味着一次 `poll(A)` 可能完成后 `head` 暂时仍指向 item 已为 null 的 A。后续 `poll/peek/first` 会跳过它并协助推进。减少 head CAS 热点的代价是读取偶尔多走一两个节点。

## updateHead 为什么让旧 head 指向自己

`updateHead(h,p)` 先 CAS `head: h -> p`，成功后执行：

```text
h.next = h
```

这个自链接有两个作用：

1. 断开旧节点对后续活跃链的普通引用，避免一个长期持有旧迭代节点的对象保留整条新链。
2. 告诉持有过期节点的并发线程“你已掉出当前链”，应通过 `succ` 或 offer 的恢复分支回到当前 head/tail。

`succ(p)` 因此不是简单返回 `p.next`：

```text
next = p.next
return p == next ? head : next
```

自链接只会出现在已经被当前 head 越过的旧节点上。把它画成当前队列中的真实环，会误解可达性不变量。

## peek 与 first 为什么不删除

`peek()` 与 `first()` 复用相似扫描，但不 CAS 清空 item：

- `peek` 返回第一个非 null item，或走到末端返回 null；
- `first` 返回第一个存活节点，供 `isEmpty/size/iterator` 等内部遍历使用；
- 两者都会在扫描中尝试推进 head，但推进指针不等于删除元素。

`peek()` 返回 A 后，另一个线程可以立即 `poll()` 取走 A。公开返回值只是调用瞬间的观察，不能先 peek 再假设随后仍能认领同一元素。

## 迭代器为什么需要 nextItem

`Itr` 同时保存 `nextNode` 和 `nextItem`。`hasNext()` 一旦告诉调用者存在下一个元素，另一个线程可能在真正调用 `next()` 前清空该节点的 item。

JDK 8 的迭代器在 `advance()` 中先把元素引用缓存到 `nextItem`：

```text
hasNext() 看到 A 并缓存 nextItem=A
并发 poll() 把节点 item 改为 null
next() 仍返回已经承诺的 A
```

这不会造成队列重复消费：迭代器本来就是观察接口，不是取走接口。缓存保证的是单个迭代器的 `hasNext/next` 协议，不会让 A 重新回到队列。

## 弱一致到底保证什么

ConcurrentLinkedQueue 的迭代器：

- 按 FIFO 方向前进；
- 不抛 `ConcurrentModificationException`；
- 可以与增删并发；
- 创建迭代器以来持续位于队列中的元素会被返回一次；
- 对创建之后加入、遍历期间删除的元素，不提供全局快照式结果。

它不会因为并发修改“失效”，也不意味着必然看见所有最新插入。若业务需要对一个固定集合做审计或结算，应在更高层建立快照或停写边界，不能把弱一致迭代当事务视图。

### 遍历也会协助清理

`Itr.advance()` 遇到 null item 节点时会继续前进；在有前驱和后继时，还会尝试 `pred.casNext(p,next)` 越过已删除节点。CAS 失败无须重试到成功，因为其他线程可能已经改变链接，迭代器仍可以按 `succ` 继续。

JDK 8 的 `remove(Object)` 使用类似的惰性解链。较新 JDK 把多个遍历和批量删除路径的清理逻辑进一步统一，但业务行为仍是弱一致，不应测试某个已删除 Node 在第几次遍历后物理消失。

## size 为什么不是并发计数器

`size()` 从 `first()` 开始遍历，逐个统计当前读取到的非 null item，超过 `Integer.MAX_VALUE` 时饱和返回。它没有维护全局原子计数，因此：

- 时间复杂度为 O(n)；
- 遍历期间并发 offer/poll 时，结果可能对应不到任意单一时刻；
- 紧接着的 `isEmpty/poll` 仍可能与其他线程竞争；
- 高频调用 size 会额外扫描链表并制造缓存读取压力。

在没有并发修改的静默期，`size` 可以得到确定数量；在活跃系统中更适合做近似监控，不适合流控或余额式精确判断。

## Spliterator 与 Stream 边界

JDK 8 的 `CLQSpliterator` 报告：

```text
ORDERED | NONNULL | CONCURRENT
```

它不报告 `SIZED/SUBSIZED`，`estimateSize()` 返回 `Long.MAX_VALUE`，拆分时按逐步增大的批次复制当前遍历到的元素。并行流可以处理这个来源，但无法像数组那样提前得到精确均匀分区；并发修改仍遵循弱一致观察。

更完整的拆分、短路与并行任务链见 [Stream 与 Spliterator](/jdk/functional/stream/)。不要因为调用 `parallelStream()` 就假设链式并发队列会自动获得理想负载均衡。

## 自动测试应该断言什么

稳定的公开断言包括：

- 静默期 offer 后按 FIFO poll；
- 多个消费者不会成功取得同一个节点 item 两次；
- null 插入被拒绝；
- 并发遍历不快速失败；
- 入队前写入的消息字段对成功取到该消息的线程可见。

不稳定断言包括：

- 某次 offer 后 tail 必须立刻指向新节点；
- poll 后旧节点必须立刻不可达；
- 活跃并发下 size 必须等于某个外部瞬时计数；
- 迭代器一定看见或一定看不见创建后的新元素；
- 某个生产者在 CAS 竞争中必须先完成。

## 推荐断点

1. `poll()` 的 `p.casItem(item,null)`：确认唯一消费线性化点。
2. `updateHead(h,p)`：观察 head CAS 成功与旧 head 自链接。
3. `succ(p)`：制造持有旧节点的遍历并命中 `p == next`。
4. `Itr.advance()`：观察 `nextNode/nextItem/lastRet` 和 null 节点跳过。
5. `remove(Object)`：区分 item CAS 成功与前驱解链 CAS 失败。
6. `size()`：在静默期和并发期比较遍历到的 item。
