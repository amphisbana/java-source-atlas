<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type AopPhase = 'creation' | 'invocation' | 'self'

interface AopSnapshot {
  phase: AopPhase
  request: string
  activeNodes: string[]
  proxyType: string
  chain: string
  outcome: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '注册自动代理创建器',
    method: 'AopConfigUtils.registerAutoProxyCreatorIfNecessary(...)',
    description: '@EnableAspectJAutoProxy 等入口把一个 AbstractAutoProxyCreator 子类注册为基础设施 BeanPostProcessor；此时还没有为业务 Bean 创建代理。'
  },
  {
    title: '完成目标对象初始化',
    method: 'AbstractAutowireCapableBeanFactory.initializeBean(...)',
    description: 'IOC 已经实例化、注入并执行目标对象的初始化回调；当前 bean 仍是保存业务状态的原始 target。'
  },
  {
    title: '进入初始化后处理',
    method: 'AbstractAutoProxyCreator.postProcessAfterInitialization(bean, beanName)',
    description: '自动代理创建器在常规包装点取得 bean，并先排除已经提供过一致早期代理引用的情况。'
  },
  {
    title: '执行代理必要性判断',
    method: 'wrapIfNecessary(bean, beanName, cacheKey)',
    description: '依次检查自定义 TargetSource、历史判断、AOP 基础设施类和 shouldSkip；只有普通候选 Bean 才继续寻找 Advisor。'
  },
  {
    title: '筛选并排序 Advisor',
    method: 'findEligibleAdvisors(beanClass, beanName)',
    description: '从候选 Advisor 中按 ClassFilter 和 MethodMatcher 找出至少对一个方法适用的项，再扩展并排序。'
  },
  {
    title: '组装 ProxyFactory',
    method: 'createProxy(...) -> ProxyFactory',
    description: '复制 exposeProxy/frozen 等配置，评估业务接口，加入 Advisor 与 TargetSource，形成可创建代理的 AdvisedSupport。'
  },
  {
    title: '选择代理实现',
    method: 'DefaultAopProxyFactory.createAopProxy(config)',
    description: '默认有合理业务接口时选 JDK；强制类代理或没有业务接口时通常选 ObjenesisCglibAopProxy，并受目标类型边界约束。'
  },
  {
    title: '外部调用进入代理',
    method: 'JdkDynamicAopProxy.invoke / DynamicAdvisedInterceptor.intercept',
    description: '客户端持有容器暴露的 proxy，因此外部业务调用先进入 JDK InvocationHandler 或 CGLIB callback。'
  },
  {
    title: '取得 target 与方法链',
    method: 'targetSource.getTarget() + getInterceptorsAndDynamicInterceptionAdvice(...)',
    description: '代理按本次 method 和 targetClass 取得已排序拦截器链；空链可直接调用目标，非空链创建 MethodInvocation。'
  },
  {
    title: '适配 Advice 与动态匹配',
    method: 'DefaultAdvisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice(...)',
    description: 'Before、AfterReturning、Throws 等 Advice 被适配为 MethodInterceptor；运行时 Matcher 与拦截器一起留在链中。'
  },
  {
    title: '进入外层拦截器',
    method: 'ReflectiveMethodInvocation.proceed() -> outer.invoke(this)',
    description: '链游标从 -1 前进到第一项；outer 先执行 before，再用同一个 invocation 调用 proceed 进入下一层。'
  },
  {
    title: '进入内层拦截器',
    method: 'proceed() -> inner.invoke(this)',
    description: '游标继续前进；inner 的 before 位于 outer 之内，随后再次 proceed，直到链中没有下一项。'
  },
  {
    title: '调用目标并逆序返回',
    method: 'invokeJoinpoint() -> target.method()',
    description: '目标执行后 Java 调用栈回卷：先 inner after，再 outer after；代理最后处理返回 this、原始类型 null 与 target 释放。'
  },
  {
    title: 'this 自调用绕过代理',
    method: 'target.outer() -> this.inner()',
    description: 'outer 已位于真实 target 中，this.inner 没有回到 proxy，所以 inner 对应 Pointcut 不会产生新的 MethodInvocation。'
  },
  {
    title: '显式通过当前代理重入',
    method: 'AopContext.currentProxy().inner()',
    description: '仅在 exposeProxy=true 且当前线程仍处于代理调用栈时可取到 proxy；重入会重新匹配 inner，但引入 ThreadLocal 与 Spring API 耦合。'
  }
]

