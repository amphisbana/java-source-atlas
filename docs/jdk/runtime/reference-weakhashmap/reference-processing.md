# Reference 处理链：referent、pending 与 ReferenceQueue

`Reference<T>` 看起来只有 `get`、`clear`、`enqueue` 几个方法，真正困难的是它同时被应用线程、GC 和 Reference Handler 访问。阅读时要把“Java 字段状态”和“对象可达性状态”分开：前者是某个 OpenJDK 版本的实现，后者由 Java 语言与 API 规范描述。

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

这是 JDK 8 `Reference.java` 注释描述的内部状态机，不是应用可以枚举的公共 enum。业务代码只能通过 `get`、`isEnqueued`、`enqueue`、队列 `poll/remove` 观察有限结果。

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

JDK 17/21 仍有 Reference Handler 概念，但 pending 列表访问、VM native 入口和 Cleaner 实现已经变化。可移植契约是引用清除与队列通知，不是 `pending`、`discovered` 的精确字段布局。

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

反过来，队列里出现一个 Reference，也不代表业务清理已经完成。队列消费者仍需读取 Reference 子类保存的独立元数据，执行幂等释放，并去掉自己的追踪结构。

## ReferenceQueue 的三种消费方式

| 方法 | 行为 | 典型用途 |
| --- | --- | --- |
| `poll()` | 立即返回队首或 null | 在普通操作中顺便清理，如 WeakHashMap |
| `remove()` | 阻塞直到有元素 | 专用清理线程 |
| `remove(timeout)` | 有界阻塞 | 可停止的后台服务与测试 |

专用消费者应响应中断，并有明确停止协议。无限循环 `remove()` 却吞掉中断，会使应用关闭时遗留线程。

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

JDK 17/21 的 `ReferenceHandler.run` 会进入不同的 native pending-list 协作入口，不能强找 JDK 8 的 `tryHandlePending` 局部变量。

## 常见误判

| 误判 | 实际情况 |
| --- | --- |
| clear 等于 enqueue | clear 不自动入队，enqueue 也不执行业务清理 |
| queue 返回 referent | queue 返回 Reference 对象；referent 通常已经不可取得 |
| Reference Handler 会替 WeakHashMap 删桶 | Handler 只入队，WeakHashMap 自己在后续操作中 expunge |
| SoftReference 是自带 LRU 的缓存 | GC 策略不是应用级容量与过期契约 |
| PhantomReference 能在清理时恢复对象 | `get()` 永远返回 null |
| System.gc 能稳定推进状态机 | 它只是建议，测试不能依赖固定时刻 |
