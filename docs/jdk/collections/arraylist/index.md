# ArrayList：结构与 add 主流程

`ArrayList` 是基于连续对象数组实现的可变长列表。它支持随机访问、允许 `null` 和重复元素、不保证线程安全。

本专题以 OpenJDK 8 为主基线，并单独说明 JDK 17/21 在扩容辅助方法和局部代码组织上的变化。

## 类型与核心字段

```text
AbstractCollection<E>
  └─ AbstractList<E>             提供 modCount
       └─ ArrayList<E>
            implements List<E>, RandomAccess, Cloneable, Serializable
```

| 字段 | 含义 |
| --- | --- |
| `elementData` | 保存元素引用的 `Object[]`，容量等于数组长度 |
| `size` | 当前有效元素数量 |
| `modCount` | 继承自 `AbstractList` 的结构性修改计数 |

`size` 与容量不是同一个概念。`size()` 返回有效元素数量，内部数组可能留有空余位置。

## 三种空数组状态

JDK 8 使用两个不同的共享空数组常量：

- `DEFAULTCAPACITY_EMPTY_ELEMENTDATA`：无参构造产生，首次添加至少分配默认容量 10。
- `EMPTY_ELEMENTDATA`：显式容量 0 或空集合构造产生，首次添加只需要满足本次最小容量。

因此下面两种写法在第一次 `add` 后的内部容量可能不同：

```java
new ArrayList<>();
new ArrayList<>(0);
```

这是内部内存策略，不影响 `List` 的公开行为，也不应该通过业务代码依赖。

## add(E) 调用链

JDK 8 的主路径是：

```text
add(element)
  └─ ensureCapacityInternal(size + 1)
       └─ calculateCapacity(...)
       └─ ensureExplicitCapacity(minCapacity)
            └─ grow(minCapacity)          容量不足时
  └─ elementData[size] = element
  └─ size++
```

### 第一步：计算最小容量

本次新增至少需要 `size + 1` 个位置。无参构造的共享空数组会把这个值与默认容量 10 比较，取较大值；其他数组直接使用传入的最小容量。

### 第二步：判断是否扩容

源码使用差值判断：

```text
minCapacity - elementData.length > 0
```

这种写法与 JDK 集合中的溢出边界处理配合使用。只有当前数组确实装不下时才进入 `grow`。

### 第三步：写入并增加 size

扩容检查完成后，元素写入 `elementData[size]`，随后 `size` 自增。尾部添加不需要搬移已有元素。

## get 与 set 为什么快

`get(index)` 完成边界检查后直接返回 `elementData[index]`，时间复杂度为 O(1)。`set(index, value)` 替换已有位置并返回旧值，也不改变 `size`，因此不是结构性修改。

`ArrayList` 实现 `RandomAccess` 是一个标记：通用算法可以据此选择按下标循环，而不是迭代器顺序移动。

## 复杂度地图

| 操作 | 典型复杂度 | 原因 |
| --- | --- | --- |
| `get` / `set` | O(1) | 数组下标访问 |
| 尾部 `add` | 均摊 O(1) | 大多数写入不扩容 |
| 指定位置 `add` | O(n) | 后续元素整体右移 |
| 指定位置 `remove` | O(n) | 后续元素整体左移 |
| `contains` / `indexOf` | O(n) | 从头顺序比较 |
| 完整遍历 | O(n) | 只访问有效元素区间 |

## 公开契约与实现细节

| 可以依赖 | 不应该依赖 |
| --- | --- |
| 保持列表顺序和重复元素 | 默认首次分配容量一定为 10 |
| 允许 `null` | 每次扩容一定精确为 1.5 倍 |
| 随机访问高效 | `elementData` 字段存在且可反射访问 |
| 非线程安全 | `ConcurrentModificationException` 一定能发现所有并发修改 |

下一步阅读 [扩容、删除与视图](./mutation.md)，观察数组增长和元素搬移。

