<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type ConfigurationTone = 'registry' | 'parse' | 'import' | 'condition' | 'enhance' | 'done'

interface ConfigurationSnapshot {
  phase: string
  registryCount: string
  candidates: string
  parsed: string
  deferred: string
  added: string
  mode: string
  tone: ConfigurationTone
}

const steps: SourceAnimationStep[] = [
  {
    title: 'register 只加入初始配置定义',
    method: 'context.register(ParsingRootConfiguration.class)',
    description: 'AnnotatedBeanDefinitionReader 把根配置类注册为 BeanDefinition；ComponentScan、Import 和 @Bean 尚未全部展开。'
  },
  {
    title: 'refresh 进入 Registry 后处理阶段',
    method: 'invokeBeanFactoryPostProcessors(beanFactory)',
    description: 'PostProcessorRegistrationDelegate 先执行 BeanDefinitionRegistryPostProcessor；ConfigurationClassPostProcessor 在业务单例创建前获得修改 Registry 的机会。'
  },
  {
    title: '识别配置类候选并标记 full/lite',
    method: 'checkConfigurationClassCandidate(beanDef, metadataReaderFactory)',
    description: '带 @Configuration 且 proxyBeanMethods=true 的候选标记为 full；其他含 @Bean、@Import、@Component 等特征的候选通常标记为 lite。'
  },
  {
    title: '根配置进入 parser',
    method: 'ConfigurationClassParser.parse(candidates)',
    description: 'parser 创建 ConfigurationClass 模型，先评估 PARSE_CONFIGURATION 条件，再递归处理根配置和它发现的配置来源。'
  },
  {
    title: 'ComponentScan 增加组件定义',
    method: 'componentScanParser.parse(...) → scanner.doScan(...)',
    description: '扫描器先把 ScannedAtlasComponent 注册到 Registry；若扫描结果本身也是配置候选，parser 会继续解析，形成“注册后再发现”的循环。'
  },
  {
    title: '普通 Import 立即递归解析',
    method: 'processImports → ImportedConfiguration',
    description: '直接导入的配置类立即加入配置模型，并记录 importingClass 关系；它的 @Bean 方法稍后由 reader 统一写回 Registry。'
  },
  {
    title: 'ImportSelector 立即返回配置名',
    method: 'ImmediateSelector.selectImports(metadata)',
    description: '普通 ImportSelector 在当前解析过程中执行，返回的类名继续走 processImports 和配置类解析。'
  },
  {
    title: 'DeferredImportSelector 先入队',
    method: 'deferredImportSelectorHandler.handle(configClass, selector)',
    description: '延迟选择器不会立刻递归；handler 保存 holder，让普通配置、扫描和 @Bean 元数据先处理完成。Spring Boot 自动配置也利用这条扩展边界。'
  },
  {
    title: '收集根配置的 @Bean 方法',
    method: 'retrieveBeanMethodMetadata → beanMethods.add(...)',
    description: 'parser 此时只建立配置模型中的 BeanMethod，不会调用用户 @Bean 方法，也不会创建业务对象。'
  },
  {
    title: '条件不匹配跳过配置',
    method: 'conditionEvaluator.shouldSkip(metadata, PARSE_CONFIGURATION)',
    description: 'NeverMatchCondition 返回 false，SkippedConfiguration 不进入有效配置模型，因此 skippedMarker 不会成为 BeanDefinition。'
  },
  {
    title: '统一执行延迟导入',
    method: 'deferredImportSelectorHandler.process()',
    description: '普通解析完成后，DeferredImportSelectorHolder 按组处理；DeferredSelectedConfiguration 此时才加入配置模型。'
  },
  {
    title: 'reader 把配置模型写回 Registry',
    method: 'ConfigurationClassBeanDefinitionReader.loadBeanDefinitions(...)',
    description: 'reader 注册被导入配置类、@Bean 工厂方法、ImportBeanDefinitionRegistrar 与 ImportResource 结果；注册定义仍不等于实例化。'
  },
  {
    title: '新定义触发下一轮候选检查',
    method: 'candidateNames.length != oldCandidateNames.length',
    description: 'ConfigurationClassPostProcessor 会再次检查本轮新注册但尚未处理的配置候选，直到没有新的配置定义需要解析。'
  },
  {
    title: 'full 配置类由 CGLIB 增强',
    method: 'enhanceConfigurationClasses(beanFactory)',
    description: 'postProcessBeanFactory 阶段把 full 配置类替换为增强子类；同类 @Bean 调用由 BeanMethodInterceptor 路由回 BeanFactory，保持单例语义。'
  },
  {
    title: 'lite 配置保持普通 Java 调用',
    method: '@Configuration(proxyBeanMethods=false)',
    description: 'lite 配置不为 @Bean 自调用提供拦截保证；直接调用另一个 @Bean 方法会创建普通新对象，推荐改为方法参数注入依赖。'
  },
  {
    title: 'Registry 稳定后才创建单例',
    method: 'finishBeanFactoryInitialization → preInstantiateSingletons',
    description: '扫描、Import、条件和 @Bean 定义全部落库后，refresh 才进入非懒单例创建。配置解析阶段与对象生命周期阶段至此清晰分开。'
  }
]

