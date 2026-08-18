# loadClass：委派、定义与类型身份

ClassLoader 的核心不是“把文件读成 byte[]”这一行，而是围绕同一二进制名维护唯一定义、父层共享、并发安全和链接边界。自定义加载器若直接重写整个 loadClass，很容易跳过其中一项。

## 动画：从 SPI 配置到 provider Class

动画把 ServiceLoader 的一次首次迭代与 ClassLoader 的委派链放在同一张图中。注意两个延迟点：创建 ServiceLoader 时尚未扫描配置，Class.forName 使用 `initialize=false` 时也尚未执行 provider 的类初始化器；真正的 provider 对象在迭代器 `next` 中产生并缓存。

<ClassLoaderServiceLoaderAnimation />

## loadClass 的四段决策

OpenJDK 8 的主干可压缩为：

```java
protected Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c == null) {
            try {
                if (parent != null) {
                    c = parent.loadClass(name, false);
                } else {
                    c = findBootstrapClassOrNull(name);
                }
            } catch (ClassNotFoundException ignored) {
                // 父层找不到，才允许本加载器寻找。
            }
            if (c == null) {
                c = findClass(name);
            }
        }
        if (resolve) {
            resolveClass(c);
        }
        return c;
    }
}
```

真实源码还记录性能计数和安全检查，但稳定决策就是：先复用、再父链、后本地、按需解析。

## 第一步为什么先 findLoadedClass

同一个 ClassLoader 不能对同一二进制名重复调用 defineClass。第二次定义会得到 LinkageError，而不是覆盖旧 Class。更准确地说，`findLoadedClass` 查询 JVM 是否已经把当前加载器记录为该 Class 的 **initiating loader**；命中结果的 `getClassLoader()` 可能是父加载器或 null，并不保证当前加载器是 defining loader。

典型命中有两种：

```text
当前加载器自己 define 过 Widget
  -> 当前加载器既是 initiating loader，也是 defining loader

当前加载器在 JVM 类型解析中委派父层取得 SharedApi
  -> 当前加载器是 initiating loader
  -> 父层才是 defining loader
```

JVM 何时记录 initiating loader 取决于类创建与解析过程。业务代码直接调用一次 `child.loadClass(name)` 的调用栈外观本身不是记录证据；对自定义加载器做实验时，应通过受保护的 `findLoadedClass` 观察真实结果，并用返回 Class 的 `getClassLoader()` 单独确认 defining loader。

这也解释了“替换 JAR 后旧类为什么没变”：只要旧加载器仍存活且其加载视图已经记录该类型，后续请求就可能继续复用旧 Class。热部署通常创建新加载器、切换入口并让旧加载器及其实例整体不可达，而不是在原加载器中覆盖同名类型。

## getClassLoadingLock 保护什么

JDK 8 的 loadClass 在 `getClassLoadingLock(name)` 上同步。普通加载器默认可能返回自身作为全局锁；通过 `registerAsParallelCapable()` 正确注册的并行加载器可以为不同名称使用不同锁，从而并行加载互不相关的类。

锁保护的是“检查是否已加载到完成定义”这一竞争区间，避免两个线程同时 define 同名类。它不把类初始化业务变成全局串行，也不替业务对象提供线程安全。

并行能力需要加载器类层级满足注册规则，且 findClass、资源读取、package 定义等实现本身支持并发。仅删除 synchronized 或随意覆盖 getClassLoadingLock 会把唯一性问题变成偶发 LinkageError。

## parent 为 null 为什么不等于什么都不做

当 parent 不为 null，默认算法调用父加载器的 loadClass；当 parent 为 null，它会尝试 bootstrap 类查找。bootstrap 由 JVM/native 实现，不需要暴露普通 Java ClassLoader 对象，所以：

```java
Object.class.getClassLoader() == null
```

null 是公开 API 对 bootstrap 的表示，不是 Object 没有定义来源，也不是可以对 null 调用加载方法。业务代码需要“系统加载器”时应使用 `ClassLoader.getSystemClassLoader()`，不要把它与 bootstrap 混为一谈。

## findClass 才是推荐的扩展点

