# Reference / WeakHashMap 断点实验手册

版本入口：[JDK 8 / 17 / 21 Reference / WeakHashMap 对比](/jdk/version-comparison/?topic=reference-weakhashmap)。同一组行为测试会通过反射探测新 API，同时保持 Java 8 编译目标。

调试入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/reference/ReferenceWeakHashMapDebugLab.java
```

行为测试：

```text
labs/jdk-labs/src/test/java/
  io/github/javasourceatlas/jdk/reference/ReferenceWeakHashMapBehaviorTest.java
```

## 运行命令

```bash
mvn -pl labs/jdk-labs \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.reference.ReferenceWeakHashMapDebugLab \
  exec:java

mvn -pl labs/jdk-labs \
  -Dtest=ReferenceWeakHashMapBehaviorTest \
  test
```

案例只依赖 Java 8 公开 API，可在 Java 8、17、21 编译运行。GC 观察打印本次结果，但不作为自动测试通过条件；确定性测试只验证 clear/enqueue 分离、队列消费、null key、equals 与 value 回指等公开行为。

## 场景一：clear 与 enqueue 的非对称关系

`observeExplicitQueueProtocol()` 建立两个注册到同一 ReferenceQueue 的 WeakReference，分别观察 clear-only 和 direct-enqueue：

```text
clear-only：get() == null，queue 仍为空
direct-enqueue：调用前 get() != null，调用后 get() == null
direct-enqueue：queue.remove(timeout) 返回同一个 WeakReference
```

推荐断点：

- `Reference.clear`
- `Reference.enqueue`
- `ReferenceQueue.enqueue`
- `ReferenceQueue.remove(long)`

这条场景完全确定，不依赖 GC。它同时验证了两个方向：`clear()` 不会自动入队，而三版 `enqueue()` 都会先清 referent 再入队；实现差异是 JDK 8 直接写字段，JDK 17/21 改走 GC 感知的 `clear0()`。

## 场景二：观察 WeakHashMap 弱键

`observeWeakKeyCollection()` 把一个 key 放进 WeakHashMap，再只保留用于观察的 WeakReference。实验有限次数请求 GC 并制造轻量分配压力：

```text
key 本次被清除：触发 map.size()，观察 expunge 后 size
key 本次未清除：打印“GC 只是建议”，实验仍正常结束
```

即使第一行已经显示 `key 已清除=true`，本次 `map.size()` 仍可能暂时得到 `1`。`WeakReference.get()` 变成 `null`、Entry 进入 `ReferenceQueue`、`expungeStaleEntries()` 从桶链摘除 Entry 是三个先后协作的动作；如果 size 调用恰好落在清除 referent 与 Entry 入队之间，它还没有可消费的队列元素。后续 Map 访问只能清理届时已经入队的 Entry，不能促使 Reference Handler 入队；程序不能依赖访问次数或固定时延推进这条链。

推荐断点：

1. `Reference.tryHandlePending`（JDK 8）或当前版本 Reference Handler 入口；
2. `ReferenceQueue.enqueue`；
3. `WeakHashMap.getTable`；
4. `WeakHashMap.expungeStaleEntries`。

不要写“运行到第 N 次 GC 必然清除”。收集器、堆占用、调试器对象查看和 JIT 都会改变可达性与时机。

## 场景三：value 回指 key

`observeValueBackReference()` 建立：

```text
WeakHashMap -> Entry -> OwnerMetadata -> key
                      Entry ~weak~> key
