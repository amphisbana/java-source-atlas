<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface CacheSnapshot {
  singletonObjects: string
  earlySingletonObjects: string
  singletonFactories: string
  creating: string
}

interface ObjectSnapshot {
  rawA: string
  rawB: string
  injectedReference: string
  exposedReference: string
  identity: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '进入单例预实例化',
    method: 'finishBeanFactoryInitialization()',
    description: 'refresh 冻结配置后调用 preInstantiateSingletons；本动画从非懒加载单例 A 的创建入口继续向下跟踪。'
  },
  {
    title: '枚举非懒单例',
    method: 'preInstantiateSingletons()',
    description: '工厂遍历 BeanDefinition 名称；A 不是抽象、是 singleton 且不是 lazy-init，于是调用 getBean("A")。'
  },
  {
    title: '规范名称并合并定义',
    method: 'doGetBean("A")',
    description: '先查单例缓存，再取得合并后的 RootBeanDefinition、处理 dependsOn 与 scope，并把 A 标记为正在创建。'
  },
  {
    title: '进入创建模板',
    method: 'createBean("A", mbd, args)',
    description: '解析目标类型和方法覆盖，再执行 resolveBeforeInstantiation；没有处理器提前返回代理时进入 doCreateBean。'
  },
  {
    title: '实例化原始 A',
    method: 'createBeanInstance("A", mbd, args)',
    description: '根据 Supplier、工厂方法或构造器策略得到 BeanWrapper 与 A#raw；此时 A 的属性和生命周期回调都尚未执行。'
  },
  {
    title: '登记 A 的早期工厂',
    method: 'addSingletonFactory("A", ...)',
    description: '合并定义后处理器先缓存注入元数据；随后把延迟调用 getEarlyBeanReference 的工厂放进三级缓存。'
  },
  {
    title: 'A 注入时请求 B',
    method: 'populateBean("A", mbd, bw)',
    description: 'InstantiationAwareBeanPostProcessor 与属性值解析发现 A.b 依赖 B，于是递归调用 getBean("B")。'
  },
  {
    title: '实例化 B 并登记工厂',
    method: 'createBeanInstance("B") / addSingletonFactory("B")',
    description: 'B#raw 已产生，B 的早期引用工厂也进入三级缓存；A、B 此时都在 singletonsCurrentlyInCreation。'
  },
  {
    title: 'B 回头请求 A',
    method: 'getSingleton("A", true)',
    description: '一级缓存没有 A；因为 A 正在创建，容器继续检查二级和三级缓存。'
  },
  {
    title: '物化 A 的早期代理',
    method: 'getEarlyBeanReference("A", mbd, A#raw)',
    description: '自动代理创建器把 A#raw 包装为 Proxy(A#raw)；引用从三级工厂迁到二级缓存，保证后续重复查询得到同一对象。'
  },
  {
    title: '把早期代理注入 B',
    method: 'applyPropertyValues("B", ...)',
    description: 'B.a 得到二级缓存中的 Proxy(A#raw)，不是原始 A；这一步建立了稍后必须保持的对象身份约束。'
  },
  {
    title: '完成并注册 B',
    method: 'initializeBean("B") / addSingleton("B")',
    description: 'B 完成 Aware、初始化与后处理器链后进入一级缓存；同名二、三级条目被清理，B 退出正在创建集合。'
  },
  {
    title: '把完整 B 注入 A',
    method: 'applyPropertyValues("A", ...)',
    description: 'A.b 得到一级缓存中的完整 B。至此两侧依赖都已填充，但 A 仍要执行自己的初始化链。'
  },
  {
    title: '执行 Aware 回调',
    method: 'invokeAwareMethods("A", A#raw)',
    description: 'initializeBean 先直接回调 BeanNameAware、BeanClassLoaderAware、BeanFactoryAware；Context Aware 由初始化前处理器负责。'
  },
  {
    title: '执行初始化前处理器',
    method: 'postProcessBeforeInitialization(A#raw, "A")',
    description: 'BeanPostProcessor 按注册顺序串行传递引用；CommonAnnotationBeanPostProcessor 在自己的位置调用 @PostConstruct。'
  },
  {
    title: '执行 init 与初始化后处理',
    method: 'invokeInitMethods() / postProcessAfterInitialization()',
    description: '先 InitializingBean.afterPropertiesSet，再自定义 init-method，最后执行初始化后处理器；早期代理记录避免 A 被再次包装。'
  },
  {
    title: '统一最终代理身份',
    method: 'getSingleton("A", false) / addSingleton("A", proxy)',
    description: 'doCreateBean 用二级缓存中的早期代理替换仍为原对象的 exposedObject，再登记销毁适配器并把同一代理放入一级缓存。'
  },
  {
    title: '按销毁契约关闭',
    method: 'DisposableBeanAdapter.destroy()',
    description: '上下文关闭时先销毁依赖当前 Bean 的对象，再对目标执行 @PreDestroy、DisposableBean.destroy 和不重复的自定义 destroy-method。'
  }
]

