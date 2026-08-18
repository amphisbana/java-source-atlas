<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type RunState = 'RUNNING' | 'SHUTDOWN' | 'STOP' | 'TIDYING' | 'TERMINATED'

interface WorkerSnapshot {
  name: string
  task: string
  status: string
}

interface PoolSnapshot {
  runState: RunState
  stateBits: string
  workers: WorkerSnapshot[]
  queue: string[]
  rejected: string[]
  branch: string
}

const stateOrder: RunState[] = ['RUNNING', 'SHUTDOWN', 'STOP', 'TIDYING', 'TERMINATED']

// 每个步骤都对应一次源码可观察快照，使任务归属、队列和 ctl 状态保持同步。
const steps: SourceAnimationStep[] = [
  {
    title: '线程池处于 RUNNING',
    method: 'ctlOf(RUNNING, 0)',
    description: 'ctl 的高 3 位记录 RUNNING，低 29 位 workerCount 为 0；核心线程默认按需创建。'
  },
  {
    title: 'T1 创建核心 worker',
    method: 'workerCount < corePoolSize → addWorker(T1, true)',
    description: 'addWorker 先用 CAS 预占 workerCount，再创建 W1；T1 作为 firstTask，不经过队列。'
  },
  {
    title: 'T2 创建第二个核心 worker',
    method: 'addWorker(T2, true)',
    description: 'workerCount 仍小于 corePoolSize，W2 接收 T2；此后核心线程数达到 2。'
  },
  {
    title: 'T3 成功入队',
    method: 'isRunning(c) && workQueue.offer(T3)',
    description: '核心 worker 已满，有界队列仍有空间，T3 进入队列；源码随后必须重新读取 ctl。'
  },
  {
    title: 'T4 填满队列',
    method: 'workQueue.offer(T4) → recheck ctl',
    description: 'T4 占用最后一个队列槽位。复查仍为 RUNNING 且 workerCount 不为 0，因此不需要补偿。'
  },
  {
    title: 'T5 扩展非核心 worker',
    method: 'offer 失败 → addWorker(T5, false)',
    description: '队列已满，但 workerCount 仍小于 maximumPoolSize 3，W3 直接把 T5 作为 firstTask。'
  },
  {
    title: 'T6 进入拒绝策略',
    method: 'addWorker(T6, false) 失败 → reject(T6)',
    description: 'worker 与队列都达到上限，无法接收 T6；拒绝策略在提交线程中执行。'
  },
  {
    title: '空闲 W1 获取 T3',
    method: 'runWorker → getTask → workQueue.take()',
    description: 'W1 完成 T1 后先释放 Worker 锁，再从队首取出 T3；队列腾出一个槽位。'
  },
  {
    title: 'T7 的 offer 已成功',
    method: 'workQueue.offer(T7) = true',
    description: '提交线程把 T7 放入刚腾出的槽位，但尚未执行入队后的 ctl 复查。这个窗口允许 shutdown 并发推进状态。'
  },
  {
    title: 'shutdown 抢先推进状态',
    method: '!isRunning(recheck) && remove(T7) → reject(T7)',
    description: '关闭线程把 ctl 推进到 SHUTDOWN；提交线程复查后移除 T7 并拒绝，避免关闭后悄悄接收新任务。'
  },
  {
    title: 'SHUTDOWN 排空旧任务',
    method: 'getTask() 继续消费 workQueue',
    description: 'SHUTDOWN 不接收新任务，但仍允许 W2 取得关闭前已接收的 T4。shutdown 只尝试中断空闲 Worker，不打断正在运行的任务。'
  },
  {
    title: 'shutdownNow 推进到 STOP',
    method: 'advanceRunState(STOP) → interruptWorkers()',
    description: '为了完整展示状态机，本时间线随后调用 shutdownNow：STOP 不再处理队列，并尝试中断正在执行 T4 的 W2。'
  },
  {
    title: '最后一个 worker 退出',
    method: 'workerCount = 0 → CAS ctlOf(TIDYING, 0)',
    description: 'W2 响应中断并退出；tryTerminate 发现 workerCount 为 0，获得主锁后把状态推进到 TIDYING。'
  },
  {
    title: 'terminated 钩子完成',
    method: 'terminated() → ctlOf(TERMINATED, 0) → signalAll()',
    description: '只有完成 terminated 钩子后才进入 TERMINATED，并唤醒 awaitTermination 的所有等待线程。'
  }
]

