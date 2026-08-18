# AtomicInteger 与 LongAdder 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/concurrent/AtomicDebugLab.java
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.AtomicDebugLab
```

实验代码只调用公开 API，JDK 私有字段和数组形态通过 IDE 附加的当前 SDK 源码观察，不要求使用反射打开 `java.base` 模块。

## 实验一：稳定复现普通计数丢失更新

运行 `observeAtomicCounterContrast()`。案例会控制两个线程先读取同一个普通旧值，再允许它们写回，从而稳定展示 `volatile` 或普通可见性不能把 `value++` 变成原子操作；对照组使用 `AtomicInteger` 完成两次原子递增。

观察重点：

| 对照 | 两个线程读取的旧值 | 最终更新数 |
| --- | --- | --- |
| 拆开的读取与写回 | 相同 | 可能只保留一次 |
| `AtomicInteger.incrementAndGet` | 原子读改写，不暴露拆分旧值 | 保留两次 |

不要依靠无控制的数据竞争“通常会丢”来写测试。线程调度可能恰好串行，使错误实现偶然得到正确结果；本实验的受控时序用于让原因可重复观察。

## 实验二：CAS 期望值匹配与失配

运行 `observeCompareAndSet()`，在 `AtomicInteger.compareAndSet` 设置断点。

建议依次记录：

- 调用前 `value`；
- `expect/update`，JDK 17/21 中对应 `expectedValue/newValue`；
- 方法返回值；
- 调用后的 `value`。

匹配时返回 true 并提交新值，失配时返回 false 且不覆盖当前值。失败分支不是异常处理，而是乐观并发协议的正常控制流。

## 实验三：函数更新的重试

运行 `observeUpdateFunctionRetry()`。案例通过受控竞争让 `updateAndGet` 的用户函数至少重试一次。

在 `AtomicInteger.updateAndGet` 观察：

| 变量 | 含义 |
| --- | --- |
| `prev` | 本轮计算基于的旧值 |
| `next` | 函数根据 prev 算出的候选值 |
| CAS 结果 | 本轮候选值是否真正提交 |
| 函数调用次数 | 可能大于成功更新次数 |

实验对调用次数的记录仅用于证明重试边界。生产函数必须无副作用，不能把发送消息、扣款或写库放进可能重复执行的函数体。

## 实验四：静默期汇总 LongAdder

运行 `observeLongAdderSum()`。多个线程完成固定次数累加后，主线程等待所有任务结束，再调用 `sum()`。

公开行为断言只应包含：

- 所有工作线程已完成后，总和等于线程数乘以每线程累加量；
- `increment()` 本身不返回更新后的全局总量。

若要观察内部慢路径，建议在以下位置使用“仅挂起当前线程”的断点：

1. `LongAdder.add(long)` 的 base CAS 失败处；
2. `Striped64.longAccumulate(...)` 入口；
3. `casCellsBusy()`；
4. `Cell.cas(long,long)`；
5. `advanceProbe(int)`；
6. `cells` 初始化或扩容赋值点。

数组是否扩容、创建多少 Cell、某线程命中哪个槽位都受调度和可用处理器数量影响，不应成为自动测试的固定期望。

## 实验五：sumThenReset 的安全边界

运行 `observeSumThenResetBoundary()`。案例在没有并发写入的两个明确窗口之间调用 `sumThenReset()`，验证前一窗口被返回并归零，后一窗口从零重新累计。

推荐检查：

1. 第一批工作线程结束后再执行 `sumThenReset()`。
2. 返回值等于第一批总量。
3. 紧接着的 `sum()` 为 0。
4. 第二批更新完成后，`sum()` 只包含第二批总量。

这个实验验证的是静默期契约，不证明并发 `add` 与 `sumThenReset` 存在全局原子切割点。不要为了演示竞态而断言某个不确定的并发结果。

## JDK 8 与 JDK 17/21 断点差异

| 位置 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| `AtomicInteger` 原子入口 | `sun.misc.Unsafe` 与 `valueOffset` | 实现仍用内部 `Unsafe`，方法文档按 VarHandle 内存语义描述 |
| `longAccumulate` | `(x, fn, wasUncontended)` | `(x, fn, wasUncontended, index)` |
| Striped64 CAS | `Unsafe.compareAndSwapLong` | VarHandle 的弱 release CAS |
| `sumThenReset` | 逐位置读取并直接写零 | 逐位置 get-and-set 为零 |

在 JDK 17/21 上找不到 JDK 8 的三参数断点是正常的。始终以 IDE 当前 SDK 的源码为准，不要按文档中的私有变量名反射访问内部状态。

## 并发调试注意事项

- 在 Cell CAS 和结构修改处优先设置线程过滤或“挂起当前线程”，避免所有线程停住后误判为死锁。
- 调试器会改变竞争强度；断点命中路径只能说明本次运行，最终计数和公开 API 契约才适合自动断言。
- JIT 可能内联短小原子方法。需要逐行观察时可使用 IDE 的方法断点，但方法断点开销较大，只用于小规模实验。
- 不通过反射修改 `base/cells/cellsBusy` 制造内部形态；那会绕过内存协议，得到的现象不能代表公开类行为。

## 实验完成标准

- 能解释普通 `value++` 为什么会丢失更新，而 `getAndAdd` 不会。
- 能说明 CAS 返回 false 表示期望值已过期，并写出正确重试思路。
- 能证明函数式更新的函数调用次数可能多于成功提交次数。
- 能画出 `base + Cell[]` 的逻辑总量，并解释 `cellsBusy` 的职责边界。
- 能区分静默期准确 `sum()`、并发近似汇总和非全局原子的 `sumThenReset()`。
- 能指出 JDK 8 与 JDK 17/21 的底层访问和私有签名差异。
