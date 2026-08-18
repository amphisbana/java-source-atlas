# TreeMap 断点实验手册

调试入口：`io.github.javasourceatlas.jdk.collection.TreeMapDebugLab`

实验只通过公开 API 构造稳定场景，不反射 `root`、`color` 等私有字段。树形和颜色应在当前运行时关联的 JDK 源码断点中观察，自动化测试只锁定公开契约。

## 准备与运行

1. 用 IntelliJ IDEA 导入项目根 Maven 工程。
2. 将 Project SDK 指向带源码的 JDK；从 `TreeMap` 能跳转到 `TreeMap.java`。
3. 若 Step Into 默认跳过 JDK 类，直接在 JDK 源码方法上设置断点。
4. 以 Debug 模式运行 `TreeMapDebugLab.main()`。

命令行先验证案例：

```bash
mvn -pl labs/jdk-labs -Dtest=TreeMapBehaviorTest test

mvn -pl labs/jdk-labs exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.TreeMapDebugLab
```

## OpenJDK 8 推荐断点

| 顺序 | 类与方法 | 重点变量或链接 | 对应实验 |
| ---: | --- | --- | --- |
| 1 | `TreeMap.put(K,V)` | `t`、`parent`、`cmp`、`cpr` | 查找、覆盖与首次写入 |
| 2 | `TreeMap.compare(Object,Object)` | `comparator`、`k1`、`k2` | 两种排序路径 |
| 3 | `TreeMap.fixAfterInsertion(Entry)` | `x`、parent、grandparent、`y` | 插入 1 到 8 |
| 4 | `TreeMap.rotateLeft(Entry)` | `p`、`r`、`r.left`、`p.parent`、`root` | 递增插入旋转 |
| 5 | `TreeMap.rotateRight(Entry)` | `p`、`l`、`l.right`、`p.parent`、`root` | 镜像分支，可改为递减输入 |
| 6 | `TreeMap.getFloorEntry(K)` | `p`、`cmp`、`parent`、`ch` | 导航查询 |
| 7 | `TreeMap.getCeilingEntry(K)` | `p`、`cmp`、祖先回退 | 导航查询 |
| 8 | `TreeMap$NavigableSubMap.inRange(Object)` | `lo`、`hi`、包含标志 | 范围视图写入 |
| 9 | `TreeMap.deleteEntry(Entry)` | `p`、`s`、`replacement`、颜色 | 删除叶子与双孩子节点 |
| 10 | `TreeMap.fixAfterDeletion(Entry)` | `x`、`sib` 及双方孩子颜色 | 黑节点删除修复 |

较新 JDK 的私有方法组织可能变化。JDK 17/21 调试时，以当前 SDK 源码的实际签名为准，不要为了匹配本文而反射私有结构。

## 实验一：自然顺序、比较器与覆盖

运行 `observeOrderingAndReplacement()`：

1. 自然顺序 TreeMap 以 `3, 1, 2` 写入，遍历输出仍为 `1, 2, 3`。
2. 相同 key 2 再次写入，只替换 value 并返回旧值。
3. 反序 Comparator 让键迭代变成 `3, 2, 1`。

在 `put` 记录：

```text
是否使用 comparator：____________
新 key 每轮 cmp：_________________
覆盖时命中的 t.key：_____________
覆盖前后 size：___________________
覆盖前后 modCount：_______________
```

再运行 `observeComparatorIdentity()`。忽略大小写比较器会让 `"Java"` 和 `"JAVA"` 命中同一节点：value 更新，但 entry 中原 key 保持第一次写入的对象。

## 实验二：插入重着色与旋转

运行 `observeInsertionBalancing()`，案例依次写入整数 `1..8`。建议在每次命中 `fixAfterInsertion` 时先记录当前插入值，再只对插入 3、4、8 的调用深入单步：

| 插入值 | 预期关键分支 |
| ---: | --- |
| 3 | 父红、叔父黑、右右直线，对 1 左旋 |
| 4 | 父 3 与叔父 1 同红，只重着色并把焦点提升到 2 |
| 8 | 第一轮父 7 与叔父 5 同红；第二轮在祖先层对 2 左旋 |

插入 8 的两轮循环填写：

```text
第一轮：x = ___，parent = ___，uncle = ___，grandparent = ___
颜色变化：____________________________________________________
第二轮：x = ___，parent = ___，uncle = ___，grandparent = ___
旋转方法与轴节点：____________________________________________
最终 root.key = ___
```

