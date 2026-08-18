# Reflection 与 JDK Dynamic Proxy 断点实验手册

实验入口：

```text
labs/jdk-labs/src/main/java/
  io/github/javasourceatlas/jdk/runtime/ReflectionProxyDebugLab.java
```

测试入口：

```text
labs/jdk-labs/src/test/java/
  io/github/javasourceatlas/jdk/runtime/ReflectionProxyBehaviorTest.java
```

实验只使用 Java 8 公共 API，以 `--release 8` 编译，可以在 JDK 8 和 17 上运行。自动测试验证公开行为，不读取 `Method.methodAccessor`、Proxy cache 或生成类静态字段；这些私有对象只在调试器附加匹配版本源码后观察。

## 直接运行

使用当前 `JAVA_HOME`：

```bash
mvn -pl labs/jdk-labs -DskipTests compile exec:java \
  -Dexec.mainClass=io.github.javasourceatlas.jdk.runtime.ReflectionProxyDebugLab
```

只运行本专题测试：

```bash
mvn -pl labs/jdk-labs \
  -Dtest=io.github.javasourceatlas.jdk.runtime.ReflectionProxyBehaviorTest \
  test
```

显式切换 JDK 时：

```bash
JAVA_HOME=/path/to/jdk8 \
PATH=/path/to/jdk8/bin:$PATH \
mvn -pl labs/jdk-labs -Dtest=ReflectionProxyBehaviorTest test
```

```bash
JAVA_HOME=/path/to/jdk17 \
PATH=/path/to/jdk17/bin:$PATH \
mvn -pl labs/jdk-labs -Dtest=ReflectionProxyBehaviorTest test
```

先用 `mvn -version` 确认 Maven 实际 JVM。只替换 shell 中的 `java` 命令而未设置 `JAVA_HOME`，可能仍让 Maven 跑在另一版本。

## 场景一：成员发现、参数转换与私有访问

运行 `observeMetadataAndInvoke()`：

1. 用 `GreetingTarget.class.getMethod("inheritedLabel")` 找到父类 public 方法。
2. 用精确参数类型找到 `welcome(String,long)`。
3. 传入 `Integer.valueOf(2)` 作为 long 形参，观察拆箱后拓宽。
4. 用 `getDeclaredMethod` 找到当前类 private `secret`，再对项目自有类调用 `setAccessible(true)`。

建议断点：

| 位置 | 观察变量 | 重点 |
| --- | --- | --- |
| `Class.getMethod` | `name`、`parameterTypes` | public 搜索会递归父类/接口 |
| `Class.getDeclaredMethod` | 同上 | 只搜索当前类声明 |
| `Class.privateGetDeclaredMethods` | `publicOnly`、局部变量 `rd`、`res` | 缓存 root Methods；源码中没有名为 ReflectionData 的局部变量 |
| `Method.copy` | `this.root`、`res.root` | 查询结果是副本，root 用于共享 |
| `Method.invoke` | `obj`、`args`、`override` | 访问检查在 accessor 前 |
| `GreetingTarget.welcome` | `times` | 已成为 long 2 |

实验不先断言 private 调用必然失败。JDK 11+ nestmate 访问和测试类组织可能影响自有嵌套类型的默认访问结果；专题要验证的是成功设置 accessible 后的公开调用行为，而不是把特定类嵌套关系写成跨版本结论。

## 场景二：Method 副本共享 accessor 与 17 次 inflation

运行 `observeMethodAccessorReuseAndInflation()`。该场景使用此前没有调用过的 `InflationTarget.ping(String)`，避免其他实验提前增加内部计数：

```text
firstCopy  = InflationTarget.class.getMethod("ping", String.class)
secondCopy = InflationTarget.class.getMethod("ping", String.class)

firstCopy  调用 1 次
secondCopy 调用 16 次
总计进入同一成员 17 次
```

两个公开查询结果满足 `firstCopy != secondCopy`、`firstCopy.equals(secondCopy)`。JDK 8 中，第一个副本首次调用会创建 accessor 并传播到共同 root；第二个副本进入 `acquireMethodAccessor()` 时从 `root.getMethodAccessor()` 复用它。建议断点顺序：

