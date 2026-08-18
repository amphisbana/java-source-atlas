<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface SharedNodeSnapshot {
  name: string
  role: string
  status: string
  tone: 'head' | 'waiting' | 'active' | 'passed'
}

interface SharedPropagationSnapshot {
  state: number
  releaseResult: string
  acquireResult: string
  passed: number
  propagation: string
  nodes: SharedNodeSnapshot[]
}

const steps: SourceAnimationStep[] = [
  {
    title: '闩锁以 count=2 创建',
    method: 'new CountDownLatch(2) → Sync.setState(2)',
    description: 'CountDownLatch 直接把计数放进 AQS state；此时 await 的共享获取条件尚未满足。'
  },
  {
    title: '三个等待者以 SHARED 模式入队',
    method: 'await() → acquireSharedInterruptibly(1) → doAcquireShared(1)',
    description: 'tryAcquireShared 返回 -1，W1、W2、W3 依次进入同一条 AQS 同步队列，并在前驱建立 SIGNAL 后 park。'
  },
  {
    title: '第一次 countDown 不传播',
    method: 'tryReleaseShared(1): state 2 → 1; return false',
    description: '计数仍大于 0，releaseShared 不调用 doReleaseShared；三个等待者必须继续停在队列中。'
  },
  {
    title: '归零释放第一个共享节点',
    method: 'state 1 → 0; releaseShared → doReleaseShared → unpark(W1)',
    description: '第二次 countDown 让 tryReleaseShared 返回 true。doReleaseShared 清除 head 的 SIGNAL 并唤醒第一个有效后继 W1。'
  },
  {
    title: 'W1 成功后继续传播',
    method: 'tryAcquireShared → 1; setHeadAndPropagate(W1, 1)',
    description: 'W1 看到 state=0，获得共享通行权并成为新 head；正返回值表示后继也可能成功，因此继续唤醒 W2。'
  },
  {
    title: 'W2 把传播交给 W3',
    method: 'setHeadAndPropagate(W2, 1) → doReleaseShared',
    description: '共享获取不是一次唤醒所有线程。每个成功节点推进 head，并保守地把传播接力给下一个共享节点。'
  },
  {
    title: '全部等待者通过',
    method: 'W3 setHeadAndPropagate → tail == head',
    description: 'W3 成为 head 后队列中已无有效后继。闩锁保持 state=0，之后新的 await 会走快速路径直接返回。'
  }
]

const snapshots: SharedPropagationSnapshot[] = [
  {
    state: 2,
    releaseResult: '-',
    acquireResult: '-1（尚未调用）',
    passed: 0,
    propagation: '队列尚未初始化',
    nodes: [
      { name: 'W1', role: '等待者', status: '准备 await', tone: 'waiting' },
      { name: 'W2', role: '等待者', status: '准备 await', tone: 'waiting' },
      { name: 'W3', role: '等待者', status: '准备 await', tone: 'waiting' }
    ]
  },
  {
    state: 2,
    releaseResult: '-',
    acquireResult: '-1',
    passed: 0,
    propagation: 'head(SIGNAL) → W1 → W2 → W3',
    nodes: [
      { name: 'head', role: '哨兵', status: 'SIGNAL(-1)', tone: 'head' },
      { name: 'W1', role: 'SHARED', status: 'WAITING', tone: 'waiting' },
      { name: 'W2', role: 'SHARED', status: 'WAITING', tone: 'waiting' },
      { name: 'W3', role: 'SHARED', status: 'WAITING', tone: 'waiting' }
    ]
  },
  {
    state: 1,
    releaseResult: 'false',
    acquireResult: '-1',
    passed: 0,
    propagation: '未进入 doReleaseShared',
    nodes: [
      { name: 'head', role: '哨兵', status: 'SIGNAL(-1)', tone: 'head' },
      { name: 'W1', role: 'SHARED', status: '继续 park', tone: 'waiting' },
      { name: 'W2', role: 'SHARED', status: '继续 park', tone: 'waiting' },
      { name: 'W3', role: 'SHARED', status: '继续 park', tone: 'waiting' }
    ]
  },
  {
    state: 0,
    releaseResult: 'true',
    acquireResult: 'W1 尚未重试',
    passed: 0,
    propagation: 'doReleaseShared → unpark(W1)',
    nodes: [
      { name: 'head', role: '哨兵', status: 'SIGNAL → 0', tone: 'head' },
      { name: 'W1', role: 'SHARED', status: 'RUNNABLE', tone: 'active' },
      { name: 'W2', role: 'SHARED', status: 'WAITING', tone: 'waiting' },
      { name: 'W3', role: 'SHARED', status: 'WAITING', tone: 'waiting' }
    ]
  },
  {
    state: 0,
    releaseResult: 'true',
    acquireResult: 'W1 = 1',
    passed: 1,
    propagation: 'W1 成为 head → unpark(W2)',
    nodes: [
      { name: 'W1', role: '新 head', status: '已通过', tone: 'passed' },
      { name: 'W2', role: 'SHARED', status: 'RUNNABLE', tone: 'active' },
      { name: 'W3', role: 'SHARED', status: 'WAITING', tone: 'waiting' }
    ]
  },
  {
    state: 0,
    releaseResult: 'true',
    acquireResult: 'W2 = 1',
    passed: 2,
    propagation: 'W2 成为 head → unpark(W3)',
    nodes: [
      { name: 'W2', role: '新 head', status: '已通过', tone: 'passed' },
      { name: 'W3', role: 'SHARED', status: 'RUNNABLE', tone: 'active' }
    ]
  },
  {
    state: 0,
    releaseResult: 'true',
    acquireResult: 'W3 = 1',
    passed: 3,
    propagation: 'tail == head，无后继需要传播',
    nodes: [
      { name: 'W3', role: 'head / tail', status: '已通过', tone: 'passed' }
    ]
  }
]
</script>

