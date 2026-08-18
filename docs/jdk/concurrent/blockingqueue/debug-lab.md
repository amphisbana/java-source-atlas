# BlockingQueue 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/concurrent/BlockingQueueDebugLab.java
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.BlockingQueueDebugLab
```

实验只依赖公开 API。队列的数组、节点、锁和条件队列由调试器附加当前 SDK 源码观察，不使用反射访问私有字段。

## 实验一：四组方法的失败语义

运行 `observeMethodFamilies()`。容量为 1 的 `ArrayBlockingQueue` 先装入 seed，再依次观察：

| 调用 | 前置状态 | 预期 |
| --- | --- | --- |
| `offer("extra")` | 队列已满 | 立即 false |
| `add("extra")` | 队列已满 | `IllegalStateException` |
| `poll()` | 队列非空 | 返回 seed |
| `poll()` | 队列已空 | 返回 null |
| `remove()` | 队列已空 | `NoSuchElementException` |

随后用短截止时间调用定时 `poll`，确认超时以 null 表示。这个场景先建立接口契约，不要求进入具体实现的等待队列。

## 实验二：ArrayBlockingQueue 满队列等待

运行 `observeArrayQueuePutWait()`。容量为 1 的队列已包含 seed，生产者线程开始 `put(next)`。实验先在统一截止时间内确认生产者已经进入 `WAITING/TIMED_WAITING`，再检查它尚未完成，最后由主线程 `take()` 移除 seed。这样不会把“生产者线程刚启动但尚未调用 put”误判成已经进入 `notFull` 条件队列。

推荐断点顺序：

1. `ArrayBlockingQueue.put` 的 `lockInterruptibly()`；
2. `count == items.length` 的 while 判断；
3. `ConditionObject.await` 的 `fullyRelease`；
4. 主线程进入 `ArrayBlockingQueue.dequeue`；
5. `notFull.signal()` 和 AQS 的 `transferForSignal`；
6. 主线程 `unlock`；
7. 生产者 `acquireQueued` 成功后从 await 返回；
8. `enqueue(next)` 更新 putIndex、count 并 signal notEmpty。

### 必须记录的状态

| 阶段 | `count` | 主锁 owner | 生产者节点位置 |
| --- | ---: | --- | --- |
| 队列已满 | 1 | 无 | 未创建 |
| `await` 后 | 1 | 无 | notFull 条件队列 |
| `dequeue` 后、signal 前后 | 0 | 主线程 | 条件队列 → AQS 同步队列 |
| 主线程 unlock 后 | 0 | 无或竞争者 | 同步队列中重新竞争 |
| `enqueue` 完成 | 1 | 生产者 | 已重新获得锁 |

不要在 `signal` 处记录“生产者获得锁”。只有生产者自己的获取 CAS 成功，owner 才能变化。

## 实验三：LinkedBlockingQueue 的两个容量边界

运行 `observeLinkedQueueBoundarySignals()`。场景先让消费者在空队列执行定时 `poll`，并确认它已经进入等待状态后，主线程才放入 first，稳定触发 `0 → 1` 的 `signalNotEmpty`；随后填满容量为 2 的队列，同样确认生产者已经因 `put` 等待后再取出一个元素，触发 `capacity → capacity-1` 的 `signalNotFull`。

建议观察：

- `put` 中 `c = count.getAndIncrement()` 返回递增前数量；
- `c == 0` 时，putLock 已释放，`signalNotEmpty` 再取得 takeLock；
- `take` 中 `c = count.getAndDecrement()` 返回递减前数量；
- `c == capacity` 时，takeLock 已释放，`signalNotFull` 再取得 putLock；
- 非边界 put/take 只在各自锁内级联 signal，不必每次跨锁。

调试器在其中一把锁内挂起全部线程会人为阻止另一侧继续。建议断点只挂起当前线程，并结合线程名过滤。

## 实验四：SynchronousQueue 直接交接

运行 `observeSynchronousHandoff()`。消费者线程先进入定时 `poll`；实验通过“即将调用”闸门和线程状态轮询确认 REQUEST 已有机会发布后，主线程再使用定时 `offer("handoff")` 配对。成功期间 `size()` 和 `remainingCapacity()` 仍为 0。

分别把构造参数改为 false 和 true：

| 版本 | false 断点 | true 断点 |
| --- | --- | --- |
| JDK 8/17 | `TransferStack.transfer` | `TransferQueue.transfer` |
| JDK 21 | `Transferer.xferLifo` | `LinkedTransferQueue.xfer` |

只断言两个构造模式都能完成交接，不通过私有字段读取公平配置，也不断言多个等待线程的最终完成顺序。线程获得 CPU、取消与新匹配者竞争都会影响控制台顺序。

## 实验五：SynchronousQueue 立即失败与取消边界

运行 `observeSynchronousImmediateFailure()`。没有消费者时 `offer("orphan")` 立即返回 false，集合视角仍为空；没有生产者时 `poll()` 立即返回 null。

如需观察取消，可在测试副本中使用定时 offer 并不启动消费者，在以下位置断点：

- JDK 8 `SNode.tryCancel/QNode.tryCancel`；
- JDK 8 两种 `clean`；
- JDK 17 对应 VarHandle CAS 与 ManagedBlocker 返回；
- JDK 21 `DualNode.await` 和 FIFO/LIFO 解链路径。

断点本身可能让截止时间耗尽。它适合观察“取消已赢得 CAS 后怎样清理”，不适合测量真实超时精度。

## 自动化测试的同步原则

配套测试遵守以下边界：

1. 类级 `@Timeout` 给出最终截止时间；
2. 使用 CountDownLatch 标记“即将调用”边界，并在有截止时间内确认工作线程进入等待状态，不用 `Thread.sleep` 猜测调度；
3. finally 中打开所有释放闸门；
4. 对每个 ExecutorService 执行 `shutdownNow` 并等待终止；
5. 自动化测试还会中断一个真实阻塞中的 `put`，确认元素未写入且工作线程协作退出；
6. 只断言公开可观察结果，不访问锁、条件队列、内部数组或节点。

## 实验完成标准

- 能按抛异常、特殊值、无限等待和限时等待选择 API。
- 能画出 ArrayBlockingQueue 的 items、takeIndex、putIndex 和 count，并说明同一把锁为何串行化两端。
- 能解释 signal 只转移条件节点，等待生产者仍须重新竞争主锁。
- 能说明 LinkedBlockingQueue 为什么需要 AtomicInteger count，以及只在 0/满边界跨锁通知。
- 能区分 SynchronousQueue 的零容量与容量为 1，并解释 DATA/REQUEST 配对。
- 能指出 JDK 8/17 的 TransferStack/TransferQueue 与 JDK 21 统一 Transferer 路径差异。
