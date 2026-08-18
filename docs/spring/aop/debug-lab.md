# Spring AOP 断点实验手册

## 实验入口

模块：`labs/spring-framework-lab`

主类：`io.github.javasourceatlas.spring.aop.SpringAopDebugLab`

```bash
mvn -f labs/spring-framework-lab/pom.xml -Dtest=SpringAopBehaviorTest test

mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=aop
```

基线为 Java 8 与 Spring Framework 5.3.39。若 IDE 使用更高版本 JDK，也应先把模块语言级别设为 8，再额外用目标 JDK 复跑行为测试。

## 实验结构

| 文件 | 作用 |
| --- | --- |
| `AtlasService` | 提供 JDK 代理接口和自调用方法契约 |
| `AtlasServiceImpl` | 真实 target，分别实现 `this.inner` 与 `currentProxy.inner` |
| `NamedTraceInterceptor` | 记录 `proceed()` 前后的具名环绕通知 |
| `AopProxyExamples` | 用同一组工厂方法创建 JDK、CGLIB 与 exposeProxy 代理 |
| `LabConfiguration` | 注册 `DefaultAdvisorAutoProxyCreator` 和 NameMatch Advisor |
| `SpringAopDebugLab` | 可运行的两条调试主线 |
| `SpringAopBehaviorTest` | 固定六项公开可观察行为 |

所有验证只使用 Spring 公共 API 和事件记录，不反射读取自动代理创建器私有缓存。这样升级 Spring 后可以先复跑测试，再根据失败场景进入目标版本源码。

## 主程序实际演示什么

### 容器自动代理路径

```text
new AnnotationConfigApplicationContext(LabConfiguration.class)
  → 注册 DefaultAdvisorAutoProxyCreator
  → 创建 AtlasServiceImpl target
  → postProcessAfterInitialization
      → wrapIfNecessary
      → 找到 NameMatchMethodPointcutAdvisor
      → 创建 JDK proxy
  → context.getBean(AtlasService.class)
  → proxy.greet("atlas")
```

预期关键输出：

```text
is-jdk-proxy=true
greet-result=hello,atlas
greet-events=[advice:auto:before:greet, target:greet, advice:auto:after:greet]
```

JDK 8 上生成类示例为 `com.sun.proxy.$Proxy...`。较新 JDK 常见 `jdk.proxy...` 包名；包名与编号不应写进断言。

### 自调用对照

同一个自动代理依次执行两种 outer：

```text
direct-self-invocation=
[advice:auto:before:outerDirect,
 target:outerDirect,
 target:inner,
 advice:auto:after:outerDirect]
```

这里没有 `advice:auto:before:inner`，证明 `this.inner()` 直接留在 target。

```text
proxy-self-invocation=
[advice:auto:before:outerViaCurrentProxy,
 target:outerViaCurrentProxy,
 advice:auto:before:inner,
 target:inner,
 advice:auto:after:inner,
 advice:auto:after:outerViaCurrentProxy]
```

配置开启 `exposeProxy` 后，`AopContext.currentProxy().inner()` 重新进入代理，所以 inner 拥有自己的完整通知边界。

### 手工 CGLIB 与双层通知

```text
new ProxyFactory()
  → setTarget(target)
  → setProxyTargetClass(true)
  → addAdvice(outer)
  → addAdvice(inner)
  → getProxy()
```

预期关键输出：

```text
is-cglib-proxy=true
cglib-events=[advice:outer:before:greet,
              advice:inner:before:greet,
              target:greet,
              advice:inner:after:greet,
              advice:outer:after:greet]
```

通知按加入顺序进入，按相反顺序退出。这对应 `ReflectiveMethodInvocation.proceed()` 的递归调用栈。

## 七个行为测试

### 1. 默认选择 JDK 代理

`shouldCreateJdkProxyForInterfaceBasedTarget` 使用 `new ProxyFactory(target)`。目标实现 `AtlasService`，未开启类代理，因此断言：

- `AopUtils.isJdkDynamicProxy(proxy)` 为 true；
- `AopUtils.isCglibProxy(proxy)` 为 false；
- 代理能正常调用业务方法。

### 2. 强制选择 CGLIB

`shouldCreateCglibProxyWhenProxyTargetClassIsEnabled` 显式调用 `setProxyTargetClass(true)`，即使目标有接口，仍断言得到 CGLIB 代理。

该测试验证的是当前可代理目标的选择行为，不意味着 final 类或 final 方法也能被代理。

### 3. 拦截器链嵌套顺序

`shouldInvokeInterceptorChainInNestedOrder` 依次加入 `outer`、`inner` 两个 `MethodInterceptor`，精确断言五个事件：

```text
outer before → inner before → target → inner after → outer after
```

这里使用完整顺序断言是合理的，因为测试自行构造 ProxyFactory，链中没有不可控的应用 Advisor。

### 4. 异常时的拦截器链回卷

`shouldUnwindInterceptorChainWhenTargetThrows` 让目标方法在记录事件后抛出固定异常，断言两层通知仍会经过 `finally` 按相反顺序退出：

```text
outer before → inner before → target throws → inner after → outer after
```

这能区分“目标方法是否正常返回”和“环绕通知是否有机会完成清理”两个问题。

### 5. 自调用边界

`shouldExposeSelfInvocationBoundary` 在同一个 `exposeProxy=true` 代理上对比：

