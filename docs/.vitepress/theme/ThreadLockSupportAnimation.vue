<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface ThreadLockSupportSnapshot {
  state: string
  permit: '空' | '可用' | '已消费'
  interruptFlag: 'false' | 'true' | '已清除'
  blocker: string
  mainAction: string
  workerAction: string
  condition: string
  event: string
  tone: 'neutral' | 'start' | 'signal' | 'waiting' | 'interrupt' | 'done'
}

const steps: SourceAnimationStep[] = [
  {
    title: '只创建 Thread 对象',
    method: 'new Thread(target)',
    description: 'Java 对象已经存在，但 threadStatus 仍表示未启动；没有执行栈，也没有调用 target.run。'
  },
  {
    title: 'start 检查一次启动约束',
    method: 'Thread.start() → threadStatus == 0',
    description: 'JDK 8 的同步 start 先拒绝二次启动，再登记 ThreadGroup；直接调用 run 不会经过这条路径。'
  },
  {
    title: 'start0 请求 VM 启动线程',
    method: 'start() → native start0()',
    description: 'VM 创建并调度底层线程。start 之前的写入 happens-before 新线程中的动作，但何时取得 CPU 不确定。'
  },
  {
    title: '新线程进入 run',
    method: 'Thread.run() → target.run()',
    description: '现在 run 由 atlas-worker 自己执行；Thread.run 的默认实现只是把调用委托给构造时保存的 Runnable。'
  },
  {
    title: '先发布业务条件',
    method: 'ready.set(true)',
    description: '可靠同步先用 volatile 或原子变量发布条件。permit 只控制是否阻塞，不能代替业务状态。'
  },
  {
    title: 'unpark 可以早于 park',
    method: 'LockSupport.unpark(worker)',
    description: 'worker 已经启动且仍在运行；unpark 把它的一位 permit 设为可用，不要求目标线程此刻正在 park。'
  },
  {
    title: '第一次 park 消费预发许可',
    method: 'LockSupport.park(blocker)',
    description: '已有 permit 时 park 消费它并立即返回，不进入 WAITING；方法返回本身不说明业务条件一定成立。'
  },
  {
    title: '重复 unpark 不会累计',
    method: 'unpark(worker); unpark(worker)',
    description: 'permit 不是计数器。空槽被设为可用后，再次 unpark 仍只有一个可用许可。'
  },
  {
    title: '下一个 park 消费唯一许可',
    method: 'LockSupport.park(blocker)',
    description: '第一个 park 用掉合并后的唯一许可并返回；permit 槽重新变空。'
  },
  {
    title: '再次 park 才真正等待',
    method: 'while (!released) LockSupport.park(waitNode)',
    description: '没有 permit 且条件未成立，线程进入 WAITING。循环能抵抗伪唤醒、无关 unpark 和中断返回。'
  },
  {
    title: '诊断线程读取 blocker',
    method: 'LockSupport.getBlocker(worker)',
    description: 'park(Object) 在等待期间暴露阻塞对象，线程转储和诊断工具据此说明“在等谁”；它不是同步条件。'
  },
  {
    title: 'interrupt 让 park 返回',
    method: 'worker.interrupt() → park returns',
    description: 'park 不抛 InterruptedException，也不清除中断标记。worker 恢复 RUNNABLE 后必须自行决定退出还是继续等待。'
  },
  {
    title: '区分两种中断查询',
    method: 'isInterrupted(); Thread.interrupted()',
    description: 'isInterrupted 只读取标记；静态 Thread.interrupted 读取当前线程并清除标记，不能用它查询任意目标线程。'
  },
  {
    title: '定时 park 仍要检查条件',
    method: 'parkNanos(blocker, remaining)',
    description: '正剩余时间的 parkNanos 对应 TIMED_WAITING，但也可能提前返回；应按 deadline 重算 remaining。'
  },
  {
    title: '线程结束并由 join 汇合',
    method: 'run returns → TERMINATED → join returns',
    description: 'run 返回后线程终止。目标线程中的动作 happens-before 另一个线程从成功 join 中返回。'
  }
]

