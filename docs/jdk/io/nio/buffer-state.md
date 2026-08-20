# Buffer 状态机：边界移动、内存形态与类型视图

`Buffer` 不知道“现在处于读模式还是写模式”。它只维护四个边界，读写 API 根据这些边界推进。所谓切换模式，是调用者用 `flip/clear/compact/rewind` 重新解释同一片存储区域。

版本入口：[JDK 8 / 17 / 21 ByteBuffer / Selector 对比](/jdk/version-comparison/?topic=bytebuffer-selector)。先掌握本页稳定的四指标协议，再看新版 API 如何减少临时修改边界和丢失具体类型的问题。

## 四个指标的准确含义

```text
-1 <= mark <= position <= limit <= capacity
```

| 指标 | 含义 | 是否由相对读写推进 |
| --- | --- | --- |
| `capacity` | 可容纳的元素总数，创建后不变 | 否 |
| `limit` | 第一个不允许相对读写的索引 | 否，由边界方法修改 |
| `position` | 下一次相对读写使用的索引 | 是 |
| `mark` | `reset()` 要恢复到的 position；`-1` 表示未定义 | 否，由 `mark/reset` 使用 |

`remaining()` 始终等于 `limit - position`，`hasRemaining()` 等价于 `position < limit`。它们只回答当前边界内还剩多少元素，不知道这些元素对业务来说是空闲空间、已接收数据还是待发送数据。

### 相对访问与绝对访问

```java
byte next = buffer.get();       // 使用当前 position，成功后 position + 1
buffer.put((byte) 7);           // 使用当前 position，成功后 position + 1
byte first = buffer.get(0);     // 使用显式索引，不改变 position
buffer.put(0, (byte) 9);        // 使用显式索引，不改变 position
```

OpenJDK 8u `Buffer.nextGetIndex/nextPutIndex` 先检查 position 与 limit，再推进 position。相对读越界抛 `BufferUnderflowException`，相对写越界抛 `BufferOverflowException`。绝对访问检查显式索引但不推进 position；它仍以当前 limit 为边界，并不是可以任意访问到 capacity。

## 九步手算一个八字节 Buffer

以 `ByteBuffer.allocate(8)` 为例：

| 步骤 | 内容示意 | mark | position | limit | capacity | 当前可访问区间 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `allocate(8)` | `_ _ _ _ _ _ _ _` | - | 0 | 8 | 8 | 可写 `[0,8)` |
| `put(A..D)` | `A B C D _ _ _ _` | - | 4 | 8 | 8 | 可写 `[4,8)` |
| `flip()` | `A B C D _ _ _ _` | - | 0 | 4 | 8 | 可读 `[0,4)` |
| `get(A); mark()` | `A B C D _ _ _ _` | 1 | 1 | 4 | 8 | 可读 `[1,4)` |
| `get(B); reset()` | `A B C D _ _ _ _` | 1 | 1 | 4 | 8 | B 可以重读 |
| `get(B); compact()` | `C D ? ? ? ? ? ?` | - | 2 | 8 | 8 | C、D 已搬到头部，后面可写 |
| `put(E); flip()` | `C D E ? ? ? ? ?` | - | 0 | 3 | 8 | 可读 `[0,3)` |
| `get(C,D); rewind()` | `C D E ? ? ? ? ?` | - | 0 | 3 | 8 | 重新读 `[0,3)` |
| `clear()` | `C D E ? ? ? ? ?` | - | 0 | 8 | 8 | 整段重新视为可写 |

表中的 `?` 表示内容不再属于当前有效数据，而不是内存一定被清零。`clear()` 只移动边界；如果业务有敏感数据擦除要求，需要显式覆盖，不能依赖 Buffer 状态方法。

## `mark/reset` 为什么经常失效

OpenJDK 8u 的规则是：

- `mark()` 保存当前 position；
- `reset()` 把 position 恢复为 mark，但保留 mark，允许再次 reset；
- `position(newPosition)` 若把 position 降到 mark 左侧，丢弃 mark；
- `limit(newLimit)` 若把 limit 降到 position 左侧，会先收缩 position；若越过 mark，也丢弃 mark；
- `clear/flip/rewind/compact` 都丢弃 mark。

因此 mark 更适合一次局部解析回退，不适合作为跨多轮 I/O 的持久游标。协议解析器通常应把“本次消息起点、预期长度、解析阶段”放在连接附件中，而不是依赖一个容易被边界操作清除的 mark。

## 四个边界方法不能互换

