# 贡献指南

感谢参与 Java Source Atlas。项目优先接受“可以运行、可以定位、可以验证”的源码解析，不接受只有结论而没有源码入口的文章。

## 章节标准

新增源码主题至少包含：

1. 明确的 JDK 或框架版本基线。
2. 一个能触发目标源码分支的最小案例。
3. 从公开 API 到核心实现的调用链。
4. 推荐断点、触发条件和预期变量。
5. 关键源码的逐步解释，不大段复制第三方源码。
6. 至少一个自动化测试，验证对外可观察行为。
7. 版本差异和不适用范围。
8. 第三方源码地址及许可证说明。
9. 至少三条结构化 evidence，包含主线和边界、失败或清理证据；仅把确定会经过目标方法的 JUnit 场景绑定到断点。

## 代码规范

- Java 方法必须写中文 Javadoc 注释。
- 复杂算法和业务逻辑必须写中文行内注释。
- 只有在确实降低复杂度时才使用设计模式。
- 修改既有业务逻辑时，不直接删除旧逻辑；应注释保留并注明日期。
- 示例应尽量只依赖公开 API。若必须使用反射访问 JDK 内部结构，需要说明模块参数和兼容范围。

## 提交前验证

```bash
mvn test
npm run verify:docs
npm run verify:community
```

## 选择合适的协作入口

- 可复现的文档、索引、Lab 或插件问题：使用 [Bug 报告](https://github.com/amphisbana/java-source-atlas/issues/new?template=bug_report.yml)。
- 想增加一个源码专题：使用 [新专题建议](https://github.com/amphisbana/java-source-atlas/issues/new?template=topic_request.yml)。
- 还在比较阅读方向、版本差异或实验设计：使用 GitHub Discussions。
- 开始实现专题：复用 [新专题模板](docs/guide/topic-template.md)，并同步索引、Lab、测试与导航。
- 第一次贡献完整专题：按 [HashMap 端到端贡献示例](docs/guide/contribution-walkthrough.md)依次完成版本固定、evidence 和断点绑定。
- 新增或修改 Lab：先阅读 [Lab 编写规范](docs/guide/lab-authoring.md)，确保有触发条件、推荐断点和自动化断言。

提交 Pull Request 时请使用仓库模板，并确认没有提交密码、Token、IDE 配置、构建产物或 `agent.md`。
