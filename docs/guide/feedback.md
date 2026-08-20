# 反馈与问题定位

反馈越接近一个可以复现的源码分支，越容易被快速确认。提交前可以先在[源码索引](/source-explorer/)、[学习路线](/learning-path/)和[路线图](/roadmap/)中搜索关键词。

## Bug 报告

请提供：

1. 受影响的专题、插件版本或文档地址。
2. 从打开项目到出现问题的最小步骤。
3. 操作系统、JDK、IDEA、Node、Maven 或 Gradle 版本。
4. 预期行为与实际行为，以及日志、截图或最小代码。

不要在 Issue、截图或日志中粘贴密码、Token、公司代码和未脱敏的路径。插件问题优先附上 IDEA 的 `Help | Show Log in Finder` 中相关片段，并说明是否使用本地 `4180` 文档站。

## 专题建议

一个可落地的专题建议至少应回答：

- 读者遇到的真实问题是什么？
- 应该从哪个公开 API、源码类或方法进入？
- 哪个状态变化可以用 Lab 或测试观察？
- 需要比较哪些 JDK、Spring 或 Spring Boot 版本？

如果你愿意实现它，请同时说明预计新增的源码入口、推荐断点、测试和第三方许可证来源。

## 文档修正

小型错别字、链接或示例修正可以直接提交 Pull Request。涉及结论变化时，请引用固定版本源码、测试输出或官方文档，并在正文标注适用版本。

## 入口

- [提交 Bug](https://github.com/amphisbana/java-source-atlas/issues/new?template=bug_report.yml)
- [建议新专题](https://github.com/amphisbana/java-source-atlas/issues/new?template=topic_request.yml)
- [参与贡献](/guide/contributing)
- [查看 GitHub Discussions](https://github.com/amphisbana/java-source-atlas/discussions)
