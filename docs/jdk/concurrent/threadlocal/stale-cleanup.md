# 弱 key、强 value 与陈旧 Entry 清理

ThreadLocalMap.Entry 继承 `WeakReference<ThreadLocal<?>>`，但它的 `value` 是普通强引用字段：

```text
Thread -> ThreadLocalMap -> Entry
                           ├─ weak referent -> ThreadLocal key
                           └─ strong value  -> 业务对象
```

当应用不再强引用 ThreadLocal key 时，GC 可以清空 Entry 的弱 referent；只要所属 Thread 仍存活且 Entry 尚未清理，map 仍通过强引用保留 value。这种 `key=null, value!=null` 的状态就是 stale Entry。

## 弱 key 为什么不能自动释放 value

弱引用只改变 referent 的可达性，不会自动执行 `entry.value = null` 或把 Entry 从数组移除。ThreadLocalMap 没有为每张 map 维护 ReferenceQueue，也没有后台清理线程；GC 只负责让 `entry.get()` 变成 null。

这样设计避免了每个线程 map 都维护队列和异步清理协调，但把回收时机变成访问驱动：后续 get/set/remove 在相关探测区间发现 stale 时清理，表空间紧张时 rehash 全表清理，线程退出时整张 map 才随 Thread 清空。

所以“ThreadLocal key 是弱引用，因此不会泄漏”是不完整结论。弱 key 防止 map 永久强留已经无其他引用的 ThreadLocal 对象，却不能保证 value 立即释放。

## stale Entry 何时出现

需要同时满足：

1. 当前线程曾经为某个 ThreadLocal set 或 get 初始化过 value。
2. 应用丢失该 ThreadLocal key 的所有强引用，例如把它创建在短生命周期方法中且没有 remove。
3. GC 某次确认 key 只有弱引用并清空 referent。
4. 所属线程仍存活，且之后尚未有清理路径覆盖该 Entry。

GC 发生时间不确定，是否立即回收某个弱 key 也不是 Java 测试可以可靠强制的公开行为。因此自动测试不应以“调用 `System.gc()` 后 key 必须为 null”为断言；源码调试可以观察本次运行，但正确性验证应围绕确定的 remove 和线程池复用行为。

## expungeStaleEntry 清理一个连续 run

已知 staleSlot 的清理步骤是：

1. 把 `table[staleSlot].value` 置 null。
2. 把该数组槽置 null，size 减一。
3. 向后扫描直到首个 null。
4. 后续 Entry 也是 stale 时，同样清 value、清槽、size 减一。
5. 后续 Entry key 存活时，重新计算 home；若 home 不是当前槽，先移除再从 home 线性探测到新空槽。
6. 返回 run 末尾 null 的索引。

清 value 不只是性能优化。只清 table 槽通常也能断开 Entry，但显式置 null 能更早解除大对象或应用类加载器对象图，并让正在被局部变量短暂引用的 Entry 不再保留 value。

## get 如何顺带清理

home slot 直接命中目标 key 时，get 不扫描别处。只有进入 `getEntryAfterMiss` 并在当前探测 run 遇到 stale，才调用 expungeStaleEntry。

这意味着：

- 频繁 get 一个总在 home slot 命中的活 key，不保证清理其他 run 的 stale。
- get 在碰撞链上清理后必须从同一索引继续读取，因为目标 Entry 可能被 rehash 搬回来。
- 找到 null 即结束，不会为了清垃圾继续扫描整张表。

ThreadLocalMap 把常见直接命中成本放在首位，用更弱的全局清理及时性换取快速访问。

## remove 为什么是确定性清理

`remove(key)` 从 key 的 home slot 线性探测。找到身份相同的 Entry 后：

```text
entry.clear()             // 先把弱 referent 清为 null
expungeStaleEntry(index)  // 清 value、移除槽并重排 run
```

remove 不等待 GC，也不依赖启发式扫描碰巧覆盖该位置。方法返回后，当前线程 map 已不再通过目标 Entry 保留 value；同一 run 的其他 stale 也会被清掉。

随后同一线程再次 get 会走 initialValue，因为目标绑定已经不存在。把 value set 为 null 不等于 remove：Entry 仍然存在，get 直接返回 null，也不会重新初始化。

## cleanSomeSlots 的启发式扫描

新 Entry 插入后，`cleanSomeSlots(insertedIndex, size)` 从下一个槽开始扫描。每轮执行 `n >>>= 1`，因此无 stale 时只检查大致对数数量的槽，在插入成本与垃圾发现概率之间折中。

发现 stale 后：

1. 把 n 重置为 table.length，延长后续扫描。
2. 调用 expungeStaleEntry 清完整个当前 run。
3. 从 expunge 返回的尾部 null 继续向后检查。
4. 返回 true，告诉 set 本轮已经做过清理，暂不因原 size 达阈值立即 rehash。

cleanSomeSlots 不是全表保证。一个离插入点较远的 stale 可能多次未被抽样到，直到相关访问、后续启发式扫描、阈值 rehash 或线程结束才消失。

## replaceStaleEntry 清理整段的原因

GC 可能在同一轮清空许多 ThreadLocal key。如果 set 只替换首次遇到的 staleSlot，其前后仍有多个失效项，每次访问都可能重新 rehash 同一 run。

replaceStaleEntry 先向后定位 run 中更早的 stale，再向前寻找目标与其他 stale，最后从合适起点 expunge 并调用 cleanSomeSlots。目标是一次处理整个连续区域，而不是保证整个 table 没有 stale。

## rehash 前先清垃圾

set 插入后若启发式清理没有删除任何 stale 且 size 达 threshold，进入 rehash：

```text
expungeStaleEntries()  // 全表扫描
if size 仍足够大:
  resize()
```

先清理能避免把已经失效的 Entry 搬到更大数组，也避免仅因 stale 被 size 计入就扩容。扩容本身同样跳过 stale 并清空其 value。

## 长生命周期线程为何风险更高

短命线程退出时 Thread 会清空 threadLocals 和 inheritableThreadLocals，整张对象图可以回收。线程池 worker、容器线程、定时线程和事件循环可能与应用进程同寿命：

- static ThreadLocal key 一直存活时，Entry 不是 stale，value 会一直绑定到 worker，除非 remove。
- key 不再可达时，Entry 可能变 stale，但 value 要等访问驱动清理。
- value 引用应用类实例、Class、类加载器或大缓存时，会放大内存和热部署卸载风险。

第一种情况常伴随数据串用，虽然不是“弱 key 泄漏”；第二种才是典型 stale value 滞留。两者的工程解法都不是催促 GC，而是在逻辑作用域结束的 finally 中 remove。

## 不要用反射做生产清理

扫描所有线程的私有 map 并强行改 Entry[] 会破坏线程所有者模型、模块封装和开放寻址探测链。诊断工具可以在受控环境观察对象引用，生产代码应修复建立绑定的入口：

- 让创建者负责 finally remove。
- 对任务提交使用统一装饰器建立和清理上下文。
- 避免方法内临时 new ThreadLocal 后丢失 key。
- 对第三方库泄漏使用其公开清理 API 或升级修复版本。

## JDK 17 和 21 的清理边界

较新版本把弱引用判断写成 `e.refersTo(null)`，但仍没有把 value 改为弱引用，也没有为 ThreadLocalMap 添加持续后台清理。JDK 21 虚拟线程结束时同样清除自身 ThreadLocal 引用；若虚拟线程长时间存活或一次创建海量带大 value 的虚拟线程，内存成本仍需评估。
