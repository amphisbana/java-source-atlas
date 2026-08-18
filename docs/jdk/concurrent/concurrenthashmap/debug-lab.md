# ConcurrentHashMap 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.concurrent.ConcurrentHashMapDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ConcurrentHashMapDebugLab
```

## 推荐断点

| 方法 | 观察变量 | 目标 |
| --- | --- | --- |
| `spread` | 原始哈希、扰动哈希 | 下标准备 |
| `initTable` | `sizeCtl`、`n` | 单线程初始化 |
| `putVal` | `tab`、`i`、`f`、`fh`、`binCount` | CAS、桶锁和树分支 |
| `addCount` | `baseCount`、`counterCells`、`s` | 分散计数和扩容检查 |
| `transfer` | `transferIndex`、`nextTable` | 领取迁移区间 |
| `helpTransfer` | `sizeCtl`、`nextTab` | 写线程协助扩容 |
| `ForwardingNode.find` | `tab`、`e`、`n`、`h` | 读取沿转发节点切换到新表 |
| `TreeBin.find` | `lockState`、`first`、`root` | 链表退路或带读计数的树查找 |
| `TreeBin.putTreeVal` | `p`、`dir`、`searched`、`kc` | 强碰撞桶查找或插入树节点 |
| `Traverser.advance` | `tab`、`index`、`baseIndex`、`stack` | 遍历在新旧表之间保存和恢复位置 |
| `computeIfAbsent` | `ReservationNode`、`binCount` | 单键原子计算 |

## 实验一：空桶与碰撞桶

运行 `observePutPaths()`。第一个键通常走空桶 CAS；固定哈希的第二个键进入同一桶，需要在桶首同步块中追加。

## 实验二：受控扩容与 ForwardingNode

运行 `observeResizeAndForwardingNode()`。场景使用 `new ConcurrentHashMap<>(1 << 14)`；在 JDK 8 中首次分配的内部表容量为 `1 << 15`，扩容阈值为 `24,576`。实验用公开 API 建立一个可重复的迁移窗口：

1. 先写入 `24,575` 个映射，其中键 `32766`、`32767` 分别落在旧表最后两个桶。
2. 控制线程对键 `32766` 执行 `computeIfPresent`，在映射函数内等待，从而占住 `table[32766]` 的桶首监视器。
3. 扩容发起线程写入第 `24,576` 个映射。`transfer` 从高下标向低下标扫描，先迁移 `table[32767]` 并安装 `ForwardingNode`，随后确定阻塞在相邻的 `table[32766]`。
4. Lab 通过公开的 `Thread.State.BLOCKED` 确认这个位置，不读取 `table` 或 `transferIndex` 私有字段。此时旧表尚未发布结束，低区任务仍可领取。
5. 同时运行读取、覆写和遍历三个观察动作，完成后释放 `table[32766]`，扩容发起线程继续迁移并补齐剩余 4,095 个新键。

| 观察动作 | 公开 API | 稳定命中的源码路径 |
| --- | --- | --- |
| 重复读取键 `32767` | `get` | 已迁移桶上的 `ForwardingNode.find` |
| 重复覆写键 `32767` | `put` | `putVal → helpTransfer`，并在低区尚未领取时登记协作者 |
| 遍历 `entrySet` | Iterator | `Traverser.advance → pushState/recoverState` |

这个控制方式利用的正是 `transfer` “区间内从高向低扫描”和“迁移桶前锁住桶首”的源码协议。控制台只核对稳定公开结果：最终 `mappingCount`、预期数量、读取缺失和最终缺失；弱一致遍历看到多少条只用于观察，不作为精确快照断言。

推荐在 JDK 8 源码使用以下完整签名设方法断点：

```text
ConcurrentHashMap.transfer(Node[], Node[])
ConcurrentHashMap.helpTransfer(Node[], Node)
ConcurrentHashMap.ForwardingNode.find(int, Object)
ConcurrentHashMap.Traverser.advance()
ConcurrentHashMap.Traverser.pushState(Node[], int, int)
ConcurrentHashMap.Traverser.recoverState(int)
```

在 `transfer` 中先观察 `tab.length == 32768` 的调用，再记录每个线程的 `bound`、`i` 和 `transferIndex`。某个区间被领取后游标已经下降，但 `i` 仍在该区间从高向低扫描；不要把游标值当成已完成桶数量。

受控窗口建立后，键 `32767` 的读写会稳定经过 `ForwardingNode.find` 与 `helpTransfer`。调试器仍应将断点设置为“仅挂起当前线程”：暂停全部线程会连同持锁控制线程和闩锁协调线程一起冻结，把教学用等待误判为容器死锁。Lab 为单线程挂起预留 5 分钟观察时间。

## 实验三：TreeBin 查找与插入

运行 `observeTreeBinPaths()`。Map 初始容量设为 64，使首次分配容量已经不小于 `MIN_TREEIFY_CAPACITY`；随后写入 12 个不同但哈希固定为 7 的 `CollisionKey`，不会因小表而只扩容、不树化。

依次设置断点：

```text
ConcurrentHashMap.treeifyBin(Node[], int)
ConcurrentHashMap.TreeBin.find(int, Object)
ConcurrentHashMap.TreeBin.putTreeVal(int, Object, Object)
```

第一次 `get(new CollisionKey(5, 7))` 观察 `TreeBin.find`；覆写编号 5 观察 `putTreeVal` 返回已有节点；新增编号 12 观察返回 `null`，并在需要平衡时进入 `lockRoot`。这些判断都由最终公开值和 size 验证，不要求测试代码反射桶首类名。

## 实验四：并发 merge

运行 `observeAtomicMerge()`。多个线程对同一键执行 `merge(key, 1, Integer::sum)`，最终值应等于线程数乘以每线程次数。

对比手写的 `get + put` 可以理解原子复合 API 的必要性，但不要在自动测试中故意依赖数据竞争一定丢失更新。

## 实验五：computeIfAbsent

运行 `observeComputeIfAbsent()`。多个线程同时请求同一键，映射函数只创建一次值，其他线程取得同一映射。

映射函数中设置断点时会延长桶的协调时间，所以只用于本地学习，不据此测量性能。

## 实验六：禁止 null

运行 `observeNullBoundary()`，确认空键和空值都会立即抛出 `NullPointerException`。这一约束让并发读取可以用 `null` 唯一表达缺失。

## 并发调试提示

- 给断点设置线程过滤，避免所有工作线程同时挂起。
- 不在持有桶锁的代码上使用“挂起全部线程”，否则容易把调试行为误判为死锁。
- 线程调度顺序不是断言；只验证最终计数和 API 契约。
- 断点实验面向本仓库固定的 OpenJDK 8u 主版本；在 JDK 17/21 中总体协议相同，但私有辅助方法和局部变量可能变化。