// 状态快照与 steps 一一对应，避免动画只改变说明文字而没有改变 ctl 和任务位置。
const snapshots: PoolSnapshot[] = [
  { runState: 'RUNNING', stateBits: '111', workers: [], queue: [], rejected: [], branch: '等待 execute(command)' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T1', status: '运行' }], queue: [], rejected: [], branch: '分支 1：创建核心 worker' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T1', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }], queue: [], rejected: [], branch: '分支 1：创建核心 worker' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T1', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }], queue: ['T3'], rejected: [], branch: '分支 2：offer 后复查' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T1', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }], queue: ['T3', 'T4'], rejected: [], branch: '分支 2：队列已满' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T1', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }, { name: 'W3', task: 'T5', status: '运行' }], queue: ['T3', 'T4'], rejected: [], branch: '分支 3：扩展到 maximumPoolSize' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T1', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }, { name: 'W3', task: 'T5', status: '运行' }], queue: ['T3', 'T4'], rejected: ['T6'], branch: '分支 3：无法扩展，执行 reject' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T3', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }, { name: 'W3', task: 'T5', status: '运行' }], queue: ['T4'], rejected: ['T6'], branch: 'runWorker → getTask' },
  { runState: 'RUNNING', stateBits: '111', workers: [{ name: 'W1', task: 'T3', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }, { name: 'W3', task: 'T5', status: '运行' }], queue: ['T4', 'T7'], rejected: ['T6'], branch: 'offer 已完成，尚未 recheck' },
  { runState: 'SHUTDOWN', stateBits: '000', workers: [{ name: 'W1', task: 'T3', status: '运行' }, { name: 'W2', task: 'T2', status: '运行' }, { name: 'W3', task: 'T5', status: '运行' }], queue: ['T4'], rejected: ['T6', 'T7'], branch: 'remove(T7) → reject(T7)' },
  { runState: 'SHUTDOWN', stateBits: '000', workers: [{ name: 'W2', task: 'T4', status: '排空队列' }], queue: [], rejected: ['T6', 'T7'], branch: '不接收新任务，继续旧任务' },
  { runState: 'STOP', stateBits: '001', workers: [{ name: 'W2', task: 'T4', status: '已中断' }], queue: [], rejected: ['T6', 'T7'], branch: 'interruptWorkers + drainQueue' },
  { runState: 'TIDYING', stateBits: '010', workers: [], queue: [], rejected: ['T6', 'T7'], branch: 'workerCount=0，执行 terminated' },
  { runState: 'TERMINATED', stateBits: '011', workers: [], queue: [], rejected: ['T6', 'T7'], branch: 'termination.signalAll()' }
]
</script>

<template>
  <SourceAnimation title="execute 决策、并发关闭与 ctl 五态迁移" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="pool-flow">
        <div class="pool-flow__states" aria-label="线程池生命周期状态">
          <div
            v-for="state in stateOrder"
            :key="state"
            class="pool-flow__state"
            :class="{
              'is-active': state === snapshots[currentIndex].runState,
              'is-complete': stateOrder.indexOf(state) < stateOrder.indexOf(snapshots[currentIndex].runState)
            }"
          >
            <span>{{ state }}</span>
          </div>
        </div>

        <div class="pool-flow__limits">
          <span>core <strong>2</strong></span>
          <span>max <strong>3</strong></span>
          <span>queue <strong>2</strong></span>
          <code>{{ snapshots[currentIndex].branch }}</code>
        </div>

        <div class="pool-flow__layout">
          <section>
            <span class="pool-flow__label">workers（{{ snapshots[currentIndex].workers.length }} / 3）</span>
            <div class="pool-flow__workers">
              <div v-for="worker in snapshots[currentIndex].workers" :key="worker.name" class="pool-flow__worker">
                <strong>{{ worker.name }}</strong>
                <span>{{ worker.task }}</span>
                <small>{{ worker.status }}</small>
              </div>
              <div
                v-for="slot in 3 - snapshots[currentIndex].workers.length"
                :key="`worker-empty-${slot}`"
                class="pool-flow__worker is-empty"
              >
                <span>空槽</span>
              </div>
            </div>
          </section>

          <section>
            <span class="pool-flow__label">workQueue（{{ snapshots[currentIndex].queue.length }} / 2）</span>
            <div class="pool-flow__queue">
              <strong v-for="task in snapshots[currentIndex].queue" :key="task">{{ task }}</strong>
              <span v-for="slot in 2 - snapshots[currentIndex].queue.length" :key="`queue-empty-${slot}`">空</span>
            </div>
          </section>

          <section>
            <span class="pool-flow__label">rejected</span>
            <div class="pool-flow__rejected" :class="{ 'has-task': snapshots[currentIndex].rejected.length }">
              <strong v-if="snapshots[currentIndex].rejected.length">{{ snapshots[currentIndex].rejected.join(', ') }}</strong>
              <span v-else>暂无</span>
            </div>
          </section>
        </div>

        <div class="pool-flow__ctl">
          <span>高 3 位 <strong>{{ snapshots[currentIndex].stateBits }}</strong></span>
          <span>runState <strong>{{ snapshots[currentIndex].runState }}</strong></span>
          <span>workerCount <strong>{{ snapshots[currentIndex].workers.length }}</strong></span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.pool-flow {
  display: grid;
  gap: 16px;
  min-height: 300px;
  container-type: inline-size;
}

