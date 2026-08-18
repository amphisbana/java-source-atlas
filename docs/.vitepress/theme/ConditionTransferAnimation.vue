<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface ConditionSnapshot {
  owner: string
  state: number
  predicate: string
  t1: string
  t2: string
  conditionQueue: string[]
  syncQueue: string[]
  savedState: string
}

const steps: SourceAnimationStep[] = [
  {
    title: 'T1 在锁内检查条件',
    method: 'lock.lock(); while (!ready) condition.await()',
    description: 'T1 重入两次，state 为 2；业务条件 ready 仍为 false，因此准备进入 await。'
  },
  {
    title: '加入 Condition 队列',
    method: 'addConditionWaiter()',
    description: '创建 waitStatus=CONDITION 的节点并追加到条件队列，此时 T1 仍持有锁。'
  },
  {
    title: '完整释放重入层数并阻塞',
    method: 'savedState = fullyRelease(node); LockSupport.park(this)',
    description: '保存 state=2 后一次性完全释放锁，其他线程才有机会进入临界区改变 ready。'
  },
  {
    title: 'T2 修改受保护状态',
    method: 'lock.lock(); ready = true',
    description: 'T2 获得空闲锁并把业务条件改为 true；修改和 signal 都发生在同一把锁保护下。'
  },
  {
    title: 'signal 只负责转移节点',
    method: 'doSignal(firstWaiter) → transferForSignal(node)',
    description: 'T1 节点从 Condition 队列转入 AQS 同步队列，但 T2 仍持锁，所以 T1 还不能从 await 返回。'
  },
  {
    title: 'T2 释放后唤醒 T1',
    method: 'unlock() → unparkSuccessor(head)',
    description: 'T2 释放锁，AQS 唤醒同步队列中的 T1；T1 接下来仍要按普通独占获取路径竞争。'
  },
  {
    title: 'T1 恢复原重入层数',
    method: 'acquireQueued(node, savedState=2)',
    description: 'T1 成功把 state 恢复到 2 后，await 才返回；while 会再次检查 ready，随后继续执行业务逻辑。'
  }
]

const snapshots: ConditionSnapshot[] = [
  { owner: 'T1', state: 2, predicate: 'ready = false', t1: '检查条件', t2: '等待', conditionQueue: [], syncQueue: [], savedState: '-' },
  { owner: 'T1', state: 2, predicate: 'ready = false', t1: 'Condition 队列', t2: '等待', conditionQueue: ['T1 / CONDITION'], syncQueue: [], savedState: '-' },
  { owner: 'null', state: 0, predicate: 'ready = false', t1: 'WAITING', t2: '准备获取锁', conditionQueue: ['T1 / CONDITION'], syncQueue: [], savedState: '2' },
  { owner: 'T2', state: 1, predicate: 'ready = true', t1: 'WAITING', t2: '修改 ready', conditionQueue: ['T1 / CONDITION'], syncQueue: [], savedState: '2' },
  { owner: 'T2', state: 1, predicate: 'ready = true', t1: '同步队列等待', t2: '持锁执行 signal', conditionQueue: [], syncQueue: ['T1 / 0'], savedState: '2' },
  { owner: 'null', state: 0, predicate: 'ready = true', t1: 'RUNNABLE', t2: '已释放', conditionQueue: [], syncQueue: ['T1 / unparked'], savedState: '2' },
  { owner: 'T1', state: 2, predicate: 'ready = true', t1: 'await 返回', t2: '完成', conditionQueue: [], syncQueue: [], savedState: '2' }
]
</script>

