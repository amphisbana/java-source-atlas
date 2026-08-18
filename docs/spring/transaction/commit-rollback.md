# 提交、回滚与 rollback-only：方法返回后还会发生什么

## 源码入口

- 正常返回：`TransactionAspectSupport.commitTransactionAfterReturning(...)`
- 异常返回：`TransactionAspectSupport.completeTransactionAfterThrowing(...)`
- 默认规则：`DefaultTransactionAttribute.rollbackOn(Throwable)`
- 规则匹配：`RuleBasedTransactionAttribute.rollbackOn(Throwable)`
- 提交入口：`AbstractPlatformTransactionManager.commit(TransactionStatus)`
- 提交模板：`processCommit(DefaultTransactionStatus)`
- 回滚模板：`processRollback(DefaultTransactionStatus, boolean)`
- 回滚标记：`doSetRollbackOnly(DefaultTransactionStatus)`
- 同步回调：`TransactionSynchronizationUtils`

事务完成不是“方法没异常就执行 SQL COMMIT，有异常就执行 SQL ROLLBACK”这么简单。至少有三次决策：

1. 目标异常是否符合 `TransactionAttribute.rollbackOn`。
2. 当前逻辑 status 是否只是参与者、保存点或新事务 owner。
3. 进入 commit 时是否已经存在 local/global rollback-only。

## 拦截器的第一层完成决策

### 正常返回

```text
target method returns
  ├─ cleanupTransactionInfo(txInfo)
  ├─ commitTransactionAfterReturning(txInfo)
  │    └─ transactionManager.commit(status)
  └─ return value to caller
```

正常返回只代表拦截器请求 commit。`transactionManager.commit` 仍可能因为 rollback-only 改为回滚，或因底层提交失败抛出异常。

### 异常返回

```text
target method throws ex
  └─ completeTransactionAfterThrowing(txInfo, ex)
       ├─ txAttr.rollbackOn(ex) == true
       │    └─ transactionManager.rollback(status)
       └─ txAttr.rollbackOn(ex) == false
            └─ transactionManager.commit(status)
                 // status 已 rollback-only 时仍会回滚
```

然后原业务异常重新抛给调用者。一个受检异常默认选择 commit，不表示异常被 Spring 吞掉；调用者仍会收到该异常。

## 默认异常规则

`DefaultTransactionAttribute.rollbackOn` 的默认语义：

| Throwable 类型 | 默认动作 |
| --- | --- |
| `RuntimeException` 及其子类 | rollback |
| `Error` 及其子类 | rollback |
| 其他受检异常 | commit |

这是 Spring 的约定，不是 Java 或数据库的强制规则。其设计倾向是：运行时异常通常代表不可恢复的编程/系统错误，受检异常可能是业务预期分支。

实际项目不能机械套用。例如“余额不足”若建模为受检异常，但整个用例必须撤销已写数据，就要显式 `rollbackFor`，或重新审视异常和写入边界。

## rollbackFor 与 noRollbackFor 怎样竞争

`SpringTransactionAnnotationParser` 把注解规则转换为：

- `RollbackRuleAttribute`
- `NoRollbackRuleAttribute`

`RuleBasedTransactionAttribute.rollbackOn` 会计算每条规则与实际异常类的继承距离，选择距离最浅、最具体的规则：

```java
@Transactional(
    rollbackFor = Exception.class,
    noRollbackFor = ValidationException.class
)
```

`ValidationException` 命中更具体的 no-rollback 规则，因此 commit；其他 `Exception` 子类命中 rollback 规则。没有规则匹配时回退到默认 RuntimeException/Error 逻辑。

### 类规则优先于字符串规则

优先使用：

```java
rollbackFor = InventoryException.class
```

`rollbackForClassName` / `noRollbackForClassName` 使用异常类名模式匹配，没有编译期类型检查。过短模式如 `"Exception"` 可能意外匹配大量异常，嵌套类或相似后缀也可能命中。只有在异常类型不方便直接依赖时才考虑字符串规则，并用测试固定结果。

### 避免同深度冲突

不要同时为同一异常层次配置语义相反、深度相同的规则，再依赖列表先后顺序。即使当前实现结果稳定，这种配置对维护者也不可读。把规则收敛到一个明确的最具体类型。

## commit 入口先检查 rollback-only

