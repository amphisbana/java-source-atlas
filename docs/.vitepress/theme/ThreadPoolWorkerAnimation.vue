<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface WorkerFlowSnapshot {
  scenario: string
  worker: string
  workerState: string
  lockState: string
  interruptState: string
  task: string
  taskState: string
  queue: string
  hook: string
  throwable: string
  outcome: string
}

// 时间线由三个独立实验组成，分别固定住 Worker 锁、空闲回收和 FutureTask 异常边界。
const steps: SourceAnimationStep[] = [
  {
    title: 'Worker 先以 state=-1 创建',
    method: 'Worker(firstTask) → setState(-1)',
    description: '线程尚未启动时先占住 Worker 的 AQS 锁，避免 shutdown 把一个还没进入 runWorker 的线程误判为空闲线程。'
  },
  {
    title: 'runWorker 开放中断',
    method: 'firstTask=null → w.unlock()',
    description: '工作线程取走 firstTask 后把 state 从 -1 改为 0；从这一刻起，线程池才允许关闭流程中断它。'
  },
  {
    title: '执行任务前持有工作锁',
    method: 'w.lock() → beforeExecute(thread, task)',
    description: 'state=1 代表正在执行用户任务。shutdown 的 interruptIdleWorkers 调用 tryLock 会失败，因此不会中断这个活跃 Worker。'
  },
  {
    title: 'execute 异常直接暴露',
    method: 'task.run() throws → afterExecute(task, throwable)',
    description: 'execute 传入的 Runnable 抛出运行时异常时，afterExecute 收到非空 Throwable，异常随后继续抛出并让 W1 异常退出。'
  },
  {
    title: '异常退出触发替补',
    method: 'processWorkerExit(W1, true) → addWorker(null, false)',
    description: 'completedAbruptly=true 表示 workerCount 尚未在 getTask 中扣减；清理 W1 后，线程池创建 W2 继续消费已排队任务。'
  },
  {
    title: '替补 Worker 完成队列任务',
    method: 'W2: getTask → beforeExecute → run → afterExecute',
    description: 'W2 从队列取得 Direct-B，三个任务钩子按同一工作线程的调用顺序执行，随后释放 Worker 锁。'
  },
  {
    title: 'shutdown 只命中空闲 Worker',
    method: 'interruptIdleWorkers → W2.tryLock() = true',
    description: 'W2 在 take 中等待任务时 state=0，关闭线程能临时取得 Worker 锁并设置中断；正在执行任务的 state=1 Worker 不会被命中。'
  },
  {
    title: '空闲 Worker 退出并终止',
    method: 'getTask: SHUTDOWN + queue empty → return null',
    description: 'take 被中断后重新检查 ctl，发现 SHUTDOWN 且队列为空，扣减 workerCount 并退出；tryTerminate 随后完成状态收口。'
  },
  {
    title: '核心线程也进入定时 poll',
    method: 'allowCoreThreadTimeOut(true) → timed=true',
    description: '在独立实验池中开启核心线程超时后，即使 workerCount 没有超过 corePoolSize，getTask 也改用 poll(keepAliveTime)。'
  },
  {
    title: '连续确认超时后回收',
    method: 'timed && timedOut → CAS workerCount - 1',
    description: '第一次 poll 返回 null 只把 timedOut 设为 true；下一轮再次检查条件并 CAS 扣减 workerCount，核心 Worker 才退出。'
  },
  {
    title: 'submit 把异常保存进 FutureTask',
    method: 'FutureTask.run → setException(cause)',
    description: 'submit 创建的 FutureTask 自己捕获任务异常，因此 runWorker 看见的是一次正常返回，worker 不会因这次失败而退出。'
  },
  {
    title: 'afterExecute 主动解包异常',
    method: 'afterExecute(FutureTask, null) → Future.get()',
    description: 'afterExecute 的原始 Throwable 通常为 null；确认 Future 已完成后调用 get，才能从 ExecutionException 中取得真实业务异常。terminated 在最终收口时执行。'
  }
]

