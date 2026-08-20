# Spring AOP 源码地图

本专题以 **Spring Framework 5.3.39** 为可执行基线，目标不是背诵几个类名，而是回答四个能真正指导调试的问题：

1. 一个普通 Bean 在什么时候、为什么被替换成代理？
2. Spring 根据什么选择 JDK 动态代理或 CGLIB？
3. 多个 Advisor 如何变成一条有顺序、可递归执行的拦截器链？
4. 为什么目标对象里的 `this.inner()` 绕过通知，`exposeProxy` 又只是一种有代价的补救？

完成代理创建主线后，进入 [Bean、早期引用与最终代理](/spring/deep-dive/bean-proxy-cycle)，核对循环依赖场景为什么必须复用同一个早期代理；再沿 [MVC 请求怎样进入事务代理](/spring/deep-dive/request-transaction) 观察代理的真实调用者。

<TopicStudyPanel topic-id="spring-framework-5-3-aop" />

## 源码入口

- 自动代理模板：`AbstractAutoProxyCreator`
- Advisor 自动发现：`AbstractAdvisorAutoProxyCreator.findEligibleAdvisors(...)`
- 代理配置：`ProxyFactory` / `AdvisedSupport`
- 代理选择：`DefaultAopProxyFactory.createAopProxy(...)`
- JDK 调用入口：`JdkDynamicAopProxy.invoke(...)`
- CGLIB 调用入口：`CglibAopProxy.DynamicAdvisedInterceptor.intercept(...)`
- 拦截器链组装：`DefaultAdvisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice(...)`
- 拦截器递归：`ReflectiveMethodInvocation.proceed()`

## 先建立完整心智模型

Spring AOP 是**基于代理的运行时方法拦截**。容器里至少有三个不同对象，阅读源码时不能把它们混成一个：

```text
BeanDefinition
  │  IOC 创建、填充、初始化
  ▼
Target（真实业务对象）
  │  AbstractAutoProxyCreator 判断 Advisor 是否适用
  ▼
Proxy（容器最终暴露的 Bean）
  │  接收外部方法调用
  ▼
MethodInterceptor chain
  │  最后一环调用 Target
  ▼
返回值 / 异常
```

| 对象 | 保存什么 | 它不是什么 |
| --- | --- | --- |
| target | 字段状态和真实业务方法 | 不是通知链本身 |
| proxy | 代理类型、`AdvisedSupport` 配置或 InvocationHandler | 通常不是新的业务状态副本 |
| Advisor | Advice 与“在哪里生效”的组合 | 不是每次调用都必然执行的通知 |
| Pointcut | `ClassFilter + MethodMatcher` | 不负责执行目标方法 |
| MethodInterceptor | `invoke(invocation)` 环绕调用 | 不负责决定 Bean 是否需要代理 |
| MethodInvocation | 当前方法、参数、target 与链游标 | 不是可跨线程重复使用的配置对象 |

## 两个时间轴必须分开看

### Bean 创建阶段：只发生一次或少数几次

```text
initializeBean
  → BeanPostProcessor.postProcessAfterInitialization
      → AbstractAutoProxyCreator.wrapIfNecessary
          → 找出适用 Advisor
          → ProxyFactory 复制代理配置
          → DefaultAopProxyFactory 选择代理实现
          → getProxy 返回 JDK 或 CGLIB 代理
```

这里解决的是“以后谁接收调用”。自动代理创建器一般不会在每次业务调用时重新扫描所有 Bean。

### 方法调用阶段：每次外部调用都会发生

```text
client → proxy
          → 取得 target
          → 为 method 取得 interceptor chain
          → new ReflectiveMethodInvocation(...)
          → proceed()
              → interceptor 1
                  → interceptor 2
                      → target.method()
```

这里解决的是“这一次调用哪些通知、按什么顺序执行”。运行时 MethodMatcher 仍可能根据本次参数决定是否跳过某个拦截器。

## 动画：从 Bean 到代理，再到目标方法

下面的动画把两个时间轴和自调用边界放在同一张图中。建议先逐步点击，再对照后续源码章节。

<SpringAopAnimation />

## `@Aspect` 到运行时链，中间还有什么

使用 `@EnableAspectJAutoProxy` 时，常见链路是：

```text
@EnableAspectJAutoProxy
  → AspectJAutoProxyRegistrar
  → AopConfigUtils 注册 AnnotationAwareAspectJAutoProxyCreator

@Aspect Bean
  → BeanFactoryAspectJAdvisorsBuilder
  → ReflectiveAspectJAdvisorFactory
  → 一个或多个 Advisor

普通业务 Bean 初始化
  → AnnotationAwareAspectJAutoProxyCreator
  → 查找适用 Advisor
  → 创建代理
```

