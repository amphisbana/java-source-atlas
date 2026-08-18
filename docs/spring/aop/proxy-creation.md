# 代理创建：从 BeanPostProcessor 到 JDK / CGLIB

## 源码入口

- `AbstractAutoProxyCreator.postProcessBeforeInstantiation(...)`
- `AbstractAutoProxyCreator.getEarlyBeanReference(...)`
- `AbstractAutoProxyCreator.postProcessAfterInitialization(...)`
- `AbstractAutoProxyCreator.wrapIfNecessary(...)`
- `AbstractAdvisorAutoProxyCreator.getAdvicesAndAdvisorsForBean(...)`
- `AbstractAutoProxyCreator.createProxy(...)`
- `ProxyCreatorSupport.createAopProxy()`
- `DefaultAopProxyFactory.createAopProxy(...)`

## 自动代理创建器是什么

`AbstractAutoProxyCreator` 同时是：

- `BeanPostProcessor`：可以在 Bean 初始化后把原对象替换为代理；
- `InstantiationAwareBeanPostProcessor`：可以在实例化前处理自定义 `TargetSource`；
- `SmartInstantiationAwareBeanPostProcessor`：可以为循环依赖提供早期代理引用；
- `AopInfrastructureBean`：自身属于 AOP 基础设施，不应再次被自动代理。

常见具体实现形成一条继承主线：

```text
AbstractAutoProxyCreator
  └─ AbstractAdvisorAutoProxyCreator
       ├─ DefaultAdvisorAutoProxyCreator
       └─ AspectJAwareAdvisorAutoProxyCreator
            └─ AnnotationAwareAspectJAutoProxyCreator
```

- `DefaultAdvisorAutoProxyCreator` 从 BeanFactory 中寻找普通 `Advisor`。
- `AnnotationAwareAspectJAutoProxyCreator` 在此基础上还能把 `@Aspect` Bean 中的方法构造成 Advisor。
- Spring 事务、缓存、异步等功能最终也会贡献 Advisor 或类似拦截基础设施，再复用同一代理主干。

## 常规创建时机：初始化后包装

IOC 创建 Bean 的后半段是：

```text
populateBean
  → initializeBean
      → applyBeanPostProcessorsBeforeInitialization
      → init callbacks
      → applyBeanPostProcessorsAfterInitialization
          → AbstractAutoProxyCreator.postProcessAfterInitialization
```

5.3.39 的核心判断可压缩为：

```java
if (bean != null) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    if (this.earlyProxyReferences.remove(cacheKey) != bean) {
        return wrapIfNecessary(bean, beanName, cacheKey);
    }
}
return bean;
```

这里比较早期引用，是为了避免同一个 Bean 在循环依赖路径已经产生代理后，又在初始化后重复包装。真正源码中的缓存和比较是实现细节；需要记住的契约是：容器最终应尽量让其他 Bean 与普通 `getBean` 得到一致的代理引用。

## `wrapIfNecessary` 的决策树

```text
wrapIfNecessary(bean, beanName, cacheKey)
  ├─ 自定义 TargetSource 已经处理？                 → 原样返回
  ├─ advisedBeans 已缓存 Boolean.FALSE？             → 原样返回
  ├─ isInfrastructureClass(beanClass)？               → 记为不代理，返回
  ├─ shouldSkip(beanClass, beanName)？                 → 记为不代理，返回
  ├─ getAdvicesAndAdvisorsForBean(...)                → 查适用通知
  │    └─ DO_NOT_PROXY？                               → 记为不代理，返回
  └─ createProxy(beanClass, beanName, specificInterceptors, targetSource)
       → 记为需要代理并返回 Proxy
```

### 为什么要有 `advisedBeans` 缓存

自动代理创建器可能在类型预测、早期引用和初始化后等多个阶段被询问。缓存保存“已经判断需要/不需要代理”的结果，减少重复扫描，也帮助不同生命周期回调保持一致。

它缓存的是实现判断，不是业务配置的公共查询 API。运行期间动态注册 Advisor 并不能保证所有已经创建的 singleton 自动重建代理。

### 哪些类属于基础设施

默认判断包括 `Advice`、`Pointcut`、`Advisor`、`AopInfrastructureBean`。若这些对象也被普通切面代理，会导致用于构造代理的零件再次进入构造代理流程。

`shouldSkip` 是留给子类的钩子。例如 AspectJ 感知实现需要避免把某些切面自身当成普通业务 Bean 处理。

## Advisor 从候选到适用

`AbstractAdvisorAutoProxyCreator` 的主要步骤是：

```text
getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource)
  → findEligibleAdvisors(beanClass, beanName)
      → findCandidateAdvisors()
      → findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName)
      → extendAdvisors(eligibleAdvisors)
      → sortAdvisors(eligibleAdvisors)
```

1. 候选发现回答“容器中有哪些 Advisor”。
2. 适用性检查先看 ClassFilter，再检查至少一个方法是否匹配。
3. 扩展钩子允许插入额外 Advisor。
4. 排序确定链的外层到内层顺序。

“发现了 Advisor”不等于“每个方法都会执行它”。Bean 级适用性只是决定是否值得创建代理；具体方法调用仍要按 MethodMatcher 组装链。

## `createProxy` 如何构造代理配置

5.3.39 中 `AbstractAutoProxyCreator.createProxy` 的主干是：

