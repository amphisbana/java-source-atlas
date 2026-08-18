# 异常、传播与清理边界：事务决定和 HTTP 决定不是一回事

异常路径最容易产生跨模块误判。Spring 事务决定数据库资源怎样完成；Spring MVC 决定异常怎样变成 HTTP 响应。它们通过同一个 Throwable 串联，但执行顺序和职责不同。

## 源码入口

| 职责 | 类与方法 |
| --- | --- |
| 事务异常模板 | `TransactionAspectSupport.completeTransactionAfterThrowing(...)` |
| 回滚规则 | `DefaultTransactionAttribute.rollbackOn(...)` / `RuleBasedTransactionAttribute.rollbackOn(...)` |
| 提交入口 | `AbstractPlatformTransactionManager.commit(...)` |
| 回滚入口 | `rollback(...)` / `processRollback(...)` |
| 参与者标记 | `doSetRollbackOnly(...)` |
| 提交前检查 | `isLocalRollbackOnly()` / `isGlobalRollbackOnly()` |
| 资源清理 | `cleanupAfterCompletion(...)` / 具体管理器 `doCleanupAfterCompletion(...)` |
| MVC 捕获 | `DispatcherServlet.doDispatch(...)` 内层 catch |
| MVC 异常解析 | `processHandlerException(...)` |
| 注解异常处理 | `ExceptionHandlerExceptionResolver.doResolveHandlerMethodException(...)` |
| 最终收尾 | `processDispatchResult(...)` / `triggerAfterCompletion(...)` |

## 一次 RuntimeException 的真实顺序

下面的完整“回滚并解绑后交给 MVC”主线，假设 Service 代理新建并拥有最外层事务，且没有事务过滤器把整个 `DispatcherServlet` 包在更外层事务中。调用骨架也适用于参与事务，但物理完成动作不同，后文会单独拆开。

```text
Controller
  -> Service proxy
      -> TransactionInterceptor
          -> begin transaction（参与者分支则 join outer）
          -> Service target
              -> throws RuntimeException
          -> catch in invokeWithinTransaction
              -> completeTransactionAfterThrowing
                  -> txAttr.rollbackOn(ex) == true
                  -> transactionManager.rollback(status)
                      -> processRollback
                      -> doRollback（参与者分支则 doSetRollbackOnly）
                      -> cleanupAfterCompletion
              -> cleanupTransactionInfo in finally
              -> rethrow original exception
      -> exception leaves proxy
  -> exception leaves Controller
-> RequestMappingHandlerAdapter throws
-> DispatcherServlet.doDispatch inner catch
   dispatchException = ex
-> processDispatchResult
-> processHandlerException
-> HandlerExceptionResolver chain
-> response / ModelAndView
```

事务异常处理发生在异常退出 Service proxy 时，MVC 异常解析发生在异常退出 Controller 调用之后。在上述 owner 主线中，`@ControllerAdvice` 看见异常时，本次事务拥有的数据库资源已经提交或回滚并解绑。

如果 Service 只是 REQUIRED 参与者，`transactionManager.rollback(participatingStatus)` 通常进入 `doSetRollbackOnly`，不会执行物理 `doRollback`，也不会解绑外层资源。若外层事务包围 MVC，请求到达 Resolver 时外层事务甚至仍然活动。两种路径共同保证的是“异常先离开当前 Service advice，MVC 后解析”，不是“到 MVC 时整个线程资源 Map 必然为空”。

## 回滚规则先决定“请求管理器做什么”

默认规则：

| 异常 | 默认 `rollbackOn` | 当前 advice 请求管理器执行的动作 |
| --- | --- | --- |
| `RuntimeException` | true | rollback |
| `Error` | true | rollback |
| 受检异常 | false | commit |
| `rollbackFor` 命中 | true | rollback |
| `noRollbackFor` 更具体命中 | false | commit |

`RuleBasedTransactionAttribute` 根据异常类型继承距离选择更具体规则。它决定事务资源动作，不决定 MVC 最终返回 4xx、5xx 还是被异常处理器转换成 200。

表中的 `commit/rollback` 是当前 status 交给事务管理器的入口，不保证立即发生物理提交或回滚。status 是新事务 owner 时才执行物理完成；参与者可能只保留外层资源或标记共享事务 rollback-only。

