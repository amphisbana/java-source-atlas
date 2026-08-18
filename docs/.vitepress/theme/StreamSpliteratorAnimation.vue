<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type PipelineTone = 'idle' | 'linked' | 'wrapping' | 'active' | 'rejected' | 'success' | 'stopped'

interface PipelineStage {
  name: string
  detail: string
  tone: PipelineTone
}

interface SourceItem {
  value: string
  tone: PipelineTone
}

interface PipelineSnapshot {
  phase: string
  direction: string
  current: string
  quota: string
  result: string
  stages: PipelineStage[]
  items: SourceItem[]
}

interface SplitNode {
  id: string
  range: string
  detail: string
  tone: 'split' | 'leaf' | 'done'
}

const steps: SourceAnimationStep[] = [
  {
    title: 'source head 只保存数据来源',
    method: 'stream() → Head(sourceSpliterator)',
    description: '创建 source stage 时还没有读取元素；它保存 Spliterator、source flags 和 parallel=false。'
  },
  {
    title: 'filter 连接为新 stage',
    method: 'filter(predicate) → new StatelessOp(previousStage)',
    description: 'filter 记录谓词并清除 SIZED 推断，previous stage 被标为已链接；源元素仍未推进。'
  },
  {
    title: 'map 继续延长 stage 链',
    method: 'map(mapper) → new StatelessOp(previousStage)',
    description: 'map 保存映射函数，并清除 SORTED 与 DISTINCT 推断；没有生成一份中间 List。'
  },
  {
    title: 'limit 加入有状态短路 stage',
    method: 'limit(1) → SliceOps.makeRef(..., 0, 1)',
    description: 'limit stage 只保存 skip=0、limit=1 与 short-circuit 标记；运行期剩余配额要等求值时创建 Slice Sink 才出现。'
  },
  {
    title: 'terminal Sink 从后向前包装',
    method: 'wrapSink: terminal ← limit ← map ← filter',
    description: 'collect 先创建终止 Sink，末 stage 沿 previousStage 反向包装，最终得到最外层 filter Sink。'
  },
  {
    title: '元素 1 被 filter 拒绝',
    method: 'tryAdvance → FilterSink.accept(1)',
    description: '数据从源端正向进入包装链；1 未通过偶数谓词，因此 map、limit 和 terminal 都不会收到它。'
  },
  {
    title: '元素 2 映射为 20 并填满 limit',
    method: 'filter(2) → map(20) → limit.accept → terminal.accept',
    description: '2 通过 filter，映射为 20，terminal 收到结果；limit 的剩余配额由 1 变为 0。'
  },
  {
    title: '取消请求阻止继续拉取',
    method: 'cancellationRequested() == true',
    description: '遍历驱动器在下一次 tryAdvance 前看到取消请求，3 和 4 不再进入 Sink 链，结果稳定为 [20]。'
  },
  {
    title: 'trySplit 生成四个叶分区',
    method: '[0,8) → [0,4)+[4,8) → four leaves',
    description: '独立的 ArrayList 索引示例按中点拆分；每个叶分区只交给一个任务遍历，任务完成先后不固定。'
  },
  {
    title: '叶结果按左到右归并',
    method: 'combine(leftResult, rightResult)',
    description: '叶任务可以乱序完成，但有序 collect 在归并树中保持左结果位于右结果之前，得到 [0,1,2,3,4,5,6,7]。'
  }
]

