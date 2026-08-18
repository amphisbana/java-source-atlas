<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type SlotTone = 'filled' | 'empty' | 'inserted'
type ThreadTone = '' | 'active' | 'waiting' | 'ready' | 'success'

interface QueueSlot {
  value: string
  tone: SlotTone
}

interface BlockingQueueSnapshot {
  owner: string
  count: number
  takeIndex: number
  putIndex: number
  queue: QueueSlot[]
  logicalOrder: string
  producerState: string
  producerTone: ThreadTone
  consumerState: string
  consumerTone: ThreadTone
  conditionQueue: string[]
  syncQueue: string[]
  event: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '容量为 2 的队列已满',
    method: 'count == items.length == 2',
    description: 'A、B 占满环形数组；满队列中 takeIndex 与 putIndex 都是 0，必须结合 count 才能区分满和空。'
  },
  {
    title: '生产者获得主锁并发现满',
    method: 'put(C) → lock.lockInterruptibly(); count == items.length',
    description: '生产者 P 已持有 ArrayBlockingQueue 的唯一主锁，但容量条件不成立，不能覆盖 putIndex 指向的槽位。'
  },
  {
    title: '生产者进入 notFull 条件队列',
    method: 'notFull.await() → fullyRelease(lock)',
    description: 'P 以 Condition 节点等待并完整释放主锁。释放是消费者进入 dequeue、改变 count 的必要条件。'
  },
  {
    title: '消费者取走队首 A',
    method: 'take() → dequeue()',
    description: '消费者 K 获得同一把锁，清空 items[0]，让 takeIndex 前进到 1，并把 count 从 2 减为 1。'
  },
  {
    title: 'signal 只转移等待节点',
    method: 'notFull.signal() → transferForSignal(P)',
    description: 'K 仍持锁；P 从 notFull 条件队列转入 AQS 同步队列，尚未获得锁，也不能从 await 返回。'
  },
  {
    title: '消费者释放主锁',
    method: 'lock.unlock() → unparkSuccessor',
    description: '主锁变为空闲，P 被允许恢复运行并参与竞争。unpark 不是锁所有权转移，其他线程仍可能先获得非公平锁。'
  },
  {
    title: '生产者重新竞争成功',
    method: 'acquireQueued → await 返回 → while 重新检查',
    description: 'P 自己的获取操作成功后才成为 owner；它再次检查 count，确认空间仍存在，随后才可写入。'
  },
  {
    title: '把 C 写入环形槽位',
    method: 'enqueue(C) → items[putIndex] = C; count++; notEmpty.signal()',
    description: 'C 写入槽 0，putIndex 从 0 前进到 1，count 恢复为 2；物理数组为 C、B，逻辑 FIFO 顺序是 B、C。'
  },
  {
    title: '生产者释放锁并返回',
    method: 'lock.unlock(); put(C) return',
    description: 'P 释放主锁后本次 put 才完整结束。最终队列仍满，但最早元素 A 已由消费者取走，新元素 C 排在 B 后面。'
  }
]

