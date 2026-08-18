# Java 源码阅读工作台

<p class="atlas-lead">从公开 API 进入真实实现，用调用链、断点、动画和可运行实验理解 JDK 核心类与 Spring Framework。每个专题都明确版本基线，并把公开契约和内部实现分开。</p>

<div class="atlas-status">
  <span><strong>已完成专题</strong> 29</span>
  <span><strong>JDK 专题</strong> 24</span>
  <span><strong>交互演示</strong> 37</span>
  <span><strong>JDK 基线</strong> OpenJDK 8</span>
  <span><strong>Spring 基线</strong> 5.3.39 / Boot 2.7.18</span>
  <span><strong>真实版本对比</strong> JDK 8 / 17 / 21</span>
  <span><strong>实验模块</strong> 3</span>
</div>

<div class="atlas-entry-grid" role="list" aria-label="学习工具入口">
  <a role="listitem" href="./learning-path/">
    <span>按顺序学习</span>
    <strong>学习路线</strong>
    <small>从集合基础走到 Spring 请求与事务，记录每站完成情况</small>
  </a>
  <a role="listitem" href="./source-explorer/">
    <span>按问题定位</span>
    <strong>源码索引</strong>
    <small>搜索类、方法、用途和断点变量，直接进入固定版本源码</small>
  </a>
  <a role="listitem" href="./jdk/version-comparison/">
    <span>按版本理解</span>
    <strong>JDK 版本对比</strong>
    <small>并排查看 JDK 8、17、21 的真实源码坐标和关键实现变化</small>
  </a>
</div>

## 学习主线

<div class="topic-list" role="list" aria-label="JDK 源码专题">
  <a class="topic-list__row" role="listitem" href="./jdk/collections/arraylist/">
    <span class="topic-list__area">集合</span>
    <strong><code>ArrayList</code></strong>
    <span class="topic-list__question">连续数组如何扩容、搬移和快速失败？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/collections/hashmap/">
    <span class="topic-list__area">集合</span>
    <strong><code>HashMap</code></strong>
    <span class="topic-list__question">哈希碰撞、扩容与树化如何协作？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/collections/linkedhashmap/">
    <span class="topic-list__area">集合</span>
    <strong><code>LinkedHashMap</code></strong>
    <span class="topic-list__question">哈希桶之外的双向链如何维护插入顺序、访问顺序和 LRU？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/collections/treemap/">
    <span class="topic-list__area">集合</span>
    <strong><code>TreeMap</code></strong>
    <span class="topic-list__question">比较结果如何驱动查找、红黑树修复与范围导航？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/jmm/">
    <span class="topic-list__area">内存模型</span>
    <strong><code>JMM + volatile + final + VarHandle</code></strong>
    <span class="topic-list__question">跨线程可见性如何建立，发布、复合原子性与 final 构造语义又有什么边界？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/synchronized-monitor/">
    <span class="topic-list__area">内置锁</span>
    <strong><code>synchronized + ObjectMonitor</code></strong>
    <span class="topic-list__question">monitorenter 如何处理重入与竞争，wait/notify 又怎样在 WaitSet 和入口竞争之间协作？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/atomic/">
    <span class="topic-list__area">原子操作</span>
    <strong><code>AtomicInteger + LongAdder</code></strong>
    <span class="topic-list__question">单点 CAS 如何保证更新，竞争加剧后又如何把热点拆到多个 Cell？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/thread-locksupport/">
    <span class="topic-list__area">线程基础</span>
    <strong><code>Thread + LockSupport</code></strong>
    <span class="topic-list__question">线程如何启动、等待和响应中断，一位 permit 又怎样避免先唤醒后阻塞的丢失？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/locks/">
    <span class="topic-list__area">锁</span>
    <strong><code>AQS + ReentrantLock</code></strong>
    <span class="topic-list__question">获取失败的线程如何排队、阻塞和被唤醒？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/concurrenthashmap/">
    <span class="topic-list__area">并发容器</span>
    <strong><code>ConcurrentHashMap</code></strong>
    <span class="topic-list__question">无全表锁时如何安全读写和协作扩容？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/concurrentlinkedqueue/">
    <span class="topic-list__area">并发容器</span>
    <strong><code>ConcurrentLinkedQueue</code></strong>
    <span class="topic-list__question">offer 与 poll 如何用 CAS、滞后指针和协助推进维持无锁 FIFO？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/copyonwritearraylist/">
    <span class="topic-list__area">并发容器</span>
    <strong><code>CopyOnWriteArrayList</code></strong>
    <span class="topic-list__question">无锁读取如何依靠旧快照，写线程又如何安全发布新数组？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/blockingqueue/">
    <span class="topic-list__area">阻塞队列</span>
    <strong><code>BlockingQueue</code></strong>
    <span class="topic-list__question">有界缓冲区如何用锁、条件队列和容量计数协调生产者与消费者？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/runtime/reference-weakhashmap/">
    <span class="topic-list__area">引用生命周期</span>
    <strong><code>Reference + WeakHashMap</code></strong>
    <span class="topic-list__question">referent 清除、引用入队和弱键 Entry 摘除为什么是三个不同动作？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/threadlocal/">
    <span class="topic-list__area">线程上下文</span>
    <strong><code>ThreadLocal</code></strong>
    <span class="topic-list__question">值为何存在线程内部，弱引用 key 又怎样形成和清理 stale Entry？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/completablefuture/">
    <span class="topic-list__area">异步编排</span>
    <strong><code>CompletableFuture</code></strong>
    <span class="topic-list__question">结果、Completion 栈和执行器如何组成并传播依赖图？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/futuretask/">
    <span class="topic-list__area">异步结果</span>
    <strong><code>FutureTask</code></strong>
    <span class="topic-list__question">Callable 的结果、取消中断与等待线程如何汇合到一个状态机？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/threadpoolexecutor/">
    <span class="topic-list__area">线程池</span>
    <strong><code>ThreadPoolExecutor</code></strong>
    <span class="topic-list__question">一个任务为何先建核心线程、再排队、再扩线程？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/scheduledthreadpoolexecutor/">
    <span class="topic-list__area">任务调度</span>
    <strong><code>ScheduledThreadPoolExecutor</code></strong>
    <span class="topic-list__question">延迟堆如何选出到期任务，周期任务又为何需要 runAndReset 后重入队？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/concurrent/forkjoinpool/">
    <span class="topic-list__area">分治执行</span>
    <strong><code>ForkJoinPool</code></strong>
    <span class="topic-list__question">本地 LIFO、远端 FIFO 窃取和 join 帮助执行如何共同减少空等？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/functional/stream/">
    <span class="topic-list__area">函数式流水线</span>
    <strong><code>Stream + Spliterator</code></strong>
    <span class="topic-list__question">惰性阶段如何包装成 Sink 链，并通过 Spliterator 拆分为并行任务？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/io/nio/">
    <span class="topic-list__area">NIO</span>
    <strong><code>ByteBuffer + Selector</code></strong>
    <span class="topic-list__question">Buffer 状态如何切换，单线程又怎样通过事件就绪管理多个非阻塞 Channel？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/runtime/classloader/">
    <span class="topic-list__area">运行时</span>
    <strong><code>ClassLoader + ServiceLoader</code></strong>
    <span class="topic-list__question">类如何沿父链被定义，SPI 又如何借 TCCL 惰性发现并缓存 provider？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./jdk/runtime/reflection-proxy/">
    <span class="topic-list__area">运行时</span>
    <strong><code>Reflection + JDK Dynamic Proxy</code></strong>
    <span class="topic-list__question">Method 如何执行目标，代理类又怎样生成、缓存并把调用交给 Handler？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
