# 拦截器链：一次代理调用如何执行

## 源码入口

- JDK 代理：`JdkDynamicAopProxy.invoke(Object, Method, Object[])`
- CGLIB 代理：`CglibAopProxy.DynamicAdvisedInterceptor.intercept(...)`
- 链工厂：`DefaultAdvisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice(...)`
- 通知适配：`DefaultAdvisorAdapterRegistry.getInterceptors(Advisor)`
- 调用对象：`ReflectiveMethodInvocation`
- 链游标：`ReflectiveMethodInvocation.proceed()`
- 最终连接点：`ReflectiveMethodInvocation.invokeJoinpoint()`

## 先看一次完整外部调用

```text
client.service()
  → JDK proxy: InvocationHandler.invoke
     或 CGLIB proxy: MethodInterceptor.intercept
  → 保存旧 AopContext（仅 exposeProxy=true）
  → targetSource.getTarget()
  → advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass)
  → 没有拦截器：直接调用 target
  → 有拦截器：new ReflectiveMethodInvocation(...).proceed()
  → 处理返回值
  → targetSource.releaseTarget(target)
  → 恢复旧 AopContext
```

获取与释放 target 必须放在完整调用边界看。singleton TargetSource 看起来只是返回同一对象，但池化、线程局部或热切换 TargetSource 可能有真实资源语义。

## JDK 代理入口做了什么

`JdkDynamicAopProxy.invoke` 不只是简单执行 `method.invoke(target, args)`。5.3.39 的主要职责包括：

1. 特殊处理未被接口重新声明的 `equals` 和 `hashCode`。
2. 处理 `DecoratingProxy.getDecoratedClass()`。
3. 若 `exposeProxy=true`，把本代理暂存到当前线程并记住旧值。
4. 从 `TargetSource` 取得本次调用的目标对象和目标类。
5. 取得当前方法的拦截器链。
6. 空链时直接调用目标；非空链创建 `ReflectiveMethodInvocation`。
7. 处理返回 `this` 的流式 API：满足条件时把 target 返回值替换为 proxy。
8. 防止原始类型返回值为 null。
9. 在 finally 中释放 target，并恢复 ThreadLocal 中的旧代理。

`toString` 不一定像 `equals/hashCode` 那样完全绕开 Advisor。判断 Object 方法是否进通知，不应只凭“它来自 Object”概括，应结合 Spring 的特殊分支、代理接口和 Pointcut 实测。

## CGLIB 入口与 JDK 入口的共同主干

`CglibAopProxy.DynamicAdvisedInterceptor.intercept` 收到 `proxy、method、args、methodProxy`，随后也会：

- 暴露当前代理（若开启）；
- 从 TargetSource 取 target；
- 组装 chain；
- 空链走优化调用，非空链创建 `CglibMethodInvocation`；
- 处理返回值；
- 释放 target 并恢复 AopContext。

`CglibMethodInvocation` 是 `ReflectiveMethodInvocation` 的子类，会在安全条件下通过 CGLIB `MethodProxy` 调用连接点。**从 `proceed()` 角度看，两种代理共享同一递归模型。**

所以排查通知顺序时，优先看 `ReflectiveMethodInvocation.proceed`；排查代理类型、方法可见性或生成类问题时，再分别进入 JDK/CGLIB 实现。

## Advisor 如何变成 MethodInterceptor

每个 Advisor 都包含 Advice，但 Advice 不一定已经实现 `MethodInterceptor`。默认注册表通过适配器统一成环绕形式：

| Advice 类型 | 适配结果 | 大致执行位置 |
| --- | --- | --- |
| `MethodInterceptor` | 原样使用 | 自己控制 `proceed()` 前后 |
| `MethodBeforeAdvice` | `MethodBeforeAdviceInterceptor` | `proceed()` 之前 |
| `AfterReturningAdvice` | `AfterReturningAdviceInterceptor` | `proceed()` 正常返回后 |
| `ThrowsAdvice` | `ThrowsAdviceInterceptor` | 捕获匹配异常后 |

统一为 `MethodInterceptor` 后，链执行器无需为每一种通知类型写一套递归算法。

AspectJ 风格的 before/after/around 等通知也会被包装为相应拦截器，并配合额外 Advisor 处理调用上下文暴露与顺序语义。

## `DefaultAdvisorChainFactory` 如何组装本次链

对每个 Advisor，链工厂依次判断：

```text
Advisor
  ├─ PointcutAdvisor？
  │    ├─ preFiltered=false 时检查 ClassFilter
  │    ├─ 取得 MethodMatcher
  │    ├─ 静态匹配当前 method + targetClass
  │    └─ Advice 适配为一个或多个 MethodInterceptor
  │          └─ isRuntime=true 时包装为 InterceptorAndDynamicMethodMatcher
  ├─ IntroductionAdvisor？
  │    └─ 检查 ClassFilter 后加入其拦截器
  └─ 其他 Advisor
       └─ 直接适配并加入
```

### 静态匹配和动态匹配的成本不同

- `MethodMatcher.isRuntime() == false`：类与方法确定后即可判断，链可以缓存复用。
- `isRuntime() == true`：还需要本次 `args`，所以链中保存“拦截器 + 动态匹配器”包装；每次执行到该位置都再次判断。

动态匹配不是重新创建整条代理。代理与静态候选链仍可复用，只是在 `proceed()` 到达该节点时增加一次参数判断。

