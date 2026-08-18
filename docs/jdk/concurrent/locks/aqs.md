# AQS：获取、排队与释放

AQS 管理一个 volatile `state` 和一个 FIFO 风格的同步等待队列。它不知道 state 的业务含义，含义由子类实现的模板方法决定。

## 同步队列节点

JDK 8 的 `Node` 主要保存：

| 字段 | 含义 |
| --- | --- |
| `prev` / `next` | 同步队列前后链接 |
| `thread` | 当前等待线程 |
| `waitStatus` | 取消、需要唤醒、条件等待等状态 |
| `nextWaiter` | 区分共享模式，或连接 Condition 队列 |

常见 `waitStatus`：

- `CANCELLED = 1`：等待已取消，不再变化。
- `SIGNAL = -1`：后继需要前驱在释放或取消时唤醒。
- `CONDITION = -2`：节点当前位于 Condition 条件队列。
- `PROPAGATE = -3`：共享获取的传播状态。

## acquire 的短路径与慢路径

```text
acquire(arg)
  ├─ tryAcquire(arg) 成功 → 返回
  └─ addWaiter(EXCLUSIVE)
       └─ acquireQueued(node, arg)
            ├─ 前驱是 head 且 tryAcquire 成功 → node 成为新 head
            ├─ shouldParkAfterFailedAcquire(...)
            ├─ parkAndCheckInterrupt()
            └─ 唤醒后循环重试
```

只有队首的直接后继有资格在每轮尝试获取，减少所有等待线程同时竞争。

## 动画：从快速获取到 park，再到重新竞争

下面固定两个线程：T1 先获得锁，T2 随后竞争失败。动画会同时显示 `state`、`exclusiveOwnerThread`、head 的 `waitStatus`、T2 的线程状态和队列位置。

<AqsQueueAnimation />

### 同一个 T2 节点经历了哪些状态

| 阶段 | T2 所在位置 | 前驱状态 | T2 线程状态 | 能否直接进入临界区 |
| --- | --- | --- | --- | --- |
| 获取失败 | 尚未入队 | 无 | RUNNABLE | 否 |
| `addWaiter` 完成 | head 之后 | 0 | RUNNABLE | 只有它可继续尝试 |
| 准备阻塞 | head 之后 | SIGNAL | RUNNABLE → WAITING | 否 |
| T1 `unpark` | head 之后 | 0 | WAITING → RUNNABLE | 仍需 `tryAcquire` |
| 获取成功 | 自己成为 head | 0 | RUNNABLE | 是 |

`unpark` 不是锁的所有权转移。它只发放一个许可，让线程从 `park` 返回；如果另一个线程在 T2 CAS 前抢到锁，T2 仍会回到队列循环并可能再次阻塞。

### SIGNAL 握手为什么需要两轮判断

第一次获取失败后，`shouldParkAfterFailedAcquire` 发现前驱状态为 0，于是把它 CAS 为 SIGNAL 并返回 false；调用方不会立即 park，而是回到循环再尝试一次。第二轮仍失败且前驱已经是 SIGNAL，才返回 true 并执行 park。

这两轮把“我需要被唤醒”的标记放在真正休眠之前。释放线程看到 head 的负状态后会调用 `unparkSuccessor`，从而避免线程在通知已经发生后才睡下。

### 建议断点与观察顺序

1. `ReentrantLock.Sync.lock()`：确认公平锁与非公平锁入口差异。
2. `AbstractQueuedSynchronizer.acquire()`：区分快速成功和进入队列。
3. `addWaiter()` / `enq()`：观察 `prev` 先于 `next` 建立。
4. `shouldParkAfterFailedAcquire()`：观察 head 从 0 变为 SIGNAL。
5. `parkAndCheckInterrupt()`：确认 T2 线程真正进入 WAITING。
6. `unparkSuccessor()`：观察唤醒目标，但不要把它当成 owner。
7. `setHead()`：以节点成为 head 作为慢路径获取完成标志。

## addWaiter 与 enq

