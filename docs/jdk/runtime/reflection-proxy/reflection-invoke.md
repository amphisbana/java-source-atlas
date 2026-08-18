# Method：元数据发现、访问检查与 JDK 8 accessor

理解 `Method.invoke` 要把四件事分开：怎样找到成员、调用者是否允许访问、参数怎样适配、目标方法怎样真正执行。`Method` 把这些步骤汇入同一个公开入口，但源码内部并不是一次“按字符串查找并直接跳转”。

## getMethod 与 getDeclaredMethod 先解决查找范围

| API | 搜索范围 | 包含非 public | 包含继承成员 | 参数匹配 |
| --- | --- | --- | --- | --- |
| `getMethod(name, types)` | 当前类、父类和接口的 public 方法规则 | 否 | 是 | 参数 `Class` 精确匹配 |
| `getDeclaredMethod(name, types)` | 当前类直接声明的方法 | 是 | 否 | 参数 `Class` 精确匹配 |
| `getMethods()` | 可见 public 方法集合 | 否 | 是 | 返回数组，不保证适合依赖的顺序 |
| `getDeclaredMethods()` | 当前类直接声明的全部方法 | 是 | 否 | 返回数组，不保证声明顺序作为协议 |

“Declared”只描述成员由谁声明，不表示调用者自动获得访问权。拿到 private `Method` 后直接 `invoke` 仍可能得到 `IllegalAccessException`；拿到继承 public 方法也仍需满足声明类、模块和接收者等访问条件。

查找使用参数类型，不使用实参数值做重载决议：

```java
Method method = serviceClass.getMethod("save", String.class, long.class);
```

这里不会因为稍后传入 `Integer` 就改选另一个重载。反射调用发生前，调用者必须已经像编译器一样决定目标签名。若框架按运行时实参自己实现“最合适重载”，就要处理 null、装箱、拓宽、可变参数和桥接方法等规则，不能只比较类名。

## Class.ReflectionData 为什么返回副本

OpenJDK 8 的 `Class` 使用软引用 `ReflectionData` 缓存声明字段、方法、构造器以及 public 合并结果。缓存还记录 `classRedefinedCount`，类被 JVMTI 重定义后可以重建元数据。

缓存数组保存的是内部 root `Method`，公开 API 通过 `ReflectionFactory.copyMethod` 返回副本：

```text
Class.ReflectionData
  -> root Method（内部共享元数据）
       -> public query 返回 Method copy A
       -> 下一次 query 返回 Method copy B

copy A.root ----+
copy B.root ----+-> 同一个 root，可共享 MethodAccessor
```

这样做同时满足两点：

1. 调用者对某个 `Method` 设置 accessible 标记，不会直接污染另一次查询返回对象的标记。
2. 多个副本仍可通过 root 复用昂贵的 MethodAccessor。

因此不要用 `methodA == methodB` 判断两个查询是否表示同一成员。使用 `Method.equals` 或由声明类、名称、参数类型和返回类型组成的稳定键；缓存还应考虑 class loader 身份，因为相同全限定名可由不同 loader 定义成不同类型。

## Method 自身保存哪些信息

OpenJDK 8 `Method` 的核心字段可分为四组：

| 分组 | 代表字段 | 作用 |
| --- | --- | --- |
| JVM 成员身份 | `clazz`、`slot`、`name`、`modifiers` | 定位声明类中的真实成员 |
| 擦除后签名 | `returnType`、`parameterTypes`、`exceptionTypes` | 调用适配和普通反射查询 |
| 泛型与注解 | `signature`、注解字节数组、惰性 repository | 按需解析 `Type`、注解和默认值 |
| 执行与共享 | `volatile methodAccessor`、`root` | 缓存调用入口并在副本之间共享 |

`getReturnType()` 返回擦除后的 `Class<?>`；`getGenericReturnType()` 可能返回 `ParameterizedType`、`TypeVariable` 等 `Type`。泛型信息用于框架建模，不改变 JVM 调用描述符，也不会让 `Method.invoke` 自动检查 `List<String>` 与 `List<Integer>` 的元素类型。

## Method.invoke 的准确主线

OpenJDK 8 的入口可以压缩成下面的等价伪代码：

```text
invoke(target, args):
  if override == false:
    if public 快速检查不能直接通过:
      caller = Reflection.getCallerClass()
      checkAccess(caller, declaringClass, target, modifiers)

  accessor = methodAccessor
  if accessor == null:
    accessor = acquireMethodAccessor()

  return accessor.invoke(target, args)
```

注意 `Method.invoke` 这一层没有执行所有接收者和参数转换细节；它把具体调用交给 `MethodAccessor`。访问检查和执行器分离，使 `setAccessible` 可以影响前者，而 accessor 缓存继续服务同一成员。

### 访问检查不是每次完整重算

`AccessibleObject.checkAccess` 在 JDK 8 中维护 `securityCheckCache`：

