<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface LinkedNodeSnapshot {
  key: string
  before: string
  after: string
  tone?: 'target' | 'new' | 'eldest'
}

interface LinkedMapSnapshot {
  phase: string
  operation: string
  nodes: LinkedNodeSnapshot[]
  head: string
  tail: string
  size: number
  modCount: number
  detached?: LinkedNodeSnapshot
  removed?: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '访问前快照',
    method: 'LinkedHashMap(accessOrder=true)',
    description: 'A、B、C 已按访问顺序连接；head=A 是最久未访问项，tail=C 是最近访问项。'
  },
  {
    title: '命中 B',
    method: 'get(B) → getNode(hash(B), B)',
    description: 'HashMap 在桶结构中找到 B。查找本身尚未改变 before/after，size 也保持 3。'
  },
  {
    title: '摘除 B',
    method: 'afterNodeAccess(B)',
    description: '保存 b=A、a=C 后，让 A 与 C 直接相连；B 暂时离开从 head 可达的顺序链。'
  },
  {
    title: 'B 接到尾部',
    method: 'last.after=B; tail=B; ++modCount',
    description: 'B 接到旧 tail C 之后，顺序变为 A、C、B。顺序变化属于结构性修改，但 size 不变。'
  },
  {
    title: '插入 D',
    method: 'newNode(D) → linkNodeLast(D)',
    description: '新节点 D 先成为 tail，随后 HashMap 把 size 增为 4、modCount 增加；此时暂时超过最大条目数 3。'
  },
  {
    title: '询问淘汰策略',
    method: 'removeEldestEntry(head)',
    description: 'afterNodeInsertion(true) 把 head=A 交给策略；size=4 大于上限 3，因此返回 true。'
  },
  {
    title: '淘汰 A',
    method: 'removeNode(A) → afterNodeRemoval(A)',
    description: 'A 同时从哈希桶和顺序链删除，head 更新为 C。最终顺序 C、B、D，size 回到 3。'
  }
]

const snapshots: LinkedMapSnapshot[] = [
  {
    phase: '访问排序',
    operation: '准备执行 get(B)',
    nodes: [
      { key: 'A', before: 'null', after: 'B' },
      { key: 'B', before: 'A', after: 'C' },
      { key: 'C', before: 'B', after: 'null' }
    ],
    head: 'A',
    tail: 'C',
    size: 3,
    modCount: 3
  },
  {
    phase: '访问排序',
    operation: '桶中已命中 B',
    nodes: [
      { key: 'A', before: 'null', after: 'B' },
      { key: 'B', before: 'A', after: 'C', tone: 'target' },
      { key: 'C', before: 'B', after: 'null' }
    ],
    head: 'A',
    tail: 'C',
    size: 3,
    modCount: 3
  },
  {
    phase: '访问排序',
    operation: '修复 A 与 C 的相邻关系',
    nodes: [
      { key: 'A', before: 'null', after: 'C' },
      { key: 'C', before: 'A', after: 'null' }
    ],
    detached: { key: 'B', before: 'A', after: 'null', tone: 'target' },
    head: 'A',
    tail: 'C',
    size: 3,
    modCount: 3
  },
  {
    phase: '访问排序',
    operation: 'get(B) 完成',
    nodes: [
      { key: 'A', before: 'null', after: 'C' },
      { key: 'C', before: 'A', after: 'B' },
      { key: 'B', before: 'C', after: 'null', tone: 'target' }
    ],
    head: 'A',
    tail: 'B',
    size: 3,
    modCount: 4
  },
  {
    phase: 'LRU 淘汰',
    operation: 'put(D) 完成插入',
    nodes: [
      { key: 'A', before: 'null', after: 'C' },
      { key: 'C', before: 'A', after: 'B' },
      { key: 'B', before: 'C', after: 'D' },
      { key: 'D', before: 'B', after: 'null', tone: 'new' }
    ],
    head: 'A',
    tail: 'D',
    size: 4,
    modCount: 5
  },
  {
    phase: 'LRU 淘汰',
    operation: 'size > maxEntries',
    nodes: [
      { key: 'A', before: 'null', after: 'C', tone: 'eldest' },
      { key: 'C', before: 'A', after: 'B' },
      { key: 'B', before: 'C', after: 'D' },
      { key: 'D', before: 'B', after: 'null', tone: 'new' }
    ],
    head: 'A',
    tail: 'D',
    size: 4,
    modCount: 5
  },
  {
    phase: 'LRU 淘汰',
    operation: 'A 已从两套结构删除',
    nodes: [
      { key: 'C', before: 'null', after: 'B' },
      { key: 'B', before: 'C', after: 'D' },
      { key: 'D', before: 'B', after: 'null', tone: 'new' }
    ],
    removed: 'A',
    head: 'C',
    tail: 'D',
    size: 3,
    modCount: 6
  }
]
</script>

