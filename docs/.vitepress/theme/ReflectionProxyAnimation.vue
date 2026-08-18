<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type AnimationPhase = 'reflection' | 'proxy'

interface ReflectionProxySnapshot {
  phase: AnimationPhase
  request: string
  activeNodes: string[]
  activeEdges: string[]
  accessor: string
  output: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '查找 Method 元数据',
    method: 'Class.getDeclaredMethod(name, parameterTypes)',
    description: 'Class 从声明方法数据中按名称和精确参数类型查找，再返回可独立设置 accessible 标记的 Method 副本；底层 root 元数据可被缓存和共享。'
  },
  {
    title: '执行访问与参数检查',
    method: 'Method.invoke(target, args)',
    description: '未设置 override 时先按调用者、声明类、接收者和修饰符检查访问；随后校验接收者、参数数量，并执行允许的拆箱与基本类型拓宽。'
  },
  {
    title: '取得共享 MethodAccessor',
    method: 'acquireMethodAccessor()',
    description: 'Method 先尝试复用 root 上已有的 MethodAccessor；尚未创建时才交给 ReflectionFactory 构造，并把结果传播回 root。'
  },
  {
    title: '前几次走 native 入口',
    method: 'DelegatingMethodAccessorImpl -> NativeMethodAccessorImpl.invoke0',
    description: 'OpenJDK 8 默认先用 JVM native 入口，避免只调用一两次的方法立即承担生成和加载 accessor 字节码的启动成本。'
  },
  {
    title: '超过阈值切换 delegate',
    method: 'numInvocations > 15 -> MethodAccessorGenerator',
    description: '默认第 16 次进入时生成字节码 accessor，并把 DelegatingMethodAccessorImpl 的 delegate 换掉；本次仍执行 invoke0，后续调用才走生成 accessor。'
  },
  {
    title: '调用目标并规范化结果',
    method: 'MethodAccessor.invoke(target, args)',
    description: '目标正常返回时，原始类型被装箱、void 映射为 null；目标抛出的 Throwable 被放进 InvocationTargetException，访问或参数错误则不属于目标异常。'
  },
  {
    title: '按有序接口列表查缓存',
    method: 'Proxy.newProxyInstance(loader, interfaces, handler)',
    description: 'JDK 8 克隆接口数组，以 class loader 和有顺序的接口身份列表进入 WeakCache；相同组合复用代理 Class，接口顺序改变会命中不同键。'
  },
  {
    title: '校验接口与可见性',
    method: 'ProxyClassFactory.apply(loader, interfaces)',
    description: '逐个确认 loader 按名称解析到同一 Class、元素确实是接口且不重复；所有非 public 接口还必须处于同一运行时包。'
  },
  {
    title: '生成并定义代理类',
    method: 'ProxyGenerator.generateProxyClass -> defineClass0',
    description: '生成器先按 hashCode、equals、toString 收集 Object 方法，再按接口顺序收集方法，生成构造器、Method 静态字段和分派方法，最后由指定 loader 定义 Class。'
  },
  {
    title: '构造代理实例并保存 h',
    method: '$ProxyN(InvocationHandler) -> Proxy(h)',
    description: 'newProxyInstance 反射调用代理类唯一的 InvocationHandler 构造器；父类 Proxy 只保存非 null 的 h，真实业务目标是否存在由处理器自己决定。'
  },
  {
    title: '代理方法编码本次调用',
    method: '$ProxyN.welcome -> h.invoke(proxy, m, args)',
    description: '生成方法读取 h 和静态 Method 字段，把原始参数装箱进 Object[]，再调用处理器。Object 三个常用方法和接口 default 方法也会先进入同一分派口。'
  },
  {
    title: '转换结果并执行异常契约',
    method: 'checkcast / unbox / UndeclaredThrowableException',
    description: '生成方法按接口返回类型强转或拆箱 handler 结果；RuntimeException、Error 和兼容的已声明异常直接传播，其余受检异常包装为 UndeclaredThrowableException。'
  }
]

