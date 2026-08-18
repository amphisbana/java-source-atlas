# park/unpark：一位许可、blocker 与伪唤醒

LockSupport 的核心模型可以压缩成“每个线程都有一个最多为 1 的 permit 槽”。它解决底层同步器最棘手的通知顺序问题：通知可以早于等待，下一次 park 仍能消费已经保存的许可。

## 一位 permit 状态机

```text
permit = 0
  --unpark(thread)--> permit = 1
  --park()----------> 阻塞或因其他原因返回，permit 仍为 0

permit = 1
  --unpark(thread)--> permit = 1   // 不累加
  --park()----------> permit = 0，并立即返回
```

这不是 Semaphore：

- 连续 10 次 unpark 也不会产生 10 个许可；
- permit 不携带消息内容；
- 没有公开 API 可以直接查询 permit 当前值；
- permit 与线程绑定，不与 blocker 或某个锁对象绑定；
- 对尚未启动的线程 unpark 没有可依赖的预发效果，实验必须先让目标线程真正启动。

## unpark-before-park 为什么不会丢通知

传统“先检查条件、再睡眠”的窗口容易出错：

```text
waiter：看到 ready=false -------- 准备睡眠 -------- 真正睡眠
notifier：                 ready=true + 通知
```

如果通知只能作用于“已经睡着”的线程，通知发生在窗口里就会丢失。LockSupport.unpark 可以先把 permit 设为 1；waiter 随后 park 时消费它并直接返回，所以通知不要求与阻塞精确重合。

不过 permit 只能弥补一个通知窗口，不能取代条件本身。可靠顺序仍然是：

```java
ready = true;                 // ready 必须是 volatile/atomic 或受锁保护
LockSupport.unpark(waiter);   // 再让 waiter 有机会重检 ready
```

等待方：

```java
while (!ready) {
    LockSupport.park(this);
}
```

如果先 unpark 再发布普通非 volatile ready，waiter 即使醒来，也没有公开契约保证它看到最新业务值。

## 为什么必须用 while，不用 if

park 可能因为以下任一原因返回：

1. 已有 permit，被本次 park 消费。
2. 其他线程调用 unpark。
3. 当前线程被 interrupt。
4. parkNanos/parkUntil 到达时间边界。
5. 允许的伪唤醒。

API 不返回“本次是哪一种原因”。所以：

```java
if (!ready) {
    LockSupport.park();
}
consume(); // 错误：一次返回不等于 ready=true
```

必须改成循环，并单独处理取消和截止时间：

```java
while (!ready) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0L) {
        return false;
    }
    LockSupport.parkNanos(this, remaining);
    if (Thread.interrupted()) {
        throw new CancellationException("等待被中断");
    }
}
return true;
```

这段代码即使伪唤醒，也只会重新计算剩余时间；不会错误地把提前返回当成条件成立。

## park 的四个常用入口

| 方法 | 时间语义 | Java 状态 | 推荐用途 |
| --- | --- | --- | --- |
| `park()` | 无期限，直到某种返回原因发生 | WAITING | 无超时底层等待 |
| `park(Object blocker)` | 同上，额外记录诊断对象 | WAITING | 同步器内部优先使用 |
| `parkNanos(blocker, nanos)` | 相对纳秒时长；非正值直接不等待 | TIMED_WAITING | 基于 `nanoTime` 的剩余时间循环 |
| `parkUntil(blocker, deadline)` | 绝对毫秒时间点 | TIMED_WAITING | 与日历时刻相关的截止时间 |

耗时和超时控制通常优先 `System.nanoTime + parkNanos`，因为 nanoTime 单调递增，不受墙上时钟校准影响。`parkUntil` 使用绝对时间，更容易受系统时钟调整影响。

无论哪个定时版本，都允许提前返回；调用者仍需重算 deadline 或重检业务条件。

## blocker 是诊断信息，不是条件

OpenJDK 8u 的带 blocker park 可以概括为：

```text
Thread t = Thread.currentThread()
setBlocker(t, blocker)
Unsafe.park(...)
setBlocker(t, null)
```

其他线程可以在等待期间调用 `LockSupport.getBlocker(t)` 读取这个对象。JDK 线程转储、诊断器和同步器监控由此回答“线程停在哪个逻辑对象上”。park 返回后 blocker 会被清空。

blocker 应选择能表达等待原因的稳定对象：

- AQS 传入同步器本身，因此转储能定位 ReentrantLock、Semaphore 等拥有者。
- FutureTask 等待时传入 task，本身就是“等待哪个结果”的描述。
- 自定义同步器可以传入队列、闸门或等待节点，但不应为了日志临时创建大量无意义对象。

blocker 不负责唤醒，不保存 permit，也不参与 happens-before。修改 blocker 对象的字段不会自动通知等待线程。

## park 与中断标记

park 发现当前线程已经被中断，或等待期间收到中断，都会返回；它不抛 InterruptedException，也不清除标记：

