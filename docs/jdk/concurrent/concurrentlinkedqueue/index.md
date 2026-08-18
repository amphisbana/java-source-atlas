# ConcurrentLinkedQueue：从链表可达性理解无锁 FIFO

`ConcurrentLinkedQueue` 是基于单向链表的无界、线程安全 FIFO 队列。它适合多个线程共享一个不需要阻塞等待的队列：`offer` 和 `poll` 在竞争时通过 CAS 重试推进，不持有互斥锁，也不维护可阻塞的“非空/非满”条件。

本专题以 OpenJDK 8u 为主基线。JDK 17/21 延续“CAS 发布节点、CAS 清空 item、允许 head/tail 滞后”的核心协议，但原子访问工具、批量删除和内部清理策略已有调整；私有字段和循环分支不能当作跨版本 API。

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `ConcurrentLinkedQueue` | [`java/util/concurrent/ConcurrentLinkedQueue.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ConcurrentLinkedQueue.java) | `offer`、`poll`、`updateHead`、`succ`、`Itr.advance` |
| `Queue` | [`java/util/Queue.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/Queue.java) | FIFO、特殊值与异常形式 |
| `Spliterator` | [`java/util/Spliterator.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/Spliterator.java) | `CONCURRENT`、`ORDERED`、`NONNULL` 特征 |

OpenJDK 源码采用 GPLv2 with Classpath Exception。本文只保留定位所需的方法签名、状态关系和伪代码，统一许可说明见[源码与许可证](/reference/source-license)。

## 先区分元素、节点和两个指针

OpenJDK 8u 的核心结构只有四类可变状态：

```text
Node.item     volatile 元素引用；CAS 为 null 表示逻辑删除
Node.next     volatile 后继引用；CAS 从 null 改为新节点表示入队
head          从这里经 succ() 能很快找到第一个存活元素
tail          从这里经 succ() 能很快找到唯一 next == null 的末节点
```

空队列创建时，`head` 和 `tail` 同时指向一个 `item == null` 的初始节点。这个节点不是永久不动的传统哨兵：随着 `poll` 推进，`head` 可以指向含有元素的节点，也可以指向已经被清空的节点。

因此不要用下面这些直觉替代真实不变量：

- `head.item` 不保证为 null；
- `tail.next` 不保证为 null，`tail` 可以暂时落后；
- 从一个已经离队的旧节点沿普通 `next` 不一定还能走回当前队列；
- 队列元素不是“head 后面的所有节点”，而是从当前 `head` 可达且 `item != null` 的节点。

## 四条核心不变量

阅读 `offer/poll` 时始终检查下面四条：

1. 链中恰有一个真正末节点满足 `next == null`，新节点只竞争这个空后继。
2. 所有仍存活的元素节点都能从当前 `head` 经 `succ()` 到达。
3. `tail` 只是加速入口；它允许滞后，更新失败不会让已经成功的入队回滚。
4. 节点的 `item` 从非 null CAS 为 null 是移除元素的线性化点，物理解链只是后续清理。

这里的“线性化点”是某次并发操作在逻辑上生效的瞬间：`offer` 成功发布 `predecessor.next` 时元素已入队；`poll` 成功清空 `node.item` 时元素已被唯一取走。`head/tail` 的辅助 CAS 可以稍后完成或失败。

## 三条主调用链

### offer 发布节点

```text
offer(e)
  -> 拒绝 null，构造尚未发布的 newNode
  -> 从 tail 附近寻找 next == null 的真正末节点 p
  -> p.casNext(null, newNode)
       成功：元素入队
       失败：其他线程先发布，继续遍历或重读 tail
  -> 必要时尝试 casTail；失败也返回 true
```

节点构造阶段的 `item` 只有在 `casNext` 发布后才能被其他线程看到。公开的内存一致性保证是：生产者在入队前的动作 happen-before 消费者访问或移除该元素后的动作。

### poll 认领元素

```text
poll()
  -> 从 head 开始跳过 item == null 的节点
  -> 对第一个存活节点执行 casItem(item, null)
       成功：当前线程唯一取得该元素
       失败：已有竞争者取走，继续扫描
  -> 必要时 updateHead 并让旧 head 自链接
```

CAS 清空 `item` 后，即使节点暂时仍留在链上，迭代器和其他读取操作也会跳过它。物理结构与逻辑元素集合因此不是同一个快照。

### 弱一致遍历

```text
iterator()
  -> Itr.advance()
  -> 从 first()/succ() 前进
  -> 跳过 null item，并尝试协助解链
  -> 把 nextItem 缓存给下一次 next()
```

迭代器不锁住队列，也不使用 `modCount` 快速失败。它可以与 `offer/poll/remove` 并发，不抛 `ConcurrentModificationException`；结果反映创建迭代器时或之后的某些状态，而不是某个全局瞬时快照。

## 无锁不等于每个线程都有完成时限

该算法是 lock-free：系统整体持续推进时，总有竞争者能完成 CAS，不会因为一个线程持锁后暂停而让所有线程永久等待该锁。但单个线程可能连续输掉 CAS 并反复重试，所以它不是 wait-free 保证。

无锁也不代表：

- 没有自旋、缓存一致性流量或调度延迟；
- 复合操作自动原子，例如 `isEmpty()` 后再 `poll()`；
- `size()` 是常量时间或精确的并发快照；
- 队列具备容量限制、背压、超时等待或公平唤醒。

需要“空时等待、满时阻塞或限时失败”时，应阅读 [BlockingQueue](../blockingqueue/)；需要明确容量时，不能用一个外部计数器和本队列拼接成未经证明的复合协议。

## size 和批量操作的边界

`size()` 从 `first()` 开始遍历并统计非 null item，时间复杂度为 O(n)。遍历期间并发增删时，返回值可能已经过时；用 `size() > 0` 决定随后 `poll()` 是否为 null 存在检查与使用之间的竞态。

`addAll` 会先构造一条私有节点链，再用一次 `casNext` 原子发布整条链；但接口并不保证 `removeAll`、`retainAll`、`toArray` 等所有批量观察与其他操作形成全局原子事务。监控可以读取近似数量，正确性协议应直接依赖单次 `offer/poll` 的返回值。

## 阅读路径

1. [offer、CAS 与滞后 tail](./offer-tail.md)：跟踪末节点定位、发布线性化点、两跳更新和自链接恢复。
2. [poll、逻辑删除与弱一致遍历](./poll-iteration.md)：理解 item CAS、head 推进、迭代缓存和 O(n) 观察。
3. [断点实验手册](./debug-lab.md)：用公开行为实验验证 FIFO、并发唯一消费、弱一致遍历与安全发布。

前置专题 [Atomic 与 Striped64](../atomic/) 解释了 CAS 的单字段语义；本专题进一步展示多个独立 CAS 如何在明确不变量下组成一条无锁链表协议。

## JDK 8、17、21 的实现边界

| 观察点 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| 原子访问 | `sun.misc.Unsafe` 字段偏移 | `VarHandle` 操作 `item/next/head/tail` |
| 入队与出队 | `casNext` 发布、`casItem` 删除，head/tail 可滞后 | 核心线性化点保持不变 |
| 链表清理 | poll、remove、iterator 在遍历中协助推进或解链 | 增加更统一的死亡节点跳过与 `bulkRemove` 批量清理路径 |
| Spliterator | `ORDERED | NONNULL | CONCURRENT`，估计大小不精确 | 公开特征保持稳定，私有批次实现可调整 |

跨版本调试应先打开当前 SDK 源码。业务代码可以依赖 FIFO、非 null、线程安全、弱一致迭代和内存一致性保证，不能依赖某次运行中的 `head/tail` 距离或旧节点自链接出现时刻。
