# ObjectMonitor：owner、入口竞争集合与 WaitSet

`ObjectMonitor` 是 JDK 8 HotSpot 在 monitor 膨胀后使用的数据结构，不是 `java.lang.Object` 中可访问的 Java 对象。字段定义位于 [`objectMonitor.hpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/runtime/objectMonitor.hpp)，主要流程位于 [`objectMonitor.cpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/runtime/objectMonitor.cpp)。

## 关键字段先按职责分组

| JDK 8 HotSpot 字段 | 职责 | 不变量/边界 |
| --- | --- | --- |
| `_owner` | 当前 owner 线程或相关锁记录 | 只有 owner 能正常 `exit/wait/notify` |
| `_recursions` | 首次持有之外的重入次数 | Java 深度 2 对应内部额外重入 1 |
| `_cxq` | 新到达竞争者组成的 contention queue | 常以 CAS 推入，不能按名称推断严格 FIFO |
| `_EntryList` | 已整理、等待获得 owner 的入口竞争者 | 与 `_cxq` 共同服务 monitor 进入，不是条件等待集 |
| `_WaitSet` | 已调用 `Object.wait` 的条件等待者 | 等待者调用 wait 前必须是 owner，入队后完整释放 owner |
| `_succ` | 继任者提示 | 用于减少无效唤醒/竞争，不是 Java 公平性承诺 |
| `_Responsible` | 负责推进竞争的线程提示 | 属于 HotSpot 调度优化，不应成为业务假设 |

最重要的分界是 `_EntryList/_cxq` 与 `_WaitSet`：前者因为“想获得锁但 owner 是别人”而等待，后者因为“业务条件不成立，主动调用 wait 并释放锁”而等待。

## enter：快速失败后怎样进入竞争

膨胀 monitor 的概念路径是：

```text
ObjectMonitor::enter
  ├─ owner 为空：CAS 尝试成为 owner
  ├─ owner 是当前线程：_recursions++
  ├─ 当前栈锁记录代表 owner：规范化 owner 并记录重入
  └─ 其他线程持有：EnterI
       → 构造 ObjectWaiter 节点
       → 进入 cxq / EntryList 竞争协议
       → park
       → 被唤醒后循环重试 owner
```

被唤醒不等于已经拥有 monitor。线程必须再次检查并成功提交 owner；这与 Java 层“从 `BLOCKED` 变为 `RUNNABLE` 只是有机会执行”一致。

## 为什么不能把 EntryList 讲成严格 FIFO

JDK 8 HotSpot 为吞吐、唤醒抑制和新旧竞争者管理维护多组指针：

- 新竞争者可能先通过 CAS 进入 `_cxq`。
- owner 释放时可能把 `_cxq` 整理到 `_EntryList`。
- `_succ` 只是指定或提示一个继任线程，不能推导 Java 公平锁语义。
- notifyee 从 `_WaitSet` 离开后进入哪条入口链受内部 `Knob_MoveNotifyee` 等策略影响。

所以源码图可以画“入口竞争集合”，但 API 结论只能是：释放后某个合格竞争者最终有机会成功进入。不能依赖先阻塞者先获得，也不能依赖 `notify()` 选择等待最久者。

## exit：释放不是简单写 owner=null

概念上 `exit` 先处理重入，再处理竞争者：

1. 若 `_recursions > 0`，只递减重入，不释放 owner。
2. 到最后一层时准备清空 owner。
3. 检查入口竞争集合与继任提示。
4. 选择需要唤醒的竞争者并 unpark。
5. 被唤醒者重新竞争 owner。

这里存在“先清 owner、再组织唤醒、竞争者重新 CAS”的并发窗口。读 `objectMonitor.cpp` 时不要把函数源代码的某一行当成 Java 业务临界区结束的唯一抽象时点；对 Java 程序而言，最终一次 monitor unlock 与随后 lock 的同步边才是稳定契约。

## wait：从 owner 变成 WaitSet 节点

`ObjectMonitor::wait` 的关键状态变化：

```text
校验当前线程是 owner
  → 创建 ObjectWaiter
  → 加入 _WaitSet
  → 保存 _recursions
  → 把 _recursions 归零并完整 exit
  → park
  → 通知/中断/超时后离开等待阶段
  → 重新进入 monitor
  → 恢复保存的 _recursions
  → 返回或抛 InterruptedException
```

两层 Java 重入时，wait 不能只释放一层，否则任何 notifier 都无法进入同一 monitor，系统会永久停住。它必须让 owner 完全空闲；返回前又必须恢复到调用 wait 时的持有深度，使后续两个词法作用域仍能各自正常退出。

## notify：只改变等待者的竞争资格

`notify` 校验 owner 后，从 `_WaitSet` 选择一个节点并移出。节点随后成为入口竞争者，但当前 owner 不变：

```text
T2 owner
  → notify 选择 T1
  → T1: WaitSet → entry contender
  → T2 继续执行同步块
  → T2 最终 exit
  → T1 与其他竞争者争夺 owner
  → T1 成功后才从 wait 返回
```

因此通知代码应尽快完成受锁保护的状态修改并退出临界区。notify 后在锁内执行长耗时 I/O，会让等待线程已经“被叫醒”却继续卡在入口竞争上。

## Java Thread.State 怎样映射

| 观察点 | 常见公开状态 | 线程栈/内部位置 |
| --- | --- | --- |
| 竞争进入同步块 | `BLOCKED` | monitorenter 慢路径，入口竞争集合 |
| 无期限 `Object.wait()` | `WAITING` | WaitSet + park |
| 有期限 `Object.wait(timeout)` | `TIMED_WAITING` | WaitSet + timed park |
| notify 后、重新取得前 | `BLOCKED` | 已离开 WaitSet，入口重新竞争 |
| 成功取得 monitor | `RUNNABLE` | 即将从 wait 返回或执行临界区 |

状态是瞬时诊断快照，不是同步工具。业务代码不能轮询 `getState()` 决定何时 notify；本项目 Lab 只在已用闸门控制主流程后，把状态用于教学断言。

## 膨胀与回收是实现生命周期

对象进入 `ObjectMonitor` 路径后，HotSpot 还需要管理 monitor 分配、空闲列表与安全时机下的 deflation。是否膨胀、何时回收、对象头如何指向 monitor 都可能随 JDK 版本变化。

应用层应使用 JFR、线程转储、async-profiler 或受支持的 JVM 诊断能力定位锁竞争；不要缓存对象头、依赖固定地址，也不要用未同步的反射读取内部队列后作业务决策。

下一步进入 [wait、notify、超时与中断](./wait-notify.md)，把 JVM 节点变化还原成正确的 Java 条件协议。
