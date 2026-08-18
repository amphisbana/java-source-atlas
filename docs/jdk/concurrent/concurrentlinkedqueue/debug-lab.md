# ConcurrentLinkedQueue 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/concurrent/ConcurrentLinkedQueueDebugLab.java
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ConcurrentLinkedQueueDebugLab
```

实验只使用公开 API。`Node.item/next`、`head/tail` 和 CAS 分支通过 IDE 附加的当前 JDK 源码观察，不需要反射或 `--add-opens`。

## 实验一：FIFO 和 null 边界

运行 `observeFifoAndNullBoundary()`：依次 offer A、B、C，再 poll 到空。断言应建立在公开行为上：

1. 非 null 元素的 offer 返回 true；
2. 返回顺序是 A、B、C；
3. 空队列 poll 返回 null；
4. offer null 抛 `NullPointerException`。

推荐在第一次 offer 设置断点，确认 `N0.next` 的 CAS 已成功而 tail 仍可能停在 N0；第二次 offer 才可能把 tail 一次推进到 B。不要把这次形态写进自动测试。

## 实验二：两个生产者的局部顺序

运行 `observeConcurrentProducers()`。两个线程各自按递增序号放入一组元素，闸门只控制同时开始；最终主线程排空队列并分别检查 P1、P2 的子序列仍递增。

跨生产者的全局交错由真实 `casNext` 成功顺序决定，可能是：

```text
P1-0, P2-0, P2-1, P1-1, ...
```

也可能是其他合法顺序。测试不能断言线程启动顺序或一个生产者连续占据全部位置，但同一生产者按程序顺序完成的 offer 不会在 FIFO 队列中倒序。

## 实验三：多个消费者唯一取走

运行 `observeConcurrentConsumers()`。主线程先在静默期放入固定编号，四个消费者并发 poll，使用线程安全集合记录结果。

观察重点：

| 位置 | 变量 | 判断 |
| --- | --- | --- |
| `poll` | `h/p/q/item` | 是否跳过 null item 节点 |
| `Node.casItem` | 期望 item、目标 null | 每个节点只有一个线程成功 |
| `updateHead` | 旧 h、新 p | CAS 失败是否仍能返回已认领元素 |
| `succ` | `p.next` | 自链接时是否回到当前 head |

最终只断言全部编号恰好出现一次。哪个消费者取得哪个编号取决于调度，不是 API 契约。

## 实验四：弱一致迭代

运行 `observeWeakIterator()`。迭代器创建时队列已有 A、B、C；随后加入 D，再完成遍历。

实验稳定验证：

- 遍历不会因并发风格修改抛 `ConcurrentModificationException`；
- A、B、C 各返回一次；
- D 是否在本次迭代可见只记录，不写成依赖。

在 `Itr.advance()` 观察 `nextNode` 与 `nextItem`。可另开调试副本，让迭代器在 `hasNext()` 后暂停，再由另一线程 poll 当前节点；恢复后 `next()` 仍可以返回先前缓存的元素。这是观察承诺，不是重复消费。

## 实验五：安全发布消息字段

运行 `observePublication()`。生产者先写普通字段 `payload`，再 offer 消息；消费者成功 poll 到同一个对象后读取字段。

断点顺序：

1. 生产者写 `message.payload`；
2. `offer` 成功执行 `casNext`；
3. 消费者沿 next 读取节点并成功 `casItem`；
4. 消费者读取 payload。

这个案例验证队列的 happen-before 交接。若生产者在 offer 之后继续无同步修改 payload，则属于另一场数据竞争，不受这条发布边界保护。

## JDK 8 与 JDK 17/21 断点差异

| 目标 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| Node CAS | `Unsafe.compareAndSwapObject` | `VarHandle.compareAndSet` |
| 节点初始化 | `Unsafe.putObject` 的松弛写 | 普通字段初始化配合发布协议 |
| head/tail CAS | 类级 `Unsafe` 字段偏移 | `HEAD/TAIL` VarHandle |
| 清理 | 各遍历路径内的惰性解链 | 统一跳过死亡节点，并增加 `bulkRemove` 辅助路径 |

私有帮助方法和局部变量会变化。跨版本通用断点优先放在 `offer/poll/iterator` 公开入口和“CAS next/item”的语义位置。

## 并发实验的截止与清理

配套案例和测试遵循：

1. CountDownLatch 只建立必要的先后关系，不用 sleep 猜调度；
2. 所有 `await/get` 都有明确秒级截止时间；
3. finally 中释放闸门并 `shutdownNow`；
4. 等待线程池终止，避免测试结束后遗留线程；
5. 不断言 head/tail、节点数量或 CAS 失败次数。

## 实验完成标准

- 能指出 offer 与 poll 各自的线性化点。
- 能解释 tail 滞后为什么不影响已经完成的入队。
- 能区分 item 逻辑删除、head 推进和节点物理解链。
- 能说明旧 head 自链接对 GC 可达性和过期遍历恢复的作用。
- 能写出弱一致迭代允许与不允许的断言。
- 能说明 size 为什么不能参与并发检查后执行协议。
