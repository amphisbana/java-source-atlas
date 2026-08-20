# ThreadPoolExecutor：关闭与拒绝策略

版本边界可回看 [JDK 8 / 17 / 21 版本对比](/jdk/version-comparison/?topic=thread-pool-executor)。公开的 `shutdown`、`shutdownNow`、拒绝策略和 `awaitTermination` 语义保持稳定，变化集中在 `tryTerminate` 的内部状态谓词和终止时的线程容器清理。

## shutdown 是有序关闭

`shutdown()` 把状态推进到 `SHUTDOWN`，中断空闲 worker，不再接收新任务，但继续执行队列中已经接收的任务。

“中断空闲 worker”依赖 Worker 自己的 AQS 锁：`interruptIdleWorkers` 在 `mainLock` 内遍历 workers，只有 `w.tryLock()` 成功才调用线程的 `interrupt()`。正在执行任务的 Worker 持有工作锁，`tryLock` 失败；阻塞在 `workQueue.take()` 的 Worker 已释放锁，会被中断后返回 `getTask` 顶部重新检查状态。详细状态变化见 [Worker 与任务循环](./worker.md#worker-为什么继承-aqs)。

方法调用本身不会等待任务完成。需要等待时：

```java
pool.shutdown();
if (!pool.awaitTermination(timeout, unit)) {
    List<Runnable> notStarted = pool.shutdownNow();
}
```

## shutdownNow 是尽力停止

`shutdownNow()` 推进到 `STOP`，尝试中断所有 worker，并清空队列返回尚未开始的任务。

Java 无法安全强制停止不响应中断的任务。因此“Now”不是立即结束保证：任务代码必须正确处理中断，阻塞 API 也必须支持中断，线程池才能及时终止。

`shutdownNow` 可以把已经处于 `SHUTDOWN` 的池继续推进到 `STOP`；runState 只前进、不回退。它返回的是从工作队列排出的、尚未开始的 Runnable，不包含已经被 Worker 取走但尚未响应中断的任务。

不要再把 JDK 8 的 `finalize()` 当作关闭兜底：JDK 17/21 的同名方法为空，JDK 21 还标记为 `forRemoval`。JDK 19 起 `ExecutorService` 继承 `AutoCloseable` 并提供默认 `close()`；它先执行有序 `shutdown`，持续等待终止，被中断后才调用 `shutdownNow`，最后恢复中断标记。面向 JDK 19+ 可以使用 try-with-resources，兼容 JDK 8/17 则仍需在明确的 `finally` 或容器生命周期回调中调用 `shutdown`，并以 `awaitTermination` 确认收口。

## tryTerminate 如何收口

多个路径都会调用 `tryTerminate`。只有满足以下条件才推进到 `TIDYING`：

- 已不是 RUNNING；
- 不是“SHUTDOWN 且队列仍有任务”；
- workerCount 已为 0。

成功 CAS 到 `TIDYING` 的线程在主锁内调用 `terminated()`，随后设置 `TERMINATED` 并唤醒所有等待 `awaitTermination` 的线程。

如果已经具备终止资格但 workerCount 仍非 0，`tryTerminate` 每次最多中断一个空闲 Worker。这个“单个唤醒继续传播”的策略足以让等待队列的 workers 依次退出，同时避免每个完成路径都重复中断全部线程。任何可能让终止条件成立的动作都要重新调用它，包括 worker 退出、关闭操作，以及 `remove` 在 SHUTDOWN 状态下移走最后一个排队任务。

JDK 8 的源码把“SHUTDOWN 且队列非空”写成对 `SHUTDOWN` 的精确比较；JDK 17/21 改成 `runStateLessThan(c, STOP) && !workQueue.isEmpty()` 的状态范围判断。在先排除 RUNNING、TIDYING 和 TERMINATED 后，五态模型里这两个条件逻辑等价，只是后一种写法更直接利用状态数值顺序，并没有扩大可以排空队列的状态范围。阅读时不要只搜索 `SHUTDOWN` 字面量，应该同时观察 `runState`、队列 `isEmpty()` 和 `workerCount`。

## 四种内置拒绝策略

| 策略 | 行为 | 主要风险 |
| --- | --- | --- |
| `AbortPolicy` | 抛出 `RejectedExecutionException` | 调用方必须处理异常 |
| `CallerRunsPolicy` | 未关闭时由提交线程执行 | 提交线程延迟增加，但可形成自然背压 |
| `DiscardPolicy` | 静默丢弃新任务 | 无监控时可能无声丢业务 |
| `DiscardOldestPolicy` | 丢弃队首任务后重试提交 | 破坏等待顺序；优先队列中“最旧”含义可能不同 |

拒绝不仅发生在“线程和队列都满”时；线程池关闭后提交任务也会进入拒绝策略。

还有一个容易漏掉的并发拒绝：提交线程先在 RUNNING 快照下 `offer` 成功，关闭线程随后推进到 SHUTDOWN，提交线程复查发现不再运行并成功 `remove` 自己的任务。这时同样调用拒绝策略；若任务已经被 Worker 取走，`remove` 失败，就不能再拒绝一个已经开始执行的任务。

## 自定义拒绝策略

自定义 `RejectedExecutionHandler` 应明确：

- 任务能否丢失；
- 是否允许调用线程阻塞；
- 如何记录指标和任务身份；
- 线程池已关闭时如何处理；
- 是否会递归提交导致新的拒绝。

不要只打印日志后吞掉关键业务任务。

## 参数不是孤立调优

线程数、队列容量、任务耗时、到达速率和拒绝策略共同决定系统行为：

```text
持续到达速率 > 持续处理速率
  → 要么队列增长
  → 要么触发拒绝/背压
  → 仅增加最大线程数不能消除容量上限
```

有界队列和可观测拒绝通常比无界积压更容易保护系统。CPU 密集与 I/O 密集任务的线程数选择也不同，应通过真实负载测量。

## JDK 版本边界

JDK 8、17、21 的 `ctl` 状态机、execute 三步决策、Worker 循环和 shutdown 语义总体稳定。JDK 21 快照包含自 JDK 19 引入的 `ExecutorService.close()`，但它只是把显式关闭与等待组合成公共默认方法。平台线程实现和内部辅助代码会继续演进；虚拟线程应使用面向每任务执行的适配执行器，不应简单塞进传统固定池并沿用旧调优公式。
