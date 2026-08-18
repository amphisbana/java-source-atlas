# ByteBuffer 与 Selector：从状态边界到就绪事件循环

Java NIO 最容易被两个表象误导：`ByteBuffer` 看起来只是带游标的字节数组，`Selector` 看起来只是把多个 Channel 放进一个循环。真正决定程序是否正确的，是两套彼此衔接的状态协议：

- Buffer 用 `mark <= position <= limit <= capacity` 约束“已经处理到哪里、当前允许处理到哪里”；
- Selector 用 `interestOps` 表示应用想观察什么，用 `readyOps` 表示本轮操作系统报告了什么，再由应用显式消费 `selectedKeys`；
- Channel 的 `read/write` 都允许只推进一部分，Buffer 的状态正是跨多轮就绪事件保存进度的载体；
- 非阻塞不是“一次调用立即完成”，而是每次调用都允许返回 `0`、部分进度或尚未完成，事件循环必须保留上下文继续推进。

本专题以 **OpenJDK 8u** 为源码基线，讲清公开契约和 8u 的典型实现。JDK 17/21 仍保持 Buffer 四指标、SelectionKey 三个集合和非阻塞 Channel 的核心语义，但 `sun.nio.ch` 中的 provider、轮询器、唤醒管道、更新队列与内部字段都可能随平台和版本变化，不能把它们当成业务代码可依赖的 API。

## 源码入口

| 层次 | OpenJDK 8u 源文件 | 本专题关注入口 |
| --- | --- | --- |
| Buffer 状态 | [`java/nio/Buffer.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/nio/Buffer.java) | `mark/position/limit/capacity`、`clear/flip/rewind`、相对索引推进 |
| 字节缓冲区 | [`java/nio/ByteBuffer.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/nio/ByteBuffer.java) | heap/direct 分配、字节序、类型视图、`compact` 抽象契约 |
| 堆实现 | [`java/nio/HeapByteBuffer.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/nio/HeapByteBuffer.java) | `hb + offset`、`System.arraycopy`、切片与压缩 |
| 直接内存实现 | [`java/nio/DirectByteBuffer.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/nio/DirectByteBuffer.java) | native address、Cleaner、slice/duplicate 附着关系 |
| Selector 门面 | [`java/nio/channels/Selector.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/nio/channels/Selector.java) | 三个 key 集合、三阶段选择、`select/selectNow/wakeup` |
| 注册协议 | [`java/nio/channels/spi/AbstractSelectableChannel.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/nio/channels/spi/AbstractSelectableChannel.java) | 非阻塞检查、`validOps`、复用或创建 SelectionKey |
| provider | [`java/nio/channels/spi/SelectorProvider.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/nio/channels/spi/SelectorProvider.java) | provider 定位、`openSelector/openSocketChannel` |
| 通用实现 | [`sun/nio/ch/SelectorImpl.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/sun/nio/ch/SelectorImpl.java) | `keys/selectedKeys`、注册、锁顺序、取消队列 |
| Linux 8u 示例 | [`sun/nio/ch/EPollSelectorImpl.java`](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/solaris/classes/sun/nio/ch/EPollSelectorImpl.java) | `epoll` 等待、fd 到 key 的映射、pipe 唤醒 |

OpenJDK 源码采用 GPLv2 with Classpath Exception。本专题只整理字段关系、公开语义、调用链和等价伪代码；完整许可边界以项目源码许可说明为准。

## 先把两套状态机接起来

一次非阻塞 echo 往返可以压缩成下面的主线：

```text
SocketChannel 收到 OP_READ
  → channel.read(inputBuffer)
  → inputBuffer.position 随实际读取字节数前进
  → 一条完整消息到齐后 flip()
  → channel.write(outputBuffer)
  → 若 outputBuffer.hasRemaining()，保留 OP_WRITE 等下一轮
  → 全部写完后移除 OP_WRITE，clear() 或 compact() 准备接收后续数据
```

这里每个动作都有不能省略的理由：

1. `read` 返回的是本次实际进度，不承诺填满 Buffer；返回 `0` 不是 EOF，返回 `-1` 才表示输入结束。
2. `flip` 不复制数据，只把“刚才写入的范围 `[0, position)`”变成新的可读范围 `[0, limit)`。
3. `write` 也可能只消费一部分；只要 `hasRemaining()` 为 true，就不能清空或覆盖待发送字节。
4. `OP_WRITE` 往往长期就绪，只应在确实有剩余输出时注册，否则 Selector 会连续返回，形成 CPU 空转。
5. 每次处理 `selectedKeys` 都要通过迭代器移除。选择操作会添加或更新 key，不会替应用清空已选集合。

## Buffer 四指标是一组边界，不是四个计数器

任意时刻都满足：

```text
-1 <= mark <= position <= limit <= capacity
```

`mark=-1` 表示未定义。`capacity` 创建后固定；`limit` 是第一个不可访问位置；`position` 是下一次相对读写的位置；`mark` 只是允许 `reset()` 回跳的已保存 position。降低 position 或 limit 越过 mark 时，mark 会被丢弃。

最常用的读写切换不是“读模式/写模式”字段，而是调用者自己调整边界：

```java
ByteBuffer buffer = ByteBuffer.allocate(8); // p=0, l=8, c=8
channel.read(buffer);                       // 相对 put，p 向右移动
buffer.flip();                              // l=旧 p, p=0
channel.write(buffer);                      // 相对 get，p 向右移动
buffer.compact();                           // 未写完内容移到头部，p=remaining, l=c
```

