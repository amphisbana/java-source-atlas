# Spring 源码学习路线

这五个专题不是互相独立的知识点。把它们放进同一个应用启动和请求场景，才能看清 Spring 为什么先有 IOC，再有代理、事务、自动装配和 Web 请求处理。

已经完成五条独立主线后，进入 [Spring 核心链路深挖](./deep-dive/)；它会把 Boot 配置解析、IOC 生命周期、早期代理、MVC 请求、事务资源和异常清理放回同一条真实调用栈。

## 一条贯穿五个专题的调用链

```text
SpringApplication.run
  → 创建 Environment 与 ApplicationContext
  → 加载用户配置和自动配置候选
  → ApplicationContext.refresh
      → 注册 BeanDefinition
      → 注册 AOP / Transaction / MVC 基础设施 BeanPostProcessor
      → 创建业务 Bean
          → AbstractAutoProxyCreator 判断 Advisor 是否匹配
          → 为 Service 返回代理对象
  → Web 应用开始接收请求
      → DispatcherServlet.doDispatch
      → Controller 方法调用
      → Controller 调用 Service 代理
          → MethodInterceptor 链
          → TransactionInterceptor
              → PlatformTransactionManager 开启或加入事务
              → 调用目标 Service
              → 提交或回滚
      → MVC 写响应或解析异常
```

这里有三个不同层次的“链”：

| 链 | 建立时间 | 主要对象 | 解决的问题 |
| --- | --- | --- | --- |
| IOC 生命周期链 | 容器刷新期间 | BeanDefinition、BeanPostProcessor、单例缓存 | Bean 从定义怎样成为可用对象 |
| AOP/事务调用链 | Bean 创建时组装，方法调用时执行 | Proxy、Advisor、MethodInterceptor | 一次方法调用前后附加哪些横切逻辑 |
| MVC 请求链 | 路由启动期注册，请求到来时执行 | HandlerMapping、HandlerAdapter、Resolver | HTTP 请求怎样变成 Java 调用和响应 |

Boot 自动装配位于这些链的外层：它决定哪些基础设施配置应该作为 BeanDefinition 进入容器，但最终仍由 IOC 创建，由条件注解决定是否退让给用户配置。

## 推荐学习顺序

1. [Spring IOC](./ioc/)：先理解 `refresh()`、Bean 创建和后处理器，因为后面所有基础设施都依赖容器扩展点。
2. [Spring AOP](./aop/)：理解自动代理创建、Advisor 匹配和拦截器链，这是声明式事务的执行基础。
3. [Spring Transaction](./transaction/)：在 AOP 调用链中观察事务获取、传播、挂起、提交和回滚。
4. [Spring Boot 自动装配](./boot-autoconfigure/)：回到应用启动外层，理解这些基础设施为什么会“自动出现”。
5. [Spring MVC](./mvc/)：最后用一次 HTTP 请求把参数解析、Controller、Service 代理和异常响应连起来。

不建议先背 `@Transactional` 的七种传播行为，再回头找代理和线程资源。先看 AOP 的 `proceed()`，传播行为才有准确的调用边界。

## 每个专题应该得到什么

| 专题 | 核心问题 | 动画观察重点 | Lab 的行为证据 |
| --- | --- | --- | --- |
| IOC | 定义怎样变成 Bean | `refresh()` 阶段与单例创建时机 | 生命周期、扩展顺序、循环依赖 |
| AOP | Bean 怎样变成代理 | 代理选择与拦截器递归推进 | JDK/CGLIB、顺序、自调用 |
| Transaction | 事务怎样包围目标方法 | 传播、挂起、rollback-only | 提交、回滚、内外事务关系 |
| Boot 自动装配 | 候选配置为何生效或退让 | 候选加载、条件过滤、Bean 注册 | 属性开关、用户 Bean 覆盖、条件报告 |
| MVC | HTTP 怎样变成方法调用 | 映射、参数、异常和响应状态 | 200、404、405 与拦截器顺序 |

## 统一调试原则

- 先用项目 Lab 触发一个确定分支，再进入框架源码。
- 方法断点要附带目标 Bean、路径或方法名条件，避免框架基础设施大量命中。
- 测试验证公开行为，断点验证内部过程；不要用深反射把内部字段写成兼容契约。
- Spring Framework 5.3.39 仍支持 Java 8；Spring 6 要求 Java 17，并迁移到 Jakarta 命名空间。
- Spring Boot 2.7.18 是本项目 Boot 专题的 Java 8 基线；Boot 3 使用 Spring 6，其自动配置注册文件和运行时前提需要单独核对。

## 运行实验

```bash
mvn test

mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=ioc
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=aop
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=transaction
mvn -pl labs/spring-boot-lab compile exec:java
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=mvc
```

完成五个实验后，再选择一个自己的 Spring 应用，从 `SpringApplication.run` 进入、沿 `refresh()` 找到业务 Bean 的代理创建点，并用一次真实请求跟到事务提交。这条闭环比孤立记类名更接近排查生产问题时的阅读方式。
