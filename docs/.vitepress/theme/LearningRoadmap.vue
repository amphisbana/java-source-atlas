<script setup lang="ts">
import { withBase } from 'vitepress'
import { computed, onMounted, ref } from 'vue'
import {
  findSourceForMethod,
  githubSourceUrl,
  sourceTopics,
  topicHomeUrl,
  topicLabUrl,
  type SourceBreakpoint,
  type SourceEntryPoint,
  type SourceTopic
} from './source-explorer-data'
import {
  loadLearningProgress,
  updateTopicProgress,
  type TopicProgress
} from './learning-progress'

type RoadmapView = 'next' | 'all'
type ProgressField = 'readMain' | 'ranLab'

interface RoadmapStopDefinition {
  topicId: string
  shortTitle: string
  prerequisite: string
  whyNow: string
  readingGoal: string
  passQuestion: string
  completion: string
  minutes: number
}

interface RoadmapPhaseDefinition {
  id: string
  title: string
  kicker: string
  prerequisite: string
  reason: string
  accent: string
  stops: RoadmapStopDefinition[]
}

interface RoadmapStop extends RoadmapStopDefinition {
  order: number
  phaseId: string
  phaseTitle: string
  topic: SourceTopic
  entry: SourceEntryPoint
  breakpoint: SourceBreakpoint
  sourceUrl: string
  nextTopic?: SourceTopic
  nextReason?: string
}

interface RoadmapPhase extends Omit<RoadmapPhaseDefinition, 'stops'> {
  stops: RoadmapStop[]
}

const QUESTION_PROGRESS_KEY = 'java-source-atlas:roadmap-question-progress:v1'

