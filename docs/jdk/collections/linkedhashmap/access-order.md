# LinkedHashMap：访问顺序与 afterNodeAccess

访问顺序模式通过三参数构造器开启：

```java
Map<String, Integer> map = new LinkedHashMap<>(16, 0.75f, true);
```

第三个参数为 `true` 时，遍历顺序从“最久未访问”指向“最近访问”。它不是访问次数排序，也不保存时间戳；每次有效访问只是把目标节点移动到双向链表尾部。

## 动画：get(B) 后移与下一次 LRU 淘汰

下面从顺序 `A -> B -> C` 开始。先执行 `get(B)`，再向最大容量为 3 的 LRU 映射写入 `D`。前四步展示访问移动，后三步展示新节点入尾和 `head` 淘汰。

<LinkedHashMapAccessAnimation />

动画展示的是顺序链。真实节点还同时存在于 HashMap 的桶数组中；移动 `before/after` 不会重新计算 hash，也不会把节点换到另一个桶。

## get 的两阶段职责

`LinkedHashMap` 必须重写 `get`，因为父类 `HashMap.get` 只负责找到节点，不知道读取是否应该改变顺序。

最小控制流如下：

```text
node = getNode(hash(key), key)
if node 不存在: return null
if accessOrder: afterNodeAccess(node)
return node.value
```

JDK 8 的 `getNode` 接受预先计算的 hash；较新 JDK 可能把签名整理为只接收 key。阅读时应关注职责，而不是死记私有方法参数。

`getOrDefault` 也有同样的顺序维护逻辑。未命中时返回默认值，但不会产生节点访问，也不会改变链表。

## afterNodeAccess 的精确步骤

假设访问前：

```text
head                                      tail
 A       <==>       B       <==>       C
                    p                   last
```

此时 `p.before=A`、`p.after=C`、`last=C`。`afterNodeAccess` 只有在 `accessOrder` 为真且 `p != tail` 时执行移动。

### 1. 保存邻居并断开 p 的后向链接

```text
b = p.before
a = p.after
p.after = null
```

保存局部变量后，后续即使覆盖节点字段，也不会丢失原前驱和后继。

### 2. 修复原位置

如果 `b == null`，说明目标原本是 `head`，新 `head` 应变为 `a`；否则令 `b.after = a`。

如果 `a != null`，令 `a.before = b`。正常进入该方法时目标不是 `tail`，所以通常存在 `a`；源码仍保留完整边界处理。

中间状态可以理解为：

```text
A <==> C       B（暂时离开可遍历主链）
```

### 3. 接到旧 tail 之后

```text
p.before = last
last.after = p
tail = p
```

最终顺序变为：

```text
head                                      tail
 A       <==>       C       <==>       B
```

### 4. 增加 modCount

顺序变化会改变迭代结果，因此属于结构性修改。JDK 8 在节点真正移动后执行 `++modCount`。

`size` 始终不变，桶数组和桶内 `next` 也不变。

## 三个边界分支

| 场景 | 顺序 | `modCount` |
| --- | --- | --- |
| `get` 未命中 | 不变 | 不变 |
| 命中当前 `tail` | 不变，因为已经是最近访问 | JDK 8 实现中不增加 |
| 命中非尾节点 | 移到 `tail` | 增加 1 |

Javadoc 常用“访问顺序映射中的 `get` 是结构性修改”提醒调用者。落实到 JDK 8 的实现，结构没有变化的尾节点命中会被快速跳过。业务代码不应依赖精确的内部计数值，但调试迭代器时必须知道这个分支。

## 哪些操作会形成“访问”

OpenJDK 8 的类级契约明确列出了访问来源。

| 操作 | 是否可能改变访问顺序 | 条件 |
| --- | --- | --- |
| `get`、`getOrDefault` | 是 | 命中映射 |
| `put`、`putIfAbsent` | 是 | 调用完成后对应映射存在 |
| `computeIfAbsent` | 是 | 已有非 `null` 值，或计算出非 `null` 值并建立/更新映射 |
| `computeIfPresent`、`compute`、`merge` | 是 | 结果为非 `null` 并保留/建立映射；结果为 `null` 时会删除或不新增 |
| `replace` | 是 | 实际替换了 value |
| `putAll` | 是 | 按来源映射 `entrySet` 的迭代顺序逐项访问 |
| `containsKey`、`containsValue` | 否 | 只查询，不属于访问事件 |
| `keySet` / `values` / `entrySet` 上的读取与遍历 | 否 | 视图操作不会刷新访问顺序 |

对新插入节点而言，`newNode` 本来就把它接到尾部；对已有节点而言，`HashMap.putVal` 在完成值处理后调用 `afterNodeAccess`。

这些复合方法最终都通过 `HashMap` 主流程中的 Hook 改变顺序。以 JDK 8 的 `computeIfAbsent` 为例：已有非 `null` 值会直接调用 `afterNodeAccess`；计算得到非 `null` 值后，更新旧节点会调用访问 Hook，创建新节点则天然位于尾部；计算结果为 `null` 的返回路径不会移动节点。不能简单理解为只要调用过方法就一定刷新顺序。

## 插入顺序模式为什么不动

当 `accessOrder=false` 时，`afterNodeAccess` 的第一层条件失败。此时：

- `get` 只查找并返回 value。
- 再次 `put` 已存在的 key 只更新 value，不改变该 key 最初插入的位置。
- 删除后重新插入同一个 key 会创建新节点，因此它会出现在尾部。

这使插入顺序映射适合需要稳定输出顺序的配置、序列化和结果组装场景。

## 访问顺序对迭代器的影响

`LinkedHashIterator` 创建时保存：

```text
next = head
expectedModCount = modCount
```

每次 `nextNode` 都先比较 `modCount`。因此下面的单线程代码也可能快速失败：

```java
Iterator<String> iterator = map.keySet().iterator();
map.get("A"); // 若 A 不是 tail，则顺序被修改
iterator.next(); // 可能抛出 ConcurrentModificationException
```

这不是并发容器问题，而是迭代期间发生了结构性修改。`fail-fast` 只用于尽早暴露错误，不能作为并发正确性保证。

通过该迭代器自身的 `remove` 删除当前节点是允许的：它会走 `removeNode` 和 `afterNodeRemoval`，随后同步自己的 `expectedModCount`。

## 访问顺序意味着 get 可能是写操作

在普通 `HashMap` 中，`get` 通常只是读取。对 `accessOrder=true` 的 `LinkedHashMap`，命中非尾节点会写入多条指针并增加 `modCount`。

因此需要特别警惕：

- 多线程共享时，不能因为调用的是 `get` 就省略同步。
- 读锁不足以保护访问顺序映射的命中读取。
- `Collections.synchronizedMap` 能包装单次方法调用，但遍历期间仍需按其契约在包装对象上同步。
- 高频读取会不断改变链表局部，不能把它当作不可变快照。

下一页将利用 `head` 的“最久未访问”含义，通过 [`removeEldestEntry`](./lru.md) 构造一个有容量上限的 LRU 示例。
