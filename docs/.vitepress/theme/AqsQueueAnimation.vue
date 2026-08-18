<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface AqsSnapshot {
  owner: string
  state: number
  headStatus: string
  queue: Array<{ name: string; status: string; current: boolean }>
  threads: Array<{ name: string; state: string }>
}

const steps: SourceAnimationStep[] = [
  {
    title: '锁处于空闲状态',
    method: 'state == 0; owner == null',
    description: 'AQS 的同步状态为 0，同步队列只有在竞争发生后才会初始化。'
  },
  {
    title: 'T1 走快速获取路径',
    method: 'tryAcquire(1) → CAS state 0 → 1',
    description: 'T1 原子地把 state 改为 1，并记录 exclusiveOwnerThread；没有入队、没有 park。'
  },
  {
    title: 'T2 获取失败并入队',
    method: 'addWaiter(Node.EXCLUSIVE)',
    description: 'T2 不能获得已被 T1 持有的锁，AQS 初始化哨兵 head，并把 T2 追加到 tail。'
  },
  {
    title: '建立 SIGNAL 握手后阻塞',
    method: 'shouldParkAfterFailedAcquire → parkAndCheckInterrupt',
    description: 'T2 先把有效前驱 head 的 waitStatus 设为 SIGNAL，再次确认后才 park，避免错过释放通知。'
  },
  {
    title: 'T1 完全释放并唤醒后继',
    method: 'release(1) → unparkSuccessor(head)',
    description: 'state 归零后清空 owner；head 标记表明后继需要唤醒，于是 LockSupport.unpark(T2)。'
  },
  {
    title: 'T2 醒来后重新竞争成功',
    method: 'p == head && tryAcquire(1) → setHead(node)',
    description: 'unpark 只让 T2 恢复竞争。成功 CAS 获取后，T2 节点成为新 head，旧哨兵帮助 GC。'
  }
]

const snapshots: AqsSnapshot[] = [
  {
    owner: 'null', state: 0, headStatus: '未初始化', queue: [],
    threads: [{ name: 'T1', state: 'RUNNABLE' }, { name: 'T2', state: 'RUNNABLE' }]
  },
  {
    owner: 'T1', state: 1, headStatus: '未初始化', queue: [],
    threads: [{ name: 'T1', state: '持有锁' }, { name: 'T2', state: '准备获取' }]
  },
  {
    owner: 'T1', state: 1, headStatus: '0', queue: [{ name: 'T2', status: '0', current: true }],
    threads: [{ name: 'T1', state: '持有锁' }, { name: 'T2', state: '已入队' }]
  },
  {
    owner: 'T1', state: 1, headStatus: 'SIGNAL(-1)', queue: [{ name: 'T2', status: 'parked', current: true }],
    threads: [{ name: 'T1', state: '持有锁' }, { name: 'T2', state: 'WAITING' }]
  },
  {
    owner: 'null', state: 0, headStatus: '0', queue: [{ name: 'T2', status: 'unparked', current: true }],
    threads: [{ name: 'T1', state: '已释放' }, { name: 'T2', state: 'RUNNABLE' }]
  },
  {
    owner: 'T2', state: 1, headStatus: '0（T2 成为 head）', queue: [],
    threads: [{ name: 'T1', state: '完成' }, { name: 'T2', state: '持有锁' }]
  }
]
</script>