const ROADMAP_PHASES: RoadmapPhaseDefinition[] = [
  {
    id: 'collections',
    title: '集合与数据结构',
    kicker: '先建立源码阅读手感',
    prerequisite: '会使用 Java 集合、理解数组与引用',
    reason: '集合类状态少、调用链短，最适合先练习“字段 → 入口 → 分支 → 边界”的源码阅读方法；随后再把同一套方法带入并发源码。',
    accent: '#0f766e',
    stops: [
      {
        topicId: 'openjdk8-java-util-arraylist',
        shortTitle: 'ArrayList',
        prerequisite: '数组、下标与 System.arraycopy',
        whyNow: '先从连续内存和短调用链开始，建立容量、逻辑大小和结构性修改三个基本概念。',
        readingGoal: '沿 add、ensureCapacityInternal、grow、remove 追踪 size 与 elementData 的变化，并识别 modCount 的职责。',
        passQuestion: '为什么 size 不等于 elementData.length，删除元素后又为什么必须清空数组尾部？',
        completion: '能画出一次扩容前后的数组，并解释 fail-fast 只是尽力检测。',
        minutes: 90
      },
      {
        topicId: 'openjdk8-java-util-hashmap',
        shortTitle: 'HashMap',
        prerequisite: 'ArrayList 的容量与扩容思路、hashCode 与 equals',
        whyNow: '从线性数组进入桶结构，开始训练对哈希扰动、冲突、阈值和结构转换的联合推理。',
        readingGoal: '完整串起 hash、putVal、resize、treeifyBin、getNode 与 removeNode，掌握数组、链表、红黑树三种形态。',
        passQuestion: '为什么容量保持 2 的幂，扩容时旧节点又为什么只需要判断 oldCap 那一位？',
        completion: '能手算 key 的桶下标，并画出一次链表拆分到新表的过程。',
        minutes: 180
      },
      {
        topicId: 'openjdk8-java-util-linkedhashmap',
        shortTitle: 'LinkedHashMap',
        prerequisite: 'HashMap 的 Node、putVal 和扩展钩子',
        whyNow: '它复用 HashMap 存储，却用双向链表表达另一种顺序，是理解模板方法与扩展钩子的直接案例。',
        readingGoal: '区分桶内 next 与顺序链 before/after，跟踪 newNode、afterNodeAccess、afterNodeInsertion 的调用时机。',
        passQuestion: 'accessOrder=true 时，一次 get 为什么可能增加 modCount 并让迭代器快速失败？',
        completion: '能说明插入顺序、访问顺序和 LRU 淘汰分别由哪段逻辑负责。',
        minutes: 120
      },
      {
        topicId: 'openjdk8-java-util-treemap',
        shortTitle: 'TreeMap',
        prerequisite: '二叉搜索树、Comparator 与 HashMap 树节点概念',
        whyNow: '用稳定有序的红黑树补齐集合结构版图，并为后续阅读并发结构中的树化与范围视图打底。',
        readingGoal: '沿 put 定位插入点，再逐步观察 fixAfterInsertion、旋转、重着色、导航查询和 SubMap 边界。',
        passQuestion: '红黑树为什么能把最长查找路径限制在最短路径的两倍以内？',
        completion: '能根据一个插入序列判断何时变色、何时旋转，并解释范围视图不是副本。',
        minutes: 180
      }
    ]
  },
  {
    id: 'concurrency',
    title: '并发状态与协作',
    kicker: '从原子更新走到容器协议',
    prerequisite: '完成集合阶段；了解线程、volatile 与 happens-before 基础',
    reason: '先掌握 CAS 和 AQS 两套底层语言，再阅读线程隔离及并发容器，才能分清“无锁、分段锁、快照、条件等待”各自在解决什么问题。',
    accent: '#2563eb',
    stops: [
      {
        topicId: 'openjdk8-jmm-volatile-final',
        shortTitle: 'JMM 与内存语义',
        prerequisite: '线程基础、普通字段与 final 字段',
        whyNow: '先掌握 happens-before、发布和复合原子性，后续读到 volatile、CAS、锁与任务状态时才能判断它们真正保证了什么。',
        readingGoal: '画出 volatile、start、join、monitor 与 final 的正式边界，并区分可见性、原子性和有序性。',
        passQuestion: '为什么读到 volatile ready=true 后能看到此前普通 payload，却仍不能让 volatile count++ 原子？',
        completion: '能为一段双线程代码画出 happens-before 图，并识别仍然存在的数据竞争。',
        minutes: 150
      },
      {
        topicId: 'openjdk8-synchronized-objectmonitor',
        shortTitle: 'synchronized 与 ObjectMonitor',
        prerequisite: 'JMM 的 monitor happens-before、线程状态基础',
        whyNow: '内置 monitor 是 Java 最基础的互斥与条件协作机制，也是理解 BLOCKED、WAITING、重入和后续 AQS Condition 的参照物。',
        readingGoal: '从 monitorenter/monitorexit 进入 owner 与重入，再跟踪 wait 完整释放、notify 转移和重新竞争。',
        passQuestion: 'notify 已经执行后，被通知线程为什么仍不能立刻从 wait 的下一行继续？',
        completion: '能区分入口竞争集合与 WaitSet，并写出正确处理中断、超时和伪唤醒的条件循环。',
        minutes: 180
      },
      {
        topicId: 'openjdk8-java-util-concurrent-atomic-striped64',
        shortTitle: 'Atomic 与 Striped64',
        prerequisite: 'JMM 的可见性、volatile 发布、CAS 与缓存竞争的基本概念',
        whyNow: 'CAS 是后续无锁队列、并发计数和任务状态机反复出现的共同语言。',
        readingGoal: '比较 AtomicInteger 的单点 CAS 与 LongAdder 的 base/cells 分流，理解 contention 下的扩容和探针迁移。',
        passQuestion: 'LongAdder 为什么吞吐量更高，却不能把 sum 当作并发时刻的线性一致快照？',
        completion: '能说清 CAS 重试、ABA 边界，以及 Striped64 的三级更新路径。',
        minutes: 120
      },
      {
        topicId: 'openjdk8-thread-locksupport',
        shortTitle: 'Thread 与 LockSupport',
        prerequisite: '线程基础、volatile 与一位许可模型',
        whyNow: 'AQS、FutureTask 和线程池最终都会落到线程启动、中断、park 与 unpark；先把底层线程协议读清，后续等待队列不再是黑盒。',
        readingGoal: '追踪 Thread.start/start0/run、状态映射、LockSupport 的一位 permit、blocker 诊断、中断和定时等待。',
        passQuestion: 'unpark 为什么可以早于 park，连续两次 unpark 又为什么不能让后续两个 park 都直接返回？',
        completion: '能画出线程从 NEW 到 TERMINATED 的主线，并用条件循环正确处理 park 的多种返回原因。',
        minutes: 150
      },
      {
        topicId: 'openjdk8-reentrantlock-aqs',
        shortTitle: 'AQS 与 ReentrantLock',
        prerequisite: 'Atomic CAS、LockSupport.park/unpark',
        whyNow: 'AQS 把同步状态、FIFO 等待队列和阻塞唤醒组合成模板，是多数并发工具的共同骨架。',
        readingGoal: '追踪 lock、acquire、addWaiter、acquireQueued、release，并区分同步队列与 Condition 条件队列。',
        passQuestion: 'Condition.signal 为什么不能直接唤醒并让线程立刻继续，而要先转移到同步队列？',
        completion: '能画出节点从条件队列转移、重新竞争锁到恢复执行的完整路径。',
        minutes: 180
      },
      {
        topicId: 'openjdk8-reference-weakhashmap',
        shortTitle: 'Reference 与 WeakHashMap',
        prerequisite: '对象可达性、哈希桶与 GC 基础',
        whyNow: '先区分 referent 清除、Reference 入队和容器 expunge，才能准确理解 ThreadLocal 弱 key 以及框架元数据缓存的生命周期。',
        readingGoal: '追踪 Reference 状态与队列协议、WeakHashMap Entry 弱 key、强 value、getTable 与 expungeStaleEntries。',
        passQuestion: 'WeakHashMap 的 key 已清除后，value 为什么仍可能暂时存活，value 回指 key 又会造成什么结果？',
        completion: '能画出从强路径消失到 Entry 摘除的完整时间线，并说明 GC 时机为什么不能成为测试断言。',
        minutes: 150
      },
      {
        topicId: 'openjdk8-java-lang-threadlocal',
        shortTitle: 'ThreadLocal',
        prerequisite: '弱引用、开放寻址和线程生命周期',
        whyNow: '它把数据放在线程而不是 ThreadLocal 对象中，能训练“所有权决定生命周期”的并发分析方式。',
        readingGoal: '沿 get、set、getEntryAfterMiss、replaceStaleEntry、expungeStaleEntry 理解散列探测与过期清理。',
        passQuestion: 'ThreadLocalMap 的 key 已被回收后，value 为什么仍可能在线程池中长期存活？',
        completion: '能解释弱 key、强 value、惰性清理和 finally remove 之间的关系。',
        minutes: 120
      },
      {
        topicId: 'openjdk8-java-util-concurrent-concurrenthashmap',
        shortTitle: 'ConcurrentHashMap',
        prerequisite: 'HashMap 桶结构、Atomic CAS、synchronized',
        whyNow: '它把已掌握的桶结构与 CAS、桶锁、协作扩容组合起来，是并发容器的核心综合题。',
        readingGoal: '追踪 get 无锁读取、putVal 空桶 CAS、桶头锁、ForwardingNode、transfer 分片和 CounterCell 计数。',
        passQuestion: '扩容期间读线程遇到 MOVED 节点为什么不会返回旧结果，写线程又如何领取迁移区间？',
        completion: '能分场景说明空桶、普通桶、树桶和迁移桶分别由谁同步。',
        minutes: 180
      },
      {
        topicId: 'openjdk8-java-util-concurrent-copyonwritearraylist',
        shortTitle: 'CopyOnWriteArrayList',
        prerequisite: 'ArrayList、ReentrantLock、volatile 可见性',
        whyNow: '用“写时复制 + volatile 发布”展示读多写少场景中，以空间换取无锁读取的完整取舍。',
        readingGoal: '比较 get 的快照读取、add 的加锁复制、setArray 发布，以及迭代器持有旧数组后的行为。',
        passQuestion: '为什么迭代期间不会抛 ConcurrentModificationException，却可能永远看不到刚写入的元素？',
        completion: '能判断它适合与不适合的负载，并解释快照一致性而非实时一致性。',
        minutes: 90
      },
      {
        topicId: 'openjdk8-java-util-concurrent-blockingqueue',
        shortTitle: 'BlockingQueue',
        prerequisite: 'AQS Condition、锁与生产者消费者模型',
        whyNow: '阻塞队列把锁、条件等待、容量和移交语义落到可运行场景，是进入线程池前必须掌握的任务通道。',
        readingGoal: '比较 ArrayBlockingQueue 单锁双条件、LinkedBlockingQueue 双锁级联通知，以及 SynchronousQueue 的零容量匹配。',
        passQuestion: 'LinkedBlockingQueue 为什么需要原子 count，put 和 take 又怎样跨两把锁完成必要通知？',
        completion: '能为有界缓冲、吞吐优先和直接移交三类场景选择正确队列。',
        minutes: 150
      },
      {
        topicId: 'openjdk8-java-util-concurrent-concurrentlinkedqueue',
        shortTitle: 'ConcurrentLinkedQueue',
        prerequisite: 'Atomic CAS、链表节点与线性化点',
        whyNow: '在学过阻塞队列后再看无锁队列，更容易区分“线程安全”与“阻塞协调”不是同一件事。',
        readingGoal: '追踪 offer 的 casNext、滞后 tail、自链接恢复，以及 poll 的 casItem、updateHead 和弱一致迭代。',
        passQuestion: 'tail 为什么被允许落后，旧 head 指向自己又如何帮助遍历摆脱脱链节点？',
        completion: '能标出 offer 与 poll 的线性化点，并解释 size 为什么只能近似遍历。',
        minutes: 120
      }
    ]
  },
  {
    id: 'async',
    title: '任务、线程池与异步编排',
    kicker: '看懂任务如何被接收、等待和完成',
    prerequisite: '完成并发阶段，尤其是 CAS、AQS 与 BlockingQueue',
    reason: '线程池负责执行资源，FutureTask 负责单个结果，调度池负责时间，ForkJoinPool 负责分治，CompletableFuture 再把它们组织成依赖图。',
    accent: '#b45309',
    stops: [
      {
        topicId: 'openjdk8-java-util-concurrent-threadpoolexecutor',
        shortTitle: 'ThreadPoolExecutor',
        prerequisite: 'BlockingQueue、AQS 与线程中断',
        whyNow: '先建立“任务提交不等于创建线程”的执行模型，后面的 Future 和调度器才有明确落点。',
        readingGoal: '拆解 ctl 高低位、execute 三步决策、addWorker 双检、runWorker 循环、getTask 回收和 shutdown 收口。',
        passQuestion: '核心线程未满、队列未满、最大线程未满三个条件为什么按这个顺序判断？',
        completion: '能给定参数和任务序列，逐个判断入队、扩线程或拒绝。',
        minutes: 150
      },
      {
        topicId: 'openjdk8-java-util-concurrent-futuretask',
        shortTitle: 'FutureTask',
        prerequisite: 'ThreadPoolExecutor、CAS 与 LockSupport',
        whyNow: '它把 Runnable、Future、结果状态和等待线程连在一起，是理解异步结果最小而完整的状态机。',
        readingGoal: '追踪 run 执行权、NEW 到终态的两阶段发布、Treiber 等待栈、finishCompletion 与取消中断。',
        passQuestion: 'cancel(true) 成功为什么不代表任务代码已经停止，INTERRUPTING 又为什么必须过渡到 INTERRUPTED？',
        completion: '能画出正常、异常、取消三条状态路径，并指出 get 的阻塞和唤醒位置。',
        minutes: 120
      },
      {
        topicId: 'openjdk8-java-util-concurrent-scheduledthreadpoolexecutor',
        shortTitle: 'ScheduledThreadPoolExecutor',
        prerequisite: 'ThreadPoolExecutor、FutureTask 与优先队列',
        whyNow: '它在普通线程池之上增加时间顺序、leader 等待和周期任务重排，展示继承扩展时哪些语义必须重写。',
        readingGoal: '追踪 ScheduledFutureTask 包装、triggerTime、DelayedWorkQueue、leader/follower 等待、runAndReset 和取消策略。',
        passQuestion: '固定频率与固定延迟为什么使用不同的 period 符号，周期任务又为什么不能每次创建新 Future？',
        completion: '能根据执行耗时推演两种周期策略的下一次触发时间。',
        minutes: 120
      },
      {
        topicId: 'openjdk8-java-util-concurrent-forkjoinpool',
        shortTitle: 'ForkJoinPool',
        prerequisite: 'ThreadPoolExecutor、双端队列、CAS 与递归分治',
        whyNow: '先理解工作窃取和 join 帮助执行，才能看懂并行 Stream 与 CompletableFuture 默认执行器的行为。',
        readingGoal: '区分 externalPush 与 fork，观察 WorkQueue 的 LIFO/FIFO 两端、scan 窃取、join 帮助和 managedBlock 补偿。',
        passQuestion: '工作线程 join 子任务时为什么不只是阻塞等待，而要优先帮助执行相关任务？',
        completion: '能画出根任务拆分、入队、被窃取、合并结果的线程轨迹。',
        minutes: 150
      },
      {
        topicId: 'openjdk8-java-util-concurrent-completablefuture',
        shortTitle: 'CompletableFuture',
        prerequisite: 'FutureTask、ForkJoinPool 与函数式接口',
        whyNow: '在理解执行器和单任务状态后，再阅读 Completion 依赖栈，才能把“任务在哪跑”和“完成后触发谁”分开。',
        readingGoal: '追踪 result 编码、Completion 栈注册、uniApply/biApply、postComplete 传播、allOf 树和异常包装。',
        passQuestion: 'thenApply 与 thenCompose 的依赖图有什么本质区别，cancel 又为什么通常不会中断底层任务？',
        completion: '能画出一个串行加汇聚流程，并预测正常值、异常和取消如何传播。',
        minutes: 150
      }
    ]
  },
  {
    id: 'runtime',
    title: '函数式流水线与运行时机制',
    kicker: '把执行模型连接到 Java 运行时',
    prerequisite: '完成集合与异步阶段，了解 Lambda 基础',
    reason: 'Stream 把集合与 ForkJoin 串起来；NIO 建立缓冲区与事件循环模型；类加载决定类型从哪里来；反射与动态代理决定框架怎样发现并包装对象。四者共同通向 Spring 与常见网络框架。',
    accent: '#be123c',
    stops: [
      {
        topicId: 'openjdk8-java-util-stream-spliterator',
        shortTitle: 'Stream 与 Spliterator',
        prerequisite: 'ArrayList、ForkJoinPool 与函数式接口',
        whyNow: '它是前面集合、任务拆分和函数组合的第一次汇合，可训练“声明阶段”和“执行阶段”分离的阅读方式。',
        readingGoal: '区分 Pipeline stage 与 Sink 链，追踪 evaluate、wrapSink、copyInto、短路取消、trySplit 和 AbstractTask。',
        passQuestion: '多个中间操作为什么只需一次数据遍历，并行拆分顺序又为什么不等于最终结果顺序？',
        completion: '能手推 filter-map-limit 的 begin/accept/cancellationRequested/end 调用序列。',
        minutes: 150
      },
      {
        topicId: 'openjdk8-bytebuffer-selector',
        shortTitle: 'ByteBuffer 与 Selector',
        prerequisite: '数组边界、线程阻塞与事件驱动基础',
        whyNow: 'NIO 把状态机、非阻塞通道和单线程多路复用组合起来，是理解网络框架事件循环前必须建立的运行时模型。',
        readingGoal: '手推 position、limit、capacity、mark 的转换，并追踪 Channel 注册、interestOps、select、selectedKeys、wakeup 与取消清理。',
        passQuestion: 'flip 与 compact 分别保留什么数据，Selector 循环又为什么必须移除 selectedKeys 并按需撤销 OP_WRITE？',
        completion: '能从半包读写场景画出 Buffer 状态变化，并写出不会空转、不会漏处理跨线程命令的选择循环。',
        minutes: 180
      },
      {
        topicId: 'openjdk8-classloader-serviceloader',
        shortTitle: 'ClassLoader 与 ServiceLoader',
        prerequisite: '类路径、Class 对象和接口实现',
        whyNow: 'Spring 启动、SPI 扩展和容器隔离都依赖类加载边界，先理解类型身份才能避免把同名类误认为同一类型。',
        readingGoal: '追踪 loadClass 委派、findLoadedClass、findClass、defineClass，以及 ServiceLoader 配置发现、惰性实例化和缓存。',
        passQuestion: '两个类文件内容完全相同，但由不同 ClassLoader 定义时，为什么不能相互强制转换？',
        completion: '能画出父委派链，并独立运行一个 META-INF/services SPI 示例。',
        minutes: 120
      },
      {
        topicId: 'openjdk8-reflection-dynamic-proxy',
        shortTitle: 'Reflection 与 JDK Dynamic Proxy',
        prerequisite: 'ClassLoader、Method 元数据与接口',
        whyNow: '它直接揭示 Spring 如何扫描方法、执行反射调用和用代理插入横切逻辑，是进入 IOC/AOP 前的最后一块运行时基础。',
        readingGoal: '追踪 Method 查找与 invoke accessor，再拆解 Proxy 缓存、代理类生成、InvocationHandler 分派和访问控制。',
        passQuestion: '代理方法调用时 Method、参数和返回值怎样跨过 InvocationHandler，Object 方法又有什么特殊边界？',
        completion: '能写出最小代理并解释代理 ClassLoader、接口顺序和 Method 缓存的意义。',
        minutes: 150
      }
    ]
  },
  {
    id: 'spring-core',
    title: 'Spring 容器、代理与事务',
    kicker: '从对象创建走到横切能力',
    prerequisite: '完成 ClassLoader、反射与动态代理；理解 ThreadLocal',
    reason: 'IOC 先建立 Bean 生命周期和扩展点；AOP 在 Bean 创建后包装对象；声明式事务再把 AOP 拦截链连接到资源管理。顺序与真实启动和调用链一致。',
    accent: '#7c3aed',
    stops: [
      {
        topicId: 'spring-framework-5-3-ioc',
        shortTitle: 'Spring IOC',
        prerequisite: 'ClassLoader、反射、HashMap 与模板方法',
        whyNow: 'Spring 后续所有能力都依赖容器先完成配置候选展开、定义注册、依赖裁决、Bean 创建和生命周期回调。',
        readingGoal: '以 refresh 十二阶段为总图，串起配置类 parser/reader、getBean、依赖候选筛选、doCreateBean、BeanPostProcessor 和三级缓存。',
        passQuestion: 'full/lite 配置自调用为何不同，多个同类型候选怎样经过泛型、Qualifier、Primary 和名称裁决，循环依赖中的早期引用又为何可能是代理？',
        completion: '能从一个配置入口追到 BeanDefinition 落库，并从注入点定位唯一候选，再解释该单例的实例化、填充、初始化和发布。',
        minutes: 330
      },
      {
        topicId: 'spring-framework-5-3-aop',
        shortTitle: 'Spring AOP',
        prerequisite: 'Spring IOC 生命周期、JDK Proxy 与反射调用',
        whyNow: 'AOP 是 BeanPostProcessor 对容器对象的典型增强，紧接 IOC 阅读能看清代理在何时、为何被创建。',
        readingGoal: '追踪 AbstractAutoProxyCreator、Advisor 匹配、JDK/CGLIB 选择、MethodInterceptor 链和 ReflectiveMethodInvocation.proceed。',
        passQuestion: '同类自调用为什么绕过代理，多个 Advice 又怎样按照拦截器链顺序前进和回退？',
        completion: '能判断一个 Bean 是否会被代理、使用哪种代理，并画出一次方法调用的拦截栈。',
        minutes: 180
      },
      {
        topicId: 'spring-framework-5-3-transaction',
        shortTitle: 'Spring Transaction',
        prerequisite: 'Spring AOP、ThreadLocal 与异常传播',
        whyNow: '声明式事务本质是具体的 AOP 拦截器；此时再读传播、挂起和回滚，抽象会落到清晰调用链。',
        readingGoal: '追踪 TransactionInterceptor、invokeWithinTransaction、事务获取、资源绑定、传播决策、提交与 rollback-only。',
        passQuestion: '内层 REQUIRED 标记 rollback-only 后，外层正常返回为什么仍可能抛 UnexpectedRollbackException？',
        completion: '能推演 REQUIRED、REQUIRES_NEW、NESTED 的连接持有、挂起恢复和最终提交结果。',
        minutes: 180
      }
    ]
  },
  {
    id: 'spring-app',
    title: 'Web 请求与应用启动',
    kicker: '把前面机制收束成完整应用',
    prerequisite: '完成 IOC、AOP 与事务阶段',
    reason: 'MVC 展示一次请求如何穿过容器中的策略组件；Boot 自动装配再解释这些组件如何被应用启动流程和条件配置装配出来，作为全路线收官。',
    accent: '#15803d',
    stops: [
      {
        topicId: 'spring-framework-5-3-mvc',
        shortTitle: 'Spring MVC',
        prerequisite: 'Spring IOC、AOP 拦截链与 Servlet 基础',
        whyNow: 'MVC 把策略查找、适配器、反射调用、参数解析、返回值处理和异常解析串成一条用户可观察的请求链。',
        readingGoal: '追踪 FrameworkServlet.processRequest、DispatcherServlet.doDispatch、HandlerMapping、HandlerAdapter、参数与异常解析器。',
        passQuestion: 'HandlerMapping 找到处理器后为什么还需要 HandlerAdapter，异常又在哪一层转成最终响应？',
        completion: '能从一个 HTTP 请求定位到 Controller 方法，并分别推演正常、参数错误和业务异常响应。',
        minutes: 180
      },
      {
        topicId: 'spring-boot-2-7-autoconfiguration',
        shortTitle: 'Spring Boot 自动装配',
        prerequisite: 'Spring IOC、MVC、ClassLoader/ServiceLoader 与条件判断',
        whyNow: '最后回到应用入口，把环境准备、上下文创建、配置类导入、条件筛选和属性绑定连接为完整启动模型。',
        readingGoal: '追踪 SpringApplication.run、ApplicationContext refresh、AutoConfigurationImportSelector、候选导入、Condition 与 ConfigurationProperties。',
        passQuestion: '自动配置为什么既能“自动生效”又允许用户 Bean 覆盖，条件评估又发生在 Bean 创建前的哪一层？',
        completion: '能从 run 入口定位一个自动配置 Bean 的候选发现、条件命中、属性绑定和最终创建。',
        minutes: 180
      }
    ]
  }
]

