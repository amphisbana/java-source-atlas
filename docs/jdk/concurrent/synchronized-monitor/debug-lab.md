# synchronized / ObjectMonitor 断点实验

实验入口：

```text
labs/jdk-labs/src/main/java/io/github/javasourceatlas/jdk/concurrent/SynchronizedMonitorDebugLab.java
```

行为测试：

```text
labs/jdk-labs/src/test/java/io/github/javasourceatlas/jdk/concurrent/SynchronizedMonitorBehaviorTest.java
```

## 运行方式

```bash
mvn -pl labs/jdk-labs compile exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.SynchronizedMonitorDebugLab

mvn -pl labs/jdk-labs \
  -Dtest=SynchronizedMonitorBehaviorTest test
```

项目以 `--release 8` 编译；同一组测试应分别在 Java 8 与 Java 17 运行，验证公开行为不依赖某一代 HotSpot 的对象头布局。

## 实验一：同一 monitor 可重入

入口：`observeReentrantEntry()`。

代码在同一线程中嵌套两层 `synchronized (monitor)`，第二层仍能进入，`Thread.holdsLock(monitor)` 为 true。断点观察：

| 位置 | 变量 | 预期 |
| --- | --- | --- |
| 外层进入后 | `depth[0]` | 1 |
| 内层进入后 | `depth[0]` | 2 |
| 内层退出后 | `Thread.holdsLock(monitor)` | true，外层仍持有 |
| 外层退出后 | `Thread.holdsLock(monitor)` | false |

`holdsLock` 不显示重入层数；业务数组只用来标记代码执行深度。JDK 8 HotSpot 内部 `_recursions` 与 Java 深度相差首次持有的一层。

## 实验二：稳定区分 WAITING 与 BLOCKED

入口：`observeBlockedAndWaiting()`。

实验同时建立两组独立 monitor：

1. `monitor-waiter` 已取得 `waitMonitor`，在条件循环中执行 `wait()`，完整释放后进入 `WAITING`。
2. `monitor-owner` 持有 `blockedMonitor`，`monitor-contender` 尝试进入同一同步块，进入 `BLOCKED`。

主线程先用 CountDownLatch 确认代码已到达指定边界，再有限轮询 `Thread.State`。闸门负责同步，`getState()` 只负责观察；不要把轮询线程状态复制为生产同步协议。

在 IDE 线程视图中比较两条栈：

```text
monitor-waiter      WAITING  at java.lang.Object.wait(Native Method)
monitor-contender   BLOCKED  at ... synchronized entry
```

## 实验三：wait 完整释放并恢复两层重入

入口：`observeWaitReleaseAndReacquire()`。

时间线：

```text
waiter: enter outer
  → enter inner（Java 深度 2）
  → wait（保存深度 2，完整释放）
main: 成功进入同一 monitor
  → condition.open=true
  → notifyAll
  → exit
waiter: 重新取得并恢复深度 2
  → 从 wait 返回
  → 退出 inner（仍持有 outer）
contender: 尝试进入，状态 BLOCKED
waiter: 获准退出 outer
contender: 取得 monitor
```

这个场景同时给出两份证据：main 能在 wait 期间进入，证明释放不是只减一层；waiter 退出内层后 contender 仍 `BLOCKED`，证明返回时恢复了外层持有。

## 实验四：notify 后仍要等 notifier 退出

入口：`observeNotifyDoesNotReleaseMonitor()`。

`monitor-notifier` 在同步块内设置条件、调用 `notify()`，随后停在 `releaseNotifier` 闸门上，但它没有退出同步块。此时：

| 观察项 | 值 |
| --- | --- |
| `notifyCalled` | 已打开 |
| `condition.open` | true |
| notifier 是否仍 owner | 是 |
| waiter 状态 | `BLOCKED`，正在重新竞争 |
| `waiterResumed` | 尚未打开 |

主线程打开 `releaseNotifier` 后，notifier 才退出，waiter 取得 monitor、从 wait 返回并打开 `waiterResumed`。

## 实验五：中断 wait

入口：`observeWaitInterruption()`。

目标线程进入 `WAITING` 后由主线程调用 `interrupt()`。最终输出应满足：

```text
捕获 InterruptedException=true，catch 中中断标记=false
```

这验证 `wait` 抛异常前会清除中断状态。实验 catch 只为展示这一瞬间；真实业务若不能向上抛出，通常应 `Thread.currentThread().interrupt()` 恢复取消信号。

## JDK 8 HotSpot 源码断点

调试 JVM 本身需要可调试构建，普通应用 IDE 不能直接在 C++ 源码下断点。推荐入口：

| 方法 | 场景 | 重点字段 |
| --- | --- | --- |
| `ObjectSynchronizer::inflate` | 首次因竞争或 wait 膨胀 | `object`, mark, monitor |
| `ObjectMonitor::enter` | owner 为空、重入、竞争三条路径 | `Self`, `_owner`, `_recursions` |
| `ObjectMonitor::EnterI` | contender 进入慢路径 | `_cxq`, `_EntryList`, `_succ` |
| `ObjectMonitor::exit` | 递减重入或最终释放 | `_owner`, `_recursions`, `_EntryList` |
| `ObjectMonitor::wait` | 两层重入 wait | `save`, `_WaitSet`, `_recursions` |
| `ObjectMonitor::notify` | 单等待者通知 | `_WaitSet`, `Iterator`, notifyee |

如果只调试 Java Lab，在 `Object.wait` native 边界前后、同步块入口和各闸门处下断点即可，不需要读取私有 VM 地址。

## 预期输出

```text
=== synchronized 可重入 ===
第二次进入同一 monitor，业务深度=2，holdsLock=true

=== BLOCKED 与 WAITING 是两种等待 ===
waiter=WAITING（条件队列），contender=BLOCKED（入口竞争）

=== wait 完整释放并恢复重入层数 ===
wait 返回并退出内层后 contender=BLOCKED，说明外层重入仍由 waiter 持有

=== notify 不会释放 monitor ===
notify 已调用但 notifier 仍持锁：waiter=BLOCKED，是否已继续=false

=== 中断 wait 的收口语义 ===
捕获 InterruptedException=true，catch 中中断标记=false
```

线程名字、对象地址和调度耗时不是断言；状态边界与闸门结果才是本实验验证的公开行为。
