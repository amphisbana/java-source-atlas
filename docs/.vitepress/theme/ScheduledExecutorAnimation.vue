<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface TimelineRun {
  label: string
  start: number
  end: number
  planned: number
}

interface ScheduledSnapshot {
  clock: number
  rateRuns: TimelineRun[]
  delayRuns: TimelineRun[]
  heap: string[]
  leader: string
  followers: string[]
  queueEvent: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '创建两种周期任务',
    method: 'period=+3 / period=-3',
    description: '固定频率保存正 period，固定延迟保存负 period；两者第一轮都计划在 t=0。'
  },
  {
    title: '第一轮开始执行',
    method: 'ScheduledFutureTask.run()',
    description: '两种任务各自开始第一轮。示例每轮占用 5 个时间单位，比周期或延迟 3 更长。'
  },
  {
    title: '固定频率错过计划点',
    method: 'time(0) + period(3)',
    description: 't=3 时第一轮仍未结束。第二轮尚未入队，更不会由另一个 worker 并发执行。'
  },
  {
    title: '第一轮完成后计算下一次',
    method: 'runAndReset() → setNextRunTime()',
    description: 't=5 正常返回后，固定频率下一计划点仍是 t=3；固定延迟则从完成时刻计算 t=8。'
  },
  {
    title: '固定频率立即追赶',
    method: 'reExecutePeriodic(outerTask)',
    description: '固定频率第二轮在 t=5 才入队并立即执行。它追赶已错过的 t=3，但与第一轮没有重叠。'
  },
  {
    title: '固定延迟等待后再运行',
    method: 'triggerTime(-period)',
    description: '固定延迟第二轮到 t=8 才开始，确保第一轮完成后完整等待 3 个时间单位。'
  },
  {
    title: '多个远期任务进入最小堆',
    method: 'DelayedWorkQueue.offer()',
    description: 'A(t=6)、B(t=9)、C(t=14) 按触发时刻组成最小堆；W1 只等待堆首 A，W2 作为 follower 无限等待。'
  },
  {
    title: '更早任务替换堆首',
    method: 'offer(D@3) → leader=null → signal()',
    description: 'D 上浮到堆首后，旧 leader 计划失效；一个等待线程被唤醒并按 D 的剩余时间重新等待。'
  },
  {
    title: '堆首到期并交接 leader',
    method: 'take() → finishPoll(D)',
    description: 'D 到期后被取出，返回前唤醒 follower；剩余线程重新选择 leader 等待下一堆首 A。'
  }
]

const snapshots: ScheduledSnapshot[] = [
  { clock: 0, rateRuns: [], delayRuns: [], heap: [], leader: '无', followers: ['W1', 'W2'], queueEvent: '等待提交' },
  { clock: 0, rateRuns: [{ label: 'R1', start: 0, end: 5, planned: 0 }], delayRuns: [{ label: 'D1', start: 0, end: 5, planned: 0 }], heap: [], leader: '无', followers: [], queueEvent: '两条任务分别由 worker 执行' },
  { clock: 3, rateRuns: [{ label: 'R1', start: 0, end: 5, planned: 0 }], delayRuns: [{ label: 'D1', start: 0, end: 5, planned: 0 }], heap: [], leader: '无', followers: [], queueEvent: 't=3：没有生成并发的 R2' },
  { clock: 5, rateRuns: [{ label: 'R1', start: 0, end: 5, planned: 0 }], delayRuns: [{ label: 'D1', start: 0, end: 5, planned: 0 }], heap: ['R2@3（已到期）', 'D2@8'], leader: '待选', followers: [], queueEvent: '当前轮完成后才重新入堆' },
  { clock: 6, rateRuns: [{ label: 'R1', start: 0, end: 5, planned: 0 }, { label: 'R2', start: 5, end: 10, planned: 3 }], delayRuns: [{ label: 'D1', start: 0, end: 5, planned: 0 }], heap: ['D2@8'], leader: 'W2', followers: [], queueEvent: 'R2 实际 t=5 开始，追赶计划点 t=3' },
  { clock: 8, rateRuns: [{ label: 'R1', start: 0, end: 5, planned: 0 }, { label: 'R2', start: 5, end: 10, planned: 3 }], delayRuns: [{ label: 'D1', start: 0, end: 5, planned: 0 }, { label: 'D2', start: 8, end: 13, planned: 8 }], heap: [], leader: '无', followers: [], queueEvent: 'D2 在完成后等待 3 个单位' },
  { clock: 0, rateRuns: [], delayRuns: [], heap: ['A@6', 'B@9', 'C@14'], leader: 'W1 → A@6', followers: ['W2'], queueEvent: '只有 leader 做定时等待' },
  { clock: 1, rateRuns: [], delayRuns: [], heap: ['D@3', 'A@6', 'C@14', 'B@9'], leader: '重新竞选 → D@3', followers: ['另一 worker'], queueEvent: '新堆首使旧 leader 失效' },
  { clock: 3, rateRuns: [], delayRuns: [], heap: ['A@6', 'B@9', 'C@14'], leader: 'W2 → A@6', followers: ['W1 执行 D'], queueEvent: 'finishPoll 后交接下一次等待' }
]

