<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type Tone = 'idle' | 'active' | 'waiting' | 'success' | 'failure'

interface WaiterSnapshot {
  name: string
  position: string
  detail: string
  tone: Tone
}

interface CancelSnapshot {
  state: string
  runner: string
  effect: string
  tone: Tone
}

interface FutureTaskSnapshot {
  state: string
  stateTone: Tone
  runner: string
  runnerTone: Tone
  outcome: string
  callable: string
  waitersHead: string
  waiters: [WaiterSnapshot, WaiterSnapshot]
  completion: string
  phase: string
  cancelFalse: CancelSnapshot
  cancelTrue: CancelSnapshot
}

const idleCancelFalse: CancelSnapshot = {
  state: 'NEW',
  runner: '独立任务 A',
  effect: '尚未调用 cancel(false)',
  tone: 'idle'
}

const idleCancelTrue: CancelSnapshot = {
  state: 'NEW',
  runner: '独立任务 B',
  effect: '尚未调用 cancel(true)',
  tone: 'idle'
}

const steps: SourceAnimationStep[] = [
  {
    title: '执行线程取得 runner',
    method: 'CAS(runner, null, atlas-runner)',
    description: 'state 仍是 NEW。runner CAS 只决定谁能调用 Callable，不会把 state 改成一个不存在的 RUNNING 状态。'
  },
  {
    title: 'W1 注册等待节点',
    method: 'W1: get() -> awaitDone -> push WaitNode',
    description: 'W1 发现 state 仍是 NEW，创建 WaitNode 并 CAS 成为 waiters 栈顶，随后通过 LockSupport.park 等待。'
  },
  {
    title: 'W2 压到 Treiber 栈顶',
    method: 'W2: q.next = W1; CAS(waiters, W1, W2)',
    description: '后到的 W2 成为栈顶，链为 W2 -> W1。这个栈用于无锁注册，不提供等待者恢复的公平顺序。'
  },
  {
    title: 'Callable 计算出结果',
    method: 'result = callable.call() // 42',
    description: '返回 42 还不等于 FutureTask 已完成。执行线程必须在 set 中赢得 state CAS，才能把结果发布给等待者。'
  },
  {
    title: '赢得结果发布权',
    method: 'CAS(state, NEW, COMPLETING)',
    description: 'COMPLETING 是短暂中间态：正常、异常和取消竞争已经分出胜负，但 outcome 尚不能被 get 解释。'
  },
  {
    title: '先写普通字段 outcome',
    method: 'outcome = 42',
    description: 'state 仍为 COMPLETING。等待者即使被虚假唤醒也只会 yield 并重读 state，不会提前 report 半发布结果。'
  },
  {
    title: '发布 NORMAL 并摘下整栈',
    method: 'putOrderedInt(state, NORMAL); CAS(waiters, W2, null)',
    description: 'release/ordered 写让 outcome 对读取终态的线程可见。finishCompletion 把共享 waiters 清空，并在局部 q 中保留 W2 -> W1。'
  },
  {
    title: '先给 W2 发放许可',
    method: 'q = W2; q.thread = null; unpark(W2)',
    description: 'W2 是旧栈顶，所以完成线程先调用 unpark(W2)。这只是发放许可，W2 不保证比 W1 更早真正返回。'
  },
  {
    title: '再给 W1 发放许可',
    method: 'q = W1; q.thread = null; unpark(W1)',
    description: '完成线程继续遍历局部链并 unpark W1。两个等待者恢复后都重新读取 NORMAL，再由 report 返回 42。'
  },
  {
    title: '执行完成钩子与清理',
    method: 'done(); callable = null',
    description: '等待者已获得许可后才调用 done，因此 get 可能与 done 并发返回。最后清空 callable，runner 则由 run 的 finally 清空。'
  },
  {
    title: 'cancel false 不发送中断',
    method: 'taskA: CAS(NEW, CANCELLED)',
    description: '这是独立任务 A。即使 runner 已在执行，cancel(false) 也可能成功；Future 立即取消，但 Callable 不会收到中断并可能继续产生副作用。'
  },
  {
    title: 'cancel true 进入中断握手',
    method: 'taskB: CAS(NEW, INTERRUPTING); runner.interrupt()',
    description: '这是独立任务 B。cancel(true) 只向当时的 runner 发出中断请求；INTERRUPTING 表示请求过程尚未完成，不表示线程已经停止。'
  },
  {
    title: '发布 INTERRUPTED 终态',
    method: 'setRelease(INTERRUPTED); finishCompletion()',
    description: '本例 Callable 在可中断等待中捕获 InterruptedException 并协作退出。若任务忽略中断，它仍可继续运行；cancel(true) 从来不是强制停止。'
  }
]