```java
LockSupport.park(this);
boolean stillSet = Thread.currentThread().isInterrupted(); // true
```

如果循环忽略这个标记，下一次 park 仍会立即返回，形成高 CPU 空转。同步器必须选择：

- 清除并把中断转换为取消结果；
- 暂存中断，继续不可中断等待，完成后恢复；
- 把中断直接纳入退出谓词。

详细策略见 [中断：请求、观察、清除与恢复](./interrupt.md)。

## park/unpark 的内存可见性边界

LockSupport 文档要求用 volatile 或原子变量控制 park/unpark。原因是 permit 只定义阻塞许可，不是通用数据发布容器。

正确模型有两条独立通道：

```text
业务状态通道：volatile / CAS / lock / Future.state
等待调度通道：park / unpark permit
```

通知方先发布业务状态，再 unpark；等待方从 park 返回后重新读取状态。即使发生预发许可、伪唤醒或无关中断，业务正确性仍由状态通道保证。

不要写成“unpark 天然刷新所有普通字段”或“park 是内存屏障所以不需要 volatile”。内部 Unsafe 和操作系统实现可能比公开契约更强，但业务只能依赖 Java 内存模型和 API 文档保证。

## AQS 如何在 permit 之上补齐队列协议

LockSupport 不知道谁应该等待、谁是下一个，也不知道等待的是锁还是许可。AQS 增加：

1. volatile `state` 表示同步资源。
2. FIFO 风格同步队列保存等待节点。
3. 前驱 SIGNAL 握手保证释放方知道需要唤醒后继。
4. `shouldParkAfterFailedAcquire` 在真正 park 前再次确认。
5. `unparkSuccessor` 选择有效后继并调用 LockSupport.unpark。
6. 被唤醒节点重新 `tryAcquire`，失败仍回到循环。

```text
T2 tryAcquire 失败
  -> 入队
  -> 前驱设为 SIGNAL
  -> 再次确认条件
  -> LockSupport.park(this)

T1 release
  -> state 释放成功
  -> unparkSuccessor(head)
  -> LockSupport.unpark(T2)

T2 park 返回
  -> 再次 tryAcquire
```

因此 `unpark(T2)` 只表示 T2 可以恢复竞争，绝不表示锁已经转交给 T2。完整队列见 [AQS：获取、排队与释放](../locks/aqs.md)。

## FutureTask 如何使用 park/unpark

FutureTask.get 的等待条件是 `state` 已进入终态，而不是“收到一个 permit”：

```text
get
  -> state 未完成
  -> WaitNode 压入 waiters Treiber 栈
  -> 再检查 state
  -> park / parkNanos
  -> 返回后继续检查 state、中断和 deadline

finishCompletion
  -> 摘下 waiters 栈
  -> 逐个 LockSupport.unpark(waiter.thread)
```

先入栈再 park 配合预发 permit，关闭了“任务刚完成、等待者正准备睡”的窗口。即使完成线程在等待者调用 park 前 unpark，permit 也会让之后的 park 直接返回。详见 [FutureTask 等待栈与取消](../futuretask/waiters-cancel.md)。

## 线程池为什么也离不开它

ThreadPoolExecutor 的 Worker 执行完任务后调用工作队列 `take/poll`：

- ArrayBlockingQueue/LinkedBlockingQueue 通过 ReentrantLock + Condition 等待非空；
- Condition.await 把节点转移到条件队列并最终 park；
- 新任务入队后 signal，再由 AQS 把对应线程 unpark；
- shutdown/shutdownNow 通过中断空闲或全部 Worker，让阻塞等待退出并重检池状态。

所以线程池代码中不一定直接出现 LockSupport，调用链末端仍会到达它。监控中的 WAITING/TIMED_WAITING 往往只是 Worker 正常空闲，不应仅凭状态判定泄漏或死锁。

## JDK 8、17、21 实现边界

| 观察点 | OpenJDK 8u | OpenJDK 17 | OpenJDK 21 |
| --- | --- | --- | --- |
| Unsafe 包 | `sun.misc.Unsafe` | `jdk.internal.misc.Unsafe` | `jdk.internal.misc.Unsafe` + 虚拟线程内部路径 |
| blocker 写入 | Thread 私有 parkBlocker + Unsafe | 私有字段与访问方式演进 | 平台/虚拟线程都保留可诊断 blocker 语义 |
| 平台线程 park | 进入 VM/OS 停放 | 核心模型延续 | 核心模型延续 |
| 虚拟线程 park | 不存在 | 不存在 | 可由虚拟线程调度器卸载并在 permit 可用时重新调度 |

JDK 21 虚拟线程让 park 更适合海量等待，但 permit 仍是一位，伪唤醒仍合法，中断仍是协作请求。若虚拟线程在固定 carrier 的位置阻塞，运行时可能无法卸载；这属于调度成本差异，不改变 LockSupport 的公开正确性协议。

