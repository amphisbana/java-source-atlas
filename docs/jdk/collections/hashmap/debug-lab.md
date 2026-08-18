# 断点实验手册

本实验不通过反射读取 `HashMap.table`，而是让程序稳定触发目标分支，再直接进入本机 JDK 源码观察内部变量。这样看到的是当前运行时的真实实现。

## 准备环境

1. 用 IntelliJ IDEA 打开项目根目录，导入 Maven 项目。
2. 确认 Project SDK 已关联源码。能从 `HashMap` 跳转到 `.java` 文件即可。
3. 打开 `HashMapDebugLab`，以 Debug 模式运行 `main`。
4. 如果 Step Into 跳过 JDK 类，直接在 JDK 源码方法上设置断点。

命令行先确认实验能够运行：

```bash
mvn -pl labs/jdk-labs test
mvn -pl labs/jdk-labs exec:java
```

## 推荐断点

| 顺序 | 类与方法 | 观察目标 |
| ---: | --- | --- |
| 1 | `HashMap.hash(Object)` | 原始 `hashCode` 如何混合高 16 位 |
| 2 | `HashMap.putVal(...)` | 桶定位、空桶、覆盖、链表和树分支 |
| 3 | `HashMap.resize()` | 首次分配及扩容的容量、阈值变化 |
| 4 | `HashMap.treeifyBin(...)` | 容量不足时扩容，容量足够时树化 |
| 5 | `HashMap.TreeNode.treeify(...)` | 链表节点如何组织成红黑树 |
| 6 | `HashMap.getNode(...)` | 桶首、链表和树三种查询路径 |

JDK 8 与 JDK 17/21 的私有方法参数可能不同，以当前 SDK 源码显示的方法签名为准。

## 实验一：首次写入与覆盖

运行 `observeBasicPutAndReplace()`，在 `putVal` 中观察：

1. 第一次写入时 `table` 为空，进入 `resize()`。
2. 返回后 `n` 为 16，默认 `threshold` 为 12。
3. 第二次使用相同键写入，找到已有节点 `e`。
4. `oldValue` 被返回，`size` 保持 1。

建议观察变量：`tab`、`n`、`i`、`p`、`e`、`oldValue`、`size`、`modCount`。

## 实验二：扩容和拆链

运行 `observeResize()`。案例使用初始容量 4，并写入多个整数键。

在 `resize()` 里分别记录：

```text
oldCap / oldThr
newCap / newThr
```

迁移链表时观察 `e.hash & oldCap`：结果为 0 的节点进入 `loHead`，否则进入 `hiHead`。继续运行后，测试会验证所有旧键仍能读取。

::: warning 不要依赖反射输出容量
JDK 9 起模块系统默认限制对 `java.util` 私有字段的深反射。教学实验直接在源码断点观察，不要求添加 `--add-opens`，也不会把内部字段访问写进示例业务代码。
:::

## 实验三：哈希碰撞

`CollisionKey` 可以让不同键返回同一个 `hashCode`。运行 `observeCollision()`，在 `putVal` 中观察：

- 两个键的扰动哈希相同；
- `equals` 返回 false，因此不是覆盖；
- 第二个节点通过 `next` 追加到同一个桶；
- 两个键仍然可以分别查询。

这证明 `hashCode` 决定候选桶，`equals` 才最终决定键是否相同。

## 实验四：链表树化

运行 `observeTreeification()`。案例把初始容量设为 64，并插入 9 个相同哈希的不同键。

在 `treeifyBin` 中观察：

1. `tab.length` 已达到 `MIN_TREEIFY_CAPACITY`。
2. 普通 `Node` 被逐个替换或包装成 `TreeNode`。
3. `TreeNode.treeify` 通过比较、旋转和变色建立红黑树。
4. `moveRootToFront` 把根节点移动到桶首。

把初始容量改成 16 再运行一次，会看到相同碰撞优先触发扩容，而不是立即树化。

## 实验五：null 键

运行 `observeNullKey()`，在 `hash` 和 `putVal` 中观察：

- `null` 键的扰动哈希为 0；
- 当前实现将其定位到第 0 个桶；
- 再次写入 `null` 键会覆盖原值，`size` 不增加。

## 验证清单

完成调试后，应能够填写：

```text
首次分配：oldCap = ___，newCap = ___，newThr = ___
默认扩容：oldCap = ___，newCap = ___
桶下标：index = __________________
低位链判断：______________________
高位链新下标：____________________
树化所需最小数组容量：____________
覆盖写入是否增加 size：____________
```

自动化测试只验证公开可观察行为；容量、树形等内部实现通过断点学习，不写成脆弱断言。