// 每个快照描述源码协议中的一个确定阶段；取消面板使用另外两个任务，避免暗示终态可以再次迁移。
const snapshots: FutureTaskSnapshot[] = [
  {
    state: 'NEW',
    stateTone: 'active',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '未写入',
    callable: '正在执行',
    waitersHead: 'null',
    waiters: [
      { name: 'W1', position: '未入栈', detail: '尚未调用 get', tone: 'idle' },
      { name: 'W2', position: '未入栈', detail: '尚未调用 get', tone: 'idle' }
    ],
    completion: 'run 已取得一次执行权',
    phase: 'state 与 runner 分工：state=NEW 不代表 Callable 尚未开始',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NEW',
    stateTone: 'active',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '未写入',
    callable: '被闸门阻塞',
    waitersHead: 'W1',
    waiters: [
      { name: 'W1', position: '栈顶', detail: '已 park，等待完成', tone: 'waiting' },
      { name: 'W2', position: '未入栈', detail: '尚未调用 get', tone: 'idle' }
    ],
    completion: 'waiters -> W1 -> null',
    phase: 'W1 的 WaitNode.thread 保存等待线程引用',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NEW',
    stateTone: 'active',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '未写入',
    callable: '被闸门阻塞',
    waitersHead: 'W2',
    waiters: [
      { name: 'W1', position: 'W2.next', detail: '已 park，等待完成', tone: 'waiting' },
      { name: 'W2', position: '栈顶', detail: '已 park，等待完成', tone: 'waiting' }
    ],
    completion: 'waiters -> W2 -> W1 -> null',
    phase: 'Treiber 栈后进先出，但不承诺线程恢复顺序',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NEW',
    stateTone: 'active',
    runner: 'atlas-runner',
    runnerTone: 'success',
    outcome: '未写入',
    callable: '已返回 42',
    waitersHead: 'W2',
    waiters: [
      { name: 'W1', position: 'W2.next', detail: '仍在等待终态', tone: 'waiting' },
      { name: 'W2', position: '栈顶', detail: '仍在等待终态', tone: 'waiting' }
    ],
    completion: '局部 result=42，尚未发布',
    phase: 'Callable 返回与 Future 完成不是同一个线性化点',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'COMPLETING',
    stateTone: 'active',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '未写入',
    callable: 'set(42)',
    waitersHead: 'W2',
    waiters: [
      { name: 'W1', position: 'W2.next', detail: '被唤醒也只会 yield', tone: 'waiting' },
      { name: 'W2', position: '栈顶', detail: '被唤醒也只会 yield', tone: 'waiting' }
    ],
    completion: '正常完成线程赢得 state CAS',
    phase: '取消线程此后再 CAS NEW 会失败',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'COMPLETING',
    stateTone: 'active',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '42',
    callable: 'set(42)',
    waitersHead: 'W2',
    waiters: [
      { name: 'W1', position: 'W2.next', detail: '不能提前 report', tone: 'waiting' },
      { name: 'W2', position: '栈顶', detail: '不能提前 report', tone: 'waiting' }
    ],
    completion: 'outcome 已写，终态尚未发布',
    phase: 'outcome 是普通字段，其可见性由随后 state 发布保护',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NORMAL',
    stateTone: 'success',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '42',
    callable: '等待清理',
    waitersHead: 'null（局部 q=W2）',
    waiters: [
      { name: 'W1', position: '局部链第二个', detail: '等待 unpark', tone: 'waiting' },
      { name: 'W2', position: '局部 q 栈顶', detail: '等待 unpark', tone: 'active' }
    ],
    completion: '终态已发布，整条等待栈已摘下',
    phase: '之后新来的 get 直接 report，不再创建 WaitNode',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NORMAL',
    stateTone: 'success',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '42',
    callable: '等待清理',
    waitersHead: 'null（局部 q=W1）',
    waiters: [
      { name: 'W1', position: '局部 q 栈顶', detail: '等待 unpark', tone: 'waiting' },
      { name: 'W2', position: '已处理', detail: '已发放 unpark 许可', tone: 'success' }
    ],
    completion: 'q.thread=null；unpark(W2)',
    phase: '发放许可后，W2 何时获得 CPU 不确定',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NORMAL',
    stateTone: 'success',
    runner: 'atlas-runner',
    runnerTone: 'active',
    outcome: '42',
    callable: '等待清理',
    waitersHead: 'null（局部链结束）',
    waiters: [
      { name: 'W1', position: '已处理', detail: '已发放 unpark 许可', tone: 'success' },
      { name: 'W2', position: '已处理', detail: '重新检查 state', tone: 'success' }
    ],
    completion: 'q.thread=null；unpark(W1)',
    phase: '两个等待者都将按 NORMAL 从 report 取得 42',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NORMAL',
    stateTone: 'success',
    runner: 'null',
    runnerTone: 'success',
    outcome: '42',
    callable: 'null',
    waitersHead: 'null',
    waiters: [
      { name: 'W1', position: 'get 返回', detail: 'result=42', tone: 'success' },
      { name: 'W2', position: 'get 返回', detail: 'result=42', tone: 'success' }
    ],
    completion: 'done 已调用，引用清理完成',
    phase: '等待者可能在 done 结束前就已经恢复执行',
    cancelFalse: idleCancelFalse,
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NORMAL（主线已完成）',
    stateTone: 'success',
    runner: 'null',
    runnerTone: 'success',
    outcome: '42',
    callable: 'null',
    waitersHead: 'null',
    waiters: [
      { name: 'W1', position: '主线结束', detail: 'result=42', tone: 'success' },
      { name: 'W2', position: '主线结束', detail: 'result=42', tone: 'success' }
    ],
    completion: '切换观察独立任务 A',
    phase: '取消面板不是从主线 NORMAL 状态继续迁移',
    cancelFalse: {
      state: 'CANCELLED',
      runner: 'runner 仍在执行',
      effect: '不调用 interrupt；后续 set 失败',
      tone: 'active'
    },
    cancelTrue: idleCancelTrue
  },
  {
    state: 'NORMAL（主线已完成）',
    stateTone: 'success',
    runner: 'null',
    runnerTone: 'success',
    outcome: '42',
    callable: 'null',
    waitersHead: 'null',
    waiters: [
      { name: 'W1', position: '主线结束', detail: 'result=42', tone: 'success' },
      { name: 'W2', position: '主线结束', detail: 'result=42', tone: 'success' }
    ],
    completion: '切换观察独立任务 B',
    phase: 'INTERRUPTING 只覆盖发出中断请求的短暂窗口',
    cancelFalse: {
      state: 'CANCELLED',
      runner: 'runner 仍可继续',
      effect: '无中断请求',
      tone: 'idle'
    },
    cancelTrue: {
      state: 'INTERRUPTING',
      runner: 'runner.interrupt()',
      effect: '已请求中断，尚未发布最终取消状态',
      tone: 'active'
    }
  },
  {
    state: 'NORMAL（主线已完成）',
    stateTone: 'success',
    runner: 'null',
    runnerTone: 'success',
    outcome: '42',
    callable: 'null',
    waitersHead: 'null',
    waiters: [
      { name: 'W1', position: '主线结束', detail: 'result=42', tone: 'success' },
      { name: 'W2', position: '主线结束', detail: 'result=42', tone: 'success' }
    ],
    completion: '独立任务 B 完成取消收尾',
    phase: '本例任务协作退出；忽略中断的任务仍可能继续运行',
    cancelFalse: {
      state: 'CANCELLED',
      runner: 'runner 仍可继续',
      effect: '无中断请求',
      tone: 'idle'
    },
    cancelTrue: {
      state: 'INTERRUPTED',
      runner: 'Callable 协作退出',
      effect: 'Future 已取消，不代表 JVM 强制停止线程',
      tone: 'success'
    }
  }
]
</script>

