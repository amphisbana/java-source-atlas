# DelayedWorkQueue：最小堆与 leader-follower

`DelayedWorkQueue` 是 STPE 的内部阻塞队列。它与普通 `DelayQueue` 的目标相近，但专门保存 `RunnableScheduledFuture<?>`，并让内部 `ScheduledFutureTask` 记录自己的堆下标，以便取消时快速删除。

## 队列为什么逻辑无界

底层数组初始容量是 16，满后增长 50%，`remainingCapacity()` 永远返回 `Integer.MAX_VALUE`。因此 `offer`、`put` 和带超时的 `offer` 最终都直接调用同一条无界入堆路径，不会因为容量拒绝任务。

这意味着：

- `maximumPoolSize` 基本不会参与扩线程；
- 拒绝通常来自线程池已经关闭，而不是队列饱和；
- 高频创建远期定时任务仍可能造成内存压力，逻辑无界不等于资源无限。

## 堆的排序键

堆顶不是“最早提交的任务”，而是 `compareTo` 最小的任务：

```text
先比较 time（绝对 nanoTime 触发时刻）
time 相同，再比较 sequenceNumber（提交序号）
```

所以数组只保证父节点不晚于子节点，不保证数组整体有序。只能把 `queue[0]` 当成下一候选任务，不能通过遍历数组推断完整执行顺序。

### sequenceNumber 解决了什么

两个任务的纳秒触发时间可能相同。全局 `AtomicLong sequencer` 为每个 `ScheduledFutureTask` 分配递增编号，让相同 `time` 的任务按 FIFO 启用。它只打破相同触发时间的平局，不保证任务实际开始顺序：多个 worker 取得到期任务后仍受线程调度影响。

## offer 如何维护最小堆

`offer` 在 `ReentrantLock` 下完成四件事：

```text
1. 必要时扩展数组
2. size 加一，把新任务从末尾位置开始 siftUp
3. 每次移动元素时同步更新 heapIndex
4. 如果新任务成为 queue[0]：leader = null，并 signal 一个等待线程
```

`siftUp` 比较新任务与父节点；新任务更早就把父节点下移，直到找到合法位置。取出堆顶则用末尾元素填到 0，再通过 `siftDown` 与更早的子节点交换。

所有堆结构变化都在同一把锁内完成，所以 `size`、数组位置和内部任务的 `heapIndex` 保持一致。

## heapIndex 如何加速取消删除

未装饰的 `ScheduledFutureTask` 在堆中记录 `heapIndex`：

```text
remove(task)
  → 直接取得 task.heapIndex
  → 用末尾元素填补空位
  → 先 siftDown；若位置没变再 siftUp
  → 被删除任务 heapIndex = -1
```

定位是 O(1)，修复堆是 O(log n)。如果 `decorateTask` 返回其他 `RunnableScheduledFuture` 实现，队列无法读写内部下标，只能逐项比较，定位退化为 O(n)。

源码还会检查 `heapIndex` 是否在范围内且该槽确实是目标对象，防止把来自另一个线程池的任务下标误用到当前堆。

## 到期前为什么 poll 返回 null

`peek()` 可以看到未到期堆首，但无参 `poll()` 只有在堆首 `getDelay(NANOSECONDS) <= 0` 时才移除并返回。`drainTo` 同样只搬走已到期任务，不会把所有远期任务都排空。

普通 `ThreadPoolExecutor.runWorker` 通过 `getTask()` 调用队列的 `take()`；真正的定时等待因此发生在队列内部，而不是任务或 worker 外层主动 `sleep`。

## take 的 leader-follower 等待

假设有多个空闲 worker，而堆首还要等待 10 秒。若每个 worker 都执行 `awaitNanos(10s)`，时间到时会一起被唤醒并竞争同一个任务。`DelayedWorkQueue` 用一个 `leader` 字段避免这种无效定时等待：

```text
循环读取 queue[0]
├─ 队列为空
│   └─ available.await()，无限等待新任务
├─ 堆首已到期
│   └─ finishPoll(first)，返回任务
├─ 堆首未到期且已有 leader
│   └─ available.await()，当前线程作为 follower 无限等待
└─ 堆首未到期且没有 leader
    └─ 当前线程成为 leader，只等待堆首剩余 delay
```

这里的 follower 不是固定角色。线程被唤醒后重新获取锁并从循环顶部检查，可能成为新 leader，也可能再次等待。

## 新的更早任务如何打断旧等待

设堆首 A 还有 10 秒到期，W1 正作为 leader 定时等待，W2 无限等待。此时提交一个 2 秒后到期的 B：

```text
提交线程：B siftUp 到 queue[0]
        → leader = null
        → available.signal()

等待线程：某个线程被唤醒并重新检查新堆首
        → 抢到 leader 身份
        → 只按 B 的剩余 2 秒等待
```

源码不要求被唤醒的一定是原 W1，也不要求原 leader 立即结束其条件等待；`leader = null` 表示旧等待计划已经失效，获得锁的等待线程会按当前堆首重新决策。

## take 返回前为什么还要 signal

leader 的定时等待结束后会在 `finally` 中清除自己的 leader 身份。`take()` 最外层 `finally` 发现 `leader == null` 且队列仍非空时，会 `signal` 另一个线程：

- 当前线程可能正要返回已到期任务；
- 新堆首可能仍需定时等待；
- 必须让一个 follower 醒来并接任 leader，否则剩余任务可能无人按时等待。

这是 leader 交接，不是唤醒全部线程。`Condition.signal()` 配合循环检查，既减少惊群，也允许虚假唤醒和竞争后重新判定。

## 带超时 poll 的额外边界

`poll(timeout, unit)` 同时受调用者剩余超时 `nanos` 和堆首剩余 `delay` 约束：

- 调用超时小于堆首 delay，或已有 leader：最多按调用者剩余超时等待；
- 调用超时足够且没有 leader：成为 leader，按堆首 delay 等待；
- 每轮都重新计算剩余调用超时，超时耗尽就返回 `null`。

因此 `poll(1s)` 不会为了一个 10 秒后到期的任务等待 10 秒。

## 锁与条件的职责边界

| 机制 | 保护或协调的内容 |
| --- | --- |
| `ReentrantLock lock` | 数组、size、heapIndex、leader 以及堆调整 |
| `Condition available` | 队列从空变非空、出现更早堆首、leader 交接 |
| `ScheduledFutureTask` 的 Future 状态 | 任务执行、完成、异常和取消 |

队列锁不会覆盖用户任务执行。worker 从 `take()` 取出任务并解锁后，才在 `runWorker` 中调用任务；长任务不会直接阻塞其他线程入堆。

## 调试时观察什么

在 `DelayedWorkQueue.offer`、`siftUp` 和 `take` 断点观察：

- `queue[0]` 是否真是最早触发任务；
- `size` 与每个内部任务的 `heapIndex`；
- `leader` 当前指向哪个 worker；
- `delay` 是正数、零还是负数；
- 新任务成为堆首时是否把 `leader` 清空并发出一次信号。

不要在持有 `lock` 时挂起全部线程，否则调试器会人为冻结提交和其他 worker，看起来像线程池死锁。推荐只挂起当前线程。

下一步阅读 [周期、异常、取消与关闭策略](./periodic-cancel.md)。