更完整的状态变化、`mark/reset`、`clear/rewind`、堆与直接内存、字节序和视图共享关系见 [Buffer 状态机](./buffer-state.md)。

## Selector 管的是注册和就绪，不负责业务调度

一个 Selector 内部从公开契约看有三组 key：

| 集合 | 谁加入 | 谁移除 | 用途 |
| --- | --- | --- | --- |
| `keys()` | Channel 注册产生 | key 取消后在选择阶段延迟注销 | 当前注册全集，只读视图 |
| `selectedKeys()` | 选择操作发现就绪后加入/更新 | **应用显式 `iterator.remove()` 或 `remove/clear`** | 等待本轮业务处理的 key |
| cancelled-key set | `key.cancel()` 或关闭 Channel | 下一次选择前后由 Selector 处理 | 延迟注销队列，公开 API 不直接暴露 |

`interestOps` 与 `readyOps` 也不能混用：

- `interestOps` 是下一次选择开始时应用希望监控的位集合；
- `readyOps` 是选择操作报告的就绪位集合，只能在有效 key 上读取；
- `isReadable()` 等只是对 `readyOps & OP_READ` 的便捷判断；
- 就绪只表示“一次非阻塞调用现在不会因为等待对应条件而阻塞”，不表示能读到完整消息或一次写完所有数据。

完整的 provider、注册、选择、唤醒、取消和四类操作路径见 [Selector 事件循环](./selector-loop.md)。

## 动画：一条时间线观察 Buffer 与 Selector

动画的前半段追踪八字节 Buffer 的四指标，后半段用本机 `PING` 回环展示 `ACCEPT → CONNECT → WRITE → READ → WRITE → READ`。可直接切换两个阶段，再逐帧核对 `interestOps`、`readyOps` 和三个 key 集合。

<NioBufferSelectorAnimation />

## 四种就绪操作的职责

| 操作 | 常见 Channel | 就绪后必须做什么 | 常见错误 |
| --- | --- | --- | --- |
| `OP_ACCEPT` | `ServerSocketChannel` | 循环或单次 `accept()`，判空，新 Channel 配置非阻塞后注册 | 把非阻塞 `accept()` 的 `null` 当异常 |
| `OP_CONNECT` | `SocketChannel` | 调用 `finishConnect()`；成功后移除 CONNECT，切到 READ 或有数据时 WRITE | 只看 `isConnectable`，从不完成连接 |
| `OP_READ` | `SocketChannel` | `read(buffer)`，区分 `>0 / 0 / -1`，按协议拆包 | 假定一次 read 就是一条消息 |
| `OP_WRITE` | `SocketChannel` | 只发送附件中的待发送 Buffer，写完立即移除 WRITE | 永久在线关注，导致空转 |

一个 Channel 的 `validOps()` 决定允许注册哪些位。`ServerSocketChannel` 只支持 `OP_ACCEPT`；`SocketChannel` 支持 CONNECT/READ/WRITE。`register` 会检查 `(ops & ~validOps()) == 0`，无效组合会抛 `IllegalArgumentException`。

## 学习顺序

1. [Buffer 状态机](./buffer-state.md)：先能手算每次调用后的四指标和剩余区间。
2. [Selector 事件循环](./selector-loop.md)：再把每个 key 的附件理解为跨事件保存的连接状态。
3. [断点与回环实验](./debug-lab.md)：运行公开行为测试，再进入 OpenJDK 8u 的私有实现观察。

读完后应当能回答：为什么半包必须保留、`clear` 为什么不清零、`compact` 为什么改变 position、`selectedKeys` 为什么必须移除、`OP_WRITE` 为什么不能常驻，以及修改 `interestOps` 后为什么跨线程通常还要 `wakeup()`。

## JDK 8、17、21 边界

| 观察点 | OpenJDK 8u | JDK 17/21 |
| --- | --- | --- |
| Buffer 核心状态 | 四指标与相对/绝对访问协议 | 语义保持 |
| Fluent 返回类型 | `Buffer.flip/clear/position` 等返回 `Buffer` | ByteBuffer 等子类增加协变覆盖，链式调用更自然 |
| ByteBuffer API | 基础 slice/duplicate、相对与绝对单值/批量操作 | 增加 `slice(index,length)`、绝对批量 get/put、`mismatch`、对齐相关 API 等 |
| DirectBuffer 内部 | `Unsafe` 分配、`Bits.reserveMemory`、Cleaner 回收的 8u 实现 | 内部清理器、内存会话关联和实现字段继续演进，不依赖私有类 |
| Selector 处理方式 | 取得 `selectedKeys()` 后应用迭代删除 | 公开集合方式仍可用；较新 JDK 还提供基于 action 的 select 重载 |
| 平台轮询器 | Linux epoll、Solaris event port、Windows selector 等 8u 实现 | 平台实现和唤醒机制有重构；macOS 常见 kqueue，Linux 仍通常基于 epoll |
| 新并发模型 | 平台线程 | JDK 21 虚拟线程改善大量阻塞式连接的另一种选择，但不改变 Selector 公开契约 |

跨版本回归应断言公开行为：边界变化、数据完整、key 集合纪律、超时和资源关闭。不要断言 provider 私有类名、底层 fd 数量、一次 select 返回的精确 key 顺序或内部轮询系统调用次数。