</div>

## Spring Framework 主线

[先查看五个专题之间的完整学习路线 →](/spring/)

<div class="topic-list" role="list" aria-label="Spring Framework 源码专题">
  <a class="topic-list__row" role="listitem" href="./spring/ioc/">
    <span class="topic-list__area">核心容器</span>
    <strong><code>Spring IOC</code></strong>
    <span class="topic-list__question">BeanDefinition 如何经过 refresh、实例化、注入和初始化变成可用 Bean？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./spring/aop/">
    <span class="topic-list__area">代理机制</span>
    <strong><code>Spring AOP</code></strong>
    <span class="topic-list__question">Bean 如何被自动代理，Advisor 又怎样组成可递归推进的拦截器链？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./spring/transaction/">
    <span class="topic-list__area">事务管理</span>
    <strong><code>Spring Transaction</code></strong>
    <span class="topic-list__question">事务属性如何驱动创建、加入、挂起、提交、回滚与 rollback-only？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./spring/boot-autoconfigure/">
    <span class="topic-list__area">应用启动</span>
    <strong><code>Spring Boot Auto-configuration</code></strong>
    <span class="topic-list__question">候选自动配置如何加载、过滤，并在用户 Bean 存在时主动退让？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
  <a class="topic-list__row" role="listitem" href="./spring/mvc/">
    <span class="topic-list__area">Web 请求</span>
    <strong><code>Spring MVC</code></strong>
    <span class="topic-list__question">请求怎样完成路由、参数解析、方法调用、响应写入和异常收尾？</span>
    <span class="topic-list__action">开始阅读 →</span>
  </a>
</div>

## 从入口到验证

```text
公开 API
   ↓
核心私有方法
   ↓
关键状态与分支
   ↓
断点变量
   ↓
自动化行为测试
```

源码行号会随上游提交变化，因此项目使用“仓库、版本、类名、方法签名”作为稳定索引。内部字段通过源码断点观察，不通过深反射写成业务依赖。

## 运行全部实验

```bash
mvn test
```

单独运行一个调试入口：

```bash
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.HashMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.ArrayListDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.LinkedHashMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.TreeMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.JmmVolatileDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.SynchronizedMonitorDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.AtomicDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ThreadLockSupportDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ConcurrentHashMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ConcurrentLinkedQueueDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.CopyOnWriteArrayListDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.BlockingQueueDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.reference.ReferenceWeakHashMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ThreadLocalDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.CompletableFutureDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.FutureTaskDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ThreadPoolExecutorDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ScheduledThreadPoolExecutorDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ForkJoinPoolDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.stream.StreamSpliteratorDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.nio.NioBufferSelectorDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.runtime.ClassLoaderServiceLoaderDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.runtime.ReflectionProxyDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.lock.ReentrantLockDebugLab
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=ioc
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=aop
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=transaction
mvn -pl labs/spring-boot-lab compile exec:java
mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=mvc
```

## 阅读完成标准

- 能从公开方法画出核心调用链。
- 能说明关键状态变量及其不变量。
- 能给出触发主要分支的最小输入。
- 能区分 API 保证、当前实现细节和并发边界。
- 能用测试验证对外行为，用断点验证内部过程。