`AbstractPlatformTransactionManager.commit` 的第一段判断可概括为：

```text
commit(status)
  ├─ status.completed ? 抛 IllegalTransactionStateException
  ├─ status.isLocalRollbackOnly()
  │    └─ processRollback(status, unexpected=false)
  ├─ !shouldCommitOnGlobalRollbackOnly()
  │    && status.isGlobalRollbackOnly()
  │    └─ processRollback(status, unexpected=true)
  └─ processCommit(status)
```

因此调用 `commit` 不是物理提交保证。它更接近“请按当前 status 完成本事务”；模板会尊重之前所有逻辑范围留下的回滚决定。

## local rollback-only 与 global rollback-only

### local rollback-only

当前 `TransactionStatus.setRollbackOnly()` 设置本逻辑 status 的本地标记。提交这个 status 时会直接回滚。

它适合极少数不能通过异常表达、但明确必须撤销当前事务的场景。滥用会形成隐藏控制流：方法正常返回，调用者却无法从签名看出事务已经注定回滚。

### global rollback-only

底层共享事务对象或资源持有器上的回滚标记。例如内层 `REQUIRED` 参与者失败：

```text
statusInner.newTransaction=false
  → processRollback(statusInner)
    → doSetRollbackOnly(statusInner)
      → shared tx-1.rollbackOnly = true

statusOuter.commit()
  → statusOuter.isGlobalRollbackOnly() == true
    → rollback tx-1
```

`DefaultTransactionStatus.isGlobalRollbackOnly` 会询问底层事务对象是否实现并报告全局回滚状态。实验管理器通过 `SmartTransactionObject` 暴露这个标记。

## UnexpectedRollbackException 为什么必要

外层方法可能正常返回：

```java
@Transactional
public void outer() {
    try {
        otherBean.innerRequired();
    } catch (RuntimeException ignored) {
        // 继续执行
    }
}
```

但内层代理已经标记共享事务。若外层 commit 静默改成 rollback，调用者会误以为操作成功。因此模板在外层完成点抛出 `UnexpectedRollbackException`，明确表示“你请求提交，但事务已经被其他参与范围决定回滚”。

典型事件：

```text
begin:tx-1
inner REQUIRED throws
mark-rollback-only:tx-1
outer catches and returns normally
rollback:tx-1
UnexpectedRollbackException
```

它不是数据库随机拒绝提交，也不应该通过捕获后忽略来“修复”。应回溯是谁先标记 rollback-only，以及内外层是否应共享事务。

## 参与者回滚的两个配置开关

`AbstractPlatformTransactionManager` 有两个容易混淆的配置：

### globalRollbackOnParticipationFailure

默认 true。参与现有事务的逻辑范围发生回滚时，模板调用 `doSetRollbackOnly` 标记共享事务。

设为 false 后，某些参与者失败可以让发起者决定是否继续，但能否真正恢复取决于资源状态。一次失败的 ORM flush、批处理或数据库错误可能已经让底层事务无法继续；不要只改开关绕过异常。

### failEarlyOnGlobalRollbackOnly

默认 false。共享事务通常在最外层边界才抛 `UnexpectedRollbackException`。设为 true 后，检测到全局回滚标记的较内层参与完成点也可以更早失败。

它改变异常出现位置，不会把已标记事务恢复成可提交状态。

## processCommit 的阶段

对一个新事务，提交模板可以概括为：

```text
processCommit(status)
  ├─ prepareForCommit(status)
  ├─ triggerBeforeCommit(status)
  ├─ triggerBeforeCompletion(status)
  ├─ 完成资源
  │    ├─ status.hasSavepoint() → releaseHeldSavepoint()
  │    ├─ status.isNewTransaction() → doCommit(status)
  │    └─ 参与者 → 不做物理提交
  ├─ 必要时检查 unexpected rollback
  ├─ triggerAfterCommit(status)
  ├─ triggerAfterCompletion(status, COMMITTED)
  └─ finally cleanupAfterCompletion(status)
```

三个分支含义不同：

- 保存点 status 的“提交”通常只是释放保存点。
- 新事务 status 才调用具体管理器 `doCommit`。
- 普通参与者的 commit 不物理提交共享事务，最终 owner 才能完成。

