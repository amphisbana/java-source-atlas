# FutureTask 等待栈、完成唤醒与取消

`FutureTask.get()` 的阻塞不是由 AQS 队列完成的。JDK 8 使用一个更窄的协议：每个等待线程创建 WaitNode，CAS 压入 Treiber 栈；状态完成者一次摘走整栈，再逐个 `unpark`。超时或中断退出的节点则由 `removeWaiter` 尽力清理。

## 动画：两个等待者如何被完成线程唤醒

主线固定展示一个执行线程和两个 get 等待者：W1 先压栈，W2 后压到栈顶；执行线程按 `COMPLETING -> outcome -> NORMAL` 发布结果，完成线程先摘下整栈，再分别给 W2、W1 发放 unpark 许可。最后两步切换到两个独立的新任务，对比 `cancel(false)` 与 `cancel(true)`，它们不是 NORMAL 之后的状态迁移。

<FutureTaskAnimation />

动画中的 unpark 顺序来自本例的 Treiber 链，不代表线程实际恢复顺序。任何等待者被调度后都必须重新读取 state；虚假唤醒和提前获得许可都不会绕过状态检查。

## get 先读 state 再决定是否等待

无超时 `get()` 的骨架是：

```text
s = state
if (s <= COMPLETING)
  s = awaitDone(false, 0)
return report(s)
```

因为 NEW=0、COMPLETING=1，所以尚未完成或正在发布 outcome 都进入 `awaitDone`。取消状态数值大于 COMPLETING，会直接进入 report 并抛 CancellationException。

定时版本把预算换算为纳秒：

```text
s = state
if (s <= COMPLETING && awaitDone(true, nanos) <= COMPLETING)
  throw TimeoutException
return report(s)
```

超时不是终态。它只表示当前调用者在预算内没有观察到终态，FutureTask 的 state 不会因此改变。随后仍可由其他线程完成、取消或再次等待。

## awaitDone 的完整循环

JDK 8 的循环按下面顺序处理每一轮：

1. 用 `Thread.interrupted()` 检查并清除当前线程的中断标记；若已中断，清理节点并抛 `InterruptedException`。
2. 读取 state；大于 COMPLETING 表示已到终态，清空本节点 thread 并返回状态。
3. state 等于 COMPLETING 时 `Thread.yield()`，等待 outcome 发布，不创建新等待节点，也不按超时提前返回。
4. 尚未创建节点时，创建记录当前线程的 WaitNode。
5. 节点尚未入栈时，设置 `q.next = waiters` 并 CAS 更新栈顶；失败则读取新栈顶后重试。
6. 定时等待重新计算剩余预算，耗尽时 removeWaiter 并返回当前 state。
7. 预算未耗尽时 `parkNanos(this, nanos)`；无超时版本调用 `park(this)`。
8. park 返回后进入下一轮，重新检查中断、状态和剩余时间。

这里没有一次 park 对应一次 unpark 的强假设。`LockSupport` 允许先 unpark 后 park，park 也允许无原因返回；循环中的 state 检查才是正确性来源。

### COMPLETING 为什么只 yield

进入 COMPLETING 表示某个线程已经通过 CAS 赢得结果发布权，接下来只剩普通字段 outcome 写入和唯一终态发布。此时把当前线程压入 waiters 可能赶不上已经开始的 finishCompletion，而直接 report 又可能读到尚未发布的 outcome，所以源码短暂 yield 并重读 state。

JDK 8 定时等待在 COMPLETING 期间不会因本轮预算耗尽而返回 TimeoutException。这个中间状态应非常短；如果用反射或调试器长期冻结完成线程，观察到的超时行为不能代表正常运行契约。

## WaitNode Treiber 栈如何压入

每个 WaitNode 只包含两个 volatile 字段：

| 字段 | 含义 |
| --- | --- |
| `thread` | 等待 get 的线程；置 null 表示节点失效或已处理 |
| `next` | 指向压栈前的旧栈顶 |

