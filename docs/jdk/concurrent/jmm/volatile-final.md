# volatile、final 与 DCL：发布不等于互斥

## volatile 的精确能力

对一个 volatile 字段：

- 读取总会按 volatile 语义取得一个合法写入值；
- 写入与后续读到该写入之间建立 synchronizes-with；
- 64 位 `long/double` volatile 访问必须原子；
- 编译器和处理器必须遵守该同步边需要的顺序约束；
- 不提供排他执行，也不自动合并多次访问。

### `count++` 为什么仍然错误

```text
T1: read count -> 0
T2: read count -> 0
T1: write count = 1
T2: write count = 1
```

四次 volatile 访问各自合法且可见，但整个 read-modify-write 没有一个共同线性化点。需要精确累加时应使用 `AtomicInteger.incrementAndGet()`、锁或分段计数器，而不是给字段加 volatile 后继续 `++`。

同理，下面检查后执行也不是原子操作：

```java
if (!started) {
    started = true;
    startService();
}
```

多个线程可以同时读到 false。volatile 只发布状态，不能保证 `startService()` 恰好一次。

## volatile 引用发布的是之前的状态

`CopyOnWriteArrayList` 的核心不是“数组元素是 volatile”，而是写线程在锁内创建新数组，填充完毕后通过 volatile 数组引用发布：

```text
复制旧数组 -> 修改新数组 -> setArray(newArray)
                              volatile write
reader -> getArray() -> 在不可变快照上读取
          volatile read
```

一旦引用发布，读线程能看到 volatile 写之前完成的新数组内容。之后如果有人绕过容器继续修改数组元素，volatile 引用不会自动为这些后续修改建立新边。

## final 字段的 freeze 语义

final 不只是“Java 语法不允许再次赋值”。正确构造时，构造器结束会形成针对 final 字段的特殊冻结语义：即使对象引用通过数据竞争到达另一个线程，读线程仍能得到正确初始化的 final 字段，并对 final 引用可达对象的构造期状态获得额外保证。

这个保证有严格前提：

- final 字段必须在构造期间写入；
- 构造器返回前 `this` 不能发布到其他线程；
- final 引用指向的对象如果随后继续变化，那些后续变化仍需同步；
- final 不让整个对象自动不可变。

### 构造期间 this 逃逸

```java
class Listener {
    final int threshold;

    Listener(EventBus bus) {
        bus.register(this); // this 提前逃逸
        threshold = 42;
    }
}
```

其他线程可能在构造器尚未完成时回调该对象。此时不能再用“字段是 final”弥补错误发布。常见修复是先完成构造，再由工厂或外层组装代码注册。

## 不可变对象仍要管理引用所有权

```java
final class Snapshot {
    final String name;
    final List<String> values;
}
```

`values` 引用不能重新赋值，不代表 List 内容不可变。真正不可变对象还要：

- 不暴露可变内部集合；
- 构造时做防御性复制；
- 返回不可变视图或副本；
- 禁止子类破坏约束，常用 final class。

final 适合表达“构造后稳定的不变量”，volatile 适合表达“会变化但每次需要发布的当前快照”。两者可以组合：`volatile Snapshot current`。

## 双重检查锁定为什么必须 volatile

```java
private static volatile Service instance;

static Service getInstance() {
    Service result = instance;
    if (result == null) {
        synchronized (Service.class) {
            result = instance;
            if (result == null) {
                result = new Service();
                instance = result;
            }
        }
    }
    return result;
}
```

需要 volatile 的两个原因：

1. 发布引用的写与外层快速路径读取之间建立 happens-before，使构造期写入可见。
2. 禁止把“发布 instance”重排到构造初始化动作之前。

局部变量 `result` 减少同一次调用中的 volatile 读取次数。内层第二次检查不可删除，因为多个线程可能一起通过外层 null 检查，随后依次取得 monitor。

更简单的单例通常应使用静态初始化、枚举或依赖注入容器，不需要自己维护 DCL。

## volatile 适用与不适用

适用：

- 独立状态标志，写后发布此前数据；
- 不可变配置快照引用；
- 一个写者发布、多个读者观察的版本号或状态；
- 算法已经通过 CAS/锁保证复合原子性，volatile 负责读取和发布。

不适用：

- `balance = balance - amount` 这类复合不变量；
- 多字段必须同时变化；
- check-then-act 必须只执行一次；
- 需要公平、等待队列或条件通知；
- 需要把失败回滚到旧状态。

## 源码中的典型组合

| 类型 | 字段/动作 | 组合逻辑 |
| --- | --- | --- |
| `FutureTask` | volatile `state` + Unsafe CAS | CAS 竞争执行权，终态发布 outcome |
| `AtomicInteger` | volatile `value` + CAS | 读取可见，复合更新由 CAS 循环线性化 |
| `CopyOnWriteArrayList` | volatile `array` + 写锁 | 锁保护复制修改，volatile 发布新快照 |
| `ConcurrentHashMap` | volatile 表/节点字段 + CAS + 桶锁 | 不同结构变化使用不同同步方式 |
| `ThreadPoolExecutor` | AtomicInteger ctl + Worker 锁 + 队列 | 单字段状态、任务互斥与等待分层处理 |

看到 volatile 时不要立刻结束分析。继续找：谁写、谁读、哪一段普通数据在它之前、是否还有 CAS 或锁共同维护不变量。

## 版本边界

JDK 8 类库大量直接使用 `sun.misc.Unsafe` 的 `compareAndSwap*`、`putOrdered*` 与 volatile 访问。JDK 9 引入 VarHandle 后，JDK 自身逐步把访问模式显式化；这不是 JMM 规则被替换，而是 Java API 更精确地表达不同顺序强度。

下一页对照这些访问模式，避免把 acquire/release、opaque 与 volatile 当成同义词。
