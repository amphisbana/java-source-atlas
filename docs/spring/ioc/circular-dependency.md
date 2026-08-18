# 循环依赖：三级缓存解决了哪一段时间差

## 源码入口

- 缓存查询：`DefaultSingletonBeanRegistry.getSingleton(String, boolean)`
- 提前工厂注册：`DefaultSingletonBeanRegistry.addSingletonFactory(...)`
- 创建主干：`AbstractAutowireCapableBeanFactory.doCreateBean(...)`
- 早期引用：`AbstractAutowireCapableBeanFactory.getEarlyBeanReference(...)`
- 完整单例注册：`DefaultSingletonBeanRegistry.addSingleton(...)`

## 先限定问题

Spring 5.3.39 的三级缓存主要处理：**singleton Bean 已经实例化、尚未完成属性填充和初始化时，另一个 Bean 又依赖它**。

它不是通用环检测器，也不会让所有循环设计自动可用。典型可解场景是单例 A、B 通过 Setter 或字段互相依赖；典型不可解场景是 A 和 B 的构造器参数互相依赖。

## 三个缓存分别保存什么

| 层级 | 5.3.39 字段 | 保存内容 | 何时出现 |
| --- | --- | --- | --- |
| 一级 | `singletonObjects` | 完成创建、可正常使用的单例 | 初始化完成并正式注册后 |
| 二级 | `earlySingletonObjects` | 已经物化的一次早期引用 | 第一次真正需要早期引用时 |
| 三级 | `singletonFactories` | 可生成早期引用的 `ObjectFactory` | 原始实例化后、属性填充前 |

二级缓存避免同一个早期 Bean 被重复生成；三级缓存把“是否需要、由谁包装”延迟到循环依赖真的发生时。三级工厂调用 `getEarlyBeanReference`，自动代理创建器可在这里提供早期代理。

在 5.3.39 中，`getSingleton(beanName, allowEarlyReference)` 的查询顺序可以展开为：

```text
singletonObjects.get(beanName)
  └─ 未命中，且 beanName 正在创建
       └─ synchronized (singletonObjects)
            ├─ 再查 singletonObjects
            ├─ 再查 earlySingletonObjects
            └─ 仍未命中，且 allowEarlyReference == true
                 ├─ singletonFactories.get(beanName)
                 ├─ factory.getObject()
                 ├─ earlySingletonObjects.put(beanName, earlyReference)
                 └─ singletonFactories.remove(beanName)
```

三级到二级是一次“物化并迁移”，不是复制。完成对象最终由 `addSingleton` 写入一级缓存，同时移除同名二级、三级条目。`registeredSingletons` 只保存已登记名称集合，不是第四级对象缓存。

## Setter 单例循环的完整调用链

设 A 有 `setB(B)`，B 有 `setA(A)`：

```text
getBean("A")
  ├─ 标记 A 正在创建
  ├─ 实例化 A
  ├─ singletonFactories["A"] = early-reference factory
  └─ populateBean(A)
       └─ getBean("B")
            ├─ 标记 B 正在创建
            ├─ 实例化 B
            ├─ singletonFactories["B"] = early-reference factory
            └─ populateBean(B)
                 └─ getBean("A")
                      └─ getSingleton("A", true)
                           ├─ 一级没有 A
                           ├─ A 正在创建，二级没有 A
                           ├─ 调用三级工厂得到 early A
                           ├─ earlySingletonObjects["A"] = early A
                           └─ 移除 singletonFactories["A"]
            ├─ 把 early A 注入 B
            ├─ 初始化 B
            └─ B 进入一级缓存
  ├─ 把完整 B 注入 A
  ├─ 初始化 A，并核对 early A 与最终暴露引用
  └─ A 进入一级缓存，清理它的二、三级条目
```

注意：容器没有先“完整创建 B 再回头修 A”。B 在创建中通过 A 的早期引用完成填充；A 随后拿到已经完成的 B。

## 缓存快照

| 时刻 | 一级缓存 | 二级缓存 | 三级缓存 | 正在创建集合 |
| --- | --- | --- | --- | --- |
| A 实例化前 | 无 A/B | 无 | 无 | A |
| A 实例化后 | 无 A/B | 无 | A factory | A |
| B 实例化后 | 无 A/B | 无 | A/B factory | A、B |
| B 请求 A 后 | 无 A/B | early A | B factory | A、B |
| B 初始化完成 | B | early A | 无 B factory | A |
| A 初始化完成 | A、B | 无 early A | 无 A/B factory | 无 |

`getSingleton(beanName, false)` 不允许从三级工厂创建早期引用。`doCreateBean` 在完成初始化后用这个模式检查早期引用是否已经存在，从而协调最终返回对象。

要把两个同名重载区分开：`getSingleton(beanName, ObjectFactory)` 负责完整单例的创建、失败清理和最终注册；`getSingleton(beanName, boolean)` 负责在创建过程中按一、二、三级顺序查询。前者包住整次 `createBean`，后者才是循环依赖回头找 A 时进入的缓存路径。

## 为什么构造器循环无法靠三级缓存解决