### 捕获异常不等于取消回滚

分两种位置：

1. **目标方法内部捕获，异常从未离开事务方法。** `TransactionInterceptor` 只看到正常返回，通常提交，除非代码显式设置 rollback-only 或资源自身标记失败。
2. **内层事务代理已处理异常，外层 target 再捕获。** 内层 REQUIRED 参与者可能已经把共享事务标记 rollback-only；外层正常返回仍会在 commit 时回滚并抛 `UnexpectedRollbackException`。

第二种调用链：

```text
outerProxy.outer() REQUIRED, new transaction
  -> outerTarget
      -> innerProxy.inner() REQUIRED, participates
          -> innerTarget throws RuntimeException
          -> inner interceptor: doSetRollbackOnly(shared transaction)
      -> outerTarget catches RuntimeException and returns normally
  -> outer interceptor: commit(outerStatus)
      -> isGlobalRollbackOnly == true
      -> processRollback(unexpected=true)
      -> UnexpectedRollbackException
```

外层没有“神秘地提交失败”。共享事务已经被内层参与者明确标记，最外层 owner 只是到提交点才公开这个结果。

## REQUIRED、REQUIRES_NEW、NESTED 的失败差异

| 维度 | 内层 REQUIRED | 内层 REQUIRES_NEW | 内层 NESTED |
| --- | --- | --- | --- |
| 是否再次经过 proxy | 必须 | 必须 | 必须 |
| 物理资源 | 与外层共享 | 通常新资源 | 通常共享外层资源 |
| 外层是否挂起 | 否 | 是 | 否 |
| 内层失败动作 | 标记共享事务 rollback-only | 回滚独立内层事务 | 回滚到保存点 |
| 外层捕获后能否提交 | 通常最终 UnexpectedRollback | 可以，取决于外层后续 | 可以，若保存点回滚成功且外层未被标记 |
| 连接池影响 | 不新增连接 | 同线程可能同时占两条连接 | 通常不新增连接 |
| 核心状态 | `newTransaction=false` | `newTransaction=true` + suspended | `hasSavepoint=true` |

传播名称不能代替管理器能力。`NESTED` 是否使用保存点取决于具体事务管理器和资源；JPA/JTA 场景不能照搬 JDBC Lab 的结果。

## 提交入口为什么也可能执行回滚

`AbstractPlatformTransactionManager.commit(status)` 不是无条件 `doCommit`：

```text
commit(status)
  -> status.isCompleted ? reject
  -> status.isLocalRollbackOnly ? processRollback
  -> shouldCommitOnGlobalRollbackOnly == false
     && status.isGlobalRollbackOnly ? processRollback(unexpected=true)
  -> otherwise processCommit
```

所以日志中出现“调用了 commit 方法”不能证明物理提交。应继续看 rollback-only 标志以及最终进入 `doCommit` 还是 `doRollback`。

## 清理有三层

| 清理层 | 方法 | 清理对象 | 失败后影响 |
| --- | --- | --- | --- |
| AOP 调用栈 | `cleanupTransactionInfo` | 当前 `TransactionInfo` ThreadLocal，恢复旧值 | 后续嵌套调用读到错误 advice 上下文 |
| 事务模板 | `cleanupAfterCompletion` | completed 标志、同步器、挂起事务恢复 | 资源和外层事务状态错配 |
| 具体资源 | `doCleanupAfterCompletion` | ConnectionHolder、autoCommit、连接释放 | 连接泄漏或线程残留资源 |

不要在业务 catch 中调用 `TransactionSynchronizationManager.clear()`。它会跳过 owner 的配对清理、同步回调和挂起恢复，使后面的模板代码面对被人为破坏的状态。

`doCleanupAfterCompletion` 只由拥有新事务的 status 执行具体资源清理；参与者的 `cleanupAfterCompletion` 只完成当前 status 的收尾，不取得外层资源的完成权。OSIV 等基础设施还可能绑定与本次 JDBC 事务无关的 key，因此验证时应按资源 key 和 owner 判断，而不是直接断言整个资源 Map 为空。

## MVC 异常解析只负责 HTTP 语义

`DispatcherServlet.processHandlerException` 先清理可能设置的 response content type/buffer，再按顺序询问 `HandlerExceptionResolver`。常见解析器包括：

