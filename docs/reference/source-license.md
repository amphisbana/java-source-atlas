# 源码引用与许可证

## OpenJDK

HashMap 专题参考 OpenJDK 的 `java.util.HashMap` 固定版本实现：

- [OpenJDK 8u412 HashMap.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/HashMap.java)
- [OpenJDK 17 GA HashMap.java](https://github.com/openjdk/jdk/blob/jdk-17%2B35/src/java.base/share/classes/java/util/HashMap.java)
- [OpenJDK 21 GA HashMap.java](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/HashMap.java)

固定 tag 与解引用后的提交哈希统一记录在仓库根目录的 `source-index/baselines.json`。源码索引与文档校验会拒绝 `master`、`main` 或未登记版本，避免上游默认分支变化后讲解和断点悄悄漂移。

新增专题还参考以下 OpenJDK 8u 原始类文件：

- [ArrayList.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/ArrayList.java)
- [Queue.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/Queue.java)
- [LinkedHashMap.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/LinkedHashMap.java)
- [TreeMap.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/TreeMap.java)
- [AtomicInteger.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/AtomicInteger.java)
- [LongAdder.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/LongAdder.java)
- [Striped64.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/atomic/Striped64.java)
- [ConcurrentHashMap.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ConcurrentHashMap.java)
- [ConcurrentLinkedQueue.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ConcurrentLinkedQueue.java)
- [CopyOnWriteArrayList.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/CopyOnWriteArrayList.java)
- [BlockingQueue.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/BlockingQueue.java)
- [ArrayBlockingQueue.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ArrayBlockingQueue.java)
- [LinkedBlockingQueue.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/LinkedBlockingQueue.java)
- [SynchronousQueue.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/SynchronousQueue.java)
- [FutureTask.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java)
- [RunnableFuture.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/RunnableFuture.java)
- [AbstractExecutorService.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/AbstractExecutorService.java)
- [CompletableFuture.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/CompletableFuture.java)
- [ThreadPoolExecutor.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ThreadPoolExecutor.java)
- [ScheduledThreadPoolExecutor.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ScheduledThreadPoolExecutor.java)
- [ThreadLocal.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ThreadLocal.java)
- [Thread.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/Thread.java)
- [InheritableThreadLocal.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/InheritableThreadLocal.java)
- [WeakReference.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/lang/ref/WeakReference.java)
- [ForkJoinPool.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ForkJoinPool.java)
- [ForkJoinTask.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ForkJoinTask.java)
- [ForkJoinWorkerThread.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ForkJoinWorkerThread.java)
- [RecursiveTask.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/RecursiveTask.java)
- [CountedCompleter.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/CountedCompleter.java)
- [Spliterator.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/Spliterator.java)
- [AbstractPipeline.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/AbstractPipeline.java)
- [ReferencePipeline.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/ReferencePipeline.java)
- [Sink.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/Sink.java)
- [StreamOpFlag.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/StreamOpFlag.java)
- [AbstractTask.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/AbstractTask.java)
- [AbstractShortCircuitTask.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/AbstractShortCircuitTask.java)
- [ReduceOps.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/ReduceOps.java)
- [ForEachOps.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/stream/ForEachOps.java)
- [AbstractQueuedSynchronizer.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/locks/AbstractQueuedSynchronizer.java)
- [ReentrantLock.java](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/locks/ReentrantLock.java)

OpenJDK 源码采用 GPLv2 with Classpath Exception。链接目标、原始注释及第三方源码片段遵循 OpenJDK 的原始许可证。

## Spring Framework

Spring IOC、AOP、Transaction 与 MVC 专题以 Spring Framework `v5.3.39` 为主基线。IOC 主要参考：

- [AbstractApplicationContext.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java)
- [PostProcessorRegistrationDelegate.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-context/src/main/java/org/springframework/context/support/PostProcessorRegistrationDelegate.java)
- [DefaultListableBeanFactory.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java)
- [AbstractBeanFactory.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java)
- [AbstractAutowireCapableBeanFactory.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java)
- [DefaultSingletonBeanRegistry.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java)

AOP 与事务主要参考：

- [AbstractAutoProxyCreator.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java)
- [DefaultAopProxyFactory.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-aop/src/main/java/org/springframework/aop/framework/DefaultAopProxyFactory.java)
- [ReflectiveMethodInvocation.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-aop/src/main/java/org/springframework/aop/framework/ReflectiveMethodInvocation.java)
- [TransactionInterceptor.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionInterceptor.java)
- [TransactionAspectSupport.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionAspectSupport.java)
- [AbstractPlatformTransactionManager.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-tx/src/main/java/org/springframework/transaction/support/AbstractPlatformTransactionManager.java)

MVC 主要参考：

- [DispatcherServlet.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-webmvc/src/main/java/org/springframework/web/servlet/DispatcherServlet.java)
- [AbstractHandlerMethodMapping.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-webmvc/src/main/java/org/springframework/web/servlet/handler/AbstractHandlerMethodMapping.java)
- [RequestMappingHandlerAdapter.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.java)
- [InvocableHandlerMethod.java](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-web/src/main/java/org/springframework/web/method/support/InvocableHandlerMethod.java)

Spring Framework 源码采用 Apache License 2.0。本文档中的方法签名、调用关系与少量伪代码用于教学定位，上游源码仍遵循 Spring Framework 原始许可证。

## Spring Boot

自动装配专题以 Spring Boot `v2.7.18` 为可执行基线，并使用 Boot 3.x 对应源码核对迁移边界。主要参考：

- [SpringApplication.java](https://github.com/spring-projects/spring-boot/blob/v2.7.18/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/SpringApplication.java)
- [AutoConfigurationImportSelector.java](https://github.com/spring-projects/spring-boot/blob/v2.7.18/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationImportSelector.java)
- [SpringBootCondition.java](https://github.com/spring-projects/spring-boot/blob/v2.7.18/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/SpringBootCondition.java)
- [ConditionEvaluationReport.java](https://github.com/spring-projects/spring-boot/blob/v2.7.18/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/ConditionEvaluationReport.java)
- [ConfigurationPropertiesBindingPostProcessor.java](https://github.com/spring-projects/spring-boot/blob/v2.7.18/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/context/properties/ConfigurationPropertiesBindingPostProcessor.java)

Spring Boot 源码采用 Apache License 2.0。Boot 2.7 与 Boot 3 的候选资源差异以相应 tag 的实现为准，不把当前主分支行为倒推到旧版本。

## 本项目的引用原则

- 优先使用类名、方法签名和上游永久链接定位源码。
- 文档只摘录解释所需的最小片段，其余使用流程图或伪代码表达。
- 明确区分 OpenJDK 的公开契约、当前实现细节和本项目原创解释。
- 本项目的 Apache License 2.0 不覆盖或重新许可任何第三方源码。
