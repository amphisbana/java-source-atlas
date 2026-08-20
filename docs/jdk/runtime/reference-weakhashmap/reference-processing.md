# Reference 处理链：referent、pending 与 ReferenceQueue

`Reference<T>` 看起来只有 `get`、`clear`、`enqueue` 几个方法，真正困难的是它同时被应用线程、GC 和 Reference Handler 访问。阅读时要把“Java 字段状态”和“对象可达性状态”分开：前者是某个 OpenJDK 版本的实现，后者由 Java 语言与 API 规范描述。

版本入口：[JDK 8 / 17 / 21 Reference / WeakHashMap 对比](/jdk/version-comparison/?topic=reference-weakhashmap)。本页以 JDK 8 建主线，并在每个交接边界标出 17/21 的真实迁移位置。

## JDK 8 的关键字段

```java
private T referent;                       // GC 特殊处理
volatile ReferenceQueue<? super T> queue;
volatile Reference next;                 // 队列状态与链接
private transient Reference<T> discovered; // GC discovered / pending 链
private static Reference<Object> pending;
```

`referent` 不是普通强引用字段。VM 识别 `Reference` 子类并按照引用类型处理它；Java 源码里的字段声明不能单独解释 GC 行为。

`next` 和 `discovered` 都可能承担链表链接，但服务不同：

- `discovered` 供收集器维护 discovered 列表，也在 JDK 8 中连接 pending 链；
- `next` 反映 ReferenceQueue 中的链接或已经 inactive 的状态；
- `queue` 可能指向真实队列，也可能被替换为内部 `NULL` / `ENQUEUED` 哨兵。

这些私有字段在较新 JDK 已有重构，调试时必须以当前 SDK 源码为准。

## JDK 8 的四种内部状态

| 状态 | referent | queue / next | 谁推进 |
| --- | --- | --- | --- |
| Active | 可能非 null | 尚未入队 | 应用与 GC |
| Pending | 通常已按类型处理 | `discovered` 链入 pending | GC 写入，Handler 取出 |
| Enqueued | 不应靠它恢复对象 | `next` 链入 ReferenceQueue | Reference Handler 或显式 enqueue |
| Inactive | 通常已清 | 已离开队列，不能再次正常入队 | 队列消费者 |

这是 JDK 8 `Reference.java` 注释描述的内部状态机，不是应用可以枚举的公共 enum。JDK 8 业务代码只能通过 `get`、`isEnqueued`、`enqueue`、队列 `poll/remove` 观察有限结果；`isEnqueued` 自 JDK 16 起已弃用且存在状态竞争，新版判断清除用 `refersTo(null)`，取得队列消费所有权则必须真正 `poll/remove` 到 Reference。

## GC 与 Reference Handler 怎样交接

JDK 8 中，GC 将待入队 Reference 接到静态 `pending` 链，并通知 `Reference.lock`。JVM 启动时，`Reference` 静态初始化会建立最大优先级 daemon 线程 `Reference Handler`：

```text
ReferenceHandler.run
  -> while (true)
       Reference.tryHandlePending(true)
         -> synchronized (lock)
         -> 从 pending 摘一个 Reference
         -> Cleaner ? clean() : queue.enqueue(reference)
```

源码特意在从 pending 摘链前执行 `instanceof Cleaner`，并处理潜在 `OutOfMemoryError`。这说明引用处理发生在内存压力附近，清理路径本身也应少分配、可重入、可失败恢复。

JDK 17/21 仍有 Reference Handler 概念，但不再由 Java `tryHandlePending` 在静态 pending 头上逐个摘取。`processPendingReferences()` 先调用 VM 的 `waitForReferencePendingList()`，再在 `processPendingLock` 内一次取得并清空整条列表，随后才循环执行 Cleaner 或入队。VM 交接与 Java 消费因此分层，但可移植契约仍只是引用清除与队列通知，不是 `pending`、`discovered` 的字段布局、批次大小或 Handler 延迟。

## clear 与 enqueue 不能混为一个动作