const phases = [
  { title: '定义与查询', method: 'doGetBean' },
  { title: '实例化', method: 'createBeanInstance' },
  { title: '属性填充', method: 'populateBean' },
  { title: '初始化', method: 'initializeBean' },
  { title: '早期代理', method: 'getEarlyBeanReference' },
  { title: '注册与销毁', method: 'addSingleton / destroy' }
]

const activePhaseIndexes = [0, 0, 0, 0, 1, 4, 2, 1, 4, 4, 2, 3, 2, 3, 3, 3, 5, 5]
const phaseLastSteps = [3, 7, 12, 15, 15, 17]

const cacheSnapshots: CacheSnapshot[] = [
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: '∅', creating: '∅' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: '∅', creating: '∅' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: 'A factory', creating: 'A' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: 'A factory', creating: 'A → B' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: 'A factory · B factory', creating: 'A · B' },
  { singletonObjects: '∅', earlySingletonObjects: '∅', singletonFactories: 'A factory · B factory', creating: 'A · B' },
  { singletonObjects: '∅', earlySingletonObjects: 'A proxy', singletonFactories: 'B factory', creating: 'A · B' },
  { singletonObjects: '∅', earlySingletonObjects: 'A proxy', singletonFactories: 'B factory', creating: 'A · B' },
  { singletonObjects: 'B', earlySingletonObjects: 'A proxy', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: 'B', earlySingletonObjects: 'A proxy', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: 'B', earlySingletonObjects: 'A proxy', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: 'B', earlySingletonObjects: 'A proxy', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: 'B', earlySingletonObjects: 'A proxy', singletonFactories: '∅', creating: 'A' },
  { singletonObjects: 'A proxy · B', earlySingletonObjects: '∅', singletonFactories: '∅', creating: '∅' },
  { singletonObjects: '按依赖顺序移除', earlySingletonObjects: '∅', singletonFactories: '∅', creating: '∅' }
]

const objectSnapshots: ObjectSnapshot[] = [
  { rawA: '尚未创建', rawB: '尚未创建', injectedReference: '尚未注入', exposedReference: '尚未暴露', identity: 'BeanDefinition 已就绪' },
  { rawA: '尚未创建', rawB: '尚未创建', injectedReference: '尚未注入', exposedReference: '尚未暴露', identity: '准备创建 A' },
  { rawA: '尚未创建', rawB: '尚未创建', injectedReference: '尚未注入', exposedReference: '尚未暴露', identity: 'A 已标记 creating' },
  { rawA: '尚未创建', rawB: '尚未创建', injectedReference: '尚未注入', exposedReference: '尚未暴露', identity: '常规创建链未被短路' },
  { rawA: 'A#raw', rawB: '尚未创建', injectedReference: '尚未注入', exposedReference: '尚未暴露', identity: '只有原始实例' },
  { rawA: 'A#raw', rawB: '尚未创建', injectedReference: '尚未注入', exposedReference: '延迟工厂', identity: '代理尚未物化' },
  { rawA: 'A#raw', rawB: '准备创建', injectedReference: '尚未注入', exposedReference: '延迟工厂', identity: 'A 等待 B' },
  { rawA: 'A#raw', rawB: 'B#raw', injectedReference: '尚未注入', exposedReference: '延迟工厂', identity: 'A 与 B 都未完成' },
  { rawA: 'A#raw', rawB: 'B#raw', injectedReference: 'B.a 正在解析', exposedReference: '检查三级缓存', identity: 'A 正在创建才允许早期查询' },
  { rawA: 'A#raw', rawB: 'B#raw', injectedReference: '等待注入', exposedReference: 'Proxy(A#raw)', identity: 'early reference 已唯一物化' },
  { rawA: 'A#raw', rawB: 'B#raw', injectedReference: 'B.a → A proxy', exposedReference: 'Proxy(A#raw)', identity: 'B.a === early reference' },
  { rawA: 'A#raw', rawB: 'B#ready', injectedReference: 'B.a → A proxy', exposedReference: 'Proxy(A#raw)', identity: 'B 已完成，A 尚未初始化' },
  { rawA: 'A#raw + B', rawB: 'B#ready', injectedReference: 'B.a → A proxy', exposedReference: 'Proxy(A#raw)', identity: '依赖填充完成' },
  { rawA: 'A#raw + B', rawB: 'B#ready', injectedReference: 'B.a → A proxy', exposedReference: 'Proxy(A#raw)', identity: 'Aware 回调作用于原始目标' },
  { rawA: 'A#raw + B', rawB: 'B#ready', injectedReference: 'B.a → A proxy', exposedReference: 'Proxy(A#raw)', identity: '@PostConstruct 仍作用于原始目标' },
  { rawA: 'A#initialized', rawB: 'B#ready', injectedReference: 'B.a → A proxy', exposedReference: 'Proxy(A#raw)', identity: '自动代理器不重复包装' },
  { rawA: 'A#initialized', rawB: 'B#ready', injectedReference: 'B.a → A proxy', exposedReference: 'getBean(A) → A proxy', identity: 'early === B.a === singleton === getBean(A)' },
  { rawA: '销毁目标 A#raw', rawB: '先按依赖关系销毁', injectedReference: '不再提供新依赖', exposedReference: '一级缓存清理', identity: '代理只创建过一次' }
]