- `ExceptionHandlerExceptionResolver`：调用 Controller 或 `@ControllerAdvice` 的 `@ExceptionHandler`。
- `ResponseStatusExceptionResolver`：解释 `@ResponseStatus` 和 `ResponseStatusException`。
- `DefaultHandlerExceptionResolver`：把部分 MVC 框架异常映射为标准状态。

解析结果必须区分：

| resolver 返回值 | 含义 |
| --- | --- |
| `null` | 当前 resolver 未处理，继续询问下一项 |
| 空 `ModelAndView` | 已处理，不需要进一步渲染视图 |
| 非空 `ModelAndView` | 已处理，后续按模型和视图渲染 |

这些结果不会重新进入或撤销已经退出的 Service advice。异常处理方法若需要写数据库，应调用另一个带明确事务边界的 Bean：在本专题 owner 主线中，它创建的是新的业务动作；若应用有包围 MVC 的外层事务，它也可能参与该外层事务，必须通过实际代理链和 transaction status 确认，不能笼统称为“继续原 Service 事务”。

## 四种跨边界场景

### 1. Controller 捕获 Service 异常并返回成功

```text
Service proxy 已按规则回滚
  -> exception 到 Controller
  -> Controller catch
  -> 返回 success body
```

HTTP 可以是 200，但事务仍已回滚。这是 API 语义问题，不是事务失效。

### 2. Service 正常提交，响应序列化失败

```text
Service proxy commit 完成
  -> Controller return
  -> HttpMessageConverter.write
  -> JSON 序列化或网络写出失败
```

默认 Service 事务已结束，响应失败不能自动撤销数据库提交。需要“数据库提交且消息/响应可靠”时，应使用 outbox、幂等查询或明确的一致性设计，不能把 HTTP socket 写出当数据库事务资源。

### 3. `@Async` 中继续写数据库

命令式事务资源位于原线程 `TransactionSynchronizationManager`：

```text
request-thread resources = {dataSource -> connectionHolder}
async-thread resources   = {}
```

不要通过 TaskDecorator 复制 ConnectionHolder。异步方法应建立自己的事务，并用消息、补偿或幂等表达跨线程一致性。

### 4. MVC 异步请求

Controller 返回 `Callable`、`DeferredResult` 等可能让 Servlet 请求进入异步派发。首次 dispatch 与后续 dispatch 是两次 MVC 阶段；原请求线程上的命令式事务不会自动跟随异步任务。

如果事务注解包围返回 `Callable` 的 Controller 方法，它通常只包围“创建 Callable 并返回”的同步阶段，不自动包围 Callable 在异步线程中的数据库操作。

## 异常路径状态表

下表仍以 Service 拥有新事务为基线。“已解绑”只描述本次事务的资源 key，不表示 OSIV 或其他组件绑定的整个 resource map 必然为空。

| 时刻 | Throwable 所在层 | status | resources | MVC 状态 |
| --- | --- | --- | --- | --- |
| target 抛出 | Service target | active | 已绑定 | 等待 HandlerAdapter |
| `completeTransactionAfterThrowing` | TX Interceptor | rollbackOn 已判断 | 仍绑定 | 尚未捕获 |
| `doRollback` 后 | 事务管理器 | completed 处理中 | 准备解绑 | 尚未捕获 |
| cleanup 完成 | 异常离开 Service proxy | completed=true | 本事务资源 key 已解绑 | HandlerAdapter 将抛出 |
| `doDispatch` 内层 catch | DispatcherServlet | 本次事务已结束 | 本事务 key 不再绑定；其他资源可能仍在 | `dispatchException=ex` |
| resolver 命中 | HandlerExceptionResolver | 本次事务已结束 | 不能以整个 Map 为空作为前提 | 生成响应/ModelAndView |
| `afterCompletion` | HandlerInterceptor | 本次事务已结束 | 由各资源 owner 分别收尾 | 请求收尾 |

## 常见误判

