# Striped64：longAccumulate 状态机

`Striped64` 把一个逻辑值拆为 `base` 和一组带填充的 `Cell`。它是包级抽象类，`LongAdder` 等公开子类负责定义加法或自定义累加函数，基类负责发现竞争、选择条带并调整结构。

## LongAdder.add 的三级路径

OpenJDK 8u 的 `add(long x)` 先尝试最便宜的路径，再逐步升级：

```text
add(x)
  ├─ cells == null 且 casBase 成功 -> 完成
  ├─ cells 已存在且目标 Cell 非空、Cell.cas 成功 -> 完成
  └─ longAccumulate(x, null, wasUncontended)
       -> 初始化表 / 安装 Cell / 换槽 / 扩容 / 初始化竞争时回退 base
```

传给 `longAccumulate` 的 `fn` 为 `null`，表示执行普通加法。`Striped64` 的其他子类可以传入累加函数复用同一套竞争处理框架。

## add 为什么先写 base

新建对象的状态是：

```text
base = 0
cells = null
cellsBusy = 0
```

没有竞争时，一个 CAS 更新 `base` 已经足够，创建数组和带填充的 Cell 只会浪费空间。第一次 `base` CAS 失败说明出现了竞争，慢路径才尝试创建长度为 2 的 `cells`。

一旦 `cells` 已非空，`add` 会优先选择 Cell，即使本次恰好没有其他线程竞争，也不会主动退回只使用 `base` 的初始形态。`cells` 不会因为负载下降而缩容或清除；这避免了结构来回抖动，但意味着曾经历竞争的长寿命计数器会保留额外空间。

## 动画：竞争如何从 base 分流到 Cell

下面的动画从两个线程竞争 `base` 开始，展示 `cells` 初始化、线程 probe 选槽、Cell CAS 冲突和扩容。动画中的每一步代表源码状态转换，不表示真实线程按固定顺序执行。

<AtomicStripedAnimation />

## 三个核心字段

| 字段 | 含义 | 谁更新 |
| --- | --- | --- |
| `transient volatile long base` | 无竞争时的主要值，也是 `cells` 尚未可用时的回退位置 | `casBase`，重置方法还会写零 |
| `transient volatile Cell[] cells` | 延迟创建的分段表，长度为 2 的幂 | 持有 `cellsBusy` 的线程初始化或替换数组 |
| `transient volatile int cellsBusy` | 0 表示结构可修改，1 表示有线程在初始化、扩容或安装 Cell | `casCellsBusy` 从 0 改 1，finally 中写回 0 |

逻辑总量不是二选一，而是：

```text
base + cells 中所有非空 Cell.value
```

创建 `cells` 时不会清空或迁移 `base`。初始化竞争期间成功回退到 `base` 的更新同样永久属于总量。

## Cell 为什么不是普通 long

`Cell` 内含一个 `volatile long value` 和 CAS 方法，并带 `@sun.misc.Contended`。数组中的普通小对象通常相邻分配，不同线程即使更新不同槽位，也可能让同一缓存行在 CPU 核之间反复失效。竞争填充用于降低这种伪共享。

它只降低相邻 Cell 互相干扰的概率，不代表：

- Cell 一定独占某个固定大小的缓存行；
- 更新 Cell 不再需要 CAS；
- `sum()` 可以原子读取整个数组；
- 用户类直接添加同名注解就一定获得相同布局。

对象布局取决于 JVM 参数和实现，源码阅读应依赖逻辑字段，不依赖猜测出的字节偏移。

## 线程如何选择槽位

每个线程复用 `ThreadLocalRandom` 维护的 probe 作为哈希。数组长度是 2 的幂，因此索引可用位掩码计算：

```text
index = probe & (cells.length - 1)
```

probe 为 0 表示尚未初始化，JDK 8 的 `longAccumulate` 会调用 `ThreadLocalRandom.current()` 强制初始化。发生碰撞后，`advanceProbe` 使用 xorshift 更新当前线程的 probe，再尝试另一个槽位。

这个 probe 属于线程，不属于某个 LongAdder。同一线程访问不同 Striped64 实例时会复用当前 probe；碰撞造成的推进也会影响它之后的槽位选择。

## longAccumulate 的入口条件

`LongAdder.add` 只有在快速路径不能完成时才进入 `longAccumulate(x, null, wasUncontended)`：

- `cells` 尚未创建，但 `base` CAS 已失败；
- `cells` 已创建，但目标槽为空；
- 目标 Cell CAS 失败；
- 观察到的结构暂时无法直接更新。

`wasUncontended == false` 表示调用方已经在目标 Cell 上 CAS 失败。慢路径不会马上在同一槽重复冲撞；它先把标记恢复为可尝试状态，并在本轮末推进 probe。

## longAccumulate 的完整决策

可以把 OpenJDK 8u 的无限重试循环压缩成下面六类状态：

| 当前状态 | 动作 | 成功后的结果 |
| --- | --- | --- |
| `cells == null` | 尝试取得 `cellsBusy`，复查后创建长度 2 的数组并安装一个 Cell | 本次 `x` 已落入新 Cell，退出 |
| `cells` 存在且目标槽为空 | 乐观创建 Cell，取得 `cellsBusy` 后复查数组和槽位再安装 | 本次 `x` 已落入新 Cell，退出 |
| 目标 Cell 可更新 | CAS `Cell.value` | 本次更新完成，退出 |
| Cell CAS 首次连续失败 | 设置 `collide = true`，推进 probe | 换槽重试 |
| 再次碰撞且尚未达容量边界 | 取得 `cellsBusy`，确认数组身份未变后扩容为两倍 | 使用新 mask 重试 |
| `cells` 尚未可用，且本轮未完成初始化 | 尝试 CAS 更新 `base` | 成功则退出，失败则继续循环 |