const ticks = Array.from({ length: 16 }, (_, index) => index)

/**
 * 把执行区间映射为固定时间轴上的网格起点。
 */
function runStart(run: TimelineRun): number {
  return run.start + 2
}

/**
 * 把执行区间映射为固定时间轴上的网格终点，CSS 网格终点不包含自身。
 */
function runEnd(run: TimelineRun): number {
  return run.end + 2
}

/**
 * 把 0..15 的刻度点放到十五个时间区间的边界上。
 */
function tickPosition(tick: number): string {
  return `${tick * 100 / 15}%`
}
</script>

<template>
  <SourceAnimation title="周期任务时间线与延迟堆 leader 交接" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="scheduled-demo">
        <div class="scheduled-demo__status">
          <span>当前时刻 <strong>t={{ snapshots[currentIndex].clock }}</strong></span>
          <code>{{ snapshots[currentIndex].queueEvent }}</code>
        </div>

        <section class="timeline" aria-label="固定频率与固定延迟执行时间线">
          <div class="timeline__scroller">
            <div class="timeline__canvas">
              <div class="timeline__ticks">
                <span class="timeline__axis-label">时间</span>
                <div class="timeline__tick-track">
                  <span
                    v-for="tick in ticks"
                    :key="tick"
                    :class="{ 'is-first': tick === 0, 'is-last': tick === 15 }"
                    :style="{ left: tickPosition(tick) }"
                  >{{ tick }}</span>
                </div>
              </div>

              <div class="timeline__row">
                <strong class="timeline__row-label">固定频率</strong>
                <div
                  v-for="run in snapshots[currentIndex].rateRuns"
                  :key="`rate-${run.label}`"
                  class="timeline__run is-rate"
                  :style="{ gridColumn: `${runStart(run)} / ${runEnd(run)}` }"
                  :title="`${run.label}：计划 t=${run.planned}，实际 t=${run.start}..${run.end}`"
                >
                  {{ run.label }} <small>计划 {{ run.planned }}</small>
                </div>
              </div>

              <div class="timeline__row">
                <strong class="timeline__row-label">固定延迟</strong>
                <div
                  v-for="run in snapshots[currentIndex].delayRuns"
                  :key="`delay-${run.label}`"
                  class="timeline__run is-delay"
                  :style="{ gridColumn: `${runStart(run)} / ${runEnd(run)}` }"
                  :title="`${run.label}：计划 t=${run.planned}，实际 t=${run.start}..${run.end}`"
                >
                  {{ run.label }} <small>计划 {{ run.planned }}</small>
                </div>
              </div>

              <div class="timeline__now" :style="{ left: `calc(92px + (100% - 104px) * ${snapshots[currentIndex].clock} / 15)` }">
                <span>now</span>
              </div>
            </div>
          </div>
          <p>每个色块是一轮实际执行；同一行的色块不会重叠。</p>
        </section>

        <section class="delay-queue" aria-label="延迟堆和等待线程">
          <div>
            <span class="delay-queue__label">DelayedWorkQueue（堆数组示意）</span>
            <div class="delay-queue__heap">
              <strong v-for="(task, index) in snapshots[currentIndex].heap" :key="task" :class="{ 'is-head': index === 0 }">
                {{ index === 0 ? '堆首 ' : '' }}{{ task }}
              </strong>
              <span v-if="snapshots[currentIndex].heap.length === 0">当前没有等待任务</span>
            </div>
          </div>

          <div class="delay-queue__waiters">
            <span class="delay-queue__label">等待角色</span>
            <strong class="is-leader">leader：{{ snapshots[currentIndex].leader }}</strong>
            <span v-for="follower in snapshots[currentIndex].followers" :key="follower">follower：{{ follower }}</span>
            <span v-if="snapshots[currentIndex].followers.length === 0">无 follower 等待</span>
          </div>
        </section>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.scheduled-demo {
  display: grid;
  gap: 18px;
  min-width: 0;
  min-height: 310px;
}

