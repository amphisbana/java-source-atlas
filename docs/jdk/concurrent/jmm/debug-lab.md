# JMM / volatile / final 调试实验

## 运行命令

从仓库根目录执行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.JmmVolatileDebugLab
```

聚焦运行行为测试：

```bash
mvn -pl labs/jdk-labs \
  -Dtest=JmmVolatileBehaviorTest test
```

Lab 以 Java 8 release 目标编译。切换 Java 8 与 Java 17 运行时后重复命令，可以观察 VarHandle 类是否存在；其余 happens-before 结论不应随运行时版本改变。

## 实验一：volatile 发布普通 payload

核心结构：

```java
state.payload = 42;
state.ready = true; // volatile write

while (!state.ready) {
    Thread.yield();
}
observed = state.payload;
```

推荐断点：

1. 主线程写 `payload`。
2. 主线程写 volatile `ready`。
3. reader 第一次读到 `ready=true`。
4. reader 读取 payload。

应记录线程名和字段值，但不要把调试器“暂停全部线程”打开在自旋位置，否则主线程无法完成发布。

## 实验二：稳定证明 volatile++ 不原子

实验不依赖调度器碰巧冲突，而是强制：

```text
T1 read counter=0 ─┐
                   ├─ snapshotsRead 归零
T2 read counter=0 ─┘
                   ↓ 主线程开放 writeBack
T1 write 1
T2 write 1
```

两次 volatile 读和两次 volatile 写都合法，最终值稳定为 1。断点观察 `snapshot` 与 `counter.value`，可以直接区分“访问可见”和“复合操作原子”。

## 实验三：start 与 join

`configuration[0]=7` 在 worker 启动前已经完成；worker 把普通 `result[0]` 写为 42；主线程 join 后读取。

```text
main write configuration
  -> Thread.start
  -> worker read configuration
  -> worker write result
  -> worker termination
  -> main join returns
  -> main read result
```

将 `join()` 替换为 `sleep()` 不是等价实验：sleep 既不确认线程终止，也不是正式可见性边。

## 实验四：final 快照

在主线程完成 `ImmutableSnapshot` 构造，再通过 start 边交给 reader。观察：

- 构造器写入 final `name/value`；
- reader 启动时对象已经构造完成；
- reader 不修改对象，只读取稳定状态。

不要为了“演示错误”在构造器中注册 this。构造逃逸属于缺陷模式，结果不稳定，不应成为自动化测试的必现断言。

## 实验五：VarHandle 版本边界

Lab 使用 `Class.forName("java.lang.invoke.VarHandle")`，因此源码可在 Java 8 编译：

| 运行时 | 预期 |
| --- | --- |
| Java 8 | 类不存在，输出 `false` |
| Java 17/21 | 类存在，输出 `true` |

这只验证 API 可用性，不验证具体类库已经全部改用 VarHandle。私有实现需要按目标 tag 查看源码。

## 测试为什么这样写

`JmmVolatileBehaviorTest` 只断言公开契约能保证的结果：

- volatile 发布后 payload 为 42；
- start/join 前后的普通字段可见；
- 闸门固定的两个 read-modify-write 最终覆盖为 1；
- 安全发布的 final 快照一致；
- VarHandle 是否存在与 Java 主版本一致。

它不断言普通标志必然看不到、不正确 DCL 必然返回半初始化对象，也不依赖某种 CPU 屏障序列。这样的测试才能在 Java 8、17、21 和不同硬件上稳定解释同一份规范。

## 过关记录

完成实验后应能写出：

1. volatile 发布链中的四个动作与三条边。
2. 受控丢失更新的两个读取值、两个写入值和最终值。
3. start 与 join 分别把数据向哪个方向发布。
4. final 保证成立所需的构造条件。
5. Java 8 Lab 为什么不能直接 import VarHandle。
