<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

const steps: SourceAnimationStep[] = [
  {
    title: '准备上下文',
    method: 'prepareRefresh()',
    description: '记录启动时间，切换 active/closed 状态，校验 Environment 必需属性，并创建早期事件集合。'
  },
  {
    title: '获得 BeanFactory',
    method: 'obtainFreshBeanFactory()',
    description: '刷新或校验内部 BeanFactory，再取得 ConfigurableListableBeanFactory；不同 ApplicationContext 子类的刷新策略不同。'
  },
  {
    title: '准备工厂',
    method: 'prepareBeanFactory(beanFactory)',
    description: '安装类加载器、表达式解析器、Aware 处理器、可解析依赖和环境相关单例，但尚未批量创建业务 Bean。'
  },
  {
    title: '执行子类钩子',
    method: 'postProcessBeanFactory(beanFactory)',
    description: '留给具体 ApplicationContext 在标准后处理器运行前继续调整 BeanFactory。'
  },
  {
    title: '改写定义',
    method: 'invokeBeanFactoryPostProcessors(beanFactory)',
    description: '先执行 BeanDefinitionRegistryPostProcessor，再执行 BeanFactoryPostProcessor；配置类解析也发生在这里。'
  },
  {
    title: '注册 Bean 后处理器',
    method: 'registerBeanPostProcessors(beanFactory)',
    description: '按 PriorityOrdered、Ordered 和普通三组注册 BeanPostProcessor，为后续实例化、注入、初始化和代理创建铺路。'
  },
  {
    title: '建立基础设施',
    method: 'initMessageSource() / initApplicationEventMulticaster()',
    description: '查找约定名称的组件；不存在时创建默认 MessageSource 和事件广播器。'
  },
  {
    title: '执行刷新钩子',
    method: 'onRefresh()',
    description: '给子类初始化特定组件的机会；普通 AnnotationConfigApplicationContext 默认不增加业务动作。'
  },
  {
    title: '注册监听器',
    method: 'registerListeners()',
    description: '登记静态监听器和监听器 Bean 名称，并广播此前暂存的早期事件。'
  },
  {
    title: '创建非懒单例',
    method: 'finishBeanFactoryInitialization(beanFactory)',
    description: '冻结 BeanDefinition 配置并 preInstantiateSingletons；大多数业务 Bean 的完整创建链在这一阶段发生。'
  },
  {
    title: '完成刷新',
    method: 'finishRefresh()',
    description: '初始化 LifecycleProcessor、启动生命周期组件并发布 ContextRefreshedEvent，此时上下文才对外处于完整可用状态。'
  },
  {
    title: '成功路径清理公共缓存',
    method: 'resetCommonCaches()',
    description: '本动画快照展示成功路径：finally 清理反射、注解和内省公共缓存。BeansException 的销毁单例与取消刷新支路见正文。'
  }
]

const phases = [
  { title: '上下文状态', method: 'prepareRefresh' },
  { title: '工厂装配', method: 'obtain / prepare' },
  { title: '定义扩展', method: 'BFPP / BPP' },
  { title: '容器设施', method: 'message / event' },
  { title: '单例创建', method: 'preInstantiateSingletons' },
  { title: '完成与清理', method: 'finishRefresh / finally' }
]

const phaseEndSteps = [0, 3, 5, 8, 9, 11]

