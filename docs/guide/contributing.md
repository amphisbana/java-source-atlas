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