以 W1 先等待、W2 后等待为例：

```text
初始：waiters = null
W1：  waiters -> W1 -> null
W2：  waiters -> W2 -> W1 -> null
```

CAS 失败只说明另一个线程先改变了 waiters，当前节点可以更新 next 后重试。这个栈不提供 FIFO 公平性；它只需要让并发等待者都能被完成线程找到。

完成时 `finishCompletion` 先 CAS `waiters: head -> null`，所以它后续遍历的是局部变量保存的旧链。共享字段已经清空，完成之后调用 get 的线程看到终态会直接 report，不再入栈。

## finishCompletion 如何逐个唤醒等待者

取得旧链后，完成线程从旧栈顶向 next 遍历：

```text
t = q.thread
if (t != null):
  q.thread = null
  unpark(t)
next = q.next
q.next = null
q = next
```

清空 thread 防止节点继续保留线程对象，断开 next 帮助整条链尽早回收。unpark 发放的是一个许可：

- 线程已经 park 时，许可让它有资格恢复。
- 线程正在入栈后、park 前的窗口中，许可会保留，随后 park 可以立即返回。
- 线程恢复后仍需重读 state，不能把 unpark 当成结果传递。

遍历顺序在本例中是 W2 再 W1，但实际恢复顺序可能是 W1、W2，也可能完成线程继续执行 done 后它们才获得 CPU。业务不能用多个 get 等待者的返回顺序表达公平性。

## removeWaiter 如何清理退出节点

等待线程因中断或超时退出时，先把自己的 `node.thread` 置 null，形成逻辑删除。然后 `removeWaiter` 从当前 waiters 栈顶重新扫描：

- 仍有 thread 的节点成为当前 pred。
- 内部失效节点通过 `pred.next = successor` 跳过。
- 栈顶失效节点用 CAS 把 waiters 从该节点改为 successor。
- 若发现 pred 同时被其他线程置为失效，或栈顶 CAS 失败，则从头重试。

内部 next 修补不使用 CAS，因为即使完成线程并发遍历到已失效节点，也只会看到 thread=null 并跳过，正确性不依赖链立即变得最短。重试逻辑的目标是避免在超时和中断频繁发生时长期累积无效节点，不是向业务提供精确队列长度。

## 中断等待者与中断执行者不是一回事

两个中断方向必须分开：

| 场景 | 被中断线程 | FutureTask state | 结果 |
| --- | --- | --- | --- |
| 等待 `get()` 的线程被外部 interrupt | 等待者 | 通常不变 | removeWaiter，抛 InterruptedException |
| 调用 `cancel(true)` | 尝试中断 runner | 进入 INTERRUPTING/INTERRUPTED | FutureTask 对外取消 |

中断一个 get 等待者不会取消 FutureTask。反过来，取消 FutureTask 会让所有 get 等待者最终抛 CancellationException；它们不是因为自身被 interrupt 才退出。

## cancel false 和 cancel true 的准确边界

两个取消入口都只能从 NEW 竞争：

```text
cancel(false): CAS NEW -> CANCELLED

cancel(true):  CAS NEW -> INTERRUPTING
               if runner != null: runner.interrupt()
               ordered write -> INTERRUPTED

共同收尾：finishCompletion()
```

### cancel false

成功后直接进入 CANCELLED，不读取 runner，也不发送中断。即使 Callable 已经在运行，因为 state 在普通运行阶段仍是 NEW，cancel(false) 仍可能赢得 CAS：

1. FutureTask 立即对外显示已取消。
2. get 抛 CancellationException。
3. 已运行 Callable 可以继续产生业务副作用。
4. Callable 返回后的 set CAS 失败，结果被丢弃。

所以 `false` 的含义不是“只能取消尚未开始的任务”，而是“取消时不要尝试中断正在执行的线程”。

### cancel true

成功后先进入 INTERRUPTING，再读取 runner 并调用 `interrupt()`，最后无论 runner 是否存在都发布 INTERRUPTED。`true` 表示允许 FutureTask 发出中断请求，不表示计算已经停止：

