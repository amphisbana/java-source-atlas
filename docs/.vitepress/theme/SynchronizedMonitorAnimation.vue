<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type MonitorTone = 'bytecode' | 'owned' | 'blocked' | 'waiting' | 'notified' | 'done'

interface MonitorSnapshot {
  event: string
  owner: string
  recursion: string
  entryList: string
  waitSet: string
  t1: string
  t2: string
  condition: string
  tone: MonitorTone
}

// EntryList 在图中代表入口竞争集合；具体 cxq/EntryList 移动策略属于 HotSpot 版本实现。
const steps: SourceAnimationStep[] = [
  {
    title: '同步块编译为 monitorenter',
    method: 'aload monitor; monitorenter',
    description: 'synchronized 代码块在字节码中显式进入 monitor；同步方法则使用 ACC_SYNCHRONIZED 标志，由调用指令和 JVM 共同完成进入。'
  },
  {
    title: 'T1 首次取得 owner',
    method: 'monitorenter → owner = T1',
    description: 'monitor 空闲时 T1 成为所有者。JVM 必须提供互斥和 happens-before，是否使用轻量路径或膨胀 ObjectMonitor 属于实现选择。'
  },
  {
    title: 'T1 再次进入同一 monitor',
    method: 'reentrant monitorenter',
    description: 'synchronized 可重入。动画用 Java 深度 2 表示进入两次；JDK 8 ObjectMonitor 的 _recursions 记录首层之外的重入，因此此时内部值为 1。'
  },
  {
    title: 'T2 竞争失败进入 BLOCKED',
    method: 'T2 monitorenter → contend',
    description: 'T2 等待进入 synchronized，公开 Thread.State 是 BLOCKED。它尚未执行 Object.wait，也不在 WaitSet 中。'
  },
  {
    title: '第一次 monitorexit 只减重入',
    method: 'T1 monitorexit → depth 2 → 1',
    description: '退出内层 synchronized 只减少一次持有计数，owner 仍是 T1，T2 仍不能进入。'
  },
  {
    title: '最后一次退出才释放 owner',
    method: 'T1 monitorexit → owner = null',
    description: '与进入次数匹配的最后一次退出才释放 monitor。解锁 happens-before 后续成功锁定同一个 monitor。'
  },
  {
    title: '入口竞争者 T2 取得 monitor',
    method: 'T2 reenter → owner = T2',
    description: '释放不承诺严格 FIFO；调度器与 JVM 入口队列策略共同决定谁先成功。这里只展示 T2 获胜的一种合法执行。'
  },
  {
    title: 'T1 以两层重入进入条件循环',
    method: 'synchronized × 2; while (!ready)',
    description: '调用 wait 前必须持有目标对象 monitor，并始终在 while 中检查业务谓词；notify 只是状态变化后的协作信号。'
  },
  {
    title: 'wait 保存深度并完整释放',
    method: 'Object.wait → save depth; exit monitor',
    description: 'wait 把 T1 放入 WaitSet，完整释放全部重入层数并进入 WAITING。普通 synchronized 退出一层与 wait 的完整释放不是一回事。'
  },
  {
    title: 'T2 修改条件并持有 monitor',
    method: 'synchronized (monitor) { ready = true; }',
    description: 'T1 已释放 monitor，所以 T2 可以进入并修改受同一锁保护的条件。条件写必须先于通知，且二者都在 synchronized 内。'
  },
  {
    title: 'notify 选择一个等待者',
    method: 'monitor.notify()',
    description: 'notify 从该 monitor 的等待集中选择一个线程，使其具备重新竞争资格；选择哪个线程没有顺序保证。'
  },
  {
    title: '被通知线程转为入口竞争',
    method: 'WaitSet → entry contenders',
    description: 'T1 离开 WaitSet 后仍拿不到锁，因为 owner 还是 T2。图中的 EntryList 是入口竞争集合示意，JDK 8 HotSpot 还可能经过 cxq。'
  },
  {
    title: 'notify 返回但 T2 继续持锁',
    method: 'notify(); doMoreWork()',
    description: 'notify 不是解锁操作。T2 可以继续执行同步块中的代码，T1 此时通常表现为 BLOCKED，不能从 wait 后立即继续。'
  },
  {
    title: 'T2 退出后 T1 重新取得锁',
    method: 'T2 monitorexit → T1 reenter',
    description: '只有 T2 释放后 T1 才能重新竞争。wait 返回前会重新取得同一 monitor，并恢复等待前保存的重入层数。'
  },
  {
    title: '恢复深度并再次检查条件',
    method: 'wait returns → while (!ready) recheck',
    description: '正常通知、超时、中断和伪唤醒都要求围绕业务谓词设计循环。被中断的 wait 在重新取得 monitor 后抛 InterruptedException，并清除中断标记。'
  }
]

