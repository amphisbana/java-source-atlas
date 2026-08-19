# Java Source Atlas IDEA Plugin

该子工程把仓库现有的 `source-index` 接入 IntelliJ IDEA。插件不会复制维护专题数据；每次构建都从上级目录合并最新索引。

插件面向 IntelliJ IDEA 2024.2 至 2026.2，编译目标为 Java 21。Gradle Toolchain 会在本机没有 JDK 21 时自动获取所需运行时。

## 0.2.3 当前能力

- 右侧 `Source Atlas` 工具窗口；
- 按当前 Java 类和方法自动匹配专题；
- 搜索全部 JDK、Spring 专题；
- 在 IDEA 内嵌教程页阅读对应锚点，并保留系统浏览器回退入口；
- 按类名、方法名和参数类型精确匹配重载方法，并从索引反向定位项目或依赖源码；
- 一键添加当前推荐断点或本专题全部推荐断点，自动跳过同文件同一行的已有断点；
- 打开专题配套 Lab 主类，或创建临时 Application 配置直接 Debug；
- 在已收录的方法旁显示 gutter 图标；
- 区分 JDK、Spring Framework 与 Spring Boot 的 exact、patch、minor、major 差异和兼容范围；
- 在 IDEA 设置中修改教程站点地址。
- 按专题、源码入口和推荐断点分组展示对应操作，避免无关按钮占用工具窗口空间。

插件默认使用 `https://amphisbana.github.io/java-source-atlas`，安装后无需运行本地文档服务。
开发仓库内容时，可在 IDEA 设置中临时改为 `http://127.0.0.1:4180`。

内嵌教程依赖 IDEA 的 JCEF 运行环境；IDEA 2026.2 中插件会连接拆分后的可选 JCEF 模块，
模块缺失或初始化失败时仍会保留完整专题导航，并提示使用“浏览器打开”。
Lab 打开与 Debug 要求当前 IDEA 项目包含完整仓库，并已导入 `labs/jdk-labs`、
`labs/spring-framework-lab` 或 `labs/spring-boot-lab` 对应 Maven 模块。

## 构建与运行

```bash
cd idea-plugin
./gradlew test verifyPlugin buildPlugin
./gradlew runIde
```

`verifyPlugin` 固定检查最低支持版本 IDEA 2024.2.5；若本机存在
`/Applications/IntelliJ IDEA.app`，还会同时检查该版本。其他安装位置可通过
`-Patlas.localIdeaPath=/absolute/path/to/IntelliJ IDEA.app` 指定。

可安装 ZIP 位于 `build/distributions/`。在 IDEA 中通过 `Settings | Plugins | Install Plugin from Disk...` 选择该文件。