// 前八个快照固定一条顺序短路流水线，用来区分 stage 组装、Sink 包装和元素推进。
const pipelineSnapshots: PipelineSnapshot[] = [
  {
    phase: '组装 / lazy', direction: '尚未创建 Sink 链', current: 'source=[1,2,3,4]', quota: '-', result: '未求值',
    stages: [
      { name: 'source', detail: 'Head / Spliterator', tone: 'linked' },
      { name: 'filter', detail: '尚未连接', tone: 'idle' },
      { name: 'map', detail: '尚未连接', tone: 'idle' },
      { name: 'limit', detail: '尚未连接', tone: 'idle' },
      { name: 'collect', detail: '尚未调用', tone: 'idle' }
    ],
    items: [
      { value: '1', tone: 'idle' }, { value: '2', tone: 'idle' },
      { value: '3', tone: 'idle' }, { value: '4', tone: 'idle' }
    ]
  },
  {
    phase: '组装 / lazy', direction: 'stage: source → filter', current: 'predicate: even', quota: '-', result: '未求值',
    stages: [
      { name: 'source', detail: 'linkedOrConsumed=true', tone: 'linked' },
      { name: 'filter', detail: 'NOT_SIZED', tone: 'linked' },
      { name: 'map', detail: '尚未连接', tone: 'idle' },
      { name: 'limit', detail: '尚未连接', tone: 'idle' },
      { name: 'collect', detail: '尚未调用', tone: 'idle' }
    ],
    items: [
      { value: '1', tone: 'idle' }, { value: '2', tone: 'idle' },
      { value: '3', tone: 'idle' }, { value: '4', tone: 'idle' }
    ]
  },
  {
    phase: '组装 / lazy', direction: 'stage: source → filter → map', current: 'mapper: x10', quota: '-', result: '未求值',
    stages: [
      { name: 'source', detail: 'Head', tone: 'linked' },
      { name: 'filter', detail: 'even', tone: 'linked' },
      { name: 'map', detail: 'NOT_SORTED / NOT_DISTINCT', tone: 'linked' },
      { name: 'limit', detail: '尚未连接', tone: 'idle' },
      { name: 'collect', detail: '尚未调用', tone: 'idle' }
    ],
    items: [
      { value: '1', tone: 'idle' }, { value: '2', tone: 'idle' },
      { value: '3', tone: 'idle' }, { value: '4', tone: 'idle' }
    ]
  },
  {
    phase: '组装 / lazy', direction: 'stage: source → filter → map → limit', current: 'limit=1 / SHORT_CIRCUIT', quota: '1', result: '未求值',
    stages: [
      { name: 'source', detail: 'Head', tone: 'linked' },
      { name: 'filter', detail: 'even', tone: 'linked' },
      { name: 'map', detail: 'x10', tone: 'linked' },
      { name: 'limit', detail: '配置 skip=0 / limit=1', tone: 'linked' },
      { name: 'collect', detail: '尚未调用', tone: 'idle' }
    ],
    items: [
      { value: '1', tone: 'idle' }, { value: '2', tone: 'idle' },
      { value: '3', tone: 'idle' }, { value: '4', tone: 'idle' }
    ]
  },
  {
    phase: '求值 / wrap', direction: '包装：collect ← limit ← map ← filter；数据方向相反', current: 'Slice Sink 创建 remaining=1', quota: '1', result: '[]',
    stages: [
      { name: 'source', detail: '等待推进', tone: 'linked' },
      { name: 'filter Sink', detail: '最外层', tone: 'wrapping' },
      { name: 'map Sink', detail: '包装 limit', tone: 'wrapping' },
      { name: 'limit Sink', detail: '包装 terminal', tone: 'wrapping' },
      { name: 'terminal', detail: '最内层', tone: 'wrapping' }
    ],
    items: [
      { value: '1', tone: 'idle' }, { value: '2', tone: 'idle' },
      { value: '3', tone: 'idle' }, { value: '4', tone: 'idle' }
    ]
  },
  {
    phase: '求值 / accept', direction: '数据：source → filter；下游未调用', current: 'element=1 / even=false', quota: '1', result: '[]',
    stages: [
      { name: 'source', detail: 'tryAdvance(1)', tone: 'active' },
      { name: 'filter Sink', detail: 'reject 1', tone: 'rejected' },
      { name: 'map Sink', detail: '未收到', tone: 'idle' },
      { name: 'limit Sink', detail: 'remaining=1', tone: 'idle' },
      { name: 'terminal', detail: '[]', tone: 'idle' }
    ],
    items: [
      { value: '1', tone: 'rejected' }, { value: '2', tone: 'idle' },
      { value: '3', tone: 'idle' }, { value: '4', tone: 'idle' }
    ]
  },
  {
    phase: '求值 / accept', direction: '数据：source → filter → map → limit → terminal', current: 'element=2 → mapped=20', quota: '0', result: '[20]',
    stages: [
      { name: 'source', detail: 'tryAdvance(2)', tone: 'active' },
      { name: 'filter Sink', detail: 'accept 2', tone: 'success' },
      { name: 'map Sink', detail: '2 → 20', tone: 'success' },
      { name: 'limit Sink', detail: 'remaining 1 → 0', tone: 'success' },
      { name: 'terminal', detail: 'accept 20', tone: 'success' }
    ],
    items: [
      { value: '1', tone: 'rejected' }, { value: '2', tone: 'success' },
      { value: '3', tone: 'idle' }, { value: '4', tone: 'idle' }
    ]
  },
  {
    phase: '求值 / cancel', direction: '下一轮先检查 cancellationRequested，再决定 tryAdvance', current: 'stop before element=3', quota: '0', result: '[20]',
    stages: [
      { name: 'source', detail: '停止推进', tone: 'stopped' },
      { name: 'filter Sink', detail: '不再调用', tone: 'stopped' },
      { name: 'map Sink', detail: '不再调用', tone: 'stopped' },
      { name: 'limit Sink', detail: 'cancel=true', tone: 'success' },
      { name: 'terminal', detail: '[20]', tone: 'success' }
    ],
    items: [
      { value: '1', tone: 'rejected' }, { value: '2', tone: 'success' },
      { value: '3', tone: 'stopped' }, { value: '4', tone: 'stopped' }
    ]
  }
]

