# wait-notify：条件循环、重新竞争、超时与中断

`Object.wait/notify` 不是消息队列。monitor 不保存“通知次数”，`notify` 也不携带业务结果。可靠协议必须把业务条件保存在受同一 monitor 保护的字段中，把通知只当作“条件可能变化，请重新检查”的提示。

## 调用前置条件：必须持有同一个对象的 monitor

```java
Object monitor = new Object();

monitor.wait();   // 错误：当前线程没有持有 monitor
monitor.notify(); // 错误：同样抛 IllegalMonitorStateException
```

正确调用必须位于 `synchronized (monitor)` 内。持有另一个对象也不行：

```java
synchronized (other) {
    monitor.wait(); // 仍然错误
}
```

这是 owner 身份约束，不只是为了语法整齐。JVM 需要原子地把当前 owner 放入目标 monitor 的 WaitSet 并释放同一个 monitor，避免“检查条件之后、真正等待之前”丢失状态变化。

## 标准条件循环

```java
synchronized (monitor) {
    while (!ready) {
        monitor.wait();
    }
    consumePublishedState();
}
```

修改方使用相同 monitor：

```java
synchronized (monitor) {
    publishedState = value;
    ready = true;
    monitor.notifyAll();
}
```

顺序是“先修改条件，再通知”。等待方每次获得锁后重新检查 `ready`，由 monitor unlock/lock 的 happens-before 看见写入，而不是从 `notify` 本身读取数据。

## 为什么必须用 while 而不是 if

至少有四种情况使 `wait` 返回后条件仍不成立：

1. **多个等待者竞争同一个资源**：notifyAll 后第一个线程消费资源，后续线程取得锁时条件已再次为 false。
2. **不同条件共用一个 monitor**：某次通知是为另一类等待者发出。
3. **伪唤醒**：Java API 允许线程没有对应 notify 也从等待返回。
4. **条件在通知后再次变化**：被通知线程等待重新取得 monitor 期间，其他线程可能先进入并修改状态。

`if` 把一次唤醒错误地等同于条件成立；`while` 才把业务谓词作为唯一通行证。

## wait 完整释放，sleep 不释放

| 操作 | 是否要求持有 monitor | 是否释放已经持有的 monitor | 返回前是否重新取得 |
| --- | --- | --- | --- |
| `Object.wait()` | 是，目标对象 monitor | 是，完整释放目标 monitor 的全部重入层数 | 是 |
| `Thread.sleep()` | 否 | 否，睡眠期间继续持有任何已持有 monitor | 不涉及 |
| `LockSupport.park()` | 否 | 否 | 不涉及 monitor |
| `Condition.await()` | 要求持有关联 Lock | 完整释放关联 Lock | 是，返回前重新获取 Lock |

在 `synchronized` 内调用 `sleep` 等待别人修改同一把锁保护的条件，通常会制造死等：睡眠线程仍占着 monitor，修改方无法进入。

## notify 与 notifyAll 的选择

### notify

- 从该 monitor 的等待集中任意选择一个线程。
- 没有 FIFO、优先级或“选择条件匹配者”的保证。
- 适合所有等待者等待同一个等价条件，且每次状态变化只需要一个消费者推进的协议。

### notifyAll

- 让当前等待集中的全部线程进入重新竞争阶段。
- 不代表所有线程同时执行；它们仍要逐个取得 monitor 并检查各自条件。
- 当同一 monitor 上存在多种条件，或无法证明 notify 一定选中可推进线程时，通常更安全。

`notifyAll` 可能增加竞争，但错误的 `notify` 可能让唯一能推进系统的线程永远留在 WaitSet。先保证协议正确，再根据真实性能数据优化。

## notify 不会释放 monitor

```java
synchronized (monitor) {
    ready = true;
    monitor.notify();
    slowOperation(); // waiter 已被选中，但仍不能从 wait 返回
}
```

被通知线程必须重新取得 monitor，当前线程只有执行到同步块末尾或正常/异常 `monitorexit` 时才释放。把通知想象成“从候诊区叫号到门口排队”，不是直接把 owner 交给被通知者。

## 中断 wait 的完整语义

线程在 `wait` 中被中断时：

1. 它不一定立刻执行 catch；返回/抛出前仍要重新取得 monitor。
2. 重新取得后抛 `InterruptedException`。
3. JVM 在抛出前清除中断标记，所以 catch 中 `isInterrupted()` 通常为 false。
4. 调用方必须选择策略：向上传递、恢复标记后退出，或把中断转成领域取消；不能静默吞掉。

```java
try {
    synchronized (monitor) {
        while (!ready) {
            monitor.wait();
        }
    }
} catch (InterruptedException exception) {
    Thread.currentThread().interrupt();
    return;
}
```

如果方法声明允许 `throws InterruptedException`，优先向上传递，让上层决定取消策略。恢复标记适用于当前层不能抛出、但需要保留取消信号的情况。

## 有界等待必须使用 deadline 循环

直接重复 `wait(timeout)` 会在每次伪唤醒后重新获得完整 timeout，导致总等待无限延长。正确做法是计算绝对 deadline：

```java
long remainingNanos = timeoutNanos;
long deadline = System.nanoTime() + timeoutNanos;
synchronized (monitor) {
    while (!ready) {
        if (remainingNanos <= 0L) {
            return false;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
        monitor.wait(millis, nanos);
        remainingNanos = deadline - System.nanoTime();
    }
    return true;
}
```

使用 `System.nanoTime()` 计算经过时间，避免系统墙上时钟调整影响期限。注意 `wait(0)` 表示无限等待，纳秒换算时不能因截断意外传入 `(0, 0)`；上例保留了纳秒部分。

## “先 notify 后 wait”为什么会丢

monitor 没有 permit 计数：WaitSet 为空时调用 notify，不会给未来等待者保存令牌。正确协议依赖条件状态：

```text
T2 先获得 monitor
  → 写 ready=true
  → notify（此时没有 waiter 也没关系）
  → unlock
T1 后获得 monitor
  → while 检查 ready 已为 true
  → 根本不调用 wait
```

丢失通知发生在代码把 notify 当消息、却没有保存 `ready` 等业务事实时。相比之下 `LockSupport` 有一位 permit，但它同样不能替代业务条件。

## wait-notify 与 Condition 怎样选择

| 需求 | 内置 monitor | `ReentrantLock + Condition` |
| --- | --- | --- |
| 简单单条件保护 | 语法短，异常退出自动释放 | 代码更多 |
| 多个独立条件队列 | 一个 WaitSet，通常只能 notifyAll | 可创建多个 Condition 精准 signal |
| 可中断/定时/非阻塞加锁 | monitor enter 本身不可选超时 | `lockInterruptibly/tryLock` 更灵活 |
| 公平策略 | 无公开公平选项 | ReentrantLock 可请求公平模式 |
| JVM 工具识别 | 线程转储原生支持 | JUC ownable synchronizer 也有诊断支持 |

两者都要求“条件循环 + 修改状态后通知 + 返回前重新取得锁”。Condition 不是不需要谓词的高级通知器。

下一步执行 [断点与线程状态实验](./debug-lab.md)，把这些时序变成可重复观察的状态。
