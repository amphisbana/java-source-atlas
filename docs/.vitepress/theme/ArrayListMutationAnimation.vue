<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface ArraySnapshot {
  values: string[]
  size: number
  active: number[]
  movement: string
  modCountDelta: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '数组已满',
    method: 'add(E)',
    description: 'size 与 elementData.length 都是 4，尾部已经没有可写位置。'
  },
  {
    title: '计算最小容量',
    method: 'ensureCapacityInternal(size + 1)',
    description: '本次新增至少需要 5 个位置，minCapacity 因而为 5。'
  },
  {
    title: '扩容并复制',
    method: 'grow(5) → Arrays.copyOf(elementData, 6)',
    description: '候选容量为 4 + (4 >> 1) = 6；复制的是元素引用，不会复制 A、B、C、D 对象。'
  },
  {
    title: '写入尾部',
    method: 'elementData[size++] = E',
    description: 'E 写入原 size 对应的下标 4，随后 size 从 4 增为 5。'
  },
  {
    title: '为插入腾出位置',
    method: 'System.arraycopy(..., index=1, ..., length=4)',
    description: '在下标 1 插入 X 前，B、C、D、E 的引用整体右移一格；复制由底层处理重叠区间。'
  },
  {
    title: '写入插入元素',
    method: 'elementData[1] = X; size++',
    description: '腾出的下标 1 写入 X，size 变为 6，数组顺序保持为 A、X、B、C、D、E。'
  },
  {
    title: '删除并左移',
    method: 'fastRemove(2) → System.arraycopy(...)',
    description: '删除 B 后，C、D、E 左移；旧数组尾部暂时仍保存一个重复的 E 引用。'
  },
  {
    title: '清空失效引用',
    method: 'elementData[--size] = null',
    description: 'size 减为 5，并把失效尾部设为 null，避免 ArrayList 继续持有已经不属于列表的对象。'
  }
]

const snapshots: ArraySnapshot[] = [
  { values: ['A', 'B', 'C', 'D'], size: 4, active: [], movement: 'capacity = 4', modCountDelta: '+0' },
  { values: ['A', 'B', 'C', 'D'], size: 4, active: [], movement: 'minCapacity = 5', modCountDelta: '+0' },
  { values: ['A', 'B', 'C', 'D', '·', '·'], size: 4, active: [4, 5], movement: '4 → 6，复制 4 个引用', modCountDelta: '+1' },
  { values: ['A', 'B', 'C', 'D', 'E', '·'], size: 5, active: [4], movement: '写入下标 4', modCountDelta: '+1' },
  { values: ['A', 'B', 'B', 'C', 'D', 'E'], size: 5, active: [1, 2, 3, 4, 5], movement: 'B..E 向右移动 1 格', modCountDelta: '+2' },
  { values: ['A', 'X', 'B', 'C', 'D', 'E'], size: 6, active: [1], movement: '下标 1 写入 X', modCountDelta: '+2' },
  { values: ['A', 'X', 'C', 'D', 'E', 'E'], size: 6, active: [2, 3, 4, 5], movement: 'C..E 向左移动 1 格', modCountDelta: '+3' },
  { values: ['A', 'X', 'C', 'D', 'E', 'null'], size: 5, active: [5], movement: '释放尾部引用', modCountDelta: '+3' }
]
</script>

<template>
  <SourceAnimation title="扩容、插入和删除如何改变 elementData" :steps="steps" :interval="2100">
    <template #visual="{ currentIndex }">
      <div class="array-flow">
        <div class="array-flow__meta">
          <span>size <strong>{{ snapshots[currentIndex].size }}</strong></span>
          <span>capacity <strong>{{ snapshots[currentIndex].values.length }}</strong></span>
          <span>{{ snapshots[currentIndex].movement }}</span>
        </div>

        <div class="array-flow__array" :style="{ '--cell-count': snapshots[currentIndex].values.length }">
          <div
            v-for="(value, index) in snapshots[currentIndex].values"
            :key="`${currentIndex}-${index}`"
            class="array-flow__cell"
            :class="{
              'is-active': snapshots[currentIndex].active.includes(index),
              'is-empty': value === '·',
              'is-null': value === 'null',
              'is-shifting': currentIndex === 4 || currentIndex === 6
            }"
          >
            <span>{{ value }}</span>
            <small>{{ index }}</small>
          </div>
        </div>

        <div class="array-flow__memory">
          <div>
            <span>有效区间</span>
            <strong>[0, size)</strong>
          </div>
          <div>
            <span>空闲容量</span>
            <strong>{{ snapshots[currentIndex].values.length - snapshots[currentIndex].size }}</strong>
          </div>
          <div>
            <span>modCount 增量</span>
            <strong>{{ snapshots[currentIndex].modCountDelta }}</strong>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.array-flow {
  display: grid;
  gap: 28px;
  align-content: center;
  min-height: 220px;
}

.array-flow__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 22px;
  color: var(--vp-c-text-3);
  font-size: 0.78rem;
}

.array-flow__meta strong {
  margin-left: 4px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.array-flow__array {
  display: grid;
  grid-template-columns: repeat(var(--cell-count), minmax(48px, 1fr));
  gap: 5px;
}

.array-flow__cell {
  position: relative;
  display: grid;
  place-items: center;
  min-width: 0;
  height: 64px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  transition: border-color 220ms ease, background 220ms ease, transform 260ms ease;
  animation: array-cell-enter 300ms ease-out both;
}

.array-flow__cell small {
  position: absolute;
  right: 5px;
  bottom: 3px;
  color: var(--vp-c-text-3);
  font-size: 0.64rem;
}

.array-flow__cell.is-active {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.array-flow__cell.is-active.is-shifting {
  animation: array-shift 520ms ease-out both;
}

.array-flow__cell.is-empty,
.array-flow__cell.is-null {
  color: var(--vp-c-text-3);
}

.array-flow__cell.is-null {
  border-style: dashed;
  background: transparent;
  font-size: 0.72rem;
}

.array-flow__memory {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
}

.array-flow__memory div {
  display: grid;
  gap: 4px;
  padding: 10px 8px 0;
  text-align: center;
}

.array-flow__memory span {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.array-flow__memory strong {
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
}

@keyframes array-cell-enter {
  from { opacity: 0.55; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes array-shift {
  0% { transform: translateX(0); }
  45% { transform: translateX(7px); }
  100% { transform: translateX(0); }
}

@media (max-width: 640px) {
  .array-flow {
    gap: 20px;
  }

  .array-flow__array {
    overflow-x: auto;
    grid-template-columns: repeat(var(--cell-count), 52px);
    padding-bottom: 5px;
  }

  .array-flow__memory {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .array-flow__memory div {
    grid-template-columns: 1fr 1fr;
    text-align: left;
  }
}
</style>
