# 贡献源码解析

项目接受 JDK 和主流 Java 框架的源码主题。完整规范位于仓库根目录的 `CONTRIBUTING.md`。

## 建议选题格式

```text
场景：Spring Bean 的属性依赖如何被注入？
入口：DefaultListableBeanFactory#getBean
核心分支：AbstractAutowireCapableBeanFactory#populateBean
实验：构造属性依赖并在 populateBean 设置断点
验证：断言依赖已经注入，且生命周期回调顺序正确
```

## 内容审查重点

- 结论是否能够在指定版本源码中定位。
- 案例是否能稳定触发被解释的分支。
- 是否区分实现细节和公开 API 契约。
- 是否说明版本范围，而不是把某个版本写成永恒结论。

## 开始实现

1. 先用[新专题模板](/guide/topic-template)组织问题、入口、调用链、断点和版本边界。
2. 按 [Lab 编写规范](/guide/lab-authoring)增加可运行案例和自动化断言。
3. 同步维护 `source-index`、导航和学习路线，避免网站与 IDEA 插件的数据漂移。
4. 运行 `mvn --batch-mode test` 和 `npm run verify:docs`。

遇到不确定的版本差异或专题边界，可以先通过[反馈入口](/guide/feedback)进入 Issue 或 Discussion。
