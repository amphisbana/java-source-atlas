# ByteBuffer / Selector 断点与回环实验

调试入口：`io.github.javasourceatlas.jdk.nio.NioBufferSelectorDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.nio.NioBufferSelectorDebugLab
```

实验不访问外网：ServerSocketChannel 绑定 `InetAddress.getLoopbackAddress()` 的系统分配端口，客户端只连接这个回环地址。事件循环有单调时钟截止时间，所有 Selector/Channel 都在 `finally` 中关闭，不会因失败留下监听端口。

## 实验一：手算 Buffer 四指标

运行 `observeBufferStateTransitions()`，按输出逐行填写：

```text
allocate → put(A..D) → flip → get/mark → get/reset
         → get/compact → put(E)/flip → get/rewind → clear
```

推荐断点：

| OpenJDK 8u 方法 | 观察变量 | 验证问题 |
| --- | --- | --- |
| `Buffer.position(int)` | `newPosition`、`mark`、`limit` | position 后退越过 mark 时何时丢弃 mark |
| `Buffer.limit(int)` | `newLimit`、`position`、`mark` | limit 收缩时怎样连带收缩 position/mark |
| `Buffer.nextGetIndex()` | `p`、`limit` | 相对 get 先检查还是先推进 |
| `Buffer.nextPutIndex()` | `p`、`limit` | 相对 put 溢出时 position 是否改变 |
| `Buffer.flip()` | 四指标 | 旧 position 怎样成为新 limit |
| `HeapByteBuffer.compact()` | `pos`、`lim`、`rem`、`hb` | 剩余区间怎样搬到逻辑索引 0 |

不要只看 backing array。`clear()` 后数组里仍可能看到旧字符，但边界已把它们重新解释为可覆盖空间；真正的可读/可写范围由 position 和 limit 决定。

### 验证 mark 被丢弃

Lab 在读取 A 后调用 mark，再读取 B 并 reset。可追加本地观察：把 position 设回 0 后调用 reset，会抛 `InvalidMarkException`。自动测试不依赖异常消息，只断言异常类型。

## 实验二：compact 保留半包

运行 `observeCompactAndViews()`：

1. 写入 A、B、C、D 后 flip；
2. 消费 A、B，此时剩余 C、D；
3. compact 把 C、D 移到头部，得到 `position=2, limit=capacity`；
4. 继续 put E，再 flip，读取结果应为 C、D、E。

这条结果是公开契约，适合 JDK 8/17/21 回归。`System.arraycopy` 是 HeapByteBuffer 8u 的实现观察，不应成为测试断言。

## 实验三：heap/direct、端序与类型视图

同一实验还会打印：

- heap Buffer 的 `hasArray=true, isDirect=false`；
- direct Buffer 的 `hasArray=false, isDirect=true`；
- `0x01020304` 在大端和小端下的四个字节；
- IntBuffer 视图改写第一个 int 后，原 ByteBuffer 的底层内容同步变化；
- 移动视图 position 不会移动原 ByteBuffer position。

推荐断点：

```text
ByteBuffer.allocate(int)
ByteBuffer.allocateDirect(int)
DirectByteBuffer.DirectByteBuffer(int)
Bits.reserveMemory(long, int)
ByteBuffer.order(ByteOrder)
HeapByteBuffer.asIntBuffer()
```

在较新 JDK 中直接内存构造器、Cleaner 与内部包发生变化，优先通过 IDE 当前 JDK 的源码导航定位，不照抄 8u 私有签名。

## 实验四：本机非阻塞回环

运行 `runLoopbackExchange("PING")`。一个 Selector 管理三个 Channel：

| Channel | 初始 interestOps | 附件角色 |
| --- | --- | --- |
| ServerSocketChannel | `OP_ACCEPT` | SERVER |
| 发起连接的 SocketChannel | `OP_CONNECT`，若立即连接则为 `OP_WRITE` | CLIENT |
| accept 得到的 SocketChannel | 注册后为 `OP_READ` | PEER |

事件循环必须完成以下公开结果：

```text
CLIENT 写 PING
  → PEER 分多次也允许地读满 4 字节
  → PEER 把同样字节作为 pending output
  → PEER 写回
  → CLIENT 读满 PING
```

Lab 不断言 ACCEPT 和 CONNECT 哪个先到，也不要求一次 read/write 完成。每个 key 的附件保存输入 Buffer、待输出 Buffer 和预期字节数；只要输出还有 remaining 才保留 OP_WRITE，写完立即移除。

### 推荐断点