## `ReflectiveMethodInvocation` 保存什么

一次调用对象通常包含：

| 字段角色 | 典型内容 |
| --- | --- |
| `proxy` | 客户端看到的代理引用 |
| `target` | TargetSource 本次提供的真实目标 |
| `method` | 经过桥接方法解析后的调用方法 |
| `arguments` | 当前参数，可被拦截器替换 |
| `targetClass` | Pointcut 匹配与最具体方法解析使用的类型 |
| `interceptorsAndDynamicMethodMatchers` | 已按顺序排列的链 |
| `currentInterceptorIndex` | 初始为 -1，每次 proceed 前进一格 |
| `userAttributes` | 同一次 invocation 内拦截器共享的附加数据 |

它代表**一次调用的可变游标**，不能缓存后跨调用或跨线程复用。可缓存的是 Advisor 配置和按方法得到的链模板。

## `proceed()` 为什么既是循环又像递归

5.3.39 的逻辑可以等价写成：

```java
if (currentInterceptorIndex == chain.size() - 1) {
    return invokeJoinpoint();
}

Object next = chain.get(++currentInterceptorIndex);
if (next 是动态匹配包装) {
    if (matcher.matches(method, targetClass, arguments)) {
        return interceptor.invoke(this);
    }
    return proceed();
}
return ((MethodInterceptor) next).invoke(this);
```

`proceed()` 自身每次只把游标向前移动一个位置；但每个环绕拦截器通常又调用同一个 invocation 的 `proceed()`，于是 Java 调用栈形成嵌套：

```text
outer.invoke
  before outer
  └─ invocation.proceed
       inner.invoke
         before inner
         └─ invocation.proceed
              target.method
         after inner
  after outer
```

可观察事件顺序为：

```text
outer before
inner before
target
inner after
outer after
```

这也是事务、缓存、鉴权等多个切面叠加时，外层通知能够包住内层通知的原因。

## 动态 MethodMatcher 被跳过时发生什么

若运行时匹配失败，`proceed()` 不调用该包装中的 interceptor，而是立刻再次 `proceed()`，继续下一个节点：

```text
index = 0：审计拦截器，执行
index = 1：仅匹配 amount > 1000，当前 amount=20，跳过
index = 2：事务拦截器，执行
index = 3：target
```

链游标仍然前进，失败的动态匹配不会在本次调用中被第二次检查。

## 不调用、调用多次、替换参数都合法

`MethodInterceptor` 是环绕契约，因此它可以：

- 不调用 `proceed()`，直接返回缓存或拒绝访问；
- 调用一次，这是最常见情形；
- 修改 `invocation.getArguments()` 后再继续；
- 捕获并转换异常；
- 理论上调用多次，用于重试等特殊语义。

但“可以”不等于“总是安全”：事务拦截器、非幂等目标、链游标状态都可能让多次 `proceed` 产生意外。重试通常需要专用实现明确重建调用边界，而不是随意在任意通知中重复推进同一个游标。

## 目标调用与异常

链走到末尾时，`invokeJoinpoint()` 最终调用真实 target 方法。Spring 的反射工具会解包 `InvocationTargetException`，让业务异常沿拦截器调用栈传播。

这意味着：

- before 通知已经执行，目标抛异常后不会有 after-returning；
- finally 风格的 after 通知仍可执行；
- throws 通知只处理声明/匹配的异常；
- 外层拦截器可以看到内层转换后的异常。

不要只断言日志的绝对全集。框架升级可能增加基础设施节点；行为测试更适合断言关键相对顺序、返回值和异常类型。

## 方法链缓存不等于结果缓存

`AdvisedSupport` 会缓存某个 Method 对应的拦截器链，减少每次调用都遍历全部 Advisor 的成本。它缓存的是“可能执行哪些拦截器”的结构，不缓存：

- target 方法返回值；
- 运行时 MethodMatcher 对本次参数的结果；
- ThreadLocal 中的当前代理；
- targetSource 每次返回的目标实例。

当 Advisor 配置发生变化时，代理配置会清理相应方法缓存；业务代码不应直接修改内部缓存。

## 返回值边界

若 target 方法返回 target 自身，Spring 在满足返回类型兼容且方法未声明 `RawTargetAccess` 等条件时，可能把返回值替换成 proxy，避免流式调用下一步意外逃离代理。

这不是任意对象替换：返回别的业务对象不会自动代理；原始返回类型若得到 null，代理入口会抛出明确异常，避免之后发生更隐晦的拆箱空指针。

## 建议断点

| 断点 | 重点变量 |
| --- | --- |
| `JdkDynamicAopProxy.invoke` | `method`、`args`、`targetSource`、`chain` |
| `DynamicAdvisedInterceptor.intercept` | `methodProxy`、`target`、`chain` |
| `DefaultAdvisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice` | `advisor`、`methodMatcher`、`interceptors` |
| `ReflectiveMethodInvocation.<init>` | `targetClass`、链列表、初始游标 |
| `ReflectiveMethodInvocation.proceed` | `currentInterceptorIndex`、当前链元素 |
| `invokeJoinpoint` | `target`、`method`、`arguments` |

Lab 中的 `shouldInvokeInterceptorChainInNestedOrder` 对应这条路径，详见[断点实验](./debug-lab.md)。
