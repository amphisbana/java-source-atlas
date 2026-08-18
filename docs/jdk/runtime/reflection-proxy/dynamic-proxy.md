# JDK Dynamic Proxy：缓存代理 Class，再把调用编码给 Handler

JDK 动态代理不是运行时“修改”真实目标类。它根据接口列表生成一个新的 final 类，这个类继承 `java.lang.reflect.Proxy`、实现指定接口，并为每个可代理方法生成具体字节码。接口调用先进入这个生成方法，再统一转到实例持有的 `InvocationHandler`。

## newProxyInstance 分成 Class 阶段和实例阶段

OpenJDK 8 `Proxy.newProxyInstance` 的主线是：

```text
Objects.requireNonNull(handler)
intfs = interfaces.clone()
若存在 SecurityManager，检查 caller、loader 和接口包访问

proxyClass = getProxyClass0(loader, intfs)
  -> proxyClassCache.get(loader, intfs)
  -> 命中：返回已有 Class
  -> 未命中：ProxyClassFactory.apply(loader, intfs)

constructor = proxyClass.getConstructor(InvocationHandler.class)
必要时为 non-public proxy constructor 设置 accessible
return constructor.newInstance(new Object[]{handler})
```

接口数组先 clone，避免调用者在校验与生成之间并发修改内容。handler 必须非 null，因为所有代理实例都通过父类 `Proxy(InvocationHandler)` 保存一个确定分派对象。

Class 阶段通常每个 `(loader, ordered interfaces)` 组合执行一次，实例阶段可以执行很多次。相同 Class 的不同代理实例可以保存不同 handler，彼此拥有不同策略或 target。

## WeakCache 的键为何包含接口顺序

JDK 8 的静态缓存声明为：

```text
WeakCache<ClassLoader, Class<?>[], Class<?>> proxyClassCache
```

表面上的 `Class<?>[]` 不能直接用数组 equals，因为需要按接口 Class 身份和顺序构造稳定子键。`KeyFactory` 对常见数量做了专门键：

- 0 个接口使用固定 key；
- 1 个接口使用持有弱引用的 `Key1`；
- 2 个接口使用 `Key2`；
- 3 个及以上使用按顺序保存弱引用数组的 `KeyX`。

主键也对 class loader 使用弱身份关联，并用引用队列清理失效条目。值侧通过弱引用包装和并发 `Factory` 占位，避免两个线程在同一 key 上稳定地产生两个最终代理 Class，同时不让全局缓存永久强持有可卸载 loader 的整个类型图。

这里的弱引用设计只保护 JDK 自己的代理类缓存。应用若把代理实例、handler、Method 或 Class 放进永不清理的 static Map，仍会自己固定住 class loader。

接口顺序必须进入 key，因为它影响公开语义：

```text
[First.class, Second.class] != [Second.class, First.class]
```

两种顺序会得到不同代理 Class，并在重复签名出现时选择不同的 `Method` 传给 handler。

## ProxyClassFactory 校验哪些约束

缓存未命中后，OpenJDK 8 `ProxyClassFactory.apply` 依次执行：

### loader 必须解析到同一个接口 Class

等价检查是：

```text
Class.forName(interfaceName, false, loader) == interfaceClass
```

只比较全限定名不够。父子 loader 可以定义两个同名接口；若指定 loader 解析到的是另一个 Class 身份，生成类就不能真正实现调用者手中的接口，工厂必须拒绝。

### 每一项都必须是非重复接口

- 普通 class、enum、annotation 之外的非接口 Class 会被拒绝；annotation type 本身是接口，可作为技术上的 proxy interface，但业务语义要自行评估。
- 同一个接口 Class 出现两次会被拒绝。
- 接口数量和生成类方法、字段、字节码仍受 JVM class file 上限约束。

### non-public 接口必须在同一运行时包

JDK 8 中，只要有一个接口非 public，代理类就不能是 public，并必须定义在该接口所在包。多个 non-public 接口若来自不同包会失败。

“同名包”在 JVM 中还包含 class loader 身份。跨 loader 的相同包名不构成同一运行时包，不能据此绕过包访问边界。

### 重复方法返回类型必须兼容

多个接口可以有相同方法名和参数签名。若返回类型为 primitive 或 void，所有返回类型必须完全相同；若为引用类型，必须存在一个返回类型可赋值给其余返回类型，即可以生成满足协变返回约束的方法组合。

## 代理类被定义在哪里

OpenJDK 8 的规则：

- 所有接口 public 时，默认生成到 `com.sun.proxy` 下，代理类为 public final；
- 存在 non-public 接口时，生成到该接口包，代理类为 package-private final；
- 类名使用 `$Proxy` 加递增编号，但未限定名和编号不是 API；
- 代理类由传给 `newProxyInstance` 的 loader 定义；
- 代理类父类固定为 `java.lang.reflect.Proxy`。

