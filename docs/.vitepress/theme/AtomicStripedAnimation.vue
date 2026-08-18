<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type ThreadTone = '' | 'active' | 'success' | 'failure'
type BaseTone = '' | 'active' | 'success'

interface AtomicThreadSnapshot {
  name: string
  state: string
  detail: string
  tone: ThreadTone
}

interface AtomicSnapshot {
  threads: AtomicThreadSnapshot[]
  base: number
  baseTone: BaseTone
  cellsLength: 0 | 2 | 4
  cells: Array<number | null>
  activeCells: number[]
  collisionCells: number[]
  cellsBusy: string
  collide: string
  pending: string
  committedTotal: number
  phase: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '两个线程读取同一 base',
    method: 'b = base; casBase(b, b + x)',
    description: '这里选择一种合法交错：T1、T2 都在任一 CAS 发生前读到 base=0。真实运行时谁先读、谁获胜都不固定。'
  },
  {
    title: 'base CAS 一成一败',
    method: 'T1: casBase(0, 1) == true; T2: false',
    description: 'T1 先把 base 改为 1；T2 仍携带期望值 0，因此 CAS 失败。CAS 只决定这一次竞争的获胜者，不会阻塞线程。'
  },
  {
    title: '竞争路径初始化 cells',
    method: 'longAccumulate(1, null, true) → new Cell[2]',
    description: 'T2 发现 cells 仍为 null，在 cellsBusy 为 0 时 CAS 取得初始化权；它创建长度 2 的数组，并按 probe 把增量放入一个 Cell。'
  },
  {
    title: 'probe 定位到 Cell',
    method: 'a = cells[(n - 1) & getProbe()]',
    description: '后续 add 优先按各线程的 probe 选槽。示例中的两个 probe 低位相同，所以都命中 Cell[1]；这只是可能发生的哈希碰撞。'
  },
  {
    title: 'Cell CAS 再次冲突',
    method: 'a.cas(v, v + x)',
    description: 'T1 把 Cell[1] 从 1 改为 2，T2 使用旧期望值 1 时失败，于是带着尚未提交的增量进入 longAccumulate。'
  },
  {
    title: '先重哈希并记录碰撞',
    method: 'wasUncontended = true; probe = advanceProbe(probe)',
    description: '源码不会在第一次 Cell CAS 失败后立刻扩容：先修正 uncontended、推进 probe；后续仍碰撞时才把 collide 置为 true。图中合并展示这些循环轮次。'
  },
  {
    title: '持续碰撞后扩容',
    method: 'collide && n < NCPU && casCellsBusy() → new Cell[n << 1]',
    description: '在 cells 引用未变化、长度仍小于 CPU 数且再次 CAS 失败时，T2 取得 cellsBusy，把 Cell[2] 扩为 Cell[4]；旧 Cell 引用被复制到同一索引。'
  },
  {
    title: '利用新槽提交增量',
    method: 'cells[probe & 3] == null → cells[3] = new Cell(1)',
    description: '扩容后，同一个 probe 在更大的掩码下可落到新槽。T2 CAS 取得 cellsBusy 后安装 Cell(1)，此前待提交的增量终于计入。'
  },
  {
    title: '聚合 base 与所有 Cell',
    method: 'sum = base + Σ cell.value',
    description: '在本例不再发生写入的稳定时点，sum() 得到 1 + 4 + 1 = 6。sum() 遍历时不加锁，并发更新期间不承诺原子快照。'
  }
]

