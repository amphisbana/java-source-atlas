# ForkJoinTask：fork、执行、join 与异常

`ForkJoinTask<V>` 是比普通 `FutureTask` 更贴近工作窃取调度的任务抽象。它既保存完成状态和结果，又让 pool 在 join 期间主动执行相关工作，而不是一开始就把 worker 停住等待。

## fork 只负责安排，不等待

JDK 8 的 `fork()` 根据当前线程选择目标 pool：

```text
当前线程是 ForkJoinWorkerThread
  → currentWorker.workQueue.push(this)

其他线程
  → ForkJoinPool.commonPool().externalPush(this)

返回 this
```

所以 `fork()` 不等同于“新建线程”，也不保证任务立刻开始。它只是把任务放到适当队列，返回自身是为了允许 `task.fork().join()` 这种写法，但该写法不一定高效。

要把根任务放进自建 pool，应使用：

```java
ForkJoinPool pool = new ForkJoinPool(4);
long result = pool.invoke(new RangeSumTask(...));
```

在这个根任务内部调用 `fork()`，子任务才沿用当前自建 pool。

## doExec 如何提交完成状态

`doExec()` 是 pool 执行任务的内部入口：

```text
读取 status
若尚未完成：
  调用 exec()
  ├─ 抛 Throwable → setExceptionalCompletion(error)
  └─ 返回 true   → setCompletion(NORMAL)
返回最新 status
```

`RecursiveTask.exec()` 会调用 `compute()`，把返回值保存到 raw result 并返回 true。`CountedCompleter.exec()` 的完成方式不同，可能需要 pending count 协议，所以不能把所有 ForkJoinTask 都简化为“compute 返回就完成”。

JDK 8 用 status 的高位编码 NORMAL、CANCELLED、EXCEPTIONAL 等终态，并在等待信号位存在时 `notifyAll`。这些私有数值和位布局在后续版本已改写，不是业务协议。

## 推荐的 fork 一侧、计算一侧

```text
Right.fork()
Left.compute()      // 当前 worker 不空转
Right.join()
合并结果
```

可能出现两条路径：

### Right 尚未被窃取

`doJoin()` 先尝试 `tryUnpush(Right)`。如果 Right 正好位于当前 WorkQueue 的 top，owner 把它撤回并直接 `doExec()`，无需真正阻塞。

### Right 已被其他 worker 窃取

本地撤回失败，进入 pool 的 `awaitJoin`。等待 worker 不会立即 park，而会尝试定位正在执行目标或其后代的 stealer，并执行相关子任务帮助目标完成。

这就是 join 和普通阻塞 Future 等待的核心差异：joiner 本身仍是计算资源。

## doJoin 的完整决策

JDK 8 主线可以概括为：

```text
任务已完成
  → 直接返回 status

当前线程是 ForkJoin worker
  → tryUnpush(this)
      ├─ 成功：doExec(this)，直接完成目标
      └─ 失败：pool.awaitJoin(workQueue, this, 0)

当前线程不是 ForkJoin worker
  → externalAwaitDone()
      ├─ commonPool 中仍可撤回目标时，当前线程执行它
      ├─ CountedCompleter 可尝试 externalHelpComplete
      └─ 否则等待完成通知
```

外部 joiner 没有当前自建 pool 的 WorkQueue，不能假定它会参与该 pool 的所有帮助路径。任务本身也不保存一个通用的“所属 pool”公开引用。

## awaitJoin 如何先帮助再阻塞

worker 进入 `awaitJoin` 后把目标记入 `currentJoin`，便于其他帮助者追踪依赖链：

1. 目标是 `CountedCompleter`：调用 `helpComplete` 执行同一完成树中的任务。
2. 普通任务：尝试从本地队列中找到并执行目标；必要时执行 `helpStealer`。
3. `helpStealer` 沿 `currentSteal -> currentJoin` 查找正在执行目标或后代的 worker。
4. 找到相关 worker 的可用任务时，从其 base 端取得并执行。
5. 目标仍未完成且没有可帮助工作时，尝试补偿活跃度后进入内部等待。
6. 目标完成后恢复之前的 `currentJoin`。

