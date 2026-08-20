import { defineConfig } from 'vitepress'

const configuredBase = process.env.DOCS_BASE || '/atlas/'
const siteBase = configuredBase.endsWith('/') ? configuredBase : `${configuredBase}/`

export default defineConfig({
  lang: 'zh-CN',
  title: 'Java Source Atlas',
  description: '用调用链、断点和可运行案例学习 Java 源码',
  // 2026-08-20：保留原先只为 VitePress 设置 base 的写法，站点资源路径现在复用同一规范值。
  // base: process.env.DOCS_BASE || '/atlas/',
  base: siteBase,
  cleanUrls: true,
  lastUpdated: true,
  head: [
    // 2026-08-20：原逻辑固定为 /atlas/favicon.svg，DOCS_BASE 改变时会指向错误部署目录。
    // ['link', { rel: 'icon', href: '/atlas/favicon.svg', type: 'image/svg+xml' }],
    ['link', { rel: 'icon', href: `${siteBase}favicon.svg`, type: 'image/svg+xml' }],
    ['meta', { name: 'theme-color', content: '#0f766e' }],
    ['meta', { name: 'viewport', content: 'width=device-width, initial-scale=1.0' }]
  ],
  themeConfig: {
    logo: {
      light: '/logo-light.svg',
      dark: '/logo-dark.svg'
    },
    siteTitle: 'Java Source Atlas',
    search: {
      provider: 'local'
    },
    nav: [
      { text: '学习路线', link: '/learning-path/' },
      { text: '源码索引', link: '/source-explorer/' },
      {
        text: 'JDK 专题',
        items: [
          { text: 'JDK 8 / 17 / 21 版本对比', link: '/jdk/version-comparison/' },
          { text: 'HashMap', link: '/jdk/collections/hashmap/' },
          { text: 'LinkedHashMap', link: '/jdk/collections/linkedhashmap/' },
          { text: 'TreeMap', link: '/jdk/collections/treemap/' },
          { text: 'ArrayList', link: '/jdk/collections/arraylist/' },
          { text: 'JMM / volatile / final / VarHandle', link: '/jdk/concurrent/jmm/' },
          { text: 'synchronized / ObjectMonitor', link: '/jdk/concurrent/synchronized-monitor/' },
          { text: 'Atomic 与 Striped64', link: '/jdk/concurrent/atomic/' },
          { text: 'Thread / LockSupport', link: '/jdk/concurrent/thread-locksupport/' },
          { text: 'AQS 与 ReentrantLock', link: '/jdk/concurrent/locks/' },
          { text: 'ConcurrentHashMap', link: '/jdk/concurrent/concurrenthashmap/' },
          { text: 'ConcurrentLinkedQueue', link: '/jdk/concurrent/concurrentlinkedqueue/' },
          { text: 'CopyOnWriteArrayList', link: '/jdk/concurrent/copyonwritearraylist/' },
          { text: 'BlockingQueue', link: '/jdk/concurrent/blockingqueue/' },
          { text: 'Reference / WeakHashMap', link: '/jdk/runtime/reference-weakhashmap/' },
          { text: 'ThreadLocal', link: '/jdk/concurrent/threadlocal/' },
          { text: 'CompletableFuture', link: '/jdk/concurrent/completablefuture/' },
          { text: 'FutureTask', link: '/jdk/concurrent/futuretask/' },
          { text: 'ThreadPoolExecutor', link: '/jdk/concurrent/threadpoolexecutor/' },
          { text: 'ScheduledThreadPoolExecutor', link: '/jdk/concurrent/scheduledthreadpoolexecutor/' },
          { text: 'ForkJoinPool', link: '/jdk/concurrent/forkjoinpool/' },
          { text: 'Stream 与 Spliterator', link: '/jdk/functional/stream/' },
          { text: 'ByteBuffer / Selector', link: '/jdk/io/nio/' },
          { text: 'ClassLoader / ServiceLoader', link: '/jdk/runtime/classloader/' },
          { text: 'Reflection / JDK Dynamic Proxy', link: '/jdk/runtime/reflection-proxy/' }
        ]
      },
      {
        text: 'Spring Framework',
        items: [
          { text: 'Spring 学习路线', link: '/spring/' },
          { text: 'Spring 核心链路深挖', link: '/spring/deep-dive/' },
          { text: 'Spring IOC', link: '/spring/ioc/' },
          { text: 'Spring AOP', link: '/spring/aop/' },
          { text: 'Spring Transaction', link: '/spring/transaction/' },
          { text: 'Spring Boot 自动装配', link: '/spring/boot-autoconfigure/' },
          { text: 'Spring MVC', link: '/spring/mvc/' }
        ]
      },
      {
        text: '调试实验',
        items: [
          { text: 'HashMap 实验', link: '/jdk/collections/hashmap/debug-lab' },
          { text: 'LinkedHashMap 实验', link: '/jdk/collections/linkedhashmap/debug-lab' },
          { text: 'TreeMap 实验', link: '/jdk/collections/treemap/debug-lab' },
          { text: 'ArrayList 实验', link: '/jdk/collections/arraylist/debug-lab' },
          { text: 'JMM 与 volatile 实验', link: '/jdk/concurrent/jmm/debug-lab' },
          { text: 'synchronized 与 monitor 实验', link: '/jdk/concurrent/synchronized-monitor/debug-lab' },
          { text: 'Atomic 与 Striped64 实验', link: '/jdk/concurrent/atomic/debug-lab' },
          { text: 'Thread / LockSupport 实验', link: '/jdk/concurrent/thread-locksupport/debug-lab' },
          { text: '锁与 AQS 实验', link: '/jdk/concurrent/locks/debug-lab' },
          { text: 'ConcurrentHashMap 实验', link: '/jdk/concurrent/concurrenthashmap/debug-lab' },
          { text: 'ConcurrentLinkedQueue 实验', link: '/jdk/concurrent/concurrentlinkedqueue/debug-lab' },
          { text: 'CopyOnWriteArrayList 实验', link: '/jdk/concurrent/copyonwritearraylist/debug-lab' },
          { text: 'BlockingQueue 实验', link: '/jdk/concurrent/blockingqueue/debug-lab' },
          { text: 'Reference / WeakHashMap 实验', link: '/jdk/runtime/reference-weakhashmap/debug-lab' },
          { text: 'ThreadLocal 实验', link: '/jdk/concurrent/threadlocal/debug-lab' },
          { text: 'CompletableFuture 实验', link: '/jdk/concurrent/completablefuture/debug-lab' },
          { text: 'FutureTask 实验', link: '/jdk/concurrent/futuretask/debug-lab' },
          { text: '线程池实验', link: '/jdk/concurrent/threadpoolexecutor/debug-lab' },
          { text: '定时线程池实验', link: '/jdk/concurrent/scheduledthreadpoolexecutor/debug-lab' },
          { text: 'ForkJoinPool 实验', link: '/jdk/concurrent/forkjoinpool/debug-lab' },
          { text: 'Stream 与 Spliterator 实验', link: '/jdk/functional/stream/debug-lab' },
          { text: 'ByteBuffer / Selector 实验', link: '/jdk/io/nio/debug-lab' },
          { text: 'ClassLoader / ServiceLoader 实验', link: '/jdk/runtime/classloader/debug-lab' },
          { text: 'Reflection / Dynamic Proxy 实验', link: '/jdk/runtime/reflection-proxy/debug-lab' },
          { text: 'Spring IOC 实验', link: '/spring/ioc/debug-lab' },
          { text: 'Spring AOP 实验', link: '/spring/aop/debug-lab' },
          { text: 'Spring Transaction 实验', link: '/spring/transaction/debug-lab' },
          { text: 'Spring Boot 自动装配实验', link: '/spring/boot-autoconfigure/debug-lab' },
          { text: 'Spring MVC 实验', link: '/spring/mvc/debug-lab' }
        ]
      },
      { text: '贡献指南', link: '/guide/contributing' }
    ],
    sidebar: {
      '/learning-path/': [
        {
          text: '完整学习路线',
          items: [
            { text: '路线工作台', link: '/learning-path/' },
            { text: '源码索引', link: '/source-explorer/' },
            { text: 'JDK 版本对比', link: '/jdk/version-comparison/' },
            { text: 'Spring 深挖', link: '/spring/deep-dive/' }
          ]
        }
      ],
      '/jdk/version-comparison/': [
        {
          text: 'JDK 版本对比',
          items: [
            { text: '对比工作台', link: '/jdk/version-comparison/' },
            { text: '如何阅读一个专题', link: '/guide/reading' },
            { text: '源码与许可证', link: '/reference/source-license' }
          ]
        }
      ],
      '/jdk/collections/hashmap/': [
        {
          text: 'HashMap 源码解析',
          items: [
            { text: '学习路径', link: '/jdk/collections/hashmap/' },
            { text: '数据结构与关键字段', link: '/jdk/collections/hashmap/data-structure' },
            { text: 'put 写入流程', link: '/jdk/collections/hashmap/put' },
            { text: 'resize 扩容机制', link: '/jdk/collections/hashmap/resize' },
            { text: '链表树化', link: '/jdk/collections/hashmap/treeify' },
            { text: '查询、删除与遍历', link: '/jdk/collections/hashmap/read-remove' },
            { text: '断点实验手册', link: '/jdk/collections/hashmap/debug-lab' },
            { text: '版本差异与边界', link: '/jdk/collections/hashmap/version-diff' }
          ]
        }
      ],
      '/jdk/collections/arraylist/': [
        {
          text: 'ArrayList 源码解析',
          items: [
            { text: '结构与 add 主流程', link: '/jdk/collections/arraylist/' },
            { text: '扩容、删除与视图', link: '/jdk/collections/arraylist/mutation' },
            { text: '迭代器与版本差异', link: '/jdk/collections/arraylist/iterator-version' },
            { text: '断点实验手册', link: '/jdk/collections/arraylist/debug-lab' }
          ]
        }
      ],
      '/jdk/collections/linkedhashmap/': [
        {
          text: 'LinkedHashMap 源码解析',
          items: [
            { text: '结构、顺序与扩展钩子', link: '/jdk/collections/linkedhashmap/' },
            { text: '访问顺序与链表调整', link: '/jdk/collections/linkedhashmap/access-order' },
            { text: 'LRU 淘汰与实现边界', link: '/jdk/collections/linkedhashmap/lru' },
            { text: '断点实验手册', link: '/jdk/collections/linkedhashmap/debug-lab' }
          ]
        }
      ],
      '/jdk/collections/treemap/': [
        {
          text: 'TreeMap 源码解析',
          items: [
            { text: '有序映射与红黑树', link: '/jdk/collections/treemap/' },
            { text: 'put、重着色与旋转', link: '/jdk/collections/treemap/put-balance' },
            { text: '导航方法与范围视图', link: '/jdk/collections/treemap/navigation-view' },
            { text: '断点实验手册', link: '/jdk/collections/treemap/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/atomic/': [
        {
          text: 'Atomic 与 Striped64',
          items: [
            { text: '原子更新与分段计数', link: '/jdk/concurrent/atomic/' },
            { text: 'AtomicInteger 与 CAS', link: '/jdk/concurrent/atomic/atomic-integer' },
            { text: 'LongAdder 与求和边界', link: '/jdk/concurrent/atomic/sum-version' },
            { text: 'Striped64 状态机', link: '/jdk/concurrent/atomic/striped64' },
            { text: '断点实验手册', link: '/jdk/concurrent/atomic/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/jmm/': [
        {
          text: 'JMM 与内存语义',
          items: [
            { text: '内存模型阅读主线', link: '/jdk/concurrent/jmm/' },
            { text: 'happens-before 与安全发布', link: '/jdk/concurrent/jmm/happens-before' },
            { text: 'volatile、final 与 DCL', link: '/jdk/concurrent/jmm/volatile-final' },
            { text: 'Unsafe 到 VarHandle', link: '/jdk/concurrent/jmm/varhandle-version' },
            { text: '断点与并发实验', link: '/jdk/concurrent/jmm/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/synchronized-monitor/': [
        {
          text: 'synchronized / ObjectMonitor',
          items: [
            { text: 'monitor 阅读主线', link: '/jdk/concurrent/synchronized-monitor/' },
            { text: '字节码、重入与释放', link: '/jdk/concurrent/synchronized-monitor/bytecode-reentrancy' },
            { text: '入口集合与 WaitSet', link: '/jdk/concurrent/synchronized-monitor/objectmonitor-queues' },
            { text: 'wait、notify、超时与中断', link: '/jdk/concurrent/synchronized-monitor/wait-notify' },
            { text: '断点与线程状态实验', link: '/jdk/concurrent/synchronized-monitor/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/concurrenthashmap/': [
        {
          text: 'ConcurrentHashMap 源码解析',
          items: [
            { text: '结构与并发读写', link: '/jdk/concurrent/concurrenthashmap/' },
            { text: 'put 与协作扩容', link: '/jdk/concurrent/concurrenthashmap/put-resize' },
            { text: '计数与原子复合操作', link: '/jdk/concurrent/concurrenthashmap/count-compute' },
            { text: '断点实验手册', link: '/jdk/concurrent/concurrenthashmap/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/concurrentlinkedqueue/': [
        {
          text: 'ConcurrentLinkedQueue',
          items: [
            { text: '无锁队列与阅读主线', link: '/jdk/concurrent/concurrentlinkedqueue/' },
            { text: 'offer、CAS 与滞后 tail', link: '/jdk/concurrent/concurrentlinkedqueue/offer-tail' },
            { text: 'poll、逻辑删除与弱一致遍历', link: '/jdk/concurrent/concurrentlinkedqueue/poll-iteration' },
            { text: '断点实验手册', link: '/jdk/concurrent/concurrentlinkedqueue/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/copyonwritearraylist/': [
        {
          text: 'CopyOnWriteArrayList',
          items: [
            { text: '读快照与写时复制', link: '/jdk/concurrent/copyonwritearraylist/' },
            { text: '复制与快照发布', link: '/jdk/concurrent/copyonwritearraylist/write-snapshot' },
            { text: '迭代器、视图与复合操作', link: '/jdk/concurrent/copyonwritearraylist/iterator-view' },
            { text: '断点实验手册', link: '/jdk/concurrent/copyonwritearraylist/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/blockingqueue/': [
        {
          text: 'BlockingQueue 源码解析',
          items: [
            { text: '阻塞队列与阅读主线', link: '/jdk/concurrent/blockingqueue/' },
            { text: 'ArrayBlockingQueue 与 LinkedBlockingQueue', link: '/jdk/concurrent/blockingqueue/array-linked' },
            { text: 'SynchronousQueue 零容量移交', link: '/jdk/concurrent/blockingqueue/synchronousqueue' },
            { text: '断点实验手册', link: '/jdk/concurrent/blockingqueue/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/completablefuture/': [
        {
          text: 'CompletableFuture 源码解析',
          items: [
            { text: '结果容器与依赖图', link: '/jdk/concurrent/completablefuture/' },
            { text: 'Completion 栈与完成传播', link: '/jdk/concurrent/completablefuture/completion-stack' },
            { text: '串行、组合与聚合', link: '/jdk/concurrent/completablefuture/composition' },
            { text: '异常、取消与阻塞等待', link: '/jdk/concurrent/completablefuture/exception-cancel' },
            { text: '断点实验手册', link: '/jdk/concurrent/completablefuture/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/futuretask/': [
        {
          text: 'FutureTask 源码解析',
          items: [
            { text: '异步结果与阅读主线', link: '/jdk/concurrent/futuretask/' },
            { text: 'run 与结果状态机', link: '/jdk/concurrent/futuretask/state-machine' },
            { text: '等待栈、取消与中断', link: '/jdk/concurrent/futuretask/waiters-cancel' },
            { text: '断点实验手册', link: '/jdk/concurrent/futuretask/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/threadlocal/': [
        {
          text: 'ThreadLocal 源码解析',
          items: [
            { text: '线程隔离与阅读主线', link: '/jdk/concurrent/threadlocal/' },
            { text: 'ThreadLocalMap 与开放寻址', link: '/jdk/concurrent/threadlocal/threadlocalmap' },
            { text: '弱引用 key 与过期清理', link: '/jdk/concurrent/threadlocal/stale-cleanup' },
            { text: '线程池污染与安全边界', link: '/jdk/concurrent/threadlocal/threadpool-leak' },
            { text: '断点实验手册', link: '/jdk/concurrent/threadlocal/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/threadpoolexecutor/': [
        {
          text: 'ThreadPoolExecutor 源码解析',
          items: [
            { text: 'ctl 与 execute 决策', link: '/jdk/concurrent/threadpoolexecutor/' },
            { text: 'Worker 与任务循环', link: '/jdk/concurrent/threadpoolexecutor/worker' },
            { text: '关闭与拒绝策略', link: '/jdk/concurrent/threadpoolexecutor/shutdown-reject' },
            { text: '断点实验手册', link: '/jdk/concurrent/threadpoolexecutor/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/scheduledthreadpoolexecutor/': [
        {
          text: 'ScheduledThreadPoolExecutor',
          items: [
            { text: '调度状态机与阅读主线', link: '/jdk/concurrent/scheduledthreadpoolexecutor/' },
            { text: 'DelayedWorkQueue 与 leader 等待', link: '/jdk/concurrent/scheduledthreadpoolexecutor/delayed-work-queue' },
            { text: '周期重排、取消与关闭', link: '/jdk/concurrent/scheduledthreadpoolexecutor/periodic-cancel' },
            { text: '断点实验手册', link: '/jdk/concurrent/scheduledthreadpoolexecutor/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/forkjoinpool/': [
        {
          text: 'ForkJoinPool 源码解析',
          items: [
            { text: '分治执行与阅读主线', link: '/jdk/concurrent/forkjoinpool/' },
            { text: 'WorkQueue 与工作窃取', link: '/jdk/concurrent/forkjoinpool/workqueue-steal' },
            { text: 'fork、join 与帮助执行', link: '/jdk/concurrent/forkjoinpool/fork-join' },
            { text: '阻塞补偿与使用边界', link: '/jdk/concurrent/forkjoinpool/managed-blocking' },
            { text: '断点实验手册', link: '/jdk/concurrent/forkjoinpool/debug-lab' }
          ]
        }
      ],
      '/jdk/functional/stream/': [
        {
          text: 'Stream 与 Spliterator',
          items: [
            { text: '流水线与阅读主线', link: '/jdk/functional/stream/' },
            { text: 'AbstractPipeline 与 Sink 链', link: '/jdk/functional/stream/pipeline-sink' },
            { text: '短路终止与取消遍历', link: '/jdk/functional/stream/short-circuit' },
            { text: 'Spliterator 与并行拆分', link: '/jdk/functional/stream/parallel-spliterator' },
            { text: '断点实验手册', link: '/jdk/functional/stream/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/locks/': [
        {
          text: 'AQS 与 ReentrantLock',
          items: [
            { text: '锁入口与同步状态', link: '/jdk/concurrent/locks/' },
            { text: 'AQS 获取与释放', link: '/jdk/concurrent/locks/aqs' },
            { text: 'Condition 条件队列', link: '/jdk/concurrent/locks/condition' },
            { text: '断点实验手册', link: '/jdk/concurrent/locks/debug-lab' }
          ]
        }
      ],
      '/jdk/concurrent/thread-locksupport/': [
        {
          text: 'Thread / LockSupport',
          items: [
            { text: '线程启动与许可模型', link: '/jdk/concurrent/thread-locksupport/' },
            { text: 'Thread 状态与生命周期', link: '/jdk/concurrent/thread-locksupport/thread-state' },
            { text: 'park、unpark 与 blocker', link: '/jdk/concurrent/thread-locksupport/park-unpark' },
            { text: '中断、定时等待与收口', link: '/jdk/concurrent/thread-locksupport/interrupt' },
            { text: '断点实验手册', link: '/jdk/concurrent/thread-locksupport/debug-lab' }
          ]
        }
      ],
      '/jdk/io/nio/': [
        {
          text: 'ByteBuffer / Selector',
          items: [
            { text: '缓冲区与事件循环主线', link: '/jdk/io/nio/' },
            { text: 'Buffer 状态机与内存边界', link: '/jdk/io/nio/buffer-state' },
            { text: 'Selector 选择循环', link: '/jdk/io/nio/selector-loop' },
            { text: '断点实验手册', link: '/jdk/io/nio/debug-lab' }
          ]
        }
      ],
      '/jdk/runtime/reference-weakhashmap/': [
        {
          text: 'Reference / WeakHashMap',
          items: [
            { text: '可达性与弱键主线', link: '/jdk/runtime/reference-weakhashmap/' },
            { text: 'Reference 处理链', link: '/jdk/runtime/reference-weakhashmap/reference-processing' },
            { text: 'WeakHashMap 与惰性清理', link: '/jdk/runtime/reference-weakhashmap/weakhashmap' },
            { text: '断点实验手册', link: '/jdk/runtime/reference-weakhashmap/debug-lab' }
          ]
        }
      ],
      '/jdk/runtime/classloader/': [
        {
          text: 'ClassLoader / ServiceLoader',
          items: [
            { text: '类加载与 SPI 阅读主线', link: '/jdk/runtime/classloader/' },
            { text: '委派、定义与类型身份', link: '/jdk/runtime/classloader/loading-delegation' },
            { text: 'SPI 发现、惰性与缓存', link: '/jdk/runtime/classloader/serviceloader-spi' },
            { text: 'TCCL、资源与模块化', link: '/jdk/runtime/classloader/context-module' },
            { text: '断点实验手册', link: '/jdk/runtime/classloader/debug-lab' }
          ]
        }
      ],
      '/jdk/runtime/reflection-proxy/': [
        {
          text: 'Reflection / Dynamic Proxy',
          items: [
            { text: '反射与代理阅读主线', link: '/jdk/runtime/reflection-proxy/' },
            { text: 'Method 元数据与调用', link: '/jdk/runtime/reflection-proxy/reflection-invoke' },
            { text: 'Proxy 生成、缓存与分派', link: '/jdk/runtime/reflection-proxy/dynamic-proxy' },
            { text: '访问控制与版本差异', link: '/jdk/runtime/reflection-proxy/access-version' },
            { text: '断点实验手册', link: '/jdk/runtime/reflection-proxy/debug-lab' }
          ]
        }
      ],
      '/spring/deep-dive/': [
        {
          text: 'Spring 核心链路深挖',
          items: [
            { text: '五条链路总览', link: '/spring/deep-dive/' },
            { text: 'Boot 怎样进入 refresh', link: '/spring/deep-dive/startup-refresh' },
            { text: 'Bean、早期引用与最终代理', link: '/spring/deep-dive/bean-proxy-cycle' },
            { text: 'MVC 请求怎样进入事务代理', link: '/spring/deep-dive/request-transaction' },
            { text: '异常、传播与清理边界', link: '/spring/deep-dive/failure-boundaries' },
            { text: '跨模块断点实验', link: '/spring/deep-dive/debug-lab' }
          ]
        }
      ],
      '/spring/ioc/': [
        {
          text: 'Spring IOC 源码解析',
          items: [
            { text: '容器分层与阅读主线', link: '/spring/ioc/' },
            { text: 'refresh 十二阶段', link: '/spring/ioc/refresh' },
            { text: '配置类解析与 full/lite', link: '/spring/ioc/configuration-class' },
            { text: '依赖候选解析', link: '/spring/ioc/dependency-resolution' },
            { text: 'Bean 创建完整链路', link: '/spring/ioc/bean-creation' },
            { text: '三级缓存与循环依赖', link: '/spring/ioc/circular-dependency' },
            { text: '容器扩展点', link: '/spring/ioc/extension-points' },
            { text: '断点实验手册', link: '/spring/ioc/debug-lab' }
          ]
        }
      ],
      '/spring/aop/': [
        {
          text: 'Spring AOP 源码解析',
          items: [
            { text: 'AOP 阅读主线', link: '/spring/aop/' },
            { text: '代理创建与选择', link: '/spring/aop/proxy-creation' },
            { text: '拦截器调用链', link: '/spring/aop/interceptor-chain' },
            { text: '切点、自调用与边界', link: '/spring/aop/pointcut-self-invocation' },
            { text: '断点实验手册', link: '/spring/aop/debug-lab' }
          ]
        }
      ],
      '/spring/transaction/': [
        {
          text: 'Spring Transaction 源码解析',
          items: [
            { text: '事务阅读主线', link: '/spring/transaction/' },
            { text: '事务拦截器', link: '/spring/transaction/transaction-interceptor' },
            { text: '传播、挂起与恢复', link: '/spring/transaction/propagation-suspension' },
            { text: '提交、回滚与 rollback-only', link: '/spring/transaction/commit-rollback' },
            { text: '断点实验手册', link: '/spring/transaction/debug-lab' }
          ]
        }
      ],
      '/spring/boot-autoconfigure/': [
        {
          text: 'Spring Boot 自动装配',
          items: [
            { text: '自动装配阅读主线', link: '/spring/boot-autoconfigure/' },
            { text: 'SpringApplication.run', link: '/spring/boot-autoconfigure/springapplication-run' },
            { text: '候选配置导入与筛选', link: '/spring/boot-autoconfigure/enable-autoconfiguration-import' },
            { text: '条件评估与配置绑定', link: '/spring/boot-autoconfigure/condition-binding' },
            { text: '断点实验手册', link: '/spring/boot-autoconfigure/debug-lab' }
          ]
        }
      ],
      '/spring/mvc/': [
        {
          text: 'Spring MVC 源码解析',
          items: [
            { text: 'MVC 阅读主线', link: '/spring/mvc/' },
            { text: 'DispatcherServlet 请求编排', link: '/spring/mvc/dispatch-chain' },
            { text: 'HandlerAdapter 与方法调用', link: '/spring/mvc/handler-adapter' },
            { text: '参数、返回值与异常', link: '/spring/mvc/argument-return-exception' },
            { text: '断点实验手册', link: '/spring/mvc/debug-lab' }
          ]
        }
      ],
      '/guide/': [
        {
          text: '项目指南',
          items: [
            { text: '如何阅读一个主题', link: '/guide/reading' },
            { text: '贡献源码解析', link: '/guide/contributing' }
          ]
        }
      ],
      '/reference/': [
        {
          text: '参考资料',
          items: [
            { text: '源码与许可证', link: '/reference/source-license' }
          ]
        }
      ]
    },
    outline: {
      level: [2, 3],
      label: '本页目录'
    },
    docFooter: {
      prev: '上一篇',
      next: '下一篇'
    },
    lastUpdated: {
      text: '最后更新'
    },
    socialLinks: []
  },
  markdown: {
    lineNumbers: true
  }
})