<template>
  <SourceAnimation title="FutureTask 如何发布结果、唤醒等待者并处理取消" :steps="steps" :interval="2500">
    <template #visual="{ currentIndex }">
      <div class="future-task-flow">
        <div class="future-task-flow__notice">
          <strong>{{ currentIndex < 10 ? '主线：正常完成' : '取消边界：独立任务' }}</strong>
          <span>{{ snapshots[currentIndex].phase }}</span>
        </div>

        <div class="future-task-flow__main" :class="{ 'is-reference': currentIndex >= 10 }">
          <section class="future-task-state" :class="[`is-${snapshots[currentIndex].stateTone}`]">
            <header>
              <span>FutureTask</span>
              <strong>{{ snapshots[currentIndex].state }}</strong>
            </header>

            <div class="future-task-state__fields">
              <div :class="[`is-${snapshots[currentIndex].runnerTone}`]">
                <span>runner</span>
                <code>{{ snapshots[currentIndex].runner }}</code>
              </div>
              <div>
                <span>outcome</span>
                <code>{{ snapshots[currentIndex].outcome }}</code>
              </div>
              <div>
                <span>callable</span>
                <code>{{ snapshots[currentIndex].callable }}</code>
              </div>
            </div>

            <footer>{{ snapshots[currentIndex].completion }}</footer>
          </section>

          <section class="future-task-waiters">
            <header>
              <span>WaitNode Treiber 栈</span>
              <code>waiters = {{ snapshots[currentIndex].waitersHead }}</code>
            </header>
            <div class="future-task-waiters__list">
              <div
                v-for="waiter in snapshots[currentIndex].waiters"
                :key="waiter.name"
                class="future-task-waiter"
                :class="[`is-${waiter.tone}`]"
              >
                <strong>{{ waiter.name }}</strong>
                <span>{{ waiter.position }}</span>
                <code>{{ waiter.detail }}</code>
              </div>
            </div>
          </section>
        </div>

        <div class="future-task-cancel" aria-label="取消分支边界">
          <section :class="[`is-${snapshots[currentIndex].cancelFalse.tone}`]">
            <header>
              <span>独立任务 A</span>
              <strong>cancel(false)</strong>
            </header>
            <code>{{ snapshots[currentIndex].cancelFalse.state }}</code>
            <p>{{ snapshots[currentIndex].cancelFalse.runner }}</p>
            <small>{{ snapshots[currentIndex].cancelFalse.effect }}</small>
          </section>

          <section :class="[`is-${snapshots[currentIndex].cancelTrue.tone}`]">
            <header>
              <span>独立任务 B</span>
              <strong>cancel(true)</strong>
            </header>
            <code>{{ snapshots[currentIndex].cancelTrue.state }}</code>
            <p>{{ snapshots[currentIndex].cancelTrue.runner }}</p>
            <small>{{ snapshots[currentIndex].cancelTrue.effect }}</small>
          </section>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.future-task-flow {
  display: grid;
  gap: 14px;
  min-height: 430px;
}

