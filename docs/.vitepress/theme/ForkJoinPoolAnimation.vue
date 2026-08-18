<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface TreeNode {
  id: string
  label: string
  state: 'idle' | 'queued' | 'running' | 'done' | 'helped' | 'waiting'
}

interface QueueSnapshot {
  base: number
  top: number
  slots: string[]
  action: string
}

interface ForkJoinSnapshot {
  external: string
  nodes: TreeNode[]
  w1: QueueSnapshot
  w2: QueueSnapshot
  w1Current: string
  w2Current: string
  result: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '外部提交 Root',
    method: 'pool.invoke(Root) → externalPush(Root)',
    description: '普通线程把根任务写入共享 submission queue；它不是任何 worker 的本地 top。'
  },
  {
    title: 'W1 取得根任务',
    method: 'scan → sharedQueue.pollAt(base)',
    description: 'W1 从共享队列 base 端取得 Root，开始执行分治计算。'
  },
  {
    title: 'W1 fork Right，直算 Left',
    method: 'Right.fork() → W1.push(top)',
    description: 'Right 发布到 W1 的 top；W1 不把 Left 入队，直接计算 Left。'
  },
  {
    title: 'W2 从 base 窃取 Right',
    method: 'W2 pollAt(W1.base)',
    description: '空闲 W2 从最老一端取得 Right。槽位 CAS 成功后，W1 的 base 前进。'
  },
  {
    title: 'W2 fork R2，直算 R1',
    method: 'R2.fork() → W2.push(top)',
    description: 'Right 继续拆分。W2 把 R2 留在本地队列，自己执行 R1。'
  },
  {
    title: 'W1 join Right 并开始帮助',
    method: 'awaitJoin → helpStealer',
    description: 'Left 已完成，但 Right 在 W2 手中；W1 沿 currentSteal/currentJoin 找到相关工作。'
  },
  {
    title: 'W1 窃取 R2 帮助收口',
    method: 'pollAt(W2.base) → R2.doExec()',
    description: 'W1 从 W2 的 base 取得 R2 并完成它；joiner 没有闲等，而是在执行目标后代。'
  },
  {
    title: 'Right 与 Root 依次完成',
    method: 'R1 + R2 → Right；Left + Right → Root',
    description: '在这条合法路径中，W2 汇总 Right，W1 的 join 观察完成后汇总 Root；其他运行可能采用不同调度。'
  }
]