不要在业务代码中判断 `class.getName().startsWith("com.sun.proxy.$Proxy")`。可靠判断是 `Proxy.isProxyClass(clazz)`；获取 handler 使用 `Proxy.getInvocationHandler(proxy)`。JDK 9+ 的模块感知放置规则也已经让固定包名假设失效。

## ProxyGenerator：先收集方法再写 class file

OpenJDK 8 `sun.misc.ProxyGenerator.generateClassFile()` 分三阶段：

1. 收集需要生成的代理方法，并合并重复签名。
2. 为构造器、每个 `Method` 静态字段、代理方法和类初始化器创建 class file 结构。
3. 固定常量池，写出完整 class bytes。

方法收集有明确先后：

```text
Object.hashCode
Object.equals
Object.toString
第 1 个接口的 getMethods()
第 2 个接口的 getMethods()
...
```

Object 三个方法先加入，所以即便接口重新声明同签名方法，handler 收到的 `Method.getDeclaringClass()` 仍是 `Object.class`。接口之间名称、参数和返回类型都相同的方法被合并为同一生成入口时，先出现的接口决定传给 handler 的代表 `Method`；协变返回类型对应不同 JVM 方法描述符，可生成不同入口。

## 生成类大致长什么样

下面是行为等价伪代码，不是从 OpenJDK 复制的生成源码：

```java
public final class $Proxy0 extends Proxy implements GreetingService {
    private static Method m0; // Object.hashCode
    private static Method m1; // Object.equals
    private static Method m2; // Object.toString
    private static Method m3; // GreetingService.welcome

    public $Proxy0(InvocationHandler handler) {
        super(handler);
    }

    public final String welcome(String name, long times) {
        try {
            Object result = h.invoke(
                    this,
                    m3,
                    new Object[]{name, Long.valueOf(times)});
            return (String) result;
        } catch (RuntimeException | Error allowed) {
            throw allowed;
        } catch (Throwable other) {
            throw new UndeclaredThrowableException(other);
        }
    }
}
```

真实生成器会根据接口 `throws` 声明建立异常表，也会为 primitive 返回生成对应 checkcast 与 unbox 指令。无参数方法传给 handler 的 args 可以是 `null`，这符合 `InvocationHandler` 契约；handler 不应无条件访问 `args.length`。

类初始化器负责取得并写入静态 `Method` 字段。一次业务调用不需要再按字符串查找 Method，但仍会分配参数数组并为原始参数装箱，具体优化程度由 JIT 和调用形态决定。

## handler 的三个入参分别代表什么

```java
Object invoke(Object proxy, Method method, Object[] args) throws Throwable
```

| 参数 | 准确含义 | 常见误用 |
| --- | --- | --- |
| `proxy` | 当前接收调用的代理实例 | 在日志中调用 `proxy.toString()`，递归进入 handler |
| `method` | 生成器为当前签名选定的代表 Method | 假定它一定来自调用时静态引用类型 |
| `args` | 已装箱参数数组；无参数时可为 null | 修改数组后假定能改变调用者局部变量 |

handler 返回 `Object`，但生成方法会按接口返回类型继续验证：

- 引用返回执行 `checkcast`，类型错误得到 `ClassCastException`；
- primitive 返回先强转到对应 wrapper，再拆箱；null 会得到 `NullPointerException`；
- void 返回会丢弃 handler 返回对象。

这些异常发生在生成代理方法中，不是 handler 自动替你做了接口类型检查。

## Object 方法为何也会进入 handler

代理类显式覆盖并分派以下三个方法；OpenJDK 8 生成器的实际收集顺序是 `hashCode -> equals -> toString`：

- `int hashCode()`；
- `boolean equals(Object)`；
- `String toString()`。

它不覆盖 Object 的 final 方法，所以 `getClass`、`wait`、`notify`、`notifyAll` 不进入 handler。`clone/finalize` 等也不属于 Proxy 规定的三个 Object 分派方法。

handler 实现这些方法时应避免递归：

```java
if (method.getDeclaringClass() == Object.class) {
    switch (method.getName()) {
        case "equals":
            return proxy == args[0];
        case "hashCode":
            return System.identityHashCode(proxy);
        case "toString":
            return "ServiceProxy[target=" + targetDescription + "]";
        default:
            throw new AssertionError(method);
    }
}
```

是否采用身份 equals 由业务语义决定。关键是 equals、hashCode 必须一致，且不能在实现过程中再次调用代理自身对应方法。

## default 方法不会自动绕过 handler

接口 default 方法在代理 Class 中也有生成分派方法，普通调用仍先进入 `InvocationHandler.invoke`。JDK 8 没有标准公共 API 让 handler 直接执行某个 default 实现。

常见行为：

- handler 返回自定义值：默认方法体不执行；
- handler 用 `method.invoke(proxy, args)`：再次调用同一个代理方法，形成递归；
- handler 有真实 target 且 target 实现接口：`method.invoke(target, args)` 可以按 target 的普通分派执行其覆写或继承的 default；
- Java 16+：可以用 `InvocationHandler.invokeDefault(proxy, method, args)` 表达类似 `Interface.super.method` 的默认调用。

