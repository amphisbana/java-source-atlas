<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

const steps: SourceAnimationStep[] = [
  {
    title: '提交异步源任务',
    method: 'asyncSupplyStage(ioExecutor, supplier)',
    description: '创建 source，并把 AsyncSupply 提交到 atlas-io。此时 result 仍为 null，阶段尚未完成。'
  },
  {
    title: '注册两个依赖',
    method: 'uniApplyStage(...)',
    description: '普通 UniApply 压入 source.stack，异步 UniApply 依赖它产生的下游；注册只建立图，不执行尚未满足条件的函数。'
  },
  {
    title: '源阶段完成',
    method: 'completeValue(21)',
    description: 'atlas-io 执行 Supplier，并用 CAS 把 source.result 从 null 改为 21，随后进入 postComplete。'
  },
  {
    title: '同步转换为 42',
    method: 'UniApply.tryFire(NESTED)',
    description: '非 Async 的 thenApply 由完成源阶段的 atlas-io 线程继续执行，写入下游结果 42。'
  },
  {
    title: '提交异步转换',
    method: 'executor.execute(completion)',
    description: 'thenApplyAsync 对应节点先提交到 atlas-cpu。最终阶段仍未完成，调用线程此时进入 join 等待。'
  },
  {
    title: '异步转换完成',
    method: 'tryFire(ASYNC); completeValue(...)',
    description: 'atlas-cpu 执行格式化函数并发布 value=42，postComplete 随后触发等待中的 Signaller。'
  },
  {
    title: '唤醒并返回结果',
    method: 'Signaller.tryFire(); reportJoin(result)',
    description: '等待线程被 unpark，恢复读取最终 result，join 解码并返回 value=42。'
  }
]
</script>

<template>
  <SourceAnimation title="21 如何穿过同步与异步依赖得到 value=42" :steps="steps" :interval="2300">
    <template #visual="{ currentIndex }">
      <div class="future-flow">
        <div class="future-flow__executors">
          <div :class="{ 'is-active': currentIndex <= 3 }">
            <span>atlas-io</span>
            <strong>{{ currentIndex === 0 ? 'AsyncSupply 已提交' : currentIndex === 1 ? 'Supplier 等待条件' : currentIndex === 2 ? 'Supplier' : currentIndex === 3 ? 'thenApply' : '空闲' }}</strong>
          </div>
          <div :class="{ 'is-active': currentIndex === 4 || currentIndex === 5 }">
            <span>atlas-cpu</span>
            <strong>{{ currentIndex === 4 ? '异步节点排队' : currentIndex === 5 ? 'thenApplyAsync' : '空闲' }}</strong>
          </div>
          <div :class="{ 'is-active': currentIndex === 0 || currentIndex === 1 || currentIndex === 4 || currentIndex === 6 }">
            <span>调用线程</span>
            <strong>{{ currentIndex === 0 ? '创建 source' : currentIndex === 1 ? '注册依赖' : currentIndex === 4 ? 'join 等待' : currentIndex === 6 ? 'join 返回' : '未执行回调' }}</strong>
          </div>
        </div>

        <div class="future-flow__pipeline" aria-label="CompletableFuture 依赖链">
          <section class="future-stage" :class="{ 'is-complete': currentIndex >= 2, 'is-running': currentIndex === 0 || currentIndex === 2 }">
            <span class="future-stage__kind">source</span>
            <strong>supplyAsync</strong>
            <code>result = {{ currentIndex >= 2 ? '21' : 'null' }}</code>
            <small>{{ currentIndex >= 2 ? '完成' : currentIndex === 0 ? '已提交' : '等待执行' }}</small>
          </section>

          <span class="future-flow__arrow" :class="{ 'is-live': currentIndex >= 1 }">→</span>

          <section class="future-stage" :class="{ 'is-registered': currentIndex >= 1, 'is-complete': currentIndex >= 3, 'is-running': currentIndex === 3 }">
            <span class="future-stage__kind">UniApply</span>
            <strong>value × 2</strong>
            <code>result = {{ currentIndex >= 3 ? '42' : 'null' }}</code>
            <small>{{ currentIndex >= 3 ? '完成' : currentIndex >= 1 ? '依赖已注册' : '尚未创建' }}</small>
          </section>

          <span class="future-flow__arrow" :class="{ 'is-live': currentIndex >= 1 }">→</span>

          <section class="future-stage" :class="{ 'is-registered': currentIndex >= 1, 'is-complete': currentIndex >= 5, 'is-running': currentIndex === 4 || currentIndex === 5 }">
            <span class="future-stage__kind">UniApply + executor</span>
            <strong>格式化文本</strong>
            <code>result = {{ currentIndex >= 5 ? '"value=42"' : 'null' }}</code>
            <small>{{ currentIndex >= 5 ? '完成' : currentIndex === 4 ? '已提交到 atlas-cpu' : currentIndex >= 1 ? '等待上游' : '尚未创建' }}</small>
          </section>
        </div>

        <div class="future-flow__stack">
          <span>Completion 状态</span>
          <strong v-if="currentIndex === 0">尚未注册</strong>
          <strong v-else-if="currentIndex === 1">source.stack → UniApply</strong>
          <strong v-else-if="currentIndex === 2">postComplete 正在弹栈</strong>
          <strong v-else-if="currentIndex === 3">同步 dependent 已完成</strong>
          <strong v-else-if="currentIndex === 4">异步节点已提交，Signaller 等待</strong>
          <strong v-else-if="currentIndex === 5">最终 result 已发布，正在 unpark</strong>
          <strong v-else>链路完成，join = value=42</strong>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.future-flow {
  display: grid;
  gap: 20px;
  min-height: 250px;
}

