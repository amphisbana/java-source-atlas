<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type TransactionPhase = 'interceptor' | 'requires-new' | 'nested' | 'completion'

interface TransactionSnapshot {
  phase: TransactionPhase
  request: string
  activeNodes: string[]
  activeEdges: string[]
  outerResource: string
  innerResource: string
  transactionStatus: string
  decision: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '代理命中事务 Advisor',
    method: 'TransactionAttributeSourcePointcut.matches(method, targetClass)',
    description: '调用先进入 Spring AOP 代理；切点只把能够解析出 TransactionAttribute 的方法交给 TransactionInterceptor。注解本身不会开启事务。'
  },
  {
    title: '解析并缓存事务属性',
    method: 'AnnotationTransactionAttributeSource.getTransactionAttribute(...)',
    description: '属性源按最具体实现方法、目标类等位置查找 @Transactional，把传播、隔离、只读、超时和回滚规则解析为 RuleBasedTransactionAttribute 并缓存。'
  },
  {
    title: '选择事务管理器',
    method: 'TransactionAspectSupport.determineTransactionManager(txAttr)',
    description: '先处理注解限定符或 transactionManager 名称，再使用默认 TransactionManager；多数据源应用在这里决定本次调用控制哪一种资源。'
  },
  {
    title: 'REQUIRED 开启外层事务',
    method: 'PlatformTransactionManager.getTransaction(REQUIRED)',
    description: '当前线程没有事务，AbstractPlatformTransactionManager 创建新 TransactionStatus，具体管理器 begin 并把资源绑定到 TransactionSynchronizationManager。'
  },
  {
    title: '进入目标业务方法',
    method: 'invocation.proceedWithInvocation()',
    description: 'TransactionInfo 已压入当前线程，代理才继续调用目标。后续跨 Bean 的代理调用可以看到同一线程上已经绑定的外层资源。'
  },
  {
    title: 'REQUIRES_NEW 挂起外层',
    method: 'handleExistingTransaction -> suspend(transaction)',
    description: '内层代理发现已有事务，先暂存外层资源与同步状态并解绑；挂起不是提交，也不是把外层事务复制到另一条线程。'
  },
  {
    title: '开始并提交独立内层事务',
    method: 'startTransaction -> doBegin -> doCommit',
    description: '同一线程绑定新的内层资源。它拥有独立物理事务和 TransactionStatus，正常返回后先提交内层，结果不依赖外层随后是否回滚。'
  },
  {
    title: '清理内层并恢复外层',
    method: 'cleanupAfterCompletion -> resume(transaction, suspended)',
    description: '模板在 finally 清理内层线程状态，再恢复先前挂起的资源和同步器；恢复后外层业务继续使用原事务。'
  },
  {
    title: 'NESTED 在外层创建保存点',
    method: 'getTransaction(NESTED) -> createAndHoldSavepoint()',
    description: '已有事务且管理器支持保存点时，不新建独立物理事务，而是在外层资源上创建保存点。没有外层事务时，NESTED 通常像 REQUIRED 一样开启新事务。'
  },
  {
    title: '嵌套失败只回滚保存点',
    method: 'processRollback -> rollbackToHeldSavepoint()',
    description: '内层异常符合回滚规则时回到保存点并释放保存点；外层捕获异常后仍可提交。NESTED 与 REQUIRES_NEW 的资源独立性不同。'
  },
  {
    title: '异常规则决定完成动作',
    method: 'completeTransactionAfterThrowing(txInfo, throwable)',
    description: 'RuntimeException 和 Error 默认回滚，受检异常默认提交；rollbackFor、noRollbackFor 按异常继承距离选择更具体的匹配规则。'
  },
  {
    title: 'rollback-only 改写提交',
    method: 'AbstractPlatformTransactionManager.commit(status)',
    description: 'commit 入口先检查 local/global rollback-only。REQUIRED 参与者已经标记共享资源时，外层虽然正常返回也会执行回滚，并可能抛 UnexpectedRollbackException。'
  },
  {
    title: '正常提交并清理线程状态',
    method: 'processCommit -> doCommit -> cleanupAfterCompletion',
    description: '提交模板依次触发同步回调、提交物理事务、通知 afterCommit/afterCompletion，最后标记状态完成、解绑资源并恢复可能存在的外层事务。'
  }
]