| 层次 | OpenJDK 8u 入口 | 观察重点 |
| --- | --- | --- |
| provider | `Selector.open`、`SelectorProvider.provider/openSelector` | 当前平台 provider 怎样创建 Selector |
| 注册 | `AbstractSelectableChannel.register` | `regLock`、`validOps`、blocking 检查、已有 key |
| 选择门面 | `SelectorImpl.lockAndDoSelect` | selector、keys、selectedKeys 的锁顺序与 timeout 转换 |
| 平台等待 | 当前 provider 的 `doSelect` | 注销队列、底层 poll、ready 更新、wakeup 排空 |
| key 更新 | `SelectionKeyImpl.interestOps/readyOps` 与平台更新方法 | Java 位掩码如何映射到 native event |
| socket | `SocketChannelImpl.connect/finishConnect/read/write` | 非阻塞返回值和状态迁移 |

在 Linux 8u 可继续观察 `EPollSelectorImpl.doSelect/updateSelectedKeys/wakeup`；macOS、Windows 和 JDK 17/21 应跟随实际 provider，不强行寻找同名类。

## 实验五：selectedKeys 删除纪律

事件循环每次取得 key 后立即调用 `iterator.remove()`，再执行业务处理。调试时观察：

```text
select 返回 n
  → selectedKeys 中出现就绪 key
  → iterator.next()
  → iterator.remove()
  → 业务 handler 可能更新 interestOps
  → 本轮结束 selectedKeys 为空
```

测试另建一个回环 accept 场景，故意在第一次 select 后暂不移除 key，验证 `selectNow()` 不会替应用清空集合；随后用同一迭代器 remove 才变空。不要断言第二次 selectNow 的返回值，它取决于本轮 ready set 是否被实现更新。

## 实验六：`wakeup()` 打断阻塞选择

运行 `observeWakeup()`：工作线程调用带长超时的 `select`，主线程在闩锁确认它已准备进入选择后调用 wakeup。即便 wakeup 恰好发生在 select 之前，公开契约也保证下一次选择立即返回，所以实验不需要 `Thread.sleep`。

推荐观察：

```text
控制线程：enqueue/change state → selector.wakeup()
选择线程：select 返回 0 或业务就绪数 → drain control queue → 下一轮
```

实验只验证在短截止时间内返回，不把返回值强制为 0，因为并发情况下也可能同时有真实就绪事件。

## Focused 自动化测试

```bash
mvn -pl labs/jdk-labs \
  -Dtest=io.github.javasourceatlas.jdk.nio.NioBufferSelectorBehaviorTest \
  test
```

测试覆盖：

1. `flip/rewind/clear` 与 mark/reset 的公开状态。
2. `compact` 对未读尾部的保序，以及 clear 不清零。
3. heap/direct 的公开内存形态。
4. 明确端序下的字节布局和类型视图共享。
5. blocking Channel 不能注册，非阻塞后可以注册合法 ops。
6. selected key 在应用移除前持续存在。
7. wakeup 在有界时间内使 select 返回。
8. 本机回环实际走过 ACCEPT/CONNECT/READ/WRITE，响应完整且 OP_WRITE 在空队列后被移除。

类级 `@Timeout` 只是最后保险；网络与线程等待本身都有更短的显式截止时间。失败时输出事件轨迹，而不是无限挂起。

## JDK 8 与 17 双版本运行

```bash
JAVA_HOME=/path/to/jdk8  PATH="$JAVA_HOME/bin:$PATH" \
  mvn -pl labs/jdk-labs -Dtest=NioBufferSelectorBehaviorTest test

JAVA_HOME=/path/to/jdk17 PATH="$JAVA_HOME/bin:$PATH" \
  mvn -pl labs/jdk-labs -Dtest=NioBufferSelectorBehaviorTest test
```

项目以 `--release 8` 编译，所以 Lab 不使用较新版本的 `ByteBuffer.slice(index,length)`、绝对批量 get/put 或 Selector action 重载。这样同一行为测试可直接比较 8/17/21 的公开契约。

## 常见故障定位表

| 现象 | 先检查 | 不要立即下结论 |
| --- | --- | --- |
| 消息头乱码 | ByteOrder、flip 时机、实际 remaining | 网络一定丢包 |
| 消息偶尔拼接/截断 | 一次 read 被当完整帧、compact/协议状态 | Selector 漏事件 |
| CPU 100% 且 select 高频返回 | 空 pending output 仍关注 OP_WRITE、selectedKeys 未删除、selectNow 空转 | JDK 一定存在 epoll bug |
| 新注册迟迟不生效 | 是否由其他线程注册、命令是否先入队、是否 wakeup | interestOps 写入失败 |
| 关闭后 keys 仍暂时可见 | cancel 是延迟注销、下一次选择是否执行 | Channel 没有关闭 |
| 直接内存增长 | 是否无界分配、是否复用、`MaxDirectMemorySize` 与监控 | 调用 GC 就是修复 |

完成实验后回到 [专题总览](./index.md)，用动画逐帧把 Buffer 状态和 SelectionKey 状态对齐。