### `flip()`：把刚写入的前缀交给读取方

```text
limit = oldPosition
position = 0
mark = undefined
```

它最适合“Buffer 刚作为 Channel 读取目标或相对 put 目标，现在要作为读取源”。连续调用两次 flip，第二次会把 limit 设为 0，得到空可读区间，这是常见故障。

### `clear()`：放弃所有未读数据，整段重新可写

```text
position = 0
limit = capacity
mark = undefined
```

它不清零，也不保留 `[position, limit)` 的未处理内容。只有确定旧数据全部消费或允许丢弃时才能 clear。

### `compact()`：保留未处理尾部，再继续接收

假设读取状态为 `position=2, limit=6, capacity=8`，则 remaining 为 4。堆实现的核心等价于：

```text
copy storage[2..6) → storage[0..4)
position = 4
limit = 8
mark = undefined
```

压缩后 position 指向保留数据末尾，下一次 Channel read 会从这里继续写。要重新读取组合后的完整数据，通常在下一轮读取完成后再 flip。`compact` 可能移动数据，不能在热路径中不加分析地每次调用。

### `rewind()`：在既有限制内重新读取

```text
position = 0
limit 保持不变
mark = undefined
```

它适合重新消费同一可读区间，不会像 flip 那样根据当前 position 收缩 limit，也不会像 clear 那样把 limit 扩到 capacity。

## HeapByteBuffer 与 DirectByteBuffer

### 堆缓冲区

`ByteBuffer.allocate(capacity)` 在 8u 返回 `HeapByteBuffer`，核心存储是 `byte[] hb` 加 `offset`：

- 分配和普通对象一样受 Java 堆管理，创建成本通常较低；
- `hasArray()` 为 true，可在非只读情况下取得 `array()` 和 `arrayOffset()`；
- `slice` 与 `duplicate` 共享同一数组，但拥有独立的 mark/position/limit/capacity；
- `compact` 可用 `System.arraycopy` 把剩余字节搬到逻辑索引 0。

`ByteBuffer.wrap(array, offset, length)` 有一个容易误解的状态：capacity 仍是整个数组长度，position 为 offset，limit 为 `offset + length`。它没有创建容量等于 length 的独立切片；需要逻辑索引从 0 开始时，再明确调用 slice 并检查版本差异。

### 直接缓冲区

`ByteBuffer.allocateDirect(capacity)` 在 8u 的典型实现会：

1. 通过 `Bits.reserveMemory` 记账并检查直接内存上限；
2. 使用底层内存分配能力取得 native address；
3. 建立 Cleaner，在 Buffer 不再可达后释放内存并归还记账；
4. slice/duplicate 共享同一片 native memory，并通过 attachment 保持拥有者可达。

直接缓冲区可减少某些 native I/O 路径的中间复制，但创建、清理和小块频繁分配成本更高。它不是“永远零拷贝”，也不保证一离开作用域就立即释放。工程上通常复用有界的直接 Buffer，结合真实吞吐与内存指标决定，而不是把所有 byte[] 机械替换。

JDK 17/21 的直接内存实现、Cleaner 与内存会话关联已有演进；`sun.nio.ch.DirectBuffer`、address、cleaner 等都是私有细节。业务代码只依赖 `isDirect()` 和公开 I/O 行为。

## 字节序决定多字节值如何拼装

一个字节没有端序；`short/int/long/char/float/double` 才需要决定高低字节顺序。新建 ByteBuffer 默认 `BIG_ENDIAN`：

```java
ByteBuffer bytes = ByteBuffer.allocate(4);
bytes.order(ByteOrder.BIG_ENDIAN).putInt(0x01020304);
// 存储：01 02 03 04

bytes.clear();
bytes.order(ByteOrder.LITTLE_ENDIAN).putInt(0x01020304);
// 存储：04 03 02 01
```

`ByteOrder.nativeOrder()` 只告诉本机原生顺序，不代表网络协议、磁盘格式或第三方消息就应使用它。协议端序必须显式规定；网络协议常用大端，但应以具体协议为准。

## 类型视图：共享内容，独立游标

`asIntBuffer()` 从当前 ByteBuffer 的 position 开始建立 int 视图：

- 视图 capacity/limit 是 `remainingBytes / 4`，不足四字节的尾部不可见；
- 内容共享，任一视图写入都会反映到底层字节；
- 两边 position/limit/mark 独立，移动 IntBuffer position 不会移动原 ByteBuffer position；
- 视图在创建时采用 ByteBuffer 当前字节序；之后修改原 ByteBuffer 的 order，不应拿来推断既有视图会同步切换。

