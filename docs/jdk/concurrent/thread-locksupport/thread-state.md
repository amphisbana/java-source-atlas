# 线程启动、六种状态与阻塞 API

`Thread.State` 是 JVM 暴露给 Java 的生命周期快照，不是操作系统线程状态的逐项翻译。读线程转储时，应先问“线程在等 CPU、monitor、业务条件还是超时”，再看调用栈和 blocker；不能看到 RUNNABLE 就断定线程正在消耗 CPU，也不能看到 WAITING 就断定发生死锁。

## 六种状态的准确含义

| Java 状态 | 典型进入方式 | 如何离开 | 是否释放已经持有的普通锁 |
| --- | --- | --- | --- |
| `NEW` | 创建 Thread 但尚未成功 start | 调用一次 `start()` | 不涉及 |
| `RUNNABLE` | 正在执行，或已具备运行条件等待系统调度/资源 | 调度、阻塞、等待或 run 返回 | 不自动释放 |
| `BLOCKED` | 进入 `synchronized` 时等待对象 monitor | monitor 拥有者退出或 wait 释放 monitor | 尚未拿到目标 monitor；不会释放其他锁 |
| `WAITING` | `Object.wait()`、无参 `join()`、`LockSupport.park()` | notify/signal、目标结束、unpark、中断、伪唤醒等 | 取决于 API；wait 释放目标 monitor，park 不释放锁 |
| `TIMED_WAITING` | `sleep`、带超时 wait/join、`parkNanos/parkUntil` | 超时、通知、中断或其他返回原因 | 取决于 API |
| `TERMINATED` | `run()` 正常返回或因未捕获异常结束 | 终态，不能再次 start | 线程退出会释放其持有的 monitor |

`RUNNABLE` 合并了操作系统层面的 running 与 ready 等状态，还可能包含等待某些底层资源的情况。只有结合线程栈、CPU 采样和持续时间，才能判断它是否构成忙循环或热点。

## new 到 run 的源码链

构造 Thread 时，JDK 初始化名称、优先级、daemon、ThreadGroup、上下文类加载器、继承 ThreadLocal 等 Java 对象状态。此时没有为这个对象启动独立执行流，`getState()` 返回 NEW。

OpenJDK 8u 的 `start()` 是同步方法，核心顺序是：

```text
检查 threadStatus == 0
  -> 否：抛 IllegalThreadStateException
  -> 是：ThreadGroup.add(this)
        -> native start0()
        -> 启动失败则从 ThreadGroup 回滚
```

这里的同步不表示调用方会等到 run 完成，它只保护同一个 Thread 对象的启动动作。`start0()` 成功后，VM 在新执行流上调用 `run()`；默认 run 再调用保存的 Runnable target。

### 为什么 start 只能一次

一次启动约束保护完整生命周期，而不只是防止两个线程同时调用 run：

- VM 与 Java Thread 对象之间只建立一次关联；
- ThreadGroup 的未启动计数和活动线程登记只提交一次；
- `start` 的 happens-before 边界只有一次；
- 线程退出后的 native 资源已经按终止流程清理。

`threadStatus` 的具体数值是 VM 私有协议。源码阅读只需要知道 0 表示尚未启动，不应通过反射读取或推断其他数值。

### 直接调用 run 为什么仍是 NEW

```java
Thread worker = new Thread(task, "worker");
worker.run();   // 当前线程调用普通方法
worker.start(); // 之后仍可真正启动一次
```

第一行 `run()` 没有触碰 `threadStatus`，所以方法返回后 worker 仍为 NEW。target 已经在当前线程执行一次；随后 start 后，它又会在 worker 线程执行一次。把 run 当作 start 会产生重复副作用和错误线程上下文。

## 典型状态迁移不是一条直线

```text
NEW --start--> RUNNABLE -------------------------> TERMINATED
                   |           ^
                   | monitor   | 获得 monitor
                   v           |
                 BLOCKED ------+
                   |
                   | wait / park / join
                   v
                 WAITING --------通知、unpark、中断、伪唤醒------> RUNNABLE
                   |
                   | 带超时版本
                   v
              TIMED_WAITING ----超时或提前返回------------------> RUNNABLE
```

实际线程可以在 RUNNABLE 与三种等待状态之间往返任意多次。WAITING 被唤醒后通常只是变回“可以继续竞争”的 RUNNABLE：

- wait 被 notify 后，还要重新取得原 monitor 才能从 wait 返回；期间可能表现为 BLOCKED。
- AQS 中 park 被 unpark 后，还要重新 `tryAcquire`；失败可以再次 park。
- join 等待者被目标结束唤醒后，还要重新检查 `isAlive()`，因为唤醒与条件成立是两件事。