// 每个快照表示一种允许出现的执行交错，不代表 JVM 会按这个固定顺序调度线程。
const snapshots: AtomicSnapshot[] = [
  {
    threads: [
      { name: 'T1', state: '读取 base = 0', detail: '期望 0，待加 1', tone: 'active' },
      { name: 'T2', state: '读取 base = 0', detail: '期望 0，待加 1', tone: 'active' }
    ],
    base: 0,
    baseTone: 'active',
    cellsLength: 0,
    cells: [null, null, null, null],
    activeCells: [],
    collisionCells: [],
    cellsBusy: '0',
    collide: 'false',
    pending: 'T1 +1，T2 +1',
    committedTotal: 0,
    phase: '两次读取先于任一 CAS（示例交错）'
  },
  {
    threads: [
      { name: 'T1', state: 'CAS 0 → 1 成功', detail: '本次 add 已提交', tone: 'success' },
      { name: 'T2', state: 'CAS 0 → 1 失败', detail: '实际 base 已是 1', tone: 'failure' }
    ],
    base: 1,
    baseTone: 'success',
    cellsLength: 0,
    cells: [null, null, null, null],
    activeCells: [],
    collisionCells: [],
    cellsBusy: '0',
    collide: 'false',
    pending: 'T2 +1',
    committedTotal: 1,
    phase: 'CAS 线性化顺序由实际竞争决定'
  },
  {
    threads: [
      { name: 'T1', state: '本次 add 已完成', detail: 'base 路径', tone: '' },
      { name: 'T2', state: '初始化 Cell[2]', detail: 'probe p2 & 1 = 1', tone: 'success' }
    ],
    base: 1,
    baseTone: '',
    cellsLength: 2,
    cells: [null, 1, null, null],
    activeCells: [1],
    collisionCells: [],
    cellsBusy: '0 → 1 → 0',
    collide: 'false',
    pending: '无',
    committedTotal: 2,
    phase: '只有取得 cellsBusy 的线程可以发布 cells'
  },
  {
    threads: [
      { name: 'T1', state: '命中 Cell[1]', detail: 'probe p1 & 1 = 1', tone: 'active' },
      { name: 'T2', state: '命中 Cell[1]', detail: 'probe p2 & 1 = 1', tone: 'active' }
    ],
    base: 1,
    baseTone: '',
    cellsLength: 2,
    cells: [null, 1, null, null],
    activeCells: [1],
    collisionCells: [],
    cellsBusy: '0',
    collide: 'false',
    pending: 'T1 +1，T2 +1',
    committedTotal: 2,
    phase: 'probe 只负责选槽，不保证线程独占 Cell'
  },
  {
    threads: [
      { name: 'T1', state: 'Cell CAS 1 → 2 成功', detail: '本次 add 已提交', tone: 'success' },
      { name: 'T2', state: 'Cell CAS 1 → 2 失败', detail: '携带 +1 进入慢路径', tone: 'failure' }
    ],
    base: 1,
    baseTone: '',
    cellsLength: 2,
    cells: [null, 2, null, null],
    activeCells: [1],
    collisionCells: [1],
    cellsBusy: '0',
    collide: 'false',
    pending: 'T2 +1',
    committedTotal: 3,
    phase: 'Cell CAS 失败仍是无锁重试，不会等待互斥锁'
  },
  {
    threads: [
      { name: 'T1', state: '后续竞争写入 2 → 3', detail: '示例中的另一轮 add', tone: 'success' },
      { name: 'T2', state: '推进 probe 后重试', detail: '后续冲突令 collide=true', tone: 'active' }
    ],
    base: 1,
    baseTone: '',
    cellsLength: 2,
    cells: [null, 3, null, null],
    activeCells: [1],
    collisionCells: [1],
    cellsBusy: '0',
    collide: 'false → true',
    pending: 'T2 +1',
    committedTotal: 4,
    phase: '首次失败先重哈希，持续冲突才具备扩容条件'
  },
  {
    threads: [
      { name: 'T1', state: '再次抢先写入 3 → 4', detail: '使 T2 的 Cell CAS 失效', tone: 'success' },
      { name: 'T2', state: '扩容 2 → 4', detail: '取得 cellsBusy 后复制引用', tone: 'active' }
    ],
    base: 1,
    baseTone: '',
    cellsLength: 4,
    cells: [null, 4, null, null],
    activeCells: [1, 2, 3],
    collisionCells: [1],
    cellsBusy: '0 → 1 → 0',
    collide: 'true → false',
    pending: 'T2 +1',
    committedTotal: 5,
    phase: '还要求 cells 未被替换且 2 < NCPU'
  },
  {
    threads: [
      { name: 'T1', state: '本轮竞争结束', detail: '已提交的值保持可见', tone: '' },
      { name: 'T2', state: '安装 Cell[3] = 1', detail: 'probe p3 & 3 = 3', tone: 'success' }
    ],
    base: 1,
    baseTone: '',
    cellsLength: 4,
    cells: [null, 4, null, 1],
    activeCells: [3],
    collisionCells: [],
    cellsBusy: '0 → 1 → 0',
    collide: 'false',
    pending: '无',
    committedTotal: 6,
    phase: '扩容提供更多槽位，但不保证以后绝不碰撞'
  },
  {
    threads: [
      { name: 'T1', state: '停止写入', detail: '本例进入稳定时点', tone: '' },
      { name: 'T2', state: '停止写入', detail: '开始执行 sum()', tone: '' }
    ],
    base: 1,
    baseTone: 'success',
    cellsLength: 4,
    cells: [null, 4, null, 1],
    activeCells: [1, 3],
    collisionCells: [],
    cellsBusy: '0',
    collide: 'false',
    pending: '无',
    committedTotal: 6,
    phase: 'sum = base(1) + Cell[1](4) + Cell[3](1)'
  }
]
</script>