const snapshots: ConfigurationSnapshot[] = [
  { phase: '初始注册', registryCount: '基础设施 + 1', candidates: 'ParsingRootConfiguration', parsed: '0 个', deferred: '空', added: '根配置 BeanDefinition', mode: '尚未判定', tone: 'registry' },
  { phase: 'BDRPP 编排', registryCount: '基础设施 + 1', candidates: 'ConfigurationClassPostProcessor', parsed: '0 个', deferred: '空', added: '尚未增加业务定义', mode: 'Registry 可修改', tone: 'registry' },
  { phase: '候选分类', registryCount: '基础设施 + 1', candidates: '根配置：lite（proxyBeanMethods=false）', parsed: '0 个', deferred: '空', added: 'configurationClass 属性', mode: 'full / lite', tone: 'parse' },
  { phase: '递归解析', registryCount: '基础设施 + 1', candidates: '根配置进入队列', parsed: '1 个模型处理中', deferred: '空', added: 'ConfigurationClass(root)', mode: 'lite', tone: 'parse' },
  { phase: '类路径扫描', registryCount: '基础设施 + 2', candidates: 'ScannedAtlasComponent', parsed: '根配置', deferred: '空', added: '+ scannedAtlasComponent', mode: '组件定义', tone: 'registry' },
  { phase: '直接导入', registryCount: '暂未写入 @Bean', candidates: 'ImportedConfiguration', parsed: 'root + direct import', deferred: '空', added: '模型 + importedBy', mode: 'lite import', tone: 'import' },
  { phase: '立即选择器', registryCount: '暂未写入 @Bean', candidates: 'ImmediateSelectedConfiguration', parsed: '+ immediate config', deferred: '空', added: 'selectImports 返回值', mode: '立即递归', tone: 'import' },
  { phase: '延迟选择器登记', registryCount: '暂未写入 @Bean', candidates: 'DeferredSelector', parsed: '普通配置继续', deferred: '1 个 holder', added: '尚未展开 deferred config', mode: '延迟处理', tone: 'import' },
  { phase: '@Bean 元数据', registryCount: '暂未创建实例', candidates: '根及导入配置', parsed: 'BeanMethod 已收集', deferred: '1 个 holder', added: '工厂方法元数据', mode: '只建模型', tone: 'parse' },
  { phase: '条件评估', registryCount: '不增加 skippedMarker', candidates: 'SkippedConfiguration 被排除', parsed: '有效模型不含 skip', deferred: '1 个 holder', added: '0 个跳过定义', mode: 'Condition=false', tone: 'condition' },
  { phase: '延迟导入处理', registryCount: '暂未写入 @Bean', candidates: 'DeferredSelectedConfiguration', parsed: '+ deferred config', deferred: '已清空', added: '延迟配置模型', mode: '分组后解析', tone: 'import' },
  { phase: '定义落库', registryCount: '基础设施 + 组件 + 配置 + @Bean', candidates: '配置模型已稳定', parsed: '全部有效模型', deferred: '空', added: '+ imported/immediate/deferred markers', mode: 'BeanDefinition', tone: 'registry' },
  { phase: '重复发现循环', registryCount: '检查增长后的名称集合', candidates: '只取未处理新候选', parsed: '避免重复 parse', deferred: '空', added: '直到无新配置候选', mode: '多轮收敛', tone: 'parse' },
  { phase: 'full 增强', registryCount: '定义数量不变', candidates: 'FullConfiguration', parsed: '配置模型完成', deferred: '空', added: 'beanClass → CGLIB 子类', mode: '@Bean 调用回容器', tone: 'enhance' },
  { phase: 'lite 保持原类', registryCount: '定义数量不变', candidates: 'LiteConfiguration', parsed: '配置模型完成', deferred: '空', added: '无 CGLIB 自调用拦截', mode: '普通 Java 调用', tone: 'enhance' },
  { phase: '进入实例化', registryCount: '冻结配置', candidates: '非懒单例名称', parsed: '配置解析结束', deferred: '空', added: 'getBean / doCreateBean', mode: '对象生命周期开始', tone: 'done' }
]
</script>

