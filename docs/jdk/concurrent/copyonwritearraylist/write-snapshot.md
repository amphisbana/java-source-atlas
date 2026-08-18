# 写时复制与快照发布

## 动画：旧迭代器与新读线程看到不同数组

下面的动画把同一次 `add("D")` 拆成锁定、复制、修改和发布。关键不是元素逐个“搬过去”的视觉效果，而是旧数组始终没有被写线程修改。

<CopyOnWriteSnapshotAnimation />

## 一次写入的变量快照

初始列表为 `[A, B, C]`，读线程 R 已创建迭代器，写线程 W 准备加入 D：

| 阶段 | `array` 当前指向 | R 的 `snapshot` | W 的 `elements` | W 的 `newElements` |
| --- | --- | --- | --- | --- |
| 写入前 | `array#1 [A,B,C]` | `array#1` | 未读取 | 未创建 |
| 获取锁后 | `array#1` | `array#1` | `array#1` | 未创建 |
| 复制后 | `array#1` | `array#1` | `array#1` | `array#2 [A,B,C,_]` |
| 填入 D | `array#1` | `array#1` | `array#1` | `array#2 [A,B,C,D]` |
| 发布后 | `array#2` | `array#1` | `array#1` | `array#2` |

发布后新调用的 `get()` 会读取 `array#2`，旧迭代器仍遍历 `array#1`。两者都合法，没有任何一个线程需要把旧数组“升级”到新数组。

## set 为什么即使值相同也会发布

JDK 8 的 `set(index, element)` 在持锁后读取旧值。新值与旧值不是同一个对象引用时，它复制数组、修改指定位置并发布新数组。若两个引用相同，源码仍调用 `setArray(elements)`，目的是保持与 `volatile` 写相关的内存语义。

这里比较的是引用身份，不是业务上的 `equals`。不要根据这个内部优化推导 `set` 是否会触发业务回调；容器没有这种回调契约。

## 中间插入和删除

`add(index, element)` 根据插入位置选择复制方式：

```text
前半段 [0, index)       -> 复制到新数组相同位置
新元素                  -> 写入 newElements[index]
后半段 [index, len)     -> 复制到 index + 1
```

`remove(index)` 则把删除点之后的区间左移到新数组。如果删除的是最后一个元素，可以直接把旧数组复制为 `len - 1`；中间删除需要两段复制。

无论分支如何，旧数组都不会原位变短或清空尾部。它可能仍被其他读线程使用。

## addIfAbsent 为什么要检查两次

第一次检查在锁外完成，快速处理元素已存在的常见情况：

```text
snapshot = getArray()
indexOf(element, snapshot) >= 0 ? false : addIfAbsent(element, snapshot)
```

当锁外未找到元素时，线程进入持锁的私有方法。等待锁期间其他写线程可能已经发布新数组，所以不能直接在旧 `snapshot` 后追加。

JDK 8 会取得 `current = getArray()` 并处理两种情况：

- `snapshot == current`：锁外结果仍基于当前数组，可以直接追加。
- `snapshot != current`：先比较两份数组共享的前缀，再搜索当前数组剩余区间；只有当前版本仍不存在该元素时才追加。

这是“乐观锁外检查 + 锁内校验”的模式。公开保证是单次 `addIfAbsent` 不会在元素已存在时重复添加，不应依赖内部前缀比较的具体组织方式。

## 批量操作只发布一次

`removeAll`、`retainAll`、`removeIf` 等批量修改会在写锁内扫描当前数组，把需要保留的引用写入临时数组，最后一次性发布压缩后的结果。它们不是对每个元素循环调用公开 `remove`，否则一次批量删除会产生大量中间快照。

用户提供的 `Predicate` 或集合查询可能在持有写锁时执行：

- 回调不应阻塞很久。
- 回调不应依赖另一个线程先修改同一个列表才能继续。
- 回调抛出异常时，以当前 JDK 版本对该方法的实现和公开异常契约为准。

## 浅复制边界

写时复制只复制数组和元素引用，不复制元素对象：

```text
array#1 [ user#7, config#9 ]
array#2 [ user#7, config#9, rule#12 ]
```

如果 `user#7` 自身可变，两个快照仍指向同一个对象。修改该对象的字段不会触发新数组发布，也不会自动获得 `CopyOnWriteArrayList` 的线程安全保护。

因此最稳妥的元素通常是不可变对象，或由其他同步机制管理内部状态的对象。

## happens-before 如何落到代码上

从 Java 内存模型看：

1. 写线程先完成 `newElements` 的普通写入。
2. `setArray(newElements)` 对 `volatile array` 执行写。
3. 读线程随后通过 `getArray()` 对同一字段执行读并观察到新引用。
4. 该读线程也能观察到 volatile 写之前对新数组完成的元素写入。

容器文档进一步给出内存一致性效果：把对象放入列表之前的动作，先行发生于另一个线程从列表访问或移除该元素之后的动作。

## 调试重点

在 JDK 8 中建议关注：

- `add(E)`：`elements`、`len`、`newElements`。
- `setArray(Object[])`：比较旧 `array` 和参数 `a` 的对象身份。
- `addIfAbsent(E,Object[])`：`snapshot`、`current`、`common`。
- `remove(int)`：`numMoved` 和两段复制分支。

不要依靠反射在业务代码中读取私有数组；数组身份只应在 IDE 源码断点里观察。

