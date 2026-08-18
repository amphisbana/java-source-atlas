# Thread / LockSupport 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.concurrent.ThreadLockSupportDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ThreadLockSupportDebugLab
```

自动测试：

```bash
mvn -pl labs/jdk-labs -Dtest=ThreadLockSupportBehaviorTest test
```

实验只使用 Java 8 已公开 API，可以在 JDK 8、17、21 分别运行。所有线程协调都使用闩锁、原子条件、真实状态轮询和有界 join；没有用 sleep 猜测某个线程“应该已经”运行到哪一步。

## 推荐断点

| 源码位置 | 观察内容 | 目标 |
| --- | --- | --- |
| `Thread.start` | `threadStatus`、ThreadGroup 登记 | 区分一次启动检查与普通 run 调用 |
| `Thread.start0` | native 边界 | 确认 Java 代码在何处交给 VM |
| `Thread.run` | 当前线程、`target` | 对比直接 run 与 start 后 run |
| `Thread.interrupt` | blocker、内部中断入口 | 观察请求如何到达 park 线程 |
| `Thread.interrupted/isInterrupted` | ClearInterrupted 语义 | 对比读取并清除与只读查询 |
| `LockSupport.park(Object)` | 当前 Thread、blocker、Unsafe.park | 观察 blocker 设置和清除 |
| `LockSupport.unpark` | 目标 Thread | 验证通知可以先于 park |
| `LockSupport.getBlocker` | 目标 parkBlocker | 在 WAITING 期间读取诊断对象 |
| `Unsafe.park/unpark` | VM 调用边界 | 只观察，不依赖内部实现写业务代码 |

调试 JDK 源码时，IDE 应关联当前运行时对应的 src.zip。JDK 8 与 17/21 的内部包、字段访问和虚拟线程路径不同；源码版本与实际 JVM 不一致时，断点可能灰掉或变量对不上。

## 实验一：run 与 start 的执行线程

`observeRunAndStart()` 对同一个 Thread 对象依次执行：

1. 记录初始状态 NEW。
2. 由 main 直接调用 `worker.run()`。
3. 确认 target 在 main 执行，worker 仍为 NEW。
4. 调用 `worker.start()` 并有界 join。
5. 确认 target 在 `atlas-start-worker` 再执行一次。
6. 对已经 TERMINATED 的对象第二次 start，捕获 IllegalThreadStateException。

预期输出结构：

```text
调用线程=main，run 执行线程=main，start 执行线程=atlas-start-worker
状态=NEW -> run 后 NEW -> start/join 后 TERMINATED，执行次数=2，二次 start 被拒绝=true
```

在 `Thread.run` 断点观察 `Thread.currentThread()`：第一次不是 `this` 指向的 worker，第二次才是。

## 实验二：稳定观察六种 Thread.State

`observeThreadStates()` 使用三条短生命周期线程：

- lifecycle 线程先在原子条件上自旋，稳定保持 RUNNABLE；再进入带 blocker 的 park，稳定保持 WAITING；释放后结束为 TERMINATED。
- blocked 线程尝试进入由 main 持有的 monitor，稳定保持 BLOCKED。
- timed 线程执行一个较长上限的 parkNanos，稳定保持 TIMED_WAITING；main 取样后立即 unpark，不等待自然超时。

NEW 在 start 前读取。六个预期值为：

```text
NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED
```

状态只是诊断快照。实验的正确性来自原子条件和 monitor 所有权，不来自 `getState()` 本身。

## 实验三：unpark-before-park 与一位 permit

`observeOneBitPermit()` 的目标线程已经启动并在原子开关上运行，main 执行：

```text
unpark(worker)
unpark(worker)
允许 worker 进入 park
```

worker 第一次 park 消费预发许可并返回。随后它在 `released == false` 的循环中第二次 park；main 等到它真实进入 WAITING，并通过 `getBlocker` 看到 `permit-slot`。这证明连续两次 unpark 没有积累两个许可。

最后 main 先设置 released，再补发一次 unpark，worker 重检条件并退出。即使运行时发生合法伪唤醒，worker 也只记录次数并继续循环，不会误判通知。

这个实验不能把“第一次很快返回”写成毫秒阈值断言。调度暂停可能让任何正确代码耗时变长；我们只用有界闩锁证明它在没有后续 unpark 的情况下完成。

## 实验四：blocker 与中断标记

`observeInterruptAndBlocker()` 让 worker 执行：

```java
LockSupport.park(blocker);
boolean before = Thread.currentThread().isInterrupted();
boolean consumed = Thread.interrupted();
boolean after = Thread.currentThread().isInterrupted();
```

main 等到 worker 为 WAITING 后：

1. `LockSupport.getBlocker(worker)` 返回实验 blocker。
2. 调用 `worker.interrupt()`。
3. park 返回，worker 的 `isInterrupted()` 为 true。
4. `Thread.interrupted()` 返回 true 并清除标记。
5. 下一次 `isInterrupted()` 为 false。
6. park 返回后 blocker 已恢复为 null。

如果在 `Thread.interrupted()` 之后停住，不要误以为中断请求从未发生；这个静态方法的职责就是消费当前标记。

## 实验五：parkNanos 截止时间循环

`observeTimedPark()` 请求至少等待 40ms，但不把一次 parkNanos 返回当作超时完成：

```text
deadline = nanoTime + 40ms
while ((remaining = deadline - nanoTime) > 0)
  parkNanos(blocker, remaining)