const snapshots: ReflectionProxySnapshot[] = [
  {
    phase: 'reflection',
    request: 'getDeclaredMethod("welcome", String.class, long.class)',
    activeNodes: ['caller', 'method'],
    activeEdges: ['r-lookup'],
    accessor: '尚未创建',
    output: 'Method{welcome(String,long)}'
  },
  {
    phase: 'reflection',
    request: 'method.invoke(service, "atlas", Integer.valueOf(2))',
    activeNodes: ['caller', 'method', 'access'],
    activeEdges: ['r-invoke', 'r-check'],
    accessor: '尚未取得',
    output: 'Integer 拆箱后拓宽为 long'
  },
  {
    phase: 'reflection',
    request: 'methodAccessor == null',
    activeNodes: ['method', 'accessor'],
    activeEdges: ['r-accessor'],
    accessor: 'root 复用或 ReflectionFactory 新建',
    output: 'MethodAccessor 已缓存'
  },
  {
    phase: 'reflection',
    request: '第 1..15 次 invoke（默认配置）',
    activeNodes: ['accessor', 'native', 'target'],
    activeEdges: ['r-native'],
    accessor: 'Delegating -> Native -> invoke0',
    output: '目标方法由 JVM 入口执行'
  },
  {
    phase: 'reflection',
    request: '第 16 次进入 NativeMethodAccessorImpl',
    activeNodes: ['accessor', 'native', 'generated', 'target'],
    activeEdges: ['r-inflate', 'r-generated-target'],
    accessor: 'delegate: Native -> Generated',
    output: '当前次 invoke0；后续走 Generated'
  },
  {
    phase: 'reflection',
    request: 'target.welcome(...) / target throws IOException',
    activeNodes: ['target', 'result'],
    activeEdges: ['r-return'],
    accessor: 'Native 或 Generated',
    output: 'String / InvocationTargetException(cause)'
  },
  {
    phase: 'proxy',
    request: 'newProxyInstance(loader, [Greeting, Audit], h)',
    activeNodes: ['client', 'cache'],
    activeEdges: ['p-cache'],
    accessor: 'cache key = loader + [Greeting, Audit]',
    output: '缓存命中返回已有 Class；否则继续生成'
  },
  {
    phase: 'proxy',
    request: 'Class.forName(intf.name, false, loader) == intf',
    activeNodes: ['cache', 'factory'],
    activeEdges: ['p-validate'],
    accessor: '接口身份、重复项、非 public 包约束',
    output: '合法接口列表'
  },
  {
    phase: 'proxy',
    request: 'hashCode -> equals -> toString -> 接口方法',
    activeNodes: ['factory', 'proxyclass'],
    activeEdges: ['p-generate'],
    accessor: 'ProxyGenerator + defineClass0',
    output: 'class com.sun.proxy.$ProxyN extends Proxy'
  },
  {
    phase: 'proxy',
    request: 'constructor.newInstance(new Object[]{h})',
    activeNodes: ['proxyclass', 'handler'],
    activeEdges: ['p-construct'],
    accessor: 'Proxy.h = handler',
    output: '可强转为 Greeting 与 Audit 的实例'
  },
  {
    phase: 'proxy',
    request: 'proxy.welcome("atlas", 2)',
    activeNodes: ['client', 'proxyclass', 'handler', 'realtarget'],
    activeEdges: ['p-call', 'p-dispatch', 'p-delegate'],
    accessor: 'h.invoke(proxy, Method, Object[]{"atlas", 2L})',
    output: 'handler 可记录、拦截或转发'
  },
  {
    phase: 'proxy',
    request: 'handler 返回 Object 或抛 Throwable',
    activeNodes: ['handler', 'proxyclass', 'client'],
    activeEdges: ['p-handler-return', 'p-client-return'],
    accessor: '生成方法负责 cast / unbox / catch',
    output: '接口返回值或 UndeclaredThrowableException'
  }
]

/**
 * 返回与当前步骤严格对应的动画快照。
 *
 * @param index 当前步骤索引
 * @return 反射或代理阶段快照
 */
function snapshotAt(index: number): ReflectionProxySnapshot {
  return snapshots[index]
}

/**
 * 判断流程节点是否处于当前源码步骤。
 *
 * @param snapshot 当前快照
 * @param node 节点标识
 * @return 当前节点需要高亮时返回 true
 */
function isNodeActive(snapshot: ReflectionProxySnapshot, node: string): boolean {
  return snapshot.activeNodes.includes(node)
}

/**
 * 判断节点之间的调用边是否处于当前源码步骤。
 *
 * @param snapshot 当前快照
 * @param edge 调用边标识
 * @return 当前调用边需要高亮时返回 true
 */
function isEdgeActive(snapshot: ReflectionProxySnapshot, edge: string): boolean {
  return snapshot.activeEdges.includes(edge)
}
</script>

