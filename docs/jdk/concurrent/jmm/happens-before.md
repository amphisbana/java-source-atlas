# happens-before：可见性不是靠“多等一会儿”

## 先把术语翻译成人话

`A happens-before B` 不是说墙上时钟显示 A 一定比 B 早，也不是说两个线程会按一条时间线串行执行。它表达的是一条**可见性与顺序保证**：

> 如果 B 执行，B 必须能够观察到 A 发布的结果；实现也不能采用破坏这项保证的重排。

同一线程的源码顺序只能直接约束这个线程自己。要把结果交给另一个线程，还需要 volatile、锁、线程启动/结束或并发容器契约提供一条跨线程边。日志看起来有先后、`sleep` 等得足够久，都不能代替这条边。

先区分三个常见词：

| 术语 | 可以先怎样理解 |
| --- | --- |
| 程序顺序 | 同一线程内，把前一个动作连接到后一个动作 |
| synchronizes-with | volatile、锁等同步动作直接建立的跨线程边 |
| happens-before | 程序顺序、跨线程边和传递性共同形成的完整保证关系 |

因此分析目标不是判断“A 是否实际先运行”，而是检查“从 A 到 B 能不能沿规则画出一条完整箭头”。

## 用四个动作读一张图

```text
写线程 A                           读线程 B

A1: payload = 42                  B1: read ready == true
    ↓ 程序顺序                        ↓ 程序顺序
A2: volatile ready = true  ─────→ B2: read payload
                         volatile 边
```

- `A1 → A2` 和 `B1 → B2` 来自各自线程内的程序顺序。
- `A2 → B1` 来自对同一个 volatile 字段的配对写读。
- 通过传递性得到 `A1 → B2`，因此 B2 必须看到 `payload=42`。

如果去掉 ready 上的 volatile，`A2 → B1` 就断了。即使某次运行中 B 很快读到 true 和 42，也没有获得 JMM 保证。

## JMM 常用 happens-before 规则

| 规则 | 边 | 常见落点 |
| --- | --- | --- |
| 程序顺序 | 同一线程中前一个动作 → 后一个动作 | 把 payload 写连接到后续 volatile 写 |
| monitor | 一个 monitor 的 unlock → 后续对同一 monitor 的 lock | `synchronized` 临界区交接 |
| volatile | 对字段 v 的写 → 同一同步顺序中后续对 v 的读 | 状态位发布普通数据 |
| start | 调用 `thread.start()` → 新线程中的任何动作 | 发布线程配置与构造完成对象 |
| termination/join | 线程中所有动作 → 其他线程成功检测到其终止 | join 后读取普通结果字段 |
| transitivity | A → B 且 B → C，则 A → C | 把普通写穿过同步动作发布出去 |

还有类初始化、默认初始化、中断检测等规则。阅读常规类库源码时，前五条已经能解释大多数交接。

## volatile 消息传递为什么成立

```text
writer: payload = 42
        ↓ 程序顺序
writer: ready = true       // volatile write
        ↓ synchronizes-with
reader: read ready == true // volatile read
        ↓ 程序顺序
reader: read payload
```

根据传递性，`payload=42` happens-before reader 读取 payload。真正被同步的不只是 ready 的布尔值，而是写线程在 volatile 写之前的动作与读线程在对应 volatile 读之后的动作。

边界条件：

- reader 必须读到 writer 那次写，或在同步顺序中更晚的写；
- writer 在 volatile 写**之后**的普通写不会被这条边倒推发布；
- 对 `readyA` 的写不能自动同步到只读取 `readyB` 的线程；
- volatile 不会让多个写线程互斥。

## start 与 join 是两条方向相反的边

### start：调用方发布给新线程

```java
Config config = new Config(7); // 普通字段写
Thread worker = new Thread(() -> use(config));
worker.start();
```

对 `start()` 的调用 happens-before worker 中的任何动作。只要对象在 start 前构造完成且没有提前逃逸，worker 不需要再通过 volatile 才能读取这份启动配置。

直接调用 `worker.run()` 不建立这条边，因为它只是当前线程中的普通方法调用；它也不会创建新线程。

### join：工作线程发布回等待方

```java
int[] result = {0};
Thread worker = new Thread(() -> result[0] = 42);
worker.start();
worker.join();
System.out.println(result[0]);
```

worker 中所有动作 happens-before 其他线程成功检测到 worker 已终止。`join()` 正常返回是最常见的检测方式，因此读取普通 `result[0]` 有正式保证。

`Thread.sleep(1000)` 没有这条语义：一秒后 worker 很可能结束，但“很可能”既不能保证完成，也不能保证可见性。

## 安全发布的常用方式

| 方式 | 发布边界 | 适用场景 |
| --- | --- | --- |
| 静态初始化 | 类初始化锁与初始化完成规则 | 全局不可变对象、枚举单例 |
| volatile 引用 | 写引用 → 读到该引用 | 配置快照、Copy-on-write 状态 |
| final + 正确构造 | 构造结束的 freeze 语义 | 不可变值对象；仍不能构造中逃逸 |
| monitor/Lock | 解锁 → 后续加锁 | 可变共享状态与复合不变量 |
| 并发容器 | 容器公开契约定义的交接 | 任务、消息、映射发布 |
| start/join | 启动或线程终止 | 一次性线程配置与结果汇合 |

“把引用放进普通静态字段，再希望读线程最终看到”不是发布策略。对象内部字段即使构造完成，也需要分析引用本身怎样到达消费者。

## data race 与 sequential consistency

两个动作访问同一变量，至少一个为写，并且没有 happens-before 顺序，就存在数据竞争。正确同步的数据竞争自由程序具有重要性质：执行结果可以按某种所有线程动作交错的顺序理解，同时保持每个线程的程序顺序。

这不意味着所有并发程序只会逐条串行执行。它意味着在规范层分析 race-free 代码时，不需要为每一种编译器和 CPU 重排建立单独模型。

含数据竞争的代码也不是“JVM 可以做任何事情”。JMM 仍有 causality、类型安全和读值合法性约束，但结果集合明显更宽，通常不值得依赖。

## 为什么数据竞争实验不能断言错误必现

下面的循环是反例演示，不是可靠测试：

```java
while (!ready) {
    // 空循环
}
```

`ready` 为普通字段时，程序缺少终止保证。但某次运行仍可能很快退出；不同 JIT 层级、CPU、调试模式和日志都会改变观察。正确的教学测试应当：

- 对正式保证的结果做断言，例如 volatile 发布后 payload 必须可见；
- 对非原子复合操作使用闸门固定读取/写回窗口，而不是等待偶发冲突；
- 对“可能不终止、可能看到旧值”只做有界观察，不写成必须失败的 CI 条件。

## 源码阅读检查表

看到并发字段时依次问：

1. 哪个线程写，哪个线程读？
2. 字段本身是普通、final、volatile，还是只在锁内访问？
3. 跨线程边来自哪个具体动作？
4. 普通数据位于发布动作之前还是之后？
5. 读取方真的执行了配对同步动作吗？
6. 代码需要的是单字段可见，还是跨多个字段的不变量？

下一页把这些规则落到 `volatile`、final 和双重检查锁定。
