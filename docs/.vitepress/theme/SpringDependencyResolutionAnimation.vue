<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type ResolutionTone = 'descriptor' | 'filter' | 'select' | 'multiple' | 'deferred' | 'failure' | 'done'

interface ResolutionSnapshot {
  request: string
  mode: string
  candidates: string
  rejected: string
  selected: string
  next: string
  tone: ResolutionTone
}

const steps: SourceAnimationStep[] = [
  {
    title: '注入点封装为 DependencyDescriptor',
    method: 'new DependencyDescriptor(methodParameter, required)',
    description: 'descriptor 保存声明类型、泛型、注解、required、依赖名称和 nesting level；后续所有候选判断都围绕它进行。'
  },
  {
    title: 'resolveDependency 先识别特殊包装',
    method: 'DefaultListableBeanFactory.resolveDependency(...)',
    description: 'Optional、ObjectFactory/ObjectProvider 和 JSR-330 Provider 先走包装分支；普通依赖再进入 doResolveDependency。'
  },
  {
    title: 'ResolvableType 保留泛型',
    method: 'descriptor.getResolvableType()',
    description: 'GenericRepository<Customer> 不会只退化为裸接口。GenericTypeAwareAutowireCandidateResolver 会比较候选工厂方法或 BeanDefinition 暴露的泛型。'
  },
  {
    title: '按原始类型收集候选名称',
    method: 'BeanFactoryUtils.beanNamesForTypeIncludingAncestors(...)',
    description: '容器先从本地及祖先范围寻找可能类型匹配的 Bean 名称，同时避免把当前正在创建的自引用候选当作普通首选。'
  },
  {
    title: 'isAutowireCandidate 执行限定过滤',
    method: 'findAutowireCandidates → isAutowireCandidate',
    description: '候选还要满足 autowireCandidate 标志、泛型与 Qualifier。@Qualifier("batch") 会淘汰没有匹配限定信息的 primaryGateway。'
  },
  {
    title: '单值候选优先选择 Primary',
    method: 'determinePrimaryCandidate(candidates, requiredType)',
    description: '没有显式 Qualifier 且同类型候选多于一个时，唯一 @Primary 可成为单值结果；出现多个 Primary 仍会报错。'
  },
  {
    title: '再比较最高 Priority',
    method: 'determineHighestPriorityCandidate(candidates, requiredType)',
    description: '没有 Primary 时，Spring 可使用受支持的 priority 值继续裁决；相同最高优先级仍不能产生唯一结果。Ordered 主要用于多值集合排序，不能简单等同单值 Priority。'
  },
  {
    title: '依赖名称可作为最后匹配线索',
    method: 'matchesBeanName(candidateName, descriptor.getDependencyName())',
    description: '前面没有裁决时，Bean 名或别名与注入点名称一致可选中候选。参数名是否保留受编译元数据影响，不应代替显式 Qualifier 表达重要业务语义。'
  },
  {
    title: '仍有多个候选则明确失败',
    method: 'descriptor.resolveNotUnique(type, matchingBeans)',
    description: '单值 required 依赖无法唯一确定时抛 NoUniqueBeanDefinitionException。Spring 不会按注册顺序随便挑一个。'
  },
  {
    title: 'List 分支收集全部匹配候选',
    method: 'resolveMultipleBeans → findAutowireCandidates',
    description: '数组、Collection 和 Map 是多值依赖，不执行单值 Primary 裁决；它们保留全部类型和限定符匹配项。'
  },
  {
    title: '多值结果按比较器排序',
    method: 'adaptDependencyComparator(matchingBeans) → sort',
    description: 'List/数组可按 dependencyComparator 排序，AnnotationAwareOrderComparator 会识别 Ordered、@Order 和受支持的优先级信息。'
  },
  {
    title: 'Optional 把缺失变为空值',
    method: 'createOptionalDependency(descriptor, beanName, autowiredBeanNames)',
    description: 'Optional<T> 复用嵌套 descriptor 解析 T，但把 required 改为 false；没有候选得到 Optional.empty，不代表多个候选歧义会被吞掉。'
  },
  {
    title: 'ObjectProvider 延迟每次查询',
    method: 'new DependencyObjectProvider(descriptor, requestingBeanName)',
    description: '注入 provider 时不要求立刻创建目标；getObject、getIfAvailable 或 stream 调用时重新进入解析，能看到之后注册或不同作用域的结果。'
  },
  {
    title: '@Lazy 注入解析为延迟代理',
    method: 'ContextAnnotationAutowireCandidateResolver.getLazyResolutionProxyIfNecessary',
    description: '注入点先获得代理，第一次方法调用时代理再解析真实依赖。它改变的是依赖获取时机，不自动消除运行期缺失或歧义。'
  },
  {
    title: '选中后才 getBean 并登记依赖关系',
    method: 'resolveCandidate → getBean; registerDependentBean',
    description: '候选名称确定后才创建或取得实例、做类型适配，并把 autowiredBeanNames 登记为 dependentBean，供销毁顺序等容器生命周期使用。'
  }
]