`slice()` 和 `duplicate()` 同样共享内容并拥有独立边界。要避免“谁拥有存储、谁拥有游标”的混淆：Buffer 对象拥有状态，底层内存可能被多个 Buffer 共同引用。

## 版本演进：便利 API 没有改变四指标

### JDK 9 协变返回解决的是静态类型

JDK 8 的 `Buffer.flip/clear/position/limit` 等方法是 `final` 并返回 `Buffer`，所以这段源码不能以 JDK 8 为目标编译：

```java
ByteBuffer readable = buffer.flip(); // JDK 9+ 才能保留 ByteBuffer 静态类型
```

JDK 17/21 的 `X-Buffer.java.template` 会为 ByteBuffer 等具体类型生成覆盖方法：先调用 `super.flip()`，再返回 `this`。因此改变的是链式调用的返回类型，`limit=旧 position、position=0、mark 丢弃` 的协议仍只在 `Buffer.flip` 中实现。`javap` 同时显示具体返回方法和 `Buffer` 返回的 synthetic bridge，不能据此误判为两次状态变更。

### JDK 13 二参 slice 解决的是状态污染

JDK 17/21 已包含 `slice(int index, int length)`：

```text
原 Buffer: position=4, limit=8
slice(1, 3)
  → index 取逻辑绝对索引 1，不是 position+1
  → 新视图 position=0, limit=capacity=3
  → 原 Buffer position 仍为 4
  → 内容共享；任一视图写入都能从另一侧观察
```

范围校验以原 Buffer 的当前 `limit` 为上界。兼容 JDK 8 时，应先 `duplicate()`，再在副本上调整 position/limit 并 `slice()`，不要临时修改原 Buffer 后再尝试手工恢复；异常或并发读取很容易留下错误边界。

### JDK 21 sealed 与 MemorySegment 是两条边界

JDK 17 的 `Buffer` 仍是普通抽象类，并用内部 `MemorySegmentProxy` 记录 Foreign Memory 生成 Buffer 的访问作用域。JDK 21 固定快照中：

- `Buffer` 和 `ByteBuffer` 已是 sealed 层级，这项变化实际自 JDK 19 出现；
- `Buffer.segment` 改为 `java.lang.foreign.MemorySegment`，派生 ByteBuffer 继承 segment 的 session；
- `MemorySegment.asByteBuffer()` 与 `MemorySegment.ofBuffer(buffer)` 共享内存，后者只覆盖原 Buffer 的 `[position, limit)`；
- Arena/session 关闭后，访问关联 Buffer 会失败；这与普通 `ByteBuffer.allocate/allocateDirect` 的生命周期不同；
- JDK 21 的 `java.lang.foreign` 仍是预览 API，需要对应编译和启动参数，不能按稳定 API 直接发布兼容库。

sealed 主要把 JDK 原本受包级构造器限制的实现层级写入类型系统，不会改变 `mark <= position <= limit <= capacity`，也不意味着业务代码之前存在一个受支持的自定义 Buffer 扩展点。

## 一次正确的半包处理

长度字段为 2 字节、消息体长度可变时，不能假设一次 read 同时得到头和体：

```text
read(input) > 0
  → flip()
  → remaining < 2：compact()，等待更多头字节
  → 读出 length
  → remaining < length：恢复协议阶段，compact()，等待消息体
  → remaining >= length：解析一帧；若还有尾部，继续解析或 compact()
```

复杂协议通常把预期长度和解析阶段放进 `SelectionKey.attachment()` 对应的连接状态对象，避免用 mark 跨越 compact。解析循环还必须限制最大帧长，防止恶意长度导致无界扩容。

## 稳定断言与脆弱观察

| 适合自动化测试 | 只适合特定版本调试 |
| --- | --- |
| 四指标不变量和每个公开状态方法的结果 | HeapByteBuffer/DirectByteBuffer 精确私有字段布局 |
| compact 后剩余字节顺序保持 | compact 最终使用哪条复制或 intrinsic 路径 |
| heap/direct 的 `hasArray/isDirect` 公开结果 | Cleaner 具体类、回收发生的准确时刻 |
| 类型视图共享内容、游标独立 | 视图具体实现类名 B/L/U/S |
| 明确端序下的字节布局 | 当前 CPU 原生端序被写死为某个值 |

下一步进入 [Selector 事件循环](./selector-loop.md)，把 Buffer 的“剩余进度”挂到每个 SelectionKey 的附件上。