| 调用 | outer 通知 | inner target | inner 通知 |
| --- | --- | --- | --- |
| `outerDirect → this.inner` | 有 | 有 | 无 |
| `outerViaCurrentProxy → proxy.inner` | 有 | 有 | 有 |

这比只检查结果值更有意义：两条路径都返回 `inner:...`，真正不同的是代理是否重新获得控制权。

### 6. currentProxy 的线程调用栈边界

`shouldRejectCurrentProxyOutsideAnInvocation` 在没有代理调用上下文时执行 `AopContext.currentProxy()`，断言抛出 `IllegalStateException`。

不要把这个测试误解成“只要同一线程曾经调用过代理就能取得”。代理在 finally 中恢复旧 ThreadLocal 值，调用结束后即不再可用。

### 7. 容器自动代理

`shouldCreateProxyThroughApplicationContext` 使用真实 `AnnotationConfigApplicationContext`：

- BeanPostProcessor 自动包装 `AtlasService`；
- 返回的是 JDK 代理；
- 容器中的 NameMatch Advisor 包住目标调用。

这条测试适合进入 `AbstractAutoProxyCreator`；前六条更适合隔离观察 `ProxyFactory` 与运行时调用链。

## 推荐断点路线 A：代理创建

先给 `beanName.equals("atlasService")` 添加条件，避免配置类和基础设施 Bean 噪声。

| 顺序 | 类与方法 | 观察变量 |
| --- | --- | --- |
| 1 | `AbstractAutoProxyCreator.postProcessAfterInitialization` | `bean` 是否还是 AtlasServiceImpl、`cacheKey` |
| 2 | `AbstractAutoProxyCreator.wrapIfNecessary` | `advisedBeans`、`specificInterceptors` |
| 3 | `AbstractAdvisorAutoProxyCreator.findEligibleAdvisors` | 候选与适用 Advisor 数量 |
| 4 | `AopUtils.findAdvisorsThatCanApply` | `ClassFilter`、`MethodMatcher` 哪个方法命中 |
| 5 | `AbstractAutoProxyCreator.createProxy` | `proxyFactory.interfaces`、`proxyTargetClass` |
| 6 | `DefaultAopProxyFactory.createAopProxy` | `targetClass` 与三个选择条件 |
| 7 | `JdkDynamicAopProxy.getProxy` | classLoader、interfaces、最终代理 Class |

在第 2 步重点区分：

- `DO_NOT_PROXY`：没有合适 Advisor；
- 非空 `specificInterceptors`：已经决定创建代理；
- `advisedBeans` 中的 Boolean 值：此前是否做过判断。

## 推荐断点路线 B：拦截器链

以 `proxy.greet("chain")` 为入口：

| 顺序 | 类与方法 | 观察变量 |
| --- | --- | --- |
| 1 | `JdkDynamicAopProxy.invoke` | `method`、`args`、`target`、`targetClass` |
| 2 | `AdvisedSupport.getInterceptorsAndDynamicInterceptionAdvice` | 方法链缓存是否命中 |
| 3 | `DefaultAdvisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice` | 每个 Advisor 的 matcher 和适配结果 |
| 4 | `ReflectiveMethodInvocation.<init>` | 链列表与 `currentInterceptorIndex=-1` |
| 5 | `ReflectiveMethodInvocation.proceed` | 每次停下记录 index 与当前元素类型 |
| 6 | `AopUtils.invokeJoinpointUsingReflection` | 最终 target、method、args |

在 `proceed` 处连续恢复，会看到 index 从 -1 逐步前进；目标返回后，同一批 Java 栈帧按相反顺序退出，不会把 index 倒退。

## 推荐断点路线 C：自调用

1. 在 `JdkDynamicAopProxy.invoke` 给方法名 `outerDirect` 或 `outerViaCurrentProxy` 加条件。
2. 在 `AtlasServiceImpl.inner` 断住，比较当前调用栈。
3. `outerDirect` 路径中，inner 上方直接是 outer target 方法，没有第二个 `JdkDynamicAopProxy.invoke`。
4. `outerViaCurrentProxy` 路径中，inner 上方会出现新的代理 invoke 与新的 `ReflectiveMethodInvocation`。
5. 在 `AopContext.setCurrentProxy` 观察旧值保存和 finally 恢复。

## IDE 使用建议

- 关闭 “Do not step into library classes” 或为 `org.springframework.aop` 添加允许规则。
- Maven 下载的 `spring-aop-5.3.39-sources.jar` 可直接附加；没有源码时 IDE 通常也能自动下载。
- 条件断点尽量用 beanName 或 methodName 过滤，避免框架初始化产生大量停顿。
- JDK 代理生成类不是主要阅读目标；先进入 `JdkDynamicAopProxy.invoke`。
- CGLIB 生成字节码也不是第一阅读目标；先进入 `DynamicAdvisedInterceptor.intercept`。

## 扩展练习

在理解并通过现有测试后，可以自行增加：

1. 自定义 `DynamicMethodMatcherPointcut`，只在参数等于某值时执行通知。
2. 给两个 Advisor 实现不同 `Ordered` 值，验证容器自动代理排序。
3. 创建 final 类并强制 CGLIB，观察明确失败信息。
4. 让目标方法抛异常，验证两个 `NamedTraceInterceptor` 的 finally 仍逆序执行。
5. 关闭 `exposeProxy` 后调用 `outerViaCurrentProxy`，观察异常发生位置和通知回卷。

扩展测试仍应围绕返回值、异常类型和关键事件顺序，不要依赖生成类编号或内部缓存实现。