const lifecycleCallbacks = [
  { title: '属性填充', method: 'populateBean', step: 12 },
  { title: 'Aware', method: 'invokeAwareMethods', step: 13 },
  { title: 'BPP.before', method: 'beforeInitialization', step: 14 },
  { title: '@PostConstruct', method: 'CommonAnnotationBPP', step: 14 },
  { title: 'InitializingBean', method: 'afterPropertiesSet', step: 15 },
  { title: '自定义 init', method: 'init-method', step: 15 },
  { title: 'BPP.after', method: 'afterInitialization', step: 15 }
]
</script>

<template>
  <SourceAnimation title="Bean 创建、三级缓存与早期代理同一时间轴" :steps="steps" :interval="2300">
    <template #visual="{ currentIndex }">
      <div class="bean-lifecycle">
        <div class="bean-lifecycle__phases" aria-label="Bean 创建阶段">
          <div
            v-for="(phase, index) in phases"
            :key="phase.title"
            class="bean-lifecycle__phase"
            :class="{
              'is-active': activePhaseIndexes[currentIndex] === index,
              'is-complete': currentIndex > phaseLastSteps[index]
            }"
          >
            <span>{{ index + 1 }}</span>
            <strong>{{ phase.title }}</strong>
            <code>{{ phase.method }}</code>
          </div>
        </div>

        <div class="bean-lifecycle__objects" aria-label="当前对象身份">
          <div>
            <span>原始 A</span>
            <strong>{{ objectSnapshots[currentIndex].rawA }}</strong>
          </div>
          <div>
            <span>对象 B</span>
            <strong>{{ objectSnapshots[currentIndex].rawB }}</strong>
          </div>
          <div>
            <span>B 实际持有</span>
            <strong>{{ objectSnapshots[currentIndex].injectedReference }}</strong>
          </div>
          <div>
            <span>容器暴露 A</span>
            <strong>{{ objectSnapshots[currentIndex].exposedReference }}</strong>
          </div>
          <p>{{ objectSnapshots[currentIndex].identity }}</p>
        </div>

        <div class="bean-lifecycle__caches" aria-label="单例缓存快照">
          <div>
            <span>一级 singletonObjects</span>
            <strong>{{ cacheSnapshots[currentIndex].singletonObjects }}</strong>
          </div>
          <div>
            <span>二级 earlySingletonObjects</span>
            <strong>{{ cacheSnapshots[currentIndex].earlySingletonObjects }}</strong>
          </div>
          <div>
            <span>三级 singletonFactories</span>
            <strong>{{ cacheSnapshots[currentIndex].singletonFactories }}</strong>
          </div>
          <div>
            <span>正在创建</span>
            <strong>{{ cacheSnapshots[currentIndex].creating }}</strong>
          </div>
        </div>

        <div class="bean-lifecycle__callbacks" aria-label="初始化回调顺序">
          <div
            v-for="callback in lifecycleCallbacks"
            :key="callback.title"
            class="bean-lifecycle__callback"
            :class="{
              'is-active': currentIndex === callback.step,
              'is-complete': currentIndex > callback.step
            }"
          >
            <strong>{{ callback.title }}</strong>
            <code>{{ callback.method }}</code>
          </div>
        </div>

        <div class="bean-lifecycle__destroy" :class="{ 'is-active': currentIndex === 17 }">
          <span>销毁回调</span>
          <strong>@PreDestroy</strong>
          <i>→</i>
          <strong>DisposableBean.destroy</strong>
          <i>→</i>
          <strong>destroy-method</strong>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.bean-lifecycle {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.bean-lifecycle__phases {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.bean-lifecycle__phase {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 2px 8px;
  align-content: center;
  min-width: 0;
  min-height: 64px;
  padding: 8px 9px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease, transform 180ms ease;
}

.bean-lifecycle__phase > span {
  grid-row: 1 / 3;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--atlas-line);
  border-radius: 50%;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.bean-lifecycle__phase strong,
.bean-lifecycle__phase code,
.bean-lifecycle__objects strong,
.bean-lifecycle__caches strong,
.bean-lifecycle__callback code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.bean-lifecycle__phase strong {
  color: var(--vp-c-text-2);
  font-size: 0.77rem;
}

.bean-lifecycle__phase code {
  color: var(--vp-c-text-3);
  font-size: 0.66rem;
}

.bean-lifecycle__phase.is-active {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
  transform: translateX(2px);
}

.bean-lifecycle__phase.is-active > span {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.bean-lifecycle__phase.is-complete {
  border-left-color: var(--vp-c-brand-1);
}

.bean-lifecycle__phase.is-complete > span {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.bean-lifecycle__objects {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}

.bean-lifecycle__objects > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  min-height: 58px;
  padding: 9px;
  border-right: 1px solid var(--atlas-line);
}

.bean-lifecycle__objects > div:nth-child(4) {
  border-right: 0;
}

.bean-lifecycle__objects span,
.bean-lifecycle__caches span,
.bean-lifecycle__destroy > span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.67rem;
}

.bean-lifecycle__objects strong,
.bean-lifecycle__caches strong {
  color: var(--atlas-ink);
  font-size: 0.76rem;
}

.bean-lifecycle__objects p {
  grid-column: 1 / -1;
  min-width: 0;
  margin: 0;
  padding: 8px 10px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.73rem;
  font-weight: 700;
  overflow-wrap: anywhere;
  text-align: center;
}

.bean-lifecycle__caches {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.bean-lifecycle__caches > div {
  display: grid;
  gap: 5px;
  min-width: 0;
  min-height: 56px;
  padding: 8px 9px;
  border-bottom: 2px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.bean-lifecycle__caches > div:nth-child(1) {
  border-bottom-color: var(--vp-c-brand-1);
}

.bean-lifecycle__caches > div:nth-child(2) {
  border-bottom-color: var(--atlas-coral);
}

.bean-lifecycle__caches > div:nth-child(3) {
  border-bottom-color: var(--vp-c-warning-1);
}

.bean-lifecycle__callbacks {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
}

.bean-lifecycle__callback {
  position: relative;
  display: grid;
  gap: 3px;
  min-width: 0;
  min-height: 52px;
  align-content: center;
  padding: 6px 7px;
  border-top: 2px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  opacity: 0.55;
}

.bean-lifecycle__callback:not(:last-child)::after {
  position: absolute;
  top: 17px;
  right: 2px;
  z-index: 1;
  color: var(--vp-c-text-3);
  content: '›';
}

.bean-lifecycle__callback:nth-child(4)::after {
  display: none;
}

.bean-lifecycle__callback strong {
  font-size: 0.7rem;
}

.bean-lifecycle__callback code {
  color: inherit;
  font-size: 0.61rem;
}

.bean-lifecycle__callback.is-active {
  border-top-color: var(--atlas-coral);
  color: var(--atlas-coral);
  opacity: 1;
}

.bean-lifecycle__callback.is-complete {
  border-top-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
  opacity: 1;
}

.bean-lifecycle__destroy {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  align-items: center;
  min-height: 40px;
  padding: 8px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  color: var(--vp-c-text-3);
  opacity: 0.55;
  transition: border-color 180ms ease, color 180ms ease, opacity 180ms ease;
}

.bean-lifecycle__destroy strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  overflow-wrap: anywhere;
}

.bean-lifecycle__destroy i {
  font-style: normal;
}

.bean-lifecycle__destroy.is-active {
  border-left-color: var(--atlas-coral);
  color: var(--atlas-coral);
  opacity: 1;
}

@media (max-width: 700px) {
  .bean-lifecycle__phases {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .bean-lifecycle__objects,
  .bean-lifecycle__caches {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .bean-lifecycle__objects > div:nth-child(2) {
    border-right: 0;
  }

  .bean-lifecycle__objects > div:nth-child(-n + 2) {
    border-bottom: 1px solid var(--atlas-line);
  }

  .bean-lifecycle__callbacks {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .bean-lifecycle__callback:not(:last-child)::after {
    display: none;
  }
}

@media (max-width: 420px) {
  .bean-lifecycle__phases,
  .bean-lifecycle__objects,
  .bean-lifecycle__caches,
  .bean-lifecycle__callbacks {
    grid-template-columns: minmax(0, 1fr);
  }

  .bean-lifecycle__objects > div {
    min-height: 48px;
    border-right: 0;
    border-bottom: 1px solid var(--atlas-line);
  }

  .bean-lifecycle__objects > div:nth-child(4) {
    border-bottom: 0;
  }

  .bean-lifecycle__objects p {
    text-align: left;
  }

  .bean-lifecycle__callback {
    min-height: 44px;
  }

  .bean-lifecycle__phase.is-active {
    transform: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .bean-lifecycle__phase,
  .bean-lifecycle__destroy {
    transition: none;
  }
}
</style>