const snapshots: AopSnapshot[] = [
  {
    phase: 'creation',
    request: '配置阶段：注册 BeanPostProcessor',
    activeNodes: ['aapc'],
    proxyType: '尚未决定',
    chain: '尚未查找',
    outcome: '容器拥有自动代理能力'
  },
  {
    phase: 'creation',
    request: 'beanName = atlasService',
    activeNodes: ['bean'],
    proxyType: '原始 AtlasServiceImpl',
    chain: '尚未查找',
    outcome: 'target 初始化完成'
  },
  {
    phase: 'creation',
    request: 'postProcessAfterInitialization(target)',
    activeNodes: ['bean', 'aapc'],
    proxyType: '等待包装',
    chain: '检查 earlyProxyReferences',
    outcome: '进入常规自动代理路径'
  },
  {
    phase: 'creation',
    request: 'wrapIfNecessary(target, "atlasService", cacheKey)',
    activeNodes: ['aapc'],
    proxyType: '候选业务 Bean',
    chain: '尚未筛选',
    outcome: '未命中 skip / DO_NOT_PROXY 缓存'
  },
  {
    phase: 'creation',
    request: 'ClassFilter + MethodMatcher',
    activeNodes: ['aapc', 'advisors'],
    proxyType: '确定需要代理',
    chain: '[outerAdvisor, innerAdvisor]',
    outcome: 'eligibleAdvisors 已排序'
  },
  {
    phase: 'creation',
    request: 'ProxyFactory + TargetSource + advisors',
    activeNodes: ['advisors', 'factory'],
    proxyType: 'interfaces=[AtlasService]',
    chain: 'Advisor 配置写入 AdvisedSupport',
    outcome: '代理配置就绪'
  },
  {
    phase: 'creation',
    request: 'proxyTargetClass=false，存在业务接口',
    activeNodes: ['factory', 'aopproxy'],
    proxyType: 'JdkDynamicAopProxy',
    chain: '运行时按 Method 取得',
    outcome: '容器暴露 Proxy，target 保留业务状态'
  },
  {
    phase: 'invocation',
    request: 'client.greet("atlas")',
    activeNodes: ['client', 'proxy'],
    proxyType: 'JDK / CGLIB 共享后续主干',
    chain: '等待组装',
    outcome: '代理获得本次调用控制权'
  },
  {
    phase: 'invocation',
    request: 'method=greet, targetClass=AtlasServiceImpl',
    activeNodes: ['proxy', 'chain'],
    proxyType: '当前代理保持不变',
    chain: '[outer, inner]',
    outcome: '创建 ReflectiveMethodInvocation'
  },
  {
    phase: 'invocation',
    request: 'Advisor -> MethodInterceptor',
    activeNodes: ['chain'],
    proxyType: '当前代理保持不变',
    chain: '[outer, dynamic(inner)]',
    outcome: '静态命中；动态项执行时再看 args'
  },
  {
    phase: 'invocation',
    request: 'currentInterceptorIndex: -1 -> 0',
    activeNodes: ['chain', 'invocation'],
    proxyType: '当前代理保持不变',
    chain: 'outer before -> proceed',
    outcome: '外层通知留在 Java 调用栈'
  },
  {
    phase: 'invocation',
    request: 'currentInterceptorIndex: 0 -> 1',
    activeNodes: ['chain', 'invocation'],
    proxyType: '当前代理保持不变',
    chain: 'outer before -> inner before -> proceed',
    outcome: '内层通知继续推进同一游标'
  },
  {
    phase: 'invocation',
    request: 'index == chain.size - 1',
    activeNodes: ['invocation', 'target', 'result'],
    proxyType: '代理处理最终返回值',
    chain: 'target -> inner after -> outer after',
    outcome: '结果返回客户端，TargetSource 被释放'
  },
  {
    phase: 'self',
    request: 'target.outerDirect() 中执行 this.inner()',
    activeNodes: ['outer', 'this-call', 'inner'],
    proxyType: 'proxy 没有再次被调用',
    chain: '只有 outer 的原调用链',
    outcome: 'inner target 执行，inner advice 不执行'
  },
  {
    phase: 'self',
    request: 'target.outerViaCurrentProxy() 中执行 proxy.inner()',
    activeNodes: ['outer', 'current-proxy', 'reentry', 'inner'],
    proxyType: 'ThreadLocal 暂存的当前 proxy',
    chain: 'outer chain 包含新的 inner chain',
    outcome: 'inner advice 与 inner target 都执行'
  }
]