.future-flow__executors {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
}

.future-flow__executors > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  padding: 8px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  transition: border-color 180ms ease, background 180ms ease, color 180ms ease;
}

.future-flow__executors > div.is-active {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 10%, var(--vp-c-bg));
  color: var(--vp-c-text-1);
}

.future-flow__executors strong {
  overflow-wrap: anywhere;
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.future-flow__pipeline {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) 28px minmax(150px, 1fr) 28px minmax(170px, 1.1fr);
  gap: 8px;
  align-items: center;
}

.future-stage {
  display: grid;
  gap: 7px;
  min-width: 0;
  min-height: 142px;
  padding: 13px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  opacity: 0.4;
  transition: border-color 200ms ease, opacity 200ms ease, transform 200ms ease;
}

.future-stage:first-child,
.future-stage.is-registered {
  opacity: 1;
}

.future-stage.is-running {
  border-color: var(--atlas-coral);
  transform: translateY(-3px);
}

.future-stage.is-complete {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.future-stage__kind {
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.64rem;
}

.future-stage strong {
  font-size: 0.8rem;
}

.future-stage code {
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.7rem;
}

.future-stage small {
  align-self: end;
  color: var(--vp-c-text-3);
  font-size: 0.66rem;
}

.future-flow__arrow {
  color: var(--atlas-line);
  font-size: 1.2rem;
  text-align: center;
  transition: color 180ms ease;
}

.future-flow__arrow.is-live {
  color: var(--vp-c-brand-1);
}

.future-flow__stack {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 16px;
  padding: 10px 12px;
  background: var(--atlas-surface);
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.future-flow__stack strong {
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

@media (max-width: 760px) {
  .future-flow__executors {
    grid-template-columns: 1fr;
  }

  .future-flow__pipeline {
    grid-template-columns: 1fr;
  }

  .future-flow__arrow {
    transform: rotate(90deg);
  }

  .future-stage {
    min-height: 118px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .future-flow__executors > div,
  .future-stage,
  .future-flow__arrow {
    transition: none;
  }
}
</style>
