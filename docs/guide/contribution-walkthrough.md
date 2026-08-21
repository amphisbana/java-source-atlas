# 从一个问题到可合并专题：HashMap 贡献全流程

这篇示例不再抽象描述“应该补文档、Lab 和测试”，而是用仓库现有的 HashMap 扩容证据演示一次完整贡献。目标问题是：**HashMap 扩容后，旧映射为什么还能被准确读取？**

完成后的证据链应当可以从四个方向互相追溯：

```text
源码结论 resize 不丢映射
  -> 固定版本入口 HashMap.resize()
  -> HashMapDebugLab#observeResize
  -> HashMapBehaviorTest#shouldKeepMappingsAfterResize
  -> 推荐断点 resize()#resize-boundary
```

## 1. 先提出可验证的问题

先搜索现有专题与 Issue。没有重复内容时，通过[新专题建议](https://github.com/amphisbana/java-source-atlas/issues/new?template=topic_request.yml)说明：

- 真实问题：扩容时桶位置改变，旧映射为什么没有丢失？
- 目标版本：OpenJDK 8u，固定 tag `jdk8u412-b08`。
- 公开行为：连续写入后 `size` 正确，每个 key 都能读回原 value。
- 源码入口：`HashMap.putVal(...) -> HashMap.resize()`。
- 实验设想：从很小的初始容量开始连续写入，在 `resize()` 停住。

问题要能被实验判定，而不是“讲一下 HashMap 源码”这类没有完成边界的宽泛选题。

## 2. 固定源码版本和永久入口

上游源码必须指向固定 tag：[OpenJDK 8u HashMap.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/HashMap.java)。同时确认 `source-index/baselines.json` 已登记仓库、tag 与解析后的 commit。

在 `source-index/jdk8/hashmap.json` 的 `entryPoints` 中登记方法、讲解地址和阅读目的：

```json
{
  "method": "resize()",
  "document": "/jdk/collections/hashmap/resize",
  "purpose": "首次分配、容量扩张和节点迁移"
}
```

不要把 `main`、`master` 或本机 JDK 当前实现写成永久依据。跨版本结论应另外进入版本对比，不覆盖当前专题的固定基线。

## 3. 写能稳定触发分支的 Debug Lab

在 `labs/jdk-labs/src/main/java/io/github/javasourceatlas/jdk/collection/HashMapDebugLab.java` 增加一个场景方法：

```java
/**
 * 使用较小初始容量连续写入，触发 resize 并验证旧映射仍然可读取。
 */
static void observeResize() {
    Map<Integer, String> map = new HashMap<>(4);
    for (int index = 0; index < 8; index++) {
        map.put(index, "value-" + index);
    }

    System.out.printf("写入后 size=%d，首尾值=%s/%s%n",
            map.size(), map.get(0), map.get(7));
}
```

Lab 负责稳定触发源码路径和提供观察现场。不要用反射读取 `table` 并把私有布局当作测试契约；容量、阈值和拆链节点应该在固定版本源码断点中观察。

## 4. 用 JUnit 锁住公开结果

在 `labs/jdk-labs/src/test/java/io/github/javasourceatlas/jdk/collection/HashMapBehaviorTest.java` 增加对应测试：

```java
/**
 * 验证连续扩容不会丢失此前写入的映射。
 */
@Test
void shouldKeepMappingsAfterResize() {
    Map<Integer, String> map = new HashMap<>(2);

    for (int index = 0; index < 1_000; index++) {
        map.put(index, "value-" + index);
    }

    assertEquals(1_000, map.size());
    for (int index = 0; index < 1_000; index++) {
        assertEquals("value-" + index, map.get(index));
    }
}
```

运行单个证据场景：

```bash
mvn --batch-mode -pl labs/jdk-labs -Dtest=HashMapBehaviorTest#shouldKeepMappingsAfterResize test
```

并发专题要用闩锁、屏障或可观察状态固定时序，不要依赖 `Thread.sleep` 猜测调度窗口。

## 5. 建立结构化 evidence

每个专题至少提供三条证据，其中必须有 `main`，并覆盖 `boundary`、`failure` 或 `cleanup` 中至少一种。HashMap 的扩容证据如下：

```json
{
  "id": "resize-boundary",
  "kind": "boundary",
  "claim": "HashMap 扩容迁移后所有旧映射仍可按原 key 读取，容量变化不改变 Map 契约。",
  "entryPoint": "resize()",
  "document": "/jdk/collections/hashmap/resize",
  "labMethod": "observeResize",
  "testClass": "io.github.javasourceatlas.jdk.collection.HashMapBehaviorTest",
  "testMethod": "shouldKeepMappingsAfterResize",
  "expectedOutcome": "连续写入 1000 个映射后 size 正确，逐个 key 都能读回对应 value。"
}
```

`claim` 是要证明的结论，`expectedOutcome` 是运行测试后的判定标准。二者不能只换一种说法重复源码实现。

## 6. 把推荐断点绑定到证据

在同一专题的 `breakpoints` 中引用证据编号：

```json
{
  "method": "resize()",
  "scenario": "观察首次分配与扩容拆链",
  "variables": ["oldCap", "oldThr", "newCap", "newThr", "loHead", "hiHead"],
  "evidenceId": "resize-boundary"
}
```

绑定后，IDEA 插件会在“推荐断点”页启用“Debug 当前场景”，创建只运行 `HashMapBehaviorTest#shouldKeepMappingsAfterResize` 的临时 JUnit Debug 配置。没有稳定测试映射的断点应省略 `evidenceId`，不要为了点亮按钮绑定不相关场景。

## 7. 写清讲解和断点观察

文档至少回答：

1. 什么输入会进入 `resize()`。
2. `oldCap`、`oldThr`、`newCap`、`newThr` 如何变化。
3. 为什么 `e.hash & oldCap` 能把旧链拆到原下标或 `原下标 + oldCap`。
4. 哪些内容是 Map 公开契约，哪些只是 OpenJDK 8u 实现。
5. JDK 17、21 是否仍可沿同一主线阅读，私有签名有哪些变化。

状态机或并发时序仅靠文字难以确认时，应增加可暂停、可回放的动画；简单分支不要为了形式强行制作动画。

## 8. 同步导航和学习路线

确认专题页面已进入 `docs/.vitepress/config.mts` 侧边栏，`source-index` 的 `recommendedNextTopicId` 能解析到真实专题。新增专题还要检查学习路线中的数量、前置关系和平台筛选。

网站和插件都从 `source-index` 读取数据，不要在组件中再维护另一份入口、断点或证据数组。

## 9. 运行完整校验

```bash
mvn --batch-mode test
npm run verify:docs
cd idea-plugin
./gradlew test buildPlugin --offline --no-daemon
cd ..
git diff --check
```

`scripts/validate-source-index.mjs` 会检查证据编号唯一、类型完整、入口与文档成对、Lab 方法存在、JUnit 方法带 `@Test`，以及 `breakpoint.evidenceId` 指向当前专题真实证据。

## 10. 提交可审查的 Pull Request

PR 描述要写清问题、固定版本、关键结论和验证命令。涉及页面或动画时附桌面与窄屏截图；涉及并发 Lab 时说明时序如何被稳定控制。

提交前最后检查：

- 没有提交密码、Token、IDE 配置、构建产物或 `agent.md`。
- 没有整段复制第三方源码，引用保留固定版本和原许可证。
- 每个结论都能落到文档、Lab、JUnit 和必要断点。
- 失败测试证明实现或文档确实有问题，而不是依赖机器速度的偶发失败。

做到这一步，一个专题才真正从“写了一篇源码文章”进入“可以定位、运行和验证的学习资产”。