/**
 * 从专题索引的推荐关系计算一条无重复路线，并检查根节点、断链和循环。
 *
 * 推荐关系是路线的单一事实来源；学习阶段只负责提供阶段说明和先修解释。
 */
function resolveRecommendedTopicOrder(topicById: Map<string, SourceTopic>): string[] {
  const incomingTopicIds = new Set<string>()
  const nextTopicById = new Map<string, string>()

  for (const topic of topicById.values()) {
    const nextTopicId = topic.recommendedNextTopicId
    if (nextTopicId === undefined) {
      continue
    }
    if (!topicById.has(nextTopicId)) {
      throw new Error(`专题推荐目标不存在: ${topic.topicId} -> ${nextTopicId}`)
    }
    if (incomingTopicIds.has(nextTopicId)) {
      throw new Error(`专题推荐目标有多个前驱: ${nextTopicId}`)
    }
    incomingTopicIds.add(nextTopicId)
    nextTopicById.set(topic.topicId, nextTopicId)
  }

  const roots = [...topicById.values()].filter((topic) => !incomingTopicIds.has(topic.topicId))
  if (roots.length !== 1) {
    throw new Error(`学习路线必须只有一个根专题，当前找到 ${roots.length} 个`)
  }

  const orderedTopicIds: string[] = []
  const visitedTopicIds = new Set<string>()
  let currentTopicId: string | undefined = roots[0].topicId
  while (currentTopicId !== undefined) {
    if (visitedTopicIds.has(currentTopicId)) {
      throw new Error(`专题推荐关系存在循环: ${currentTopicId}`)
    }
    visitedTopicIds.add(currentTopicId)
    orderedTopicIds.push(currentTopicId)
    currentTopicId = nextTopicById.get(currentTopicId)
  }

  if (orderedTopicIds.length !== topicById.size) {
    const disconnectedTopicIds = [...topicById.keys()].filter((topicId) => !visitedTopicIds.has(topicId))
    throw new Error(`专题推荐关系存在断开的专题: ${disconnectedTopicIds.join(', ')}`)
  }
  return orderedTopicIds
}