## sleep、join、wait 与 park 对比

| API | 调用前必须持有 monitor | 等待时释放什么 | Java 状态 | 中断结果 | 可能伪唤醒/提前返回 |
| --- | --- | --- | --- | --- | --- |
| `Thread.sleep(ms)` | 否 | 不释放任何 monitor 或 Lock | TIMED_WAITING | 抛 `InterruptedException` 并清标记 | 可能因中断提前结束 |
| `thread.join()` | 否；内部同步目标 Thread 对象 | 等待时释放目标 Thread 对象的 monitor，不释放调用者其他锁 | WAITING | 抛 `InterruptedException` 并清标记 | 内部循环检查 `isAlive` |
| `thread.join(ms)` | 同上 | 同上 | TIMED_WAITING | 同上 | 超时或中断返回 |
| `object.wait()` | 是，必须持有 object monitor | 完整释放 object monitor，返回前重新获得 | WAITING | 抛 `InterruptedException` 并清标记 | 是，必须循环检查条件 |
| `object.wait(ms)` | 是 | 同上 | TIMED_WAITING | 同上 | 是，也可能超时 |
| `LockSupport.park(blocker)` | 否 | 不释放任何 monitor 或 `Lock` | WAITING | 返回并保留中断标记 | 是 |
| `parkNanos/parkUntil` | 否 | 不释放任何锁 | TIMED_WAITING | 返回并保留中断标记 | 是，也可能超时 |

最危险的误解是“线程睡眠会让出锁”。sleep 只让当前线程暂停调度，不会释放它已经持有的 synchronized monitor 或 ReentrantLock；park 也一样。持锁 sleep/park 会让其他竞争者长时间卡在 BLOCKED 或 AQS 队列中。

## JDK 8 的 join 为什么使用 wait 循环

无参 join 最终进入带参数实现，JDK 8 的逻辑可概括为：

```text
synchronized join(0)
  -> while (isAlive())
       wait(0)
```

`join` 同步的是“目标 Thread 对象”。调用方等待时通过 `wait` 释放这个对象的 monitor；目标线程终止时，VM 的终止流程唤醒在该 Thread 对象上等待的线程。循环处理伪唤醒，并以 `isAlive()` 作为真实条件。

不要在业务代码中把 Thread 实例当作普通 monitor 做 wait/notify，这会干扰 join 协议，也是 JDK 文档明确不建议的用法。

## BLOCKED 与 WAITING 的诊断差异

下面代码会产生 BLOCKED：

```java
synchronized (monitor) {
    // 另一个线程尝试进入同一个 synchronized 时为 BLOCKED
}
```

下面代码会产生 WAITING：

```java
while (!ready) {
    LockSupport.park(blocker);
}
```

两者都表现为“线程没有继续执行”，原因却不同：

- BLOCKED 的唯一目标是获得某个 monitor，线程转储会指出 monitor owner。
- WAITING 可能在等待业务条件、任务完成、队列元素或显式许可，要结合栈顶 API 与 blocker。
- ReentrantLock 竞争线程通常是 WAITING，因为 AQS 使用 LockSupport，而不是 JVM monitor 进入协议。

因此不能通过“BLOCKED 才算锁竞争”来判断并发问题。

## getState 只是瞬时快照

`Thread.getState()` 主要用于监控和诊断，不是同步协议：

```java
if (worker.getState() == Thread.State.WAITING) {
    // 下一条指令执行前，worker 可能已经醒来
}
```

正确程序必须由 volatile/atomic 条件、锁、闩锁、Future 状态等机制保证先后；状态读取只能帮助验证“某个时刻大概停在哪条路径”。实验 Lab 之所以能稳定取样，是因为目标线程同时被一个未满足的业务谓词约束，并且所有轮询都有截止时间。

## JDK 17/21 与虚拟线程边界

JDK 17 的平台线程仍可按 OpenJDK 8 的主线理解，但内部字段、模块包名和 native 衔接会变化。JDK 21 增加虚拟线程后：

- `Thread.State` 仍只有六种，没有单独的 VIRTUAL 状态；
- 虚拟线程 start 后由调度器安排在 carrier 上运行，不等于创建一条长期绑定的 OS 线程；
- 虚拟线程在可卸载的 park、阻塞队列等等待点通常释放 carrier；
- 被固定时，虚拟线程阻塞可能同时占住 carrier，需要用 JFR 和线程诊断确认；
- `getState()` 仍是快照，不提供调度器队列的完整状态。

跨版本稳定的阅读方法是先认公开状态和 API 契约，再进入对应版本的 Thread/VirtualThread 私有实现，不把 JDK 8 的 native 细节硬套到 JDK 21。