`addWaiter` 先尝试一次快速尾插 CAS。队列尚未初始化或竞争失败时进入 `enq` 自旋：

1. 队列为空时 CAS 创建哨兵 head，并让 tail 指向它。
2. 设置新节点的 `prev = tail`。
3. CAS 把 tail 移到新节点。
4. 成功后把旧 tail 的 `next` 指向新节点。

前驱链接先建立，便于取消时从后向前查找有效节点；后继链接用于正常唤醒和遍历。

## 为什么 park 前要设置前驱 SIGNAL

线程不能看到获取失败就立即 park，否则释放线程可能不知道需要唤醒它。`shouldParkAfterFailedAcquire` 先保证有效前驱的 `waitStatus` 为 `SIGNAL`，下一轮确认后才安全阻塞。

这是避免“准备睡眠”和“资源释放”之间丢失唤醒的关键握手。

## release 流程

```text
release(arg)
  └─ tryRelease(arg)
       ├─ false：仍然重入持有，不唤醒
       └─ true：资源完全释放
            └─ head.waitStatus != 0
                 └─ unparkSuccessor(head)
```

ReentrantLock 的 `tryRelease` 扣减重入次数，只有 state 归零时清空 owner 并返回 true。

`unparkSuccessor` 优先使用后继节点；后继为空或已取消时，从 tail 向前寻找离当前节点最近的有效等待者。

## 取消与中断

等待超时或可中断获取被中断时，`cancelAcquire` 把节点标为取消并重新连接或唤醒后继。取消节点可能暂时留在链中，后续获取、释放和入队过程会协助跳过或清理。

普通 `acquire` 在等待期间记录中断，最终成功获取后调用 `selfInterrupt` 恢复中断标记；`acquireInterruptibly` 则直接响应中断并取消等待。

## 独占与共享不是两条完全分离的队列

独占模式一次只允许一个成功拥有者，`ReentrantLock` 使用该模式。共享模式允许多个线程在资源条件满足时先后成功，`Semaphore`、`CountDownLatch`、`ReentrantReadWriteLock` 的部分能力建立在共享路径上。

两种节点仍进入同一个 AQS 同步队列：

```text
head → EXCLUSIVE node → SHARED node → SHARED node → tail
```

区别由节点的 `nextWaiter` 标记和获取模板决定，不是每种模式各维护一条队列。共享节点也只有成为 head 的直接后继后才调用 `tryAcquireShared`；成功后除了推进 head，还可能继续把通行机会传播给后继。

## tryAcquireShared 返回值是传播协议

独占 `tryAcquire` 只返回 boolean。共享 `tryAcquireShared(arg)` 返回 int，结果同时表达“当前线程是否成功”和“后继是否可能继续成功”：

| 返回值 | 当前线程 | 对后继的含义 |
| ---: | --- | --- |
| `< 0` | 获取失败，进入或继续共享等待 | 当前条件不允许通过 |
| `= 0` | 获取成功 | 当前成功后没有明确剩余资源，但仍可能因并发释放状态继续检查传播 |
| `> 0` | 获取成功 | 后继也可能成功，应主动传播 |

这个返回值不是固定的“剩余线程数”。`CountDownLatch` 在 state 为 0 时固定返回 1；`Semaphore` 通常返回扣减许可后的剩余许可数。AQS 只解释符号，不解释业务数值。

公开入口的第一层很短：

```text
acquireShared(arg)
  ├─ tryAcquireShared(arg) >= 0 → 快速成功
  └─ tryAcquireShared(arg) < 0
       └─ doAcquireShared(arg)
```

可中断和定时版本分别由 `acquireSharedInterruptibly`、`tryAcquireSharedNanos` 包装，但最终仍围绕同一个共享尝试、入队、park 和取消协议。

## doAcquireShared 怎样进入共享队列

JDK 8 的慢路径可以压缩为：

```text
node = addWaiter(Node.SHARED)
for (;;) {
  p = node.predecessor()
  if (p == head) {
    r = tryAcquireShared(arg)
    if (r >= 0) {
      setHeadAndPropagate(node, r)
      p.next = null
      必要时恢复中断标记
      return
    }
  }
  建立前驱 SIGNAL
  park 并记录中断
}
```