const snapshots: ForkJoinSnapshot[] = [
  {
    external: 'Root',
    nodes: [
      { id: 'root', label: 'Root', state: 'queued' }, { id: 'left', label: 'Left', state: 'idle' },
      { id: 'right', label: 'Right', state: 'idle' }, { id: 'r1', label: 'R1', state: 'idle' },
      { id: 'r2', label: 'R2', state: 'idle' }
    ],
    w1: { base: 0, top: 0, slots: [], action: '扫描共享队列' },
    w2: { base: 0, top: 0, slots: [], action: '扫描任务' },
    w1Current: '无', w2Current: '无', result: '等待 Root'
  },
  {
    external: '空',
    nodes: [
      { id: 'root', label: 'Root', state: 'running' }, { id: 'left', label: 'Left', state: 'idle' },
      { id: 'right', label: 'Right', state: 'idle' }, { id: 'r1', label: 'R1', state: 'idle' },
      { id: 'r2', label: 'R2', state: 'idle' }
    ],
    w1: { base: 0, top: 0, slots: [], action: '执行 Root' },
    w2: { base: 0, top: 0, slots: [], action: '空闲扫描' },
    w1Current: 'Root', w2Current: '无', result: '开始拆分'
  },
  {
    external: '空',
    nodes: [
      { id: 'root', label: 'Root', state: 'running' }, { id: 'left', label: 'Left', state: 'running' },
      { id: 'right', label: 'Right', state: 'queued' }, { id: 'r1', label: 'R1', state: 'idle' },
      { id: 'r2', label: 'R2', state: 'idle' }
    ],
    w1: { base: 0, top: 1, slots: ['Right'], action: 'owner push top；直算 Left' },
    w2: { base: 0, top: 0, slots: [], action: '选择 victim' },
    w1Current: 'Left', w2Current: '无', result: 'Right 可被窃取'
  },
  {
    external: '空',
    nodes: [
      { id: 'root', label: 'Root', state: 'running' }, { id: 'left', label: 'Left', state: 'running' },
      { id: 'right', label: 'Right', state: 'running' }, { id: 'r1', label: 'R1', state: 'idle' },
      { id: 'r2', label: 'R2', state: 'idle' }
    ],
    w1: { base: 1, top: 1, slots: [], action: 'base 被 W2 推进' },
    w2: { base: 0, top: 0, slots: [], action: '执行偷到的 Right' },
    w1Current: 'Left', w2Current: 'Right', result: '左右并行'
  },
  {
    external: '空',
    nodes: [
      { id: 'root', label: 'Root', state: 'running' }, { id: 'left', label: 'Left', state: 'done' },
      { id: 'right', label: 'Right', state: 'running' }, { id: 'r1', label: 'R1', state: 'running' },
      { id: 'r2', label: 'R2', state: 'queued' }
    ],
    w1: { base: 1, top: 1, slots: [], action: '准备 join Right' },
    w2: { base: 0, top: 1, slots: ['R2'], action: 'owner push top；直算 R1' },
    w1Current: 'Root', w2Current: 'R1', result: 'Left 已完成'
  },
  {
    external: '空',
    nodes: [
      { id: 'root', label: 'Root', state: 'waiting' }, { id: 'left', label: 'Left', state: 'done' },
      { id: 'right', label: 'Right', state: 'running' }, { id: 'r1', label: 'R1', state: 'running' },
      { id: 'r2', label: 'R2', state: 'queued' }
    ],
    w1: { base: 1, top: 1, slots: [], action: 'helpStealer 查找 W2 后代' },
    w2: { base: 0, top: 1, slots: ['R2'], action: '继续计算 R1' },
    w1Current: 'join Right', w2Current: 'R1', result: '先帮助，再考虑阻塞'
  },
  {
    external: '空',
    nodes: [
      { id: 'root', label: 'Root', state: 'waiting' }, { id: 'left', label: 'Left', state: 'done' },
      { id: 'right', label: 'Right', state: 'running' }, { id: 'r1', label: 'R1', state: 'done' },
      { id: 'r2', label: 'R2', state: 'helped' }
    ],
    w1: { base: 1, top: 1, slots: [], action: '执行从 W2.base 偷到的 R2' },
    w2: { base: 1, top: 1, slots: [], action: 'base 被 W1 推进' },
    w1Current: 'R2（帮助）', w2Current: 'Right 汇总', result: '两个叶子完成'
  },
  {
    external: '空',
    nodes: [
      { id: 'root', label: 'Root', state: 'done' }, { id: 'left', label: 'Left', state: 'done' },
      { id: 'right', label: 'Right', state: 'done' }, { id: 'r1', label: 'R1', state: 'done' },
      { id: 'r2', label: 'R2', state: 'done' }
    ],
    w1: { base: 1, top: 1, slots: [], action: 'join 返回并汇总 Root' },
    w2: { base: 1, top: 1, slots: [], action: 'Right 正常完成' },
    w1Current: 'Root 完成', w2Current: '无', result: 'Root = Left + Right'
  }
]
</script>

<template>
  <SourceAnimation title="分治任务如何被 owner、stealer 与 joiner 协作完成" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="forkjoin-demo">
        <div class="forkjoin-demo__status">
          <span>共享提交队列 <strong>{{ snapshots[currentIndex].external }}</strong></span>
          <span class="forkjoin-demo__path-note">合法调度路径示例，非固定顺序</span>
          <code>{{ snapshots[currentIndex].result }}</code>
        </div>

        <section class="task-tree" aria-label="ForkJoin 分治任务树">
          <div
            v-for="node in snapshots[currentIndex].nodes"
            :key="node.id"
            class="task-tree__node"
            :class="[`is-${node.state}`, `is-${node.id}`]"
          >
            <strong>{{ node.label }}</strong>
            <span>{{ node.state === 'helped' ? '帮助执行' : node.state }}</span>
          </div>
        </section>

        <section class="worker-grid" aria-label="两个 worker 的本地 WorkQueue">
          <div class="worker">
            <header>
              <strong>W1</strong>
              <span>当前：{{ snapshots[currentIndex].w1Current }}</span>
            </header>
            <div class="worker__indices">
              <code>base={{ snapshots[currentIndex].w1.base }}</code>
              <code>top={{ snapshots[currentIndex].w1.top }}</code>
            </div>
            <div class="worker__queue">
              <span class="worker__edge">base →</span>
              <strong v-for="task in snapshots[currentIndex].w1.slots" :key="task">{{ task }}</strong>
              <span v-if="snapshots[currentIndex].w1.slots.length === 0" class="worker__empty">空</span>
              <span class="worker__edge">← top</span>
            </div>
            <p>{{ snapshots[currentIndex].w1.action }}</p>
          </div>

          <div class="worker">
            <header>
              <strong>W2</strong>
              <span>当前：{{ snapshots[currentIndex].w2Current }}</span>
            </header>
            <div class="worker__indices">
              <code>base={{ snapshots[currentIndex].w2.base }}</code>
              <code>top={{ snapshots[currentIndex].w2.top }}</code>
            </div>
            <div class="worker__queue">
              <span class="worker__edge">base →</span>
              <strong v-for="task in snapshots[currentIndex].w2.slots" :key="task">{{ task }}</strong>
              <span v-if="snapshots[currentIndex].w2.slots.length === 0" class="worker__empty">空</span>
              <span class="worker__edge">← top</span>
            </div>
            <p>{{ snapshots[currentIndex].w2.action }}</p>
          </div>
        </section>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.forkjoin-demo {
  display: grid;
  gap: 18px;
  min-width: 0;
  min-height: 340px;
}

