<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type SlotTone = 'live' | 'stale' | 'active' | 'moved'

interface ThreadLocalSlot {
  index: number
  key: string
  value: string
  home: number
  tone: SlotTone
}

interface ThreadLocalSnapshot {
  size: number
  action: string
  probe: number[]
  scan: number[]
  slots: Array<ThreadLocalSlot | null>
}

const steps: SourceAnimationStep[] = [
  {
    title: '连续 hash 低位分散',
    method: 'nextHashCode.getAndAdd(0x61c88647)',
    description: '长度 16 时，黄金增量让连续创建的 key 依次落到 0、7、14、5、12、3、10、1。图中的 A、B、C 是从更长全局序列选出的合法碰撞样本，并非三个连续创建的 ThreadLocal；R 是场景开始前已有的无关活 Entry。'
  },
  {
    title: 'A 命中 home 槽',
    method: 'table[5] = new Entry(ctx-A, A-value)',
    description: 'ctx-A 的 hash 低 4 位为 5，home slot 为空，直接在槽 5 创建弱 key、强 value 的 Entry。R 是场景开始前已有且与当前碰撞区间无关的活 Entry。'
  },
  {
    title: 'B 线性探测到下一槽',
    method: 'nextIndex(5, 16) -> 6',
    description: 'ctx-B 的 home 也为 5。槽 5 已被 A 占用，set 沿数组向后探测，在首个 null 槽 6 插入 B。'
  },
  {
    title: 'C 继续形成连续 run',
    method: '5 -> 6 -> 7',
    description: 'ctx-C 依次越过 A、B，在槽 7 插入。开放寻址依赖从 home 到首个 null 的连续探测链。'
  },
  {
    title: 'A 的弱 key 被回收',
    method: 'Entry.get() == null',
    description: 'GC 只清除了 Entry 对 ctx-A 的弱引用；A-value 仍由 Entry.value 强引用，槽 5 仍计入 size，也没有后台线程自动清理。'
  },
  {
    title: 'set 更新 C 并清 stale',
    method: 'replaceStaleEntry(ctx-C, "C2", 5)',
    description: 'set(C) 先遇到槽 5 的 stale，向前找到槽 7 的已有 C，更新 value 后把 C 与 stale 交换，再从槽 7 expunge；C 回到 5，B 留在 6。'
  },
  {
    title: 'D 碰撞后插入',
    method: 'home(D)=6; table[7]=D',
    description: 'ctx-D 的 home 为 6。槽 6 是 B，因此 D 在线性探测后的槽 7 落位，形成新的连续 run。'
  },
  {
    title: 'B 变成 stale Entry',
    method: 'B.key -> null; B.value 仍存活',
    description: 'ctx-B 不再有强引用后，其弱 key 被清空；B-value 仍占槽 6。D 仍在槽 7，不能简单把槽 6 置空。'
  },
  {
    title: 'get 清理并重排 D',
    method: 'getEntryAfterMiss(D) -> expungeStaleEntry(6)',
    description: 'D.get 从 home=6 遇到 stale，expunge 清槽 6，并按 D 的 home 重新安置后续 run；D 从 7 搬回 6，查找在原索引重新读取后命中。'
  },
  {
    title: 'remove 确定性删除 C',
    method: 'C.clear(); expungeStaleEntry(5)',
    description: 'C.remove 主动清弱引用并从槽 5 开始 expunge，不等待 GC。D 的 home 就是 6，因此重排后仍留在槽 6。'
  },
  {
    title: 'S 插入远处槽位',
    method: 'table[12] = new Entry(ctx-S, S-value)',
    description: 'ctx-S 在槽 12 插入。此时 R、D、S 三个 Entry 都计入 size，启发式扫描的预算由 size 决定。'
  },
  {
    title: 'S 的 key 随后被回收',
    method: 'table[12].get() == null',
    description: '槽 12 成为与 D 所在 run 无关的 stale Entry。普通 get(D) 直接命中槽 6，不会扫描或清理这个远处槽位。'
  },
  {
    title: '新插入触发启发式清理',
    method: 'cleanSomeSlots(10, 4)',
    description: 'E 在槽 10 插入后 size=4。扫描先检查 11，再检查 12 并发现 stale；n 重置为 16，expunge 清掉 S-value 后继续有限扫描。它增加发现概率，但不是每次全表清理。'
  }
]

/**
 * 构造固定 16 槽快照，让每一步只改变 Entry 状态而不改变页面几何尺寸。
 *
 * @param entries 当前步骤中存在的 Entry
 * @return 按槽位索引排列的快照数组
 */