| JDK 8 位置 | 观察变量 | 预期 |
| --- | --- | --- |
| `Method.acquireMethodAccessor` | `this`、`root`、`tmp` | 第一个副本新建，第二个副本从 root 得到非 null tmp |
| `ReflectionFactory.newMethodAccessor` | `noInflation`、`inflationThreshold` | 默认创建 Native + Delegating 组合 |
| `NativeMethodAccessorImpl.invoke` | `numInvocations`、`method`、`parent` | 第 1..15 次 native；第 16 次生成并换 delegate |
| `DelegatingMethodAccessorImpl.setDelegate` | `delegate` | 第 16 次切成 generated accessor |
| `InflationTarget.ping` | `invocations` | 第 17 次通常已从 generated accessor 进入 |

系统属性 `sun.reflect.noInflation` 或 `sun.reflect.inflationThreshold` 会改变内部轨迹。实验只固定完成 17 次真实调用，不把某个内部 accessor 类名写进自动断言；JDK 21 的主路径已经改用 MethodHandle accessor。

## 场景三：目标异常包装

运行 `observeTargetException()`。目标 `fail("boom")` 抛 `IOException`，外层 `Method.invoke` 抛 `InvocationTargetException`，其 cause 是目标异常。

断点顺序：

```text
Method.invoke
  -> MethodAccessor.invoke
  -> GreetingTarget.fail
  -> 目标 IOException
  -> InvocationTargetException
  -> 实验 catch 并读取 getCause()
```

分别记录三类失败：

- 把 target 改成不兼容对象：进入目标前 `IllegalArgumentException`。
- 去掉可访问授权并调用不可访问成员：访问层 `IllegalAccessException`。
- 保持 target/参数正确，让目标自己抛错：`InvocationTargetException(cause)`。

不要把前两类也归到“业务方法执行失败”。

## 场景四：代理类缓存与接口顺序

运行 `observeProxyCreationAndCache()`：

```text
[GreetingService, Marker] -> proxy Class A
[GreetingService, Marker] -> 仍为 Class A
[Marker, GreetingService] -> proxy Class B
```

实验只断言 Class 身份、父类、接口顺序和 `Proxy.isProxyClass`。生成类的完整名称和编号只打印供观察，不进入测试，因为 `$Proxy0/$Proxy1` 会受进程中其他代理创建顺序影响，JDK 9+ 包与模块也不同。

首次组合建议断点：

| JDK 8 位置 | 观察内容 |
| --- | --- |
| `Proxy.newProxyInstance` | cloned `intfs`、loader、handler |
| `Proxy.getProxyClass0` | 接口数量检查和 cache 调用 |
| `WeakCache.get` | cacheKey、subKey、Factory/Value |
| `Proxy.ProxyClassFactory.apply` | loader 可见性、接口身份、proxyPkg |
| `ProxyGenerator.generateClassFile` | proxyMethods、fields、methods |
| `Proxy.defineClass0` | proxyName、loader、class bytes 长度 |

第二个同序实例应在 cache 路径返回已有 Class，不再进入生成器。逆序列表应形成不同 subKey 并生成另一 Class。

## 场景五：生成代理方法到 InvocationHandler

运行 `observeProxyDispatch()`。实验使用具名的 `ForwardingInvocationHandler.invoke(...)`，可以直接设置源码行断点，不需要尝试在抽象的 `InvocationHandler.invoke` 接口声明处断行。handler 处理两类方法：

1. `equals/hashCode/toString` 根据 `method.getDeclaringClass() == Object.class` 单独处理，避免对 proxy 递归。
2. 接口方法通过 `method.invoke(target, args)` 转发真实 target，并解包 `InvocationTargetException.getCause()`。

依次调用：

```text
proxy.welcome("atlas", 3)
proxy.defaultLabel()
proxy.toString()
proxy.equals(proxy)
proxy.fail("proxy-boom")
```