```text
在 BeanFactory 中暴露原始 targetClass（便于类型工具识别）
  → new ProxyFactory()
  → proxyFactory.copyFrom(this)            // 复制 frozen、exposeProxy 等配置
  → 决定代理接口或 proxyTargetClass
  → buildAdvisors(beanName, specificInterceptors)
  → proxyFactory.addAdvisors(...)
  → proxyFactory.setTargetSource(targetSource)
  → customizeProxyFactory(proxyFactory)
  → 设置 preFiltered / frozen
  → proxyFactory.getProxy(classLoader)
```

### `evaluateProxyInterfaces` 不是“看到任何接口就照单全收”

当没有强制类代理时，Spring 会评估目标实现的接口。回调、Aware、内部语言接口等不代表稳定业务契约，可能被排除。存在合理代理接口时加入 `ProxyFactory`；否则切换为目标类代理。

因此排查代理类型时应直接观察：

- `proxyFactory.getProxiedInterfaces()`；
- `proxyFactory.isProxyTargetClass()`；
- 最终 `targetClass`；
- 配置是否被多个 `@Enable...` 注册器合并升级。

不要只看目标类源码里是否写了 `implements`。

## `DefaultAopProxyFactory` 的选择规则

Spring 5.3.39 的核心条件可以表达为：

```text
if (optimize || proxyTargetClass || 没有用户提供的代理接口) {
    if (targetClass 是接口 || targetClass 本身已是 JDK Proxy 类) {
        return JdkDynamicAopProxy
    }
    return ObjenesisCglibAopProxy
}
return JdkDynamicAopProxy
```

注意两个反直觉点：

1. `proxyTargetClass=true` 并不意味着“无条件 CGLIB”。如果目标类型本身是接口或已经是 JDK Proxy 类，仍可能选择 JDK 实现。
2. “没有用户代理接口”与“Java 反射能看到零个接口”不是完全同义；Spring 自己的标记接口不算业务代理接口。

### 对照表

| 配置与目标 | 典型结果 | 调用方能按什么类型使用 |
| --- | --- | --- |
| 有合理业务接口，默认配置 | JDK 动态代理 | 代理接口 |
| 有接口，`proxyTargetClass=true` | CGLIB 子类代理 | 目标类及其接口 |
| 无合理业务接口 | CGLIB 子类代理 | 目标类可见方法 |
| targetClass 本身是接口 | JDK 动态代理 | 该接口 |

## JDK 代理与 CGLIB 不是功能高低关系

### JDK 动态代理

- 代理类实现配置的接口并继承 `java.lang.reflect.Proxy`；
- Spring 的 `JdkDynamicAopProxy` 是 InvocationHandler；
- 调用方应面向接口，不可把代理强转为目标实现类；
- 接口边界清楚，通常更适合已有服务接口的代码。

### CGLIB 代理

- Spring 使用重打包的 CGLIB 生成目标类子类；
- Spring 5.3 默认使用 `ObjenesisCglibAopProxy`，尽量避免通过目标构造器创建代理壳；
- final 类不能被继承，final/private 方法不能被覆盖拦截；
- 包可见性、类加载器和 Java 模块开放策略都可能影响生成类的访问；
- 目标对象内部自调用仍不会因此自动穿过 Spring 的拦截器链。

## 早期代理引用与循环依赖

当 singleton Setter/字段循环依赖触发三级缓存时，BeanFactory 可以调用：

```text
AbstractAutoProxyCreator.getEarlyBeanReference(bean, beanName)
  → 记录 earlyProxyReferences
  → wrapIfNecessary(bean, beanName, cacheKey)
  → 返回早期代理
```

后续 `postProcessAfterInitialization` 识别该原始 Bean 已提供过早期代理，不再创建第二个代理。

这不代表“AOP 可以解决所有循环依赖”：

- 构造器循环发生在可产生早期引用之前；
- prototype 没有 singleton 三级缓存语义；
- 其他处理器若在早期与最终阶段返回不一致包装，仍可能触发一致性异常；
- Spring Boot 是否允许循环引用还有独立配置策略。

## 实例化前代理不是常规主线

`postProcessBeforeInstantiation` 可以为自定义 `TargetSource` 提前创建代理，从而跳过常规 Bean 实例化。这常用于池化、热切换等特殊 TargetSource 场景。

普通 singleton AOP 更常见的是：先按 IOC 流程创建 target，再在初始化后 `wrapIfNecessary`。调试时若一上来就在实例化前分支等待所有代理，往往会错过主路径。

## 建议断点

| 断点 | 条件 | 重点变量 |
| --- | --- | --- |
| `postProcessAfterInitialization` | `beanName.equals("atlasService")` | `bean`、`cacheKey`、`earlyProxyReferences` |
| `wrapIfNecessary` | 同上 | `beanClass`、`advisedBeans`、`specificInterceptors` |
| `findEligibleAdvisors` | `beanClass` 为业务类 | `candidateAdvisors`、`eligibleAdvisors` |
| `createProxy` | 业务 Bean | `proxyFactory`、`targetSource`、代理接口 |
| `DefaultAopProxyFactory.createAopProxy` | 无需条件 | 三个布尔条件与 `targetClass` |
| `JdkDynamicAopProxy.getProxy` / `CglibAopProxy.getProxy` | 无需条件 | 最终生成的代理 Class |

## Spring 6.x 核对点

Spring 6 仍保留自动代理、Advisor、JDK/CGLIB 选择这套核心模型，但 Java 17 基线、AOT、类加载与模块边界使“生成代理类是否可行”更值得关注。具体私有缓存、辅助方法拆分和 CGLIB 回调优化可能随 6.x 小版本变化。

升级时应复跑 [行为实验](./debug-lab.md)，再在目标版本的 `AbstractAutoProxyCreator` 与 `DefaultAopProxyFactory` 上核对实现，不要依赖本文展示的 5.3.39 私有字段形状。
