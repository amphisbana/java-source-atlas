# Condition：条件队列与重新竞争

一个 `ConditionObject` 绑定到创建它的 AQS 实例。它维护独立的单向条件队列 `firstWaiter → nextWaiter → lastWaiter`。

## 两种队列不要混淆

```text
Condition 条件队列
  保存已经持锁并调用 await、等待业务条件的线程

AQS 同步队列
  保存正在竞争锁或其他同步状态的线程
```

`signal` 的作用是把节点从条件队列转移到同步队列。被转移线程仍要重新竞争并获取锁，才能从 `await` 返回。

## 动画：一个节点如何从 Condition 队列转入同步队列

演示让 T1 重入两次后调用 `await`，T2 修改业务条件并调用 `signal`。重点观察 T1 的节点位置、锁 owner、`state` 和 `savedState`，而不是只看线程是否被唤醒。

<ConditionTransferAnimation />

### 节点转移前后的字段差异

| 阶段 | 所在队列 | `waitStatus` | 连接字段 | 是否持锁 |
| --- | --- | --- | --- | --- |
| `addConditionWaiter` 后 | Condition | CONDITION(-2) | `nextWaiter` | T1 暂时仍持锁 |
| `fullyRelease` 后 | Condition | CONDITION(-2) | `nextWaiter` | 否，state 已完整释放 |
| `transferForSignal` 后 | AQS 同步队列 | 0 或后续 SIGNAL 协议状态 | `prev / next` | 否 |
| `acquireQueued` 成功后 | 成为 head | 0 | 同步队列链接 | 是，恢复 savedState |

同一个节点不会同时有效地留在两条队列。`signal` 先把条件状态 CAS 掉，再通过 `enq` 接到同步队列；失败或取消路径还会清理已失效的 Condition 节点。

### 为什么 await 必须完整释放而不是只 unlock 一次

如果 T1 已经重入两次，`state=2`。只执行一次普通释放会剩下 `state=1`，owner 仍是 T1，负责改变条件的 T2 永远无法进入临界区。`fullyRelease` 保存完整的 2 并把锁彻底释放；之后 `acquireQueued(node, 2)` 再恢复原重入层数。

### signal、unpark、await 返回是三个不同事件

```text
signal
  → 节点从条件队列转到同步队列
  → 当前持锁线程继续运行
  → 正常路径等待持锁线程 unlock；转移补偿路径也可能提前 unpark
  → 等待者重新获取锁
  → await 返回
```

因此 signal 后立即读取共享变量时，不能假设等待线程已经执行；等待线程也必须在 while 中重新检查业务条件，因为排队期间条件可能再次变化。

## await 的完整流程

```text
await()
  ├─ 检查当前线程中断状态
  ├─ addConditionWaiter()
  ├─ fullyRelease(node)             完全释放所有重入层数
  ├─ park，直到节点进入同步队列
  ├─ acquireQueued(node, savedState)
  ├─ 清理取消的条件节点
  └─ 按中断模式抛异常或恢复中断标记
```

`fullyRelease` 必须释放完整重入计数，否则其他线程无法获得锁并改变条件。重新获得时使用保存的 state 恢复原重入层数。

本专题 Lab 不是只在动画里假设 state=2：等待线程会连续调用两次 `lock()`，在 `await` 前记录 `getHoldCount()==2`，被 signal 并重新获得锁后再次记录 `getHoldCount()==2`。自动测试同时断言这两个值，因而能证明实验真实经过 `fullyRelease(node)` 保存 2、完全释放到 0，再由 `acquireQueued(node, 2)` 恢复两层持有。

调用 `await` 时没有持有关联锁会导致 `IllegalMonitorStateException`。

## signal 的转移流程

持锁线程调用 `signal()`：

1. 检查当前线程独占持有锁。
2. 从条件队列取第一个等待节点。
3. CAS 把节点状态从 `CONDITION` 改为同步等待状态。
4. 通过 `enq` 加入 AQS 同步队列。
5. 必要时直接 `unpark`，但线程仍需等待锁释放。

`signalAll` 逐个转移全部条件节点，并不保证它们同时执行。

## 为什么 await 必须放在 while 中

```java
lock.lock();
try {
    while (!conditionPredicate()) {
        condition.await();
    }
    useProtectedState();
} finally {
    lock.unlock();
}
```

线程可能虚假唤醒；也可能在被 signal 后排队期间，业务条件再次被其他线程改变。重新取得锁后必须再次检查谓词，不能用 `if` 假设条件永久成立。

## 多个 Condition 的价值

同一把 ReentrantLock 可以创建多个 Condition。例如有界缓冲区可分别维护 `notEmpty` 和 `notFull`：生产者只唤醒等待空间的线程，消费者只唤醒等待元素的线程，避免单一监视器条件集造成无关唤醒。

## 中断与超时

`await`、`awaitNanos`、`awaitUntil` 等方法需要区分：

- 中断在线程仍位于条件队列时赢得转移竞争：记录 `THROW_IE`，重新获得锁后抛出 `InterruptedException`。
- signal 已经先完成节点转移：记录 `REINTERRUPT`，重新获得锁后恢复中断标记，不再因这次中断抛出 `InterruptedException`。
- 超时也需要把节点转移到同步队列，重新获取锁后才能返回。

复杂性来自一个基本保证：无论正常 signal、中断还是超时，调用方离开 `await` 前都重新持有关联锁。

[BlockingQueue](../blockingqueue/) 专题把这套协议落到真实容器：`ArrayBlockingQueue` 用同一把锁上的 `notEmpty/notFull` 协调两端，`LinkedBlockingQueue` 则用两把锁和跨锁边界通知提高非边界场景的并行度。