<template>
  <SourceAnimation title="配置类怎样让 BeanDefinition Registry 逐轮增长" :steps="steps" :interval="2700">
    <template #visual="{ currentIndex }">
      <div class="configuration-flow" :class="`is-${snapshots[currentIndex].tone}`">
        <div class="configuration-flow__header">
          <div>
            <span>当前阶段</span>
            <strong>{{ snapshots[currentIndex].phase }}</strong>
          </div>
          <code>{{ snapshots[currentIndex].mode }}</code>
        </div>

        <div class="configuration-flow__pipeline">
          <section>
            <span>待处理候选</span>
            <strong>{{ snapshots[currentIndex].candidates }}</strong>
          </section>
          <i aria-hidden="true">→</i>
          <section>
            <span>ConfigurationClass 模型</span>
            <strong>{{ snapshots[currentIndex].parsed }}</strong>
          </section>
          <i aria-hidden="true">→</i>
          <section>
            <span>Registry</span>
            <strong>{{ snapshots[currentIndex].registryCount }}</strong>
          </section>
        </div>

        <div class="configuration-flow__state">
          <div>
            <span>本帧新增 / 排除</span>
            <strong>{{ snapshots[currentIndex].added }}</strong>
          </div>
          <div>
            <span>DeferredImportSelector 队列</span>
            <strong>{{ snapshots[currentIndex].deferred }}</strong>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.configuration-flow { display: grid; min-width: 0; min-height: 320px; gap: 18px; align-content: center; }
.configuration-flow__header { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; align-items: center; }
.configuration-flow__header > div,
.configuration-flow__state > div { display: grid; min-width: 0; gap: 6px; }
.configuration-flow span { color: var(--vp-c-text-3); font-size: 0.72rem; font-weight: 700; }
.configuration-flow strong,
.configuration-flow code { min-width: 0; overflow-wrap: anywhere; }
.configuration-flow__header code { padding: 7px 10px; border-radius: 4px; background: var(--vp-c-brand-soft); color: var(--vp-c-brand-1); }
.configuration-flow__pipeline { display: grid; grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr) 24px minmax(0, 1fr); gap: 8px; align-items: center; }
.configuration-flow__pipeline section,
.configuration-flow__state > div { display: grid; min-width: 0; min-height: 88px; gap: 7px; align-content: center; padding: 13px; border-block: 1px solid var(--atlas-line); background: var(--atlas-surface); }
.configuration-flow__pipeline section:last-of-type { border-left: 4px solid var(--vp-c-brand-1); }
.configuration-flow__pipeline i { color: var(--vp-c-brand-1); font-size: 1.2rem; font-style: normal; text-align: center; }
.configuration-flow__state { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.configuration-flow.is-condition .configuration-flow__header code { color: var(--atlas-coral); background: color-mix(in srgb, var(--atlas-coral) 12%, transparent); }
.configuration-flow.is-import .configuration-flow__header code { color: var(--atlas-purple); }
.configuration-flow.is-enhance .configuration-flow__pipeline section:last-of-type { border-left-color: var(--atlas-gold); }
@media (max-width: 700px) {
  .configuration-flow { min-height: 450px; }
  .configuration-flow__header { grid-template-columns: 1fr; }
  .configuration-flow__header code { justify-self: stretch; }
  .configuration-flow__pipeline { grid-template-columns: 1fr; }
  .configuration-flow__pipeline i { transform: rotate(90deg); }
}
@media (max-width: 420px) {
  .configuration-flow { min-height: 540px; gap: 12px; }
  .configuration-flow__state { grid-template-columns: 1fr; }
  .configuration-flow__pipeline section,
  .configuration-flow__state > div { min-height: 70px; padding: 10px; }
}
</style>
