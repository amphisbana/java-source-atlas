# Java Source Atlas

Java Source Atlas（Java 源码地图）是一个面向 Java 程序员的源码学习项目。它不只给出源码结论，而是把真实问题、源码入口、调用链、推荐断点、可运行案例和版本差异组织成一条可验证的学习路径。

当前已完成 29 个专题：24 个 JDK 核心机制专题，以及 Spring IOC、AOP、事务、Boot 自动装配与 MVC 五条主线。

站点现已提供 37 个可逐步观察的源码演示，覆盖 JMM 发布与原子性、synchronized 重入与 wait-notify、集合结构、线程生命周期、并发容器、任务与线程池状态机、引用处理、NIO 事件循环、工作窃取、Stream 并行拆分、类加载、反射与动态代理、AQS、JDK 版本差异，以及 Spring 容器刷新、配置类解析、依赖候选筛选、Bean 生命周期、AOP、事务、自动装配、MVC 请求链和跨模块协作。

源码索引工作台统一检索 29 个专题的源码入口、关联类和推荐断点，并在浏览器本地记录主线阅读与实验完成进度。

完整学习路线把 29 个专题排成 6 个阶段，每站给出先修关系、阅读目标、真实源码入口、断点实验、过关问题、预计投入和完成条件；阅读与实验进度和源码索引共用同一份本地记录。

JDK 版本对比工作台使用 `jdk8u412-b08`、`jdk-17+35`、`jdk-21+35` 三份固定快照，先覆盖 HashMap、ConcurrentHashMap、ThreadLocal、CompletableFuture、ClassLoader / ServiceLoader，提供 21 条精选方法与实现差异、双栏源码、反向比较和迁移影响。

Spring 深挖把 Boot、IOC、AOP、MVC 与 Transaction 放回同一条启动和请求调用栈，补充早期代理一致性、线程事务资源、异常回滚与 HTTP 异常解析的跨模块边界。

IDEA 插件 0.2.3 提供 `Source Atlas` 工具窗口、参数级方法匹配、IDE 内嵌教程、源码反向跳转、gutter 图标、推荐断点一键添加、Lab 打开与 Debug，以及 JDK/Spring/Boot 结构化版本提示。插件默认连接公开教程站点，并在构建时直接复用 `source-index`，29 个专题的教程、源码、断点和 Lab 坐标只维护一份。

## 已完成内容

- HashMap 的数组、链表与红黑树结构
- `put()` 到 `putVal()` 的完整写入流程
- 哈希扰动、桶下标与容量为何必须是 2 的幂
- `resize()` 扩容和高低位拆分原理
- 链表树化、反树化及 `MIN_TREEIFY_CAPACITY`
- JDK 7、JDK 8、JDK 17/21 的关键差异
- 可直接运行和断点调试的 Maven 实验
- 覆盖写入、扩容、碰撞与空键的自动化测试
- ArrayList 的数组扩容、元素搬移、迭代器和 SubList 视图
- LinkedHashMap 的双向顺序链、访问顺序、扩展钩子与 LRU 淘汰
- TreeMap 的比较路径、红黑树重着色与旋转、导航方法和范围视图
- JMM 的 happens-before 推理、volatile 发布与复合原子性、final 构造语义，以及 Unsafe 到 VarHandle 的版本边界
- synchronized 的字节码、重入和 happens-before，以及 JDK 8 ObjectMonitor 的入口竞争、WaitSet、wait-notify 与中断边界
- AtomicInteger 的 CAS 更新、函数式重试与 LongAdder/Striped64 分段计数
- Thread 的启动、状态、join 与中断，以及 LockSupport 的一位 permit、blocker 和定时等待
- ConcurrentHashMap 的 CAS 空桶写入、桶锁、协作扩容与分段计数
- ConcurrentLinkedQueue 的 CAS 入队、滞后 tail、逻辑删除与弱一致遍历
- CopyOnWriteArrayList 的无锁快照读取、写时复制、迭代器与 SubList 边界
- BlockingQueue 的四组操作语义、条件等待、双锁队列与零容量移交
- Reference 的可达性、pending/queue 协作，以及 WeakHashMap 弱 key、强 value 与惰性 expunge
- ThreadLocalMap 的开放寻址、弱引用 key、stale Entry 清理与线程池边界
- CompletableFuture 的结果编码、Completion 栈、组合、异常传播和取消
- FutureTask 的状态机、等待栈、取消中断与周期任务复用基础
- ThreadPoolExecutor 的 ctl、execute 三步决策、Worker 循环与关闭状态机
- ScheduledThreadPoolExecutor 的延迟堆、leader 等待、周期重排和关闭策略
- ForkJoinPool 的本地队列、工作窃取、fork/join 帮助执行与阻塞补偿
- Stream 与 Spliterator 的惰性流水线、Sink 链、短路和并行拆分
- ByteBuffer 的 position/limit/mark 状态机、直接内存边界，以及 Selector 的注册、选择、wakeup 与取消
- ClassLoader 的委派、类型身份、TCCL，以及 ServiceLoader 的惰性 SPI 发现与缓存
- Reflection 的成员发现、访问与 MethodAccessor，以及 JDK Dynamic Proxy 的生成、缓存和分派
- AQS 同步队列、ReentrantLock 公平性、可重入与 Condition 条件队列
- Spring IOC 的 BeanDefinition 注册、`refresh()`、配置类扫描/Import/full-lite、依赖候选筛选、完整 Bean 生命周期、三级缓存、早期代理身份、扩展点和销毁
- Spring AOP 的自动代理创建、JDK/CGLIB 选择、Advisor 匹配、MethodInterceptor 链与自调用边界
- Spring Transaction 的元数据解析、传播行为、挂起恢复、提交回滚与 rollback-only
- Spring Boot 的启动阶段、自动配置候选加载、条件过滤、配置绑定和用户 Bean 回退
- Spring MVC 的路由匹配、拦截器、参数解析、Controller 调用、返回值与异常处理
- 支持上一步、下一步、自动播放和重置的源码状态动画，并适配移动端与减少动态效果设置