/**
 * 将阶段说明与源码索引合并，并使用推荐关系决定专题顺序。
 *
 * 阶段必须覆盖当前全部专题；新增索引却未接入推荐链时，让文档构建立刻失败。
 */
function resolveRoadmapPhases(): RoadmapPhase[] {
  const topicById = new Map(sourceTopics.map((topic) => [topic.topicId, topic]))
  const orderedTopicIds = resolveRecommendedTopicOrder(topicById)
  const routeOrder = new Map(orderedTopicIds.map((topicId, index) => [topicId, index]))
  const seenTopicIds = new Set<string>()

  const phases = ROADMAP_PHASES.map((phase): RoadmapPhase => ({
    ...phase,
    stops: [...phase.stops]
      .sort((left, right) => (routeOrder.get(left.topicId) ?? Number.MAX_SAFE_INTEGER)
        - (routeOrder.get(right.topicId) ?? Number.MAX_SAFE_INTEGER))
      .map((definition): RoadmapStop => {
        if (seenTopicIds.has(definition.topicId)) {
          throw new Error(`学习路线包含重复专题: ${definition.topicId}`)
        }

        const topic = topicById.get(definition.topicId)
        if (topic === undefined) {
          throw new Error(`学习路线找不到源码索引: ${definition.topicId}`)
        }
        const entry = topic.entryPoints[0]
        const breakpoint = topic.breakpoints[0]
        if (entry === undefined || breakpoint === undefined) {
          throw new Error(`学习路线专题缺少源码入口或实验断点: ${definition.topicId}`)
        }

        const entrySource = findSourceForMethod(topic, entry.method, entry.sourceClass) ?? topic.source
        seenTopicIds.add(definition.topicId)
        const nextTopic = topic.recommendedNextTopicId === undefined
          ? undefined
          : topicById.get(topic.recommendedNextTopicId)
        return {
          ...definition,
          order: (routeOrder.get(definition.topicId) ?? 0) + 1,
          phaseId: phase.id,
          phaseTitle: phase.title,
          topic,
          entry,
          breakpoint,
          sourceUrl: githubSourceUrl(topic, entrySource),
          nextTopic,
          nextReason: topic.recommendedNextReason
        }
      })
  }))

  const uncoveredTopics = sourceTopics.filter((topic) => !seenTopicIds.has(topic.topicId))
  if (uncoveredTopics.length > 0) {
    throw new Error(`学习路线尚未覆盖专题: ${uncoveredTopics.map((topic) => topic.topicId).join(', ')}`)
  }

  const flattenedTopicIds = phases.flatMap((phase) => phase.stops.map((stop) => stop.topicId))
  if (flattenedTopicIds.join('\u0000') !== orderedTopicIds.join('\u0000')) {
    throw new Error('学习阶段顺序与专题推荐关系不一致')
  }
  return phases
}

const roadmapPhases = resolveRoadmapPhases()
const allStops = roadmapPhases.flatMap((phase) => phase.stops)
const validTopicIds = new Set(allStops.map((stop) => stop.topicId))
const totalMinutes = allStops.reduce((total, stop) => total + stop.minutes, 0)

const viewMode = ref<RoadmapView>('next')
const progressByTopic = ref<Record<string, TopicProgress>>({})
const answeredTopicIds = ref<Set<string>>(new Set())
const focusedTopicId = ref('')

/**
 * 返回专题的稳定进度对象，尚未记录时使用未完成默认值。
 */