五次都会进入同一个 handler。default 方法之所以得到接口默认文本，是 handler 把 Method 调到真实 `GreetingTarget`；并不是生成代理类自动绕过了 handler。`fail` 则由真实 target 抛出 IOException，`Method.invoke` 先包装成 `InvocationTargetException`，handler 再抛出同一个 cause，接口调用方最终看不到反射包装层。

建议在 handler 入口观察：

| 调用 | `method.declaringClass` | `args` |
| --- | --- | --- |
| `welcome` | `GreetingService` | `{"atlas", Long(3)}` |
| `defaultLabel` | `GreetingService` | JDK 8 通常为 null |
| `toString` | `Object` | null |
| `equals` | `Object` | `{proxy}` |
| `fail` | `GreetingService` | `{"proxy-boom"}` |

不要在调试器表达式中直接求值 `proxy.toString()`，这会再次命中断点，看起来像 handler 无故重复进入。查看 `proxy.getClass().getName()` 或使用不调用代理方法的身份信息。

## 场景六：重复签名的前置接口优先

运行 `observeDuplicateMethodPrecedence()`。`FirstView.identity` 与 `SecondView.identity` 名称、参数和返回类型相同。

```text
interfaces = [FirstView, SecondView]
通过 SecondView 引用调用 identity
handler 仍收到 declaringClass == FirstView
```

反转接口数组后，代表 Method 变为 `SecondView.identity`。调用点静态引用类型不会传进生成代理方法，生成类无法据此在同一个签名入口中选择另一 Method。

推荐断点：

1. `ProxyGenerator.generateClassFile` 的接口循环。
2. `ProxyGenerator.addProxyMethod`。
3. 相同 `name + parameterDescriptors` 的 `sigmethods`。
4. `ProxyMethod.fromClass` 与最终静态 Method 字段初始化。
5. handler 入口的 `method.getDeclaringClass()`。

若把两个接口顺序当作无序 Set 处理，这个实验会直接暴露语义变化。

## 场景七：受检异常边界

运行 `observeCheckedExceptionBoundary()`。同一个 handler 始终抛同一个 `IOException`：

```text
DeclaredFailure.execute() throws IOException
  -> 调用者直接收到同一个 IOException

NoDeclaredFailure.execute()
  -> 生成代理方法捕获 Throwable
  -> new UndeclaredThrowableException(IOException)
```

断在 `ProxyGenerator.ProxyMethod.generateMethod` 时，观察 `computeUniqueCatchList(exceptionTypes)` 如何为声明异常生成直接 rethrow 的异常表入口，并用最终 `Throwable` catch 包装其余异常。

自动测试还覆盖 handler 返回值检查：primitive `int` 方法若收到 null，生成方法拆箱时抛 `NullPointerException`；若收到 String，强转 wrapper 时抛 `ClassCastException`。这两类错误发生在 handler 返回之后。

## 十二个行为测试分别保证什么

| 测试 | 稳定行为 |
| --- | --- |
| `shouldSeparatePublicMemberSearchFromDeclaredMemberSearch` | public 继承搜索与当前类声明搜索不同 |
| `shouldApplyReflectionConversionsWithoutPackingTargetVarargs` | 拆箱拓宽、void 返回、窄化拒绝、目标 varargs 不自动打包 |
| `shouldWrapTargetFailureInInvocationTargetException` | 目标异常保存在反射包装 cause 中 |
| `shouldInvokePrivateProjectMethodAfterSetAccessible` | 自有类 private 方法授权后可调用 |
| `shouldCacheProxyClassByLoaderAndOrderedInterfaces` | loader + 有序接口列表决定代理 Class |
| `shouldDispatchInterfaceInvocationToHandler` | proxy、Method、装箱 args 完整进入 handler |
| `shouldDispatchDefaultMethodToHandler` | default 方法先进入 handler，handler 可覆盖默认体结果 |
| `shouldUnwrapRealTargetFailureInsideHandler` | 反射转发真实 target 后传播同一个业务异常 cause |
| `shouldDispatchObjectMethodsToHandler` | Object 三方法进入 handler |
| `shouldUseForemostInterfaceMethodForDuplicateSignature` | 最前接口决定代表 Method |
| `shouldEnforceProxyCheckedExceptionContract` | 声明异常直传，未声明受检异常包装 |
| `shouldValidateHandlerResultAtGeneratedProxyMethod` | 生成方法执行返回强转和拆箱 |

