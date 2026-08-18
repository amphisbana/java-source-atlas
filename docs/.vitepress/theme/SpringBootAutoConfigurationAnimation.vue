<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

const steps: SourceAnimationStep[] = [
  {
    title: '发现开启注解',
    method: '@EnableAutoConfiguration',
    description: '配置类解析器发现 @Import(AutoConfigurationImportSelector.class)，但此时只登记延迟选择器，不创建业务 Bean。'
  },
  {
    title: '进入延迟导入',
    method: 'DeferredImportSelector.Group.process(...)',
    description: '普通用户配置先完成解析，AutoConfigurationGroup 再处理各个自动配置选择器入口。'
  },
  {
    title: '加载候选类名',
    method: 'getCandidateConfigurations(...)',
    description: 'Boot 2.7 同时读取 spring.factories 旧来源与 AutoConfiguration.imports 新来源，再合并候选类名。'
  },
  {
    title: '去重并应用排除',
    method: 'removeDuplicates / getExclusions',
    description: '合并注解 exclude、excludeName 与 spring.autoconfigure.exclude；显式排除的候选不会进入后续判断。'
  },
  {
    title: '执行快速过滤',
    method: 'ConfigurationClassFilter.filter(...)',
    description: 'OnClassCondition、OnBeanCondition 与 OnWebApplicationCondition 利用元数据批量剔除明显不匹配的配置。'
  },
  {
    title: '分组合并并排序',
    method: 'AutoConfigurationGroup.selectImports()',
    description: '合并多个入口的候选和排除项，再按 AutoConfigureBefore、After 与 Order 关系生成最终导入顺序。'
  },
  {
    title: '评估配置类条件',
    method: 'SpringBootCondition.matches(...)',
    description: '实验条件读取 atlas.feature.enabled；false 时跳过整个配置，true 时继续注册属性与 Bean 方法定义。'
  },
  {
    title: '判断用户 Bean',
    method: 'OnBeanCondition / @ConditionalOnMissingBean',
    description: '默认服务只在容器尚无同类型用户 Bean 时注册；已有用户实现时自动配置主动回退。'
  },
  {
    title: '绑定并创建 Bean',
    method: 'Binder.bind(...) / createBean(...)',
    description: 'Binder 把 Environment 属性绑定到 AtlasFeatureProperties，IOC 随后用它创建最终默认服务实例。'
  }
]

const stageByStep = [
  'annotation',
  'deferred',
  'candidates',
  'excluded',
  'filtered',
  'ordered',
  'class-condition',
  'bean-condition',
  'registered'
]

const candidates = [
  { name: 'AtlasFeatureAutoConfiguration', outcome: ['等待', '等待', '候选', '保留', '保留', '第 2', '匹配', '匹配', '已注册'] },
  { name: 'JacksonAutoConfiguration', outcome: ['等待', '等待', '候选', '保留', '保留', '第 1', '匹配', '无目标方法', '已导入'] },
  { name: 'WebMvcAutoConfiguration', outcome: ['等待', '等待', '候选', '保留', '类路径不匹配', '已过滤', '已过滤', '已过滤', '未注册'] },
  { name: 'DataSourceAutoConfiguration', outcome: ['等待', '等待', '候选', '显式排除', '已排除', '已排除', '已排除', '已排除', '未注册'] }
]

const candidateTone = [
  ['idle', 'idle', 'candidate', 'kept', 'kept', 'ordered', 'matched', 'matched', 'registered'],
  ['idle', 'idle', 'candidate', 'kept', 'kept', 'ordered', 'matched', 'idle', 'registered'],
  ['idle', 'idle', 'candidate', 'kept', 'filtered', 'filtered', 'filtered', 'filtered', 'filtered'],
  ['idle', 'idle', 'candidate', 'excluded', 'excluded', 'excluded', 'excluded', 'excluded', 'excluded']
]