- caller 与声明类相同可以快速通过；
- 普通成功检查可缓存最近 caller；
- protected 成员还可能把 caller 与实际 target class 组成二元缓存；
- 缓存命中只省略重复语言访问计算，不绕过 SecurityManager 或改变成员修饰符。

`override` 是 JDK 8 内部对 suppress-access-checks 标记的称呼。`setAccessible(true)` 成功后，`invoke` 跳过上述语言访问检查，但并不修改 class 文件中的 private/protected/public，也不让参数类型变得可见或兼容。

JDK 9+ 还要满足模块开放规则，详见 [访问控制与版本差异](./access-version.md)。

## 接收者规则

| 目标方法 | `obj` 参数 | 结果 |
| --- | --- | --- |
| 实例方法 | 正确声明类或子类实例 | 正常虚分派，override 方法仍可被调用 |
| 实例方法 | `null` | `NullPointerException` |
| 实例方法 | 不兼容类型 | `IllegalArgumentException` |
| static 方法 | 任意值，包括 `null` | `obj` 被忽略；必要时触发声明类初始化 |

反射调用 virtual 方法不会“锁死”在 `Method.getDeclaringClass()` 的实现上。若 `Method` 表示父类 public 方法，而 target 是覆写它的子类实例，普通 invokevirtual 语义仍会分派到子类实现。要表达 invokespecial、默认接口特定 super 调用或更精确的调用类型，应评估 MethodHandle，而不是期待 `Method.invoke` 增加隐藏选项。

## 参数转换只允许方法调用中的安全子集

`Method.invoke` 接收 `Object[]`，因此原始参数先以包装对象出现。执行器会做拆箱与允许的基本类型拓宽：

| 实参包装类型 | 可传给的原始形参示例 | 不允许的示例 |
| --- | --- | --- |
| `Byte` | `byte/short/int/long/float/double` | `char/boolean` |
| `Short` | `short/int/long/float/double` | `byte/char` |
| `Character` | `char/int/long/float/double` | `short/byte` |
| `Integer` | `int/long/float/double` | `short/byte/char` |
| `Long` | `long/float/double` | `int` |
| `Float` | `float/double` | `long` |
| `Double` | `double` | `float` |
| `Boolean` | `boolean` | 所有数值类型 |

引用参数使用普通 assignability；`null` 可以传给引用形参，不能拆箱给原始形参。以下情况在目标方法开始前抛 `IllegalArgumentException`：

- 参数数量不一致；
- 引用类型不可赋值；
- 包装类型不能拆成目标原始类型；
- 拆箱后需要窄化；
- target 不是声明类实例。

### 可变参数不会替目标自动打包

`Method.invoke` 自身声明为 `Object... args`，这是调用反射 API 的语法便利，不等于它会对目标方法再次执行编译器的 variable-arity 打包。

目标签名为：

```java
String join(String... values) // JVM 形参实际是 String[]
```

可靠调用方式是把一个 `String[]` 作为唯一目标参数：

```java
method.invoke(target, new Object[]{new String[]{"a", "b"}});
```

直接写 `method.invoke(target, "a", "b")` 代表两个目标参数，会因数量不符失败。尤其在零个、一个数组参数和 `null` 场景，应显式构造 `Object[]`，避免 Java 编译器先对 `Method.invoke` 这一层 varargs 产生歧义。

## acquireMethodAccessor 如何在副本间共享

JDK 8 的 `Method.acquireMethodAccessor()` 不使用全局锁：

```text
tmp = root == null ? null : root.getMethodAccessor()

if tmp != null:
  this.methodAccessor = tmp
else:
  tmp = ReflectionFactory.newMethodAccessor(this)
  this.setMethodAccessor(tmp)  // 同时传播到 root

return tmp
```

源码明确允许并发线程偶尔重复生成 accessor。同步每个首次调用会降低可扩展性，而生成两个语义等价 accessor 只是效率损失，不破坏结果。框架代码也不应假定“同一 Method 只会创建一个内部执行器”来挂载业务状态。

## JDK 8 inflation：启动成本与稳定调用成本的折中

`ReflectionFactory` 默认配置：

```text
noInflation = false
inflationThreshold = 15
```

首次创建的结构是：

```text
Method.methodAccessor
  -> DelegatingMethodAccessorImpl
       -> delegate = NativeMethodAccessorImpl
```

每次调用进入 native accessor 时：

```text
numInvocations += 1
if numInvocations > inflationThreshold
   且声明类不是不能按名称引用的 VM anonymous class:
  generated = MethodAccessorGenerator.generateMethod(...)
  parent.setDelegate(generated)

return invoke0(method, target, args)
```

有两个常被简化掉的细节：