测试刻意不断言：

- `$ProxyN` 的 N 或包名；
- MethodAccessor 私有类名；
- inflation 的固定次数；
- handler 无参数调用的 args 在所有 JDK 都是同一数组对象；
- proxy cache 的具体 Map 层级；
- 反射或代理相对直接调用的固定性能倍数。

## 观察 JDK 8 inflation

DebugLab 的 `observeMethodAccessorReuseAndInflation()` 已经对同一 root 成员完成 17 次真实调用，无需在 IDE Evaluate 临时补循环。设置断点：

```text
sun.reflect.NativeMethodAccessorImpl.invoke
sun.reflect.MethodAccessorGenerator.generateMethod
sun.reflect.DelegatingMethodAccessorImpl.setDelegate
```

预期默认轨迹：

```text
第 1..15 次：delegate=Native，执行 invoke0
第 16 次：生成 accessor，setDelegate，当前次仍执行 invoke0
第 17 次：Delegating 转到 generated accessor
```

如果进程设置了 `sun.reflect.noInflation` 或修改 threshold，轨迹会不同。观察前检查 JVM 参数，不把实验结果当公开规范。

## JDK 17 与 21 断点迁移

JDK 17 把内部包改为：

```text
jdk.internal.reflect.ReflectionFactory
jdk.internal.reflect.NativeMethodAccessorImpl
jdk.internal.reflect.DelegatingMethodAccessorImpl
jdk.internal.reflect.MethodAccessorGenerator
```

inflation 主线仍可观察，但不要从应用代码 import 或反射读取这些封装类。

JDK 21 默认优先关注：

```text
java.lang.reflect.Method.invoke
jdk.internal.reflect.ReflectionFactory.newMethodAccessor
jdk.internal.reflect.MethodHandleAccessorFactory.newMethodAccessor
jdk.internal.reflect.DirectMethodHandleAccessor.invoke
```

如果看到 legacy native accessor，先检查 VM 初始化阶段和内部配置，不能直接得出“JEP 416 未生效”。

动态代理在 JDK 17/21 关注：

```text
java.lang.reflect.Proxy.getProxyConstructor
java.lang.reflect.Proxy.ProxyBuilder
java.lang.reflect.Proxy.ProxyBuilder.defineProxyClass
java.lang.reflect.ProxyGenerator
```

模块化版本用 `ClassLoaderValue` 等结构替代 JDK 8 WeakCache 细节，并为代理类映射动态模块；公开缓存语义和接口顺序仍可用测试验证。

## 可选：保存生成代理类

在独立实验目录运行，避免把 `$Proxy*.class` 散落到仓库：

```bash
# JDK 8
java -Dsun.misc.ProxyGenerator.saveGeneratedFiles=true ...

# JDK 9+
java -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true ...
```

保存后用当前 JDK 的 `javap` 查看：

```bash
javap -c -p path/to/GeneratedProxy.class
```

重点找：

- 父类 `Proxy` 与接口顺序；
- `Method` 静态字段；
- 构造器对 `Proxy.<init>(InvocationHandler)` 的调用；
- 参数 wrapper `valueOf`；
- `InvocationHandler.invoke`；
- 返回 `checkcast` 与 primitive unbox；
- `UndeclaredThrowableException` catch 分支。

属性名、输出路径和生成字节码是实现细节。实验完成后删除临时 class 文件，不纳入项目源码或测试基线。

## 调试结束检查

1. 清除 `sun.reflect.*` 和 proxy save-generated-files JVM 参数。
2. 确认没有为了断点给项目增加 `--add-opens java.base/...=ALL-UNNAMED`。
3. 确认 handler 的日志表达式不会调用 proxy 的 toString/equals/hashCode。
4. 对照运行的 JDK 版本加载对应 `src.zip`，不要在 JDK 21 会话附 JDK 8 行号。
5. 自动测试只保留公开行为断言，私有字段观察留在本文步骤中。
