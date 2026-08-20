# Selector 事件循环：注册、选择、唤醒与就绪消费

Selector 的价值不是让 I/O 本身变快，而是让一个线程等待多组 Channel 的就绪条件，并把每个连接的实际进度保存在附件和 Buffer 中。理解它要同时跟踪 provider、Channel 注册、SelectionKey 位集合、三个 key 集合和跨线程唤醒。

版本入口：[JDK 8 / 17 / 21 ByteBuffer / Selector 对比](/jdk/version-comparison/?topic=bytebuffer-selector)。传统 selected-key set 是三版共同基线，Consumer 选择和原子兴趣位是建立在它之上的新入口。

## `Selector.open()` 如何落到平台实现

OpenJDK 8u 的公开入口很短：

```text
Selector.open()
  → SelectorProvider.provider()
  → provider.openSelector()
  → 平台 Selector 实现
```

8u `SelectorProvider.provider()` 首次调用时按大致顺序寻找系统级 provider：

1. 读取 `java.nio.channels.spi.SelectorProvider` 系统属性并反射创建；
2. 通过已安装 provider 的服务配置寻找实现；
3. 回退到 `sun.nio.ch.DefaultSelectorProvider` 选择当前平台默认实现；
4. 缓存结果，后续返回同一 provider。

这段逻辑解释了 provider 是 JVM/平台级扩展点，但业务代码通常只需调用 `Selector.open()`。类名会因操作系统和 JDK 改变；Linux 8u 常见 epoll，macOS 与较新 JDK 常见 kqueue，不能用 `selector.getClass().getName()` 做业务分支。

## 注册调用链

以 ServerSocketChannel 为例：

```java
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
server.configureBlocking(false);
SelectionKey key = server.register(selector, SelectionKey.OP_ACCEPT, attachment);
```

OpenJDK 8u `AbstractSelectableChannel.register` 的关键检查和分支是：

```text
持有 regLock
  → channel 必须 open
  → (ops & ~validOps()) 必须为 0
  → channel 必须处于 non-blocking
  → 查找该 channel 在此 selector 上的已有 key
     → 已有：更新 interestOps 和 attachment，返回同一 key
     → 没有：AbstractSelector.register 创建 key，再加入 channel 的 key 集合
```

同一 Channel 可以注册到多个 Selector，每组注册各有一个 key；同一 Channel 对同一 Selector 重复 register 通常更新并返回已有 key，不是再造一份注册。

已经有有效注册的 Channel 不能切回阻塞模式。`configureBlocking(true)` 会检查有效 key 并抛 `IllegalBlockingModeException`，因此生命周期通常是“先配置非阻塞，再注册，注销/关闭后才可能切换”。

## SelectionKey 的四个位

```text
OP_READ    = 1
OP_WRITE   = 4
OP_CONNECT = 8
OP_ACCEPT  = 16
```

它们是位掩码，可以在 Channel 支持的范围内组合：

```java
key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
boolean readable = (key.readyOps() & SelectionKey.OP_READ) != 0;
```

但组合不是越多越好。典型状态迁移是：

```text
客户端 connect 尚未完成：OP_CONNECT
finishConnect 成功且有首包：OP_WRITE
首包写完：OP_READ
收到响应后又产生待发送数据：OP_READ | OP_WRITE
待发送队列清空：移除 OP_WRITE，只留 OP_READ
```

### interestOps 与 readyOps 的时间边界

`interestOps` 是应用声明，`readyOps` 是某次选择操作写入 key 的结果。JDK 8 Selector 规范明确：选择进行中修改 interest set，不影响正在执行的这一次选择，只会被后续选择观察。因此其他线程改变兴趣位或注册新 Channel 时，通常要同时调用 `selector.wakeup()`，让阻塞线程返回并进入下一轮。

`readyOps` 也不是“操作一定成功”的保证：

- readable 可能是有数据、EOF、远端关闭读方向或待处理错误；仍需检查 `read` 返回值/异常；
- writable 只表示内核当前可能接受一些数据，不保证把整个 Buffer 写完；
- connectable 要调用 `finishConnect()` 才完成或暴露连接错误；
- acceptable 后 `accept()` 在非阻塞模式仍应判空，状态可能在通知与调用间变化。

### JDK 11 原子兴趣位不是自动 wakeup

JDK 17/21 快照已经包含 JDK 11 的两个组合入口：

```java
int old = key.interestOpsOr(SelectionKey.OP_WRITE);
int previous = key.interestOpsAnd(~SelectionKey.OP_READ);
```

它们都返回更新前的 interest set。默认实现同步执行“读取 → OR/AND → 写回”，内建 SelectionKey 则可以使用 VarHandle 位操作；公开原子性只保证相对于其他并发 `interestOpsOr/And`。`Or` 会像整体 setter 一样拒绝 Channel 不支持的位，`And` 故意允许补码，以便不枚举其他位就清除某个标志。