/* 说明长度随步骤变化，预留高度避免自动播放时舞台上下跳动。 */
:deep(.source-animation__explanation) {
  min-height: 126px;
}

.future-task-flow__notice {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 5px 16px;
  min-height: 28px;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.future-task-flow__notice strong {
  color: var(--atlas-coral);
}

.future-task-flow__notice span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.future-task-flow__main {
  display: grid;
  grid-template-columns: minmax(250px, 1fr) minmax(280px, 1.1fr);
  gap: 12px;
  transition: opacity 180ms ease;
}

.future-task-flow__main.is-reference {
  opacity: 0.62;
}

.future-task-state,
.future-task-waiters {
  display: grid;
  align-content: start;
  min-width: 0;
  min-height: 220px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
}

.future-task-state {
  grid-template-rows: auto 1fr auto;
  transition: border-color 180ms ease, background 180ms ease;
}

.future-task-state.is-active {
  border-color: var(--atlas-coral);
}

.future-task-state.is-success {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.future-task-state > header,
.future-task-waiters > header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 12px;
  min-height: 48px;
  padding: 11px 13px;
  border-bottom: 1px solid var(--atlas-line);
}

.future-task-state > header span,
.future-task-waiters > header span {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
  font-weight: 700;
}

.future-task-state > header strong,
.future-task-waiters > header code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.future-task-state__fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  align-self: stretch;
  background: var(--atlas-line);
}

