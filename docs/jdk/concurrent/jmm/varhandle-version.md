# 从 Unsafe 到 VarHandle：把内存顺序写进 API

::: tip 这是进阶页
VarHandle 解决的是“用哪一种 API 表达所需的内存顺序”，不是 JMM 入门的前置知识。如果还不能独立画出 `payload → volatile ready → payload read`，先回到[专题首页](./index.md)和 [happens-before](./happens-before.md)。
:::

## JDK 8 为什么大量出现 Unsafe

OpenJDK 8 并发类需要完成：

- 按字段偏移做 CAS；
- volatile 读写；
- 有序但弱于 volatile 的写入；
- park/unpark；
- 直接内存访问。

这些能力集中在内部类 `sun.misc.Unsafe`。它依赖字段偏移和调用方自行匹配类型，普通业务代码没有稳定、受支持的契约。

以 `AtomicInteger` 为例：

```text
valueOffset = unsafe.objectFieldOffset(AtomicInteger.value)
compareAndSet(expect, update)
  -> unsafe.compareAndSwapInt(this, valueOffset, expect, update)
```

`value` 仍声明为 volatile；CAS 不只是替换值，还具有该原子操作规定的内存效果。

## VarHandle 提供什么

JDK 9 的 `java.lang.invoke.VarHandle` 把“变量位置”和“访问模式”建模为受支持 API。它可以指向字段、静态字段或数组元素，并在调用时检查坐标类型和值类型。

```java
private static final VarHandle STATE;

static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    STATE = lookup.findVarHandle(Task.class, "state", int.class);
}
```

专题 Lab 仍以 Java 8 编译，因此不直接引用 VarHandle；只在 JDK 9+ 文档和版本实验中使用。若把 VarHandle 源码直接写进 Java 8 模块，整个兼容矩阵会在编译阶段失败。

## 五组常用访问强度

| 模式 | 读 | 写 | 核心边界 |
| --- | --- | --- | --- |
| plain | `get` | `set` | 与普通字段访问相近，不建立线程间同步边 |
| opaque | `getOpaque` | `setOpaque` | 保证该变量的相干观察与有限顺序，弱于 acquire/release |
| acquire/release | `getAcquire` | `setRelease` | release 前动作可发布给读到它的 acquire 后动作 |
| volatile | `getVolatile` | `setVolatile` | 具有 volatile 读写语义并进入全局同步顺序 |
| atomic | `compareAndSet`、`getAndAdd` 等 | 原子读改写 | 在访问模式语义上再提供单一线性化动作 |

`setRelease` 常对应 JDK 8 Unsafe 的 ordered/lazySet 思路，但不能只按方法名字机械等同；应以目标 JDK 的 VarHandle 规范和具体调用点为准。

## acquire/release 怎样形成消息传递

```text
writer: payload = 42       // plain write
writer: setRelease(ready, true)
                    ↓
reader: getAcquire(ready) == true
reader: read payload       // 必须看到 42
```

release 只约束它之前的动作，acquire 只约束它之后的动作。它们适合单向发布协议。volatile 模式约束更强，因为所有 volatile 访问还参与同步顺序；不要在没有基准和证明的情况下为了“性能”随意降低访问强度。

## compareAndSet 的成功与失败语义

CAS 至少涉及：

1. 原子比较当前位置与 expected。
2. 相等时原子写入 update，并返回成功。
3. 不等时不写入，并返回失败。

VarHandle 还提供 weak CAS 及带 acquire/release 后缀的变体。weak CAS 可以伪失败，调用方必须在循环中重试。失败路径的内存效果可能弱于成功路径，阅读无锁算法时要看调用的确切方法，而不是只看“这里有 CAS”。

## JDK 8、17、21 阅读坐标

| 版本 | 主要观察点 | 迁移注意 |
| --- | --- | --- |
| JDK 8 | volatile 字段、Unsafe offset、CAS、putOrdered | 内部 API，不应被业务代码复制 |
| JDK 17 | VarHandle 已广泛使用，模块边界更严格 | 反射打开内部包不是长期方案 |
| JDK 21 | VarHandle 语义稳定，并与现代并发实现结合 | 仍要区分平台线程、虚拟线程和内存访问协议 |

固定源码：

- [JDK 8 Unsafe](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/misc/Unsafe.java)
- [JDK 17 VarHandle](https://github.com/openjdk/jdk/blob/jdk-17%2B35/src/java.base/share/classes/java/lang/invoke/VarHandle.java)
- [JDK 21 VarHandle](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/invoke/VarHandle.java)

## 选择顺序

业务代码通常按以下优先级选择：

1. 不可变对象和线程封闭，避免共享可变状态。
2. `java.util.concurrent` 的现成容器、锁、原子类和执行器。
3. 确有自定义底层结构时使用 VarHandle，并写清不变量与线性化点。
4. 不直接依赖 Unsafe；JVM 启动参数打开模块也不等于获得兼容承诺。

VarHandle 是工具，不是算法。错误的节点生命周期、ABA 处理或内存回收策略不会因为换成 VarHandle 自动正确。

## 阅读一个 VarHandle 调用的检查表

1. handle 指向哪个字段或数组元素，坐标类型是什么？
2. 使用 plain、opaque、acquire/release 还是 volatile？
3. 哪些普通动作位于 release 前和 acquire 后？
4. 原子操作的线性化点在哪里？
5. CAS 失败后是否重新读取全部依赖状态？
6. 目标代码是否仍要在 Java 8 运行？

完成本页后进入 [断点实验](./debug-lab.md)，用同一份 Lab 在 Java 8 与 17 运行，观察 API 可用性变化，而不把内部实现当公开契约。