帮助算法不保证 joiner 一定执行目标的直接子任务，也不保证完全避免阻塞。它的目标是在依赖明确时尽量把等待者转换为有用计算。

## join 与 get 的异常差异

| API | 中断语义 | 计算异常 |
| --- | --- | --- |
| `join()` | 不声明 `InterruptedException`；外部等待中断不会让它以受检异常退出 | 重新抛出原类型的 RuntimeException 或 Error |
| `get()` | 外部非 worker 等待可被中断 | 用 `ExecutionException` 包装原因 |
| `invoke()` | 当前线程先尝试执行，再等待 | 与 join 类似，重新抛 unchecked 异常 |
| `quietlyJoin()` | 等待但不返回结果，也不报告任务异常 | 调用方之后自行检查状态 |

JDK 8 为了让跨线程 join 尽量保留有用堆栈，把异常记录在全局弱引用表中，并可能重建同类型异常。JDK 17/21 改写了异常辅助结构。稳定契约是 join 报告 unchecked 异常、get 报告 `ExecutionException`；不要断言异常对象身份或拼接后的完整堆栈文本。

## join 提供内存可见性

任务文档要求：在 fork 后对子任务数据的修改，若没有 join、相关完成查询或其他同步，不保证被另一线程一致观察。正常的模式是：

```text
初始化子任务输入
  → fork 发布任务
  → 子任务执行并写结果
  → join 观察完成
  → 读取结果与子任务先前动作
```

不要把共享可变容器的并发写入安全性寄托在“最后会 join”。join 建立任务完成后的观察边界，不会让执行期间的数据竞争自动变安全。

## invokeAll 的两个任务策略

JDK 8 的 `ForkJoinTask.invokeAll(t1, t2)` 不是简单地同时 fork 两个再等待：

```text
t2.fork()
t1.doInvoke()   // 当前线程执行 t1
t2.doJoin()
```

这与“fork 一侧、执行一侧、join”模式一致，但异常处理存在版本边界：

- **JDK 8u**：若 `t1.doInvoke()` 抛出异常，方法会立即向调用者报告，不会等待或取消已经 fork 的 `t2`；`t2` 仍可能在后台执行。若 `t1` 正常完成，才会进入 `t2.doJoin()` 并报告 `t2` 的结果或异常。
- **JDK 17/21**：两参数实现会记录主路径异常；若 `t1` 失败，会对 `t2` 调用内部取消辅助方法，若 `t1` 成功才 join `t2`。取消仍是状态竞争，不表示 `t2` 的用户代码必然从未开始。

因此不能跨版本依赖“一个失败时另一个必然被取消”。需要掌握两个任务的最终状态时，应分别保存引用并查询结果，同时保证任务本身可以安全地独立完成。

## 阈值为什么决定性能

拆分太粗：可窃取任务少，CPU 利用不足。

拆分太细：创建任务、push/pop、状态 CAS、join 和递归调用的成本超过计算本身。

合理阈值取决于：

- 每个元素的计算成本；
- 数据局部性和缓存；
- pool parallelism 与同时存在的其他任务；
- 是否有阻塞或共享写入；
- 实际机器和 JDK 版本。

应通过基准测试选择阈值，不用固定公式把数组长度机械除以 CPU 数量。

## 常见错误

### 两边都 fork 再立即 join

会把当前 worker 本可直接完成的工作也排队，增加调度压力。优先 fork 一边并同步计算另一边。

### 在 compute 中等待普通 Future 或外部锁

pool 不一定知道这段阻塞，可能降低可用 parallelism。能改成任务依赖就用 join；必须阻塞时评估 `ManagedBlocker`，见下一章。

### 让两个任务循环 join 对方

帮助执行不能修复逻辑依赖环。A 等 B、B 等 A 仍可能永远无法完成。

### 重复 fork 未完成的同一个任务

源码文档明确视为使用错误。任务完成并确保所有 join 结束后，只有非常受控的场景才考虑 `reinitialize`；业务通常应创建新任务。

下一步阅读 [ManagedBlocker：阻塞补偿的能力边界](./managed-blocking.md)。