`invokeDefault` 还会检查 method 是否确实是该 proxy interface 可调用的 default 方法。接口演进新增或覆盖 default 方法可能改变解析结果，不能把任意 `Method.isDefault()` 都无条件交给它。

## 重复签名为何让接口顺序有语义

考虑：

```java
interface First { String id(); }
interface Second { String id(); }

Object proxy = Proxy.newProxyInstance(
        loader,
        new Class<?>[]{First.class, Second.class},
        handler);
```

无论通过 `First` 还是 `Second` 引用调用 `id()`，生成类只有按该签名确定的分派入口，无法知道调用点使用了哪种静态引用类型。handler 收到的是最前接口规则选定的 `First.id` Method。

把接口顺序反过来会生成另一代理 Class，并让代表 Method 来自 `Second`。因此不要在一个已有代理契约中随意重排接口数组，即使 `instanceof` 集合看起来没有变化。

### checked exception 取交集

重复方法的合法受检异常必须同时兼容所有相关接口声明。生成器会收敛 exception types；handler 不能因为收到某个代表 Method 就只按它的宽 throws 列表抛异常。

例如一个接口声明 `throws IOException`，另一个同签名方法不声明受检异常，最终 handler 抛出的 IOException 不能直接穿过生成方法，会被包装成 `UndeclaredThrowableException`。

## handler 异常怎样离开代理方法

生成方法允许直接传播：

1. `RuntimeException`；
2. `Error`；
3. 与接口方法 throws 声明兼容的受检异常。

其他 `Throwable` 会被包装：

```text
handler throws IOException
  + interface method declares IOException
      -> caller 直接得到同一个 IOException

handler throws IOException
  + interface method declares nothing
      -> caller 得到 UndeclaredThrowableException(IOException)
```

若 handler 用反射转发：

```text
h.invoke
  -> Method.invoke(target, args)
       -> target throws IOException
       -> InvocationTargetException(IOException)
```

handler 应解包 `InvocationTargetException.getCause()`。否则生成代理方法看到的是未声明的 `InvocationTargetException`，很可能再包装一层 `UndeclaredThrowableException`，接口调用者最终要剥两层才能找到业务异常。

## 代理目标是可选的

### 本地 target 转发

```java
try {
    return method.invoke(target, args);
} catch (InvocationTargetException exception) {
    throw exception.getCause();
}
```

适合日志、鉴权、事务等接口装饰器，但要处理 Object 方法、默认方法和目标是否真正实现声明接口。

### 无本地 target

```text
Method + args
  -> 读取注解、服务名和超时
  -> 编码 RPC 请求
  -> 等待响应
  -> 按 Method.getGenericReturnType 解码
```

这类代理是运行时 stub。它证明“动态代理一定包着真实对象”不是 Proxy 的契约。

## 能做什么，不能做什么

JDK Proxy 的生成类只能实现接口：

- 可以代理多个接口；
- 可以为接口 method 统一提供横切逻辑；
- 不能覆盖普通类中没有接口表达的方法；
- 不能通过 JDK Proxy 拦截构造器、字段访问、static 方法或 final Object 方法；
- 不能让同一个已经创建的 proxy Class 动态增加新接口，需要创建另一接口列表对应的 Class。

需要基于类的代理时，框架通常使用字节码生成子类。那套方案还要面对 final 类/方法、构造器、模块开放和 self-invocation 等不同边界，不能直接套用 `InvocationHandler` 结论。

## 推荐断点

1. `Proxy.newProxyInstance`：观察 intfs clone、handler 和 constructor。
2. `Proxy.getProxyClass0`：区分缓存入口与接口数量上限。
3. `WeakCache.get`：观察 loader key、subKey、Factory 占位和缓存命中。
4. `Proxy.ProxyClassFactory.apply`：逐项检查 loader 可见性、接口身份和 non-public 包。
5. `ProxyGenerator.generateClassFile`：观察 Object 方法先加入、接口按顺序加入。
6. `ProxyGenerator.addProxyMethod`：观察重复签名代表 Method、返回类型与异常交集。
7. `ProxyGenerator.ProxyMethod.generateMethod`：观察参数装箱、handler invoke、返回拆箱和异常表。
8. `Proxy.defineClass0`：确认代理 Class 由哪个 loader 定义。
9. 代理类构造器与 `Proxy(InvocationHandler)`：观察实例字段 h。
10. 自定义 `InvocationHandler.invoke`：分别从接口方法、toString 和 default 方法进入。

下一步阅读 [访问控制与 JDK 8/17/21 差异](./access-version.md)，避免把 JDK 8 的 `com.sun.proxy`、SecurityManager 和 accessor 私有实现带到模块化 JDK。