// 每个快照只呈现当前源码分支需要的最少状态，便于对比 AQS state 和异常去向。
const snapshots: WorkerFlowSnapshot[] = [
  { scenario: 'execute 异常与替补', worker: 'W1', workerState: '-1', lockState: '启动前锁定', interruptState: '抑制中断', task: 'Direct-A', taskState: 'firstTask', queue: 'Direct-B', hook: '未进入', throwable: '—', outcome: 'workerCount=1' },
  { scenario: 'execute 异常与替补', worker: 'W1', workerState: '0', lockState: '空闲/可中断', interruptState: '允许中断', task: 'Direct-A', taskState: '已取出', queue: 'Direct-B', hook: '未进入', throwable: '—', outcome: '准备执行' },
  { scenario: 'execute 异常与替补', worker: 'W1', workerState: '1', lockState: '任务执行中', interruptState: 'shutdown 不命中', task: 'Direct-A', taskState: 'run()', queue: 'Direct-B', hook: 'beforeExecute', throwable: '—', outcome: 'tryLock=false' },
  { scenario: 'execute 异常与替补', worker: 'W1', workerState: '1', lockState: '任务执行中', interruptState: '未中断', task: 'Direct-A', taskState: '抛出异常', queue: 'Direct-B', hook: 'afterExecute', throwable: 'RuntimeException', outcome: '异常退出' },
  { scenario: 'execute 异常与替补', worker: 'W1 → W2', workerState: '0', lockState: '替补已创建', interruptState: '无', task: 'Direct-B', taskState: '等待 W2', queue: 'Direct-B', hook: 'processWorkerExit', throwable: 'RuntimeException', outcome: 'createdWorkers=2' },
  { scenario: 'execute 异常与替补', worker: 'W2', workerState: '1', lockState: '任务执行中', interruptState: '未中断', task: 'Direct-B', taskState: '正常完成', queue: '空', hook: 'before → after', throwable: 'null', outcome: 'completedTasks+1' },
  { scenario: 'shutdown 与空闲锁', worker: 'W2', workerState: '0', lockState: '等待队列', interruptState: 'interrupt=true', task: '—', taskState: 'take()', queue: '空', hook: '无', throwable: 'InterruptedException', outcome: 'tryLock=true' },
  { scenario: 'shutdown 与空闲锁', worker: 'W2', workerState: '0', lockState: '已退出', interruptState: '已消费中断', task: '—', taskState: 'getTask=null', queue: '空', hook: 'terminated', throwable: '—', outcome: 'TERMINATED' },
  { scenario: '核心线程超时', worker: 'W3', workerState: '0', lockState: '空闲', interruptState: '无', task: '—', taskState: 'poll(timeout)', queue: '空', hook: '无', throwable: '—', outcome: 'timed=true' },
  { scenario: '核心线程超时', worker: 'W3', workerState: '0', lockState: '已退出', interruptState: '无', task: '—', taskState: 'timedOut=true', queue: '空', hook: 'processWorkerExit', throwable: '—', outcome: 'workerCount=0' },
  { scenario: 'submit 异常', worker: 'W4', workerState: '1', lockState: '任务执行中', interruptState: '未中断', task: 'FutureTask', taskState: 'setException', queue: '空', hook: 'afterExecute', throwable: '原始值 null', outcome: 'worker 继续复用' },
  { scenario: 'submit 异常', worker: 'W4', workerState: '0', lockState: '任务已完成', interruptState: '无', task: 'FutureTask', taskState: 'get() 解包', queue: '空', hook: 'afterExecute → terminated', throwable: 'IllegalStateException', outcome: '异常已观测' }
]
</script>