<template>
  <SourceAnimation title="LongAdder 如何从 base 竞争演进到 Striped64 分段累加" :steps="steps" :interval="2400">
    <template #visual="{ currentIndex }">
      <div class="atomic-flow">
        <div class="atomic-flow__notice">
          <strong>一种允许的竞争交错</strong>
          <span>用于解释源码分支，不代表确定的线程调度</span>
        </div>

        <div class="atomic-flow__threads" aria-label="参与线程状态">
          <div
            v-for="thread in snapshots[currentIndex].threads"
            :key="thread.name"
            class="atomic-thread"
            :class="[`is-${thread.tone}`]"
          >
            <strong>{{ thread.name }}</strong>
            <span>{{ thread.state }}</span>
            <code>{{ thread.detail }}</code>
          </div>
        </div>

        <div class="atomic-flow__accumulator" aria-label="LongAdder 当前存储结构">
          <section
            class="atomic-base"
            :class="[`is-${snapshots[currentIndex].baseTone}`]"
          >
            <span>base</span>
            <strong>{{ snapshots[currentIndex].base }}</strong>
            <small>低竞争快速路径</small>
          </section>

          <span class="atomic-flow__operator">+</span>

          <section class="atomic-cells">
            <header>
              <strong>cells</strong>
              <code>length = {{ snapshots[currentIndex].cellsLength }}</code>
            </header>
            <!-- 始终保留四个槽位，避免初始化和扩容步骤改变舞台宽度。 -->
            <div class="atomic-cells__grid">
              <div
                v-for="(value, index) in snapshots[currentIndex].cells"
                :key="index"
                class="atomic-cell"
                :class="{
                  'is-unallocated': index >= snapshots[currentIndex].cellsLength,
                  'is-filled': value !== null,
                  'is-active': snapshots[currentIndex].activeCells.includes(index),
                  'is-collision': snapshots[currentIndex].collisionCells.includes(index)
                }"
              >
                <small>Cell[{{ index }}]</small>
                <strong v-if="index < snapshots[currentIndex].cellsLength">
                  {{ value === null ? 'null' : value }}
                </strong>
                <strong v-else>未分配</strong>
              </div>
            </div>
          </section>

          <span class="atomic-flow__operator">=</span>

          <section class="atomic-total" :class="{ 'is-final': currentIndex === snapshots.length - 1 }">
            <span>{{ currentIndex === snapshots.length - 1 ? 'sum()' : '已提交' }}</span>
            <strong>{{ snapshots[currentIndex].committedTotal }}</strong>
            <small>{{ currentIndex === snapshots.length - 1 ? '稳定时点结果' : '不含 pending' }}</small>
          </section>
        </div>

        <div class="atomic-flow__status">
          <span>cellsBusy <strong>{{ snapshots[currentIndex].cellsBusy }}</strong></span>
          <span>collide <strong>{{ snapshots[currentIndex].collide }}</strong></span>
          <span>pending <strong>{{ snapshots[currentIndex].pending }}</strong></span>
        </div>

        <div class="atomic-flow__phase">
          <code>{{ snapshots[currentIndex].phase }}</code>
          <span>sum() 在并发写入期间不是原子快照</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.atomic-flow {
  display: grid;
  gap: 16px;
  min-height: 342px;
}

/* 说明文本长短不同，预留固定空间可避免步进时整个动画上下跳动。 */
:deep(.source-animation__explanation) {
  min-height: 112px;
}

