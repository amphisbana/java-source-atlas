# ThreadLocal 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/concurrent/ThreadLocalDebugLab.java
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ThreadLocalDebugLab
```

实验通过公开 API 验证线程隔离、remove、线程池复用和继承快照。私有 hash、Entry[] 和 stale run 只在 IDE 附加当前 JDK 源码后观察，不通过反射打开 java.lang；自动测试也不依赖 `System.gc()` 必须生效。

## 实验一：每线程独立初始化

运行 `observePerThreadValues()`。主线程先连续两次 get，同一 value 只初始化一次；命名工作线程第一次 get 触发自己的初始化；主线程 remove 后再次 get 会重新初始化。

建议断点：

1. `ThreadLocal.get()`。
2. `setInitialValue()`。
3. `getMap(Thread)`。
4. `createMap(Thread,Object)`。
5. `ThreadLocalMap.getEntry(ThreadLocal)`。

观察 `Thread.currentThread()`、`t.threadLocals`、当前 ThreadLocal 的 threadLocalHashCode 和 Entry.value。不要只看 value 内容，要确认两张 map 分别属于哪个 Thread。

## 实验二：线程池污染与 finally 清理

运行 `observeThreadPoolPollutionAndCleanup()`。单线程执行器固定复用同一 worker：

1. 任务 A set `request-A` 后故意不 remove。
2. 任务 B 不 set，直接 get 到 `request-A`。
3. 清理 worker 上的旧绑定。
4. 任务 C 使用 try/finally set/remove。
5. 任务 D 再 get 时得到 null。

在 `ThreadLocal.set/get/remove` 断点确认四个任务的 Thread 对象身份相同。这个实验稳定验证的是公开跨任务污染，不需要制造 key GC 或读取私有数组。

## 实验三：继承快照发生在构造时

运行 `observeInheritableSnapshot()`。父线程先 set `parent-v1` 并构造 child，然后改为 `parent-v2` 才 start child。child 仍读取 v1，且 child 自己 set 的值不替换父线程 Entry。

JDK 8 建议断点：

- `Thread.init(...)` 中复制 inheritableThreadLocals 的条件分支。
- `ThreadLocal.createInheritedMap(...)`。
- `ThreadLocalMap(ThreadLocalMap parentMap)` 复制构造器。
- `InheritableThreadLocal.childValue`。

确认 childValue 在父线程执行、child 尚未 start。若 value 是可变对象，默认 childValue 返回同一引用；需要隔离时重写 childValue 创建真正副本。

## 实验四：弱 key 和 stale value 只做观察

运行 `observeWeakKeyBoundary()`。工作线程创建一个局部 ThreadLocal 并保存一块 value，方法返回后主线程只保留 key 和 value 的 WeakReference。实验有限次数请求 GC，打印本次是否观察到 key 清空，以及 worker 存活时 value 是否仍可达。

这个输出不是通过标准：

- `System.gc()` 只是建议，key 本次不回收完全合法。
- 即使 key 已清空，也不能用公开 API 精确断言私有 Entry 何时被启发式清理。
- 实验最后终止 worker，让 Thread 自身生命周期收口，不在进程中遗留探针 value。

若本次 key 已被回收，可在调试器展开 worker.threadLocals.table，寻找 `entry.get()==null` 且 value 非 null 的 stale Entry；不要把槽位编号写成固定期望。

## 实验五：黄金增量与线性探测

可以在同一线程创建并 set 多个 ThreadLocal，在下面位置下断点：

| 位置 | 观察内容 |
| --- | --- |
| `nextHashCode()` | 相邻 hash 相差 `0x61c88647` |
| ThreadLocalMap 构造器 | 初始长度 16、首个 home index |
| `ThreadLocalMap.set` | `i`、`e`、`k` 和 nextIndex |
| `replaceStaleEntry` | `staleSlot`、`slotToExpunge` 与交换位置 |
| `expungeStaleEntry` | stale value 清空和活 Entry 重新落位 |
| `cleanSomeSlots` | n 右移以及发现 stale 后重置为 len |

全局 hash 还会被 JVM 和其他代码创建的 ThreadLocal 消耗，具体首个 hash 和槽位不可预测。调试应验证公式与相对变化，不断言 `ctx-A` 必须在某一数组索引。

## JDK 8 与 JDK 17/21 断点差异

| 目标 | OpenJDK 8u | OpenJDK 17/21 |
| --- | --- | --- |
| 弱 key 判断 | `e.get() == key/null` | `e.refersTo(key/null)` |
| get 辅助结构 | get 内直接取当前 Thread | JDK 21 拆出接受 Thread 参数的私有 get |
| Thread 字段 | 平台线程的两张 map | JDK 21 虚拟线程也继承 Thread 字段模型 |
| 继承控制 | 常用构造器默认继承 | JDK 21 Thread.Builder 可显式关闭 |

JDK 21 调试虚拟线程时，应展开虚拟 Thread 对象自己的 threadLocals，不要误看当前 carrier 的 map。项目实验按 Java 8 release 编译，不直接调用 Thread.ofVirtual 或 ScopedValue。

## 实验完成标准

- 能画出 Thread、ThreadLocalMap、弱 key Entry 和 value 的引用方向。
- 能解释同一个 ThreadLocal 如何在两个线程中得到不同 value。
- 能说明黄金增量减少常见连续 key 聚集，但不消除碰撞。
- 能跟踪 staleSlot 清理后同一 run 的活 Entry 为什么要重新落位。
- 能稳定复现单线程池的数据污染，并用 finally remove 消除。
- 能证明 InheritableThreadLocal 在 new Thread 时复制，而不是 start 或 submit 时复制。
- 能说明 GC 探针为何只能观察，不能成为自动测试断言。