在 `rotateLeft` 中特别观察旋转前 `r.left` 是否需要转交给 p，以及 p 原来是根时 `root` 在哪一步更新。

## 实验三：四组导航边界

运行 `observeNavigation()`。Map 固定包含 `10, 20, 30, 40`，分别查询存在的 20 和不存在的 25。

在 `getFloorEntry(25)` 中观察：搜索到 20 后会进入右侧还是直接返回；在 `getCeilingEntry(25)` 中观察如何得到 30。把查询值改为 5 或 50，确认越过最小/最大边界时返回 `null`。

建议分别回答：

```text
lower(20) = ___，floor(20) = ___
ceiling(20) = ___，higher(20) = ___
lower(25) = ___，floor(25) = ___
ceiling(25) = ___，higher(25) = ___
```

## 实验四：subMap 后备视图

运行 `observeRangeView()`。原 Map 包含 10、20、30、40，视图范围为 `[20, 40)`：

1. 通过视图写入 25，原 Map 立即出现 25。
2. 通过原 Map 写入 35，视图立即出现 35。
3. 尝试通过视图写入边界 40，触发 `IllegalArgumentException`。
4. 视图 clear 后，原 Map 仍保留范围外的 10 和 40。

在 `inRange`、`tooLow`、`tooHigh` 观察比较结果和两个 inclusive 标志。不要把“当前没有越界元素”误解为可以创建超出父视图的新嵌套范围；范围合法性由边界本身决定。

## 实验五：删除与后继替换

运行 `observeDeletion()`。案例先构造包含多个节点的 TreeMap，再依次删除双孩子节点 4 和其他节点，最后打印剩余有序键。

删除 4 时在 `deleteEntry` 观察：

1. p 同时拥有 left 和 right。
2. `successor(p)` 返回右子树的最小节点。
3. 后继的 key/value 被复制到 p。
4. p 变量随后指向后继，真正摘除的节点至多一个孩子。
5. 被摘除节点若为黑色，进入 `fixAfterDeletion`。

不同 JDK 构建可能因实现调整产生不同颜色快照，但公开结果必须满足：被删除 key 不再存在、其他映射仍按比较器顺序排列。

随后运行 `observeDeletionRepairBranches()`，把页面删除动画的三组输入逐一放到断点中：

| 插入顺序 | 删除 | `fixAfterDeletion` 重点观察 |
| --- | ---: | --- |
| `1,2,3,4,5,6,7,8` | 4 | 后继 5 被复制后成为幻影 x；兄弟 7 的远侄 8 为红 |
| `2,1,4,3,7,5,6` | 1 | 兄弟 4 为红，左旋后重新读取黑兄弟 3；随后全黑上推 |
| `3,2,4,5,7,1,6` | 4 | 兄弟 7 的近侄 6 为红；先右旋兄弟，再进入远侄红分支 |

每次进入循环都记录 `x`、parent、sib、近侄、远侄及颜色。近侄和远侄必须以 x 所在方向重新判断，不能固定理解为兄弟的 left/right。

## 实验六：迭代器快速失败

运行 `observeFailFastIterator()`。创建 key 迭代器后，通过原 Map 新增映射；下一次读取通常在 expectedModCount 检查处抛出 `ConcurrentModificationException`。

再把新增改成覆盖已有 key 的 value，会看到它不是结构性修改。这个对比说明 `modCount` 追踪的是节点数量/结构变化，不是所有 value 写入。

## 自动化测试边界

`TreeMapBehaviorTest` 验证：

- 自然顺序、反向比较器和覆盖行为；
- 比较结果为 0 时的键身份语义；
- lower/floor/ceiling/higher 的严格与包含边界；
- subMap 是后备视图并拒绝越界写入；
- 删除后顺序与内容正确；
- 结构性修改后的迭代器快速失败。

测试不会断言 root、颜色或旋转次数。这些属于 OpenJDK 8 学习入口，不是 Java 公开契约。

## 完成清单

完成调试后，应能不看源码解释：

```text
比较结果为 0 时，TreeMap 为什么不会新增节点：____________________
新节点为什么先设为红色：_______________________________________
叔父为红时为什么要把焦点上移：_________________________________
三角形为什么需要两次旋转：_____________________________________
删除双孩子节点时真正摘除的是哪个节点：_________________________
ceiling 搜索失败后为什么可能沿 parent 找到答案：_________________
subMap 越界 put 的异常：________________________________________
视图修改是否反映到原 Map：_____________________________________
```