它与独占 `acquireQueued` 复用相同的前驱检查、SIGNAL 握手、park 和取消清理。关键差异只有两点：

1. 入队时使用 `Node.SHARED` 标记。
2. 成功后调用 `setHeadAndPropagate`，而不是只调用 `setHead`。

普通 `acquireShared` 与普通 `acquire` 一样，在等待期间只记录中断，成功后通过 `selfInterrupt` 恢复标记；可中断版本则取消节点并抛出 `InterruptedException`。

## 动画：CountDownLatch 怎样沿共享队列传播

下面固定一个 `count=2` 的 CountDownLatch 和三个等待者。第一轮 `countDown` 只把 state 改为 1；第二轮归零后，`doReleaseShared` 先唤醒 W1，随后每个成功的共享节点通过 `setHeadAndPropagate` 把接力传给下一个节点。

<AqsSharedPropagationAnimation />

### 一次 countDown 与一次通过分别做什么

| 阶段 | state | 当前关键返回值 | 队列动作 |
| --- | ---: | --- | --- |
| 三个 await 失败 | 2 | `tryAcquireShared = -1` | 三个 SHARED 节点依次入队并 park |
| 第一次 countDown | 1 | `tryReleaseShared = false` | 不进入 `doReleaseShared` |
| 第二次 countDown | 0 | `tryReleaseShared = true` | 清除 head 的 SIGNAL，unpark W1 |
| W1 成功 | 0 | `tryAcquireShared = 1` | W1 成为 head，传播给 W2 |
| W2、W3 成功 | 0 | 各返回 1 | 逐个推进 head，直到 tail == head |

共享传播保证符合条件的后继最终有机会重试，不保证它们获得 CPU 或离开 `await` 的实际时间顺序。

## setHeadAndPropagate 为什么要保守传播

成功节点进入 `setHeadAndPropagate(node, propagate)` 时会先保存旧 head，再把自己设为新 head。随后满足下列任一条件就考虑调用 `doReleaseShared`：

- `propagate > 0`，同步器明确表示后继仍可能成功；
- 旧 head 为空或 `waitStatus < 0`；
- 再次读取的新 head 为空或 `waitStatus < 0`。

最后还会检查后继为空或后继是共享节点，避免把共享传播直接越过一个明确的独占后继。

为什么要前后读取两次 head？共享获取成功和另一个线程的共享释放可能并发发生。如果只看某一次瞬时状态，当前线程可能刚推进 head，释放线程也刚好认为别人会负责传播，结果后继没有人唤醒。JDK 8 允许保守地多做一次 `doReleaseShared`，因为多一次唤醒只会让后继重新检查资源；漏掉传播却可能让已有资源无人使用。

这里的 `propagate` 是 `tryAcquireShared` 的返回值，不等于 Node 的 `PROPAGATE(-3)` 状态。两个名称相近，但一个是同步器返回的资源提示，另一个是 head 上记录的队列传播状态。

## releaseShared 与 doReleaseShared

共享释放入口同样先把业务语义交给子类：

```text
releaseShared(arg)
  └─ tryReleaseShared(arg)
       ├─ false → 状态变化尚不允许等待者通过，直接返回
       └─ true  → doReleaseShared()
```

`doReleaseShared` 在 JDK 8 中循环观察 head：

```text
for (;;) {
  h = head
  if (h != null && h != tail) {
    if (h.waitStatus == SIGNAL) {
      CAS SIGNAL → 0
      unparkSuccessor(h)
    } else if (h.waitStatus == 0) {
      CAS 0 → PROPAGATE
    }
  }
  if (h == head) break
}
```

两条分支职责不同：

- `SIGNAL → 0`：已经有后继声明需要唤醒，当前线程取得清零权后执行一次 `unparkSuccessor`。
- `0 → PROPAGATE`：当前没有可直接处理的 SIGNAL，但共享释放已经发生，把“仍需传播”的事实留在 head 上，供后续 head 推进时识别。