ClassLoader 默认 `findClass(name)` 直接抛 ClassNotFoundException。URLClassLoader 在这里把二进制名转换为资源路径、定位 URL、读取并校验字节、处理 CodeSource/证书/package，最终调用 defineClass。

典型自定义加载器只需：

```text
loadClass 继续使用父类模板
  -> 父层确实找不到
  -> findClass(name)
       -> name 转资源键
       -> 获取完整 class 字节
       -> 必要时定义 package
       -> defineClass(name, bytes, ...)
```

这体现模板方法模式：ClassLoader 固定加载协议，自定义加载器实现本地查找步骤。若业务确实需要 child-first 插件隔离，可以只对明确包名改写 loadClass，但必须保留加载锁和 findLoadedClass，并把 `java.*`、共享 API 包等委派给父层。

## defineClass 的输入不是普通数据

defineClass 会让 JVM 校验 class 文件和请求名称的一致性，并把返回类型永久关联到当前定义加载器。通用 `ClassLoader.defineClass` 需要留意：

- 传入名称与 class 文件内名称不一致会失败。
- 禁止业务加载器定义 `java.*` 命名空间。
- 同一加载器重复定义同名类型会失败。
- 同一 package 中各 Class 的签名证书集合必须一致；
- 字节格式、版本、验证失败分别可能表现为 ClassFormatError、UnsupportedClassVersionError、VerifyError 等 LinkageError。

### sealing 校验不在通用 defineClass 里完成

JAR package sealing 依赖 Manifest 和 class 的 CodeSource URL，这些信息属于 `URLClassLoader` 的资源协议。OpenJDK 8 的边界是：

```text
URLClassLoader.defineClass(name, Resource)
  -> 读取 Resource 的 Manifest 与 CodeSource URL
  -> definePackageInternal(packageName, manifest, url)
       -> getAndVerifyPackage(...)
            -> 已 sealed：必须来自同一 seal base URL
            -> 未 sealed：不能被后来的 JAR 改成 sealed
  -> 读取 class bytes 与 signers
  -> SecureClassLoader / ClassLoader.defineClass(...)
       -> 校验同 package 的证书集合、名称与 class bytes
```

因此，手写只调用 `defineClass(name, bytes, ...)` 的加载器不会自动读取 JAR Manifest，也不会凭空建立 sealing 语义。若加载来源需要 package 元数据，应在定义第一个类前正确调用 `definePackage`，并在后续定义前按自己的资源模型验证 seal base；证书一致性仍由 `ClassLoader` 的 pre-define 路径校验。

ClassNotFoundException 通常表达“按请求路径没有找到定义”；LinkageError 表达“找到了某种定义，但无法合法链接”。捕获前者后换备用来源可能合理，笼统吞掉后者通常会隐藏部署冲突。

## resolveClass 不执行 static 块

`resolve=true` 会请求链接返回类型，不能与初始化画等号。下面两句都能拿到 Class，但初始化语义不同：

```java
Class<?> loaded = loader.loadClass("demo.Widget");
Class<?> notInitialized = Class.forName("demo.Widget", false, loader);
```

主动使用通常会触发初始化，例如：

- `new` 创建实例；
- 调用类的 static 方法；
- 读取或写入不是编译期常量的 static 字段；
- `Class.forName(name, true, loader)`；
- 某些反射调用。

类字面量 `Widget.class`、创建该类的数组、读取编译期常量通常不会触发 Widget 初始化。接口初始化还有自己的规则：初始化一个类不会递归初始化它实现的所有接口，只会处理规范要求的超接口场景。

## 类型身份为何包含定义加载器

动画和实验中的两个隔离加载器使用相同字节分别定义 `LoaderIdentityFixture`：

```text
typeA = <LoaderIdentityFixture, loaderA>
typeB = <LoaderIdentityFixture, loaderB>

typeA.getName().equals(typeB.getName())  // true
typeA == typeB                           // false
typeA.isAssignableFrom(typeB)            // false
```

运行时的强制转换、方法参数、字段描述符都会使用这种类型身份。若共享 SPI 接口也被插件加载器重新定义，插件 provider 实现的是“插件自己的接口”，不再是宿主传给 ServiceLoader 的接口，即使两边全限定名一致也会在 isAssignableFrom 检查失败。