.atomic-flow__notice {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 5px 16px;
  min-height: 26px;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.atomic-flow__notice strong {
  color: var(--atlas-coral);
}

.atomic-flow__threads {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.atomic-thread {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  grid-template-rows: auto auto;
  gap: 4px 12px;
  align-content: center;
  min-width: 0;
  min-height: 74px;
  padding: 10px 12px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease, transform 180ms ease;
}

.atomic-thread > strong {
  grid-row: 1 / 3;
  align-self: center;
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
}

.atomic-thread span,
.atomic-thread code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.atomic-thread span {
  color: var(--vp-c-text-1);
  font-size: 0.73rem;
  font-weight: 700;
}

.atomic-thread code {
  color: var(--vp-c-text-3);
  font-size: 0.66rem;
}

.atomic-thread.is-active {
  border-left-color: var(--atlas-coral);
  transform: translateY(-2px);
}

.atomic-thread.is-success {
  border-left-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.atomic-thread.is-failure {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, var(--vp-c-bg));
}

.atomic-flow__accumulator {
  display: grid;
  grid-template-columns: minmax(86px, 0.46fr) 22px minmax(300px, 2fr) 22px minmax(86px, 0.46fr);
  gap: 8px;
  align-items: center;
}

.atomic-base,
.atomic-total {
  display: grid;
  place-items: center;
  gap: 3px;
  min-width: 0;
  min-height: 96px;
  padding: 10px 7px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  transition: border-color 180ms ease, background 180ms ease, transform 180ms ease;
}

.atomic-base > span,
.atomic-total > span,
.atomic-cells header {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.atomic-base > strong,
.atomic-total > strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 1.12rem;
}

.atomic-base small,
.atomic-total small {
  color: var(--vp-c-text-3);
  font-size: 0.6rem;
  text-align: center;
}

.atomic-base.is-active {
  border-color: var(--atlas-coral);
  transform: translateY(-2px);
}

.atomic-base.is-success,
.atomic-total.is-final {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.atomic-cells {
  display: grid;
  gap: 7px;
  min-width: 0;
}

.atomic-cells header {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.atomic-cells header strong {
  color: var(--vp-c-text-2);
}

.atomic-cells header code {
  color: var(--vp-c-brand-1);
  font-size: 0.66rem;
}

.atomic-cells__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(56px, 1fr));
  gap: 6px;
}

.atomic-cell {
  display: grid;
  place-items: center;
  gap: 3px;
  min-width: 0;
  height: 72px;
  border: 1px dashed var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease, opacity 180ms ease, transform 180ms ease;
}

.atomic-cell small,
.atomic-cell strong {
  max-width: 100%;
  overflow-wrap: anywhere;
  text-align: center;
}

.atomic-cell small {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.61rem;
}

.atomic-cell strong {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.atomic-cell.is-unallocated {
  opacity: 0.3;
}

.atomic-cell.is-filled {
  border-style: solid;
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.atomic-cell.is-filled strong {
  color: var(--vp-c-brand-1);
  font-size: 0.92rem;
}

.atomic-cell.is-active {
  border-color: var(--vp-c-brand-1);
  opacity: 1;
  transform: translateY(-3px);
}

.atomic-cell.is-collision {
  border-color: var(--atlas-coral);
  box-shadow: inset 0 0 0 1px var(--atlas-coral);
  animation: atomic-collision 720ms ease-in-out infinite alternate;
}

.atomic-flow__operator {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 1rem;
  text-align: center;
}

.atomic-flow__status {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.atomic-flow__status span {
  display: grid;
  gap: 3px;
  min-width: 0;
  min-height: 48px;
  padding: 7px 9px;
  background: var(--atlas-surface);
  color: var(--vp-c-text-3);
  font-size: 0.65rem;
  text-align: center;
}

.atomic-flow__status strong {
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
}

.atomic-flow__phase {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 16px;
  min-height: 28px;
  padding-top: 9px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.atomic-flow__phase code {
  max-width: 70%;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.68rem;
}

@keyframes atomic-collision {
  from { box-shadow: inset 0 0 0 1px var(--atlas-coral), 0 0 0 0 transparent; }
  to { box-shadow: inset 0 0 0 1px var(--atlas-coral), 0 0 0 4px color-mix(in srgb, var(--atlas-coral) 16%, transparent); }
}

@media (max-width: 760px) {
  .atomic-flow {
    min-height: 590px;
  }

  :deep(.source-animation__explanation) {
    min-height: 220px;
  }

  .atomic-flow__threads,
  .atomic-flow__status {
    grid-template-columns: 1fr;
  }

  .atomic-flow__accumulator {
    grid-template-columns: minmax(72px, 0.45fr) 16px minmax(0, 1.55fr);
  }

  .atomic-flow__accumulator > .atomic-flow__operator:nth-of-type(2) {
    display: none;
  }

  .atomic-total {
    grid-column: 1 / -1;
    min-height: 68px;
  }

  .atomic-cells__grid {
    grid-template-columns: repeat(2, minmax(58px, 1fr));
  }

  .atomic-cell {
    height: 64px;
  }

  .atomic-flow__phase code {
    max-width: 100%;
  }
}

@media (max-width: 390px) {
  .atomic-flow__notice span {
    width: 100%;
  }

  .atomic-thread {
    grid-template-columns: 34px minmax(0, 1fr);
  }

  .atomic-flow__accumulator {
    grid-template-columns: 72px 14px minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .atomic-thread,
  .atomic-base,
  .atomic-total,
  .atomic-cell {
    transition: none;
  }

  .atomic-cell.is-collision {
    animation: none;
  }
}
</style>
