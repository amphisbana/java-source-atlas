# ArrayBlockingQueue 与 LinkedBlockingQueue：单锁环形数组和双锁链表

两个类都提供 FIFO、有界等待队列，但并发控制的结构不同：`ArrayBlockingQueue` 用一把 `ReentrantLock` 保护数组、索引和普通 `int count`；`LinkedBlockingQueue` 用 `putLock`、`takeLock` 分开队尾写入与队首移除，再用 `AtomicInteger count` 连接两边。

## ArrayBlockingQueue 的七个关键字段

OpenJDK 8u 的四个队列状态字段与三个同步字段可以压缩为：

```text
Object[] items       固定长度数组
int takeIndex        下一次取出位置
int putIndex         下一次插入位置
int count            当前元素数
ReentrantLock lock   保护以上全部状态
Condition notEmpty   消费者等待“从空变为非空”
Condition notFull    生产者等待“从满变为非满”
```

`takeIndex == putIndex` 既可能表示空，也可能表示满，必须结合 `count` 判断。三个基本不变量是：

1. `0 <= count <= items.length`；
2. `takeIndex` 指向逻辑队首，`putIndex` 指向下一次写入槽；
3. 每次索引走到数组末端后回到 0，元素的 FIFO 顺序是从 `takeIndex` 开始绕环读取。

假设容量为 4，当前数组和索引是：

```text
物理槽位       0     1     2     3
items        [ D ] [ - ] [ B ] [ C ]
                         ^           ^
                    takeIndex=2  putIndex=1

逻辑顺序：B → C → D，count=3
```

插入会把新元素写到槽 1，再让 `putIndex` 变为 2；取出会清空槽 2，再让 `takeIndex` 变为 3。数组不搬移，只有从中间 `remove(Object)` 时才需要移动环上的后续元素并更新迭代器跟踪状态。

## ArrayBlockingQueue.put 的等待路径

OpenJDK 8u 的调用链是：

```text
put(e)
  ├─ checkNotNull(e)
  ├─ lock.lockInterruptibly()
  ├─ while (count == items.length)
  │    └─ notFull.await()
  └─ enqueue(e)
       ├─ items[putIndex] = e
       ├─ putIndex 环形前进
       ├─ count++
       └─ notEmpty.signal()
```

必须使用 `while` 而不是 `if`：线程可能虚假唤醒，也可能在被通知后等待重新获取锁期间，空间先被其他生产者占用。`lockInterruptibly()` 表明中断既可能发生在获取主锁时，也可能发生在条件等待时。

`offer(e)` 使用普通 `lock()`，拿到锁后发现满就立即返回 false；定时 `offer` 使用 `awaitNanos` 反复扣减剩余时间，时间耗尽返回 false。四组 API 共享相同数组不变量，差别主要在失败策略。

## ArrayBlockingQueue.take 的释放路径

```text
take()
  ├─ lock.lockInterruptibly()
  ├─ while (count == 0)
  │    └─ notEmpty.await()
  └─ dequeue()
       ├─ x = items[takeIndex]
       ├─ items[takeIndex] = null
       ├─ takeIndex 环形前进
       ├─ count--
       ├─ 更新活跃迭代器状态
       └─ notFull.signal()
```

取出后清空数组槽位不是装饰，它让队列不再强引用已移除对象。`dequeue` 调用 `notFull.signal()` 时，消费者仍持有主锁；被通知生产者只是从 Condition 条件队列转入 AQS 同步队列，必须等消费者解锁后重新竞争。

## 动画：满队列中的 put 如何恢复

动画固定容量为 2：队列先放入 A、B，生产者 P 尝试 `put(C)`，消费者随后 `take()`。重点观察 P 从 `notFull` 条件队列转入 AQS 同步队列之后，仍然没有获得 `lock`。

<BlockingQueueAnimation />

### signal 到 put 返回之间还有什么

| 阶段 | 主锁 owner | P 所在位置 | P 能否写入 C |
| --- | --- | --- | --- |
| `notFull.await()` | 完整释放 | Condition 队列 | 否，正在等待 |
| 消费者 `dequeue()` | C | Condition 队列 | 否 |
| `notFull.signal()` | C | 转入 AQS 同步队列 | 否，signal 不转移所有权 |
| 消费者 `unlock()` | 暂时为空 | AQS 中重新竞争 | 仍未必，非公平锁允许其他线程竞争 |
| P 获得锁并重新检查 while | P | 已离开等待队列 | `count < capacity` 才能写入 |

这也是为什么测试不能把“消费者完成一次 take”直接等价为“被阻塞生产者已经完成 put”。两者之间还存在调度和锁竞争。

## 公平参数约束的是什么

`new ArrayBlockingQueue(capacity, fair)` 把 `fair` 原样传给内部 `ReentrantLock`。公平模式尽量让等待主锁时间更长的线程先获得访问机会，可以降低饥饿与延迟波动，但通常牺牲吞吐。