1. 默认前 15 次使用 `invoke0`。第 16 次进入时先生成并替换 delegate，但当前调用仍执行源码末尾的 `invoke0`；第 17 次开始通常经过 generated accessor。
2. `sun.reflect.noInflation=true` 可以要求 ReflectionFactory 初次就生成 accessor，`sun.reflect.inflationThreshold` 可调整阈值；它们是 HotSpot/OpenJDK 内部诊断配置，不是可移植业务 API。

generated accessor 的价值是把参数拆箱、类型检查和目标 invoke 指令写成 JVM 可见的字节码，让后续优化更容易处理；代价是生成、验证和加载新类。这个历史折中已在 JDK 21 主路径中被 MethodHandle accessor 取代。

## 返回值和异常边界

### 正常返回

| 目标返回类型 | `Method.invoke` 返回 |
| --- | --- |
| 引用类型 | 原引用，包括 `null` |
| 原始类型 | 对应包装对象，例如 `long -> Long` |
| `void` | `null` |

因此不能仅凭返回 `null` 区分“目标返回了 null”和“目标是 void”；调用者可用 `method.getReturnType() == void.class` 判断声明。

### 异常分层

```text
查找失败                  -> NoSuchMethodException
访问检查失败              -> IllegalAccessException
接收者/参数适配失败        -> IllegalArgumentException 或 NullPointerException
目标类初始化失败           -> ExceptionInInitializerError
目标方法抛 Throwable       -> InvocationTargetException(cause)
```

`InvocationTargetException` 的存在让反射基础设施错误与业务目标错误可区分。转发型框架通常应解包 cause，再按自己的 API 契约传播：

```java
try {
    return method.invoke(target, args);
} catch (InvocationTargetException exception) {
    throw exception.getCause();
}
```

是否直接抛 cause 仍取决于框架边界：日志、事务回滚规则和受检异常声明可能需要统一适配。不要用 `catch (Exception) { throw new RuntimeException(exception); }` 无差别增加包装层。

## bridge、synthetic 与泛型擦除

编译器可能为协变返回或泛型覆写生成 bridge method。`getDeclaredMethods()` 可以同时看到业务方法和 bridge/synthetic 方法：

```text
Child.value(): String          // 源码方法
Child.value(): Object          // 编译器 bridge，转调上面的方法
```

框架扫描时应明确策略：

- `method.isBridge()` 判断桥接方法；
- `method.isSynthetic()` 判断编译器或工具生成成员；
- 注解可能写在接口、父类或具体实现的不同 Method 上；
- 同名同参数但协变返回在 JVM 描述符层面可能是两个方法，不能只用名称和参数数组粗暴去重。

JDK 动态代理自己的重复方法合并也会处理协变返回类型，详见 [代理的重复签名规则](./dynamic-proxy.md#重复签名为何让接口顺序有语义)。

## 性能判断要测哪一段

一次“反射调用”可能包含：

```text
字符串/注解扫描 -> Method 查找 -> 访问决策 -> 参数数组分配
-> accessor 分派 -> 目标方法 -> 返回/异常适配
```

应优先做结构性优化：

1. 在类加载或容器启动边界缓存已经验证的 `Method` 或调用计划，不在每个请求中扫描全部方法。
2. 缓存键包含 Class 身份而不是只有类名，避免多 loader 环境串用元数据。
3. 避免无意义地反复调用 `setAccessible`。
4. 若调用点固定且确实处于热点，再用 JMH 比较直接调用、Method、MethodHandle；不要用一次 `nanoTime` 把类加载和 warm-up 混在结果中。
5. 若目标方法包含数据库或网络 I/O，先用剖析证据确认反射是否真是瓶颈。

JDK 8 inflation 阈值不应写进性能断言。JIT、CPU、调用形态和 JDK 版本都影响结果，JDK 21 已更换主实现。

## 推荐断点

1. `Class.getDeclaredMethod`：观察名称和精确 parameterTypes 查找。
2. `Class.privateGetDeclaredMethods`：观察 `ReflectionData` 命中和 VM 元数据获取。
3. `Method.copy`：观察 root 与副本的 accessible/accessor 独立和共享边界。
4. `Method.invoke`：记录 `override`、`clazz`、`obj`、`modifiers` 和 args。
5. `AccessibleObject.checkAccess`：观察 caller/target 的访问缓存。
6. `Method.acquireMethodAccessor`：区分 root 复用与新建。
7. `ReflectionFactory.newMethodAccessor`：确认当前 JDK 选用哪种执行器。
8. JDK 8 `NativeMethodAccessorImpl.invoke`：观察 `numInvocations` 和 delegate 切换。
9. 目标方法首行：确认参数适配完成后才进入业务代码。
10. `InvocationTargetException` 构造点或 handler 解包处：区分基础设施错误与目标异常。

下一步阅读 [Proxy 生成、缓存与分派](./dynamic-proxy.md)，看 Method 元数据怎样被写进一个新生成类的静态字段并交给 handler。