## 本地运行

运行 Java 实验和测试：

```bash
mvn test
mvn -pl labs/jdk-labs exec:java
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=ioc
```

启动文档站：

```bash
npm install
npm run docs:dev
```

构建静态文档：

```bash
npm run docs:build
```

构建并调试 IDEA 插件：

```bash
cd idea-plugin
./gradlew test verifyPlugin buildPlugin
./gradlew runIde
```

## 阅读入口

- [JDK 到 Spring 完整学习路线](docs/learning-path/index.md)
- [源码索引工作台](docs/source-explorer/index.md)
- [JDK 8 / 17 / 21 版本对比](docs/jdk/version-comparison/index.md)
- [Spring 源码学习路线](docs/spring/index.md)
- [Spring 核心链路深挖](docs/spring/deep-dive/index.md)
- [JDK 专题入口](docs/index.md)
- [HashMap 学习路径](docs/jdk/collections/hashmap/index.md)
- [LinkedHashMap 学习路径](docs/jdk/collections/linkedhashmap/index.md)
- [TreeMap 学习路径](docs/jdk/collections/treemap/index.md)
- [ArrayList 学习路径](docs/jdk/collections/arraylist/index.md)
- [JMM、volatile、final 与 VarHandle 学习路径](docs/jdk/concurrent/jmm/index.md)
- [synchronized、ObjectMonitor 与 wait-notify 学习路径](docs/jdk/concurrent/synchronized-monitor/index.md)
- [Atomic 与 Striped64 学习路径](docs/jdk/concurrent/atomic/index.md)
- [Thread 与 LockSupport 学习路径](docs/jdk/concurrent/thread-locksupport/index.md)
- [ConcurrentHashMap 学习路径](docs/jdk/concurrent/concurrenthashmap/index.md)
- [ConcurrentLinkedQueue 学习路径](docs/jdk/concurrent/concurrentlinkedqueue/index.md)
- [CopyOnWriteArrayList 学习路径](docs/jdk/concurrent/copyonwritearraylist/index.md)
- [BlockingQueue 学习路径](docs/jdk/concurrent/blockingqueue/index.md)
- [Reference 与 WeakHashMap 学习路径](docs/jdk/runtime/reference-weakhashmap/index.md)
- [ThreadLocal 学习路径](docs/jdk/concurrent/threadlocal/index.md)
- [CompletableFuture 学习路径](docs/jdk/concurrent/completablefuture/index.md)
- [FutureTask 学习路径](docs/jdk/concurrent/futuretask/index.md)
- [ThreadPoolExecutor 学习路径](docs/jdk/concurrent/threadpoolexecutor/index.md)
- [ScheduledThreadPoolExecutor 学习路径](docs/jdk/concurrent/scheduledthreadpoolexecutor/index.md)
- [ForkJoinPool 学习路径](docs/jdk/concurrent/forkjoinpool/index.md)
- [Stream 与 Spliterator 学习路径](docs/jdk/functional/stream/index.md)
- [ByteBuffer 与 Selector 学习路径](docs/jdk/io/nio/index.md)
- [ClassLoader 与 ServiceLoader 学习路径](docs/jdk/runtime/classloader/index.md)
- [Reflection 与 JDK Dynamic Proxy 学习路径](docs/jdk/runtime/reflection-proxy/index.md)
- [AQS 与 ReentrantLock 学习路径](docs/jdk/concurrent/locks/index.md)
- [Spring IOC 学习路径](docs/spring/ioc/index.md)
- [Spring AOP 学习路径](docs/spring/aop/index.md)
- [Spring Transaction 学习路径](docs/spring/transaction/index.md)
- [Spring Boot 自动装配学习路径](docs/spring/boot-autoconfigure/index.md)
- [Spring MVC 学习路径](docs/spring/mvc/index.md)
- [贡献指南](CONTRIBUTING.md)
- [源码引用与许可证说明](docs/reference/source-license.md)
- [安全说明](SECURITY.md)

## 版本基线

JDK 专题以 OpenJDK 8u 为主基线，并使用本项目测试在 Java 8 release 目标下验证公开行为；版本工作台额外固定 OpenJDK 17 与 21 GA 快照，源码行号只在固定 Tag 内作为辅助定位。Spring Framework 专题以 5.3.39 为主基线，Boot 自动装配以仍支持 Java 8 的 2.7.18 为主基线，并标注 Spring 6 / Boot 3 的重要变化。仓库、Tag、类名和方法签名仍是主要索引。

## License

项目原创文档和示例代码采用 [Apache License 2.0](LICENSE) 许可。OpenJDK 等第三方源码仍遵循其原始许可证，本项目不重新许可第三方源码。