<template>
  <SourceAnimation title="Worker 工作锁、回收与任务异常" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="worker-flow">
        <div class="worker-flow__scenario">
          <span>当前实验</span>
          <strong>{{ snapshots[currentIndex].scenario }}</strong>
        </div>

        <div class="worker-flow__lanes">
          <section>
            <span class="worker-flow__label">Worker / AQS</span>
            <div class="worker-flow__worker">
              <strong>{{ snapshots[currentIndex].worker }}</strong>
              <code>state={{ snapshots[currentIndex].workerState }}</code>
              <span>{{ snapshots[currentIndex].lockState }}</span>
              <small>{{ snapshots[currentIndex].interruptState }}</small>
            </div>
          </section>

          <div class="worker-flow__arrow" aria-hidden="true">→</div>

          <section>
            <span class="worker-flow__label">task / queue</span>
            <div class="worker-flow__task">
              <strong>{{ snapshots[currentIndex].task }}</strong>
              <span>{{ snapshots[currentIndex].taskState }}</span>
              <small>queue: {{ snapshots[currentIndex].queue }}</small>
            </div>
          </section>

          <div class="worker-flow__arrow" aria-hidden="true">→</div>

          <section>
            <span class="worker-flow__label">hook / result</span>
            <div class="worker-flow__hook">
              <strong>{{ snapshots[currentIndex].hook }}</strong>
              <span>Throwable: {{ snapshots[currentIndex].throwable }}</span>
              <small>{{ snapshots[currentIndex].outcome }}</small>
            </div>
          </section>
        </div>

        <div class="worker-flow__legend">
          <span><strong>-1</strong> 启动前</span>
          <span><strong>0</strong> 空闲，可被 shutdown 中断</span>
          <span><strong>1</strong> 执行中，工作锁已持有</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.worker-flow {
  display: grid;
  gap: 18px;
  min-height: 260px;
  container-type: inline-size;
}

.worker-flow__scenario {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: baseline;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.worker-flow__scenario strong {
  color: var(--atlas-ink);
  font-size: 0.86rem;
}

.worker-flow__lanes {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) 24px minmax(130px, 1fr) 24px minmax(150px, 1.15fr);
  gap: 8px;
  align-items: center;
}

.worker-flow__lanes section {
  min-width: 0;
}

.worker-flow__label {
  display: block;
  margin-bottom: 7px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.worker-flow__worker,
.worker-flow__task,
.worker-flow__hook {
  display: grid;
  align-content: center;
  gap: 6px;
  min-height: 130px;
  padding: 12px;
  border-top: 3px solid var(--vp-c-brand-1);
  background: var(--atlas-surface);
  overflow-wrap: anywhere;
  animation: worker-state-enter 300ms ease-out both;
}

.worker-flow__task {
  border-color: var(--atlas-coral);
}

.worker-flow__hook {
  border-color: var(--vp-c-tip-1);
}

.worker-flow__worker strong,
.worker-flow__task strong,
.worker-flow__hook strong {
  color: var(--atlas-ink);
  font-size: 0.82rem;
}

.worker-flow__worker code,
.worker-flow__worker span,
.worker-flow__task span,
.worker-flow__hook span {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-size: 0.74rem;
}

.worker-flow__worker small,
.worker-flow__task small,
.worker-flow__hook small {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.worker-flow__arrow {
  color: var(--vp-c-brand-1);
  font-size: 1.1rem;
  text-align: center;
}

.worker-flow__legend {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.worker-flow__legend strong {
  margin-right: 4px;
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
}

@keyframes worker-state-enter {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

@container (max-width: 540px) {
  .worker-flow__lanes {
    grid-template-columns: 1fr;
  }

  .worker-flow__arrow {
    transform: rotate(90deg);
  }

  .worker-flow__worker,
  .worker-flow__task,
  .worker-flow__hook {
    min-height: 92px;
  }

  .worker-flow__legend {
    grid-template-columns: 1fr;
  }
}
</style>
