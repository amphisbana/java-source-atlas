# 字节码、重入与释放：synchronized 到底锁在哪里

## 同步代码块与同步方法不是同一种字节码形态

同步代码块把 monitor 对象作为一个明确表达式：

```java
void block(Object monitor) {
    synchronized (monitor) {
        work();
    }
}
```

概念化字节码如下：

```text
aload_1
dup
astore_2
monitorenter
invokestatic work
aload_2
monitorexit
goto normalReturn
exceptionHandler:
  astore_3
  aload_2
  monitorexit
  aload_3
  athrow
```

三个细节最值得看：

1. `monitorenter` 消费对象引用；对象为 `null` 时抛 `NullPointerException`。
2. javac 把同一个 monitor 引用保存到局部变量，保证退出时使用的对象与进入时一致。
3. 正常路径和异常路径都有 `monitorexit`，所以临界区抛异常不会永久占有 monitor。

同步实例方法通常没有显式 `monitorenter`：class 文件给方法设置 `ACC_SYNCHRONIZED`，JVM 调用方法时自动获取 `this` 的 monitor。同步静态方法使用对应 `Class` 对象的 monitor，因此 `synchronized static` 与同类实例同步方法并不竞争同一个对象。

```bash
javac MonitorSample.java
javap -c -v MonitorSample
```

阅读输出时先看 method flags，再看 Code 区。只搜索 `monitorenter` 会漏掉全部同步方法。

## monitorenter 的公开语义

执行 `monitorenter` 时可以分三种情况：

| 当前情况 | 结果 |
| --- | --- |
| monitor 未被任何线程持有 | 当前线程成为 owner，进入次数为 1 |
| owner 就是当前线程 | 允许重入，进入次数加 1 |
| owner 是其他线程 | 当前线程等待，成功取得前不能执行临界区 |

规范描述的是 monitor 所有权和进入次数，不要求 JVM 一开始就分配重量级对象。HotSpot 可以先在解释器/JIT 快速路径处理无竞争场景，只有遇到竞争、`wait`、哈希等条件时才进入更重的运行时路径。

## monitorexit 必须与进入次数匹配

当前线程执行 `monitorexit` 时：

1. 它必须是该 monitor 的 owner，否则抛 `IllegalMonitorStateException`。
2. 进入次数减 1。
3. 只有次数降到 0，monitor 才真正释放，其他线程才有机会成功进入。

因此“可重入”不是忽略第二次加锁，而是 VM 记录同一 owner 的嵌套深度。下面代码退出内层后，`T1` 仍持有外层：

```java
synchronized (monitor) {       // Java 深度 1
    synchronized (monitor) {   // Java 深度 2
        work();
    }                          // 深度回到 1，尚未释放
    moreWork();
}                              // 深度回到 0，真正释放
```

JDK 8 `ObjectMonitor` 的 `_recursions` 记录“首次持有之外的次数”：Java 深度 1 时 `_recursions=0`，深度 2 时 `_recursions=1`。阅读字段时不要把它误当 Java 视角的总深度。

## synchronized 建立什么内存边

JMM 规定：对一个 monitor 的 unlock happens-before 随后对同一个 monitor 的 lock。配合同一线程的程序顺序和传递性，可以发布临界区中的普通字段写入：

```text
T1: write payload
  → program order
T1: unlock monitor
  → synchronizes-with
T2: lock same monitor
  → program order
T2: read payload
```

这条边要求是“同一个 monitor”。`synchronized (left)` 中的写，不能因为另一个线程随后进入 `synchronized (right)` 就自动可见。

互斥也不等于所有操作都自动原子。例如在锁外先读取共享状态、进入锁后不重新验证，就可能基于过期决策执行。锁保护的是遵循同一协议的临界区，不会修复协议外的数据竞争。

## JDK 8 HotSpot 的快速路径与膨胀边界

JDK 8 HotSpot 会在对象 mark word、栈上锁记录与 `ObjectMonitor` 之间选择路径。源码阅读可按下面顺序：

1. [`markOop.hpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/oops/markOop.hpp)：JDK 8 mark word 的锁标志、hash、偏向字段布局。
2. [`interpreterRuntime.cpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/interpreter/interpreterRuntime.cpp)：解释器 monitorenter/monitorexit 的运行时入口。
3. [`synchronizer.cpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/runtime/synchronizer.cpp)：`ObjectSynchronizer::fast_enter`、`slow_enter`、`inflate`、`enter/exit`。
4. [`objectMonitor.cpp`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/hotspot/src/share/vm/runtime/objectMonitor.cpp)：膨胀后 owner、重入和竞争线程管理。

“偏向锁 → 轻量级锁 → 重量级锁”是学习 JDK 8 实现的方便路线，不是永久不变的 Java 锁等级规范。JDK 15 通过 JEP 374 默认禁用偏向锁；后续 HotSpot 又重构轻量级锁实现。跨 JDK 8/17/21 比较时应重新查看对应 tag，而不是把 JDK 8 对象头图复制到所有版本。

## 异常与编译优化边界

- Java 源码的同步块保证异常退出时释放；手写字节码仍必须满足验证和结构约束。
- JIT 可以消除不可逃逸对象上的锁或进行锁粗化，但必须保持单线程可观察语义和 JMM 保证。
- `Thread.holdsLock(object)` 只能回答当前线程是否持有，不能读取 owner 身份、等待队列或重入层数。
- 生产代码不要通过深反射依赖 mark word 或 `ObjectMonitor` 地址；这些结构既非公开 API，也可能在 safepoint 或版本升级后变化。

下一步进入 [ObjectMonitor 的入口集合与 WaitSet](./objectmonitor-queues.md)，把竞争进入与主动条件等待拆开。