const snapshots: TransactionSnapshot[] = [
  {
    phase: 'interceptor',
    request: 'orderService.placeOrder()',
    activeNodes: ['caller', 'proxy'],
    activeEdges: ['call-proxy'],
    outerResource: '尚未读取',
    innerResource: '无',
    transactionStatus: '尚未创建',
    decision: '方法存在事务属性，进入 advice'
  },
  {
    phase: 'interceptor',
    request: '@Transactional(propagation = REQUIRED)',
    activeNodes: ['proxy', 'attribute'],
    activeEdges: ['proxy-attribute'],
    outerResource: '尚未读取',
    innerResource: '无',
    transactionStatus: 'RuleBasedTransactionAttribute',
    decision: '缓存 key = Method + targetClass'
  },
  {
    phase: 'interceptor',
    request: 'transactionManager = "orderTxManager"',
    activeNodes: ['attribute', 'interceptor', 'manager'],
    activeEdges: ['attribute-interceptor', 'interceptor-manager'],
    outerResource: '尚未读取',
    innerResource: '无',
    transactionStatus: 'manager = orderTxManager',
    decision: '确定 PlatformTransactionManager'
  },
  {
    phase: 'interceptor',
    request: 'getTransaction(PROPAGATION_REQUIRED)',
    activeNodes: ['interceptor', 'manager', 'resource'],
    activeEdges: ['interceptor-manager', 'manager-resource'],
    outerResource: 'tx-1 · ACTIVE · 当前线程已绑定',
    innerResource: '无',
    transactionStatus: 'newTransaction=true',
    decision: 'doBegin(tx-1)'
  },
  {
    phase: 'interceptor',
    request: 'invocation.proceed()',
    activeNodes: ['interceptor', 'business'],
    activeEdges: ['interceptor-business'],
    outerResource: 'tx-1 · ACTIVE · 当前线程已绑定',
    innerResource: '无',
    transactionStatus: 'TransactionInfo 保存旧 ThreadLocal',
    decision: '业务代码运行在 tx-1 中'
  },
  {
    phase: 'requires-new',
    request: 'auditService.write() · REQUIRES_NEW',
    activeNodes: ['business', 'proxy', 'manager', 'resource'],
    activeEdges: ['business-proxy', 'interceptor-manager', 'manager-resource'],
    outerResource: 'tx-1 · SUSPENDED · 已从线程解绑',
    innerResource: '准备开始',
    transactionStatus: 'suspendedResources = tx-1',
    decision: 'doSuspend(tx-1)'
  },
  {
    phase: 'requires-new',
    request: 'doBegin(tx-2) -> business -> doCommit(tx-2)',
    activeNodes: ['manager', 'business', 'resource'],
    activeEdges: ['manager-resource', 'interceptor-business'],
    outerResource: 'tx-1 · SUSPENDED',
    innerResource: 'tx-2 · COMMITTED · 独立资源',
    transactionStatus: 'newTransaction=true（内层）',
    decision: '内层提交不等待外层'
  },
  {
    phase: 'requires-new',
    request: 'cleanup(tx-2) -> resume(tx-1)',
    activeNodes: ['manager', 'resource', 'business'],
    activeEdges: ['manager-resource', 'interceptor-business'],
    outerResource: 'tx-1 · ACTIVE · 重新绑定',
    innerResource: 'tx-2 · 已清理',
    transactionStatus: '恢复外层同步状态',
    decision: '外层业务继续'
  },
  {
    phase: 'nested',
    request: 'stockService.reserve() · NESTED',
    activeNodes: ['business', 'proxy', 'manager', 'resource'],
    activeEdges: ['business-proxy', 'interceptor-manager', 'manager-resource'],
    outerResource: 'tx-1 · ACTIVE · savepoint=sp-1',
    innerResource: '与 tx-1 共用物理资源',
    transactionStatus: 'hasSavepoint=true',
    decision: 'createAndHoldSavepoint()'
  },
  {
    phase: 'nested',
    request: 'nested throws -> rollbackToSavepoint(sp-1)',
    activeNodes: ['business', 'interceptor', 'manager', 'resource'],
    activeEdges: ['interceptor-manager', 'manager-resource'],
    outerResource: 'tx-1 · ACTIVE · 已回到 sp-1',
    innerResource: '局部改动已撤销',
    transactionStatus: '保存点已回滚并释放',
    decision: '外层没有被标记 rollback-only'
  },
  {
    phase: 'completion',
    request: 'target throws CheckedBusinessException',
    activeNodes: ['business', 'interceptor', 'attribute'],
    activeEdges: ['attribute-interceptor', 'interceptor-business'],
    outerResource: 'tx-1 · ACTIVE',
    innerResource: '无',
    transactionStatus: 'rollbackOn(exception) = false',
    decision: '默认受检异常走 commit；rollbackFor 可改写'
  },
  {
    phase: 'completion',
    request: 'outer returns -> commit(status)',
    activeNodes: ['interceptor', 'manager', 'resource'],
    activeEdges: ['interceptor-manager', 'manager-resource'],
    outerResource: 'tx-1 · GLOBAL_ROLLBACK_ONLY',
    innerResource: 'REQUIRED 参与者已失败',
    transactionStatus: 'isGlobalRollbackOnly=true',
    decision: 'doRollback + UnexpectedRollbackException'
  },
  {
    phase: 'completion',
    request: 'beforeCommit -> doCommit -> afterCompletion',
    activeNodes: ['manager', 'resource', 'caller'],
    activeEdges: ['manager-resource', 'return-caller'],
    outerResource: 'tx-1 · COMMITTED · 已解绑',
    innerResource: '无',
    transactionStatus: 'completed=true',
    decision: '返回业务结果'
  }
]