const snapshots: MonitorSnapshot[] = [
  { event: '字节码入口', owner: '未进入运行时', recursion: 'Java 深度 0', entryList: '空', waitSet: '空', t1: 'RUNNABLE：执行 monitorenter', t2: '尚未竞争', condition: 'ready = false', tone: 'bytecode' },
  { event: '首次获取', owner: 'T1', recursion: 'Java 深度 1 / _recursions=0', entryList: '空', waitSet: '空', t1: 'RUNNABLE：持有 monitor', t2: 'RUNNABLE', condition: 'ready = false', tone: 'owned' },
  { event: '可重入', owner: 'T1', recursion: 'Java 深度 2 / _recursions=1', entryList: '空', waitSet: '空', t1: 'RUNNABLE：第二次进入', t2: 'RUNNABLE', condition: 'ready = false', tone: 'owned' },
  { event: '入口竞争', owner: 'T1', recursion: 'Java 深度 2 / _recursions=1', entryList: 'T2', waitSet: '空', t1: 'RUNNABLE：临界区', t2: 'BLOCKED：等待 monitorenter', condition: 'ready = false', tone: 'blocked' },
  { event: '退出一层', owner: 'T1', recursion: 'Java 深度 1 / _recursions=0', entryList: 'T2', waitSet: '空', t1: 'RUNNABLE：仍持锁', t2: 'BLOCKED', condition: 'ready = false', tone: 'owned' },
  { event: '完全释放', owner: 'null', recursion: 'Java 深度 0', entryList: 'T2 可竞争', waitSet: '空', t1: 'RUNNABLE：已退出', t2: 'RUNNABLE：尝试取得', condition: 'ready = false', tone: 'done' },
  { event: '竞争成功', owner: 'T2', recursion: 'Java 深度 1 / _recursions=0', entryList: '空', waitSet: '空', t1: 'RUNNABLE', t2: 'RUNNABLE：进入临界区', condition: 'ready = false', tone: 'owned' },
  { event: '准备条件等待', owner: 'T1', recursion: 'Java 深度 2 / _recursions=1', entryList: '空', waitSet: '空', t1: 'RUNNABLE：while 检查 false', t2: 'RUNNABLE', condition: 'ready = false', tone: 'owned' },
  { event: '进入 WaitSet', owner: 'null', recursion: '已保存 Java 深度 2', entryList: '空', waitSet: 'T1', t1: 'WAITING：完整释放 monitor', t2: 'RUNNABLE', condition: 'ready = false', tone: 'waiting' },
  { event: '修改条件', owner: 'T2', recursion: 'Java 深度 1', entryList: '空', waitSet: 'T1', t1: 'WAITING', t2: 'RUNNABLE：写 ready=true', condition: 'ready = true', tone: 'owned' },
  { event: '发出单个通知', owner: 'T2', recursion: 'Java 深度 1', entryList: 'T1 待转移', waitSet: 'T1 被选中', t1: 'WAITING → 入口竞争', t2: 'RUNNABLE：调用 notify', condition: 'ready = true', tone: 'notified' },
  { event: '等待重新获取', owner: 'T2', recursion: 'Java 深度 1', entryList: 'T1（可能经 cxq）', waitSet: '空', t1: 'BLOCKED：等待重新进入', t2: 'RUNNABLE：仍持锁', condition: 'ready = true', tone: 'blocked' },
  { event: '通知后继续工作', owner: 'T2', recursion: 'Java 深度 1', entryList: 'T1', waitSet: '空', t1: 'BLOCKED：尚未从 wait 返回', t2: 'RUNNABLE：doMoreWork', condition: 'ready = true', tone: 'blocked' },
  { event: '重新竞争成功', owner: 'T1', recursion: '恢复 Java 深度 2 / _recursions=1', entryList: '空', waitSet: '空', t1: 'RUNNABLE：即将从 wait 返回', t2: 'RUNNABLE：已退出', condition: 'ready = true', tone: 'notified' },
  { event: '条件循环收口', owner: 'T1', recursion: 'Java 深度 2', entryList: '空', waitSet: '空', t1: 'RUNNABLE：重新检查 ready', t2: 'RUNNABLE', condition: 'ready = true，继续业务', tone: 'done' }
]
</script>

