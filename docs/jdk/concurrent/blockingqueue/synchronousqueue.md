# SynchronousQueue：零容量双向配对

`SynchronousQueue` 没有保存元素供未来消费的内部容量。一次插入只有与一次移除成功配对才算成功，因此它更像线程间的交接点，而不是装有零个或一个元素的普通容器。

## 零容量的公开表现

无论是否有线程正在等待交接，集合视角都保持：

| 方法 | 结果 |
| --- | --- |
| `size()` | 始终为 0 |
| `isEmpty()` | 始终为 true |
| `remainingCapacity()` | 始终为 0 |
| `peek()` | 始终为 null |
| `contains(x)` | 始终为 false |
| `iterator()` | 空迭代器 |

内部当然可能存在等待生产者或消费者节点，但这些节点表示尚未完成的操作，不是队列已经拥有的元素。不能用 `size()` 或 `peek()` 判断是否有另一方正在等待。

立即方法的语义也由“能否当场匹配”决定：

- `offer(e)` 只有已经存在可匹配的消费者时才可能返回 true，否则立即 false；
- `poll()` 只有已经存在可匹配的生产者时才可能取得元素，否则立即 null；
- `put(e)` 等待消费者匹配；
- `take()` 等待生产者匹配；
- 定时 `offer/poll` 在匹配或截止时间之间竞争。

## 所有操作汇入 transfer

OpenJDK 8u 的公开入口最终都委托给内部 `Transferer.transfer(e, timed, nanos)`：

```text
生产操作：e != null  → DATA
消费操作：e == null  → REQUEST

put(e)              → transfer(e, false, 0)
offer(e)            → transfer(e, true, 0)
offer(e, timeout)   → transfer(e, true, nanos)
take()              → transfer(null, false, 0)
poll()              → transfer(null, true, 0)
poll(timeout)       → transfer(null, true, nanos)
```

`timed=true,nanos=0` 表示不能等待，并非开启一个后台交接。成功时 transfer 返回已交接元素；失败时返回 null，公开入口再根据中断状态区分超时/立即失败和 `InterruptedException`。

构造参数决定具体传输器：

```text
new SynchronousQueue(false)  默认 → TransferStack → LIFO 等待顺序
new SynchronousQueue(true)        → TransferQueue → FIFO 等待顺序
```

公平策略约束的是等待节点的匹配倾向，不创建存储容量，也不保证被匹配线程先于所有其他线程获得 CPU。

## DATA 与 REQUEST 为什么必须配对

双重数据结构在任一稳定时刻只包含一种尚未满足的模式：DATA、REQUEST，或为空。

```text
已有 DATA 节点 + 新 DATA 操作       → 同模式，追加/压栈并等待
已有 REQUEST 节点 + 新 REQUEST 操作 → 同模式，追加/压栈并等待
已有 DATA 节点 + 新 REQUEST 操作    → 互补，匹配并交付 item
已有 REQUEST 节点 + 新 DATA 操作    → 互补，匹配并交付 item
```

如果两个方向可以不配对就同时留下，结构便会退化为普通缓冲队列，也会破坏 `offer`、`size` 与 `remainingCapacity` 的公开契约。

## 非公平 TransferStack 的三种分支

JDK 8 默认使用 `TransferStack`。`SNode.mode` 包含：

| 模式 | 含义 |
| --- | --- |
| `REQUEST = 0` | 等待数据的消费者 |
| `DATA = 1` | 等待消费者的生产者 |
| `FULFILLING = 2` | 正在占位并帮助完成互补匹配的节点标记 |

`transfer` 的循环可以压缩为三类动作：

1. 栈为空或栈顶与本次同模式：把自己的节点 CAS 到 head，等待 `match` 被填入；立即/超时失败则取消并清理。
2. 栈顶是互补模式且不是 fulfilling：先压入带 `FULFILLING` 位的节点占住匹配位置，再把自己与下一个节点配对并一起弹出。
3. 栈顶已经是其他线程的 fulfilling 节点：当前线程协助完成匹配或弹栈，避免占位线程暂停时阻塞全局进展。

等待节点通过 `match == this` 表示取消。成功匹配会 CAS 设置 `match` 并 `unpark` 等待线程；`unpark` 只是允许它继续检查匹配结果，不代表线程已经从公开方法返回。

LIFO 让最近阻塞的等待者更可能先配对，通常有较好的时间局部性，但较早节点可能等待更久。它适合缓存线程池一类资源交接场景，并不适合要求稳定先来先服务的消息传递。