/**
 * 返回与源码步骤严格对应的事务快照。
 *
 * @param index 当前步骤索引
 * @return 当前事务快照
 */
function snapshotAt(index: number): TransactionSnapshot {
  return snapshots[index]
}

/**
 * 判断流程节点是否需要高亮。
 *
 * @param snapshot 当前快照
 * @param node 节点名称
 * @return 当前节点活跃时返回 true
 */
function isNodeActive(snapshot: TransactionSnapshot, node: string): boolean {
  return snapshot.activeNodes.includes(node)
}

/**
 * 判断节点之间的调用边是否需要高亮。
 *
 * @param snapshot 当前快照
 * @param edge 边名称
 * @return 当前调用边活跃时返回 true
 */
function isEdgeActive(snapshot: TransactionSnapshot, edge: string): boolean {
  return snapshot.activeEdges.includes(edge)
}

/**
 * 返回当前阶段的中文标题。
 *
 * @param phase 动画阶段
 * @return 中文阶段标题
 */
function phaseLabel(phase: TransactionPhase): string {
  const labels: Record<TransactionPhase, string> = {
    interceptor: '事务拦截',
    'requires-new': 'REQUIRES_NEW',
    nested: 'NESTED',
    completion: '提交 / 回滚'
  }
  return labels[phase]
}
</script>