| 误判 | 应怎样验证 |
| --- | --- |
| `@ControllerAdvice` 吞异常会导致事务提交 | 查看异常是否已离开 Service proxy，以及 `doRollback/doCommit` 先发生哪个 |
| catch 住内层 REQUIRED 异常就能提交外层 | 检查 `isGlobalRollbackOnly` 和最终 `UnexpectedRollbackException` |
| 调用 commit 就一定物理提交 | 继续跟到 `processCommit/doCommit` 或 `processRollback/doRollback` |
| NESTED 等于新事务 | 看 `hasSavepoint`、资源身份和是否发生 suspend/resume |
| REQUIRES_NEW 只是换一个 status 对象 | 通常还会挂起外层资源并取得新资源 |
| `@Async` 会继承请求事务 | 比较两个线程的 `TransactionSynchronizationManager.getResourceMap()` |
| 响应写失败会回滚数据库 | 比较 commit 与返回值处理器的时间顺序 |
| MVC catch 时资源 Map 必须为空 | 检查本次事务拥有的 key；外围事务、OSIV 和其他资源可继续存在 |

## 断点路线：异常先经过谁

| 顺序 | 断点 | 条件/变量 |
| --- | --- | --- |
| 1 | `TransactionAspectSupport.invokeWithinTransaction` catch | 目标异常、`txInfo` |
| 2 | `RuleBasedTransactionAttribute.rollbackOn` | 匹配规则及继承距离 |
| 3 | `completeTransactionAfterThrowing` | 选择 rollback 还是 commit |
| 4 | `AbstractPlatformTransactionManager.processRollback` | `newTransaction`、`hasSavepoint`、unexpected |
| 5 | `doSetRollbackOnly` / `doRollback` | 参与者标记还是物理回滚 |
| 6 | `cleanupAfterCompletion` | synchronization、suspendedResources |
| 7 | `doCleanupAfterCompletion` | resource map、ConnectionHolder |
| 8 | `DispatcherServlet.doDispatch` catch | `dispatchException` |
| 9 | `processHandlerException` | resolver 列表和返回值 |
| 10 | `ExceptionHandlerExceptionResolver` | advice/handler method、返回结果 |

在第 7 和第 8 个断点分别记录线程名和资源 Map，能直观看见 MVC 接手前事务已经清理。

## 可运行案例映射

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest=TransactionRollbackRuleTest test

mvn -pl labs/spring-framework-lab \
  -Dtest=TransactionPropagationTest test

mvn -pl labs/spring-framework-lab \
  -Dtest=SpringMvcBehaviorTest#shouldResolveControllerException test
```

| 测试 | 核心证据 |
| --- | --- |
| `shouldRollbackForRuntimeException` | RuntimeException 默认 rollback |
| `shouldCommitForCheckedExceptionByDefault` | 受检异常默认 commit，但异常仍传播 |
| `shouldRollbackForConfiguredCheckedException` | rollbackFor 改变完成动作 |
| `shouldReportUnexpectedRollbackForCaughtRequiredFailure` | 内层标记后，外层正常返回仍不能提交 |
| `shouldSuspendAndResumeForRequiresNew` | tx-1 suspend、tx-2 完成、tx-1 resume 的顺序 |
| `shouldRollbackNestedSavepointAndCommitOuterTransaction` | 保存点回滚不等于独立物理事务 |
| `shouldResolveControllerException` | MVC resolver 把已传播异常转换成响应 |
| `shouldNotPropagateTransactionToNewThread` | 命令式资源不跨线程继承 |

把事务和 MVC 测试的事件时间线并排阅读，可以证明两个专题各自的职责；不要把两个最小上下文误认为一个共享事务的集成测试。

## 过关问题

1. `@ControllerAdvice` 把 RuntimeException 转成 200 时，数据库为什么仍可能已经回滚？
2. 内层 REQUIRED 抛异常后被外层 catch，哪个对象保存 rollback-only，谁最终执行物理回滚？
3. `commit(status)` 为什么可能进入 `processRollback`？
4. `cleanupTransactionInfo`、`cleanupAfterCompletion`、`doCleanupAfterCompletion` 各自清什么？
5. REQUIRES_NEW 和 NESTED 在资源数量、外层挂起和失败隔离上有什么差异？
6. Controller 返回 `Callable` 时，原线程事务为何通常只包住 Callable 的创建？
7. 怎样用断点证明异常到达 MVC resolver 前资源 Map 已为空？
8. 响应写出失败时，为什么 outbox 比扩大数据库事务到 HTTP 写出更可靠？

下一页把前面四章整理成可以直接执行的断点路线和过关清单。
