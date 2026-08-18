# AtomicInteger：CAS 与原子读改写

`AtomicInteger` 的结构非常小：一个 `volatile int value` 加上一组围绕它的原子方法。源码的重点不在字段数量，而在“先观察旧值，再只在旧值仍未变化时写入”的条件更新协议。

## value 是唯一状态

OpenJDK 8u 中，构造方法直接写 `value`，之后常见访问可以分成四类：

| 操作 | 代表方法 | 对外语义 |
| --- | --- | --- |
| volatile 读取/写入 | `get`、`set` | 读取或覆盖当前值 |
| release 风格写入 | `lazySet` | 以更弱的写入时序最终发布新值 |
| 条件更新 | `compareAndSet` | 当前值等于期望值时才更新 |
| 原子读改写 | `getAndAdd`、`getAndSet` | 一次操作返回旧值并写入新值 |

`value` 是 `int`，溢出规则与普通 Java 整数运算相同：超过 `Integer.MAX_VALUE` 会回绕到负数，原子性不会自动提供越界检查。

## compareAndSet 的判定边界

一次 CAS 可以抽象为：

```text
compareAndSet(expect, update)
  -> 原子读取当前 value
  -> value == expect ? 写入 update 并返回 true : 不写入并返回 false
```

假设初始值为 10：

| 线程 | 动作 | 结果 |
| --- | --- | --- |
| T1 | 读取旧值 10，计划写 11 | 尚未提交 |
| T2 | `compareAndSet(10, 12)` | 成功，当前值变为 12 |
| T1 | `compareAndSet(10, 11)` | 失败，当前值保持 12 |

失败不是异常，也不意味着底层原子指令损坏；它通常表示调用者持有的期望值已经过期。需要重试时必须重新读取当前值，并重新判断业务条件。

成功的 CAS 只证明提交瞬间值等于 `expect`。如果值经历 `10 -> 12 -> 10`，只比较整数值的 CAS 无法识别这次往返，这就是 ABA 边界。状态机确实需要识别版本时，可以把值和版本合并编码到更宽的原子状态，或使用锁；不要假设 CAS 自动记录历史。

## getAndAdd 为什么能避免丢失更新

`getAndAdd(delta)` 在 OpenJDK 8u 中直接委托给 `Unsafe.getAndAddInt`。对调用者而言，它是一个不可分割的读改写：

```text
old = 当前值
当前值 = old + delta
返回 old
```

`addAndGet(delta)` 使用同一个底层读改写结果，再返回 `old + delta`。因此两者的区别是返回旧值还是新值，不是更新时机不同：

| 初始值 | 调用 | 返回值 | 调用后值 |
| --- | --- | --- | --- |
| 7 | `getAndAdd(3)` | 7 | 10 |
| 7 | `addAndGet(3)` | 10 | 10 |

不要把源码中的一次 `Unsafe.getAndAddInt` 简化成“所有平台都执行同一条 CPU 指令”。JVM 可以使用内建函数和平台原子指令，也可以采用符合语义的其他实现；Java API 保证的是原子与内存语义，不是特定汇编形态。

## 函数更新为什么要求无副作用

OpenJDK 8u 的 `updateAndGet` 本质是 CAS 循环：

```text
循环：
  prev = get()
  next = updateFunction(prev)
  CAS(prev, next) 失败则重新开始
成功后返回 next
```

另一个线程可能在函数计算后先更新 `value`，导致 CAS 失败。此时函数会针对新值再次执行。日志发送、扣款、序列落库等副作用如果写进函数，可能发生多次，即便最终只提交了一次数值更新。

正确理解是：**数值提交最多成功一次，不代表用户函数最多调用一次**。函数应只根据参数计算结果，把外部副作用放到成功更新之后，并另外设计幂等或事务边界。

JDK 17/21 的循环会在“弱 CAS 失败但重新读取的值仍等于旧值”时复用已计算的 `next`，减少某些重复计算；一旦观察到值变化，函数仍可能重算，所以无副作用要求没有改变。

## CAS 循环不等于无成本

CAS 不会像互斥锁那样因为一次竞争失败就进入等待队列。竞争线程可以持续重读和重试，这通常避免了阻塞切换，但高竞争下会带来：

- 同一缓存位置反复失效和迁移；
- 大量 CAS 失败与重复计算；
- 某个线程可能多次失败，API 不承诺公平；
- 复杂函数的重算成本被放大。

这正是计数热点常考虑 `LongAdder` 的原因。若必须得到每次更新后的唯一值，则仍应保留单点原子状态，不能为了吞吐改成没有返回值的分段累加。

## 原子性只覆盖一次方法调用

下面的 `incrementAndGet` 本身原子，但“达到阈值后只执行一次动作”并不天然成立：

```java
int current = counter.incrementAndGet();
if (current >= limit) {
    trigger();
}
```

多个线程得到不同但都大于阈值的值时，会重复执行 `trigger`。如果目标是只有一个线程完成状态迁移，应使用 CAS 把状态从“未触发”切换到“已触发”，并明确重试和失败路径。

多个原子对象之间同样没有整体一致性。`a.get()` 与 `b.get()` 可能来自两个不同时刻；依次更新 `a`、`b` 时，其他线程也能观察到中间状态。

## 内存语义与版本差异

OpenJDK 8u 通过 `sun.misc.Unsafe` 和 `valueOffset` 操作字段：

- `get/set` 对应 volatile 读写；
- `lazySet` 使用有序写，适合只需要 release 发布的场景；
- `compareAndSet/getAndAdd/getAndSet` 提供各自公开的原子读改写语义。

JDK 17/21 的 Javadoc 统一借助 `VarHandle` 术语精确定义内存效果，并增加 `getPlain`、`getOpaque`、`getAcquire`、`setRelease`、`compareAndExchange` 和多种弱 CAS。值得注意的是，当前 `AtomicInteger` 源码实际仍调用 `jdk.internal.misc.Unsafe`，并注明直接用 VarHandle 会遇到尚未解决的启动循环依赖。文档语义是 API 契约，内部调用类不是业务可依赖接口。

## 推荐断点

| 方法 | 观察变量 | 验证目标 |
| --- | --- | --- |
| `compareAndSet` | `expect/update` 或较新版本的 `expectedValue/newValue`、`value` | 期望值匹配与失败分支 |
| `getAndAdd` | `delta`、调用前后 `value`、返回值 | 返回旧值的原子读改写 |
| `updateAndGet` | `prev`、`next`、CAS 结果 | 竞争导致用户函数重算 |
| `lazySet` | `newValue` | 确认当前 JDK 使用的 release 写入口 |

下一步阅读 [LongAdder](./sum-version.md)，对比同一位置竞争与分散写入的取舍。