const snapshots = [
  { context: 'active=true', factory: '尚未取得', definitions: '待读取', singletons: '尚未预实例化', events: 'early events 已准备' },
  { context: '刷新中', factory: '已取得', definitions: '原始定义集合', singletons: '仅基础对象', events: '继续暂存' },
  { context: '刷新中', factory: '标准能力已配置', definitions: '可以继续注册', singletons: '环境对象可用', events: '继续暂存' },
  { context: '刷新中', factory: '子类已调整', definitions: '等待后处理', singletons: '业务 Bean 未批量创建', events: '继续暂存' },
  { context: '刷新中', factory: '定义后处理完成', definitions: '@Bean / 扫描结果就绪', singletons: '少量基础设施可能已创建', events: '继续暂存' },
  { context: '刷新中', factory: 'BPP 链已注册', definitions: '元数据仍可读取', singletons: '创建链已具备', events: '继续暂存' },
  { context: '刷新中', factory: '消息与广播器就绪', definitions: '保持不变', singletons: '基础设施增加', events: '仍加入 early events 暂存' },
  { context: '刷新中', factory: '子类设施就绪', definitions: '保持不变', singletons: '依实现增加', events: '仍暂存，监听器未登记完整' },
  { context: '刷新中', factory: '监听器已登记', definitions: '监听器名称已收集', singletons: '监听器可延迟取得', events: '早期事件已回放' },
  { context: '刷新中', factory: '配置被冻结', definitions: '不再期望变化', singletons: '非懒单例已创建', events: '等待刷新完成事件' },
  { context: '刷新完成', factory: '可对外使用', definitions: '稳定', singletons: 'SmartInitializingSingleton 已回调', events: 'ContextRefreshedEvent 已发布' },
  { context: '刷新完成（成功路径）', factory: '可对外使用', definitions: '保持稳定', singletons: '保持可用', events: '正常广播' }
]
</script>

<template>
  <SourceAnimation title="ApplicationContext.refresh 的完整主干" :steps="steps" :interval="2200">
    <template #visual="{ currentIndex }">
      <div class="spring-refresh">
        <div class="spring-refresh__phases" aria-label="refresh 阶段进度">
          <div
            v-for="(phase, index) in phases"
            :key="phase.title"
            class="spring-refresh__phase"
            :class="{
              'is-active': currentIndex <= phaseEndSteps[index] && (index === 0 || currentIndex > phaseEndSteps[index - 1]),
              'is-complete': currentIndex > phaseEndSteps[index]
            }"
          >
            <span>{{ index + 1 }}</span>
            <strong>{{ phase.title }}</strong>
            <code>{{ phase.method }}</code>
          </div>
        </div>

        <div class="spring-refresh__snapshot" aria-label="当前容器变量快照">
          <div>
            <span>ApplicationContext</span>
            <strong>{{ snapshots[currentIndex].context }}</strong>
          </div>
          <div>
            <span>BeanFactory</span>
            <strong>{{ snapshots[currentIndex].factory }}</strong>
          </div>
          <div>
            <span>BeanDefinition</span>
            <strong>{{ snapshots[currentIndex].definitions }}</strong>
          </div>
          <div>
            <span>Singletons</span>
            <strong>{{ snapshots[currentIndex].singletons }}</strong>
          </div>
          <div>
            <span>Application events</span>
            <strong>{{ snapshots[currentIndex].events }}</strong>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.spring-refresh {
  display: grid;
  grid-template-columns: minmax(300px, 1.35fr) minmax(250px, 1fr);
  gap: 22px;
  align-items: stretch;
  min-height: 250px;
}

.spring-refresh__phases {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.spring-refresh__phase {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 2px 8px;
  align-content: center;
  min-height: 72px;
  padding: 9px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease, transform 180ms ease;
}

.spring-refresh__phase > span {
  grid-row: 1 / 3;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--atlas-line);
  border-radius: 50%;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.spring-refresh__phase strong {
  min-width: 0;
  color: var(--vp-c-text-2);
  font-size: 0.8rem;
}

.spring-refresh__phase code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.spring-refresh__phase.is-active {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
  transform: translateX(3px);
}

.spring-refresh__phase.is-active > span {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.spring-refresh__phase.is-complete {
  border-left-color: var(--vp-c-brand-1);
}

.spring-refresh__phase.is-complete > span {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.spring-refresh__snapshot {
  display: grid;
  align-content: stretch;
  border-top: 1px solid var(--atlas-line);
}

.spring-refresh__snapshot > div {
  display: grid;
  gap: 4px;
  align-content: center;
  min-height: 48px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--atlas-line);
}

.spring-refresh__snapshot span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.spring-refresh__snapshot strong {
  color: var(--atlas-ink);
  font-size: 0.78rem;
  font-weight: 650;
  overflow-wrap: anywhere;
}

@media (max-width: 760px) {
  .spring-refresh {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 460px) {
  .spring-refresh__phases {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spring-refresh__phase {
    transition: none;
  }
}
</style>
