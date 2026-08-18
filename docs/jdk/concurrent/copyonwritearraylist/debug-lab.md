# CopyOnWriteArrayList 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/concurrent/CopyOnWriteArrayListDebugLab.java
```

运行：

```bash
mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.CopyOnWriteArrayListDebugLab
```

## 实验一：旧迭代器保持快照

场景：列表初始为 `[A,B,C]`，先创建迭代器，再加入 D。

建议断点：

1. `CopyOnWriteArrayList.iterator()`。
2. `COWIterator` 构造方法。
3. `CopyOnWriteArrayList.add(E)`。
4. `setArray(Object[])`。
5. `COWIterator.next()`。

观察变量：

| 位置 | 变量 | 预期 |
| --- | --- | --- |
| 迭代器构造 | `elements` / `snapshot` | 指向长度 3 的旧数组 |
| add 进入锁后 | `elements` | 与旧快照相同 |
| 复制后 | `newElements` | 新对象，长度 4 |
| setArray 后 | 列表 `array` | 指向新数组 |
| iterator.next | `snapshot` | 仍指向旧数组，只返回 A/B/C |

在调试器中比较对象身份，不要只比较数组内容。

## 实验二：addIfAbsent 的锁内复查

单线程案例可以验证已存在时返回 `false`。要观察 `snapshot != current` 分支，可以让两个线程同时尝试加入同一个新元素，并把断点放在私有 `addIfAbsent(E,Object[])` 获取锁之后。

重点变量：

- `snapshot`：锁外查找使用的数组。
- `current`：获得锁后的最新数组。
- `common`：两份数组可直接比较的公共长度。
- `len`：当前数组长度，而不是旧快照长度。

线程调度具有不确定性。如果一次没有进入该分支，应重新运行或使用调试器控制线程恢复顺序，不要在生产代码中加入休眠来“固定”实现细节。

自动测试会让 8 个线程同时调用 `addIfAbsent`，并断言最终只有一个元素、恰好一个调用返回 true。这可以验证公开原子语义，但不能保证某一次运行必然命中私有的 `snapshot != current` 分支；该分支仍应通过断点控制线程顺序观察。

## 实验三：迭代器禁止修改

在 `COWIterator.remove()` 上断点，确认它不检查当前列表状态，而是直接抛出 `UnsupportedOperationException`。这是一项公开行为，不是并发竞争导致的偶发现象。

## 实验四：SubList 检查数组身份

场景：

```text
parent = [A,B,C]
view = parent.subList(0, 2)
parent.add(D)
view.size()
```

建议断点：

- `COWSubList` 构造方法，记录 `expectedArray`。
- `COWSubList.checkForComodification()`。

父列表加入 D 后，`l.getArray()` 指向新数组，而 `expectedArray` 仍是旧数组，视图操作会抛出 `ConcurrentModificationException`。

## JDK 版本提示

JDK 8 可在 `ReentrantLock.lock()` 之后观察写入。JDK 17/21 使用 `synchronized(lock)`，没有相同的私有锁调用链。跨版本通用断点应选择公开修改方法、数组复制点和新数组赋值点。

## 实验完成标准

- 能指出列表当前数组与迭代器快照不是同一个对象。
- 能解释 `volatile` 发布和写锁分别解决的问题。
- 能区分 COWIterator 的稳定快照与 COWSubList 的父列表视图。
- 能说明 `addIfAbsent` 为什么在获得锁后必须重新校验。
- 能根据读写比例和数组规模判断该容器是否合适。