<template>
  <SourceAnimation title="访问节点移到尾部，以及超限后淘汰 head" :steps="steps" :interval="2300">
    <template #visual="{ currentIndex }">
      <div
        class="linked-access"
        role="img"
        :aria-label="`${snapshots[currentIndex].operation}；当前顺序 ${snapshots[currentIndex].nodes.map(node => node.key).join('、')}`"
      >
        <div class="linked-access__context">
          <strong>{{ snapshots[currentIndex].phase }}</strong>
          <span>{{ snapshots[currentIndex].operation }}</span>
          <code>accessOrder=true</code>
          <code>maxEntries=3</code>
        </div>

        <div class="linked-access__direction" aria-hidden="true">
          <span>最久未访问</span>
          <span>最近访问</span>
        </div>

        <div
          class="linked-access__chain"
          :style="{ '--node-count': snapshots[currentIndex].nodes.length }"
        >
          <div
            v-for="node in snapshots[currentIndex].nodes"
            :key="`${currentIndex}-${node.key}`"
            class="linked-access__node"
            :class="[
              node.tone ? `is-${node.tone}` : '',
              node.key === snapshots[currentIndex].tail ? 'is-tail' : ''
            ]"
          >
            <span class="linked-access__marker">
              <template v-if="node.key === snapshots[currentIndex].head">head</template>
              <template v-if="node.key === snapshots[currentIndex].head && node.key === snapshots[currentIndex].tail"> / </template>
              <template v-if="node.key === snapshots[currentIndex].tail">tail</template>
            </span>
            <strong>{{ node.key }}</strong>
            <small>b: {{ node.before }}</small>
            <small>a: {{ node.after }}</small>
          </div>
        </div>

        <div class="linked-access__transient">
          <template v-if="snapshots[currentIndex].detached">
            <span>暂时摘除</span>
            <strong>{{ snapshots[currentIndex].detached?.key }}</strong>
            <code>before={{ snapshots[currentIndex].detached?.before }}</code>
            <code>after={{ snapshots[currentIndex].detached?.after }}</code>
          </template>
          <template v-else-if="snapshots[currentIndex].removed">
            <span>已淘汰</span>
            <strong class="is-removed">{{ snapshots[currentIndex].removed }}</strong>
            <code>桶与顺序链均已删除</code>
          </template>
          <template v-else>
            <span>顺序链完整</span>
            <code>沿 head.after 可访问全部 {{ snapshots[currentIndex].size }} 个节点</code>
          </template>
        </div>

        <div class="linked-access__state">
          <div>
            <span>head</span>
            <strong>{{ snapshots[currentIndex].head }}</strong>
          </div>
          <div>
            <span>tail</span>
            <strong>{{ snapshots[currentIndex].tail }}</strong>
          </div>
          <div>
            <span>size</span>
            <strong>{{ snapshots[currentIndex].size }}</strong>
          </div>
          <div>
            <span>modCount</span>
            <strong>{{ snapshots[currentIndex].modCount }}</strong>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.linked-access {
  display: grid;
  gap: 18px;
  min-height: 248px;
}

.linked-access__context,
.linked-access__direction,
.linked-access__transient {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px 14px;
}

.linked-access__context {
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
}

.linked-access__context strong {
  color: var(--vp-c-brand-1);
}

.linked-access__context code {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.linked-access__direction {
  justify-content: space-between;
  margin-bottom: -10px;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.linked-access__chain {
  display: grid;
  grid-template-columns: repeat(var(--node-count), minmax(56px, 1fr));
  gap: 18px;
  align-items: stretch;
}

.linked-access__node {
  position: relative;
  display: grid;
  gap: 2px;
  place-items: center;
  min-width: 0;
  min-height: 92px;
  padding: 14px 6px 7px;
  border: 1px solid var(--atlas-line);
  border-radius: 4px;
  background: var(--atlas-surface);
  color: var(--atlas-ink);
  animation: linked-node-settle 320ms ease-out both;
  transition: border-color 180ms ease, background 180ms ease, transform 240ms ease;
}

.linked-access__node:not(.is-tail)::after {
  position: absolute;
  top: 50%;
  left: calc(100% + 3px);
  width: 12px;
  color: var(--vp-c-text-3);
  content: '⇄';
  font-size: 0.75rem;
  text-align: center;
  transform: translateY(-50%);
}

.linked-access__node strong,
.linked-access__transient strong {
  display: grid;
  place-items: center;
  width: 30px;
  height: 28px;
  border: 1px solid var(--vp-c-brand-1);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
}

.linked-access__node small {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.64rem;
}

.linked-access__marker {
  position: absolute;
  top: 3px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.62rem;
}

.linked-access__node.is-target {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  transform: translateY(-3px);
}

.linked-access__node.is-new {
  border-color: var(--atlas-coral);
}

.linked-access__node.is-new strong {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.linked-access__node.is-eldest {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 10%, transparent);
  animation: linked-eldest-pulse 720ms ease-in-out infinite alternate;
}

.linked-access__transient {
  min-height: 38px;
  padding-top: 8px;
  border-top: 1px dashed var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.linked-access__transient strong {
  width: 28px;
  height: 26px;
}

.linked-access__transient strong.is-removed {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
  text-decoration: line-through;
}

.linked-access__transient code {
  color: var(--vp-c-text-2);
  font-size: 0.68rem;
}

.linked-access__state {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
}

.linked-access__state div {
  display: grid;
  gap: 3px;
  padding: 9px 6px 0;
  text-align: center;
}

.linked-access__state span {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.linked-access__state strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
}

@keyframes linked-node-settle {
  from { opacity: 0.55; transform: translateY(7px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes linked-eldest-pulse {
  from { box-shadow: 0 0 0 0 color-mix(in srgb, var(--atlas-coral) 18%, transparent); }
  to { box-shadow: 0 0 0 5px color-mix(in srgb, var(--atlas-coral) 18%, transparent); }
}

@media (max-width: 480px) {
  .linked-access {
    gap: 14px;
  }

  .linked-access__chain {
    gap: 12px;
  }

  .linked-access__node {
    min-height: 88px;
    padding-right: 3px;
    padding-left: 3px;
  }

  .linked-access__node:not(.is-tail)::after {
    left: calc(100% + 1px);
    width: 10px;
    font-size: 0.68rem;
  }

  .linked-access__state {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 7px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .linked-access__node {
    transition-duration: 0.01ms;
    animation-duration: 0.01ms;
    animation-iteration-count: 1;
  }
}
</style>
