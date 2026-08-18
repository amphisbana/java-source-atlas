<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { SourceAnimationStep } from './animation-types'

const props = withDefaults(defineProps<{
  title: string
  steps: SourceAnimationStep[]
  interval?: number
}>(), {
  interval: 1800
})

const currentIndex = ref(0)
const playing = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

const currentStep = computed(() => props.steps[currentIndex.value])
const isFirstStep = computed(() => currentIndex.value === 0)
const isLastStep = computed(() => currentIndex.value === props.steps.length - 1)

/**
 * 停止自动播放并清理计时器，防止页面切换后仍更新已卸载组件。
 */
function stop(): void {
  if (timer !== undefined) {
    clearInterval(timer)
    timer = undefined
  }
  playing.value = false
}

/**
 * 切换到指定步骤，并把越界值限制在合法范围内。
 */
function goToStep(index: number): void {
  currentIndex.value = Math.min(Math.max(index, 0), props.steps.length - 1)
}

/**
 * 返回上一步；手动操作时停止自动播放，避免状态立即被计时器覆盖。
 */
function previous(): void {
  stop()
  goToStep(currentIndex.value - 1)
}

/**
 * 进入下一步；最后一步再次点击时保持当前状态。
 */
function next(): void {
  stop()
  goToStep(currentIndex.value + 1)
}

/**
 * 从头开始自动播放，播放到最后一步后自动停止。
 */
function play(): void {
  if (playing.value) {
    stop()
    return
  }

  if (isLastStep.value) {
    currentIndex.value = 0
  }

  playing.value = true
  timer = setInterval(() => {
    if (isLastStep.value) {
      stop()
      return
    }
    currentIndex.value += 1
  }, props.interval)
}

/**
 * 恢复初始快照，方便重新对照完整调用链。
 */
function reset(): void {
  stop()
  currentIndex.value = 0
}

/**
 * 点击步骤轨道时直接定位到对应源码阶段。
 */
function selectStep(index: number): void {
  stop()
  goToStep(index)
}

onBeforeUnmount(stop)
</script>

<template>
  <section class="source-animation" :aria-label="`${title} 动画演示`">
    <header class="source-animation__header">
      <div>
        <span class="source-animation__eyebrow">源码动态演示</span>
        <h3>{{ title }}</h3>
      </div>
      <span class="source-animation__counter">{{ currentIndex + 1 }} / {{ steps.length }}</span>
    </header>

    <div class="source-animation__track" aria-label="执行步骤">
      <button
        v-for="(step, index) in steps"
        :key="step.title"
        type="button"
        class="source-animation__track-step"
        :class="{
          'is-active': index === currentIndex,
          'is-complete': index < currentIndex
        }"
        :aria-current="index === currentIndex ? 'step' : undefined"
        :aria-label="`第 ${index + 1} 步：${step.title}`"
        :title="`跳到第 ${index + 1} 步：${step.title}`"
        @click="selectStep(index)"
      >
        <span>{{ index + 1 }}</span>
      </button>
    </div>

    <div class="source-animation__stage">
      <slot
        name="visual"
        :current-index="currentIndex"
        :current-step="currentStep"
      />
    </div>

    <div class="source-animation__explanation" aria-live="polite">
      <code>{{ currentStep.method }}</code>
      <strong>{{ currentStep.title }}</strong>
      <p>{{ currentStep.description }}</p>
    </div>

    <footer class="source-animation__controls">
      <button type="button" :disabled="isFirstStep" title="查看上一步" @click="previous">
        ← 上一步
      </button>
      <button type="button" class="source-animation__primary" :aria-pressed="playing" @click="play">
        {{ playing ? '暂停' : '自动播放' }}
      </button>
      <button type="button" :disabled="isLastStep" title="查看下一步" @click="next">
        下一步 →
      </button>
      <button type="button" title="回到第一步" @click="reset">
        重置
      </button>
    </footer>
  </section>
</template>

<style scoped>
.source-animation {
  margin: 24px 0 32px;
  overflow: hidden;
  border: 1px solid var(--atlas-line);
  border-radius: 6px;
  background: var(--vp-c-bg);
}

.source-animation__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--atlas-line);
}

.source-animation__header h3 {
  min-width: 0;
  margin: 3px 0 0;
  font-size: 1rem;
  line-height: 1.4;
}

.source-animation__eyebrow,
.source-animation__counter {
  color: var(--vp-c-text-3);
  font-size: 0.76rem;
  font-weight: 700;
}

.source-animation__counter {
  flex: 0 0 auto;
  white-space: nowrap;
}

.source-animation__track {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(30px, 1fr));
  gap: 0;
  padding: 14px 20px 0;
}

.source-animation__track-step {
  position: relative;
  display: grid;
  place-items: center;
  min-width: 30px;
  height: 28px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--vp-c-text-3);
  cursor: pointer;
}

.source-animation__track-step::before {
  position: absolute;
  z-index: 0;
  top: 13px;
  right: 50%;
  left: -50%;
  height: 2px;
  background: var(--atlas-line);
  content: '';
}

.source-animation__track-step:first-child::before {
  display: none;
}

.source-animation__track-step span {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--atlas-line);
  border-radius: 50%;
  background: var(--vp-c-bg);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.source-animation__track-step.is-complete::before,
.source-animation__track-step.is-active::before {
  background: var(--vp-c-brand-1);
}

.source-animation__track-step.is-complete span {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.source-animation__track-step.is-active span {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-1);
  color: white;
  box-shadow: 0 0 0 4px var(--vp-c-brand-soft);
}

.source-animation__stage {
  min-height: 250px;
  padding: 20px;
}

.source-animation__explanation {
  display: grid;
  grid-template-columns: minmax(150px, 0.6fr) minmax(150px, 0.8fr) minmax(260px, 1.8fr);
  gap: 14px;
  align-items: start;
  min-height: 72px;
  padding: 14px 20px;
  border-top: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.source-animation__explanation code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.78rem;
}

.source-animation__explanation strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 0.9rem;
}

.source-animation__explanation p {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-size: 0.84rem;
  line-height: 1.65;
}

.source-animation__controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 20px 18px;
}

.source-animation__controls button {
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--atlas-line);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
  font: inherit;
  font-size: 0.82rem;
  font-weight: 600;
  cursor: pointer;
}

.source-animation__controls button:hover:not(:disabled) {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.source-animation__controls button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.source-animation__controls .source-animation__primary {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-1);
  color: white;
}

.source-animation__controls .source-animation__primary:hover:not(:disabled) {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-1);
  color: white;
  filter: brightness(1.08);
}

@media (max-width: 720px) {
  .source-animation__header,
  .source-animation__stage,
  .source-animation__explanation,
  .source-animation__controls {
    padding-right: 14px;
    padding-left: 14px;
  }

  .source-animation__track {
    padding-right: 14px;
    padding-left: 14px;
  }

  .source-animation__explanation {
    grid-template-columns: minmax(0, 1fr);
    gap: 6px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .source-animation *,
  .source-animation *::before,
  .source-animation *::after {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
</style>