const snapshots: ResolutionSnapshot[] = [
  { request: 'Gateway gateway', mode: 'required single', candidates: '尚未查询', rejected: '无', selected: '无', next: '读取类型、注解与依赖名称', tone: 'descriptor' },
  { request: 'Optional / Provider / 普通依赖', mode: '包装类型分流', candidates: '由嵌套 descriptor 决定', rejected: '无', selected: '创建包装或进入 doResolveDependency', next: '普通依赖继续', tone: 'descriptor' },
  { request: 'GenericRepository<Customer>', mode: 'ResolvableType', candidates: 'customerRepository, orderRepository', rejected: 'orderRepository<T=Order>', selected: 'customerRepository<T=Customer>', next: '继续候选资格检查', tone: 'filter' },
  { request: 'Gateway gateway', mode: '按类型枚举', candidates: 'primaryGateway, batchGateway', rejected: '尚未过滤', selected: '尚未裁决', next: 'isAutowireCandidate', tone: 'filter' },
  { request: '@Qualifier("batch") Gateway', mode: '限定符过滤', candidates: 'primaryGateway, batchGateway', rejected: 'primaryGateway', selected: 'batchGateway', next: '唯一候选直接返回', tone: 'select' },
  { request: 'Gateway gateway', mode: '单值裁决', candidates: 'primaryGateway, batchGateway', rejected: 'batchGateway 不是 Primary', selected: 'primaryGateway', next: 'resolveCandidate', tone: 'select' },
  { request: 'Gateway gateway', mode: 'Priority 裁决', candidates: '无 Primary 的多个候选', rejected: '较低优先级', selected: '唯一最高 Priority（若存在）', next: '相同最高值则失败', tone: 'select' },
  { request: 'Gateway batchGateway', mode: '名称匹配', candidates: '多个剩余候选', rejected: '名称与别名不匹配项', selected: 'batchGateway', next: '重要语义优先显式 Qualifier', tone: 'select' },
  { request: 'Gateway gateway', mode: 'required single', candidates: 'leftGateway, rightGateway', rejected: '没有可继续裁决的候选', selected: '无', next: 'NoUniqueBeanDefinitionException', tone: 'failure' },
  { request: 'List<Handler>', mode: 'multiple', candidates: 'firstHandler, secondHandler', rejected: '非 Handler 候选', selected: '全部匹配项', next: '实例化并排序', tone: 'multiple' },
  { request: 'List<Handler>', mode: 'dependencyComparator', candidates: 'second(order=20), first(order=10)', rejected: '无', selected: 'first → second', next: '注入只读视角的结果集合', tone: 'multiple' },
  { request: 'Optional<MissingService>', mode: 'required=false', candidates: '0 个', rejected: '无实现', selected: 'Optional.empty', next: '目标 Bean 仍可创建', tone: 'deferred' },
  { request: 'ObjectProvider<MissingService>', mode: '延迟查询句柄', candidates: '注入时不创建目标', rejected: '无', selected: 'DependencyObjectProvider', next: 'getIfAvailable() → null', tone: 'deferred' },
  { request: '@Lazy HeavyService', mode: '延迟代理', candidates: 'heavyService BeanDefinition', rejected: '真实目标暂不创建', selected: 'HeavyService proxy', next: '首次 load() → getBean', tone: 'deferred' },
  { request: '已选中的 dependencyName', mode: '完成解析', candidates: '唯一候选', rejected: '已淘汰', selected: '实例 + 类型转换结果', next: 'registerDependentBean', tone: 'done' }
]
</script>

