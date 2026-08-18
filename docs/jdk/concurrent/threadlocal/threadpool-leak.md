# 线程池污染、继承与上下文边界

ThreadLocal 的生命周期单位是 Thread，不是 Runnable、HTTP 请求或消息。线程池恰好反复让不同逻辑任务使用同一个 worker，因此“每线程一份”会变成“后一个任务继承了前一个任务没清掉的绑定”。

## 最小污染时间线

单线程池只有 worker-1：

```text
请求 A -> worker-1: context.set("tenant-A") -> 忘记 remove -> 结束
请求 B -> worker-1: context.get() == "tenant-A"
```

请求 B 没有 set 仍读到 A，不是 ThreadLocal 并发可见性错误，而是同一个 Thread 的 map 按设计保留了 Entry。可能后果包括日志串号、越权读取、错误事务资源复用和租户数据混淆。

如果 B 恰好调度到另一个 worker，问题可能暂时不出现，所以用多线程池做无控制复现会不稳定。实验和自动测试使用单线程执行器固定复用同一 Thread。

## try finally remove 是基本边界

```java
requestContext.set(context);
try {
    return invokeBusiness();
} finally {
    requestContext.remove();
}
```

remove 必须在 finally 中，因为业务异常、提前 return 和取消都不应跳过清理。调用 `set(null)` 不能替代 remove：它仍保留 Entry，并改变下一次 get 是否调用 initialValue 的语义。

应由建立绑定的代码负责清理。业务深层方法随意 remove 一个上层仍要使用的 ThreadLocal，会破坏嵌套调用；上下文 API 应明确所有权和作用域。

## 用任务装饰器集中治理

当所有提交入口都由同一基础设施控制时，可以使用装饰器模式包装 Runnable/Callable：

```text
提交线程：捕获允许传播的 context 快照
执行线程：安装快照
          -> try 执行业务任务
          -> finally 清理或恢复旧值
```

装饰器应回答三个问题：

1. 哪些字段允许跨线程传播，是否包含安全敏感信息。
2. worker 原本已有值时，是覆盖后 remove，还是保存并恢复；嵌套任务需要明确策略。
3. 提交失败、任务取消和任务抛异常时，谁负责释放捕获快照。

不要反射复制执行线程的整张 ThreadLocalMap。Map 中可能包含线程池、JDK 或其他框架的私有状态，盲目传播会制造更隐蔽的耦合和泄漏。

## InheritableThreadLocal 使用另一张 map

InheritableThreadLocal 只重写两个包级钩子：

```text
getMap(thread)    -> thread.inheritableThreadLocals
createMap(...)    -> thread.inheritableThreadLocals = new ThreadLocalMap(...)
```

普通 get/set/remove 和开放寻址算法都复用 ThreadLocal 实现。区别不在每次 get 时“向父线程查询”，而在线程构造时复制父线程的 inheritableThreadLocals。

## 继承发生在线程对象构造时

OpenJDK 8 的 Thread.init 在构造线程对象时执行：

```text
parent = Thread.currentThread()
if inheritThreadLocals && parent.inheritableThreadLocals != null:
  child.inheritableThreadLocals =
      ThreadLocal.createInheritedMap(parent.inheritableThreadLocals)
```

因此快照时点是 `new Thread(...)`，不是 `child.start()`：

```text
parent.set("v1")
child = new Thread(task)  // 此时复制 v1
parent.set("v2")
child.start()             // child 初始仍是 v1
```

复制构造器遍历父 map 的活 Entry，对每个 key 调用 `key.childValue(parentValue)`，再按相同 ThreadLocal hash 插入子 map。默认 InheritableThreadLocal.childValue 直接返回 parentValue，因此可变 value 默认是父子共享同一对象，不是深复制。

childValue 在构造线程中、子线程启动前调用。自定义实现不应假定当前线程已经是 child，也不应执行依赖 child 运行的阻塞逻辑。

## 为什么 InheritableThreadLocal 不适合线程池任务传播

线程池 worker 通常在首次提交或预启动时创建一次，之后执行成千上万个任务。继承只发生在 worker Thread 构造时：

- 第一次创建 worker 时可能意外复制提交线程的值。
- 后续提交不会重新构造 worker，不会获得新的父线程快照。
- worker 自己修改或遗留的值会继续影响后续任务。

因此它既可能传播过期上下文，也可能完全没有传播调用方当前上下文。在线程池中需要显式任务装饰器、框架提供的上下文传播能力或参数传递，而不是依赖 InheritableThreadLocal。

## 继承关闭能力的版本边界

JDK 8 的常用 Thread 构造器默认允许继承，内部 init 参数可为框架创建线程时关闭。Java 9 增加公开五参数 Thread 构造器，其中 boolean 可以禁止继承。

JDK 21 的平台线程和虚拟线程 builder 都支持：

```java
Thread.ofVirtual()
      .inheritInheritableThreadLocals(false)
      .start(task);
```

这是较新 JDK API，不能写进本项目按 Java 8 编译的实验源码。关闭继承适合明确不需要父上下文的基础设施线程，但如果已有代码依赖继承，应先盘点再调整。

## 虚拟线程中的 ThreadLocal

JDK 21 中，每个虚拟线程也是 Thread 对象，普通 ThreadLocal 绑定在虚拟线程自己的 threadLocals 上。虚拟线程挂载到不同 carrier 时，`Thread.currentThread()` 仍表示该虚拟线程，所以普通 ThreadLocal 不会因为迁移 carrier 就自动串到另一个虚拟线程。

需要把三个结论分开：

- 每任务新建虚拟线程通常避免平台线程池那种跨任务复用污染，因为任务结束时虚拟线程也结束。
- ThreadLocal value 仍会在虚拟线程整个生命期保留；海量虚拟线程各自保存大对象会形成显著总内存。
- 不要依赖 carrier 平台线程的普通 ThreadLocal 向虚拟线程传播。JDK 内部有 CarrierThreadLocal 支持自身机制，但它不是普通业务上下文 API。

虚拟线程不应被业务再次放进自建池中复用。对只读、词法作用域的上下文，JDK 21 的 ScopedValue 是预览 API，可减少任意 set/remove 生命周期管理；它不是 Java 8 可用能力，也不是所有可变线程状态的直接替代品。

## 异步边界应显式建模

CompletableFuture、消息消费回调和异步框架都可能切换执行线程。一个可靠上下文传播协议至少包含：

```text
capture：在提交点复制必要且允许传播的数据
install：在真正执行前建立当前线程绑定
invoke：执行业务
restore：finally 中恢复旧绑定或 remove
```

若上下文本来就能作为方法参数传递，优先显式参数；它更容易测试、审计和跨线程。ThreadLocal 适合框架边界或深层同步调用链，不应成为隐藏所有依赖的全局通道。

## 排查清单

- 找到每个 set/withInitial 首次 get 的入口和对应 remove。
- 确认异常、超时、取消和拒绝路径是否都经过 finally。
- 区分 static key 一直存活的跨任务污染，与 key 被回收后的 stale value 滞留。
- 核对线程池 worker 的真实创建线程和创建时点，不假定每次 submit 都继承。
- 检查 value 是否引用请求、ClassLoader、大缓存或不可关闭资源。
- JDK 21 虚拟线程场景按每虚拟线程占用评估，不按 carrier 数量估算。