/**
 * 返回当前动画步骤对应的完整状态快照。
 *
 * @param index 当前步骤索引
 * @return AOP 创建、调用或自调用快照
 */
function snapshotAt(index: number): AopSnapshot {
  return snapshots[index]
}

/**
 * 判断指定流程节点是否需要高亮。
 *
 * @param snapshot 当前状态快照
 * @param node 节点标识
 * @return 节点位于当前源码步骤时返回 true
 */
function isNodeActive(snapshot: AopSnapshot, node: string): boolean {
  return snapshot.activeNodes.includes(node)
}

/**
 * 返回当前阶段的中文标签。
 *
 * @param phase 动画阶段
 * @return 阶段名称
 */
function phaseLabel(phase: AopPhase): string {
  if (phase === 'creation') {
    return 'Bean 创建期'
  }
  if (phase === 'invocation') {
    return '每次方法调用'
  }
  return '自调用边界'
}
</script>

<template>
  <SourceAnimation title="Spring AOP：代理创建、拦截器链与自调用" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="spring-aop-demo">
        <div class="spring-aop-demo__status">
          <strong :class="`is-${snapshotAt(currentIndex).phase}`">
            {{ phaseLabel(snapshotAt(currentIndex).phase) }}
          </strong>
          <code>{{ snapshotAt(currentIndex).request }}</code>
        </div>

        <section
          class="aop-lane"
          :class="{ 'is-muted': snapshotAt(currentIndex).phase !== 'creation' }"
          aria-label="代理创建流程"
        >
          <div class="aop-lane__heading">
            <strong>创建期</strong>
            <span>一次 Bean 生命周期内决定是否包装</span>
          </div>
          <div class="aop-flow is-five">
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'bean') }">
              <span>原始对象</span><strong>target Bean</strong><small>保存业务状态</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'aapc') }">
              <span>BeanPostProcessor</span><strong>AutoProxyCreator</strong><small>wrapIfNecessary</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'advisors') }">
              <span>适用性与顺序</span><strong>eligible Advisors</strong><small>Pointcut 匹配</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'factory') }">
              <span>代理配置</span><strong>ProxyFactory</strong><small>接口 + TargetSource</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'aopproxy') }">
              <span>策略结果</span><strong>JDK / CGLIB</strong><small>容器暴露 proxy</small>
            </div>
          </div>
        </section>

        <section
          class="aop-lane"
          :class="{ 'is-muted': snapshotAt(currentIndex).phase !== 'invocation' }"
          aria-label="拦截器链调用流程"
        >
          <div class="aop-lane__heading">
            <strong>调用期</strong>
            <span>每次外部调用创建独立 MethodInvocation</span>
          </div>
          <div class="aop-flow is-six">
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'client') }">
              <span>外部入口</span><strong>client</strong><small>持有代理</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'proxy') }">
              <span>调用分派</span><strong>proxy</strong><small>invoke / intercept</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'chain') }">
              <span>本次匹配</span><strong>chain</strong><small>Advisor → Interceptor</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'invocation') }">
              <span>链游标</span><strong>proceed()</strong><small>index 递增</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'target') }">
              <span>连接点</span><strong>target.method</strong><small>真实业务</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'result') }">
              <span>栈回卷</span><strong>result</strong><small>inner → outer</small>
            </div>
          </div>
        </section>

        <section
          class="aop-lane"
          :class="{ 'is-muted': snapshotAt(currentIndex).phase !== 'self' }"
          aria-label="自调用边界"
        >
          <div class="aop-lane__heading">
            <strong>自调用</strong>
            <span>关键不是 JDK/CGLIB，而是调用是否重新经过 proxy</span>
          </div>
          <div class="self-flow">
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'outer') }">
              <span>真实目标</span><strong>target.outer()</strong><small>this = target</small>
            </div>
            <div class="self-branch">
              <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'this-call') }">
                <span>直接路径</span><strong>this.inner()</strong><small>绕过 proxy</small>
              </div>
              <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'current-proxy') }">
                <span>显式路径</span><strong>currentProxy()</strong><small>exposeProxy=true</small>
              </div>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'reentry') }">
              <span>重新进入</span><strong>proxy.inner()</strong><small>新 MethodInvocation</small>
            </div>
            <div class="aop-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'inner') }">
              <span>最终目标</span><strong>target.inner()</strong><small>业务总会执行</small>
            </div>
          </div>
        </section>

        <div class="spring-aop-demo__snapshot" aria-label="当前代理状态">
          <div><span>Proxy</span><strong>{{ snapshotAt(currentIndex).proxyType }}</strong></div>
          <div><span>Interceptor chain</span><strong>{{ snapshotAt(currentIndex).chain }}</strong></div>
          <div><span>Outcome</span><strong>{{ snapshotAt(currentIndex).outcome }}</strong></div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.spring-aop-demo {
  display: grid;
  gap: 12px;
}