稳定的插件边界通常是：

```text
父加载器：定义共享 API / SPI 接口
子加载器：定义各插件实现及插件私有依赖
双方传递：父层可见的接口、JDK 类型或明确序列化边界
```

把宿主业务实现类直接作为跨加载器 DTO，会让卸载、版本升级和类型兼容都变得困难。

## child-first 何时有意义

父优先保证核心类和公共依赖尽量唯一，降低伪造 JDK 类、重复 API 和类型转换冲突。以下场景可能需要受控 child-first：

- 插件需要使用与宿主不同版本的第三方库；
- 应用服务器为每个部署单元隔离实现依赖；
- 测试框架需要隔离静态状态；
- 热部署需要新加载器定义新版本类型。

实现时应先划清包边界，而不是“所有名称一律 child-first”。至少应父优先共享 JDK 类型、SPI API、日志门面契约和跨边界模型。否则同名接口、异常、注解甚至日志 API 都可能各有一份。

## 数组类和基本类型的加载器

数组类不是由 ClassLoader.defineClass 直接创建；JVM 在需要时创建。数组元素为引用类型时，数组 Class 的定义加载器与元素类型相同：

```text
String[].class.getClassLoader() == String.class.getClassLoader() == null
PluginType[].class.getClassLoader() == PluginType.class.getClassLoader()
```

基本类型和基本类型数组的 Class 也由 JVM 表示，getClassLoader 返回 null。调试“为什么数组不能转换”时仍应检查其组件类型来自哪个加载器。

## 类查找和资源查找不能完全类比

`loadClass` 有明确的 Class 唯一性与父委派模板。资源 API 没有相同的类型链接约束：

- `getResource` 返回一个命中的 URL；默认 ClassLoader 实现先父后本地。
- `getResources` 枚举多个同名资源，具体全局顺序受加载器实现和 classpath 影响。
- ServiceLoader 正是用 `getResources("META-INF/services/...")` 合并多个配置。
- 某些容器为了覆盖配置会定制资源顺序，即使类加载仍采用另一套规则。

因此，不能根据某个类由父加载器定义，就推断同名配置资源一定只从父层读取；诊断时要分别记录 Class 的定义加载器和配置 URL 枚举。

## URLClassLoader 的生命周期边界

JDK 8 应用加载器通常是 URLClassLoader 体系，但自定义插件 URLClassLoader 在不用后仍应调用 `close()`，释放已打开 JAR 的文件句柄。close 不会卸载已经定义的 Class；真正卸载要求定义加载器、它定义的所有 Class、实例以及关联缓存整体不可达。

常见阻止卸载的引用包括：

- 未停止的线程及其 TCCL；
- ThreadLocal value；
- 静态注册表和单例缓存；
- ServiceLoader 持有的 provider 实例；
- JDBC DriverManager 等全局注册；
- 监听器、定时任务与 shutdown hook。

JDK 17/21 的应用加载器不保证是 URLClassLoader，业务代码不要通过强转并反射修改 classpath。插件自己的 URLClassLoader 仍可显式管理，但模块层通常应使用模块 API 建立。

## 调试 loadClass 的变量顺序

在 JDK 8 下建议依次观察：

| 断点 | 核心变量 | 要回答的问题 |
| --- | --- | --- |
| `ClassLoader.loadClass(String,boolean)` | name、resolve、this、parent | 谁发起请求，是否请求解析 |
| `findLoadedClass` 返回后 | c、`c.getClassLoader()` | 当前加载器是否已被记录为 initiating loader；谁才是 defining loader |
| `parent.loadClass` | parent、c | 父层在哪一级命中 |
| 自定义 `findClass` | 资源键、字节来源 | 为什么父层失败后本地能找到 |
| `defineClass` | name、ProtectionDomain | 最终谁成为定义加载器 |
| `resolveClass` | c | 是否请求链接，不要误判为初始化 |

断点应优先设置类名条件，否则 JVM 和调试器自身会产生大量加载请求。不要在自定义 loadClass 中调用会触发同一类加载的日志格式化逻辑，以免调试行为递归进入加载器。