<template>
  <SourceAnimation title="从 monitorenter、重入到 wait-notify 重新竞争" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="monitor-flow" :class="`is-${snapshots[currentIndex].tone}`">
        <div class="monitor-flow__event">
          <span>当前事件</span>
          <strong>{{ snapshots[currentIndex].event }}</strong>
          <code>{{ snapshots[currentIndex].condition }}</code>
        </div>

        <div class="monitor-flow__threads">
          <section>
            <span>T1</span>
            <strong>{{ snapshots[currentIndex].t1 }}</strong>
          </section>
          <section>
            <span>T2</span>
            <strong>{{ snapshots[currentIndex].t2 }}</strong>
          </section>
        </div>

        <div class="monitor-flow__monitor" aria-label="monitor 内部状态示意">
          <div class="monitor-flow__owner">
            <span>owner</span>
            <strong>{{ snapshots[currentIndex].owner }}</strong>
            <small>{{ snapshots[currentIndex].recursion }}</small>
          </div>
          <div>
            <span>入口竞争集合</span>
            <strong>{{ snapshots[currentIndex].entryList }}</strong>
            <small>HotSpot: cxq / _EntryList</small>
          </div>
          <div>
            <span>条件等待集合</span>
            <strong>{{ snapshots[currentIndex].waitSet }}</strong>
            <small>HotSpot: _WaitSet</small>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.monitor-flow {
  display: grid;
  min-width: 0;
  min-height: 320px;
  gap: 18px;
  align-content: center;
}

.monitor-flow__event {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  min-width: 0;
  gap: 8px 14px;
  align-items: center;
}

.monitor-flow span,
.monitor-flow small {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-weight: 700;
}

.monitor-flow strong,
.monitor-flow code,
.monitor-flow small {
  min-width: 0;
  overflow-wrap: anywhere;
}

.monitor-flow__event code {
  padding: 7px 10px;
  border-radius: 4px;
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.monitor-flow__threads,
.monitor-flow__monitor {
  display: grid;
  min-width: 0;
  gap: 12px;
}

.monitor-flow__threads {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.monitor-flow__threads section,
.monitor-flow__monitor > div {
  display: grid;
  min-width: 0;
  min-height: 82px;
  gap: 7px;
  align-content: center;
  padding: 13px;
  border-block: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.monitor-flow__threads section:first-child { border-left: 4px solid var(--vp-c-brand-1); }
.monitor-flow__threads section:last-child { border-left: 4px solid var(--atlas-coral); }

.monitor-flow__monitor {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.monitor-flow__monitor > div { border-top: 3px solid var(--atlas-line); }
.monitor-flow__owner { border-top-color: var(--vp-c-brand-1) !important; }
.monitor-flow.is-blocked .monitor-flow__monitor > div:nth-child(2) { border-top-color: var(--atlas-coral); }
.monitor-flow.is-waiting .monitor-flow__monitor > div:nth-child(3) { border-top-color: var(--atlas-gold); }
.monitor-flow.is-notified .monitor-flow__owner { border-top-color: var(--atlas-purple) !important; }

@media (max-width: 700px) {
  .monitor-flow { min-height: 430px; }
  .monitor-flow__event { grid-template-columns: 1fr; }
  .monitor-flow__event code { justify-self: stretch; }
  .monitor-flow__monitor { grid-template-columns: 1fr; }
}

@media (max-width: 420px) {
  .monitor-flow { min-height: 540px; gap: 12px; }
  .monitor-flow__threads { grid-template-columns: 1fr; }
  .monitor-flow__threads section,
  .monitor-flow__monitor > div { min-height: 68px; padding: 10px; }
}
</style>
