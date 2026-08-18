# Pointcut、自调用与代理边界

## Advisor、Pointcut、Advice 各自回答什么

```text
Advisor = Advice + 适用范围元数据

Pointcut = ClassFilter + MethodMatcher
Advice   = 命中后怎样执行
```

| 组件 | 核心问题 | 示例 |
| --- | --- | --- |
| ClassFilter | 这个目标类是否可能命中 | 只处理 `OrderService` |
| MethodMatcher 静态部分 | 这个方法签名是否命中 | 方法名为 `pay` |
| MethodMatcher 运行时部分 | 本次参数是否命中 | 金额大于 1000 |
| Advice | 命中后做什么 | 开事务、鉴权、记录耗时 |
| Advisor | 把范围和行为组成可排序单元 | 事务属性源 Advisor |

一个 Bean 只要存在至少一个适用方法，通常就值得创建代理；但代理上的其他方法仍可能得到空拦截器链，直接调用 target。

## Spring 做两轮匹配

### 创建代理前：Bean 级筛选

`AopUtils.findAdvisorsThatCanApply` 判断候选 Advisor 是否至少能应用于目标类的某个方法。这一步避免完全无关的 Bean 也被包装。

对于普通 PointcutAdvisor，检查大致包括：

1. `ClassFilter.matches(targetClass)`；
2. 遍历目标类及其相关接口方法；
3. 找到至少一个 `MethodMatcher.matches(method, targetClass)` 即认为 Advisor 对 Bean 适用。

IntroductionAdvisor 会先参与检查，因为引介出来的新接口可能影响其他方法匹配器对“是否存在 introductions”的判断。

### 每次方法调用前：方法级组链

代理已经存在后，`DefaultAdvisorChainFactory` 针对当前 Method 重新按 Advisor 顺序筛选并适配拦截器。静态结果可缓存；声明为运行时匹配的 Matcher 还会在 `proceed()` 到达时查看本次参数。

因此下面两件事可以同时成立：

- `getBean` 返回的是代理；
- 调用某个不匹配的方法时，一个业务通知也没有执行。

## 方法匹配看到哪个 Method

代理入口拿到的方法可能来自接口、桥接方法或代理子类，而业务注解通常写在实现类最具体的方法上。Spring 会结合 targetClass、桥接方法解析和“最具体方法”查找来帮助 Pointcut 与注解工具定位真实声明。

自定义 MethodMatcher 不应武断地只检查 `method.getDeclaringClass()`，否则 JDK 代理下可能只看到接口声明并漏掉实现类注解。优先复用 Spring 的 `AopUtils.getMostSpecificMethod`、`BridgeMethodResolver` 和合并注解工具。

## 静态与动态 MethodMatcher

```java
class AmountMatcher extends DynamicMethodMatcherPointcut {
    // 静态阶段先按方法名筛选
    // 运行时阶段再读取 args[0] 的金额
}
```

动态匹配适合确实依赖参数的横切规则，但它会在每次调用中增加判断。能由类型、方法签名或固定注解决定的范围，应尽量保持静态。

动态 Pointcut 也不等同于安全边界。参数校验、权限和审计仍需明确失败策略，不能因为“某次未匹配”默默放过本应强制执行的规则。

## Advisor 顺序如何理解

把链画成括号最直观：

```text
Advisor A before
  Advisor B before
    target
  Advisor B after
Advisor A after
```

越靠前的 Advisor 越像外层括号。自动代理创建器会对 eligible Advisors 排序，常见依据包括 `PriorityOrdered`、`Ordered`、`@Order` 适配值，以及 AspectJ 感知实现的切面内优先级规则。

不要依赖：

- 容器扫描文件顺序；
- 反射返回方法的偶然顺序；
- 两个同优先级切面当前碰巧稳定的日志顺序。

若顺序影响事务、重试或鉴权语义，应给出显式且不同的顺序值，并用行为测试固定关键嵌套关系。

## 自调用为什么绕过代理

外部调用是：

```text
client
  → proxy.outer()
      → interceptor chain
          → target.outer()
```

而 `target.outer()` 内部写 `this.inner()` 时，`this` 是正在执行的真实目标对象：

```text
target.outer()
  → this.inner()
      → target.inner()
```

调用没有回到 proxy，所以代理没有机会重新查 Pointcut 或创建第二条 MethodInvocation。`inner` 上即使存在 `@Transactional`、`@Cacheable`、`@Async` 或自定义切点，也不会因为这次 `this` 调用单独触发对应拦截器。

### 为什么换成 CGLIB 仍不自动解决

“CGLIB 是子类，所以内部虚调用一定能再次拦截”忽略了 Spring 代理与 target 的分工。Spring 的代理入口从 TargetSource 取得真实 target，再在链末端调用它；业务方法执行时的 `this` 仍是该 target。它内部直接调用仍留在目标对象上。

此外 final/private 方法本就不能通过子类覆盖。选择 CGLIB 的理由应是类型暴露与接口条件，不应把它当成自调用修复开关。