function buildSlots(...entries: ThreadLocalSlot[]): Array<ThreadLocalSlot | null> {
  const slots: Array<ThreadLocalSlot | null> = Array.from({ length: 16 }, () => null)
  entries.forEach((entry) => {
    slots[entry.index] = entry
  })
  return slots
}

/**
 * 创建动画 Entry，集中声明默认活跃样式并减少快照中的重复字段。
 *
 * @param index Entry 当前槽位
 * @param key 弱引用 key 的展示文本
 * @param value 强引用 value 的展示文本
 * @param home key 的 home slot
 * @param tone 当前步骤的视觉状态
 * @return 可放入固定槽位表的 Entry
 */
function entry(
  index: number,
  key: string,
  value: string,
  home: number,
  tone: SlotTone = 'live'
): ThreadLocalSlot {
  return { index, key, value, home, tone }
}

/**
 * 创建不参与碰撞 run 的保留 Entry，使最后一步具有真实的 size=4 扫描预算。
 *
 * @return 位于槽 2 的无关活 Entry
 */
const retained = (): ThreadLocalSlot => entry(2, 'ctx-R', 'R-value', 2)

// 每个快照严格对应一个源码阶段；R 用于保持最后一次 cleanSomeSlots 的 size=4 扫描预算。
const snapshots: ThreadLocalSnapshot[] = [
  {
    size: 1,
    action: '连续低位：0 -> 7 -> 14 -> 5 -> 12 -> 3 -> 10 -> 1',
    probe: [],
    scan: [],
    slots: buildSlots(retained())
  },
  {
    size: 2,
    action: 'A: hash & 15 = 5',
    probe: [5],
    scan: [],
    slots: buildSlots(retained(), entry(5, 'ctx-A', 'A-value', 5, 'active'))
  },
  {
    size: 3,
    action: 'B: home 5 被占用，探测到 6',
    probe: [5, 6],
    scan: [],
    slots: buildSlots(
      retained(),
      entry(5, 'ctx-A', 'A-value', 5),
      entry(6, 'ctx-B', 'B-value', 5, 'active')
    )
  },
  {
    size: 4,
    action: 'C: home 5，探测 5 -> 6 -> 7',
    probe: [5, 6, 7],
    scan: [],
    slots: buildSlots(
      retained(),
      entry(5, 'ctx-A', 'A-value', 5),
      entry(6, 'ctx-B', 'B-value', 5),
      entry(7, 'ctx-C', 'C-value', 5, 'active')
    )
  },
  {
    size: 4,
    action: 'GC 清除 A 的弱 referent，value 未清',
    probe: [5],
    scan: [],
    slots: buildSlots(
      retained(),
      entry(5, 'key=null', 'A-value', 5, 'stale'),
      entry(6, 'ctx-B', 'B-value', 5),
      entry(7, 'ctx-C', 'C-value', 5)
    )
  },
  {
    size: 3,
    action: 'C: 7 -> 5；stale: 5 -> 7 -> 清除',
    probe: [5, 6, 7],
    scan: [7],
    slots: buildSlots(
      retained(),
      entry(5, 'ctx-C', 'C2', 5, 'moved'),
      entry(6, 'ctx-B', 'B-value', 5)
    )
  },
  {
    size: 4,
    action: 'D: home 6 被 B 占用，落到 7',
    probe: [6, 7],
    scan: [],
    slots: buildSlots(
      retained(),
      entry(5, 'ctx-C', 'C2', 5),
      entry(6, 'ctx-B', 'B-value', 5),
      entry(7, 'ctx-D', 'D-value', 6, 'active')
    )
  },
  {
    size: 4,
    action: 'GC 清除 B 的弱 referent，D 仍在后方',
    probe: [6],
    scan: [],
    slots: buildSlots(
      retained(),
      entry(5, 'ctx-C', 'C2', 5),
      entry(6, 'key=null', 'B-value', 5, 'stale'),
      entry(7, 'ctx-D', 'D-value', 6)
    )
  },
  {
    size: 3,
    action: 'expunge(6)：清 B，D 从 7 重排到 6',
    probe: [6],
    scan: [6, 7],
    slots: buildSlots(
      retained(),
      entry(5, 'ctx-C', 'C2', 5),
      entry(6, 'ctx-D', 'D-value', 6, 'moved')
    )
  },
  {
    size: 2,
    action: 'remove(C)：槽 5 清空，扫描至 run 尾部',
    probe: [5],
    scan: [5, 6, 7],
    slots: buildSlots(retained(), entry(6, 'ctx-D', 'D-value', 6))
  },
  {
    size: 3,
    action: 'S: hash & 15 = 12',
    probe: [12],
    scan: [],
    slots: buildSlots(
      retained(),
      entry(6, 'ctx-D', 'D-value', 6),
      entry(12, 'ctx-S', 'S-value', 12, 'active')
    )
  },
  {
    size: 3,
    action: 'S.key -> null；普通 get(D) 不会访问槽 12',
    probe: [6],
    scan: [],
    slots: buildSlots(
      retained(),
      entry(6, 'ctx-D', 'D-value', 6),
      entry(12, 'key=null', 'S-value', 12, 'stale')
    )
  },
  {
    size: 3,
    action: 'E 插入 10；扫描 11、12，清理 S 后 size=3',
    probe: [10],
    scan: [11, 12, 13, 14, 15, 0, 1],
    slots: buildSlots(
      retained(),
      entry(6, 'ctx-D', 'D-value', 6),
      entry(10, 'ctx-E', 'E-value', 10, 'active')
    )
  }
]

