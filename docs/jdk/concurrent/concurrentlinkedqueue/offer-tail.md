# ConcurrentLinkedQueue：offer、CAS 与滞后 tail

`offer` 的正确性不依赖 `tail` 永远指向末节点。它只要求从某个可靠入口最终能找到唯一 `next == null` 的节点，再用 CAS 把新节点发布为后继；`tail` 只是降低寻找成本的提示指针。

## 初始节点为什么可以没有元素

空队列构造为：

```text
head ─┐
      v
     N0(item=null, next=null)
      ^
tail ─┘
```

第一个 `offer(A)` 不会替换 N0，而是构造 `A` 节点并竞争 `N0.next`。构造阶段的节点尚未共享，OpenJDK 8u 可以用较弱的普通写设置 `item`；成功的 `casNext(null, newNode)` 才把节点安全发布给其他线程。

null 被保留为逻辑删除标记，因此公开方法拒绝 null 元素。否则 `poll`、`peek` 和遍历无法区分“真实 null”与“节点已经被取走”。

## offer 的完整循环

OpenJDK 8u 可以压缩为：

```text
t = tail
p = t

循环：
  q = p.next

  q == null
    -> p 是本轮看到的末节点
    -> p.casNext(null, newNode)
         成功：元素已经入队；必要时尝试推进 tail；返回 true
         失败：别人先接上节点，重新读取 p.next

  p == q
    -> p 是已离队的自链接旧节点
    -> tail 已变化则跳到新 tail，否则跳到 head

  其他情况
    -> 向 q 前进一步
    -> 每走两跳顺便检查 tail 是否已有更好位置
```

循环中没有“锁住 tail 再插入”的临界区。多个生产者可以同时走到同一个末节点，但只有一个 `casNext(null,newNode)` 成功；失败者沿获胜节点继续寻找新的末端。

## casNext 是入队线性化点

假设 T1 和 T2 都看到 `P.next == null`：

```text
T1: P.casNext(null, A) -> true
T2: P.casNext(null, B) -> false
```

T1 的 CAS 成功时 A 已经属于队列，即使 T1 随后暂停、还没有更新 `tail`，消费者也能从 `head` 走到 A。T2 重新读取 `P.next` 得到 A，再从 A 继续，最终可以把 B 接到 A 后面。

这次竞争的 FIFO 顺序由成功发布链表后继的 CAS 顺序决定，不由线程启动、对象构造或方法调用开始的墙上时间决定。

## tail 为什么故意允许滞后

成功入队后，源码只有在 `p != t` 时才尝试：

```text
casTail(t, newNode)
```

这形成“每两跳推进一次”的松弛策略。以空队列连续插入 A、B 为例：

```text
offer(A): N0.next CAS 为 A，p == t，tail 仍在 N0
offer(B): 从 N0 走到 A，A.next CAS 为 B，p != t，尝试 tail: N0 -> B
```

若每次插入都必须更新 `tail`，每个元素至少多竞争一个共享热点 CAS。允许一个短距离滞后可减少写流量；只要末节点仍能从当前入口找到，`tail` 更新失败就不是 `offer` 失败。

`head` 和 `tail` 由不同线程独立推进，极端交错下 `tail` 甚至可以落在已经被 `head` 越过的旧节点上。`offer` 的自链接检测负责恢复，不要求两个指针组成瞬时一致快照。

## 两跳检查如何避免长时间追旧指针

循环局部变量 `t` 保存最初观察到的 tail，`p` 是当前扫描位置。普通前进分支使用类似下面的选择：

```text
p 已经离开 t 且 tail 已变化
  -> 跳到新的 tail
否则
  -> p = q
```

这样不会每经过一个节点都重新争读和写 `tail`，也不会在明显过期的长链上一直单步追赶。这里的“slack=2”是 OpenJDK 8u 的性能策略，不是公开队列容量或跨版本常量。

## 旧节点自链接如何让扫描恢复

`poll` 推进 `head` 成功后，会把旧 head 的 `next` 指向自己：

```text
oldHead.next = oldHead
```

一个较早暂停的生产者恢复时可能仍持有 `p = oldHead`。它读到 `p.next == p`，说明已经从当前队列链上掉下去，不能继续沿这个 next：

```text
tail 已经变化 -> 跳到新 tail
tail 仍是这个旧节点 -> 跳到当前 head
```

自链接既切断旧节点到新生代链表的普通强连接，又给并发遍历留下一个明确的“重新定位”信号。它不是环形队列，也不表示当前活跃链出现了环。

## 动画：发布、滞后与自链接恢复

下面固定一种合法交错，展示 `offer(A/B/C)`、`poll(A/B)` 和一个持有过期 tail 的生产者怎样恢复。真实运行中的线程胜负不固定，动画只对应源码允许的状态转换。

<ConcurrentLinkedQueueAnimation />

## CAS 失败后的协助推进

无锁算法常把一次失败转化为新信息：

- `casNext` 失败说明别的生产者已经发布节点，失败者沿它继续；
- `casTail` 失败说明其他线程可能已经把提示指针推进到更好位置，无需回滚；
- 读取到非 null `next` 说明当前不是末节点，直接向前；
- 读取到自链接说明本地引用过期，重新从 tail/head 定位。

所以循环不是机械地对同一地址无限 CAS。每次失败或读取都会重新验证链表状态，系统的某个线程完成操作后，其他线程可以利用它留下的新结构继续推进。

## 内存发布边界

生产者在节点发布前完成元素构造和字段写入，成功 CAS `next` 后，消费者通过队列访问这个元素。类 Javadoc 给出 happen-before 保证，确保消费者能看到入队前动作。

这个保证不覆盖取出后的任意并发修改。例如生产者在 `offer(message)` 返回后继续无同步修改 `message`，消费者与这次后续写仍可能数据竞争。队列负责安全交接引用，不把可变对象自动变成线程安全对象。

## JDK 17/21 对照

较新 JDK 用 `VarHandle` 替代 `Unsafe` 字段偏移，并调整遍历和清理辅助方法。稳定主线仍是：

1. 新节点先私有构造；
2. CAS 末节点的 null 后继完成发布；
3. tail 允许滞后并由参与者协助推进；
4. 过期、自链接节点触发重新定位。

断点时不要假设局部变量一定叫 `p/q/t`，也不要把某一版本“每两跳更新”的具体表达复制进业务实现。无锁链表的正确性依赖可达性、发布顺序和所有重检分支，删掉看似多余的分支会打开竞态窗口。

## 推荐断点

1. `ConcurrentLinkedQueue.offer(E)`：记录 `t/p/q/newNode`。
2. `Node.casNext`：确认只有从 null 成功的 CAS 才发布元素。
3. `casTail`：观察失败时 `offer` 为什么仍返回 true。
4. `p == q` 分支：先让线程持有旧节点，再由其他线程推进 head。
5. `updateHead`：对照旧 head 自链接何时出现。

下一步阅读 [poll、逻辑删除与弱一致遍历](./poll-iteration.md)，把元素删除线性化点与物理链清理分开。