因此，`@Aspect` 类不是直接变成“包住所有 Bean 的代理”。它先被解析为 Advisor；自动代理创建器再针对每个候选 Bean 做匹配。

本专题 Lab 使用 `DefaultAdvisorAutoProxyCreator + NameMatchMethodPointcutAdvisor`，刻意不引入 AspectJ 表达式解析。这样断点中只保留代理选择、Advisor 匹配和拦截器执行的核心机制。理解这条主干后，再看 `@Aspect` 只是多了一层“如何生产 Advisor”。

## 推荐阅读顺序

1. [代理创建全流程](./proxy-creation.md)：跟踪 `wrapIfNecessary → ProxyFactory → getProxy`。
2. [拦截器链执行](./interceptor-chain.md)：跟踪一次方法调用如何进入和退出多层通知。
3. [Pointcut 与自调用边界](./pointcut-self-invocation.md)：理解匹配时机、`this` 调用和 `exposeProxy`。
4. [断点实验](./debug-lab.md)：用可运行案例固定 JDK/CGLIB、顺序与自调用行为。

## 第一次调试只观察六个变量

| 变量 | 出现位置 | 要回答的问题 |
| --- | --- | --- |
| `beanName` / `cacheKey` | `wrapIfNecessary` | 当前处理哪个 Bean，是否已做过判定 |
| `specificInterceptors` | `wrapIfNecessary` | 没有适用 Advisor，还是已经收集到候选 |
| `proxyFactory` | `createProxy` | 接口、targetSource、`proxyTargetClass`、`exposeProxy` 是什么 |
| `targetClass` | 代理选择与链组装 | 匹配和代理策略看到的真实类型是什么 |
| `chain` | JDK/CGLIB 调用入口 | 这次方法最终有哪些 MethodInterceptor |
| `currentInterceptorIndex` | `proceed` | 当前正准备进入第几个拦截器 |

## 常见误区速查

| 说法 | 问题 | 更准确的理解 |
| --- | --- | --- |
| “加了 `@Aspect` 就创建代理” | 忽略自动代理创建器与匹配过程 | `@Aspect` 先贡献 Advisor，适用 Bean 才被包装 |
| “有接口就一定是 JDK 代理” | 强制类代理会改变选择 | 默认倾向 JDK；`proxyTargetClass=true` 可强制 CGLIB |
| “CGLIB 能代理所有方法” | final/private 方法不能被子类覆盖 | 只有可覆盖且进入代理的方法才能拦截 |
| “通知按注解书写顺序执行” | 书写顺序不是稳定排序契约 | 看 Advisor 顺序、`Ordered` 和具体自动代理创建器规则 |
| “CGLIB 可以解决自调用” | 目标内部 `this` 没重新经过代理入口 | JDK 与 CGLIB 的 Spring 代理都有自调用边界 |
| “开启 `exposeProxy` 就彻底解决” | 引入 ThreadLocal 和 Spring API 耦合 | 优先拆 Bean；确有必要时局部使用并理解线程边界 |

## 公开契约与实现细节

应用代码可以依赖 `Advisor`、`Pointcut`、`MethodInterceptor`、`ProxyFactory`、`AopContext` 等公开 API，但应谨慎使用后两者：它们会把业务代码与 Spring AOP 绑定。

`earlyProxyReferences`、方法链缓存、CGLIB 回调索引、`ReflectiveMethodInvocation.currentInterceptorIndex` 等属于实现细节，只适合源码学习和诊断，不应被业务代码反射读取。

## Spring 5.3 与 Spring 6 的阅读边界

| 维度 | Spring 5.3.39 | Spring 6.x |
| --- | --- | --- |
| Java 基线 | Java 8+ | Java 17+ |
| 企业 API 命名空间 | `javax.*` 时代 | Jakarta EE 9+ 的 `jakarta.*` |
| 核心代理模型 | `AbstractAutoProxyCreator`、JDK/CGLIB、Advisor 链 | 核心概念保持连续，但内部字段和辅助方法需按目标小版本复核 |
| CGLIB | Spring 已重打包在 `spring-core` 中 | 仍由 Spring 内部管理，不要额外依赖旧版外部 cglib |
| AOT | 主要是传统 JVM 运行时模型 | AOT/native image 对反射与代理提示提出额外要求 |

不要拿 5.3.39 的私有字段行号直接套到 Spring 6。推荐以本文建立对象关系，再切到应用实际使用的小版本重新下断点。
