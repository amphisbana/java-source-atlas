# AtomicInteger、LongAdder 与 Striped64：从单点 CAS 到分段累加

原子类解决的不是“所有并发逻辑自动安全”，而是对一个明确状态执行具有原子语义的读取、条件更新或读改写。`AtomicInteger` 让所有线程竞争同一个 `value`；`LongAdder` 在竞争出现后把写入分散到 `base` 和多个 `Cell`，读取时再汇总。`Striped64` 是后者的包级抽象基类，负责分段表、线程探针和扩容协议。

本专题以 OpenJDK 8u 为主基线。JDK 17/21 延续了分段累加模型，但底层原子访问、`longAccumulate` 参数以及 `sumThenReset` 的局部实现已有变化；这些差异会单独标明，不能拿 JDK 8 的私有签名直接给较新 JDK 下断点。

## 源码入口

| 类型 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| `AtomicInteger` | [`java/util/concurrent/atomic/AtomicInteger.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/AtomicInteger.java) | `compareAndSet`、`getAndAdd`、`updateAndGet` |
| `LongAdder` | [`java/util/concurrent/atomic/LongAdder.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/LongAdder.java) | `add`、`sum`、`sumThenReset` |
| `Striped64` | [`java/util/concurrent/atomic/Striped64.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/Striped64.java) | `casBase`、`Cell.cas`、`longAccumulate` |

`Striped64` 不是公开 API，业务代码不能直接实例化它。`LongAdder`、`DoubleAdder`、`LongAccumulator` 和 `DoubleAccumulator` 复用它的内部机制。

## 先建立四条不变量

1. `AtomicInteger` 的每次原子方法只对自己的一个 `int value` 建立原子边界，不会把多个对象或多次方法调用合成事务。
2. `LongAdder` 的逻辑总量始终是 `base + 所有非空 Cell.value`；扩容只复制 `Cell` 引用，不把 `base` 搬进数组。
3. `cellsBusy` 只串行化 `cells` 初始化、扩容和空槽安装，不保护普通 `Cell` 累加，也不保护 `sum()`。
4. `LongAdder.sum()` 在没有并发写入时给出准确总和；有并发写入时只是逐个位置读取的汇总，不是某个瞬间的原子快照。

## 两种计数模型

```text
AtomicInteger
  T1 ─┐
  T2 ─┼── CAS / getAndAdd ──> 同一个 volatile value
  T3 ─┘

LongAdder
  低竞争 ────────────────> base
  高竞争 T1 ─────────────> Cell[probe1 & mask]
         T2 ─────────────> Cell[probe2 & mask]
         读取 ───────────> base + Cell[0] + ... + Cell[n-1]
```

`AtomicInteger` 把“当前值”放在一个位置，因此单次更新和单次读取都容易定义精确语义；代价是所有写线程争用同一缓存位置。`LongAdder` 用额外空间和汇总成本换取高竞争下的写吞吐，因此不提供“递增并返回唯一新值”这类 API。

## 应该选择谁

| 需求 | 推荐 | 原因 |
| --- | --- | --- |
| 发号、序列、并发状态机 | `AtomicInteger` 或更完整的同步协议 | 需要对单个当前值做精确读改写 |
| 低竞争计数且频繁读取当前值 | `AtomicInteger` / `AtomicLong` | 单点读写简单，额外空间小 |
| 高并发请求量、命中量、监控累计值 | `LongAdder` | 写入可以分散到多个 Cell |
| 要在更新时应用自定义结合函数 | `LongAccumulator` | 复用 Striped64，并显式提供累加函数和单位值 |
| 余额、库存、额度扣减 | 通常不是 `LongAdder` | 业务往往需要精确校验、返回值和跨字段不变量 |

`LongAdder` 更快不是公开保证。线程数、更新频率、CPU 拓扑、读写比例和对象生命周期都会改变结果，应使用贴近生产负载的基准测试，而不是把微基准结论当成固定倍数。

## 与 volatile 和 synchronized 的关系

单独的 `volatile int` 能提供读写可见性，但 `value++` 仍包含读取、加一、写回三个步骤，多个线程可能基于同一个旧值写回。`AtomicInteger.getAndIncrement()` 把读改写作为一次原子操作。

原子类也不取代锁。下面的业务不变量跨越两个状态，两个独立 CAS 之间仍可能被其他线程观察：

```text
if (available > 0) {
  available--
  reserved++
}
```

如果两个字段必须同步变化，应把状态合并为一个可原子替换的不可变对象，或使用锁保护完整临界区。

## 一条完整学习路径

1. [AtomicInteger：CAS 与原子读改写](./atomic-integer.md)：理解单点状态、返回旧值和函数重试。
2. [LongAdder：写入分散、汇总与版本边界](./sum-version.md)：理解 `add/sum/sumThenReset` 的不同语义。
3. [Striped64：longAccumulate 状态机](./striped64.md)：跟踪初始化、碰撞换槽和扩容。
4. [断点实验手册](./debug-lab.md)：运行受控并发案例，再到 JDK 源码中验证变量变化。

已有的 [ConcurrentHashMap 分散计数](../concurrenthashmap/count-compute.md) 使用了同类思想，但它内部的 `CounterCell` 与 `Striped64.Cell` 是各自维护的实现，不能据此认为 `ConcurrentHashMap` 直接持有一个 `LongAdder`。继续阅读 [ConcurrentLinkedQueue](../concurrentlinkedqueue/) 可以对照 CAS 如何从“原子更新一个数值”扩展到“发布节点并协助推进链表指针”。

## JDK 8、17、21 的实现边界

| 观察点 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| `AtomicInteger` 底层 | `sun.misc.Unsafe` 和字段偏移 | 文档用 VarHandle 内存语义描述，但实现仍通过 `jdk.internal.misc.Unsafe`，源码注明受启动循环依赖限制 |
| `Striped64` 底层 | `Unsafe` CAS 和线程 probe 字段偏移 | `VarHandle` 操作 `base`、`cellsBusy`、`Cell.value` 和线程 probe |
| `longAccumulate` | 三个参数，方法内读取 probe | 增加 `index` 参数，由调用方传入 probe |
| `sumThenReset` | 对 `base/Cell.value` 逐个读取再写零 | 对每个位置使用原子 get-and-set，但整个跨条带操作仍不是原子快照 |
| 原子 API | Java 8 的核心方法 | Java 9 起增加 plain、opaque、acquire/release、compare-and-exchange 等更细粒度 API |

因此，“JDK 9 以后都改成 VarHandle”是不准确的概括。应分别检查具体类的源码，并把公开内存语义与底层实现工具区分开。
