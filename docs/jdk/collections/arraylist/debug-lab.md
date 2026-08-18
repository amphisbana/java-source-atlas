# ArrayList 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.collection.ArrayListDebugLab`

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.ArrayListDebugLab
```

## 推荐断点

| 类与方法 | 观察变量 | 对应场景 |
| --- | --- | --- |
| `ArrayList.add(E)` | `size`、`elementData.length` | 尾部写入 |
| `ensureCapacityInternal` | `minCapacity` | 首次容量计算 |
| `grow` | `oldCapacity`、`newCapacity` | 1.5 倍扩容 |
| `add(int,E)` | `index`、`size` | 中间插入和数组右移 |
| `remove(int)` | `numMoved`、尾部槽位 | 左移并释放引用 |
| `ArrayList.Itr.checkForComodification` | 两个修改计数 | 快速失败 |

较新 JDK 的私有方法可能改名或合并，以当前 SDK 源码为准。

## 实验一：首次分配与扩容

运行 `observeGrowth()`。无参列表连续加入 11 个元素：第一次写入分配默认容量，写入第 11 个元素时触发下一次增长。

断点中填写：

```text
第一次 grow：oldCapacity = ___，minCapacity = ___，newCapacity = ___
第二次 grow：oldCapacity = ___，minCapacity = ___，newCapacity = ___
```

## 实验二：中间插入和删除

运行 `observeMiddleMutation()`。观察 `System.arraycopy` 的源下标、目标下标和搬移长度，再确认删除后尾部数组槽位被清空。

## 实验三：remove 重载

运行 `observeRemoveOverload()`。比较 `remove(1)` 与 `remove(Integer.valueOf(1))` 的调用入口和最终列表。

## 实验四：SubList 视图

运行 `observeSubListView()`。通过子列表替换元素后，父列表立即变化；这说明返回对象共享底层数据，而不是复制集合。

## 实验五：迭代器快速失败

运行 `observeFailFast()`。创建迭代器后直接修改原列表，下一次 `next()` 进入修改计数检查并抛出 `ConcurrentModificationException`。

自动化测试只断言公开行为，不通过反射断言数组容量。