这不改变选择时间边界：修改仍只保证被后续 selection operation 观察。如果 Selector 线程正阻塞，而另一个线程希望立刻应用新兴趣位，仍要先把控制命令放入线程安全队列，再调用 `wakeup()`。

## 三个 key 集合怎样变化

### 注册集合 `keys`

成功 register 后进入 `keys()`。这个集合是只读视图，应用不能直接 add/remove。关闭 Channel 或 `key.cancel()` 不会立刻从 keys 消失；Selector 在下一次选择的注销阶段处理。

### 已选集合 `selectedKeys`

选择操作发现就绪时：

- key 不在 selected 集合：加入集合，readyOps 替换为本次就绪位；
- key 已在 selected 集合：保留旧 readyOps，并把本次新就绪位按位 OR 进去。

最重要的规则是：**下一次 select 不会自动移除旧 selected key。** 应用处理后必须显式移除：

```java
Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
while (iterator.hasNext()) {
    SelectionKey key = iterator.next();
    iterator.remove(); // 先取得所有权，再进入可能抛异常的业务处理

    if (!key.isValid()) {
        continue;
    }
    // 根据 readyOps 推进连接状态
}
```

把 `iterator.remove()` 放在业务处理后面，会让异常路径遗留旧 key；下一轮可能反复处理陈旧 readyOps。直接遍历后忘记 clear，是典型“Selector 一直返回但没有新事件”的原因。

### 取消集合 `cancelledKeys`

`key.cancel()` 把 key 放入 AbstractSelector 管理的取消集合。选择操作在查询操作系统前处理一次，查询后再处理一次，以覆盖等待期间发生的取消。实现最终会从 selector 的 keys/selectedKeys、底层轮询器注册以及 channel 自己的 key 集合中注销。

取消是延迟生效；要让另一个线程正在阻塞的 Selector 尽快处理取消，调用 `wakeup()`。

## `select`、超时与空返回

| 方法 | 阻塞语义 |
| --- | --- |
| `selectNow()` | 不等待，立即执行一次选择 |
| `select(timeoutMillis)` | 最多等待正数毫秒；**参数 0 表示无限等待，不是立即返回** |
| `select()` | 无限等待，直到就绪、wakeup、interrupt 或 close 等条件 |

返回值是本次 ready-operation set 被更新的 key 数，不等同于 `selectedKeys().size()`。selected 集合可能保留上一轮未移除的 key，也可能因同一 key 已在集合而出现不同关系。

即使有超时，也应把 `0` 当作允许出现的状态：超时、wakeup、线程中断、实现层事件或竞争都可能让 select 没有业务 key。正确循环基于截止时间和关闭标志，不应把单次 0 返回视为永久故障，也不应无条件高速重试。

```java
while (running) {
    int updated = selector.select(remainingTimeoutMillis());
    drainControlTasks();
    if (updated == 0) {
        continue;
    }
    processSelectedKeys();
}
```

若 `selectNow()` 放在无节流的 while 中，本来就是主动轮询，会占满 CPU。若阻塞 `select()` 持续异常快速返回 0，应记录 provider、JDK/OS、interestOps、注册数、wakeup/interrupt 调用来源，再采用有界退避或重建 Selector 等经过证据支持的策略，不能把“重建”当成无条件模板。

### JDK 11 Consumer 选择的所有权

JDK 17/21 还提供 `select(Consumer)`、`select(Consumer,long)` 和 `selectNow(Consumer)`。对于本轮新发现的 ready key，Selector 直接调用 action，不把它新增到 selected-key set：

```java
selector.selectNow(key -> {
    if (key.isReadable()) {
        handleRead(key);
    }
});
```

这减少了忘记 `iterator.remove()` 的机会，但有三个边界不能省略：

1. action 在 Selector 与 selected-key set 的同步边界内执行，应短小、非阻塞，并避免重入同一个 Selector；内建实现会拒绝 select 重入，规范也不承诺其他 provider 的重入行为。
2. “本轮 key 不加入集合”不等于该方法替应用清空历史债务。调用前已经留在 `selectedKeys()` 中的 key 仍由应用负责。
3. action 仍只得到就绪通知，Channel read/write 的 `0`、部分进度、EOF 和异常处理完全不变。

需要兼容 JDK 8 时继续使用迭代器模板更清晰；只有最低版本允许时，再选择 action 形式统一事件所有权。

## `wakeup()` 是一次许可，不是事件队列

`wakeup()` 使第一个尚未返回的选择操作立即返回；如果当前没有线程在选择，则下一次选择立即返回。多次 wakeup 可能合并为一次效果，它不会累计成业务消息数量。

