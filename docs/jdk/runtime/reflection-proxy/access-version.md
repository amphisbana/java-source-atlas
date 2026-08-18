# 访问控制与版本差异：语言权限、模块开放和执行器更替

反射访问失败经常被简单归因于“private”。从 JDK 9 开始，至少要同时判断 Java 语言访问、运行时包、class loader、模块 exports/opens，以及调用 API 的版本。动态代理的生成位置和 default 方法能力也受模块化演进影响。

## 先分清四道边界

```text
成员查找边界：getMethod 还是 getDeclaredMethod
        ↓
Java 语言访问：public / protected / package / private
        ↓
运行时类型边界：声明类、接收者、class loader 身份
        ↓
模块边界：exports 供普通链接，opens 供深反射
```

`setAccessible(true)` 只作用在“是否抑制语言访问检查”的反射能力上。它不能：

- 把 `getMethod` 搜不到的 private 方法变成可搜索；
- 让错误 class loader 中的同名 Class 变成同一个类型；
- 把不兼容接收者或参数强制转换成功；
- 绕过 JDK 9+ 未开放模块包的强封装；
- 改写 final/static/构造器等 JVM 语义。

## OpenJDK 8：override 标记与 SecurityManager

JDK 8 的 `AccessibleObject.setAccessible(true)` 先在存在 SecurityManager 时检查 `ReflectPermission("suppressAccessChecks")`，成功后设置内部 `override` 标记。

调用 `Method.invoke` 时：

```text
override == false
  -> quickCheckMemberAccess
  -> 必要时按 caller、声明类、接收者和 modifiers 做完整检查

override == true
  -> 跳过这层 Java 语言访问检查
  -> 仍由 MethodAccessor 校验接收者和参数
```

JDK 8 的 `isAccessible()` 只返回 suppress 标记，不回答“当前调用者现在能否访问这个成员”。名字容易让人误判，它在 Java 9 被 deprecated。

即使没有模块系统，也不应把 `setAccessible(true)` 当作默认扫描动作。优先使用 public contract；确需 private 访问时，在框架初始化期集中验证并给出明确失败信息。

## JDK 9+：exports 与 opens 不是一回事

Java Platform Module System 增加两类常见包可见性：

| 声明 | 主要用途 | 是否允许任意 private 深反射 |
| --- | --- | --- |
| `exports p` | 让其他模块编译/链接访问 `p` 中 public API | 否 |
| `exports p to m` | 只向指定模块导出 public API | 否 |
| `opens p` | 向其他模块开放 `p` 的运行时深反射 | 是，仍需反射 API 请求 suppress |
| `opens p to m` | 只向指定模块开放深反射 | 对指定模块是 |
| `open module x` | 模块内所有包运行时开放 | 是 |

一个包 exported 不等于 open。业务代码能正常 `new PublicType()`，不代表框架能 `setAccessible(true)` 读取它的 private 字段。

### setAccessible 何时会被拒绝

现代 JDK 会根据 caller module、declaring class、member modifiers 和包开放关系判断。对另一个 named module 的非开放包做深反射，`setAccessible(true)` 通常抛 `InaccessibleObjectException`。

可靠处理顺序：

1. 能用 public API 就不要深反射。
2. 自有模块由 `module-info.java` 精确 `opens package to framework.module`。
3. 无法修改目标模块时，在部署边界显式评估 `--add-opens source.module/package=target.module`。
4. 不要捕获异常后静默返回 null；这会把启动配置错误推迟成难定位的业务数据缺失。

`--add-exports` 与 `--add-opens` 目的不同。前者补普通 public 链接可见性，后者才针对深反射；随意把整个 JDK 开给 `ALL-UNNAMED` 会扩大维护和安全面。

## canAccess 与 trySetAccessible

Java 9 增加了更准确的能力 API：

### `canAccess(target)`

回答当前反射对象在当前调用上下文能否访问指定接收者：

- 实例成员传兼容实例；
- static 成员传 null；
- 不兼容 target 本身会触发参数错误；
- 结果综合默认访问规则与已成功的 suppress 标记。

它比旧 `isAccessible()` 更接近“此刻能否调用”。

### `trySetAccessible()`