## 自调用的首选解决方式：拆分职责

把需要独立横切边界的方法移动到另一个 Bean：

```text
OrderFacade.confirm()
  → paymentService.pay()   // 外部 Bean 调用，经过 paymentService 代理
```

优点：

- 依赖方向清晰，不依赖 ThreadLocal；
- 单元测试可以直接表达两个职责；
- 事务边界与领域边界更接近；
- 异步线程、事件回调和定时任务中语义仍然明确。

若两个方法实际上必须处于同一职责，可以重新评估切点是否放错层级，而不是机械地让所有内部调用再次进代理。

## `exposeProxy` 与 `AopContext.currentProxy()`

开启 `exposeProxy` 后，JDK/CGLIB 代理入口会：

```text
进入调用
  → AopContext.setCurrentProxy(proxy)，保存旧值
  → 执行整条拦截器链与 target
  → finally 恢复旧值
```

目标方法可以显式写：

```java
((AtlasService) AopContext.currentProxy()).inner(value);
```

这次调用重新经过 proxy，因此 `inner` 的匹配通知会执行。

### 使用边界

- 默认 `exposeProxy=false`，因为绝大多数业务不需要这份 ThreadLocal 成本。
- 只能在当前线程正处于该代理调用栈时取得；在外部直接调用会抛 `IllegalStateException`。
- 线程切换后不会自动传播到 `@Async`、线程池任务或新线程。
- 嵌套代理会保存并恢复旧代理；不要把取得的引用缓存起来跨调用使用。
- 业务类因此依赖 Spring AOP API，降低可移植性和可测试性。
- JDK 代理只能强转为它暴露的接口；不能假设 `currentProxy()` 是目标实现类。

所以 `AopContext` 适合少量无法立即重构、且边界已经被测试覆盖的场景，不应成为普通业务调用方式。

## 注入“自身代理”也要谨慎

另一种做法是通过 `ObjectProvider<AtlasService>`、延迟注入或拆出的接口取得代理再调用。这避免直接依赖 `AopContext`，但可能引入自依赖、循环依赖与初始化顺序问题。

如果采用，应满足：

1. 明确注入的是代理接口，不是裸 target；
2. 使用延迟获取，避免构造器循环；
3. 测试容器配置关闭循环引用时的行为；
4. 不把这种技巧扩散到多个层级。

长期仍应优先通过拆 Bean 让调用天然跨越代理边界。

## 哪些方法天然受代理能力限制

| 场景 | JDK 代理 | CGLIB 代理 |
| --- | --- | --- |
| 接口公开方法 | 可代理 | 可代理（目标实现可覆盖时） |
| 目标类新增但接口没有的方法 | 客户端无法通过接口代理调用 | 可代理（可见且可覆盖时） |
| final 方法 | 不影响接口代理分派本身，但目标调用仍是该实现 | 不能覆盖拦截 |
| private 方法 | 不在接口契约中 | 不能覆盖拦截 |
| final 类 | 可基于其接口使用 JDK 代理 | 不能生成子类代理 |
| 构造器 | 不属于方法代理调用链 | 不属于普通 AOP 方法拦截 |

所谓“public 方法才有事务”还涉及具体 Advisor/TransactionAttributeSource 的公开方法策略，不能只归因于 JDK 或 CGLIB。进入事务专题时应再按 Spring 版本核对事务切点配置。

## 代理对象的类型、equals 与调试显示

- JDK 代理的 `getClass()` 是生成的 `$Proxy...`，不是目标类；按实现类注入可能失败。
- CGLIB 代理类名通常带 `$$EnhancerBySpringCGLIB$$` 等标记，但名称格式不是稳定契约。
- 使用 `AopUtils.isAopProxy/isJdkDynamicProxy/isCglibProxy` 判断代理形态。
- 使用 `AopProxyUtils.ultimateTargetClass` 取得目标类型线索，但不要为业务逻辑随意解包 target，这会绕过通知。
- `equals/hashCode` 在代理中有专门语义；不要用代理实现类名称或内存地址作为持久化身份。

## 调试自调用的最短路径

1. 在外部调用前打印 `AopUtils.isAopProxy(bean)`，确认拿到的不是手工 `new` 对象。
2. 在代理入口检查外部 `outer` 是否有非空 chain。
3. 在 `outer` 中断住，确认 `this` 与外部 proxy 是否同一引用。
4. 给 `inner` 对应 interceptor 条件断点；若 `this.inner` 不命中，行为符合代理模型。
5. 临时使用跨 Bean 调用或实验专用 `exposeProxy` 对照，验证 Pointcut 本身是否正确。
6. 最终修复优先调整对象边界，并用测试覆盖。

配套测试 `shouldExposeSelfInvocationBoundary` 会在同一个代理上对比 `this.inner` 与 `currentProxy().inner`，详见[断点实验](./debug-lab.md)。