`Reference.clear()` 只把 referent 清空，Javadoc 明确说明它不会导致自动入队。反过来，JDK 8 的公开 `Reference.enqueue()` 会先把 referent 设为 null，再尝试加入注册队列：

```text
reference.clear()
  -> reference.get() == null
  -> queue 仍可能为空

reference.enqueue()
  -> reference.get() == null
  -> 尝试把 Reference 对象加入注册队列
  -> 不负责关闭 referent 代表的业务资源
```

因此“clear 不入队”是正确结论，但“enqueue 只改队列、不清 referent”并不正确。GC 入队走的是 VM 与 Reference Handler 的内部协作，不会回调公开 `enqueue()` 方法；调试时也要把这两条路径分开。

JDK 17/21 把两个入口中的直接字段赋值改成 private native `clear0()`。源码明确说明：对于部分垃圾收集器，普通 `referent=null` 不足以表达需要的屏障与通知。公开结果没有变化，变化的是 GC 特殊字段的写入责任被收回到 VM 边界。业务代码不应通过反射清 referent，也不能用“字段已经是 null”推断收集器的内部 reference processing 已完成。

反过来，队列里出现一个 Reference，也不代表业务清理已经完成。队列消费者仍需读取 Reference 子类保存的独立元数据，执行幂等释放，并去掉自己的追踪结构。

## ReferenceQueue 的三种消费方式

| 方法 | 行为 | 典型用途 |
| --- | --- | --- |
| `poll()` | 立即返回队首或 null | 在普通操作中顺便清理，如 WeakHashMap |
| `remove()` | 阻塞直到有元素 | 专用清理线程 |
| `remove(timeout)` | 有界阻塞 | 可停止的后台服务与测试 |

专用消费者应响应中断，并有明确停止协议。无限循环 `remove()` 却吞掉中断，会使应用关闭时遗留线程。

### 队列内部为什么经历两次变化

JDK 8 的 `ReferenceQueue.enqueue` 先把 `r.queue` 发布为 `ENQUEUED`，再把 r 接到 head。JDK 17 调整为“先完成 next/head，再用 volatile queue 发布 ENQUEUED”，源码原因是避免并发 `isEnqueued` 与 fast-path poll 看到尚未完成的队列结构。出队也相应先发布 inactive 状态，再移动 head。

JDK 21 保留这条发布顺序，但把私有 monitor 和 `Object.wait/notifyAll` 改为 `ReentrantLock + Condition`。这项改造服务于虚拟线程阻塞实现，不改变以下公开结果：空队列 timed remove 返回 null、无限 remove 可被 interrupt、enqueue 会唤醒等待消费者。自动测试应验证这些结果，而不是私有锁类型。

## refersTo：观察身份但不强化 referent

JDK 16 新增 `Reference.refersTo(obj)`。JDK 17/21 快照都已包含它，并同期弃用 `isEnqueued()`：

```text
reference.refersTo(candidate)  → 当前 referent 是否就是 candidate
reference.refersTo(null)       → 当前是否已清除
```

它与 `get() == candidate` 的关键区别是不用先把 referent 读成强局部变量。PhantomReference 的 `get()` 始终为 null，但只要尚未清除，`phantom.refersTo(referent)` 仍可为 true。

JDK 17 直接从 final public 方法调用可覆写 native `refersTo0`；JDK 21 改成 `refersTo → refersToImpl → private native refersTo0`，PhantomReference 覆写 Java impl。源码说明这层 Java 分派是为了避免 native 虚调用使 C2 放弃 intrinsic，公共身份语义没有变化。

## 四类引用的处理边界

### SoftReference

JDK 8 `SoftReference` 保存 `timestamp`，静态 `clock` 由 GC 更新；每次成功 `get()` 可能刷新时间戳。VM 可以参考它，但并不承诺严格的最近最少使用顺序。

软引用只承诺在抛出 `OutOfMemoryError` 前，所有指向软可达对象的 SoftReference 已被清除。它不提供容量、TTL 或固定回收阈值。