const splitNodes: SplitNode[] = [
  { id: 'root', range: '[0,8)', detail: 'root', tone: 'split' },
  { id: 'left', range: '[0,4)', detail: 'left child', tone: 'split' },
  { id: 'right', range: '[4,8)', detail: 'right child', tone: 'split' },
  { id: 'l0', range: '[0,2)', detail: 'leaf 0', tone: 'leaf' },
  { id: 'l1', range: '[2,4)', detail: 'leaf 1', tone: 'leaf' },
  { id: 'l2', range: '[4,6)', detail: 'leaf 2', tone: 'leaf' },
  { id: 'l3', range: '[6,8)', detail: 'leaf 3', tone: 'leaf' }
]

const completedSplitNodes: SplitNode[] = splitNodes.map(node => ({
  ...node,
  detail: /^l\d$/.test(node.id) ? `${node.range.slice(1, -1)} done` : 'combined',
  tone: 'done'
}))
</script>

<template>
  <SourceAnimation title="Stream 惰性流水线、Sink 短路与 Spliterator 拆分" :steps="steps" :interval="2500">
    <template #visual="{ currentIndex }">
      <div v-if="currentIndex < 8" class="stream-flow">
        <div class="stream-flow__status">
          <span>{{ pipelineSnapshots[currentIndex].phase }}</span>
          <code>{{ pipelineSnapshots[currentIndex].current }}</code>
          <span>limit <strong>{{ pipelineSnapshots[currentIndex].quota }}</strong></span>
          <span>result <strong>{{ pipelineSnapshots[currentIndex].result }}</strong></span>
        </div>

        <div class="stream-flow__source" aria-label="源元素推进状态">
          <span>source</span>
          <strong
            v-for="item in pipelineSnapshots[currentIndex].items"
            :key="item.value"
            :class="[`is-${item.tone}`]"
          >
            {{ item.value }}
          </strong>
        </div>

        <div class="stream-flow__direction">
          <code>{{ pipelineSnapshots[currentIndex].direction }}</code>
        </div>

        <div class="stream-flow__stages" aria-label="Stream stage 与 Sink 链">
          <div
            v-for="(stage, index) in pipelineSnapshots[currentIndex].stages"
            :key="stage.name"
            class="stream-stage"
            :class="[`is-${stage.tone}`]"
          >
            <small>{{ index === 0 ? 'SOURCE' : index === 4 ? 'TERMINAL' : `STAGE ${index}` }}</small>
            <strong>{{ stage.name }}</strong>
            <span>{{ stage.detail }}</span>
          </div>
        </div>

        <div class="stream-flow__rule">
          <code>{{ currentIndex < 4 ? '只记录 stage，不读取 source' : 'Sink 反向包装，元素正向流动' }}</code>
          <span>{{ currentIndex === 7 ? '取消是下一次推进前的协作检查，不回滚已经接收的 20' : '每个元素沿同一条融合调用链向下游传递' }}</span>
        </div>
      </div>

      <div v-else class="split-flow">
        <div class="split-flow__status">
          <span>独立示例 <strong>ArrayList indices [0,8)</strong></span>
          <code>{{ currentIndex === 8 ? 'trySplit: prefix + remainder' : 'ordered collect: left + right' }}</code>
        </div>

        <div class="split-tree" aria-label="Spliterator 二叉拆分树">
          <div
            v-for="node in currentIndex === 8 ? splitNodes : completedSplitNodes"
            :key="node.id"
            class="split-node"
            :class="[`is-${node.id}`, `is-${node.tone}`]"
          >
            <strong>{{ node.range }}</strong>
            <span>{{ node.detail }}</span>
          </div>
        </div>

        <div class="split-flow__workers" aria-label="叶分区执行与归并结果">
          <div v-for="(range, index) in ['[0,2)', '[2,4)', '[4,6)', '[6,8)']" :key="range">
            <small>leaf {{ index }}</small>
            <strong>{{ range }}</strong>
            <span>{{ currentIndex === 8 ? '可独立执行' : `[${index * 2},${index * 2 + 1}]` }}</span>
          </div>
        </div>

        <div class="split-flow__combine">
          <code v-if="currentIndex === 8">任务完成顺序可以是 L3 → L0 → L2 → L1</code>
          <template v-else>
            <code>[0,1] + [2,3] → [0,1,2,3]</code>
            <code>[4,5] + [6,7] → [4,5,6,7]</code>
            <strong>root → [0,1,2,3,4,5,6,7]</strong>
          </template>
        </div>

        <p>拆分树与任务调度是两层：trySplit 只划分范围，Fork/Join 再决定由哪个 worker 执行。</p>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.stream-flow,