如果提交过程失败，模板根据异常类型、管理器配置和底层状态选择回滚尝试或 `STATUS_UNKNOWN` 通知。此时必须保留原提交异常和资源日志，不能把所有失败都归类为普通业务回滚。

## processRollback 的阶段

```text
processRollback(status, unexpected)
  ├─ triggerBeforeCompletion(status)
  ├─ 完成回滚动作
  │    ├─ status.hasSavepoint()
  │    │    └─ rollbackToHeldSavepoint()
  │    ├─ status.isNewTransaction()
  │    │    └─ doRollback(status)
  │    └─ status.hasTransaction()
  │         └─ doSetRollbackOnly(status)（满足参与者标记策略时）
  ├─ triggerAfterCompletion(status, ROLLED_BACK)
  ├─ unexpected ? 抛 UnexpectedRollbackException
  └─ finally cleanupAfterCompletion(status)
```

“调用 rollback”也不一定立即执行物理回滚。参与者只能标记共享资源；保存点范围只回退保存点；新事务 owner 才做完整资源回滚。

## TransactionSynchronization 回调顺序

注册在当前同步范围的 `TransactionSynchronization` 可观察事务完成：

### 提交

```text
beforeCommit(readOnly)
  → beforeCompletion()
  → 物理 commit / 保存点释放
  → afterCommit()
  → afterCompletion(STATUS_COMMITTED)
```

### 回滚

```text
beforeCompletion()
  → 物理 rollback / 保存点回滚 / 标记 rollback-only
  → afterCompletion(STATUS_ROLLED_BACK)
```

挂起和恢复时，还可能调用 synchronization 的 `suspend()` 与 `resume()`。

回调可实现 `Ordered` 影响同阶段顺序。回调不应承担不可控的长耗时，更不应假定 `afterCommit` 抛异常可以撤销已经发生的物理提交。

## @TransactionalEventListener 的时间语义

事务事件监听器建立在事务同步机制之上，常见 phase：

| Phase | 含义 |
| --- | --- |
| `BEFORE_COMMIT` | 提交动作前，仍可能影响当前事务 |
| `AFTER_COMMIT` | 物理提交成功后 |
| `AFTER_ROLLBACK` | 回滚后 |
| `AFTER_COMPLETION` | 无论提交或回滚都在完成后 |

`AFTER_COMMIT` 时资源可能尚未彻底解绑，但主事务已经完成。监听器在此阶段继续写库，不应期待这些写操作能加入已提交事务并再次提交。若确实要写入独立数据，应建立明确的新事务并处理失败可靠性。

没有事务时，`@TransactionalEventListener` 默认不执行；`fallbackExecution=true` 才允许回退执行。开启后监听器就有“事务内”和“无事务”两种语义，必须分别测试。

## 完成阶段异常如何影响调用者

### rollback 失败

如果按回滚规则执行 rollback 时事务管理器抛异常，回滚异常可能覆盖原业务异常。Spring 会记录 application exception 被覆盖的信息，但调用者最终看到的通常是事务系统异常。诊断时要同时查看 cause、suppressed/日志和原始业务栈。

### commit 失败

目标方法正常返回并不保证调用成功。数据库在 commit 时可能发现约束、连接、超时或启发式结果问题。此时代理向调用者抛提交异常，业务返回值不会正常交付。

### afterCommit 失败

物理提交已经发生。后续回调异常不能回滚已提交数据，恢复策略应按“主事务成功，后置动作失败”处理，而不是盲目重试整个用例。

## try/catch 的四种结果

| 异常路径 | 事务 advice 看见异常吗 | 常见结果 |
| --- | --- | --- |
| 目标方法直接抛 RuntimeException 到代理 | 看见 | 当前新事务 rollback |
| 目标方法内部抛出又在返回前捕获 | 看不见 | 正常 commit，除非显式 rollback-only |
| 跨 Bean 的内层 REQUIRED 抛出，外层捕获 | 内层代理看见 | 共享事务被标记，外层最终 unexpected rollback |
| 跨 Bean 的内层 NESTED 抛出，外层捕获 | 内层代理看见 | 回滚保存点，外层可 commit |

代码审查时应画出“异常穿过哪些代理边界”，仅看最外层有没有 catch 不够。

## 返回值与异步异常

### Spring 5.3.39