```

若发生伪唤醒，remaining 仍为正，线程再次 park。测试只验证：

- 至少调用一次 parkNanos；
- 截止时间循环覆盖请求时长；
- 没有依靠外部 unpark 完成；
- 没有意外中断；
- 返回后 blocker 已清空。

40ms 不是性能指标，只是让实验足够快又能稳定进入定时等待。生产代码应根据业务 SLA 计算 deadline，并正确处理纳秒换算与超时结果。

## 为什么清理同时使用 interrupt 与 unpark

每个实验 finally 都先把业务退出条件设为 true，再对仍存活线程发送 interrupt 和 unpark，最后做有界 join：

```text
发布退出条件
  -> interrupt：覆盖 sleep/wait/中断检查路径
  -> unpark：覆盖 LockSupport 停放路径
  -> join(timeout)：确认没有线程泄漏
```

这不是正常业务通知协议，而是测试失败时的兜底。正常路径仍坚持“先发布条件，再发对应通知”。

## 调试注意

- 断在 park 前后会改变调度窗口，但不会改变 permit 最多一位的契约。
- 不要在 debugger 中求值会获取业务锁的方法，可能制造额外阻塞。
- `getState` 和 `getBlocker` 都是快照，只用于定位，不作为生产同步条件。
- 线程处于 WAITING 时优先只挂起当前线程；挂起全部线程会让通知方无法 unpark。
- JDK 21 运行虚拟线程实验时，平台线程的 `Unsafe.park` 断点不一定覆盖虚拟线程调度路径，应同时查看 VirtualThread/调度器实现。

## 与上层专题串联的断点顺序

完成本实验后，可按下面顺序进入真实同步器：

1. [AQS](../locks/aqs.md)：从 `shouldParkAfterFailedAcquire` 进入 `parkAndCheckInterrupt`。
2. [FutureTask](../futuretask/debug-lab.md)：从 `awaitDone` 观察 WaitNode 入栈后 park。
3. [ThreadPoolExecutor](../threadpoolexecutor/debug-lab.md)：从 Worker 的 `getTask` 进入阻塞队列，再进入 Condition/AQS。

每次都同时观察“业务状态/队列状态”和“park permit”。只看 LockSupport 会漏掉谁有资格等待和醒来；只看上层状态又会不清楚线程为什么真正停止执行。