.spring-aop-demo__status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--atlas-line);
}

.spring-aop-demo__status strong {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-left: 3px solid var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
  font-size: 0.76rem;
}

.spring-aop-demo__status strong.is-invocation {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 10%, transparent);
  color: var(--atlas-coral);
}

.spring-aop-demo__status strong.is-self {
  border-left-color: var(--atlas-gold);
  background: color-mix(in srgb, var(--atlas-gold) 12%, transparent);
  color: var(--atlas-ink);
}

.spring-aop-demo__status code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-size: 0.75rem;
}

.aop-lane {
  padding: 10px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: opacity 180ms ease, border-color 180ms ease;
}

.aop-lane.is-muted {
  opacity: 0.42;
}

.aop-lane:not(.is-muted) {
  border-color: color-mix(in srgb, var(--vp-c-brand-1) 55%, var(--atlas-line));
}

.aop-lane__heading {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
  margin-bottom: 9px;
}

.aop-lane__heading strong {
  color: var(--atlas-ink);
  font-size: 0.79rem;
}

.aop-lane__heading span {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.aop-flow {
  display: grid;
  gap: 12px;
}

.aop-flow.is-five,
.aop-flow.is-six {
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
}

.aop-node {
  position: relative;
  display: grid;
  gap: 2px;
  align-content: center;
  min-width: 0;
  min-height: 58px;
  padding: 7px 8px;
  border-left: 3px solid var(--atlas-line);
  background: var(--vp-c-bg);
  transition: transform 180ms ease, border-color 180ms ease, background 180ms ease;
}

.aop-node span,
.aop-node small {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-size: 0.64rem;
  line-height: 1.3;
}

.aop-node strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-size: 0.71rem;
  line-height: 1.35;
}

.aop-node.is-active {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, var(--vp-c-bg));
  transform: translateY(-2px);
}

.aop-node.is-active strong {
  color: var(--atlas-ink);
}

.self-flow {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  align-items: stretch;
}

.self-branch {
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
}

.spring-aop-demo__snapshot {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}

.spring-aop-demo__snapshot > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 9px 10px;
}

.spring-aop-demo__snapshot > div + div {
  border-left: 1px solid var(--atlas-line);
}

.spring-aop-demo__snapshot span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
}

.spring-aop-demo__snapshot strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.72rem;
  line-height: 1.45;
}

@media (max-width: 760px) {
  .aop-flow.is-five,
  .aop-flow.is-six,
  .self-flow {
    grid-template-columns: 1fr;
  }

  .spring-aop-demo__snapshot {
    grid-template-columns: 1fr;
  }

  .spring-aop-demo__snapshot > div + div {
    border-top: 1px solid var(--atlas-line);
    border-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .aop-lane,
  .aop-node {
    transition: none;
  }
}
</style>