经典同步方法在目标返回那一刻就进入 commit。稍后才异常完成的 `CompletableFuture` 不会让已经提交的事务倒流回滚。5.3 可选支持 Vavr `Try`，在返回时把 failure 按事务规则设置 rollback-only。

### Spring 6.1+

如果方法返回时 `Future`/`CompletableFuture` 已经处于异常完成状态，事务拦截器可以按回滚规则处理，主要用于与 `@Async` 返回契约协作。但它不是等待任意异步任务结束：返回时仍未完成的 Future，其后续异常不能撤销同步事务。

真正异步执行的数据库逻辑应在工作线程内部打开事务。

## 常见配置误区

### 用 Exception.class 全量回滚，却忘记业务拒绝异常

`rollbackFor = Exception.class` 会改变全部受检异常默认语义。若某些异常只是“没有找到可选数据”或合法拒绝，可能导致不必要回滚。规则应围绕事务一致性设计，而不是为了“保险”。

### 用 noRollbackFor 隐藏资源已经失败

即使属性选择 commit，底层数据库或 ORM 可能已经把事务标记 rollback-only。`noRollbackFor` 不能恢复资源状态，最终仍可能 unexpected rollback。

### 在 finally 中 return

Java `finally return` 会压掉目标异常，事务拦截器看到的是正常返回，可能请求 commit。禁止这种控制流，既会破坏事务语义，也会隐藏故障。

### 捕获 UnexpectedRollbackException 后返回成功

事务已经回滚。吞掉异常只会向上游谎报成功。应调整传播边界、异常处理或返回契约，并记录真正导致 rollback-only 的内层失败。

## 实验对照

`TransactionRollbackRuleTest` 固定四个结果：

| 场景 | 目标抛出 | 事件 |
| --- | --- | --- |
| 正常返回 | 无 | `begin → commit` |
| 默认运行时异常 | `IllegalStateException` | `begin → rollback` |
| 默认受检异常 | `CheckedBusinessException` | `begin → commit`，异常仍抛给调用者 |
| 显式 `rollbackFor` | 同一个受检异常 | `begin → rollback` |

`TransactionPropagationTest` 再固定 REQUIRED 参与者标记全局 rollback-only 与 NESTED 保存点局部回滚，避免把属性规则和物理完成动作混为一层。

## 变量快照

| 断点 | 观察值 | 需要回答的问题 |
| --- | --- | --- |
| `RuleBasedTransactionAttribute.rollbackOn` | `winner`、`deepest`、异常类 | 哪条规则最具体 |
| `completeTransactionAfterThrowing` | `txInfo`、`ex`、rollbackOn 结果 | 拦截器请求 rollback 还是 commit |
| `commit` | local/global rollback-only | commit 为什么被改写 |
| `processCommit` | `hasSavepoint`、`isNewTransaction` | 是释放保存点、物理提交还是仅参与 |
| `processRollback` | `unexpected`、`globalRollbackOnParticipationFailure` | 是否标记共享资源、是否抛意外回滚 |
| `cleanupAfterCompletion` | `completed`、`newSynchronization`、`suspendedResources` | 谁负责解绑与恢复 |

## 推荐断点顺序

1. 从 `completeTransactionAfterThrowing` 记录异常真实类型。
2. 进入 `rollbackOn`，确认规则匹配深度，而不是只看注解文本。
3. 在 manager 的 `commit/rollback` 入口观察 status 类型与 owner 身份。
4. 在 `processRollback` 区分保存点、新事务和参与者三个分支。
5. 对 unexpected rollback 追踪第一次 `doSetRollbackOnly`，不要只停在最外层异常处。
6. 在 synchronization 回调记录物理完成动作前后顺序。

## Spring 6.x 边界

Spring 6 的 rollback-only、保存点、同步回调和意外回滚模型保持连续，但 6.1 的异常 Future 处理、6.2 的全局回滚规则会改变一些应用的默认观察结果。升级时应把“异常类型 × 返回类型 × 方法级规则 × 全局规则”做成参数化测试。

## 公开契约与实现边界

异常回滚约定、`TransactionStatus.setRollbackOnly`、`TransactionSynchronization` 阶段和 `UnexpectedRollbackException` 是公开语义。`processCommit/processRollback` 的局部变量、内部 try/catch 排列与具体状态对象字段属于实现细节。

应用应断言业务可见结果和公开回调，不应通过反射修改 status 的内部 rollback 标志。