## 公平 TransferQueue 如何前进

公平模式使用带哨兵 head 的 `TransferQueue`：

```text
head(dummy) → QNode → QNode → ... → tail
                                  cleanMe（必要时记录待清理前驱）
```

每个 `QNode` 用 `isData` 明确节点类型，`item` 同时承担数据和匹配状态：生产节点以非 null item 开始，消费者节点以 null 开始；互补操作 CAS 改变 item 完成配对。

循环同样有两条主路径：

1. 队列为空或尾部与本次同模式：CAS 追加节点，推进可能滞后的 tail，再等待匹配。
2. head 后继是互补节点：CAS 修改其 item 完成匹配，推进 head，并 unpark 该节点的等待线程。

tail 允许短暂落后，其他线程会协助推进；旧 head 会通过自链接忘记后续链，减少阻塞线程长期持有历史节点导致的对象滞留。FIFO 让较早入队的有效等待者更可能先匹配，但取消节点、并发帮助和线程调度意味着不能用最终业务完成顺序证明绝对公平。

## 超时、中断与取消竞态

等待中的传输需要同时处理三件事：互补线程可能完成匹配、截止时间可能耗尽、当前线程可能被中断。源码用 CAS 让“匹配”和“取消”竞争同一节点状态：

```text
匹配先成功 → 返回已交接元素
取消先成功 → transfer 返回失败，公开入口返回 false/null 或抛 InterruptedException
```

取消后还必须解除节点链接并清空线程、item 等引用，否则频繁超时会让内部链持有大量无效节点。TransferStack 从 head 吸收取消节点并遍历解链；TransferQueue 还使用 `cleanMe` 处理暂时不能移除的尾节点。

不要通过“超时大约发生在第几毫秒”测试内部自旋次数。操作系统调度、JIT 和运行环境都会改变实际耗时；可靠测试只断言在明确截止时间内返回了契约规定的结果，并保证线程最终回收。

## 与 ThreadPoolExecutor 的组合

`Executors.newCachedThreadPool()` 使用非公平 `SynchronousQueue`。`ThreadPoolExecutor.execute` 对它调用 `offer(command)`：只有空闲 worker 已经在取任务时才能直接交接；否则 offer 失败，线程池尝试在 `maximumPoolSize` 范围内新增 worker。

所以 SynchronousQueue 不会积压任务，压力会更直接地转化为扩线程或拒绝。它必须和严格的最大线程数、任务耗时、拒绝策略一起评估；把最大线程数设得极大并不等于弹性没有成本。

## JDK 8、17、21 的实现变化

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| 公平/非公平结构 | `TransferQueue` / `TransferStack` | 保留两个独立实现 | 一个 `Transferer` 继承 `LinkedTransferQueue`，公平走 FIFO `xfer`，非公平走 `xferLifo` |
| 原子字段访问 | `sun.misc.Unsafe` 与字段偏移 | VarHandle | VarHandle 与 compare-and-exchange 风格辅助方法 |
| 阻塞协作 | 自旋、yield、`LockSupport.park` | 节点实现 `ManagedBlocker`，可经 ForkJoinPool 协作阻塞 | 复用 `LinkedTransferQueue.DualNode` 的等待与清理机制 |
| 私有主入口 | `transfer(e,timed,nanos)` | 同名 transfer | `xfer(e,nanos)` 再按 fair 选择 FIFO/LIFO |

公开的零容量、四组方法、公平构造参数与中断/超时契约保持稳定。JDK 21 中再去寻找 `TransferStack.transfer` 会找不到，这属于实现替换，不是 API 行为变化。

## 推荐断点

### OpenJDK 8u / 17

1. `SynchronousQueue.put/take/offer/poll`：确认传入的 e、timed 和 nanos。
2. 构造方法：确认 `fair` 选择 `TransferStack` 还是 `TransferQueue`。
3. `TransferStack.transfer`：观察 mode、head、SNode.match 与 fulfilling 帮助。
4. `TransferQueue.transfer`：观察 isData、head/tail、QNode.item 和 CAS 匹配。
5. 两种实现的 `awaitFulfill` 与 `clean`：观察 park、取消和解链。

### OpenJDK 21

1. `SynchronousQueue.xfer`：确认公平分支选择。
2. `LinkedTransferQueue.xfer`：跟踪 FIFO 配对。
3. `SynchronousQueue.Transferer.xferLifo`：跟踪非公平栈式配对。
4. `DualNode.await` 与清理路径：观察超时、中断以及匹配竞争。