<template>
  <SourceAnimation title="Spring 5.3：事务拦截、传播与完成决策" :steps="steps" :interval="3000">
    <template #visual="{ currentIndex }">
      <div class="spring-transaction-demo">
        <header class="spring-transaction-demo__status">
          <strong :class="`is-${snapshotAt(currentIndex).phase}`">
            {{ phaseLabel(snapshotAt(currentIndex).phase) }}
          </strong>
          <code>{{ snapshotAt(currentIndex).request }}</code>
        </header>

        <section class="transaction-flow" aria-label="声明式事务调用链">
          <div
            class="transaction-node"
            :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'caller') }"
          >
            <span>调用入口</span>
            <strong>客户端</strong>
          </div>
          <div
            class="transaction-arrow"
            :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'call-proxy') || isEdgeActive(snapshotAt(currentIndex), 'return-caller') }"
          >
            <span>调用 / 返回</span><i>→</i>
          </div>
          <div
            class="transaction-node is-proxy"
            :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'proxy') }"
          >
            <span>Spring AOP</span>
            <strong>事务代理</strong>
          </div>
          <div
            class="transaction-arrow"
            :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'proxy-attribute') || isEdgeActive(snapshotAt(currentIndex), 'business-proxy') }"
          >
            <span>匹配属性</span><i>→</i>
          </div>
          <div
            class="transaction-node is-attribute"
            :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'attribute') }"
          >
            <span>元数据</span>
            <strong>TransactionAttribute</strong>
          </div>
        </section>

        <section class="transaction-flow is-second" aria-label="事务拦截器与资源调用链">
          <div
            class="transaction-node is-interceptor"
            :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'interceptor') }"
          >
            <span>MethodInterceptor</span>
            <strong>TransactionInterceptor</strong>
          </div>
          <div
            class="transaction-arrow"
            :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'attribute-interceptor') || isEdgeActive(snapshotAt(currentIndex), 'interceptor-manager') }"
          >
            <span>get / complete</span><i>→</i>
          </div>
          <div
            class="transaction-node is-manager"
            :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'manager') }"
          >
            <span>模板与策略</span>
            <strong>TransactionManager</strong>
          </div>
          <div
            class="transaction-arrow"
            :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'manager-resource') }"
          >
            <span>bind / commit</span><i>→</i>
          </div>
          <div
            class="transaction-node is-resource"
            :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'resource') }"
          >
            <span>线程资源</span>
            <strong>连接 / 会话</strong>
          </div>
          <div
            class="transaction-arrow"
            :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'interceptor-business') }"
          >
            <span>proceed</span><i>→</i>
          </div>
          <div
            class="transaction-node is-business"
            :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'business') }"
          >
            <span>目标对象</span>
            <strong>业务方法</strong>
          </div>
        </section>

        <div class="transaction-resources" aria-label="事务资源状态">
          <div>
            <span>外层资源</span>
            <strong>{{ snapshotAt(currentIndex).outerResource }}</strong>
          </div>
          <div>
            <span>内层 / 保存点</span>
            <strong>{{ snapshotAt(currentIndex).innerResource }}</strong>
          </div>
        </div>

        <footer class="spring-transaction-demo__result">
          <div>
            <span>TransactionStatus</span>
            <code>{{ snapshotAt(currentIndex).transactionStatus }}</code>
          </div>
          <div>
            <span>本步决策</span>
            <strong>{{ snapshotAt(currentIndex).decision }}</strong>
          </div>
        </footer>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.spring-transaction-demo {
  display: grid;
  gap: 14px;
  min-width: 0;
  min-height: 420px;
}

.spring-transaction-demo__status {
  display: grid;
  grid-template-columns: minmax(130px, 0.4fr) minmax(0, 1.6fr);
  gap: 12px;
  align-items: center;
  min-width: 0;
}

.spring-transaction-demo__status strong {
  padding-left: 9px;
  border-left: 3px solid var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
  font-size: 0.78rem;
}