function topicProgress(topicId: string): TopicProgress {
  return progressByTopic.value[topicId] ?? {
    readMain: false,
    ranLab: false,
    updatedAt: ''
  }
}

/**
 * 判断学习者是否已经确认能回答本站过关问题。
 */
function answeredQuestion(topicId: string): boolean {
  return answeredTopicIds.value.has(topicId)
}

/**
 * 统计本站三个完成动作中已经完成的数量。
 */
function stopProgressCount(stop: RoadmapStop): number {
  const progress = topicProgress(stop.topicId)
  return Number(progress.readMain) + Number(progress.ranLab) + Number(answeredQuestion(stop.topicId))
}

/**
 * 三个学习动作全部完成后，本站才计入路线和阶段完成度。
 */
function isStopComplete(stop: RoadmapStop): boolean {
  return stopProgressCount(stop) === 3
}

/**
 * 汇总一个阶段中已经完成的专题数。
 */
function phaseCompletedCount(phase: RoadmapPhase): number {
  return phase.stops.filter(isStopComplete).length
}

/**
 * 计算阶段进度百分比，供原生 progress 和文字状态共同使用。
 */
function phaseCompletionPercent(phase: RoadmapPhase): number {
  return phase.stops.length === 0
    ? 0
    : Math.round(phaseCompletedCount(phase) / phase.stops.length * 100)
}

/**
 * 汇总阶段内所有专题的建议投入时间。
 */
function phaseMinutes(phase: RoadmapPhase): number {
  return phase.stops.reduce((total, stop) => total + stop.minutes, 0)
}

/**
 * 把分钟转换成紧凑的中文时长，整小时不显示小数。
 */
function formatDuration(minutes: number): string {
  if (minutes < 60) {
    return `${minutes} 分钟`
  }
  const hours = minutes / 60
  return `${Number.isInteger(hours) ? hours : hours.toFixed(1)} 小时`
}

/**
 * 已完成专题数量会同时驱动总进度和“下一步”定位。
 */
const completedStopCount = computed(() => allStops.filter(isStopComplete).length)

/**
 * 返回路线中第一个未满足完成条件的专题。
 */
const nextStop = computed(() => allStops.find((stop) => !isStopComplete(stop)))

/**
 * 保持“下一步”模式当前卡片稳定；完成本站后由学习者显式进入下一站。
 */
const focusedStop = computed(() => (
  allStops.find((stop) => stop.topicId === focusedTopicId.value) ?? nextStop.value
))

/**
 * 定位当前展示或下一未完成专题所属阶段，为摘要区提供上下文。
 */
const currentPhase = computed(() => {
  const stop = viewMode.value === 'next' ? focusedStop.value : nextStop.value
  return stop === undefined
    ? undefined
    : roadmapPhases.find((phase) => phase.id === stop.phaseId)
})

/**
 * “下一步”模式仅保留当前专题；“全部路线”模式展示完整六阶段。
 */
const visiblePhases = computed<RoadmapPhase[]>(() => {
  if (viewMode.value === 'all') {
    return roadmapPhases
  }
  const stop = focusedStop.value
  if (stop === undefined) {
    return []
  }
  const phase = roadmapPhases.find((candidate) => candidate.id === stop.phaseId)
  return phase === undefined ? [] : [{ ...phase, stops: [stop] }]
})

/**
 * 计算全路线完成百分比。
 */
const overallCompletionPercent = computed(() => (
  allStops.length === 0 ? 0 : Math.round(completedStopCount.value / allStops.length * 100)
))

/**
 * 在学习者确认后切换到第一项未完成专题；全部完成时进入路线总结。
 */
function advanceToNextStop(): void {
  focusedTopicId.value = nextStop.value?.topicId ?? ''
}

/**
 * 安全获取浏览器存储；SSR、隐私模式和安全策略拒绝时返回 null。
 */
function getRoadmapStorage(): Storage | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    return window.localStorage
  } catch {
    return null
  }
}

/**
 * 从本地存储恢复过关问题进度，并过滤已经不在路线中的专题编号。
 */
function loadAnsweredQuestions(): Set<string> {
  const storage = getRoadmapStorage()
  if (storage === null) {
    return new Set()
  }
  try {
    const raw = storage.getItem(QUESTION_PROGRESS_KEY)
    const parsed: unknown = raw === null ? [] : JSON.parse(raw)
    if (!Array.isArray(parsed)) {
      return new Set()
    }
    return new Set(parsed.filter((value): value is string => (
      typeof value === 'string' && validTopicIds.has(value)
    )))
  } catch {
    return new Set()
  }
}

/**
 * 持久化过关问题进度；写入失败只影响跨会话保存，不阻断当前页面操作。
 */
function persistAnsweredQuestions(topicIds: Set<string>): void {
  const storage = getRoadmapStorage()
  if (storage === null) {
    return
  }
  try {
    storage.setItem(QUESTION_PROGRESS_KEY, JSON.stringify([...topicIds]))
  } catch {
    // localStorage 被禁用或配额耗尽时，仍保留组件内存中的最新状态。
  }
}

/**
 * 更新主线阅读或实验进度，并复用源码索引工作台的同一份本地存储。
 */
function handleTopicProgressChange(topicId: string, field: ProgressField, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  const current = topicProgress(topicId)
  progressByTopic.value = {
    ...progressByTopic.value,
    [topicId]: updateTopicProgress(topicId, {
      readMain: field === 'readMain' ? checked : current.readMain,
      ranLab: field === 'ranLab' ? checked : current.ranLab
    })
  }
}

/**
 * 更新过关问题状态，并使用新 Set 触发 Vue 响应式刷新。
 */
function handleQuestionProgressChange(topicId: string, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  const nextAnsweredTopicIds = new Set(answeredTopicIds.value)
  if (checked) {
    nextAnsweredTopicIds.add(topicId)
  } else {
    nextAnsweredTopicIds.delete(topicId)
  }
  answeredTopicIds.value = nextAnsweredTopicIds
  persistAnsweredQuestions(nextAnsweredTopicIds)
}

/**
 * 返回阶段的视觉状态，区分已完成、当前所在和尚未开始。
 */
function phaseState(phase: RoadmapPhase): 'complete' | 'current' | 'pending' {
  if (phaseCompletedCount(phase) === phase.stops.length) {
    return 'complete'
  }
  return currentPhase.value?.id === phase.id ? 'current' : 'pending'
}

/**
 * 返回专题的视觉状态，当前状态始终对应路线中的第一项未完成专题。
 */
function stopState(stop: RoadmapStop): 'complete' | 'current' | 'pending' {
  if (isStopComplete(stop)) {
    return 'complete'
  }
  return nextStop.value?.topicId === stop.topicId ? 'current' : 'pending'
}

/**
 * 客户端挂载后再读取本地进度，避免 VitePress 服务端构建访问 window。
 */
onMounted(() => {
  progressByTopic.value = loadLearningProgress()
  answeredTopicIds.value = loadAnsweredQuestions()
  focusedTopicId.value = nextStop.value?.topicId ?? ''
})
</script>

