# Spring Framework 统一调试 Lab

本项目把 Spring IOC、AOP、事务与 MVC 放在同一个 Maven 模块中，统一使用 Spring Framework 5.3.39 和 Java 8 基线。

“同一个项目”只表示共享依赖、构建和测试入口。四个专题仍位于独立包中，并分别创建自己的 `ApplicationContext`，避免 AOP 自动代理器、事务自动代理器和 Web 基础设施互相改变实验结果。

## 目录

| 专题 | 包 | 主要观察点 |
| --- | --- | --- |
| IOC | `spring.ioc` | refresh、扩展点顺序、Bean 生命周期、FactoryBean、循环依赖 |
| AOP | `spring.aop` | JDK/CGLIB、代理创建、拦截器链、自调用 |
| Transaction | `spring.transaction` | 传播、挂起、保存点、rollback-only、线程边界 |
| MVC | `spring.mvc` | DispatcherServlet、参数解析、返回值、异常解析 |

## 运行

运行全部 Spring 测试：

```bash
mvn -pl labs/spring-framework-lab test
```

通过统一入口选择专题：

```bash
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=ioc
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=aop
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=transaction
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=mvc
```

在 IDE 中可直接运行四个原有 `*DebugLab` 主类。不要建立扫描整个 `io.github.javasourceatlas.spring` 包的共享上下文；源码学习需要保留每条调用链的独立边界。
