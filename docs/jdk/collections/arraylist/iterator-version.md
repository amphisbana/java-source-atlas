# ArrayList：迭代器与版本差异

## Itr 的三个游标

JDK 8 的内部迭代器 `Itr` 维护：

| 字段 | 含义 |
| --- | --- |
| `cursor` | 下一次 `next()` 将返回的下标 |
| `lastRet` | 上一次返回的下标，没有则为 -1 |
| `expectedModCount` | 创建迭代器时记录的结构性修改计数 |

`next()` 的核心顺序是：检查修改计数、检查是否还有元素、读取数组、推进游标。

## fail-fast 如何发生

迭代期间通过列表本身执行结构性修改，会使 `modCount` 与 `expectedModCount` 不一致。下一次迭代器检查时抛出 `ConcurrentModificationException`。

```java
Iterator<String> iterator = list.iterator();
list.add("new");
iterator.next(); // 通常抛出 ConcurrentModificationException
```

使用 `iterator.remove()` 是支持的：它调用外部列表删除后，会重新同步 `expectedModCount`，并调整 `cursor`。

### fail-fast 不是并发保证

- 它用于尽早暴露错误用法，不提供线程同步。
- 检查是尽力而为，不保证发现所有数据竞争。
- `set` 替换元素不是结构性修改，通常不会触发。
- 多线程共享修改仍需外部同步，或改用适合场景的并发集合。

## ListItr 增加了什么

`ListItr` 继承 `Itr`，增加向前移动、获取前后下标、替换当前元素和在游标位置插入的能力。它仍然依赖同一套 `expectedModCount` 检查。

## Spliterator 的延迟绑定

ArrayList 的 `Spliterator` 尽量延迟确定遍历边界和预期修改计数，使得创建后、首次遍历前的部分修改仍可被纳入。分割时按下标中点拆区间，适合数组的并行遍历。

并行流能否更快取决于数据量、操作成本和线程调度，不能只因为 ArrayList 支持高效分割就默认使用并行流。

## JDK 8 到 JDK 17/21

稳定设计没有改变：

- 连续对象数组保存元素；
- `size` 区分有效区间和容量；
- 随机访问为 O(1)；
- 中间插入删除依赖数组搬移；
- 迭代器通过 `modCount` 快速失败。

局部实现发生过调整：较新 JDK 把扩容边界计算交给 `ArraysSupport.newLength` 等内部辅助方法，并重新组织 `add` 与 `grow` 的代码以减少常见路径开销。`ArrayList` 的私有方法名和精确扩容代码不是兼容性接口。

## 选型边界

| 场景 | 更适合的选择 |
| --- | --- |
| 读多写少、需要下标访问 | `ArrayList` |
| 频繁从头部增删 | `ArrayDeque`，而不是把 ArrayList 当队列 |
| 多线程读、极少修改且可接受复制 | `CopyOnWriteArrayList` |
| 需要并发复合操作 | 根据语义选并发结构并明确同步边界 |