/**
 * 根据动画步骤取得稳定快照，模板只负责展示，不在渲染期间重建数组。
 *
 * @param index 当前动画步骤索引
 * @return 与步骤一一对应的 ThreadLocalMap 状态
 */
function snapshotAt(index: number): ThreadLocalSnapshot {
  return snapshots[index]
}

/**
 * 判断某槽是否属于当前 key 的查找或插入探测路径。
 *
 * @param snapshot 当前快照
 * @param index 待判断槽位
 * @return 属于探测路径时返回 true
 */
function isProbed(snapshot: ThreadLocalSnapshot, index: number): boolean {
  return snapshot.probe.includes(index)
}

/**
 * 判断某槽是否被本轮清理算法扫描。
 *
 * @param snapshot 当前快照
 * @param index 待判断槽位
 * @return 属于清理扫描范围时返回 true
 */
function isScanned(snapshot: ThreadLocalSnapshot, index: number): boolean {
  return snapshot.scan.includes(index)
}
</script>

<template>
  <SourceAnimation title="ThreadLocalMap：探测、陈旧 Entry 与访问驱动清理" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="tl-map">
        <div class="tl-map__summary">
          <div>
            <span>所有者</span>
            <strong>worker-1.threadLocals</strong>
          </div>
          <div>
            <span>容量 / 阈值</span>
            <strong>16 / 10</strong>
          </div>
          <div>
            <span>当前 size</span>
            <strong>{{ snapshotAt(currentIndex).size }}</strong>
          </div>
        </div>

        <div class="tl-map__formula">
          <code>index = threadLocalHashCode &amp; 15</code>
          <span>{{ snapshotAt(currentIndex).action }}</span>
        </div>

        <div class="tl-map__slots" aria-label="ThreadLocalMap 的 16 个固定槽位">
          <div
            v-for="(slot, slotIndex) in snapshotAt(currentIndex).slots"
            :key="slotIndex"
            class="tl-slot"
            :class="[
              slot ? `is-${slot.tone}` : 'is-empty',
              { 'is-probed': isProbed(snapshotAt(currentIndex), slotIndex) },
              { 'is-scanned': isScanned(snapshotAt(currentIndex), slotIndex) }
            ]"
          >
            <div class="tl-slot__index">
              <span>{{ slotIndex }}</span>
              <i v-if="isProbed(snapshotAt(currentIndex), slotIndex)">探测</i>
              <i v-else-if="isScanned(snapshotAt(currentIndex), slotIndex)">扫描</i>
            </div>
            <template v-if="slot">
              <strong>{{ slot.key }}</strong>
              <small>{{ slot.value }}</small>
              <em>home {{ slot.home }}</em>
            </template>
            <span v-else class="tl-slot__empty">null</span>
          </div>
        </div>

        <div class="tl-map__legend" aria-label="状态图例">
          <span><i class="is-live"></i>live Entry</span>
          <span><i class="is-stale"></i>stale：弱 key=null，value 仍强引用</span>
          <span><i class="is-probe"></i>key 探测路径</span>
          <span><i class="is-scan"></i>清理扫描范围</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.tl-map {
  --tl-live: #147d73;

  display: grid;
  gap: 14px;
  min-width: 0;
}

.tl-map__summary {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) repeat(2, minmax(90px, 0.7fr));
  gap: 8px;
}