- Callable 阻塞在可中断方法并正确响应 InterruptedException 时，通常能尽快结束。
- Callable 主动轮询中断标记并退出时，可以协作停止。
- Callable 忽略中断、清除中断后继续、执行不可中断 I/O 或陷入无限计算时，仍可继续运行。
- state 已经是取消终态，之后产生的值或异常都不能替代 CancellationException。

因此不能把 `cancel(true)` 写成“强制终止线程”。Java 没有在这里使用危险的 `Thread.stop()`；资源释放、幂等性和外部副作用仍由任务代码负责。

## handlePossibleCancellationInterrupt 防止什么

考虑取消线程已经把 state 改成 INTERRUPTING，却在调用 runner.interrupt 之前被暂停；与此同时 Callable 正好返回并进入 run 的 finally。如果 runner 立刻清空并开始执行线程池中的下一个任务，迟到的 interrupt 可能击中下一项工作。

`run` 清空 runner 后重新读取 state。若仍是 INTERRUPTING，`handlePossibleCancellationInterrupt` 循环 yield，直到取消线程发布 INTERRUPTED，确认这次中断动作已经结束后才退出 run。

JDK 8 源码刻意没有调用 `Thread.interrupted()` 清除标记，因为无法区分这个标记来自 cancel，还是任务与调用方约定的独立中断信号。FutureTask 负责关闭“中断动作尚未发生”的窗口，不承诺把工作线程中断状态恢复为 false。

## 取消与完成竞争时谁赢

取消、正常完成和异常完成都竞争同一个 `NEW`：

| 首个成功 CAS | 最终状态 | 另一方行为 |
| --- | --- | --- |
| `set: NEW -> COMPLETING` | NORMAL | cancel 返回 false |
| `setException: NEW -> COMPLETING` | EXCEPTIONAL | cancel 返回 false |
| `cancel(false): NEW -> CANCELLED` | CANCELLED | set/setException 不再写 outcome |
| `cancel(true): NEW -> INTERRUPTING` | INTERRUPTED | set/setException 不再写 outcome |

这才是取消结果的线性化边界。调用 cancel 的墙钟时间“看起来接近 Callable 返回”不足以判断谁赢，必须看 state CAS 的实际顺序。

## 超时、取消和任务截止时间的区别

| 机制 | 改变 FutureTask 状态 | 尝试中断 runner | 适合表达 |
| --- | --- | --- | --- |
| `get(timeout)` 超时 | 否 | 否 | 调用方最多等待多久 |
| `cancel(false)` | 是 | 否 | 结果不再需要，但不打断执行 |
| `cancel(true)` | 是 | 是 | 结果不再需要，并请求协作停止 |
| Callable 内部截止时间 | 由 Callable 最终结果决定 | 任务自行处理 | 一整条 I/O 或计算链的时间预算 |

生产系统常常需要组合这些机制：调用方超时后决定是否 cancel，Callable 同时把剩余预算传给网络和数据库客户端。只给 `get` 加超时并不能阻止后台任务继续占用线程或产生副作用。

## JDK 17 和 21 的等待实现差异

JDK 17/21 仍使用 WaitNode Treiber 栈、LockSupport 和 removeWaiter，但通过 `WAITERS.weakCompareAndSet` 操作栈顶，并细化定时等待计算，避免 `deadline = now + nanos` 的溢出边界和不必要的 nanoTime 调用。

较新实现还调整了循环检查顺序：优先识别已经完成或正处于 COMPLETING 的 state，再处理中断。调试跨版本竞争时，某个极窄边界里是先观察完成还是先抛 InterruptedException 可能不同；应用应依赖 Future 接口允许的并发结果，不要把私有循环分支顺序写成业务契约。

JDK 21 的虚拟线程可以停放在 LockSupport 上，降低大量等待线程的资源成本，但不会改变取消的协作性质，也不会让无截止时间的等待自动变安全。