<template>
  <SourceAnimation title="依赖候选怎样从类型集合逐轮收缩为结果" :steps="steps" :interval="2700">
    <template #visual="{ currentIndex }">
      <div class="resolution-flow" :class="`is-${snapshots[currentIndex].tone}`">
        <div class="resolution-flow__request">
          <div>
            <span>DependencyDescriptor</span>
            <strong>{{ snapshots[currentIndex].request }}</strong>
          </div>
          <code>{{ snapshots[currentIndex].mode }}</code>
        </div>

        <div class="resolution-flow__funnel" aria-label="依赖候选筛选过程">
          <section>
            <span>进入本轮的候选</span>
            <strong>{{ snapshots[currentIndex].candidates }}</strong>
          </section>
          <i aria-hidden="true">→</i>
          <section class="is-rejected">
            <span>本轮排除</span>
            <strong>{{ snapshots[currentIndex].rejected }}</strong>
          </section>
          <i aria-hidden="true">→</i>
          <section class="is-selected">
            <span>本轮结果</span>
            <strong>{{ snapshots[currentIndex].selected }}</strong>
          </section>
        </div>

        <div class="resolution-flow__next">
          <span>下一步</span>
          <strong>{{ snapshots[currentIndex].next }}</strong>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.resolution-flow { display: grid; min-width: 0; min-height: 310px; gap: 18px; align-content: center; }
.resolution-flow__request { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; align-items: center; }
.resolution-flow__request > div { display: grid; min-width: 0; gap: 6px; }
.resolution-flow span { color: var(--vp-c-text-3); font-size: 0.72rem; font-weight: 700; }
.resolution-flow strong,
.resolution-flow code { min-width: 0; overflow-wrap: anywhere; }
.resolution-flow__request code { padding: 7px 10px; border-radius: 4px; background: var(--vp-c-brand-soft); color: var(--vp-c-brand-1); }
.resolution-flow__funnel { display: grid; grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr) 24px minmax(0, 1fr); gap: 8px; align-items: center; }
.resolution-flow__funnel section { display: grid; min-width: 0; min-height: 92px; gap: 7px; align-content: center; padding: 13px; border-block: 1px solid var(--atlas-line); background: var(--atlas-surface); }
.resolution-flow__funnel i { color: var(--vp-c-brand-1); font-size: 1.2rem; font-style: normal; text-align: center; }
.resolution-flow__funnel .is-rejected { border-left: 4px solid var(--atlas-coral); }
.resolution-flow__funnel .is-selected { border-left: 4px solid var(--vp-c-brand-1); }
.resolution-flow__next { display: grid; min-width: 0; gap: 5px; padding: 12px 14px; border-left: 4px solid var(--atlas-gold); background: var(--atlas-surface); }
.resolution-flow.is-failure .resolution-flow__request code { color: var(--atlas-coral); background: color-mix(in srgb, var(--atlas-coral) 12%, transparent); }
.resolution-flow.is-deferred .resolution-flow__funnel .is-selected { border-left-color: var(--atlas-purple); }
@media (max-width: 700px) {
  .resolution-flow { min-height: 440px; }
  .resolution-flow__request { grid-template-columns: 1fr; }
  .resolution-flow__request code { justify-self: stretch; }
  .resolution-flow__funnel { grid-template-columns: 1fr; }
  .resolution-flow__funnel i { transform: rotate(90deg); }
}
@media (max-width: 420px) {
  .resolution-flow { min-height: 510px; gap: 12px; }
  .resolution-flow__funnel section { min-height: 70px; padding: 10px; }
}
</style>