```

外部 key 变量释放后，metadata 仍强引用 key，因此观察 WeakReference 仍可取得对象。这条结果由强可达图保证，不依赖 GC 是否执行。

在内存分析工具中应从 GC Root 反向查看 key 的强引用链，而不是只看 Entry 的 WeakReference 边。

## 场景四：null key 哨兵

`observeNullKeyBoundary()` 写入 null key 和 null value，调用 GC 后再次读取。公开行为只说明映射仍由 Map 持有；断点进入 `maskNull/unmaskNull` 可看到内部 `NULL_KEY`。

不要通过反射读取私有哨兵。JDK 17+ 模块封装会阻止这种做法，而且哨兵身份本来就不是公开契约。

## 场景五：PhantomReference 通知

`observePhantomReference()` 验证 `phantom.get()` 从创建开始就是 null，并有限等待队列通知。若本次 GC 未入队，只打印观察结果。

真实资源清理还需要 Reference 子类保存 `resourceId` 等元数据，并由专用线程幂等释放。本 Lab 不创建 native 资源，避免把演示写成不可控清理器。

## JDK 8 推荐断点表

| 断点 | 观察变量 | 能回答的问题 |
| --- | --- | --- |
| `Reference.tryHandlePending` | `pending`、`r`、`c` | GC 与 Handler 怎样交接 |
| `ReferenceQueue.enqueue` | `r.queue`、`head`、`queueLength` | Reference 怎样进入注册队列 |
| `ReferenceQueue.reallyPoll` | `head`、`r.next` | 出队后怎样转为 inactive |
| `WeakHashMap.put` | `k`、`h`、`i`、`tab` | Entry 怎样保存 weak key 与 strong value |
| `WeakHashMap.expungeStaleEntries` | `x`、`i`、`prev`、`p` | 已清 key 怎样按 Entry 身份摘链 |
| `WeakHashMap.transfer` | `key`、`src`、`dest` | resize 中怎样丢弃 stale Entry |
| `WeakHashMap.HashIterator.hasNext` | `nextKey`、`currentKey` | 迭代器怎样暂时保活 key |

JDK 17/21 的 Reference pending 处理已重构：从 `tryHandlePending` 改看 `processPendingReferences` 与 VM pending-list 入口；clear/enqueue 改看 `clear0`；WeakHashMap 的 key 匹配与 stale 判断改看 `matchesKey/refersTo`。JDK 21 的 ReferenceQueue 等待锁也已从 monitor 改为 ReentrantLock/Condition。WeakHashMap 其余断点名称大体仍可用，但应以当前 SDK 附带源码为准。

## 自动测试覆盖

| 测试 | 稳定断言 |
| --- | --- |
| `shouldSeparateClearFromEnqueue` | clear 不自动入队，显式 enqueue 后可取回同一 Reference |
| `shouldExposeNullFromPhantomReference` | PhantomReference.get 始终返回 null |
| `shouldSupportNullKeyAndValue` | WeakHashMap 支持 null key/value |
| `shouldUseEqualsWhileKeysAreAlive` | 活 key 仍按 equals 进行 Map 查找 |
| `shouldKeepKeyReachableThroughValueBackReference` | Map 的强 value 回指能保活 key |
| `shouldRemoveMappingsExplicitly` | 显式 remove/clear 仍遵循普通 Map 语义 |
| `shouldInspectReferentIdentityFromJdk16` | JDK 16+ 的 refersTo 不通过 get 判断身份，PhantomReference 也可判断 |
| `shouldCreateWeakHashMapForExpectedMappingsFromJdk19` | JDK 19+ 工厂正常创建并拒绝负数，旧版入口不存在 |
| `shouldExposeSealedReferenceHierarchyFromJdk19` | JDK 21 快照的 Reference sealed，具体公开引用类仍 non-sealed |
| `shouldKeepReferenceQueueWaitContractAcrossVersions` | timed remove、enqueue 唤醒与 interrupt 契约跨版本稳定 |

## 实验通过标准

- 能解释为什么 GC 清 key 与 Map 删除 Entry 不是同一步；
- 能在断点中确认 ReferenceQueue 返回 Reference 对象而不是 referent；
- 能画出 value 回指 key 的强引用路径；
- 不把 System.gc 或某次 size 变化写成确定性契约；
- 能区分显式资源关闭、Cleaner 兜底和 WeakHashMap 元数据清理。