### WeakReference

当对象变成弱可达时，GC 会原子清除指向该对象以及通过强/软路径可达的同组弱可达对象的 WeakReference。清除后 `get()` 返回 null；若注册了队列，引用对象随后会被安排入队。

### PhantomReference

JDK 8 的 `PhantomReference.get()` 无条件返回 null。构造器允许传入 null ReferenceQueue，`Reference` 会把它转换为内部空队列哨兵；但这种引用不会提供可消费的入队通知，对清理协作几乎没有实用价值，因此真实清理场景仍应传入非 null 队列。资源标识必须放在 PhantomReference 子类或外部追踪记录中，绝不能指望从 referent 读取。

JDK 8 的公开规范已经明确：GC 判定对象为 phantom reachable 时，会原子清除该对象及其可达闭包关联的全部 PhantomReference，随后在同一时刻或稍后把已注册的引用入队。JDK 9+ 延续了这条公开契约，但 Reference 私有字段与处理链持续演进；跨版本文档不应照搬 JDK 8 的内部状态。

### FinalReference / Cleaner

JDK 8 的 finalization 与 `sun.misc.Cleaner` 进入 Reference 内部特殊路径。JDK 9 提供 `java.lang.ref.Cleaner`，JDK 18 起 finalization 被标记为 deprecated for removal。新代码应优先显式关闭，Cleaner 仅作为安全网。

## reachabilityFence 解决的 JIT 生命周期问题

从 JDK 9 开始，`Reference.reachabilityFence(obj)` 可以保证对象在调用点之前保持强可达。它解决的是 JIT 发现“后续不再读取 Java 字段”后，可能比源码块结束更早判定对象不可达的问题，常用于对象持有 native handle 的场景。

```text
try {
  useNativeHandle(owner.handle)
} finally {
  Reference.reachabilityFence(owner)
}
```

它不会延长到方法结束之后，也不会替代 close；只建立调用点之前的可达性边界。本项目以 Java 8 编译 Lab，因此只在版本说明中展示该 API。

## 内存可见性边界

ReferenceQueue 是通知通道，不是任意业务状态的发布协议。若清理线程还要读取 Reference 子类中的 metadata，应在构造完成后安全发布 Reference，并避免在入队前后无同步修改 metadata。

同样，`WeakReference.get() != null` 只能说明本次读取拿到了 referent。使用期间若必须防止过早清理，应把返回值保存到强局部变量，并在 native 交互中按版本使用 reachability fence。

## 断点路线

JDK 8：

1. `Reference.clear`：区分显式清除和 GC 直接写字段。
2. `Reference.enqueue`：观察 queue 哨兵与 enqueue 返回值。
3. `Reference.tryHandlePending`：观察 `pending`、`r`、`c`。
4. `ReferenceQueue.enqueue`：观察 `head`、`queueLength`、`next`。
5. `ReferenceQueue.remove(long)`：观察等待、超时与出队。

JDK 17/21 的 `ReferenceHandler.run` 会进入 VM pending-list 协作入口，不能强找 JDK 8 的 `tryHandlePending` 局部变量。JDK 21 还把 `Reference` 声明成 sealed，但允许的 `WeakReference/SoftReference/PhantomReference` 是 non-sealed；正常从具体引用家族扩展仍然可行，直接继承 Reference 本来就不是支持的入口。

## 常见误判

| 误判 | 实际情况 |
| --- | --- |
| clear 等于 enqueue | clear 不自动入队，enqueue 也不执行业务清理 |
| queue 返回 referent | queue 返回 Reference 对象；referent 通常已经不可取得 |
| Reference Handler 会替 WeakHashMap 删桶 | Handler 只入队，WeakHashMap 自己在后续操作中 expunge |
| SoftReference 是自带 LRU 的缓存 | GC 策略不是应用级容量与过期契约 |
| PhantomReference 能在清理时恢复对象 | `get()` 永远返回 null |
| System.gc 能稳定推进状态机 | 它只是建议，测试不能依赖固定时刻 |