const snapshots = [
  { source: '尚未读取', environment: '已由 run() 准备', report: '等待候选', bean: '无' },
  { source: '延迟选择器已登记', environment: '可供条件读取', report: '等待候选', bean: '无' },
  { source: 'factories + .imports', environment: '保持不变', report: '候选集合建立', bean: '无' },
  { source: '排除项已合并', environment: '读取 exclude 属性', report: '记录 exclusions', bean: '无' },
  { source: '快速元数据过滤', environment: '辅助 Web 条件', report: '记录评估候选', bean: '无' },
  { source: '有序导入列表', environment: '保持不变', report: '等待详细条件', bean: '无' },
  { source: '配置类元数据', environment: 'enabled=true', report: '类级 match', bean: '属性定义已加入' },
  { source: '@Bean 方法元数据', environment: '保持不变', report: '方法级 match', bean: '默认服务定义已加入' },
  { source: 'BeanDefinition', environment: 'message / repeat', report: '完整匹配', bean: '默认服务实例就绪' }
]
</script>

<template>
  <SourceAnimation title="自动配置候选到默认 Bean 的完整路径" :steps="steps" :interval="2200">
    <template #visual="{ currentIndex }">
      <div class="boot-auto" :data-stage="stageByStep[currentIndex]">
        <div class="boot-auto__pipeline" aria-label="候选配置处理结果">
          <div
            v-for="(candidate, index) in candidates"
            :key="candidate.name"
            class="boot-auto__candidate"
            :class="`is-${candidateTone[index][currentIndex]}`"
          >
            <code>{{ candidate.name }}</code>
            <span>{{ candidate.outcome[currentIndex] }}</span>
          </div>
        </div>

        <div class="boot-auto__decision" aria-label="当前自动配置决策快照">
          <div>
            <span>候选来源</span>
            <strong>{{ snapshots[currentIndex].source }}</strong>
          </div>
          <div>
            <span>Environment</span>
            <strong>{{ snapshots[currentIndex].environment }}</strong>
          </div>
          <div>
            <span>Condition report</span>
            <strong>{{ snapshots[currentIndex].report }}</strong>
          </div>
          <div>
            <span>AtlasGreetingService</span>
            <strong>{{ snapshots[currentIndex].bean }}</strong>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.boot-auto {
  display: grid;
  grid-template-columns: minmax(300px, 1.2fr) minmax(250px, 0.8fr);
  gap: 20px;
  min-height: 250px;
}

.boot-auto__pipeline {
  display: grid;
  gap: 9px;
  align-content: center;
}

.boot-auto__candidate {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px;
  gap: 12px;
  align-items: center;
  min-height: 48px;
  padding: 9px 11px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, opacity 180ms ease, transform 180ms ease;
}

.boot-auto__candidate code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.75rem;
}

.boot-auto__candidate span {
  color: var(--vp-c-text-3);
  font-size: 0.74rem;
  font-weight: 700;
  text-align: right;
}

.boot-auto__candidate.is-candidate,
.boot-auto__candidate.is-kept {
  border-left-color: var(--vp-c-brand-1);
}

.boot-auto__candidate.is-ordered,
.boot-auto__candidate.is-matched,
.boot-auto__candidate.is-registered {
  border-left-color: var(--vp-c-brand-1);
  transform: translateX(3px);
}

.boot-auto__candidate.is-matched,
.boot-auto__candidate.is-registered {
  background: var(--vp-c-brand-soft);
}

.boot-auto__candidate.is-matched span,
.boot-auto__candidate.is-registered span {
  color: var(--vp-c-brand-1);
}

.boot-auto__candidate.is-excluded,
.boot-auto__candidate.is-filtered {
  border-left-color: var(--atlas-coral);
  opacity: 0.58;
}

.boot-auto__candidate.is-excluded span,
.boot-auto__candidate.is-filtered span {
  color: var(--atlas-coral);
}

.boot-auto__decision {
  display: grid;
  align-content: stretch;
  border-top: 1px solid var(--atlas-line);
}

.boot-auto__decision > div {
  display: grid;
  gap: 5px;
  align-content: center;
  min-height: 58px;
  padding: 9px 11px;
  border-bottom: 1px solid var(--atlas-line);
}

.boot-auto__decision span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.boot-auto__decision strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.78rem;
}

@media (max-width: 760px) {
  .boot-auto {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 430px) {
  .boot-auto__candidate {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .boot-auto__candidate span {
    text-align: left;
  }
}

@media (prefers-reduced-motion: reduce) {
  .boot-auto__candidate {
    transition: none;
  }
}
</style>