.future-task-state__fields > div {
  display: grid;
  place-content: center;
  gap: 6px;
  min-width: 0;
  min-height: 108px;
  padding: 9px;
  background: var(--vp-c-bg);
  text-align: center;
  transition: background 180ms ease;
}

.future-task-state__fields > div.is-active {
  background: color-mix(in srgb, var(--atlas-coral) 9%, var(--vp-c-bg));
}

.future-task-state__fields > div.is-success {
  background: var(--vp-c-brand-soft);
}

.future-task-state__fields span {
  color: var(--vp-c-text-3);
  font-size: 0.62rem;
}

.future-task-state__fields code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.68rem;
}

.future-task-state > footer {
  min-height: 44px;
  padding: 10px 13px;
  color: var(--vp-c-text-2);
  font-size: 0.69rem;
  font-weight: 700;
}

.future-task-waiters__list {
  display: grid;
  gap: 9px;
  padding: 13px;
}

.future-task-waiter {
  display: grid;
  grid-template-columns: 38px minmax(0, 0.75fr) minmax(0, 1.35fr);
  gap: 8px;
  align-items: center;
  min-width: 0;
  min-height: 64px;
  padding: 8px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease, transform 180ms ease;
}

.future-task-waiter strong,
.future-task-waiter span,
.future-task-waiter code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.future-task-waiter strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
}

.future-task-waiter span {
  color: var(--vp-c-text-2);
  font-size: 0.67rem;
  font-weight: 700;
}

.future-task-waiter code {
  color: var(--vp-c-text-3);
  font-size: 0.64rem;
}

.future-task-waiter.is-waiting {
  border-left-color: var(--atlas-coral);
}

.future-task-waiter.is-active {
  border-left-color: var(--atlas-coral);
  transform: translateY(-2px);
}

.future-task-waiter.is-success {
  border-left-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.future-task-cancel {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.future-task-cancel > section {
  display: grid;
  grid-template-columns: minmax(0, 0.75fr) minmax(0, 1.25fr);
  gap: 4px 12px;
  min-width: 0;
  min-height: 106px;
  padding: 11px 13px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  opacity: 0.62;
  transition: border-color 180ms ease, background 180ms ease, opacity 180ms ease, transform 180ms ease;
}

.future-task-cancel > section.is-active {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, var(--vp-c-bg));
  opacity: 1;
  transform: translateY(-2px);
}

.future-task-cancel > section.is-success {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  opacity: 1;
}

.future-task-cancel header {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.future-task-cancel header span {
  color: var(--vp-c-text-3);
  font-size: 0.61rem;
}

.future-task-cancel header strong {
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.future-task-cancel > section > code {
  align-self: center;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.72rem;
  font-weight: 700;
  text-align: right;
}

.future-task-cancel p,
.future-task-cancel small {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.future-task-cancel p {
  color: var(--vp-c-text-2);
  font-size: 0.66rem;
}

.future-task-cancel small {
  color: var(--vp-c-text-3);
  font-size: 0.62rem;
}

@media (max-width: 760px) {
  .future-task-flow {
    min-height: 0;
  }

  :deep(.source-animation__explanation) {
    min-height: 0;
  }

  .future-task-flow__main,
  .future-task-cancel {
    grid-template-columns: 1fr;
  }

  .future-task-state,
  .future-task-waiters {
    min-height: 0;
  }

  .future-task-state__fields {
    grid-template-columns: 1fr;
  }

  .future-task-state__fields > div {
    min-height: 62px;
  }
}

@media (max-width: 420px) {
  .future-task-waiter {
    grid-template-columns: 34px minmax(0, 1fr);
  }

  .future-task-waiter code {
    grid-column: 1 / -1;
  }

  .future-task-cancel > section {
    grid-template-columns: 1fr;
  }

  .future-task-cancel > section > code {
    text-align: left;
  }
}

@media (prefers-reduced-motion: reduce) {
  .future-task-flow__main,
  .future-task-state,
  .future-task-state__fields > div,
  .future-task-waiter,
  .future-task-cancel > section {
    transition: none;
  }
}
</style>