<template>
  <section class="learning-roadmap" aria-label="JDK 到 Spring 源码学习路线">
    <div class="roadmap-overview">
      <div class="roadmap-overview__progress">
        <span class="roadmap-eyebrow">全路线进度</span>
        <div class="roadmap-overview__number" aria-live="polite">
          <strong>{{ completedStopCount }}/{{ allStops.length }}</strong>
          <span>{{ overallCompletionPercent }}%</span>
        </div>
        <progress
          :value="completedStopCount"
          :max="allStops.length"
          :aria-label="`全路线进度：已完成 ${completedStopCount} 个，共 ${allStops.length} 个专题`"
        >
          {{ overallCompletionPercent }}%
        </progress>
      </div>

      <dl class="roadmap-overview__facts">
        <div>
          <dt>阶段</dt>
          <dd>{{ roadmapPhases.length }}</dd>
        </div>
        <div>
          <dt>专题</dt>
          <dd>{{ allStops.length }}</dd>
        </div>
        <div>
          <dt>建议投入</dt>
          <dd>{{ formatDuration(totalMinutes) }}</dd>
        </div>
        <div>
          <dt>当前阶段</dt>
          <dd>{{ currentPhase?.title ?? '全部完成' }}</dd>
        </div>
      </dl>
    </div>

    <div class="roadmap-toolbar">
      <div class="roadmap-view-switch" role="group" aria-label="路线显示范围">
        <button
          type="button"
          :class="{ 'is-active': viewMode === 'next' }"
          :aria-pressed="viewMode === 'next'"
          @click="viewMode = 'next'"
        >
          只看下一步
        </button>
        <button
          type="button"
          :class="{ 'is-active': viewMode === 'all' }"
          :aria-pressed="viewMode === 'all'"
          @click="viewMode = 'all'"
        >
          全部路线
        </button>
      </div>
      <a class="roadmap-index-link" :href="withBase('/source-explorer/')">打开源码索引</a>
    </div>

    <ol class="roadmap-logic" aria-label="路线排序逻辑">
      <li><strong>结构</strong><span>先看数据怎样存</span></li>
      <li><strong>协作</strong><span>再看线程怎样争用</span></li>
      <li><strong>执行</strong><span>接着看任务怎样完成</span></li>
      <li><strong>运行时</strong><span>理解框架依赖的能力</span></li>
      <li><strong>容器</strong><span>最后进入 Spring 调用链</span></li>
      <li><strong>导航</strong><span>下一站由专题索引自动生成</span></li>
    </ol>

    <nav v-if="viewMode === 'all'" class="roadmap-phase-nav" aria-label="学习阶段">
      <a
        v-for="(phase, phaseIndex) in roadmapPhases"
        :key="phase.id"
        :href="`#roadmap-${phase.id}`"
        :class="`is-${phaseState(phase)}`"
        :style="{ '--phase-accent': phase.accent }"
      >
        <span>阶段 {{ phaseIndex + 1 }}</span>
        <strong>{{ phase.title }}</strong>
        <small>{{ phaseCompletedCount(phase) }}/{{ phase.stops.length }} · {{ formatDuration(phaseMinutes(phase)) }}</small>
        <progress
          :value="phaseCompletedCount(phase)"
          :max="phase.stops.length"
          :aria-label="`${phase.title}阶段进度：已完成 ${phaseCompletedCount(phase)} 个，共 ${phase.stops.length} 个专题`"
        >
          {{ phaseCompletionPercent(phase) }}%
        </progress>
      </a>
    </nav>

    <div v-if="viewMode === 'next' && focusedStop === undefined" class="roadmap-finished" role="status">
      <span class="roadmap-finished__mark">{{ allStops.length }}/{{ allStops.length }}</span>
      <div>
        <h2>路线已完成</h2>
        <p>回到全部路线复盘过关问题，或进入源码索引按类名和方法继续深挖。</p>
      </div>
      <button type="button" @click="viewMode = 'all'">复盘全部路线</button>
    </div>

    <div v-else class="roadmap-phases">
      <section
        v-for="(phase, phaseIndex) in visiblePhases"
        :id="`roadmap-${phase.id}`"
        :key="phase.id"
        class="roadmap-phase"
        :style="{ '--phase-accent': phase.accent }"
      >
        <header class="roadmap-phase__header">
          <div class="roadmap-phase__identity">
            <span class="roadmap-eyebrow">阶段 {{ roadmapPhases.findIndex((item) => item.id === phase.id) + 1 }} · {{ phase.kicker }}</span>
            <h2>{{ phase.title }}</h2>
            <p>{{ phase.reason }}</p>
          </div>
          <dl class="roadmap-phase__summary">
            <div>
              <dt>阶段先修</dt>
              <dd>{{ phase.prerequisite }}</dd>
            </div>
            <div>
              <dt>阶段进度</dt>
              <dd>{{ phaseCompletedCount(roadmapPhases.find((item) => item.id === phase.id) ?? phase) }}/{{ roadmapPhases.find((item) => item.id === phase.id)?.stops.length ?? phase.stops.length }}</dd>
            </div>
            <div>
              <dt>预计投入</dt>
              <dd>{{ formatDuration(phaseMinutes(roadmapPhases.find((item) => item.id === phase.id) ?? phase)) }}</dd>
            </div>
          </dl>
        </header>

        <ol class="roadmap-stops" :start="phase.stops[0]?.order">
          <li v-for="stop in phase.stops" :key="stop.topicId" class="roadmap-stop-item">
            <article class="roadmap-stop" :class="`is-${stopState(stop)}`">
              <header class="roadmap-stop__header">
                <span class="roadmap-stop__order" aria-hidden="true">{{ stop.order }}</span>
                <div class="roadmap-stop__identity">
                  <span class="roadmap-eyebrow">第 {{ stop.order }} 站 · {{ formatDuration(stop.minutes) }}</span>
                  <h3>{{ stop.shortTitle }}</h3>
                  <code>{{ stop.topic.source.className }}</code>
                </div>
                <span class="roadmap-stop__state">
                  {{ stopState(stop) === 'complete' ? '已完成' : stopState(stop) === 'current' ? '当前' : '待学习' }}
                </span>
              </header>

              <div class="roadmap-stop__context">
                <p><strong>先修关系</strong>{{ stop.prerequisite }}</p>
                <p><strong>为什么现在学</strong>{{ stop.whyNow }}</p>
                <p v-if="stop.nextTopic" class="roadmap-stop__next">
                  <strong>下一站</strong>
                  <a :href="withBase(topicHomeUrl(stop.nextTopic))">{{ stop.nextTopic.title }}</a>
                  <span>；{{ stop.nextReason }}</span>
                </p>
                <p v-else class="roadmap-stop__next roadmap-stop__next--terminal">
                  <strong>路线终点</strong>
                  <span>这是当前推荐路线的最后一个专题，完成后可回到源码索引自由深挖。</span>
                </p>
              </div>

              <details class="roadmap-stop__details" :open="viewMode === 'next'">
                <summary>阅读、实验与过关标准</summary>
                <div class="roadmap-stop__detail-grid">
                  <section>
                    <h4>阅读目标</h4>
                    <p>{{ stop.readingGoal }}</p>
                    <div class="roadmap-stop__links">
                      <a :href="withBase(topicHomeUrl(stop.topic))">专题主线</a>
                    </div>
                  </section>

                  <section>
                    <h4>源码入口</h4>
                    <code>{{ stop.entry.method }}</code>
                    <p>{{ stop.entry.purpose }}</p>
                    <div class="roadmap-stop__links">
                      <a :href="withBase(stop.entry.document)">入口讲解</a>
                      <a :href="stop.sourceUrl" target="_blank" rel="noreferrer">固定版本源码</a>
                    </div>
                  </section>

                  <section>
                    <h4>运行实验</h4>
                    <code>{{ stop.breakpoint.method }}</code>
                    <p>{{ stop.breakpoint.scenario }}</p>
                    <div class="roadmap-stop__variables">
                      <code v-for="variable in stop.breakpoint.variables" :key="variable">{{ variable }}</code>
                    </div>
                    <div class="roadmap-stop__links">
                      <a :href="withBase(topicLabUrl(stop.topic))">实验手册</a>
                    </div>
                  </section>

                  <section>
                    <h4>过关问题</h4>
                    <p>{{ stop.passQuestion }}</p>
                    <p class="roadmap-stop__completion"><strong>完成条件</strong>{{ stop.completion }}</p>
                  </section>
                </div>
              </details>

              <fieldset class="roadmap-stop__progress">
                <legend>本站进度 · {{ stopProgressCount(stop) }}/3</legend>
                <label>
                  <input
                    type="checkbox"
                    :checked="topicProgress(stop.topicId).readMain"
                    @change="handleTopicProgressChange(stop.topicId, 'readMain', $event)"
                  />
                  主线已读
                </label>
                <label>
                  <input
                    type="checkbox"
                    :checked="topicProgress(stop.topicId).ranLab"
                    @change="handleTopicProgressChange(stop.topicId, 'ranLab', $event)"
                  />
                  实验已运行
                </label>
                <label>
                  <input
                    type="checkbox"
                    :checked="answeredQuestion(stop.topicId)"
                    @change="handleQuestionProgressChange(stop.topicId, $event)"
                  />
                  能回答过关问题
                </label>
              </fieldset>

              <div v-if="viewMode === 'next' && isStopComplete(stop)" class="roadmap-stop__advance" role="status">
                <span>{{ nextStop === undefined ? `${allStops.length} 站已经全部完成` : `本站完成，下一站是 ${nextStop.shortTitle}` }}</span>
                <button type="button" @click="advanceToNextStop">
                  {{ nextStop === undefined ? '查看完成总结' : '进入下一站' }}
                </button>
              </div>
            </article>
          </li>
        </ol>
      </section>
    </div>
  </section>