.forkjoin-demo__status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  align-items: center;
  justify-content: space-between;
  color: var(--vp-c-text-3);
  font-size: 0.74rem;
}

.forkjoin-demo__status strong,
.forkjoin-demo__status code {
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
}

.forkjoin-demo__path-note {
  color: var(--atlas-coral);
  font-weight: 700;
}

.task-tree {
  display: grid;
  grid-template-areas:
    '. root root .'
    'left left right right'
    '. . r1 r2';
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 7px;
  min-height: 154px;
}

.task-tree__node {
  display: grid;
  place-items: center;
  min-width: 0;
  min-height: 44px;
  padding: 5px;
  border: 1px dashed var(--atlas-line);
  background: transparent;
  color: var(--vp-c-text-3);
  transition: border-color 220ms ease, background-color 220ms ease, color 220ms ease;
}

.task-tree__node strong,
.task-tree__node span {
  max-width: 100%;
  overflow-wrap: anywhere;
  text-align: center;
}

.task-tree__node strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
}

.task-tree__node span {
  font-size: 0.6rem;
}

.task-tree__node.is-root { grid-area: root; }
.task-tree__node.is-left { grid-area: left; }
.task-tree__node.is-right { grid-area: right; }
.task-tree__node.is-r1 { grid-area: r1; }
.task-tree__node.is-r2 { grid-area: r2; }

.task-tree__node.is-queued {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.task-tree__node.is-running,
.task-tree__node.is-helped {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
  animation: forkjoin-focus 320ms ease-out both;
}

.task-tree__node.is-helped {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.task-tree__node.is-done {
  border-style: solid;
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.task-tree__node.is-waiting {
  border-color: var(--atlas-coral);
  border-style: solid;
  color: var(--atlas-coral);
}

.worker-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.worker {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.worker header,
.worker__indices,
.worker__queue {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: center;
}

.worker header {
  justify-content: space-between;
}

.worker header strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.worker header span,
.worker p,
.worker__edge {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.worker__indices code {
  color: var(--vp-c-brand-1);
  font-size: 0.66rem;
}

.worker__queue {
  min-height: 42px;
  flex-wrap: nowrap;
}

.worker__queue strong,
.worker__empty {
  display: grid;
  place-items: center;
  min-width: 52px;
  min-height: 34px;
  border: 1px solid var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.worker__empty {
  border-color: var(--atlas-line);
  border-style: dashed;
  background: transparent;
  color: var(--vp-c-text-3);
}

.worker__edge:last-child {
  margin-left: auto;
}

.worker p {
  min-height: 34px;
  margin: 0;
  line-height: 1.5;
}

@keyframes forkjoin-focus {
  from { opacity: 0.55; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 720px) {
  .forkjoin-demo__status {
    align-items: flex-start;
    flex-direction: column;
  }

  .worker-grid {
    grid-template-columns: 1fr;
  }

  .worker p {
    min-height: 0;
  }
}

@media (max-width: 390px) {
  .task-tree {
    grid-template-areas:
      'root root'
      'left right'
      'r1 r2';
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .worker__queue {
    flex-wrap: wrap;
  }

  .worker__edge:last-child {
    margin-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .task-tree__node {
    animation: none;
    transition: none;
  }
}
</style>