跨线程控制的常见模板是：

```text
业务线程：把 register/change-interest/close 命令放进线程安全队列
        → selector.wakeup()

Selector 线程：select 返回
             → 先清空控制命令队列
             → 再处理 selected keys
             → 进入下一轮 select
```

“先入队，再 wakeup”避免 Selector 被唤醒后看不到命令。实际项目还要处理 wakeup 发生在入队与 select 之间的竞态；公开契约保证下一次选择会消费这次唤醒效果。

在 OpenJDK 8u Linux EPollSelectorImpl 中，典型实现用一对 pipe fd 触发和排空中断，并用标志合并重复触发。JDK 17/21 的平台实现可能采用不同机制，这只用于理解“为什么能打断底层 poll”，不应被应用反射访问。

## 四类事件的稳健处理

### OP_ACCEPT

```text
server.accept()
  → null：本轮无可接收连接，直接返回循环
  → SocketChannel：configureBlocking(false)
                    → 创建 ConnectionState(Buffer/队列/协议阶段)
                    → register(selector, OP_READ, state)
```

高连接速率下可在一次 accept 事件内循环到 null，但要有公平性预算，避免一个 ServerSocketChannel 饿死其他连接事件。

### OP_CONNECT

```text
socket.finishConnect()
  → true：interestOps 移除 OP_CONNECT
          → 有待发送数据则加 OP_WRITE，否则加 OP_READ
  → false：保持 OP_CONNECT，等待后续就绪
  → exception：取消 key 并关闭 channel
```

非阻塞 `connect(remote)` 若立即返回 true，连接已经完成，不应再等待 OP_CONNECT；可以直接注册 READ，或有数据时注册 WRITE。

### OP_READ

```text
int n = socket.read(input)
n > 0：position 前进；flip 后尽可能解析完整帧；compact 保留半包
n = 0：没有进度，保留状态返回事件循环
n = -1：对端输入结束；按协议决定半关闭、冲刷输出或关闭
```

读取循环可以继续直到返回 0，但必须设置每 key 的字节/次数预算，以免一个热点连接占据整个事件线程。

### OP_WRITE

```text
socket.write(currentOutput)
  → hasRemaining：保留 OP_WRITE，下一次继续
  → 当前 Buffer 完成：取队列下一项
  → 队列为空：interestOps &= ~OP_WRITE
```

大多数 TCP socket 在发送缓冲区有空间时几乎总是 writable。没有数据仍关注 OP_WRITE，会让 select 立刻返回同一个 key，表现为高 CPU 和空业务循环。

## 单线程事件循环仍需要附件状态

Selector 线程串行处理 key 不代表一条消息一次完成。每个连接至少需要：

- 输入 ByteBuffer 与解析阶段；
- 当前待发送 ByteBuffer 或有界输出队列；
- 连接角色、协议状态、超时信息；
- 是否允许继续读、是否正在关闭等背压状态。

`SelectionKey.attach(state)` 让状态跟随注册，但附件替换不会自动释放旧资源。关闭路径应取消 key、关闭 Channel，并清理附件持有的大对象。

## 本专题回环时间线

调试 Lab 在同一个 Selector 线程内完成：

```text
ServerSocketChannel: OP_ACCEPT
Client SocketChannel: OP_CONNECT
  → accept peer + finishConnect
Client: OP_WRITE(PING) → OP_READ
Peer:   OP_READ(PING)  → OP_WRITE(PING)
Peer:   OP_WRITE(PING) → OP_READ
Client: OP_READ(PING)  → 完成
```

它刻意在每次处理前 `iterator.remove()`，并只在附件确实有 pending output 时启用 OP_WRITE。完整运行方式和断点位置见 [断点与回环实验](./debug-lab.md)。

## JDK 17/21 的边界

- 公开的 register、interestOps/readyOps、selectedKeys、wakeup 和非阻塞 Channel 语义保持稳定；
- JDK 17/21 已包含 JDK 11 的 `Consumer<SelectionKey>` 选择重载和 `interestOpsOr/And`；前者不把本轮新 key 加入 selected set，后者也不会自动 wakeup；
- `sun.nio.ch.SelectorImpl` 在较新版本中可见注册更新队列、不同的锁组织和平台 poller，类与字段都不是兼容接口；
- JDK 21 虚拟线程让“一连接一阻塞任务”在部分场景重新具有吸引力，但 Selector 仍适合需要集中背压、协议状态机和明确事件线程所有权的系统；选择应基于负载、延迟、调试复杂度和依赖库，而不是版本口号。

自动化测试只断言本机回环数据完整、四类操作实际被处理、selected key 被移除、wakeup 有界返回和资源关闭；不固定事件到达顺序、单次 select 数量或 provider 私有类名。
