# ArrayList：扩容、删除与视图

## 动画：同一数组连续经历扩容、插入和删除

这个演示从容量 4 且已经装满的数组开始。自动播放时，先为尾部新增 E 扩到容量 6，再在下标 1 插入 X，最后删除下标 2 的 B。每一步都显示 `size`、容量以及当前受影响区间。

<ArrayListMutationAnimation />

### 把动画换算成真实 arraycopy 参数

指定位置插入 X 时，源码调用的参数等价于：

```text
src      = elementData
srcPos   = index = 1
dest     = elementData
destPos  = index + 1 = 2
length   = size - index = 4
```

源数组和目标数组是同一个对象，区间还发生重叠。`System.arraycopy` 会按安全方向完成复制，因此不会因为先覆盖下标 2 而丢失原来的 C。

删除下标 2 的 B 时，移动数量来自：

```text
numMoved = size - index - 1
         = 6 - 2 - 1
         = 3
```

这三个引用是 C、D、E。移动结束后旧尾部仍有 E 的重复引用，必须通过 `elementData[--size] = null` 释放。

### 三种修改对字段的影响

| 操作 | `elementData` | `size` | `modCount` |
| --- | --- | --- | --- |
| `set(index, value)` | 原位覆盖 | 不变 | 不变 |
| `add` / `remove` | 可能扩容或搬移 | 改变 | 增加 |
| `trimToSize` | 复制为更短数组 | 不变 | 增加 |

因此迭代器关注的是结构性修改，而不是每一次 value 替换。动画中的扩容只是一次 `add` 的内部步骤，不会让本次 `add` 被重复计数。

::: tip 性能判断应看搬移长度
尾部 `add` 的常见路径不搬移已有元素；头部插入或删除需要移动接近 `size` 个引用。二者都叫 `add/remove`，实际成本可能相差一个数量级。
:::

## grow 的 1.5 倍策略

JDK 8 先计算候选容量：

```text
newCapacity = oldCapacity + (oldCapacity >> 1)
```

右移一位约等于除以 2，所以候选值约为旧容量的 1.5 倍。随后处理两个边界：

1. 候选容量仍小于本次 `minCapacity`，直接使用 `minCapacity`。
2. 候选容量超过 `MAX_ARRAY_SIZE`，进入 `hugeCapacity` 处理整数溢出和 VM 数组上限。

最终通过 `Arrays.copyOf` 创建新数组并复制有效引用。扩容不会创建元素对象的新副本。

::: tip 1.5 倍不是 API 契约
不同 JDK 版本可以更换容量计算辅助方法。业务代码只应通过初始容量减少已知扩容，不要预测某次写入后的精确内部长度。
:::

## 为什么不会自动缩容

删除元素只减少 `size` 并清空尾部引用，不会自动缩短 `elementData`。这样可以避免列表大小波动时不断分配和复制数组。

确定列表长期缩小后，可以显式调用 `trimToSize()`。它本身也需要复制数组，因此不适合每次删除后调用。

## 指定位置插入

`add(index, element)` 先检查 `0 <= index <= size`，确保容量足够，再调用 `System.arraycopy`：

```text
原数组: [A, B, C, _, _]
在 1 插入 X
搬移后: [A, B, B, C, _]
写入后: [A, X, B, C, _]
```

搬移长度为 `size - index`。越靠近列表头部，通常需要复制的引用越多。

## 删除为什么要清空尾部

`remove(index)` 把后续元素整体左移后执行：

```text
elementData[--size] = null
```

最后一个旧引用已经不属于有效列表。如果不设为 `null`，数组仍会持有对象引用，垃圾收集器无法回收仅由该位置引用的对象。

## remove 的重载陷阱

对于 `ArrayList<Integer>`：

```java
list.remove(1);                 // 调用 remove(int)，删除下标 1
list.remove(Integer.valueOf(1)); // 调用 remove(Object)，删除值 1
```

`remove(Object)` 从头查找第一个相等元素，找到后进入 `fastRemove`。这个私有方法跳过重复的边界检查，也不需要返回被删元素。

## clear 与批量删除

`clear()` 会把有效区间中的引用逐个设为 `null`，再把 `size` 设为 0。内部数组仍保留，可以被后续写入复用。

`removeAll` 和 `retainAll` 的批量处理会把保留元素压缩到数组前部，并在 `finally` 路径处理异常情况下尚未扫描的元素。阅读这里应关注“异常后仍维持列表结构一致”，而不只是正常循环。

## subList 是视图，不是副本

`subList(from, to)` 返回原列表指定范围的视图：

- 通过子列表 `set`、`add`、`remove` 会反映到父列表。
- 子列表保存父列表、偏移量、自己的 `size` 和预期 `modCount`。
- 在子列表之外直接结构性修改父列表后，继续使用旧子列表的语义未定义，通常会快速失败。

需要独立副本时应显式创建：

```java
List<E> copy = new ArrayList<>(source.subList(from, to));
```

## toArray 的类型边界

无参 `toArray()` 返回新的 `Object[]`。`toArray(T[] target)` 根据目标数组长度选择复用还是创建同运行时类型的新数组；目标数组较大时，会在有效元素后的第一个位置写入 `null`。

不要把无参 `toArray()` 强制转换为具体元素数组，这会在运行时产生 `ClassCastException`。