<template>
  <SourceAnimation title="获取失败的线程如何安全地 park 与 unpark" :steps="steps" :interval="2200">
    <template #visual="{ currentIndex }">
      <div class="aqs-flow">
        <div class="aqs-flow__lock">
          <span>ReentrantLock</span>
          <div class="aqs-flow__state" :class="{ 'is-owned': snapshots[currentIndex].state > 0 }">
            <strong>state = {{ snapshots[currentIndex].state }}</strong>
            <small>owner = {{ snapshots[currentIndex].owner }}</small>
          </div>
        </div>

        <div class="aqs-flow__threads">
          <div v-for="thread in snapshots[currentIndex].threads" :key="thread.name">
            <strong>{{ thread.name }}</strong>
            <span>{{ thread.state }}</span>
          </div>
        </div>

        <div class="aqs-flow__queue">
          <span class="aqs-flow__label">AQS 同步队列</span>
          <div class="aqs-flow__nodes">
            <div v-if="snapshots[currentIndex].headStatus !== '未初始化'" class="aqs-flow__node is-head">
              <strong>head</strong>
              <small>{{ snapshots[currentIndex].headStatus }}</small>
            </div>
            <template v-for="node in snapshots[currentIndex].queue" :key="node.name">
              <span class="aqs-flow__arrow">→</span>
              <div class="aqs-flow__node" :class="{ 'is-current': node.current, 'is-parked': node.status === 'parked' }">
                <strong>{{ node.name }}</strong>
                <small>{{ node.status }}</small>
              </div>
            </template>
            <span v-if="snapshots[currentIndex].headStatus === '未初始化'" class="aqs-flow__tail">
              head = null，tail = null
            </span>
            <span v-else-if="!snapshots[currentIndex].queue.length" class="aqs-flow__tail">tail = head</span>
            <span v-else class="aqs-flow__tail">tail</span>
          </div>
        </div>

        <div class="aqs-flow__invariant">
          <code>只有 head 的直接后继在循环中尝试获取</code>
          <span>unpark ≠ 已经获得锁</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.aqs-flow {
  display: grid;
  grid-template-columns: minmax(150px, 0.7fr) minmax(190px, 0.9fr);
  gap: 18px;
  min-height: 240px;
}

.aqs-flow__lock,
.aqs-flow__threads {
  display: grid;
  gap: 9px;
}

.aqs-flow__lock > span,
.aqs-flow__label {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-weight: 700;
}

.aqs-flow__state {
  display: grid;
  gap: 5px;
  min-height: 84px;
  padding: 14px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease;
}

.aqs-flow__state.is-owned {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.aqs-flow__state strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.aqs-flow__state small {
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
}

.aqs-flow__threads {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: end;
}

.aqs-flow__threads div {
  display: grid;
  gap: 4px;
  padding: 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  animation: aqs-state-enter 260ms ease-out both;
}

.aqs-flow__threads strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
}

.aqs-flow__threads span {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.aqs-flow__queue {
  grid-column: 1 / -1;
  display: grid;
  gap: 9px;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
}

.aqs-flow__nodes {
  display: flex;
  align-items: center;
  min-height: 66px;
  overflow-x: auto;
  padding: 4px 0;
}

.aqs-flow__node {
  display: grid;
  place-items: center;
  gap: 2px;
  min-width: 94px;
  min-height: 54px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  animation: aqs-node-enter 360ms ease-out both;
}

.aqs-flow__node strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.75rem;
}

.aqs-flow__node small {
  color: var(--vp-c-text-3);
  font-size: 0.65rem;
}

.aqs-flow__node.is-head {
  border-color: var(--vp-c-brand-1);
}

.aqs-flow__node.is-current {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.aqs-flow__node.is-parked {
  animation: parked-pulse 900ms ease-in-out infinite alternate;
}

.aqs-flow__arrow {
  width: 34px;
  flex: 0 0 34px;
  color: var(--vp-c-text-3);
  text-align: center;
}

.aqs-flow__tail {
  margin-left: 10px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
  white-space: nowrap;
}

.aqs-flow__invariant {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 18px;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.aqs-flow__invariant code {
  color: var(--vp-c-brand-1);
}

@keyframes aqs-node-enter {
  from { opacity: 0; transform: translateX(-14px); }
  to { opacity: 1; transform: translateX(0); }
}

@keyframes aqs-state-enter {
  from { opacity: 0.55; }
  to { opacity: 1; }
}

@keyframes parked-pulse {
  from { box-shadow: 0 0 0 0 color-mix(in srgb, var(--atlas-coral) 12%, transparent); }
  to { box-shadow: 0 0 0 5px color-mix(in srgb, var(--atlas-coral) 18%, transparent); }
}

@media (max-width: 640px) {
  .aqs-flow {
    grid-template-columns: 1fr;
  }

  .aqs-flow__queue,
  .aqs-flow__invariant {
    grid-column: 1;
  }
}
</style>
