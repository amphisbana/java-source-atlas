# ConcurrentHashMap：计数与原子复合操作

## 为什么不用一个 AtomicLong 记录 size

如果所有写线程都 CAS 同一个计数变量，高并发下会不断失败重试。JDK 8 采用类似 `LongAdder` 的分散计数：

```text
低竞争 → CAS 更新 baseCount
发生竞争 → 按线程探针更新 counterCells 中的某个 Cell
读取总量 → baseCount + 所有 CounterCell.value
```

`addCount` 在增加计数后还会检查是否达到扩容条件；如果扩容已经开始，线程可能参与 `transfer`。

完整的 `base + Cell[]`、线程 probe 与扩容协议见 [Atomic、LongAdder 与 Striped64](../atomic/striped64.md)。`ConcurrentHashMap.CounterCell` 使用同类思想，但并不直接持有一个 `LongAdder`。

## size 不是事务快照

并发更新期间，`size()` 汇总多个计数位置，返回的是瞬时观察结果。它适合监控和近似决策，不适合作为“没有其他线程改变 Map”的证明。

返回值超过 `Integer.MAX_VALUE` 时 `size()` 会封顶；需要长整型估计可使用 `mappingCount()`，但它同样不是原子快照。

## computeIfAbsent 的原子语义

`computeIfAbsent(key, mappingFunction)` 对单个键执行“缺失时计算并放入”。调用方不需要先 `get` 再 `putIfAbsent`：

```java
map.computeIfAbsent(key, ignored -> new LongAdder()).increment();
```

对同一个键，整个计算与写入按 ConcurrentHashMap 的规则原子完成。映射函数应当短小，并且不能在计算中递归更新同一个键；部分递归更新会被检测并抛出 `IllegalStateException`。

### 返回 null

映射函数返回 `null` 时不建立映射。函数本身不能为 `null`，键也不能为 `null`。

## merge 适合聚合

`merge(key, value, remappingFunction)` 的语义是：

- 键不存在时直接写入给定非空值；
- 键存在时用旧值和给定值计算新值；
- 计算结果为 `null` 时删除映射。

计数场景可以使用：

```java
map.merge(word, 1, Integer::sum);
```

这个单键更新是原子的，比手工 `get`、加一、`put` 更安全。

## putIfAbsent 与 replace

- `putIfAbsent` 只在键无映射时写入。
- `replace(key, oldValue, newValue)` 只有当前值匹配时替换。
- `remove(key, value)` 只有键和值都匹配时删除。

这些条件更新避免把检查和修改拆成两个可能被其他线程插入的步骤。

## 批量操作与并行阈值

ConcurrentHashMap 提供 `forEach`、`search`、`reduce` 的并行版本。`parallelismThreshold` 表示估计元素数达到什么规模时允许拆分任务：

- `Long.MAX_VALUE` 基本强制顺序执行；
- `1` 尽可能拆分；
- 中间值需要结合任务成本和公共 ForkJoinPool 负载评估。

函数必须能容忍并发变化，不能假设遍历的是固定快照。

## JDK 8 到 JDK 17/21

桶数组、CAS 空桶、桶首同步、ForwardingNode、协作扩容和分散计数的总体设计保持稳定。较新 JDK 把底层原子访问从 `Unsafe` 的具体调用逐步迁移或封装到更新后的平台内部机制，私有字段偏移量和辅助方法不是兼容接口。