.spring-transaction-demo__status strong.is-requires-new {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.spring-transaction-demo__status strong.is-nested {
  border-color: var(--vp-c-warning-1);
  color: var(--vp-c-warning-1);
}

.spring-transaction-demo__status strong.is-completion {
  border-color: var(--vp-c-brand-2);
  color: var(--vp-c-brand-2);
}

.spring-transaction-demo__status code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
}

.transaction-flow {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.62fr) minmax(0, 1fr) minmax(0, 0.62fr) minmax(0, 1.35fr);
  gap: 8px;
  align-items: stretch;
  min-width: 0;
}

.transaction-flow.is-second {
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 0.55fr) minmax(0, 1.2fr) minmax(0, 0.55fr) minmax(0, 1fr) minmax(0, 0.48fr) minmax(0, 0.9fr);
}

.transaction-node {
  display: grid;
  gap: 5px;
  align-content: center;
  min-width: 0;
  min-height: 66px;
  padding: 9px 10px;
  border: 1px solid var(--atlas-line);
  border-left: 3px solid var(--vp-c-text-3);
  background: var(--vp-c-bg);
  transition: border-color 180ms ease, background 180ms ease, transform 180ms ease;
}

.transaction-node span {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.transaction-node strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-size: 0.76rem;
}

.transaction-node.is-active {
  border-color: var(--vp-c-brand-1);
  border-left-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  transform: translateY(-2px);
}

.transaction-node.is-proxy.is-active,
.transaction-node.is-interceptor.is-active {
  border-color: var(--atlas-coral);
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
}

.transaction-node.is-manager.is-active,
.transaction-node.is-resource.is-active {
  border-color: var(--vp-c-brand-2);
  border-left-color: var(--vp-c-brand-2);
  background: color-mix(in srgb, var(--vp-c-brand-2) 9%, transparent);
}

.transaction-node.is-business.is-active {
  border-color: var(--vp-c-warning-1);
  border-left-color: var(--vp-c-warning-1);
  background: color-mix(in srgb, var(--vp-c-warning-1) 10%, transparent);
}

.transaction-arrow {
  display: grid;
  grid-template-rows: 1fr auto;
  place-items: center;
  min-width: 0;
  color: var(--vp-c-text-3);
  text-align: center;
}

.transaction-arrow span {
  align-self: end;
  overflow-wrap: anywhere;
  font-family: var(--vp-font-family-mono);
  font-size: 0.6rem;
}

.transaction-arrow i {
  color: var(--atlas-line-strong);
  font-size: 1.25rem;
  font-style: normal;
  line-height: 1;
}

.transaction-arrow.is-active span,
.transaction-arrow.is-active i {
  color: var(--vp-c-brand-1);
}

.transaction-resources {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.transaction-resources > div {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px 12px;
}

.transaction-resources > div + div {
  border-left: 1px solid var(--atlas-line);
}

.transaction-resources span,
.spring-transaction-demo__result span {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.transaction-resources strong,
.spring-transaction-demo__result strong,
.spring-transaction-demo__result code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.76rem;
}

.spring-transaction-demo__result {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
}

.spring-transaction-demo__result > div {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px 12px;
}

@media (max-width: 860px) {
  .transaction-flow,
  .transaction-flow.is-second {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .transaction-arrow {
    min-height: 42px;
  }
}

@media (max-width: 560px) {
  .spring-transaction-demo__status,
  .transaction-flow,
  .transaction-flow.is-second,
  .transaction-resources,
  .spring-transaction-demo__result {
    grid-template-columns: 1fr;
  }

  .transaction-arrow {
    grid-template-columns: 1fr auto;
    grid-template-rows: 1fr;
    box-sizing: border-box;
    min-height: 28px;
    padding-right: 4px;
  }

  .transaction-arrow span {
    align-self: center;
  }

  .transaction-arrow i {
    transform: rotate(90deg);
  }

  .transaction-resources > div + div {
    border-top: 1px solid var(--atlas-line);
    border-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .transaction-node {
    transition: none;
  }
}
</style>