.tl-map__summary > div {
  min-width: 0;
  padding: 9px 10px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.tl-map__summary span,
.tl-map__summary strong {
  display: block;
}

.tl-map__summary span {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.tl-map__summary strong {
  margin-top: 3px;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
}

.tl-map__formula {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 42px;
  padding: 8px 10px;
  border-left: 3px solid var(--vp-c-brand-1);
  background: var(--atlas-surface);
}

.tl-map__formula code {
  flex: 0 0 auto;
  color: var(--vp-c-brand-1);
  font-size: 0.72rem;
}

.tl-map__formula span {
  min-width: 0;
  color: var(--vp-c-text-2);
  font-size: 0.72rem;
  text-align: right;
}

.tl-map__slots {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 6px;
}

.tl-slot {
  position: relative;
  display: grid;
  grid-template-rows: 19px 20px 18px 16px;
  min-width: 0;
  height: 86px;
  padding: 5px 6px;
  overflow: hidden;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  transition: border-color 180ms ease, background 180ms ease, box-shadow 180ms ease;
}

.tl-slot.is-empty {
  color: var(--vp-c-text-3);
}

.tl-slot.is-live {
  border-color: color-mix(in srgb, var(--tl-live) 50%, var(--atlas-line));
  background: color-mix(in srgb, var(--tl-live) 7%, var(--vp-c-bg));
}

.tl-slot.is-active,
.tl-slot.is-moved {
  border-color: var(--tl-live);
  background: color-mix(in srgb, var(--tl-live) 13%, var(--vp-c-bg));
  box-shadow: inset 0 3px 0 var(--tl-live);
}

.tl-slot.is-moved {
  animation: tl-arrive 280ms ease-out;
}

.tl-slot.is-stale {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 12%, var(--vp-c-bg));
  box-shadow: inset 0 3px 0 var(--atlas-coral);
}

.tl-slot.is-probed::after,
.tl-slot.is-scanned::after {
  position: absolute;
  inset: 1px;
  border: 1px dashed var(--vp-c-brand-1);
  content: '';
  pointer-events: none;
}

.tl-slot.is-scanned::after {
  border-color: var(--atlas-coral);
}

.tl-slot__index {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.65rem;
}

.tl-slot__index i {
  overflow: hidden;
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-base);
  font-size: 0.58rem;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tl-slot.is-scanned .tl-slot__index i {
  color: var(--atlas-coral);
}

.tl-slot strong,
.tl-slot small,
.tl-slot em {
  min-width: 0;
  overflow: hidden;
  font-family: var(--vp-font-family-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tl-slot strong {
  color: var(--atlas-ink);
  font-size: 0.68rem;
}

.tl-slot.is-stale strong,
.tl-slot.is-stale small {
  color: var(--atlas-coral);
}

.tl-slot small {
  color: var(--vp-c-text-2);
  font-size: 0.62rem;
}

.tl-slot em {
  color: var(--vp-c-text-3);
  font-size: 0.58rem;
  font-style: normal;
}

.tl-slot__empty {
  grid-row: 2 / 5;
  align-self: center;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.64rem;
  text-align: center;
}

.tl-map__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.tl-map__legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.tl-map__legend i {
  width: 12px;
  height: 12px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
}

.tl-map__legend .is-live {
  border-color: var(--tl-live);
  background: color-mix(in srgb, var(--tl-live) 13%, var(--vp-c-bg));
}

.tl-map__legend .is-stale {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 12%, var(--vp-c-bg));
}

.tl-map__legend .is-probe {
  border-style: dashed;
  border-color: var(--vp-c-brand-1);
}

.tl-map__legend .is-scan {
  border-style: dashed;
  border-color: var(--atlas-coral);
}

@keyframes tl-arrive {
  from { opacity: 0.35; transform: translateX(10px); }
  to { opacity: 1; transform: translateX(0); }
}

:global(.dark) .tl-map {
  --tl-live: #54c5b8;
}

@media (max-width: 720px) {
  .tl-map__summary {
    grid-template-columns: 1fr 1fr;
  }

  .tl-map__summary > div:first-child {
    grid-column: 1 / -1;
  }

  .tl-map__formula {
    display: grid;
  }

  .tl-map__formula span {
    text-align: left;
  }

  .tl-map__slots {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 420px) {
  .tl-slot {
    padding-right: 4px;
    padding-left: 4px;
  }

  .tl-slot__index i {
    display: none;
  }
}
</style>
