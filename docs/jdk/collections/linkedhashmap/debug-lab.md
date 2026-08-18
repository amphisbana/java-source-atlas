# LinkedHashMap 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.collection.LinkedHashMapDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.LinkedHashMapDebugLab
```

自动化测试入口：`LinkedHashMapBehaviorTest`。实验只通过公开 API 触发分支，不使用反射读取 `head`、`tail` 或节点字段；断点进入本机 JDK 源码后再观察内部变量。

## 调试环境准备

1. 为 IDE 使用的 JDK 附加与运行版本一致的 `src.zip`。
2. 确认调试时打开的是 `java.util.LinkedHashMap`，而不是项目中的实验类。
3. 对 JDK 类断点无效时，检查 IDE 是否跳过了 `java.*` 类、是否启用了“只调试用户代码”。
4. 使用 JDK 8 观察本文方法签名；在 JDK 17/21 上运行公开行为仍可验证，但私有调用和行号可能不同。

## 推荐断点总表

| 类与方法 | 观察变量 | 要回答的问题 |
| --- | --- | --- |
| `LinkedHashMap.newNode` | `key`、`e`、`head`、`tail` | 新普通节点何时加入顺序链 |
| `linkNodeLast` | `p`、`last`、`head`、`tail` | 空链与非空链分别更新哪些字段 |
| `replacementNode` | `p`、`next`、`q`、`t` | 树节点退化后顺序位置如何保留 |
| `replacementTreeNode` | `p`、`next`、`q`、`t` | 普通节点树化后顺序位置如何保留 |
| `LinkedHashMap.get` | `key`、`e`、`accessOrder` | 查找与顺序维护如何分层 |
| `afterNodeAccess` | `p`、`b`、`a`、`last`、`modCount` | 非尾节点如何摘下再接到尾部 |
| `afterNodeInsertion` | `evict`、`first`、`size` | 淘汰策略在何时被询问 |
| `removeEldestEntry` | `eldest`、`size()`、`maxEntries` | 为什么第 4 项加入后才返回 true |
| `afterNodeRemoval` | `p`、`b`、`a`、`head`、`tail` | 删除头、中间、尾节点如何修链 |
| `LinkedHashIterator.nextNode` | `next`、`current`、两个修改计数 | 遍历为何沿 after 前进、何时快速失败 |

`replacementNode` 等方法可能只在树化/退化、克隆或内部重建路径出现。初次实验优先完成访问顺序和 LRU，再用碰撞键单独构造树桶场景。

## 实验一：插入顺序不受覆盖影响

运行 `observeInsertionOrder()`：

```text
put(A), put(B), put(C), put(B, 新值)
```

预期 key 顺序仍为 `A, B, C`。建议断点：

1. `HashMap.putVal` 找到 key 为 B 的已有节点。
2. 观察它调用 `afterNodeAccess`。
3. 进入 `afterNodeAccess` 后确认 `accessOrder=false`，链表不移动。

填写变量：

```text
覆盖前 size = ___，modCount = ___
覆盖后 size = ___，modCount = ___
head.key = ___，tail.key = ___
```

覆盖 value 不是新增映射，插入位置不应变化。

## 实验二：get 把节点移到 tail

运行 `observeAccessOrder()`。初始顺序为 `A, B, C`，随后读取 A。

在 `afterNodeAccess` 中逐行观察：

```text
p.key = ___
b = ___
a.key = ___
last.key = ___
```

A 原本是 `head`，所以 `b == null`。修复原位置后 `head` 应变为 B；接到旧尾 C 后，`tail` 应变为 A，最终顺序 `B, C, A`。

实验随后读取不存在的 key。确认 `getNode` 返回 `null` 后直接结束，不进入移动 Hook，顺序保持不变。

## 实验三：集合视图读取不是访问

运行 `observeCollectionViewRead()`。调用 `map.keySet().contains("A")` 后，访问顺序仍是 `A, B, C`。

可对比两条路径：

```text
keySet.contains -> containsKey -> getNode          不调用 afterNodeAccess
map.get         -> getNode -> afterNodeAccess      命中时可能移动
```

这说明“能找到一个 key”不等于 LinkedHashMap 定义的“访问事件”。

## 实验四：插入 D 后淘汰真正的 LRU

运行 `observeLruEviction()`：

```text
最大条目数 3
put A/B/C -> get A -> put D
```

关键快照应为：

```text
访问 A 后: B, C, A
插入 D 后、淘汰前: B, C, A, D
淘汰完成: C, A, D
```

建议同时在三个位置断点：

1. `newNode`：D 先通过 `linkNodeLast` 成为 `tail`。
2. 实验子类的 `removeEldestEntry`：此时 `size()` 为 4，`eldest.key` 为 B。
3. `afterNodeRemoval`：确认删除 B 后 `head` 变为 C，最终 `size` 回到 3。

如果断点看到 `eldest` 是 A，说明前面的 `get(A)` 没有进入访问顺序模式，应检查三参数构造器的第三个参数。

## 实验五：读取也能让旧迭代器失效

运行 `observeFailFastAfterAccess()`：

```text
创建 keySet 迭代器
get(A) 将 A 从 head 移到 tail
iterator.next()
```

在 `afterNodeAccess` 末尾观察 `modCount` 增加；在 `LinkedHashIterator.nextNode` 中比较：

```text
expectedModCount = ___
modCount = ___
```

二者不同，因此抛出 `ConcurrentModificationException`。不要用该异常实现并发同步，它只提供尽力而为的错误检测。

## 实验六：删除头、中间和尾

可以在 IDE Evaluate Expression 中依次创建新映射并删除：

```java
map.remove("A"); // 删除 head
map.remove("B"); // 删除中间节点
map.remove("C"); // 删除 tail 或最后节点
```

每次停在 `afterNodeRemoval`，按下表检查：

| 删除位置 | `b` | `a` | 应更新字段 |
| --- | --- | --- | --- |
| head | `null` | 原第二项 | `head = a`、`a.before = null` |
| 中间 | 前驱 | 后继 | `b.after = a`、`a.before = b` |
| tail | 原倒数第二项 | `null` | `tail = b`、`b.after = null` |
| 唯一节点 | `null` | `null` | `head = tail = null` |

源码还会清空被删节点的 `before/after`，避免它继续携带已经失效的顺序关系。

## 断点变量快照模板

调试复杂链路时，可以逐步记录下面这张表：

| 步骤 | `size` | `modCount` | `head.key` | `tail.key` | `p.key` | `b.key` | `a.key` | key 顺序 |
| --- | ---: | ---: | --- | --- | --- | --- | --- | --- |
| 操作前 |  |  |  |  |  |  |  |  |
| 摘除后 |  |  |  |  |  |  |  |  |
| 接到尾部后 |  |  |  |  |  |  |  |  |
| 淘汰后 |  |  |  |  |  |  |  |  |

不要只看 `table`。用 `head -> after` 重建顺序，再用每个节点的 `before` 反向核对，才能确认双向链是否完整。

## 自动化验证范围

`LinkedHashMapBehaviorTest` 覆盖：

- 插入顺序下覆盖已有 value 不换位。
- 访问顺序下命中节点移到尾部。
- 集合视图读取不刷新访问顺序。
- LRU 淘汰的是最久未访问项。
- `putIfAbsent`、`computeIfAbsent`、`compute`、`merge` 真正新增映射时同样触发 LRU 淘汰。
- 访问移动会使已经创建的迭代器快速失败。

测试刻意不反射 `head/tail`，也不断言精确 `modCount`，以免把教学验证绑定到 JDK 私有实现。内部字段由断点实验负责观察。

## JDK 版本差异核对点

| 核对项 | JDK 8 | 较新 JDK 阅读提示 |
| --- | --- | --- |
| `getNode` 调用形态 | 传入 hash 和 key | JDK 17 的内部签名已调整 |
| 顺序链核心 | `head/tail`、`before/after` | JDK 17 主体思路一致 |
| 树节点关系 | `HashMap.TreeNode` 可携带顺序链接 | 仍应以目标版本源码确认继承关系 |
| 顺序公开 API | 主要通过迭代和构造模式体现 | JDK 21 增加 SequencedMap 首尾、反向等 API |

跨版本阅读时，先验证公开行为，再定位目标 JDK 的真实方法和变量名；不要拿 JDK 8 的行号直接指导 JDK 21 调试。
