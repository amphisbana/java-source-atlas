# WorkQueue：base、top 与工作窃取

OpenJDK 8u 的 `ForkJoinPool.WorkQueue` 是专用双端工作队列。它不是公开的 `Deque`，也不向任意线程开放对称操作：owner 享有 top 端快路径，stealer 只从 base 端竞争。

## 三个核心字段

```text
base：下一个可被 FIFO poll/steal 的逻辑下标，volatile
top： 下一个 owner push 的逻辑下标，主要由 owner 写
array：长度为 2 的幂的循环数组
```

逻辑任务范围是 `[base, top)`，估算数量为 `top - base`。物理槽位通过 `index & (array.length - 1)` 映射，所以 base/top 可以持续递增，不等于数组下标本身。

`queueSize()` 在并发读取时先读 base 再读 top，并把瞬时负值按 0 处理。它是估计值，不提供线性一致的 size。

## owner push 到 top

只有未共享 WorkQueue 的 owner 调用 `push(task)`：

```text
s = top
array[s & mask] = task    // 有序/发布写
top = s + 1               // 发布新的边界

队列此前很短时 signalWork
容量不足时 growArray
```

先写槽位再推进 top，保证观察到新边界的窃取者能够读取已发布任务。JDK 8 具体使用 Unsafe 的有序写；JDK 17 使用 VarHandle；JDK 21 又改为内部 Unsafe。文章只依赖“先发布元素，再发布可见边界”这一协议。

扩容时 owner 建立更大的 2 次幂数组，并从旧数组的 base 端逐个 CAS 搬迁尚未被窃取的元素。base 可以被 stealer 并发推进，所以搬迁不能按静态数组复制处理。

## 默认 owner 从 top 弹出

默认 `asyncMode=false` 时，owner 的 `pop()` 查看 `top - 1`：

```text
s = top - 1
若 s >= base：
  CAS array[s & mask]: task -> null
  top = s
  返回 task
```

这形成 LIFO：刚 fork 的较小子任务优先被当前 worker 继续处理。owner 对 top 有单写者优势，但最后一个元素仍可能同时被 stealer 从 base 竞争，因此槽位清空需要 CAS。

如果 owner 和 stealer 同时争最后一项，只有一个槽位 CAS 能成功；失败方重读边界或返回空。短暂看到 `base == top`、空槽或旧边界都不等于任务丢失。

## stealer 从 base 窃取

其他 worker 扫描到非空 victim 后，通过 `pollAt(base)` 或等价内联逻辑：

```text
b = victim.base
读取 array[b & mask]
确认 victim.base 仍等于 b
CAS 槽位 task -> null
victim.base = b + 1
```

stealer 取得最老任务，即 FIFO 方向。较老任务通常对应更靠近分治树上层、潜在工作量更大的子树；一次成功窃取更可能让 stealer 保持忙碌。

“FIFO 窃取”只说明从 victim 队列哪端取，不承诺多个 worker 的全局执行顺序，也不保证平均分配。

## 为什么减少同槽竞争

队列含多个任务时：

```text
base → [较老任务 ... 较新任务] ← top
         stealer                 owner
```

owner 与 stealer 大多访问不同缓存位置。只有队列接近空时才争同一槽，这比所有线程共享一个队首或队尾锁更适合大量小任务。

代价是源码必须处理：

- base/top 环绕与数组掩码；
- 任务槽位发布顺序；
- 最后一项的 CAS 竞争；
- 扩容与并发窃取；
- 近空队列的瞬时不一致。

## asyncMode 的本地 FIFO

`nextLocalTask()` 根据 WorkQueue 配置选择：

```text
默认模式：pop()  → top 端 LIFO
asyncMode：poll() → base 端 FIFO
```

stealer 始终从 victim 的 base 端取得任务。asyncMode 改变的是 owner 本地选择方向，让较早 fork 且通常不 join 的事件任务不容易长期压在队列底部。

对于经典递归分治，LIFO 更贴合深度优先和 join 的局部性；不要因为“FIFO 更公平”就无条件开启 asyncMode。

## 外部提交使用共享 WorkQueue

普通线程没有 owner WorkQueue。`pool.submit/execute/invoke` 最终通过 `externalPush`：

1. 使用提交线程的随机 probe 选择一条共享 submission queue。
2. JDK 8 用 `qlock` 保护多生产者对 top 和数组的修改。
3. 快路径空间足够时写入 top 端并发出工作信号。
4. 队列未初始化、冲突或需扩容时进入 `externalSubmit`。
5. worker 扫描并从共享队列 base 端取得任务。

在 JDK 8 的 `workQueues` 数组中，内部通过索引位区分 worker 队列和共享提交队列。这是实现细节；JDK 17/21 已大幅重写容器和扫描协议，业务代码不应依赖奇偶索引或反射数组。

## 为什么需要多条提交队列

如果所有外部提交者都争一个全局队列，ForkJoinPool 会在根任务入口形成热点。按 probe 分散到多条共享队列可以降低生产者竞争；冲突时推进 probe 再尝试其他槽位。

外部提交队列也使用 base/top 方向，但没有唯一 owner，JDK 8 的 top 写入必须加轻量锁。worker 消费依然可以从 base 端 CAS。

## scan 不固定 victim

空闲 worker 会从随机化起点扫描 WorkQueue 集合：

- 找到非空 victim 后尝试 poll base；
- 竞争失败或队列已空则继续扫描；
- 多轮稳定地找不到任务后，worker 才进入非活跃和等待协议；
- 新任务入队会通过 `signalWork` 尝试激活等待 worker 或创建必要 worker。

因此不要根据一次运行日志得出“W2 总会偷 W1”或“任务总在固定槽位”。随机扫描正是避免多个空闲 worker 长期冲向同一 victim 的手段。

## push、pop、poll 的线性化观察

| 操作 | 成功判定点 | 失败后行为 |
| --- | --- | --- |
| owner push | 槽位与 top 按发布顺序对其他线程可见 | 容量不足扩容或拒绝失控增长 |
| owner pop | 目标槽位 CAS 为 null | 可能被 stealer 抢先，重读或返回空 |
| stealer poll | base 槽位 CAS 为 null | victim 改变则继续扫描 |

不要把对 top/base 的某一次读取单独当成任务归属线性化点。真正避免重复执行的是任务槽位从对象到 null 的成功原子竞争。

## 与业务队列的边界

WorkQueue 是调度器内部结构，不提供：

- 有界容量和业务背压；
- 公平消费；
- 可持久化或跨进程任务；
- 按业务优先级排序；
- 精确队列长度。

需要这些能力时应在提交前使用明确的业务队列或其他执行器，不要试图反射修改 WorkQueue。

下一步阅读 [ForkJoinTask：fork、执行、join 与异常](./fork-join.md)。