</template>

<style scoped>
.learning-roadmap {
  --roadmap-line: color-mix(in srgb, var(--vp-c-divider) 88%, transparent);
  container-name: learning-roadmap;
  container-type: inline-size;
  margin: 24px 0 56px;
  color: var(--vp-c-text-1);
  letter-spacing: 0;
}

.roadmap-overview {
  display: grid;
  grid-template-columns: minmax(220px, 0.75fr) minmax(0, 1.25fr);
  border-block: 1px solid var(--roadmap-line);
}

.roadmap-overview__progress {
  display: grid;
  align-content: center;
  gap: 8px;
  min-width: 0;
  padding: 18px 20px 18px 0;
  border-right: 1px solid var(--roadmap-line);
}

.roadmap-overview__number {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 14px;
}

.roadmap-overview__number strong {
  font-family: var(--vp-font-family-mono);
  font-size: 1.65rem;
  line-height: 1;
}

.roadmap-overview__number span {
  color: var(--vp-c-brand-1);
  font-size: 0.86rem;
  font-weight: 800;
}

.roadmap-overview progress,
.roadmap-phase-nav progress {
  width: 100%;
  height: 7px;
  border: 0;
  border-radius: 3px;
  accent-color: var(--vp-c-brand-1);
}

.roadmap-overview__facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
}

.roadmap-overview__facts div {
  min-width: 0;
  padding: 17px 14px;
  border-right: 1px solid var(--roadmap-line);
}

.roadmap-overview__facts div:last-child {
  border-right: 0;
}

.roadmap-overview__facts dt,
.roadmap-phase__summary dt {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  font-weight: 800;
}

.roadmap-overview__facts dd,
.roadmap-phase__summary dd {
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-1);
  font-size: 0.83rem;
  font-weight: 700;
  line-height: 1.45;
}

.roadmap-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 0;
}

.roadmap-view-switch {
  display: inline-grid;
  grid-template-columns: repeat(2, minmax(112px, 1fr));
  padding: 3px;
  border: 1px solid var(--roadmap-line);
  border-radius: 5px;
  background: var(--vp-c-bg-soft);
}

.roadmap-view-switch button,
.roadmap-finished button {
  min-height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: var(--vp-c-text-2);
  cursor: pointer;
  font: inherit;
  font-size: 0.8rem;
  font-weight: 800;
  letter-spacing: 0;
}

.roadmap-view-switch button.is-active {
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  box-shadow: 0 1px 4px rgba(23, 32, 42, 0.12);
}

.roadmap-index-link,
.roadmap-stop__links a {
  color: var(--vp-c-brand-1);
  font-size: 0.8rem;
  font-weight: 800;
  text-decoration: none;
}

.roadmap-index-link:hover,
.roadmap-stop__links a:hover {
  color: var(--vp-c-brand-2);
}

.roadmap-logic {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  margin: 0 0 24px;
  padding: 0;
  border-block: 1px solid var(--roadmap-line);
  list-style: none;
}

.roadmap-logic li {
  position: relative;
  min-width: 0;
  padding: 12px 24px 12px 12px;
  border-right: 1px solid var(--roadmap-line);
}

.roadmap-logic li:last-child {
  border-right: 0;
}

.roadmap-logic li:not(:last-child)::after {
  position: absolute;
  top: 50%;
  right: 8px;
  color: var(--vp-c-text-3);
  content: '›';
  transform: translateY(-50%);
}

.roadmap-logic strong,
.roadmap-logic span {
  display: block;
}

.roadmap-logic strong {
  font-size: 0.78rem;
}

.roadmap-logic span {
  margin-top: 3px;
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
  line-height: 1.4;
}

.roadmap-phase-nav {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-bottom: 32px;
  border-top: 1px solid var(--roadmap-line);
  border-left: 1px solid var(--roadmap-line);
}

.roadmap-phase-nav a {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 13px 14px;
  border-right: 1px solid var(--roadmap-line);
  border-bottom: 1px solid var(--roadmap-line);
  color: var(--vp-c-text-1);
  text-decoration: none;
}

.roadmap-phase-nav a:hover,
.roadmap-phase-nav a.is-current {
  background: color-mix(in srgb, var(--phase-accent) 8%, var(--vp-c-bg));
}

.roadmap-phase-nav a.is-complete strong,
.roadmap-phase-nav a.is-current strong {
  color: var(--phase-accent);
}

.roadmap-phase-nav span,
.roadmap-phase-nav small {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
  font-weight: 700;
}

.roadmap-phase-nav strong {
  overflow-wrap: anywhere;
  font-size: 0.88rem;
}

.roadmap-phase-nav progress {
  margin-top: 5px;
  accent-color: var(--phase-accent);
}

.roadmap-phases {
  display: grid;
  gap: 42px;
}

.roadmap-phase {
  min-width: 0;
  scroll-margin-top: 88px;
}

.roadmap-phase__header {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(220px, 0.75fr);
  gap: 28px;
  padding: 0 0 18px;
  border-bottom: 2px solid var(--phase-accent);
}

.roadmap-phase__identity {
  min-width: 0;
}

.roadmap-eyebrow {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  font-weight: 800;
}

.roadmap-phase__identity h2,
.roadmap-finished h2 {
  margin: 5px 0 0;
  border: 0;
  font-size: 1.2rem;
  line-height: 1.35;
}

.roadmap-phase__identity p,
.roadmap-finished p {
  margin: 8px 0 0;
  color: var(--vp-c-text-2);
  font-size: 0.83rem;
  line-height: 1.7;
}

.roadmap-phase__summary {
  display: grid;
  gap: 9px;
  margin: 0;
}

.roadmap-phase__summary div {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  align-items: baseline;
}

.roadmap-phase__summary dd {
  margin: 0;
  font-size: 0.76rem;
}

.roadmap-stops {
  position: relative;
  margin: 0;
  padding: 22px 0 0 34px;
  list-style: none;
}

.roadmap-stops::before {
  position: absolute;
  top: 22px;
  bottom: 18px;
  left: 14px;
  width: 2px;
  background: var(--roadmap-line);
  content: '';
}

.roadmap-stop-item {
  position: relative;
  margin: 0 0 18px;
}

.roadmap-stop-item:last-child {
  margin-bottom: 0;
}

.roadmap-stop {
  min-width: 0;
  padding: 16px 18px;
  border: 1px solid var(--roadmap-line);
  border-left: 3px solid var(--vp-c-divider);
  border-radius: 6px;
  background: var(--vp-c-bg);
}

.roadmap-stop.is-current {
  border-left-color: var(--phase-accent);
  background: color-mix(in srgb, var(--phase-accent) 4%, var(--vp-c-bg));
}

.roadmap-stop.is-complete {
  border-left-color: #15803d;
}

.roadmap-stop__header {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
}

.roadmap-stop__order {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--roadmap-line);
  border-radius: 50%;
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 0.8rem;
  font-weight: 800;
}

.roadmap-stop.is-current .roadmap-stop__order {
  border-color: var(--phase-accent);
  background: var(--phase-accent);
  color: #fff;
}

.roadmap-stop.is-complete .roadmap-stop__order {
  border-color: #15803d;
  color: #15803d;
}

