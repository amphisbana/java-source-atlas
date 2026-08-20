# AQS 与 ReentrantLock 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.lock.ReentrantLockDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.lock.ReentrantLockDebugLab
```

## 跨版本运行边界

Lab 以 `--release 8` 编译，同一组公开行为测试应在 JDK 8、17、21 各运行一次：

```bash
mvn -pl labs/jdk-labs -Dtest=ReentrantLockBehaviorTest test
```

| 版本 | 优先定位的入口 | 不应直接照搬的 JDK 8 名称 |
| --- | --- | --- |
| JDK 8 | `addWaiter`、`acquireQueued`、`doAcquireShared`、`transferForSignal` | 无 |
| JDK 17 | 统一 `acquire`、`cleanQueue`、`ConditionNode.enableWait/doSignal` | `waitStatus`、`acquireQueued`、`transferForSignal` |
| JDK 21 | 统一 `acquire`、`cleanQueue`、`ConditionNode`，必要时查看 OOME 退避分支 | JDK 8 的 Node 常量和字段布局 |

三版都必须保持：重入层数完整恢复、取消后后继最终可获取、`signal` 后仍需重新获取锁、CountDownLatch 归零后共享传播、Semaphore 许可数不为负。队列长度、节点地址、私有字段名和某次唤醒的精确顺序不属于跨版本断言。

## 推荐断点

| 类与方法 | 观察变量 | 目标 |
| --- | --- | --- |
| `ReentrantLock.NonfairSync.lock` | `state`、owner | 非公平快速 CAS |
| `Sync.nonfairTryAcquire` | `c`、`current`、owner | 首次获取与重入 |
| `FairSync.tryAcquire` | `c`、`hasQueuedPredecessors()` | 两个线程真实排队后的公平判断 |
| `AQS.acquire` | `arg`、tryAcquire 结果 | 快慢路径分界 |
| `addWaiter` / `enq` | `head`、`tail`、`pred` | 同步队列入队 |
| `acquireQueued` | `p`、`node`、`failed` | 阻塞和被唤醒后的重试 |
| `AQS.release` | `state`、head 状态 | 完全释放与唤醒 |
| `ConditionObject.await` | `savedState`、节点状态 | 条件等待与重入恢复 |
| `transferForSignal` | 条件节点、同步队列 tail | signal 转移 |
| `AQS.acquireShared` / `doAcquireShared` | `r`、`Node.SHARED`、前驱 | 共享快速路径与入队等待 |
| `setHeadAndPropagate` | `propagate`、旧/新 head、后继模式 | 成功共享获取继续传播 |
| `AQS.releaseShared` / `doReleaseShared` | `ws`、head 是否变化 | SIGNAL 清零、PROPAGATE 与循环复查 |
| `CountDownLatch.Sync.tryReleaseShared` | `c`、`nextc` | 只有 count 归零才返回 true |
| `Semaphore.Sync.nonfairTryAcquireShared` | `available`、`remaining` | 扣减许可与返回值符号 |

## 实验一：可重入

运行 `observeReentrancy()`。同一线程连续两次 `lock`，观察 state 从 0 到 1 再到 2；第一次 `unlock` 后仍由当前线程持有，第二次才完全释放。

## 实验二：可中断获取

运行 `observeInterruptibleAcquire()`。主线程持锁，工作线程调用 `lockInterruptibly` 进入同步队列；中断后取消节点并退出等待。

## 实验三：Condition

运行 `observeConditionSignal()`。等待线程先连续调用两次 `lock()`，实验会同时打印和断言 `await` 前后 `holdCount` 都为 2：

1. 等待线程以 `state=2` 进入 `await`，`fullyRelease` 保存 2 并完全释放到 0。
2. 通知线程获得锁、修改业务条件并调用 `signal`。
3. 等待节点转入同步队列，但要等通知线程 unlock。
4. 等待线程通过 `acquireQueued(node, 2)` 恢复两层持有，检查条件后继续。

建议在 `ConditionObject.await` 观察 `savedState=2`，再在 `acquireQueued` 成功分支确认 state 已恢复到 2。若实验只重入一次，这两个位置都只能看到 1，无法验证“完整释放全部重入层数”的设计目的。

## 实验四：公平锁真实排队

运行 `observeFairQueuedAcquire()`：

1. 主线程先持有公平锁。
2. first 线程调用 `lock()`，实验用 `hasQueuedThread(first)` 确认它已入队。
3. second 线程随后调用 `lock()`，同样确认它排在同步队列中。
4. 主线程释放后，first 进入 `FairSync.tryAcquire` 并调用 `hasQueuedPredecessors()`；获得锁后暂不释放，使 second 仍停在队列中。
5. 放行 first 后，second 再按公平路径获得锁，最终顺序固定为 `first -> second`。

这比只检查 `isFair()==true` 多验证了真实竞争路径。公平性约束的是已有同步队列前驱，线程打印和 CPU 调度先后本身不能证明公平。

## 实验五：CountDownLatch 归零传播

运行 `observeCountDownLatchPropagation()`。三个线程在一个 `count=2` 的闩锁上调用 `await`：

1. 三个 `tryAcquireShared` 都返回 -1，以 SHARED 模式入队。
2. 第一次 `countDown` 让 state 从 2 到 1，`tryReleaseShared` 返回 false，没有线程通过。
3. 第二次 `countDown` 让 state 归零并进入 `doReleaseShared`。
4. W1 成功后在 `setHeadAndPropagate` 把传播交给 W2，最终三个线程全部通过。

建议给 `atlas-latch-waiter-*` 添加线程过滤，避免实验内部用于编排的其他 CountDownLatch 干扰断点。

## 实验六：Semaphore 许可传播

运行 `observeSemaphorePropagation()`。两个线程在公平 Semaphore 的 0 个许可上排队，主线程一次 `release(2)`：

1. `tryReleaseShared(2)` 把 state 增加到 2 并进入 `doReleaseShared`。
2. 第一个等待者扣减一个许可，返回 `remaining=1`，明确要求继续传播。
3. 第二个等待者取得最后一个许可，返回 0；实验结束时两个线程都通过且许可归零。

在 `Semaphore.Sync.nonfairTryAcquireShared` 或 `FairSync.tryAcquireShared` 观察 `available/remaining`，可以把返回值的符号与 AQS 传播决策直接对应起来。

## 调试注意

- AQS 断点容易同时命中 JVM 和测试框架内部锁，建议加类实例或线程过滤。
- 在持锁线程上挂起全部线程可能人为制造停顿。
- 不通过反射修改 state、head 或 tail；只观察它们的变化。
- Lab 的每个并发场景都会在 `finally` 中先打开闸门或释放主线程持有的资源，再中断并限时 join 工作线程；调试器长时间冻结单个线程仍可能让五秒保护超时。
- `getQueueLength()` 和线程恢复顺序只适合辅助观察；自动测试断言业务终态、明确入队状态和资源计数，不把近似队列长度当作协议。

运行定向自动测试：

```bash
mvn -pl labs/jdk-labs -Dtest=ReentrantLockBehaviorTest test
```

七个测试覆盖重入、错误 owner、中断获取、Condition state=2 恢复、公平排队、CountDownLatch 归零传播和 Semaphore 许可传播。
