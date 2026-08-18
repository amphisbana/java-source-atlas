# 迭代器、SubList 与复合操作

## COWIterator 保存的是数组引用

创建迭代器时，JDK 8 执行的核心动作等价于：

```text
new COWIterator(getArray(), 0)
```

迭代器保存两个状态：

| 字段 | 含义 |
| --- | --- |
| `snapshot` | 创建迭代器时的数组引用，之后不会替换 |
| `cursor` | 下一次 `next()` 要读取的下标 |

`next()` 直接返回 `snapshot[cursor++]`。列表后来发布多少份新数组，都不改变这个 `snapshot`。

## 为什么不会抛 ConcurrentModificationException

`ArrayList.Itr` 使用 `expectedModCount` 尽力发现结构性修改；`COWIterator` 不需要版本计数，因为它读取的旧数组不会再被修改。并发写入不会破坏其遍历结构，所以也没有快速失败的必要。

这并不代表它能看到最新数据：

```text
iterator = list.iterator()  // 快照 [A,B]
list.add(C)                 // 当前列表 [A,B,C]
iterator 仍只返回 A、B
```

“遍历稳定”和“数据实时”是两个不同目标。

## 迭代器为何禁止 remove/set/add

如果迭代器允许删除，应该修改创建时的旧快照还是当前列表？当前列表可能已经发布了多个新版本，旧下标也可能不再代表同一元素。

为避免这种模糊语义，`COWIterator.remove()`、`set()` 和 `add()` 都固定抛出 `UnsupportedOperationException`。需要修改时应调用列表自身的方法，并接受现有迭代器继续读取旧快照。

## Spliterator 的稳定特征

JDK 8 的 `spliterator()` 同样基于创建时的数组，并报告：

- `IMMUTABLE`：遍历期间这份数组快照不会被结构性修改。
- `ORDERED`：保持列表顺序。
- 数组工厂还能推导 `SIZED` 和 `SUBSIZED`。

并行流可以拆分这份固定数组，但流执行期间新增的元素不保证进入本次流水线。

## SubList 不是独立快照

`subList(from, to)` 返回 `COWSubList` 视图，内部保存：

| 字段 | 作用 |
| --- | --- |
| `l` | 原 `CopyOnWriteArrayList` |
| `offset` | 视图起始偏移 |
| `size` | 当前视图长度 |
| `expectedArray` | 创建或上次通过视图修改后预期的底层数组 |

每个核心操作都会比较 `l.getArray() != expectedArray`。如果父列表绕过该视图发布了新数组，视图不能再确定原范围对应什么内容，于是通常抛出 `ConcurrentModificationException`。

这与普通迭代器形成明显对比：

| 对象 | 父列表修改后行为 |
| --- | --- |
| 创建好的 `COWIterator` | 继续读取旧数组，不抛快速失败异常 |
| `COWSubList` 视图 | 后续操作检查数组身份，通常抛 `ConcurrentModificationException` |

如果需要独立且长期稳定的子列表，应显式复制：

```java
List<E> copy = new ArrayList<>(list.subList(from, to));
```

## size 与 get 组合不是原子快照

下面的代码调用了两次独立读取：

```java
int last = list.size() - 1;
E value = list.get(last);
```

两次调用之间另一个线程可能删除元素，导致 `get(last)` 越界。单个读取方法是线程安全的，不代表多个方法组合自动成为一个原子操作。

可选做法取决于业务目标：

- 需要稳定遍历：先创建迭代器或调用 `toArray()` 获取副本。
- 需要“存在才添加”：使用 `addIfAbsent`，不要手写 `contains + add`。
- 需要围绕多个读取和写入建立事务语义：使用更合适的数据结构或在容器外建立一致的同步协议。

## toArray 与迭代器的区别

迭代器共享当时的内部数组，不额外复制；`toArray()` 则返回一份新数组，调用者可自由修改该数组。两者都能提供稳定内容，但空间成本不同。

不要通过“迭代器不复制数组”推导元素对象不可变。迭代器和内部快照仍然只是引用的集合。

## CopyOnWriteArraySet 的关系

`CopyOnWriteArraySet` 以 `CopyOnWriteArrayList` 作为内部存储，并通过 `addIfAbsent` 提供集合去重语义。因此它继承同样的性能特征：读和遍历适合高频使用，添加和删除需要线性搜索与数组复制。

## 常见误区

| 误区 | 正确理解 |
| --- | --- |
| 线程安全等于所有组合操作原子 | 单个公开方法有自己的并发契约，跨方法组合需另行设计 |
| 迭代器没有 CME 就能看到新增值 | 迭代器固定读取创建时快照 |
| 写时复制会深拷贝元素 | 只复制数组与元素引用 |
| `subList` 是独立数组 | 它是带 `expectedArray` 校验的父列表视图 |
| 读不加锁，所以适合任意规模 | 大数组的每次写入仍有 O(n) 复制和内存峰值 |

## 版本边界

JDK 17/21 仍保持快照迭代器和 SubList 数组身份检查的核心语义，但内部锁从 `ReentrantLock` 改为对象监视器，批量删除使用位图式标记等不同实现。阅读较新源码时应重新确认私有字段和辅助方法，不能照搬 JDK 8 的局部变量名称。