尝试启用 suppress access checks：

- 成功返回 true；
- 模块边界不允许时返回 false，而不是像 `setAccessible(true)` 那样以 `InaccessibleObjectException` 表达普通失败；
- SecurityManager 或其他安全检查仍可能抛 `SecurityException`。

框架可以在启动阶段使用它构造清晰诊断：

```text
无法访问 com.example.model.Order.privateField
请在 module-info.java 添加：opens com.example.model to framework.module
```

不要把 false 解释成“字段不存在”，两者是不同问题。

## JDK 17：强封装成为实际迁移边界

JDK 9 到 16 曾提供过对部分 JDK 8 内部 API 的迁移宽限。JDK 17 的 JEP 403 强化 JDK 内部包封装，旧的 `--illegal-access` 宽松模式不再是可靠解决方案。

这对源码学习有两个直接影响：

- JDK 8 的核心反射 accessor 与 `ReflectionFactory` 实现已从 `sun.reflect` 迁入 `jdk.internal.reflect`，应用代码不应导入这些内部类；`sun.reflect` 下仍有 generics、annotation、misc 等其他实现，不能把整个包族概括为已迁走；
- 对 `java.base` 非开放包私有字段做 `setAccessible` 的旧实验可能在 JDK 8 成功、JDK 17 抛 `InaccessibleObjectException`。

本专题 DebugLab 只反射项目自己的类，不要求 `--add-opens`。观察 JDK 私有字段时应使用 IDE 调试器与匹配版本源码，而不是让测试用例依赖突破模块封装。

## Method.invoke 从 JDK 8 到 JDK 21

### JDK 8：native 到 generated accessor

```text
Method
  -> DelegatingMethodAccessorImpl
       -> NativeMethodAccessorImpl.invoke0
       -> 超过阈值后切换 GeneratedMethodAccessor
```

内部包是 `sun.reflect`，默认 inflation threshold 为 15。

### JDK 17：架构仍在，包和封装已变

JDK 17 的对应内部类位于 `jdk.internal.reflect`。`ReflectionFactory.newMethodAccessor` 仍可选择 native + delegating + generated accessor，inflation 解释对这个版本仍有观察价值。

但模块强封装意味着：业务代码不应通过反射读取 `Method.methodAccessor` 或强转内部类型来“验证优化”。这些都是非 API 实现，升级时可能立即失效。

### JDK 21：JEP 416 后默认走 MethodHandle accessor

JDK 18 的 JEP 416 用 MethodHandle 重新实现 core reflection；JDK 21 默认主线可概括为：

```text
Method.invoke
  -> acquireMethodAccessor
  -> ReflectionFactory.newMethodAccessor(method, callerSensitive)
  -> MethodHandleAccessorFactory
  -> DirectMethodHandleAccessor.invoke
  -> 已适配 MethodHandle 调用目标
```

它保留 Reflection 的公开参数转换、访问和异常契约，但不再默认依赖“先 JNI/native，调用 15 次后生成 accessor class”的历史性能策略。JDK 21 源码仍可能保留 legacy 配置或 VM 启动早期 fallback；不要把存在旧类等同于默认业务主路径。

| 结论 | 是否跨版本稳定 |
| --- | --- |
| 目标 primitive 返回由 `Method.invoke` 装箱 | 是 |
| 目标 Throwable 包装为 `InvocationTargetException` | 是 |
| 默认第 16 次生成 accessor | 只适用于 JDK 8/17 旧主线 |
| accessor 类名可用于监控业务 | 否 |
| 反射一定比 MethodHandle 慢固定倍数 | 否，必须在目标 JDK 实测 |

## 动态代理从 classpath 到 module-aware

### JDK 8

public 接口的代理类通常在 `com.sun.proxy`，non-public 接口让代理类落到其包。定义和访问主要由 class loader、运行时包与 SecurityManager 约束。

### JDK 9+

`Proxy` 需要为代理类选择模块和包，并确保生成类能读取接口模块、访问方法签名引用类型。核心规则可以这样把握：