.roadmap-stop__identity {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.roadmap-stop__identity h3 {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 1rem;
  line-height: 1.35;
}

.roadmap-stop__identity code,
.roadmap-stop__details code {
  overflow-wrap: anywhere;
  white-space: normal;
}

.roadmap-stop__identity code {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.roadmap-stop__state {
  min-width: 54px;
  padding: 4px 7px;
  border: 1px solid var(--roadmap-line);
  border-radius: 4px;
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
  font-weight: 800;
  text-align: center;
}

.roadmap-stop.is-current .roadmap-stop__state {
  border-color: var(--phase-accent);
  color: var(--phase-accent);
}

.roadmap-stop.is-complete .roadmap-stop__state {
  border-color: #15803d;
  color: #15803d;
}

.roadmap-stop__context {
  display: grid;
  gap: 8px;
  margin: 15px 0 0 54px;
}

.roadmap-stop__context p,
.roadmap-stop__details p {
  margin: 0;
  color: var(--vp-c-text-2);
  font-size: 0.8rem;
  line-height: 1.65;
}

.roadmap-stop__next {
  padding: 9px 11px;
  border-left: 3px solid var(--phase-accent);
  background: color-mix(in srgb, var(--phase-accent) 7%, transparent);
}

.roadmap-stop__next a {
  color: var(--phase-accent);
  font-weight: 800;
  text-decoration: none;
}

.roadmap-stop__next a:hover {
  text-decoration: underline;
}

.roadmap-stop__next--terminal {
  border-left-color: #15803d;
  background: color-mix(in srgb, #15803d 7%, transparent);
}

.roadmap-stop__context strong,
.roadmap-stop__completion strong {
  display: inline-block;
  min-width: 74px;
  margin-right: 8px;
  color: var(--vp-c-text-1);
  font-size: 0.72rem;
}

.roadmap-stop__details {
  margin: 14px 0 0 54px;
  border-block: 1px solid var(--roadmap-line);
}

.roadmap-stop__details > summary {
  padding: 11px 0;
  color: var(--vp-c-text-2);
  cursor: pointer;
  font-size: 0.78rem;
  font-weight: 800;
}

.roadmap-stop__detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-top: 1px solid var(--roadmap-line);
}

.roadmap-stop__detail-grid section {
  min-width: 0;
  padding: 14px 14px 14px 0;
  border-right: 1px solid var(--roadmap-line);
  border-bottom: 1px solid var(--roadmap-line);
}

.roadmap-stop__detail-grid section:nth-child(even) {
  padding-right: 0;
  padding-left: 14px;
  border-right: 0;
}

.roadmap-stop__detail-grid section:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.roadmap-stop__detail-grid h4 {
  margin: 0 0 8px;
  font-size: 0.78rem;
}

.roadmap-stop__detail-grid > section > code {
  display: block;
  margin-bottom: 7px;
  color: var(--phase-accent);
  font-size: 0.72rem;
}

.roadmap-stop__links,
.roadmap-stop__variables {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 10px;
}

.roadmap-stop__variables code {
  padding: 2px 5px;
  border: 1px solid var(--roadmap-line);
  border-radius: 3px;
  background: var(--vp-c-bg-soft);
  font-size: 0.68rem;
}

.roadmap-stop__completion {
  margin-top: 10px !important;
  padding-top: 9px;
  border-top: 1px dashed var(--roadmap-line);
}

.roadmap-stop__progress {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 14px 0 0 54px;
  padding: 0;
  border: 0;
}

.roadmap-stop__progress legend {
  width: 100%;
  margin-bottom: 7px;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  font-weight: 800;
}

.roadmap-stop__progress label {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 7px;
  min-height: 38px;
  padding: 7px 9px;
  border: 1px solid var(--roadmap-line);
  border-radius: 4px;
  color: var(--vp-c-text-2);
  cursor: pointer;
  font-size: 0.75rem;
  line-height: 1.35;
}

.roadmap-stop__progress label:has(input:checked) {
  border-color: color-mix(in srgb, var(--phase-accent) 55%, var(--roadmap-line));
  background: color-mix(in srgb, var(--phase-accent) 7%, var(--vp-c-bg));
  color: var(--vp-c-text-1);
}

.roadmap-stop__progress input {
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  accent-color: var(--phase-accent);
}

.roadmap-stop__advance {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin: 14px 0 0 54px;
  padding-top: 12px;
  border-top: 1px solid color-mix(in srgb, var(--phase-accent) 50%, var(--roadmap-line));
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
  font-weight: 700;
}

.roadmap-stop__advance button {
  flex: 0 0 auto;
  min-height: 34px;
  padding: 0 13px;
  border: 1px solid var(--phase-accent);
  border-radius: 4px;
  background: var(--phase-accent);
  color: #fff;
  cursor: pointer;
  font: inherit;
  font-size: 0.76rem;
  font-weight: 800;
  letter-spacing: 0;
}

.roadmap-finished {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 18px;
  align-items: center;
  padding: 22px 0;
  border-block: 1px solid #15803d;
}

.roadmap-finished__mark {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #15803d;
  color: #fff;
  font-family: var(--vp-font-family-mono);
  font-size: 0.78rem;
  font-weight: 800;
}

.roadmap-finished button {
  border: 1px solid #15803d;
  color: #15803d;
}

@container learning-roadmap (max-width: 760px) {
  .roadmap-overview {
    grid-template-columns: 1fr;
  }

  .roadmap-overview__progress {
    padding-right: 0;
    border-right: 0;
    border-bottom: 1px solid var(--roadmap-line);
  }

  .roadmap-overview__facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .roadmap-overview__facts div:nth-child(2) {
    border-right: 0;
  }

  .roadmap-overview__facts div:nth-child(-n + 2) {
    border-bottom: 1px solid var(--roadmap-line);
  }

  .roadmap-phase-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .roadmap-phase__header {
    grid-template-columns: 1fr;
    gap: 16px;
  }

}

@container learning-roadmap (max-width: 580px) {
  .roadmap-logic {
    grid-template-columns: 1fr;
  }

  .roadmap-logic li {
    padding-right: 36px;
    border-right: 0;
    border-bottom: 1px solid var(--roadmap-line);
  }

  .roadmap-logic li:last-child {
    border-bottom: 0;
  }

  .roadmap-logic li:not(:last-child)::after {
    right: 16px;
    transform: translateY(-50%) rotate(90deg);
  }

  .roadmap-stop__detail-grid {
    grid-template-columns: 1fr;
  }

  .roadmap-stop__detail-grid section,
  .roadmap-stop__detail-grid section:nth-child(even) {
    padding: 13px 0;
    border-right: 0;
    border-bottom: 1px solid var(--roadmap-line);
  }

  .roadmap-stop__detail-grid section:nth-last-child(2) {
    border-bottom: 1px solid var(--roadmap-line);
  }

  .roadmap-stop__detail-grid section:last-child {
    border-bottom: 0;
  }

  .roadmap-stop__progress {
    grid-template-columns: 1fr;
  }
}

@container learning-roadmap (max-width: 480px) {
  .roadmap-toolbar {
    display: grid;
  }

  .roadmap-view-switch {
    width: 100%;
  }

  .roadmap-index-link {
    justify-self: start;
  }

  .roadmap-phase-nav {
    grid-template-columns: 1fr;
  }

  .roadmap-stops {
    padding-left: 22px;
  }

  .roadmap-stops::before {
    left: 7px;
  }

  .roadmap-stop {
    padding: 14px 12px;
  }

  .roadmap-stop__header {
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 9px;
  }

  .roadmap-stop__order {
    width: 30px;
    height: 30px;
  }

  .roadmap-stop__state {
    grid-column: 2;
    justify-self: start;
  }

  .roadmap-stop__context,
  .roadmap-stop__details,
  .roadmap-stop__progress,
  .roadmap-stop__advance {
    margin-left: 0;
  }

  .roadmap-stop__advance {
    align-items: stretch;
    flex-direction: column;
  }

  .roadmap-stop__advance button {
    width: 100%;
  }

  .roadmap-stop__context strong {
    display: block;
    margin-bottom: 3px;
  }

  .roadmap-finished {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .roadmap-finished button {
    grid-column: 1 / -1;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .roadmap-stop,
  .roadmap-view-switch button,
  .roadmap-phase-nav a {
    transition: border-color 160ms ease, background-color 160ms ease, color 160ms ease;
  }
}
</style>