const snapshots: ThreadLockSupportSnapshot[] = [
  {
    state: 'NEW', permit: '空', interruptFlag: 'false', blocker: 'null',
    mainAction: '构造 atlas-worker', workerAction: '尚未启动', condition: 'ready = false',
    event: '对象已创建', tone: 'neutral'
  },
  {
    state: 'NEW', permit: '空', interruptFlag: 'false', blocker: 'null',
    mainAction: '调用 worker.start()', workerAction: '等待 VM 启动', condition: 'threadStatus == 0',
    event: '一次启动检查', tone: 'start'
  },
  {
    state: 'RUNNABLE', permit: '空', interruptFlag: 'false', blocker: 'null',
    mainAction: '从 start 返回', workerAction: '由 VM 调度', condition: 'start happens-before',
    event: 'start0', tone: 'start'
  },
  {
    state: 'RUNNABLE', permit: '空', interruptFlag: 'false', blocker: 'null',
    mainAction: '继续执行调用方逻辑', workerAction: 'target.run()', condition: 'ready = false',
    event: 'atlas-worker 执行', tone: 'start'
  },
  {
    state: 'RUNNABLE', permit: '空', interruptFlag: 'false', blocker: 'null',
    mainAction: '读取原子条件', workerAction: '发布 ready', condition: 'ready = true',
    event: 'volatile / atomic', tone: 'signal'
  },
  {
    state: 'RUNNABLE', permit: '可用', interruptFlag: 'false', blocker: 'null',
    mainAction: 'unpark(worker)', workerAction: '尚未调用 park', condition: 'ready = true',
    event: '预发一个 permit', tone: 'signal'
  },
  {
    state: 'RUNNABLE', permit: '已消费', interruptFlag: 'false', blocker: 'null',
    mainAction: '无需再次通知', workerAction: 'park 立即返回', condition: 'ready = true',
    event: '消费预发 permit', tone: 'signal'
  },
  {
    state: 'RUNNABLE', permit: '可用', interruptFlag: 'false', blocker: 'null',
    mainAction: '连续调用两次 unpark', workerAction: '准备下一轮 park', condition: 'released = false',
    event: '两个通知合并为一位', tone: 'signal'
  },
  {
    state: 'RUNNABLE', permit: '已消费', interruptFlag: 'false', blocker: 'null',
    mainAction: '观察第一次返回', workerAction: '第一次 park 返回', condition: 'released = false',
    event: '唯一 permit 已用完', tone: 'signal'
  },
  {
    state: 'WAITING', permit: '空', interruptFlag: 'false', blocker: 'WaitNode@7a',
    mainAction: '尚未发新通知', workerAction: '循环中的第二次 park', condition: 'released = false',
    event: '真正进入等待', tone: 'waiting'
  },
  {
    state: 'WAITING', permit: '空', interruptFlag: 'false', blocker: 'WaitNode@7a',
    mainAction: 'getBlocker(worker)', workerAction: '仍停在 park', condition: '只读诊断快照',
    event: '定位阻塞对象', tone: 'waiting'
  },
  {
    state: 'RUNNABLE', permit: '空', interruptFlag: 'true', blocker: 'null',
    mainAction: 'worker.interrupt()', workerAction: 'park 返回并重检条件', condition: 'released = false',
    event: '中断唤醒', tone: 'interrupt'
  },
  {
    state: 'RUNNABLE', permit: '空', interruptFlag: '已清除', blocker: 'null',
    mainAction: '等待 worker 决策', workerAction: 'Thread.interrupted()', condition: '取消策略自行决定',
    event: '读取并清除标记', tone: 'interrupt'
  },
  {
    state: 'TIMED_WAITING', permit: '空', interruptFlag: 'false', blocker: 'Deadline@2f',
    mainAction: '等待有界完成', workerAction: 'parkNanos(remaining)', condition: 'remaining > 0',
    event: '按剩余时间等待', tone: 'waiting'
  },
  {
    state: 'TERMINATED', permit: '空', interruptFlag: 'false', blocker: 'null',
    mainAction: 'join 返回并读取结果', workerAction: 'run 已返回', condition: 'remaining <= 0',
    event: '线程汇合完成', tone: 'done'
  }
]
</script>

<template>
  <SourceAnimation title="从 start 到一位 permit、中断与定时等待" :steps="steps" :interval="2400">
    <template #visual="{ currentIndex }">
      <div class="thread-lock-flow">
        <div class="thread-lock-flow__event" :class="`is-${snapshots[currentIndex].tone}`">
          <span>当前事件</span>
          <strong>{{ snapshots[currentIndex].event }}</strong>
        </div>

        <div class="thread-lock-flow__lanes">
          <div class="thread-lock-flow__lane is-main">
            <span>main</span>
            <strong>{{ snapshots[currentIndex].mainAction }}</strong>
          </div>
          <div class="thread-lock-flow__lane is-worker">
            <span>worker</span>
            <strong>{{ snapshots[currentIndex].workerAction }}</strong>
          </div>
        </div>

        <div class="thread-lock-flow__machine">
          <div>
            <span>Thread.State</span>
            <strong :class="{ 'is-waiting': snapshots[currentIndex].state.includes('WAITING') }">
              {{ snapshots[currentIndex].state }}
            </strong>
          </div>
          <div>
            <span>interrupt flag</span>
            <strong :class="{ 'is-interrupted': snapshots[currentIndex].interruptFlag === 'true' }">
              {{ snapshots[currentIndex].interruptFlag }}
            </strong>
          </div>
          <div>
            <span>parkBlocker</span>
            <strong>{{ snapshots[currentIndex].blocker }}</strong>
          </div>
          <div>
            <span>业务条件</span>
            <strong>{{ snapshots[currentIndex].condition }}</strong>
          </div>
        </div>

        <div class="thread-lock-flow__permit">
          <div class="thread-lock-flow__permit-title">
            <span>每线程 permit 槽</span>
            <strong>{{ snapshots[currentIndex].permit }}</strong>
          </div>
          <div class="thread-lock-flow__permit-track" :class="{
            'is-available': snapshots[currentIndex].permit === '可用',
            'is-consumed': snapshots[currentIndex].permit === '已消费'
          }">
            <span aria-hidden="true"></span>
          </div>
          <p>最多保存 1 个，不是计数信号量</p>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.thread-lock-flow {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(170px, 0.75fr);
  gap: 18px 22px;
  min-width: 0;
  min-height: 270px;
}