构造 A 必须先解析构造器参数 B；构造 B 又必须先得到 A。此时 A 的实例尚未产生，`doCreateBean` 还没有机会执行 `addSingletonFactory`。三级缓存里没有任何可返回的 A，因此容器报告 `BeanCurrentlyInCreationException`。

这不是“Spring 忘了支持构造器注入”，而是对象创建的时间顺序决定没有可暴露引用。

## 适用边界

| 场景 | 结果或风险 | 原因 |
| --- | --- | --- |
| singleton Setter/字段循环 | 通常可解析 | 实例化后可提前暴露引用 |
| 构造器循环 | 失败 | 参数解析发生在实例产生之前 |
| prototype 循环 | 失败 | prototype 不进入单例三级缓存 |
| 自定义 Scope 循环 | 取决于 Scope | 由 Scope 实现自己的缓存和生命周期 |
| `allowCircularReferences=false` | Setter 单例循环也失败 | 工厂不会注册提前引用 |
| 初始化后才包装、且早期引用为原对象 | 可能失败或注入错误版本 | 早期引用与最终代理不一致 |
| `@Lazy` / `ObjectProvider` 打断一边 | 可工作，但属于延迟依赖 | 注入的是代理或提供者，不是三级缓存消除设计环 |

Spring 的早期代理协作依赖 `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference`。并非任意自定义 BeanPostProcessor 都天然支持循环代理；只在初始化后换引用、却不实现一致的早期引用策略，可能触发原始对象注入检查。

### 早期 AOP 代理为什么只有一个

以 Spring AOP 常见的 `AbstractAutoProxyCreator` 为例，身份协调分为三步：

1. B 回头取 A 时，`getEarlyBeanReference(A#raw, "A")` 把 cache key 与原始 A 记录到 `earlyProxyReferences`，并调用 `wrapIfNecessary` 得到 `Proxy(A#raw)`。
2. A 稍后走到 `postProcessAfterInitialization`，自动代理创建器移除并比较这条记录；发现记录值就是当前原始 A，于是不再创建第二个代理，先返回原始 A。
3. `doCreateBean` 发现 `exposedObject == bean` 且二级缓存已有 early A，于是把 `exposedObject` 替换为那份早期代理。外层 `getSingleton(beanName, ObjectFactory)` 最终把同一代理写入一级缓存。

因此实验中可以稳定断言：

```text
B.a
  === RecordingAutoProxyCreator 记录的 earlyReference
  === context.getBean("A")
```

如果自定义处理器只在初始化后返回新包装对象，却没有实现相容的早期引用，B 可能已经拿到原始 A。此时 `allowRawInjectionDespiteWrapping` 默认为 false；若确有真实依赖方持有错误版本，容器会用 `BeanCurrentlyInCreationException` 阻止悄悄产生两个观察身份。打开该开关只会允许不一致，不会让原始引用自动升级成代理。

完整 18 帧对象与缓存变化见 [Bean 创建联动动画](./bean-creation.md#spring-bean-lifecycle-animation)。

## 关闭循环引用不等于 Spring Framework 6 默认行为

`AbstractAutowireCapableBeanFactory` 在 Spring Framework 5.3.39 中默认允许循环引用。Spring Boot 2.6 起默认把应用层配置 `spring.main.allow-circular-references` 设为 false，因此很多应用观察到默认失败。

这是 Boot 对底层工厂的配置策略，不应写成“Spring 5.3 或 Spring 6 的三级缓存被删除”。即使技术上能启用，新的业务设计仍应优先移除职责环。

当 `allowCircularReferences=false` 时，A 即使已经完成构造，`doCreateBean` 也不会为它执行 `addSingletonFactory`。B 再次请求 A 时只会看到“A 正在创建”，却找不到任何完整或早期引用，于是失败。开关改变的是提前暴露策略，不是等到最后才做一遍环检测。

## 断点建议

1. `DefaultSingletonBeanRegistry.beforeSingletonCreation`：观察 A、B 加入 `singletonsCurrentlyInCreation`。
2. `AbstractAutowireCapableBeanFactory.doCreateBean` 的 `earlySingletonExposure` 判断：确认 scope、开关和创建状态。
3. `addSingletonFactory`：观察三级缓存新增和二级缓存移除动作。
4. `getSingleton(beanName, true)`：仅对 `beanName == "setterA"` 条件断点，观察一级、二级、三级查找。
5. `getEarlyBeanReference`：确认返回原对象还是代理。
6. `addSingleton`：观察完整单例写入一级缓存，并清理同名二、三级条目。

## 公开契约与实现边界

应用可使用 Setter、`@Lazy`、`ObjectProvider` 和工厂配置开关，但不应把“三级缓存字段”作为业务 API。三个 Map 的名称、同步方式、代理协调检查都是 Spring 5.3.39 的实现细节。

公开契约也不承诺某个循环一定成功。成功与否还受 scope、注入时机、BeanPostProcessor、代理类型和容器配置影响。Spring 6.x 排查时应重跑本专题实验，并以对应版本的 `DefaultSingletonBeanRegistry` 与 `AbstractAutowireCapableBeanFactory` 为准。