<template>
  <SourceAnimation title="OpenJDK 8：Method.invoke 与动态代理完整调用链" :steps="steps" :interval="2800">
    <template #visual="{ currentIndex }">
      <div class="reflection-proxy-demo">
        <div class="reflection-proxy-demo__status">
          <strong :class="`is-${snapshotAt(currentIndex).phase}`">
            {{ snapshotAt(currentIndex).phase === 'reflection' ? 'Reflection 调用' : 'JDK Dynamic Proxy' }}
          </strong>
          <code>{{ snapshotAt(currentIndex).request }}</code>
        </div>

        <section
          class="flow-section is-reflection"
          :class="{ 'is-muted': snapshotAt(currentIndex).phase !== 'reflection' }"
          aria-label="Method.invoke 调用链"
        >
          <div class="flow-section__title">
            <span>Reflection</span>
            <small>Method 是元数据入口，MethodAccessor 是 JDK 8 的执行策略</small>
          </div>

          <div class="flow-grid reflection-flow">
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'caller') }">
              <span>调用者</span>
              <strong>业务代码</strong>
            </div>
            <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'r-lookup') || isEdgeActive(snapshotAt(currentIndex), 'r-invoke') }">
              <span>查找 / invoke</span><i>→</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'method') }">
              <span>java.lang.reflect</span>
              <strong>Method</strong>
            </div>
            <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'r-check') || isEdgeActive(snapshotAt(currentIndex), 'r-accessor') }">
              <span>check / acquire</span><i>→</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'access') || isNodeActive(snapshotAt(currentIndex), 'accessor') }">
              <span>{{ isNodeActive(snapshotAt(currentIndex), 'access') ? '访问检查' : '执行入口' }}</span>
              <strong v-if="isNodeActive(snapshotAt(currentIndex), 'access')">check<wbr>Access</strong>
              <strong v-else>Method<wbr>Accessor</strong>
            </div>
            <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'r-native') || isEdgeActive(snapshotAt(currentIndex), 'r-generated-target') }">
              <span>accessor.invoke</span><i>→</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'target') }">
              <span>接收者</span>
              <strong>真实目标方法</strong>
            </div>
            <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'r-native') || isEdgeActive(snapshotAt(currentIndex), 'r-return') }">
              <span>调用 / 返回</span><i>→</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'result') }">
              <span>公开外观</span>
              <strong>Object / 包装异常</strong>
            </div>
          </div>

          <div class="accessor-routes" aria-label="JDK 8 MethodAccessor 策略切换路径">
            <div class="accessor-route">
              <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'native') && isEdgeActive(snapshotAt(currentIndex), 'r-native') }">
                <span>第 1..16 次入口</span>
                <strong>Native<wbr>Accessor</strong>
              </div>
              <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'r-native') }">
                <span>invoke0</span><i>→</i>
              </div>
              <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'target') && isEdgeActive(snapshotAt(currentIndex), 'r-native') }">
                <span>native 路径</span>
                <strong>目标方法</strong>
              </div>
            </div>
            <div class="accessor-route is-generated-route">
              <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'native') && isEdgeActive(snapshotAt(currentIndex), 'r-inflate') }">
                <span>阈值判断</span>
                <strong>Native<wbr>Accessor</strong>
              </div>
              <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'r-inflate') }">
                <span>setDelegate</span><i>→</i>
              </div>
              <div class="flow-node is-generated" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'generated') }">
                <span>第 17 次起</span>
                <strong>Generated<wbr>Accessor</strong>
              </div>
              <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'r-generated-target') }">
                <span>invoke</span><i>→</i>
              </div>
              <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'target') && isEdgeActive(snapshotAt(currentIndex), 'r-generated-target') }">
                <span>字节码路径</span>
                <strong>目标方法</strong>
              </div>
            </div>
          </div>
        </section>

        <section
          class="flow-section is-proxy"
          :class="{ 'is-muted': snapshotAt(currentIndex).phase !== 'proxy' }"
          aria-label="JDK 动态代理生成和调用链"
        >
          <div class="flow-section__title">
            <span>Dynamic Proxy</span>
            <small>先生成并缓存代理 Class，实例调用再经过生成方法进入 handler</small>
          </div>

          <div class="flow-grid proxy-flow">
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'client') }">
              <span>调用者</span>
              <strong>接口引用</strong>
            </div>
            <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'p-cache') || isEdgeActive(snapshotAt(currentIndex), 'p-call') }">
              <span>创建 / 调用</span><i>→</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'cache') || isNodeActive(snapshotAt(currentIndex), 'proxyclass') }">
              <span>{{ isNodeActive(snapshotAt(currentIndex), 'cache') ? 'WeakCache' : '生成类实例' }}</span>
              <strong>{{ isNodeActive(snapshotAt(currentIndex), 'cache') ? '代理 Class 缓存' : '$ProxyN' }}</strong>
            </div>
            <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'p-validate') || isEdgeActive(snapshotAt(currentIndex), 'p-generate') || isEdgeActive(snapshotAt(currentIndex), 'p-dispatch') || isEdgeActive(snapshotAt(currentIndex), 'p-construct') }">
              <span>生成 / dispatch</span><i>→</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'factory') || isNodeActive(snapshotAt(currentIndex), 'handler') }">
              <span>{{ isNodeActive(snapshotAt(currentIndex), 'factory') ? '类生成阶段' : '实例策略' }}</span>
              <strong v-if="isNodeActive(snapshotAt(currentIndex), 'factory')">ProxyClass<wbr>Factory</strong>
              <strong v-else>Invocation<wbr>Handler</strong>
            </div>
            <div class="flow-arrow" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'p-delegate') }">
              <span>可选转发</span><i>→</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'realtarget') }">
              <span>可选</span>
              <strong>真实目标对象</strong>
            </div>
          </div>

          <div class="flow-grid proxy-return-flow" aria-label="InvocationHandler 返回到调用方的路径">
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'client') && isEdgeActive(snapshotAt(currentIndex), 'p-client-return') }">
              <span>最终接收</span>
              <strong>接口调用方</strong>
            </div>
            <div class="flow-arrow is-reverse" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'p-client-return') }">
              <span>接口结果 / 异常</span><i>←</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'proxyclass') && isEdgeActive(snapshotAt(currentIndex), 'p-client-return') }">
              <span>cast / unbox / catch</span>
              <strong>$ProxyN</strong>
            </div>
            <div class="flow-arrow is-reverse" :class="{ 'is-active': isEdgeActive(snapshotAt(currentIndex), 'p-handler-return') }">
              <span>Object / Throwable</span><i>←</i>
            </div>
            <div class="flow-node" :class="{ 'is-active': isNodeActive(snapshotAt(currentIndex), 'handler') && isEdgeActive(snapshotAt(currentIndex), 'p-handler-return') }">
              <span>策略返回</span>
              <strong>Invocation<wbr>Handler</strong>
            </div>
          </div>
        </section>

        <div class="reflection-proxy-demo__result">
          <div>
            <span>内部策略 / 关键状态</span>
            <code>{{ snapshotAt(currentIndex).accessor }}</code>
          </div>
          <div>
            <span>本步输出</span>
            <strong>{{ snapshotAt(currentIndex).output }}</strong>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.reflection-proxy-demo {
  display: grid;
  gap: 14px;
  min-width: 0;
  min-height: 430px;
}

