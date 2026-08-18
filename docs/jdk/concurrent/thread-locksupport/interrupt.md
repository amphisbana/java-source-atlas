# 中断：请求、观察、清除与恢复

Java 中断不是异步杀死线程，也不是强制从任意方法跳出。它是一个协作式请求：发起方设置目标线程的中断状态；目标线程、阻塞 API 或上层同步器在明确检查点观察它，再决定抛异常、返回、清理资源或继续工作。

## 三个名字相近但职责不同的 API

| 调用 | 操作对象 | 是否修改标记 | 典型用途 |
| --- | --- | --- | --- |
| `worker.interrupt()` | 指定目标线程 | 设置中断请求；某些阻塞 API 会响应 | 请求取消或唤醒阻塞中的目标 |
| `worker.isInterrupted()` | 指定目标线程 | 只读取，不清除 | 外部诊断，或线程读取自身状态但保留请求 |
| `Thread.interrupted()` | 当前执行线程 | 读取并清除 | 在当前线程消费一次中断请求 |

最常见的错误是写成 `worker.interrupted()`，误以为它检查 worker。静态方法即使通过实例语法调用，检查的仍是当前执行线程；应始终写成 `Thread.interrupted()`，让语义显式。

## interrupt 的主路径

OpenJDK 8u 的 `Thread.interrupt()` 先处理访问检查和 blocker，再调用内部中断入口设置状态。公开语义比私有字段更重要：

```text
线程 A：worker.interrupt()
  -> 请求设置 worker 的中断状态
  -> 若 worker 停在可响应中断的阻塞点，使其有机会恢复执行

worker：
  -> API 抛 InterruptedException，或
  -> park 返回且标记保持 true，或
  -> 普通计算继续，直到显式查询标记
```

中断一个正在执行纯计算且从不检查状态的线程，只会把标记设为 true，不会让它自动退出。正确的长循环需要定义取消点：

```java
while (!Thread.currentThread().isInterrupted()) {
    processNextChunk();
}
```

检查频率应与取消延迟和计算成本匹配。不能在每条指令后检查，也不能让一次任务永久忽略请求。

## 不同阻塞 API 如何响应

| 场景 | 中断发生后的结果 | 中断标记 |
| --- | --- | --- |
| `Thread.sleep` | 抛 `InterruptedException` | 抛出时清除 |
| `Object.wait` | 重新取得 monitor 后抛 `InterruptedException` | 抛出时清除 |
| `Thread.join` | 等待方抛 `InterruptedException` | 等待方标记被清除；目标线程不受影响 |
| `LockSupport.park` | park 返回，不抛受检异常 | 保持 true |
| `ReentrantLock.lockInterruptibly` | 取消排队并抛 `InterruptedException` | 抛出时通常已清除 |
| `ReentrantLock.lock` | 不以异常取消；最终成功后恢复请求 | AQS 记录后重新设置 |
| 等待进入 `synchronized` | 不会因为中断取消 monitor 竞争 | 标记保持，获得 monitor 后代码继续 |

这解释了为什么同样是 WAITING，处理代码不同：sleep/wait/join 必须 catch；park 必须在返回后检查条件和中断状态。

### 中断发生在阻塞调用之前

- 标记已经为 true 时调用 sleep/wait/join，API 会很快抛 InterruptedException 并清除标记。
- 标记已经为 true 时调用 park，park 会很快返回，但标记仍为 true。
- 如果代码不清除标记又反复 park，后续 park 会持续立即返回，可能形成忙循环。

因此 park 循环必须同时定义“业务条件未满足”和“中断意味着什么”。

## 捕获 InterruptedException 后为什么常要恢复标记

如果当前方法无法完成取消，只能做清理后把决定权交给上层，应恢复中断：

```java
try {
    queue.take();
} catch (InterruptedException exception) {
    Thread.currentThread().interrupt();
    return;
}
```