.split-flow {
  display: grid;
  gap: 16px;
  min-width: 0;
  min-height: 360px;
}

.stream-flow__status,
.split-flow__status {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 7px 16px;
  align-items: center;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.stream-flow__status code,
.split-flow__status code {
  min-width: 0;
  margin-right: auto;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.7rem;
}

.stream-flow__status strong,
.split-flow__status strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.stream-flow__source {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 7px;
  align-items: center;
}

.stream-flow__source > span {
  margin-right: 4px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.stream-flow__source > strong {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.78rem;
  transition: border-color 220ms ease, background-color 220ms ease, opacity 220ms ease;
}

.stream-flow__source > strong.is-active {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.stream-flow__source > strong.is-rejected {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
  text-decoration: line-through;
}

.stream-flow__source > strong.is-success {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-1);
  color: white;
}

.stream-flow__source > strong.is-stopped {
  border-style: dashed;
  opacity: 0.42;
}

.stream-flow__direction {
  min-width: 0;
  padding: 9px 11px;
  border-left: 3px solid var(--vp-c-brand-1);
  background: var(--atlas-surface);
}

.stream-flow__direction code {
  display: block;
  max-width: 100%;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.7rem;
  line-height: 1.55;
}

.stream-flow__stages {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 7px;
  min-width: 0;
}

.stream-stage {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 96px;
  align-content: center;
  gap: 4px;
  padding: 8px;
  border: 1px dashed var(--atlas-line);
  background: transparent;
  color: var(--vp-c-text-3);
  text-align: center;
  transition: border-color 220ms ease, background-color 220ms ease, color 220ms ease;
}

.stream-stage:not(:last-child)::after {
  position: absolute;
  z-index: 1;
  top: 50%;
  right: -7px;
  color: var(--atlas-line);
  content: '›';
  font-size: 0.82rem;
  transform: translate(50%, -50%);
}

.stream-stage small,
.stream-stage strong,
.stream-stage span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.stream-stage small {
  font-size: 0.55rem;
}

.stream-stage strong {
  color: inherit;
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.stream-stage span {
  font-size: 0.6rem;
  line-height: 1.35;
}

.stream-stage.is-linked,
.stream-stage.is-wrapping {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.stream-stage.is-wrapping {
  background: var(--vp-c-brand-soft);
}

.stream-stage.is-active {
  border-style: solid;
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
  animation: stream-focus 300ms ease-out both;
}

.stream-stage.is-rejected {
  border-style: solid;
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
  color: var(--atlas-coral);
}

.stream-stage.is-success {
  border-style: solid;
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.stream-stage.is-stopped {
  opacity: 0.48;
}

.stream-flow__rule {
  display: grid;
  grid-template-columns: minmax(170px, 0.8fr) minmax(220px, 1.4fr);
  gap: 12px;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
}

.stream-flow__rule code {
  color: var(--vp-c-brand-1);
  font-size: 0.69rem;
}

.stream-flow__rule span,
.split-flow > p {
  margin: 0;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  line-height: 1.55;
}

.split-tree {
  display: grid;
  grid-template-areas:
    '. . root root . . . .'
    '. left left . . right right .'
    'l0 l0 l1 l1 l2 l2 l3 l3';
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 8px;
  min-width: 0;
  min-height: 164px;
}

.split-node {
  display: grid;
  min-width: 0;
  min-height: 46px;
  place-items: center;
  padding: 6px;
  border: 1px dashed var(--atlas-line);
  color: var(--vp-c-text-3);
  text-align: center;
  transition: border-color 220ms ease, background-color 220ms ease, color 220ms ease;
}

.split-node strong,
.split-node span {
  max-width: 100%;
  overflow-wrap: anywhere;
}

.split-node strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.split-node span {
  font-size: 0.58rem;
}

.split-node.is-root { grid-area: root; }
.split-node.is-left { grid-area: left; }
.split-node.is-right { grid-area: right; }
.split-node.is-l0 { grid-area: l0; }
.split-node.is-l1 { grid-area: l1; }
.split-node.is-l2 { grid-area: l2; }
.split-node.is-l3 { grid-area: l3; }

.split-node.is-split {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.split-node.is-leaf {
  border-style: solid;
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
  animation: stream-focus 320ms ease-out both;
}

.split-node.is-done {
  border-style: solid;
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.split-flow__workers {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.split-flow__workers > div {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 8px;
  border-left: 3px solid var(--vp-c-brand-1);
  background: var(--atlas-surface);
}

.split-flow__workers small,
.split-flow__workers span {
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-size: 0.6rem;
}

.split-flow__workers strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.69rem;
}

.split-flow__combine {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 7px 16px;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
}

.split-flow__combine code,
.split-flow__combine strong {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.68rem;
}

@keyframes stream-focus {
  from { opacity: 0.55; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 720px) {
  .stream-flow__stages {
    grid-template-columns: 1fr;
  }

  .stream-stage {
    min-height: 72px;
    grid-template-columns: 64px minmax(72px, 0.7fr) minmax(90px, 1.3fr);
    align-items: center;
    text-align: left;
  }

  .stream-stage:not(:last-child)::after {
    top: auto;
    right: 50%;
    bottom: -8px;
    transform: translate(50%, 50%) rotate(90deg);
  }

  .stream-flow__rule {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .split-flow__workers {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 390px) {
  .stream-flow,
  .split-flow {
    gap: 12px;
  }

  .stream-flow__status,
  .split-flow__status {
    align-items: flex-start;
    flex-direction: column;
  }

  .stream-flow__status code,
  .split-flow__status code {
    margin-right: 0;
  }

  .stream-stage {
    grid-template-columns: minmax(52px, 0.7fr) minmax(0, 1.3fr);
    min-height: 78px;
    padding: 7px;
  }

  .stream-stage span {
    grid-column: 1 / -1;
  }

  .split-tree {
    grid-template-areas:
      'root root root root'
      'left left right right'
      'l0 l1 l2 l3';
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 6px;
  }

  .split-node {
    padding: 4px 2px;
  }

  .split-node span {
    display: none;
  }

  .split-flow__workers {
    grid-template-columns: 1fr 1fr;
    gap: 6px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .stream-stage,
  .stream-flow__source > strong,
  .split-node {
    animation: none;
    transition: none;
  }
}
</style>