.thread-lock-flow__event {
  grid-column: 1 / -1;
  display: flex;
  min-width: 0;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 2px solid var(--atlas-line);
}

.thread-lock-flow__event span,
.thread-lock-flow__machine span,
.thread-lock-flow__permit-title span {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  font-weight: 700;
}

.thread-lock-flow__event strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.9rem;
  text-align: right;
}

.thread-lock-flow__event.is-start {
  border-bottom-color: var(--vp-c-brand-1);
}

.thread-lock-flow__event.is-signal,
.thread-lock-flow__event.is-done {
  border-bottom-color: var(--vp-c-brand-2);
}

.thread-lock-flow__event.is-waiting {
  border-bottom-color: var(--vp-c-warning-1);
}

.thread-lock-flow__event.is-interrupt {
  border-bottom-color: var(--atlas-coral);
}

.thread-lock-flow__lanes {
  display: grid;
  gap: 9px;
  min-width: 0;
}

.thread-lock-flow__lane {
  display: grid;
  grid-template-columns: 62px minmax(0, 1fr);
  gap: 10px;
  min-width: 0;
  min-height: 68px;
  align-items: center;
  padding: 9px 12px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.thread-lock-flow__lane.is-main {
  border-left-color: var(--vp-c-brand-1);
}

.thread-lock-flow__lane.is-worker {
  border-left-color: var(--vp-c-brand-2);
}

.thread-lock-flow__lane span {
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
  font-weight: 700;
}

.thread-lock-flow__lane strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
  line-height: 1.5;
}

.thread-lock-flow__machine {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: start;
  min-width: 0;
  border-top: 1px solid var(--atlas-line);
  border-left: 1px solid var(--atlas-line);
}

.thread-lock-flow__machine > div {
  display: grid;
  gap: 5px;
  min-width: 0;
  min-height: 76px;
  align-content: center;
  padding: 9px;
  border-right: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}

.thread-lock-flow__machine strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  line-height: 1.45;
}

.thread-lock-flow__machine strong.is-waiting {
  color: var(--vp-c-warning-1);
}

.thread-lock-flow__machine strong.is-interrupted {
  color: var(--atlas-coral);
}

.thread-lock-flow__permit {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: minmax(120px, 0.45fr) minmax(100px, 1fr) minmax(150px, 0.75fr);
  gap: 12px;
  min-width: 0;
  align-items: center;
  padding-top: 14px;
  border-top: 1px solid var(--atlas-line);
}

.thread-lock-flow__permit-title {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.thread-lock-flow__permit-title strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.8rem;
}

.thread-lock-flow__permit-track {
  position: relative;
  min-width: 0;
  height: 30px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.thread-lock-flow__permit-track span {
  position: absolute;
  top: 6px;
  left: 7px;
  width: 16px;
  height: 16px;
  border: 2px solid var(--vp-c-text-3);
  border-radius: 50%;
  background: var(--vp-c-bg);
  opacity: 0.34;
  transition: left 220ms ease, border-color 220ms ease, background 220ms ease, opacity 220ms ease;
}

.thread-lock-flow__permit-track.is-available span {
  left: calc(100% - 23px);
  border-color: var(--vp-c-brand-2);
  background: var(--vp-c-brand-2);
  opacity: 1;
}

.thread-lock-flow__permit-track.is-consumed span {
  left: calc(50% - 8px);
  border-color: var(--vp-c-brand-1);
  opacity: 0.55;
}

.thread-lock-flow__permit p {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  line-height: 1.45;
  text-align: right;
}

@media (max-width: 640px) {
  .thread-lock-flow {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
    min-height: 430px;
  }

  .thread-lock-flow__event,
  .thread-lock-flow__permit {
    grid-column: 1;
  }

  .thread-lock-flow__lane {
    grid-template-columns: 54px minmax(0, 1fr);
    min-height: 58px;
    padding: 8px 10px;
  }

  .thread-lock-flow__machine > div {
    min-height: 68px;
    padding: 8px;
  }

  .thread-lock-flow__permit {
    grid-template-columns: minmax(88px, 0.65fr) minmax(95px, 1fr);
    gap: 8px 10px;
  }

  .thread-lock-flow__permit p {
    grid-column: 1 / -1;
    text-align: left;
  }
}

@media (max-width: 360px) {
  .thread-lock-flow__event {
    display: grid;
    gap: 3px;
  }

  .thread-lock-flow__event strong {
    text-align: left;
  }

  .thread-lock-flow__machine strong {
    font-size: 0.68rem;
  }
}
</style>