InterruptedException 已经清除了标记。如果 catch 后既不退出、不抛出，也不恢复，上层就失去了取消信号，这通常称为“吞掉中断”。

但恢复不是机械规则。方法如果把 InterruptedException 原样抛出，调用方已经得到明确请求，无需先恢复；如果当前层就是取消协议的最终处理者并决定正常收口，也可以消费它。关键是 API 契约要说明谁拥有中断决策权。

## park 循环的三种中断策略

### 策略一：中断立即取消

```java
while (!ready) {
    LockSupport.park(this);
    if (Thread.interrupted()) {
        throw new CancellationException("等待被取消");
    }
}
```

这里 `Thread.interrupted()` 清除了标记，但取消异常成为新的显式结果。

### 策略二：不可中断等待，完成后恢复

```java
boolean interrupted = false;
while (!ready) {
    LockSupport.park(this);
    if (Thread.interrupted()) {
        interrupted = true;
    }
}
if (interrupted) {
    Thread.currentThread().interrupt();
}
```

AQS 的普通独占 `acquire` 使用类似思路：排队期间记录中断，成功取得同步状态后调用 `selfInterrupt` 恢复标记。它不会把“不可中断”误解成“永远丢弃中断”。

### 策略三：中断只是一次唤醒，状态仍由谓词决定

某些底层组件可能保留标记并继续循环，但要避免标记为 true 导致 park 持续立即返回。通常应把中断纳入退出条件，或者暂存并清除，完成后恢复。没有明确策略的 `while (!ready) park()` 在收到中断后可能空转。

## 中断与 happens-before

Java 内存模型规定：对线程调用 `interrupt()` happens-before 被中断线程检测到该中断，包括抛出 InterruptedException，或通过 `isInterrupted/interrupted` 观察到请求。

这条规则保证中断请求的检测顺序，但中断仍不适合作为通用结果发布协议。业务数据应通过 volatile、原子变量、锁、Future 完成状态或其他明确同步器发布；中断只表达取消/唤醒请求。把数据写入和取消请求混成一条隐式协议，会让正常完成、超时和取消难以区分。

## join 中断的是谁

```java
worker.join();
```

执行 join 的是当前线程，等待的对象是 worker。如果另一个线程中断当前等待方：

- 当前线程从 join 抛 InterruptedException；
- 当前线程的中断标记被清除；
- worker 不会因此被中断，仍可继续执行。

若要同时取消 worker，必须明确调用 `worker.interrupt()`，并由 worker 协作退出。join 只是等待终止，不是取消操作。

## 与 FutureTask 和线程池取消的关系

`FutureTask.cancel(true)` 的含义是：如果任务正在运行，尝试中断记录在 runner 字段中的执行线程。它不能保证 Callable 立即停止；Callable 必须通过 InterruptedException 或状态查询协作退出。FutureTask 自己已经进入取消终态，即使 Callable 忽略中断并继续计算，也不能再覆盖取消结果。

`ThreadPoolExecutor.shutdownNow()` 会尝试中断 Worker，并把尚未开始的队列任务返回给调用方。已经运行的任务是否结束，仍取决于任务是否响应中断。线程池不能安全地强杀任意用户代码。

## JDK 17/21 边界

公开中断契约在 JDK 8、17、21 中保持一致。变化主要在内部实现和新线程模型：

- JDK 17 强封装 JDK 内部字段，不应再用反射修改 Thread 的中断状态。
- JDK 21 虚拟线程支持同样的 interrupt/isInterrupted/interrupted 契约；中断会与虚拟线程调度器的 park/unpark 协作。
- 虚拟线程更便宜不代表可以忽略取消。海量任务若吞掉中断，仍会泄漏业务资源、占用队列或长时间固定 carrier。

调试时关注公开状态、异常和调用栈；不要把不同版本中的私有 `interrupted` 字段、native 方法或 carrier 唤醒细节当成应用协议。