- non-public 接口要求更严格的同包、同模块约束，代理类通常是 non-public；
- public 接口若处于正常导出包，代理类可位于 JDK 管理的动态代理模块并保持 public 可访问；
- public 接口若涉及非导出、非开放包，代理类可能位于封装的动态模块包，构造器与类型可访问性不能按 JDK 8 包名推断；
- JDK 17/21 的 `ProxyBuilder.validateProxyInterfaces` 会拒绝 hidden interface 和 sealed interface；运行时生成的任意 `$ProxyN` 不能被假定为 sealed 接口 permits 列表中的合法实现；
- 每个 loader 的动态代理模块、包名和 `$ProxyN` 编号都是实现细节。

因此现代代码应直接使用 `Proxy.newProxyInstance`。`Proxy.getProxyClass` 自 Java 9 起 deprecated，因为“先取得 Class，再自行反射构造”更容易暴露模块访问细节。

`Proxy.isProxyClass` 也不只是判断 `Proxy.class.isAssignableFrom(clazz)`。JDK 8 还确认 Class 存在于 proxy cache，现代 JDK 维护反向代理类记录；手写一个 `extends Proxy` 的类不能冒充 JDK 生成代理类。

## default 方法的版本边界

| 版本 | handler 收到 default 方法 | 标准方式调用默认实现 |
| --- | --- | --- |
| JDK 8 | 是 | 无公开专用 API；反射调用 proxy 会递归 |
| JDK 9-15 | 是 | 可用 MethodHandles 组合，但访问与模块规则复杂 |
| JDK 16+ | 是 | `InvocationHandler.invokeDefault(proxy, method, args)` |

`invokeDefault` 的语义接近从代理类显式调用某个接口 super 默认实现，而不是普通 virtual 调用。它验证 method 所属接口和当前代理接口图的合法性；接口升级新增更具体 default 实现后，旧选择可能不再合法。

## SecurityManager 的位置变化

JDK 8 的 `Method`、`Proxy` 和 Class 成员查询源码中存在大量 caller-sensitive 与 SecurityManager 检查。SecurityManager 自 JDK 17 起 deprecated for removal，现代部署不应再把它当作模块访问模型的替代品。

读源码时应分层：

```text
公开行为：访问失败、接口可见性、代理构造与调用
历史安全路径：SecurityManager permission checks
现代封装路径：module reads / exports / opens
```

即使某个新 JDK 构建仍保留兼容分支，也不代表应为新系统设计 SecurityManager policy。

## 调试属性也有版本差异

为了学习生成类，可以在独立实验进程中保存代理 class：

```bash
# OpenJDK 8 常见属性
-Dsun.misc.ProxyGenerator.saveGeneratedFiles=true

# JDK 9+ OpenJDK 常见属性
-Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true
```

这些是实现诊断属性，不属于 Java SE API。只在临时目录和匹配 JDK 上使用；不要把生成文件路径、类编号或属性可用性写进业务构建。

反射 accessor 也有 `sun.reflect.noInflation`、`sun.reflect.inflationThreshold` 等历史属性。它们适合验证 JDK 8/17 源码路径，不适合作为生产性能调优旋钮，更不能用于 JDK 21 主路径推理。

## 迁移检查清单

从 JDK 8 升级到 17/21 时，逐项核对：

1. 是否反射访问 JDK 内部类或第三方 named module 的 private 成员。
2. 目标模块能否用精确 `opens ... to ...` 表达框架需求。
3. 是否通过类名判断 `sun.reflect.GeneratedMethodAccessor`、`com.sun.proxy.$Proxy`。
4. 是否依赖 `setAccessible(true)` 永远成功，且把失败静默吞掉。
5. 是否手工使用 deprecated `Proxy.getProxyClass` 再访问构造器。
6. handler 是否用非标准 MethodHandles 技巧调用 default 方法，可否切到 `InvocationHandler.invokeDefault`。
7. 性能基准是否在目标 JDK 重新 warm-up 和测量，而不是复用 JDK 8 inflation 结论。
8. 测试是否只断言公开行为，不读取 accessor、cache 或代理 module 私有字段。

下一步进入 [断点实验手册](./debug-lab.md)，用同一套 Java 8 源码分别运行在 JDK 8 和 17，再根据当前 JDK 源码调整私有实现断点。