这里的“再次碰撞”不是简单累计两次历史失败。`collide` 会在空槽、数组已变化、达到 CPU 边界或扩容后被清除，用来避免一次 CAS 失败就立即争抢结构锁。

## 为什么扩容前要检查两次

线程先把当前数组引用保存为局部变量 `as`，随后经历 Cell CAS、probe 推进或 `cellsBusy` 竞争。另一个线程可能已把 `cells` 替换为更大的数组。

扩容前需要同时满足：

1. 当前观察到连续碰撞；
2. 数组长度尚未达到 `NCPU` 边界；
3. `cells == as`，局部数组仍是当前数组；
4. CAS 取得 `cellsBusy`；
5. 进入临界区后再次确认 `cells == as`。

两次身份检查防止基于过期数组再次扩容并覆盖其他线程已经发布的新表。空槽安装也采用“锁外观察 + CAS 取得结构权 + 锁内复查”的同一模式。

## cellsBusy 不是什么

虽然 OpenJDK 注释称它为 spinlock，但线程没有围着它单独阻塞等待。已有可用 `cells` 时，获取失败的线程会推进 probe 并继续尝试其他槽位；`cells` 尚未可用时，它还可能尝试更新 `base`。

它不保护：

- 已存在 Cell 的普通 CAS 累加；
- `LongAdder.sum()` 对各位置的读取；
- 一组条带作为整体的原子快照；
- 业务代码中的多个 LongAdder。

它只保护短小的结构变更，并且释放写位于 `finally`，防止异常路径把结构永久留在 busy 状态。

## 扩容为什么受 NCPU 限制

数组从长度 2 开始按两倍增长。当当前长度 `n >= NCPU` 时不再扩容；若 CPU 数不是 2 的幂，最后一次翻倍可以得到大于 CPU 数的最小二次幂。例如 `NCPU = 6` 时，长度可以从 4 扩到 8。

设计假设是：条带数达到处理器数量附近后，继续增加槽位很难产生与额外空间相称的收益。达到边界仍碰撞时，线程继续推进 probe，尝试找到更合适的映射。

`Runtime.availableProcessors()` 可能受容器 CPU 配额和 JVM 配置影响。它是实现用于确定扩容边界的运行时值，不是业务吞吐保证。

## 一次竞争升级的变量轨迹

假设两个线程都曾尝试更新 `base = 40`：

| 阶段 | `base` | `cells` | `cellsBusy` | 本轮更新位置 |
| --- | ---: | --- | ---: | --- |
| 初始 | 40 | `null` | 0 | 尚未完成 |
| T1 CAS base 成功 | 41 | `null` | 0 | T1 落入 base |
| T2 的旧值 CAS 失败 | 41 | `null` | 0 | 转入慢路径 |
| T2 取得结构权 | 41 | `[Cell(1), null]` 或反向槽位 | 1 | T2 落入 Cell |
| T2 释放结构权 | 41 | 长度 2 | 0 | 逻辑总量 42 |

`base` 没有被搬到 Cell，Cell 的初始值就是当前线程尚未提交的 `x`。后续更新通常直接选择 Cell；只有线程基于尚未可用的表参与初始化竞争时，慢路径才可能回退到 base。

## fn 参数与 LongAccumulator

`longAccumulate` 通过 `fn == null` 表示普通 `v + x`，这是 LongAdder 的路径；非空函数用于 LongAccumulator。函数可能因 CAS 失败在循环中重复执行，而且不同条带的最终合并顺序不固定，因此自定义累加函数必须符合对应公开 API 对无副作用、结合方式和单位值的要求。

不要直接复制 `longAccumulate` 到业务代码。它依赖 JDK 私有的 Thread probe、`@Contended`、内存语义和多个锁内复查条件，删掉任何一个“看似重复”的判断都可能打开竞态窗口。

## JDK 17/21 如何变化

核心状态机保持不变，但实现细节有四个重要变化：

1. `Cell.value`、`base`、`cellsBusy` 和线程 probe 改由 VarHandle 定位和访问。
2. Cell 与 base 的 CAS 使用 release 弱 CAS；算法本身已有重试循环，可以容忍虚假失败。
3. `longAccumulate` 增加 `index` 参数，`LongAdder.add` 先读取 probe 再传入，JDK 8 则在慢路径内部读取。
4. Cell 增加 `reset/getAndSet` 等辅助方法，`sumThenReset` 可逐位置原子交换为零。

“逐位置原子”仍不等于“所有位置同时原子”。公开类的行为说明比私有 CAS 形式更稳定；跨版本调试应先确认当前 SDK 的真实方法签名和局部变量名。

## 推荐断点顺序

1. `LongAdder.add(long)`：观察 `cells` 是否为空，以及 base CAS 或 Cell CAS 的结果。
2. `Striped64.longAccumulate(...)`：确认进入慢路径时的 `wasUncontended` 和 probe/index。
3. `casCellsBusy()`：观察谁取得结构修改权。
4. 新建 `Cell[2]` 与 `cells = rs` 的发布点：确认初始化前后的数组身份。
5. `Cell.cas(long,long)`：观察槽位竞争。
6. `advanceProbe(int)`：记录碰撞后索引如何变化。
7. `cells = 新数组`：比较扩容前后 Cell 对象身份，确认复制的是引用。

调试器会显著改变线程调度。某次运行只命中 base 或只创建两个 Cell 都是合法结果，不要把数组长度和线程到槽位的映射写成测试断言。