.reflection-proxy-demo__status {
  display: grid;
  grid-template-columns: minmax(160px, 0.45fr) minmax(0, 1.55fr);
  gap: 12px;
  align-items: center;
  min-width: 0;
}

.reflection-proxy-demo__status strong {
  padding-left: 9px;
  border-left: 3px solid var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
  font-size: 0.78rem;
}

.reflection-proxy-demo__status strong.is-proxy {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.reflection-proxy-demo__status code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.72rem;
  text-align: right;
}

.flow-section {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: opacity 180ms ease, border-color 180ms ease;
}

.flow-section.is-reflection:not(.is-muted) {
  border-color: var(--vp-c-brand-1);
}

.flow-section.is-proxy:not(.is-muted) {
  border-color: var(--atlas-coral);
}

.flow-section.is-muted {
  opacity: 0.46;
}

.flow-section__title {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 12px;
  align-items: baseline;
  justify-content: space-between;
}

.flow-section__title span {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
  font-weight: 700;
}

.flow-section__title small {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.flow-grid {
  display: grid;
  gap: 7px;
  align-items: stretch;
  min-width: 0;
}

.reflection-flow {
  grid-template-columns: minmax(0, 0.85fr) minmax(34px, 0.36fr) minmax(0, 0.9fr) minmax(34px, 0.36fr) minmax(0, 1fr) minmax(34px, 0.36fr) minmax(0, 1fr) minmax(34px, 0.36fr) minmax(0, 1fr);
}

.proxy-flow {
  grid-template-columns: minmax(0, 0.8fr) minmax(34px, 0.38fr) minmax(0, 1fr) minmax(34px, 0.38fr) minmax(0, 1fr) minmax(34px, 0.38fr) minmax(0, 0.9fr);
}

.accessor-routes,
.proxy-return-flow {
  padding-top: 4px;
  border-top: 1px dashed var(--atlas-line);
}

.accessor-routes {
  display: grid;
  gap: 7px;
}

.accessor-route {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(42px, 0.45fr) minmax(0, 1fr);
  gap: 7px;
  min-width: 0;
}

.accessor-route.is-generated-route {
  grid-template-columns: minmax(0, 1fr) minmax(42px, 0.45fr) minmax(0, 1fr) minmax(42px, 0.45fr) minmax(0, 1fr);
}

.proxy-return-flow {
  grid-template-columns: minmax(0, 1fr) minmax(44px, 0.5fr) minmax(0, 1fr) minmax(44px, 0.5fr) minmax(0, 1fr);
}

.flow-node {
  display: grid;
  align-content: center;
  min-width: 0;
  min-height: 54px;
  padding: 7px 8px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  transition: background-color 180ms ease, border-color 180ms ease, transform 180ms ease;
}

.flow-node span,
.flow-node strong {
  min-width: 0;
  overflow-wrap: anywhere;
  text-align: center;
}

.flow-node span {
  color: var(--vp-c-text-3);
  font-size: 0.62rem;
}

.flow-node strong {
  margin-top: 3px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.is-reflection .flow-node.is-active {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  transform: translateY(-2px);
}

.is-reflection .flow-node.is-active strong {
  color: var(--vp-c-brand-1);
}

.is-proxy .flow-node.is-active {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 10%, var(--vp-c-bg));
  transform: translateY(-2px);
}

.is-proxy .flow-node.is-active strong {
  color: var(--atlas-coral);
}

.flow-node.is-generated:not(.is-active) {
  border-style: dashed;
}

.flow-arrow {
  display: grid;
  grid-template-rows: 1fr auto;
  place-items: center;
  min-width: 0;
  color: var(--vp-c-text-3);
}

.flow-arrow span {
  align-self: end;
  overflow-wrap: anywhere;
  font-size: 0.58rem;
  text-align: center;
}

.flow-arrow i {
  align-self: start;
  font-size: 1.1rem;
  font-style: normal;
  line-height: 1;
}

.is-reflection .flow-arrow.is-active {
  color: var(--vp-c-brand-1);
}

.is-proxy .flow-arrow.is-active {
  color: var(--atlas-coral);
}

.reflection-proxy-demo__result {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}

.reflection-proxy-demo__result > div {
  min-width: 0;
  padding: 9px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.reflection-proxy-demo__result span,
.reflection-proxy-demo__result code,
.reflection-proxy-demo__result strong {
  display: block;
  min-width: 0;
  overflow-wrap: anywhere;
}

.reflection-proxy-demo__result span {
  color: var(--vp-c-text-3);
  font-size: 0.64rem;
}

.reflection-proxy-demo__result code,
.reflection-proxy-demo__result strong {
  margin-top: 4px;
  color: var(--atlas-ink);
  font-size: 0.7rem;
}

@media (max-width: 760px) {
  .reflection-proxy-demo {
    min-height: 680px;
  }

  .reflection-proxy-demo__status,
  .reflection-proxy-demo__result {
    grid-template-columns: 1fr;
  }

  .reflection-proxy-demo__status code {
    text-align: left;
  }

  .flow-grid,
  .proxy-flow,
  .reflection-flow,
  .accessor-route,
  .accessor-route.is-generated-route,
  .proxy-return-flow {
    grid-template-columns: minmax(0, 1fr);
  }

  .flow-arrow {
    grid-template-rows: auto;
    grid-template-columns: 1fr auto 1fr;
    min-height: 24px;
  }

  .flow-arrow::before,
  .flow-arrow::after {
    height: 1px;
    background: currentColor;
    content: '';
  }

  .flow-arrow span {
    align-self: center;
    padding: 0 6px;
  }

  .flow-arrow i {
    display: none;
  }

  .flow-arrow.is-reverse span::after {
    content: '（回写）';
  }
}

@media (prefers-reduced-motion: reduce) {
  .flow-section,
  .flow-node {
    transition: none;
  }
}
</style>
