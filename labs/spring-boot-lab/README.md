# Spring Boot 自动装配调试 Lab

本项目固定 Spring Boot 2.7.18、Spring Framework 5.3.39 和 Java 8，独立演示 SpringApplication 启动、自动配置候选发现、自定义条件、属性绑定与用户 Bean 回退。

Boot 独立成模块后，不会把 Starter、Boot parent 和自动配置测试栈带入 IOC、AOP、事务与 MVC 的纯 Spring Framework 实验。

## 运行

```bash
mvn -pl labs/spring-boot-lab test
mvn -pl labs/spring-boot-lab compile exec:java
```

传入自定义属性：

```bash
mvn -pl labs/spring-boot-lab compile exec:java \
  -Dexec.args="--atlas.feature.enabled=true --atlas.feature.message=调试 --atlas.feature.repeat=3"
```

自动配置候选资源位于 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。测试既验证单个候选，也验证 `@EnableAutoConfiguration` 能从该资源发现候选。