<template>
  <SourceAnimation title="CountDownLatch 怎样沿共享队列逐个传播" :steps="steps" :interval="2400">
    <template #visual="{ currentIndex }">
      <div
        class="shared-flow"
        role="img"
        :aria-label="`state=${snapshots[currentIndex].state}，已通过 ${snapshots[currentIndex].passed} 个等待者；${snapshots[currentIndex].propagation}`"
      >
        <div class="shared-flow__metrics">
          <div>
            <span>AQS state</span>
            <strong>{{ snapshots[currentIndex].state }}</strong>
          </div>
          <div>
            <span>tryReleaseShared</span>
            <strong>{{ snapshots[currentIndex].releaseResult }}</strong>
          </div>
          <div>
            <span>tryAcquireShared</span>
            <strong>{{ snapshots[currentIndex].acquireResult }}</strong>
          </div>
          <div>
            <span>已通过</span>
            <strong>{{ snapshots[currentIndex].passed }} / 3</strong>
          </div>
        </div>

        <div class="shared-flow__queue">
          <span class="shared-flow__label">AQS 同步队列（从 head 向 tail）</span>
          <div class="shared-flow__nodes">
            <div
              v-for="node in snapshots[currentIndex].nodes"
              :key="`${currentIndex}-${node.name}`"
              class="shared-flow__node"
              :class="`is-${node.tone}`"
            >
              <span>{{ node.role }}</span>
              <strong>{{ node.name }}</strong>
              <small>{{ node.status }}</small>
            </div>
          </div>
        </div>

        <div class="shared-flow__propagation">
          <code>{{ snapshots[currentIndex].propagation }}</code>
          <span>共享传播是队列接力，不是一次广播执行</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.shared-flow {
  display: grid;
  gap: 18px;
  min-width: 0;
  min-height: 280px;
}

.shared-flow__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-line);
}

.shared-flow__metrics div {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 10px;
  background: var(--atlas-surface);
}

.shared-flow__metrics span,
.shared-flow__label {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.shared-flow__metrics strong {
  min-width: 0;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
  overflow-wrap: anywhere;
}

.shared-flow__queue {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.shared-flow__nodes {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  min-width: 0;
}

.shared-flow__node {
  display: grid;
  min-width: 0;
  min-height: 76px;
  place-content: center;
  gap: 3px;
  padding: 9px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  text-align: center;
  animation: shared-node-enter 260ms ease-out both;
}

.shared-flow__node span,
.shared-flow__node small {
  color: var(--vp-c-text-3);
  font-size: 0.66rem;
  overflow-wrap: anywhere;
}

.shared-flow__node strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.78rem;
  overflow-wrap: anywhere;
}

.shared-flow__node.is-head {
  border-color: var(--vp-c-brand-1);
}

.shared-flow__node.is-active {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 8%, var(--vp-c-bg));
}

.shared-flow__node.is-active strong {
  color: var(--atlas-coral);
}

.shared-flow__node.is-passed {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.shared-flow__node.is-passed strong {
  color: var(--vp-c-brand-1);
}

.shared-flow__propagation {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 18px;
  padding-top: 10px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.shared-flow__propagation code {
  min-width: 0;
  color: var(--vp-c-brand-1);
  overflow-wrap: anywhere;
}

@keyframes shared-node-enter {
  from { opacity: 0.55; transform: translateY(7px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 520px) {
  .shared-flow {
    min-height: 0;
  }

  .shared-flow__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .shared-flow__nodes {
    grid-template-columns: minmax(0, 1fr);
  }

  .shared-flow__node {
    grid-template-columns: minmax(64px, 0.7fr) minmax(54px, 0.6fr) minmax(84px, 1fr);
    min-height: 54px;
    align-items: center;
    text-align: left;
  }
}
</style>