<template>
  <SourceAnimation title="await 与 signal 如何让节点跨越两种队列" :steps="steps" :interval="2300">
    <template #visual="{ currentIndex }">
      <div class="condition-flow">
        <div class="condition-flow__summary">
          <span>owner <strong>{{ snapshots[currentIndex].owner }}</strong></span>
          <span>state <strong>{{ snapshots[currentIndex].state }}</strong></span>
          <span>savedState <strong>{{ snapshots[currentIndex].savedState }}</strong></span>
          <span class="condition-flow__predicate">{{ snapshots[currentIndex].predicate }}</span>
        </div>

        <div class="condition-flow__threads">
          <div><strong>T1</strong><span>{{ snapshots[currentIndex].t1 }}</span></div>
          <div><strong>T2</strong><span>{{ snapshots[currentIndex].t2 }}</span></div>
        </div>

        <div class="condition-flow__queues">
          <section>
            <header>
              <strong>Condition 条件队列</strong>
              <code>firstWaiter → nextWaiter</code>
            </header>
            <div class="condition-flow__lane">
              <span v-if="!snapshots[currentIndex].conditionQueue.length" class="condition-flow__empty">空</span>
              <strong v-for="node in snapshots[currentIndex].conditionQueue" :key="node" class="condition-flow__node is-condition">
                {{ node }}
              </strong>
            </div>
          </section>

          <div class="condition-flow__transfer" :class="{ 'is-active': currentIndex === 4 }">signal →</div>

          <section>
            <header>
              <strong>AQS 同步队列</strong>
              <code>prev / next</code>
            </header>
            <div class="condition-flow__lane">
              <span v-if="!snapshots[currentIndex].syncQueue.length" class="condition-flow__empty">空</span>
              <strong v-for="node in snapshots[currentIndex].syncQueue" :key="node" class="condition-flow__node is-sync">
                {{ node }}
              </strong>
            </div>
          </section>
        </div>

        <div class="condition-flow__rule">
          <code>signal 不等于 await 返回</code>
          <span>节点必须进入同步队列并重新获得锁</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.condition-flow {
  display: grid;
  gap: 16px;
  min-height: 260px;
}

.condition-flow__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.condition-flow__summary strong {
  margin-left: 4px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.condition-flow__predicate {
  margin-left: auto;
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-weight: 700;
}

.condition-flow__threads {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.condition-flow__threads div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  animation: condition-fade 280ms ease-out both;
}

.condition-flow__threads strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.75rem;
}

.condition-flow__threads span {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.condition-flow__queues {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 72px minmax(180px, 1fr);
  gap: 10px;
  align-items: center;
}

.condition-flow__queues section {
  min-width: 0;
}

.condition-flow__queues header {
  display: grid;
  gap: 3px;
  margin-bottom: 7px;
}

.condition-flow__queues header strong {
  font-size: 0.76rem;
}

.condition-flow__queues header code {
  color: var(--vp-c-text-3);
  font-size: 0.66rem;
}

.condition-flow__lane {
  display: flex;
  align-items: center;
  min-height: 78px;
  padding: 10px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.condition-flow__node {
  display: grid;
  place-items: center;
  min-width: 110px;
  min-height: 42px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
  animation: transfer-node 420ms ease-out both;
}

.condition-flow__node.is-condition {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.condition-flow__node.is-sync {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.condition-flow__empty {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.condition-flow__transfer {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  text-align: center;
  opacity: 0.35;
}

.condition-flow__transfer.is-active {
  color: var(--atlas-coral);
  opacity: 1;
  animation: transfer-arrow 700ms ease-in-out infinite alternate;
}

.condition-flow__rule {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 18px;
  padding-top: 10px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.condition-flow__rule code {
  color: var(--vp-c-brand-1);
}

@keyframes transfer-node {
  from { opacity: 0; transform: translateX(-20px); }
  to { opacity: 1; transform: translateX(0); }
}

@keyframes transfer-arrow {
  from { transform: translateX(-4px); }
  to { transform: translateX(5px); }
}

@keyframes condition-fade {
  from { opacity: 0.55; }
  to { opacity: 1; }
}

@media (max-width: 720px) {
  .condition-flow__predicate {
    width: 100%;
    margin-left: 0;
  }

  .condition-flow__queues {
    grid-template-columns: 1fr;
  }

  .condition-flow__transfer {
    text-align: left;
  }
}
</style>