// 快照固定一种合法交错，用来解释条件节点转移，不代表运行时线程必然按此顺序调度。
const snapshots: BlockingQueueSnapshot[] = [
  {
    owner: 'null', count: 2, takeIndex: 0, putIndex: 0,
    queue: [{ value: 'A', tone: 'filled' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'A → B', producerState: '准备 put(C)', producerTone: '',
    consumerState: '准备 take()', consumerTone: '', conditionQueue: [], syncQueue: [],
    event: '环形索引相等，count=2 表示满'
  },
  {
    owner: 'P', count: 2, takeIndex: 0, putIndex: 0,
    queue: [{ value: 'A', tone: 'filled' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'A → B', producerState: '持锁检查：FULL', producerTone: 'active',
    consumerState: '等待主锁', consumerTone: 'waiting', conditionQueue: [], syncQueue: [],
    event: 'P 不能覆盖 items[0]'
  },
  {
    owner: 'null', count: 2, takeIndex: 0, putIndex: 0,
    queue: [{ value: 'A', tone: 'filled' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'A → B', producerState: 'WAITING / notFull', producerTone: 'waiting',
    consumerState: '可以竞争主锁', consumerTone: 'ready', conditionQueue: ['P'], syncQueue: [],
    event: 'await 完整释放 lock'
  },
  {
    owner: 'K', count: 1, takeIndex: 1, putIndex: 0,
    queue: [{ value: '空', tone: 'empty' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'B', producerState: 'WAITING / notFull', producerTone: 'waiting',
    consumerState: '持锁，已取出 A', consumerTone: 'active', conditionQueue: ['P'], syncQueue: [],
    event: 'dequeue: count 2 → 1'
  },
  {
    owner: 'K', count: 1, takeIndex: 1, putIndex: 0,
    queue: [{ value: '空', tone: 'empty' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'B', producerState: '已转入同步队列', producerTone: 'ready',
    consumerState: '仍持有 lock', consumerTone: 'active', conditionQueue: [], syncQueue: ['P'],
    event: 'signal ≠ P 获得 lock'
  },
  {
    owner: 'null', count: 1, takeIndex: 1, putIndex: 0,
    queue: [{ value: '空', tone: 'empty' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'B', producerState: 'RUNNABLE，重新竞争', producerTone: 'ready',
    consumerState: 'take(A) 返回', consumerTone: 'success', conditionQueue: [], syncQueue: ['P'],
    event: 'unlock/unpark 只开放竞争机会'
  },
  {
    owner: 'P', count: 1, takeIndex: 1, putIndex: 0,
    queue: [{ value: '空', tone: 'empty' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'B', producerState: '持锁，while 复查通过', producerTone: 'active',
    consumerState: '已完成', consumerTone: '', conditionQueue: [], syncQueue: [],
    event: 'P 的 tryAcquire 成功后才是 owner'
  },
  {
    owner: 'P', count: 2, takeIndex: 1, putIndex: 1,
    queue: [{ value: 'C', tone: 'inserted' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'B → C', producerState: 'enqueue(C) 完成', producerTone: 'success',
    consumerState: '已完成', consumerTone: '', conditionQueue: [], syncQueue: [],
    event: 'putIndex 0 → 1，count 1 → 2'
  },
  {
    owner: 'null', count: 2, takeIndex: 1, putIndex: 1,
    queue: [{ value: 'C', tone: 'inserted' }, { value: 'B', tone: 'filled' }],
    logicalOrder: 'B → C', producerState: 'put(C) 返回', producerTone: 'success',
    consumerState: 'take() 返回 A', consumerTone: 'success', conditionQueue: [], syncQueue: [],
    event: '两次操作均完成，FIFO 顺序保持'
  }
]
</script>

<template>
  <SourceAnimation title="满队列 put 如何等待、被 signal 并重新竞争" :steps="steps" :interval="2300">
    <template #visual="{ currentIndex }">
      <div class="blocking-flow">
        <div class="blocking-flow__summary">
          <span>capacity <strong>2</strong></span>
          <span>count <strong>{{ snapshots[currentIndex].count }}</strong></span>
          <span>owner <strong>{{ snapshots[currentIndex].owner }}</strong></span>
          <code>{{ snapshots[currentIndex].event }}</code>
        </div>

        <div class="blocking-flow__threads" aria-label="生产者和消费者状态">
          <div :class="[`is-${snapshots[currentIndex].producerTone}`]">
            <strong>P / producer</strong>
            <span>{{ snapshots[currentIndex].producerState }}</span>
          </div>
          <div :class="[`is-${snapshots[currentIndex].consumerTone}`]">
            <strong>K / consumer</strong>
            <span>{{ snapshots[currentIndex].consumerState }}</span>
          </div>
        </div>

        <div class="blocking-flow__main">
          <section class="blocking-ring" aria-label="ArrayBlockingQueue 环形数组">
            <header>
              <strong>items[2]</strong>
              <code>logical: {{ snapshots[currentIndex].logicalOrder }}</code>
            </header>
            <div class="blocking-ring__slots">
              <div
                v-for="(slot, index) in snapshots[currentIndex].queue"
                :key="index"
                class="blocking-slot"
                :class="[`is-${slot.tone}`]"
              >
                <small>items[{{ index }}]</small>
                <strong>{{ slot.value }}</strong>
                <div class="blocking-slot__markers">
                  <span v-if="snapshots[currentIndex].takeIndex === index">takeIndex</span>
                  <span v-if="snapshots[currentIndex].putIndex === index">putIndex</span>
                </div>
              </div>
            </div>
          </section>

          <section class="blocking-waits" aria-label="条件队列与 AQS 同步队列">
            <div>
              <header><strong>notFull 条件队列</strong><code>nextWaiter</code></header>
              <div class="blocking-waits__lane">
                <span v-if="!snapshots[currentIndex].conditionQueue.length">空</span>
                <strong v-for="node in snapshots[currentIndex].conditionQueue" :key="node" class="is-condition">
                  {{ node }} / CONDITION
                </strong>
              </div>
            </div>
            <div class="blocking-waits__arrow" :class="{ 'is-active': currentIndex === 4 }">signal →</div>
            <div>
              <header><strong>AQS 同步队列</strong><code>prev / next</code></header>
              <div class="blocking-waits__lane">
                <span v-if="!snapshots[currentIndex].syncQueue.length">空</span>
                <strong v-for="node in snapshots[currentIndex].syncQueue" :key="node" class="is-sync">
                  {{ node }} / 等待 lock
                </strong>
              </div>
            </div>
          </section>
        </div>

        <div class="blocking-flow__compare" aria-label="ArrayBlockingQueue 与 LinkedBlockingQueue 锁结构对照">
          <div><strong>ArrayBlockingQueue</strong><span>notEmpty ← 单一 lock → notFull</span></div>
          <div><strong>LinkedBlockingQueue</strong><span>takeLock / notEmpty ← Atomic count → putLock / notFull</span></div>
        </div>

        <div class="blocking-flow__rule">
          <code>signal 只转移节点</code>
          <span>等待线程仍需重新获得锁，并在 while 中复查容量条件</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.blocking-flow {
  display: grid;
  gap: 16px;
  min-height: 390px;
}

.blocking-flow__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  align-items: center;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.blocking-flow__summary strong {
  margin-left: 4px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.blocking-flow__summary code {
  margin-left: auto;
  color: var(--vp-c-brand-1);
  font-size: 0.7rem;
}

.blocking-flow__threads {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.blocking-flow__threads > div {
  display: flex;
  min-width: 0;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  animation: blocking-state-enter 280ms ease-out both;
}

.blocking-flow__threads > div.is-active,
.blocking-flow__threads > div.is-ready {
  border-left-color: var(--vp-c-brand-1);
}

.blocking-flow__threads > div.is-waiting {
  border-left-color: var(--atlas-coral);
}

.blocking-flow__threads > div.is-success {
  border-left-color: var(--vp-c-tip-1);
}

.blocking-flow__threads strong {
  flex: 0 0 auto;
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.blocking-flow__threads span {
  min-width: 0;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  overflow-wrap: anywhere;
  text-align: right;
}

.blocking-flow__main {
  display: grid;
  grid-template-columns: minmax(210px, 0.72fr) minmax(330px, 1.28fr);
  gap: 18px;
  align-items: stretch;
}

.blocking-ring,
.blocking-waits {
  min-width: 0;
}

.blocking-ring header,
.blocking-waits header {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.blocking-ring header strong,
.blocking-waits header strong {
  font-size: 0.74rem;
}

.blocking-ring header code,
.blocking-waits header code {
  min-width: 0;
  color: var(--vp-c-text-3);
  font-size: 0.64rem;
  overflow-wrap: anywhere;
  text-align: right;
}

.blocking-ring__slots {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.blocking-slot {
  display: grid;
  min-width: 0;
  min-height: 126px;
  place-items: center;
  align-content: center;
  gap: 5px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  animation: blocking-slot-enter 340ms ease-out both;
}

.blocking-slot.is-filled {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.blocking-slot.is-inserted {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
}

.blocking-slot.is-empty {
  border-style: dashed;
  color: var(--vp-c-text-3);
}

.blocking-slot small {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.65rem;
}

.blocking-slot > strong {
  font-family: var(--vp-font-family-mono);
  font-size: 1rem;
}

.blocking-slot__markers {
  display: flex;
  min-height: 34px;
  flex-wrap: wrap;
  justify-content: center;
  gap: 3px;
}

.blocking-slot__markers span {
  padding: 2px 4px;
  border: 1px solid var(--atlas-line);
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 0.58rem;
}

.blocking-waits {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) 58px minmax(130px, 1fr);
  gap: 8px;
  align-items: center;
}

.blocking-waits > div {
  min-width: 0;
}

.blocking-waits__lane {
  display: grid;
  min-height: 92px;
  place-items: center;
  padding: 8px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.blocking-waits__lane strong {
  display: grid;
  min-width: 0;
  min-height: 44px;
  place-items: center;
  padding: 4px 7px;
  border: 1px solid;
  background: var(--vp-c-bg);
  font-family: var(--vp-font-family-mono);
  font-size: 0.64rem;
  overflow-wrap: anywhere;
  text-align: center;
  animation: blocking-node-enter 400ms ease-out both;
}

.blocking-waits__lane strong.is-condition {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.blocking-waits__lane strong.is-sync {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.blocking-waits__arrow {
  color: var(--vp-c-text-3);
  font-size: 0.66rem;
  opacity: 0.35;
  text-align: center;
}

.blocking-waits__arrow.is-active {
  color: var(--atlas-coral);
  opacity: 1;
  animation: blocking-arrow 700ms ease-in-out infinite alternate;
}

.blocking-flow__compare {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.blocking-flow__compare div {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 8px 10px;
  border-top: 1px solid var(--atlas-line);
}

.blocking-flow__compare strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.blocking-flow__compare span {
  color: var(--vp-c-text-3);
  font-size: 0.65rem;
  overflow-wrap: anywhere;
}

.blocking-flow__rule {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 18px;
  padding-top: 8px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.blocking-flow__rule code {
  color: var(--vp-c-brand-1);
}

@keyframes blocking-state-enter {
  from { opacity: 0.55; }
  to { opacity: 1; }
}

@keyframes blocking-slot-enter {
  from { opacity: 0.6; transform: translateY(-5px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes blocking-node-enter {
  from { opacity: 0; transform: translateX(-14px); }
  to { opacity: 1; transform: translateX(0); }
}

@keyframes blocking-arrow {
  from { transform: translateX(-4px); }
  to { transform: translateX(4px); }
}

@media (max-width: 760px) {
  .blocking-flow {
    min-height: 620px;
  }

  .blocking-flow__summary code {
    width: 100%;
    margin-left: 0;
  }

  .blocking-flow__main {
    grid-template-columns: 1fr;
  }

  .blocking-waits {
    grid-template-columns: 1fr;
  }

  .blocking-waits__arrow {
    text-align: left;
  }

  .blocking-flow__compare {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 460px) {
  .blocking-flow__threads {
    grid-template-columns: 1fr;
  }

  .blocking-flow__threads > div {
    align-items: flex-start;
    flex-direction: column;
  }

  .blocking-flow__threads span {
    text-align: left;
  }
}
</style>