它不保证：

- 生产者与消费者按一个全局提交顺序完成；
- 被 signal 的线程立刻运行；
- 操作系统绝对按 FIFO 调度线程；
- `offer` 一定排在更早调用 `put` 的线程之后完成。

公平性属于锁竞争策略，不改变队列元素自身的 FIFO 顺序。

## LinkedBlockingQueue 的链表与哨兵

OpenJDK 8u 的主要状态是：

```text
final int capacity
AtomicInteger count

head → Node(null) → first data node → ... → last

putLock  + notFull   保护队尾链接与生产者等待
takeLock + notEmpty  保护队首移除与消费者等待
```

`head` 始终是 item 为 null 的哨兵。`enqueue` 执行 `last = last.next = node`；`dequeue` 让旧 head 自链接以帮助迭代器和 GC 处理可达链，把首个数据节点变成新哨兵，并取出它原来的 item。

生产者只持 `putLock` 修改 `last`，消费者只持 `takeLock` 修改 `head`。队列既非空又非满时，两者可以在不同端并行。相较之下，ArrayBlockingQueue 的 put 和 take 都必须持有同一把锁。

## AtomicInteger count 为什么是双锁桥梁

两把锁分别保护两个端点，普通 `int` 无法同时在两个独立临界区中安全读改写。`AtomicInteger count` 有三项职责：

1. 让 put 与 take 对同一个数量执行原子增减；
2. 让两端判断空、满边界时看到已发布的数量；
3. 配合锁与原子访问，建立从已链接节点到消费端读取的可见性链。

它不让整个链表成为无锁结构。节点链接仍分别依赖 `putLock` 或 `takeLock`，而 `remove`、`clear`、`toArray` 等需要稳定遍历两端的操作会按固定顺序执行 `fullyLock()`，同时取得 putLock 和 takeLock。

## LinkedBlockingQueue.put 与跨锁 signal

`put(e)` 的关键局部变量 `c` 保存递增前的数量：

```text
putLock.lockInterruptibly()
while (count == capacity): notFull.await()
enqueue(node)
c = count.getAndIncrement()
if (c + 1 < capacity): notFull.signal()   // 同侧级联生产者
putLock.unlock()

if (c == 0): signalNotEmpty()             // 0 → 1，跨到 takeLock 通知消费者
```

只有 `0 → 1` 的生产会跨锁调用 `signalNotEmpty()`：该辅助方法取得 `takeLock` 后执行 `notEmpty.signal()`。如果递增后仍有空间，则当前生产者在持有 putLock 时 signal 另一个生产者，形成级联通知。

`take()` 完全对称：

```text
takeLock.lockInterruptibly()
while (count == 0): notEmpty.await()
x = dequeue()
c = count.getAndDecrement()
if (c > 1): notEmpty.signal()             // 同侧级联消费者
takeLock.unlock()

if (c == capacity): signalNotFull()        // 满 → 非满，跨到 putLock 通知生产者
```

跨锁 signal 放在原临界区释放之后，避免同时不必要地持有两把锁。signal 仍只负责条件节点转移；等待者必须重新获得对应锁并在 while 中复查 `count`。

## 容量与空间成本对照

| 观察点 | `ArrayBlockingQueue` | `LinkedBlockingQueue` |
| --- | --- | --- |
| 存储 | 构造时一次分配固定 `Object[]` | 每个入队元素分配 `Node` |
| 数量 | 主锁下普通 `int count` | 跨双锁共享的 `AtomicInteger count` |
| 锁 | 一把可配置公平性的锁 | 固定两把非公平 `ReentrantLock` |
| 条件 | 同一锁创建 `notEmpty/notFull` | 两把锁各自创建一个 Condition |
| put/take 并行 | 队列状态修改不能并行 | 非边界场景可以分别修改两端 |
| 默认容量 | 必须显式传入 | 无参构造为 `Integer.MAX_VALUE` |
| 内存形态 | 容量成本预付，空槽仍占引用位 | 成本随元素数增加，节点有额外字段 |

双锁不保证 LinkedBlockingQueue 在所有负载下都更快。元素分配、GC、竞争比例、缓存局部性、容量和批量操作都会影响结果，应使用贴近业务的基准测试。

## JDK 17/21 的实现边界

三个版本都保留上述锁与条件结构。较新源码使用 `Objects.requireNonNull`、提取环形索引辅助方法，并持续调整迭代器、清理与内部注释；这些差异不改变四组 API 或单锁/双锁模型。

不要把 JDK 8 的私有字段可见性、局部变量名或迭代器内部类布局当成跨版本契约。跨版本调试时先打开当前 SDK 源码，优先在稳定的公开入口和 `enqueue/dequeue`、`signalNotEmpty/signalNotFull` 语义位置下断点。