.pool-flow__states {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
}

.pool-flow__state {
  min-width: 0;
  padding: 8px 4px;
  border-bottom: 2px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
  text-align: center;
  overflow-wrap: anywhere;
}

.pool-flow__state.is-complete {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.pool-flow__state.is-active {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
  color: var(--atlas-coral);
  font-weight: 700;
}

.pool-flow__limits {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
  color: var(--vp-c-text-3);
  font-size: 0.74rem;
}

.pool-flow__limits strong {
  margin-left: 3px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.pool-flow__limits code {
  min-width: 0;
  margin-left: auto;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.74rem;
}

.pool-flow__layout {
  display: grid;
  grid-template-columns: minmax(210px, 1.4fr) minmax(135px, 0.8fr) minmax(90px, 0.5fr);
  gap: 14px;
}

.pool-flow__layout section {
  min-width: 0;
}

.pool-flow__label {
  display: block;
  margin-bottom: 8px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.pool-flow__workers {
  display: grid;
  grid-template-columns: repeat(3, minmax(52px, 1fr));
  gap: 6px;
}

.pool-flow__worker {
  display: grid;
  place-items: center;
  gap: 2px;
  min-height: 82px;
  border: 1px solid var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  animation: pool-enter 360ms ease-out both;
}

.pool-flow__worker strong,
.pool-flow__worker span {
  font-family: var(--vp-font-family-mono);
}

.pool-flow__worker strong {
  color: var(--vp-c-brand-1);
  font-size: 0.76rem;
}

.pool-flow__worker span {
  color: var(--atlas-ink);
  font-size: 0.8rem;
}

.pool-flow__worker small {
  color: var(--vp-c-text-3);
  font-size: 0.64rem;
}

.pool-flow__worker.is-empty {
  border-color: var(--atlas-line);
  border-style: dashed;
  background: transparent;
}

.pool-flow__worker.is-empty span {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.pool-flow__queue {
  display: grid;
  grid-template-columns: repeat(2, minmax(48px, 1fr));
  gap: 6px;
}

.pool-flow__queue strong,
.pool-flow__queue span {
  display: grid;
  place-items: center;
  min-height: 58px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
}

.pool-flow__queue strong {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
  animation: pool-enter 360ms ease-out both;
}

.pool-flow__queue span {
  border-style: dashed;
  background: transparent;
  color: var(--vp-c-text-3);
}

.pool-flow__rejected {
  display: grid;
  place-items: center;
  min-height: 58px;
  border: 1px dashed var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.pool-flow__rejected.has-task {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
  color: var(--atlas-coral);
  animation: reject-pulse 600ms ease-out both;
}

.pool-flow__ctl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  text-align: center;
}

.pool-flow__ctl strong {
  margin-left: 4px;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

@keyframes pool-enter {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes reject-pulse {
  0% { transform: scale(0.94); }
  70% { transform: scale(1.03); }
  100% { transform: scale(1); }
}

@container (max-width: 540px) {
  .pool-flow__limits code {
    width: 100%;
    margin-left: 0;
  }

  .pool-flow__layout {
    grid-template-columns: 1fr;
  }

  .pool-flow__ctl {
    grid-template-columns: 1fr;
    text-align: left;
  }
}

@container (max-width: 360px) {
  .pool-flow__states {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