循环末尾只有发现 head 没有变化才退出。若被唤醒线程并发成功并推进了 head，当前释放线程会再检查新 head，避免只处理旧节点后过早结束。

`PROPAGATE` 不是许可数、等待者数或广播标记，也不表示后继已经执行。它只用于协调并发的共享获取与共享释放，避免传播责任从两条路径之间掉落。

## CountDownLatch 怎样把 count 映射到 state

`CountDownLatch.Sync` 的规则非常窄：

```text
构造：setState(count)

tryAcquireShared(1):
  state == 0 ? 1 : -1

tryReleaseShared(1):
  state == 0      → false
  CAS state - 1
  nextState == 0  → true
  nextState > 0   → false
```

因此：

1. `await` 不消耗 count，只判断是否已经归零。
2. `countDown` 到非零时只更新 state，不唤醒等待者。
3. 最后一次 `countDown` 才进入 `doReleaseShared`。
4. state 一旦归零就不会恢复，后续 `await` 直接成功；CountDownLatch 不能重置。

类契约还保证：调用 `countDown` 前的动作 happen-before 另一个线程从对应 `await` 成功返回后的动作。这个可见性保证建立在真实同步协议上，不应通过轮询 `getCount()` 自己模拟。

## Semaphore 怎样把许可数映射到 state

`Semaphore.Sync` 把 state 解释为可用许可数。非公平共享获取循环为：

```text
available = state
remaining = available - acquires
remaining < 0          → 返回负数，获取失败
CAS state → remaining  → 返回 remaining，获取成功
```

返回 0 表示当前线程成功拿走最后一份许可；正数表示仍有许可，`setHeadAndPropagate` 可以继续推动共享后继。`release(n)` 通过 `tryReleaseShared` 原子增加许可并返回 true，随后进入共享释放流程。

Semaphore 与锁有三个重要区别：

1. 许可没有线程所有权；一个线程可以 acquire，另一个线程 release。
2. 多次 release 会增加许可，错误释放不会像错误 unlock 那样抛 `IllegalMonitorStateException`。
3. 公平 Semaphore 的阻塞获取会在 `FairSync.tryAcquireShared` 中检查 `hasQueuedPredecessors`；无参数 `tryAcquire()` 仍允许立即非公平尝试。

因此 Semaphore 适合表达数量资源，不适合伪装成必须由 owner 释放的互斥锁。生产代码还要确保异常路径不会多释放或漏释放许可。

## 三个同步器怎样落到 AQS 模板

| 观察点 | ReentrantLock | CountDownLatch | Semaphore |
| --- | --- | --- | --- |
| 模式 | 独占 | 共享 | 共享 |
| state | 重入次数 | 剩余 count | 可用 permits |
| 获取入口 | `acquire(1)` | `acquireSharedInterruptibly(1)` | `acquireSharedInterruptibly(n)` |
| 获取成功 | 空闲或 owner 重入 | count 已为 0 | 成功扣减许可 |
| 释放入口 | `release(1)` | `releaseShared(1)` | `releaseShared(n)` |
| 传播条件 | 完全释放时唤醒一个后继 | 最后一次 countDown 后传播所有共享等待者 | 许可仍足够时继续传播 |
| 所有权 | 有 owner | 无 | 无 |

## JDK 版本边界

较新 JDK 对 AQS 节点类型、状态字段、队列链接和等待实现进行过明显重构。JDK 17/21 源码中不能照搬 JDK 8 的 `waitStatus`、`nextWaiter`、`PROPAGATE` 字段布局或私有方法局部变量。

跨版本稳定的是：

- 同步器子类定义独占/共享资源语义；
- 失败线程进入同步等待，成功共享获取可以继续传播；
- CountDownLatch 归零后永久开放；
- Semaphore 以许可数协调共享获取；
- 公平策略检查已有排队前驱，但不承诺操作系统调度顺序。

调试较新 JDK 时应从公开入口和当前源码中的 acquire/release 模板重新定位，不能把 JDK 8 的 Node 私有常量当作兼容接口。
