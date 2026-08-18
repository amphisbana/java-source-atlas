# Reference / WeakHashMap：从可达性到弱键清理

`java.lang.ref` 把 GC 的一部分可达性判断暴露给 Java 程序，`WeakHashMap` 再把弱引用与哈希表组合成“key 不再被外部使用后，映射可以自动失效”的容器。两者解决的是生命周期协作，不是缓存容量控制，也不是确定时刻的资源释放。

本专题以 OpenJDK 8u 为源码基线，重点区分三件经常被混在一起的事：

1. referent 何时从强可达变成软、弱或虚可达；
2. GC 何时清除 `Reference.referent`，并把引用对象送入 `ReferenceQueue`；
3. 容器或清理线程何时消费队列，真正解除 value、native handle 等资源。

## 源码地图

| 类型 | 固定源码 | 本专题关注点 |
| --- | --- | --- |
| `Reference` | [`java/lang/ref/Reference.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ref/Reference.java) | referent、queue、next、discovered、pending、Reference Handler |
| `ReferenceQueue` | [`java/lang/ref/ReferenceQueue.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ref/ReferenceQueue.java) | enqueue、poll、remove 与队列状态 |
| `SoftReference` | [`java/lang/ref/SoftReference.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ref/SoftReference.java) | timestamp 与内存压力下的策略边界 |
| `WeakReference` | [`java/lang/ref/WeakReference.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ref/WeakReference.java) | 弱可达对象的原子清除 |
| `PhantomReference` | [`java/lang/ref/PhantomReference.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ref/PhantomReference.java) | `get()` 永远返回 null，配合队列清理 |
| `WeakHashMap` | [`java/util/WeakHashMap.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/WeakHashMap.java) | Entry 弱 key、强 value、队列驱动 expunge |

## 先建立两张图

### 对象可达图

```text
GC Root ──强──> owner ──强──> key
                          ↑
WeakHashMap.table ──强──> Entry ──弱──┘
                              └─强──> value
```

只要第一条强路径存在，Entry 的弱引用不会影响 key。外部释放 `owner → key` 后，key 才可能变成弱可达。GC 清除弱 referent 时，`Entry.get()` 变成 null，但 table 仍强引用 Entry，Entry 又仍强引用 value。

### 引用处理图

```text
Active Reference
  -> GC 判定 reachability 并清 referent
  -> JDK 8 pending list
  -> Reference Handler
  -> ReferenceQueue
  -> 应用/容器 poll 或 remove
  -> 清理业务资源、Map Entry 或元数据
```

GC 只负责到“清除 referent、安排入队”附近。如何处理队列里的通知，仍由 `WeakHashMap` 或应用代码负责。

## 四种引用不是四档精确缓存

| 引用 | `get()` 可能返回对象 | 典型清除条件 | 适合表达 | 不应依赖 |
| --- | --- | --- | --- | --- |
| 强引用 | 是 | 强路径消失后才可能回收 | 普通所有权 | GC 主动替你解业务关系 |
| `SoftReference` | 是 | 内存压力与实现策略共同决定 | 对清除时机不敏感的辅助数据 | 固定 TTL、精确 LRU、避免 OOM |
| `WeakReference` | 是，清除后为 null | 对象只剩弱路径 | 观察对象身份是否仍被外部拥有 | `System.gc()` 后立刻清除 |
| `PhantomReference` | 永远为 null | 对象进入不可通过引用恢复的清理阶段 | 与队列组合的清理通知 | 从虚引用重新取得对象 |

软引用策略受收集器、堆大小、访问时间和 JVM 参数影响。生产缓存需要容量、过期、统计与并发语义时，应使用专门缓存实现，而不是把 `SoftReference` 当策略引擎。

## 动画：弱键为什么不是立刻消失

<ReferenceWeakHashMapAnimation />

逐帧时重点观察：

- 第 3 帧 GC 已清 key，但 value 仍被 table 中的 Entry 保留；
- 第 5 帧 Entry 已进入队列，Map 的逻辑结构仍未改变；
- 第 6 帧下一次 Map 操作才执行 `expungeStaleEntries`；
- 第 9 帧 value 回指 key 会重新建立强路径，弱键不会清理；
- 第 11、12 帧虚引用只传递“可以清理”的通知，不传递对象本身。

## WeakHashMap 的准确契约

`WeakHashMap<K,V>` 的 key 是弱引用，value 不是。它适合元数据附着关系：只要外部还拥有 key，映射可用；外部不再拥有 key 后，Map 不应成为阻止 key 回收的所有者。

这带来几个公开可见的特点：

- `size()` 可能在没有显式 `remove` 时变小；
- 一次 `get`、`put`、`size` 或视图操作可能顺便清掉旧 Entry；
- `null` key 被内部强哨兵替代，不会因弱引用清除；
- value 若强引用 key，会形成 `map → entry → value → key` 强路径，破坏预期；
- Map 不是线程安全容器，GC 引起的结构变化也不提供并发同步。

详细的桶级清理见 [WeakHashMap：弱 key、强 value 与 expunge](./weakhashmap)。Reference 内部状态和队列协议见 [Reference 处理链](./reference-processing)。

## 什么时候使用

合适场景：

- 给外部拥有的 Class、ClassLoader、组件对象附加非关键元数据；
- registry 不希望延长 key 的生命周期；
- 映射消失只影响缓存命中率，不影响业务正确性；
- 单线程使用，或由调用方在整个复合操作外同步。

不合适场景：

- 需要精确容量、TTL、命中率或淘汰顺序的缓存；
- value 可能直接或间接引用 key；
- key 是临时装箱值、短字符串等，调用方没有稳定身份所有权；
- 资源必须在确定时刻关闭；
- 多线程高并发读写。

确定性资源管理仍应优先使用 `try-with-resources` / `AutoCloseable`。ReferenceQueue 或 Cleaner 只能作为遗漏关闭后的兜底，不应替代清晰的所有权。

## 阅读与实验顺序

1. 本页先区分可达性、清 referent、入队和消费队列。
2. 阅读 [Reference 处理链](./reference-processing)，跟踪 JDK 8 的四种内部状态。
3. 阅读 [WeakHashMap](./weakhashmap)，手推 `expungeStaleEntries` 怎样按 identity 摘链。
4. 运行 [断点实验手册](./debug-lab)，把确定性队列操作与不确定的 GC 观察分开。

## 过关问题

1. WeakHashMap 的 key 已被 GC 清除后，为什么 value 仍可能暂时存活？
2. `Reference.clear()` 为什么不会自动把 Reference 放入队列？
3. value 回指 key 时，哪一条强路径阻止了 key 进入弱可达？
4. `null` key 为什么不会像普通 key 一样自动消失？
5. PhantomReference 的 `get()` 永远为 null，清理线程靠什么知道要释放哪个资源？
6. 为什么自动测试不能把“调用一次 `System.gc()` 后必须清除”当公开契约？