.scheduled-demo__status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  align-items: center;
  justify-content: space-between;
  color: var(--vp-c-text-3);
  font-size: 0.75rem;
}

.scheduled-demo__status strong,
.scheduled-demo__status code {
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
}

.timeline {
  min-width: 0;
}

.timeline__scroller {
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.timeline__canvas {
  position: relative;
  display: grid;
  gap: 8px;
  min-width: 650px;
  padding: 12px;
}

.timeline__row {
  display: grid;
  grid-template-columns: 80px repeat(15, minmax(24px, 1fr));
  gap: 0;
}

.timeline__ticks {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr);
}

.timeline__ticks > span,
.timeline__tick-track span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.64rem;
  text-align: center;
}

.timeline__ticks .timeline__axis-label,
.timeline__row-label {
  text-align: left;
}

.timeline__tick-track {
  position: relative;
  min-width: 0;
  height: 14px;
}

.timeline__tick-track span {
  position: absolute;
  transform: translateX(-50%);
}

.timeline__tick-track span.is-first {
  transform: none;
}

.timeline__tick-track span.is-last {
  transform: translateX(-100%);
}

.timeline__row {
  min-height: 50px;
  align-items: stretch;
  background-image: repeating-linear-gradient(
    to right,
    transparent 0,
    transparent calc((100% - 80px) / 15 - 1px),
    var(--atlas-line) calc((100% - 80px) / 15 - 1px),
    var(--atlas-line) calc((100% - 80px) / 15)
  );
  background-position-x: 80px;
}

.timeline__row-label {
  z-index: 1;
  display: grid;
  grid-column: 1;
  grid-row: 1;
  align-items: center;
  padding-right: 8px;
  background: var(--atlas-surface);
  color: var(--atlas-ink);
  font-size: 0.72rem;
}

.timeline__run {
  z-index: 1;
  display: grid;
  grid-row: 1;
  place-content: center;
  min-width: 0;
  margin: 5px 1px;
  border: 1px solid;
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  line-height: 1.2;
  text-align: center;
  animation: scheduled-enter 300ms ease-out both;
}

.timeline__run small {
  font-size: 0.58rem;
}

.timeline__run.is-rate {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.timeline__run.is-delay {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 10%, transparent);
  color: var(--atlas-coral);
}

.timeline__now {
  position: absolute;
  z-index: 2;
  top: 30px;
  bottom: 12px;
  width: 2px;
  background: var(--atlas-ink);
  pointer-events: none;
  transform: translateX(-50%);
  transition: left 260ms ease;
}

.timeline__now span {
  position: absolute;
  top: -15px;
  left: 4px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.58rem;
}

.timeline p {
  margin: 7px 0 0;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.delay-queue {
  display: grid;
  grid-template-columns: minmax(260px, 1.4fr) minmax(190px, 0.8fr);
  gap: 14px;
}

.delay-queue > div {
  min-width: 0;
}

.delay-queue__label {
  display: block;
  margin-bottom: 7px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.delay-queue__heap {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 5px;
  min-height: 48px;
}

.delay-queue__heap strong,
.delay-queue__heap span,
.delay-queue__waiters strong,
.delay-queue__waiters > span:not(.delay-queue__label) {
  display: grid;
  place-items: center;
  min-height: 42px;
  padding: 5px;
  border: 1px solid var(--atlas-line);
  color: var(--vp-c-text-2);
  font-size: 0.68rem;
  overflow-wrap: anywhere;
  text-align: center;
}

.delay-queue__heap strong.is-head {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.delay-queue__waiters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px;
}

.delay-queue__waiters .delay-queue__label {
  grid-column: 1 / -1;
  margin: 0;
}

.delay-queue__waiters strong.is-leader {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

@keyframes scheduled-enter {
  from { opacity: 0; transform: scaleX(0.9); }
  to { opacity: 1; transform: scaleX(1); }
}

@media (max-width: 720px) {
  .scheduled-demo__status {
    align-items: flex-start;
    flex-direction: column;
  }

  .delay-queue {
    grid-template-columns: 1fr;
  }

  .delay-queue__heap {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
