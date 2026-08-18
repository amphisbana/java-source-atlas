<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type NodeTone = '' | 'active' | 'published' | 'deleted' | 'stale'

interface QueueNodeSnapshot {
  id: string
  item: string
  next: string
  tone: NodeTone
  visible: boolean
}

interface QueueSnapshot {
  nodes: QueueNodeSnapshot[]
  head: string
  tail: string
  local: string
  liveQueue: string
  result: string
  event: string
}

const steps: SourceAnimationStep[] = [
  {
    title: '创建空队列',
    method: 'head = tail = new Node(null)',
    description: 'N0 是初始空节点。head 和 tail 都只是搜索入口，节点是否属于逻辑队列取决于 item 是否非 null。'
  },
  {
    title: 'offer A 找到末节点',
    method: 'p = tail; q = p.next == null',
    description: '生产者从 tail=N0 开始，确认 N0 是当前看到的真正末节点；新节点 A 此时尚未共享。'
  },
  {
    title: 'CAS 发布 A，tail 暂不前进',
    method: 'N0.casNext(null, A) → true',
    description: 'next CAS 是 A 入队的线性化点。因为 p 与最初的 t 相同，源码不额外更新 tail，允许它落后一跳。'
  },
  {
    title: 'offer B 追到 A',
    method: 'N0.next == A; p = A',
    description: '第二个生产者不能把 B 接到 N0；它沿已经发布的 A 前进，直到再次找到 next 为 null 的节点。'
  },
  {
    title: '发布 B 并把 tail 跳到 B',
    method: 'A.casNext(null, B); casTail(N0, B)',
    description: 'B 的 next CAS 完成入队。此时 p 已离开最初 tail，辅助 CAS 把 tail 一次推进两跳；即使 tail CAS 失败，B 仍已生效。'
  },
  {
    title: 'poll 用 item CAS 认领 A',
    method: 'A.casItem("A", null) → true',
    description: 'A 的 item 被原子清空时，当前消费者唯一取得 A。节点仍物理可达，不影响逻辑删除已经完成。'
  },
  {
    title: 'head 跨过旧链并自链接 N0',
    method: 'casHead(N0, B); N0.next = N0',
    description: '扫描已跨过 N0 和 A，head 可以直接推进到 B。旧 head 自链接，切断它对活跃链的普通引用。'
  },
  {
    title: '过期生产者从自链接恢复',
    method: 'p == p.next → p = tail / head',
    description: '一个暂停线程仍持有 N0。恢复后读到自链接，知道本地指针已掉出队列，于是跳到当前 tail=B。'
  },
  {
    title: '发布 C，tail 再次允许滞后',
    method: 'B.casNext(null, C) → true',
    description: 'C 已经可以从 head 到达，因此 offer 成功；本轮 p 等于最初 tail，提示指针仍留在 B。'
  },
  {
    title: 'poll 认领 head 上的 B',
    method: 'B.casItem("B", null) → true',
    description: '本轮 p 与 h 都是 B，所以无需立即更新 head。后续读取会跳过 B 的 null item 并协助推进。'
  },
  {
    title: 'poll C 后形成新的空节点',
    method: 'C.casItem("C", null); updateHead(B, C)',
    description: 'C 被唯一取走，head 推进到 C 并让旧 B 自链接。tail 可能暂时停在已离队的 B，下一次 offer 会通过自链接分支恢复。'
  }
]

const nodeIds = ['N0', 'A', 'B', 'C']

const snapshots: QueueSnapshot[] = [
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'null', tone: '', visible: true },
      { id: 'A', item: '-', next: '-', tone: '', visible: false },
      { id: 'B', item: '-', next: '-', tone: '', visible: false },
      { id: 'C', item: '-', next: '-', tone: '', visible: false }
    ],
    head: 'N0', tail: 'N0', local: '无', liveQueue: '空', result: '无', event: '初始空节点'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'null', tone: 'active', visible: true },
      { id: 'A', item: 'A', next: 'null', tone: 'stale', visible: true },
      { id: 'B', item: '-', next: '-', tone: '', visible: false },
      { id: 'C', item: '-', next: '-', tone: '', visible: false }
    ],
    head: 'N0', tail: 'N0', local: 't=N0, p=N0, q=null', liveQueue: '空', result: 'A 尚未发布', event: '寻找 null 后继'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'A', tone: 'active', visible: true },
      { id: 'A', item: 'A', next: 'null', tone: 'published', visible: true },
      { id: 'B', item: '-', next: '-', tone: '', visible: false },
      { id: 'C', item: '-', next: '-', tone: '', visible: false }
    ],
    head: 'N0', tail: 'N0', local: 'p == t，不更新 tail', liveQueue: 'A', result: 'offer(A) = true', event: 'A 已线性化入队'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'A', tone: '', visible: true },
      { id: 'A', item: 'A', next: 'null', tone: 'active', visible: true },
      { id: 'B', item: 'B', next: 'null', tone: 'stale', visible: true },
      { id: 'C', item: '-', next: '-', tone: '', visible: false }
    ],
    head: 'N0', tail: 'N0', local: 't=N0, p=A, q=null', liveQueue: 'A', result: 'B 尚未发布', event: '沿 next 追赶 tail'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'A', tone: '', visible: true },
      { id: 'A', item: 'A', next: 'B', tone: '', visible: true },
      { id: 'B', item: 'B', next: 'null', tone: 'published', visible: true },
      { id: 'C', item: '-', next: '-', tone: '', visible: false }
    ],
    head: 'N0', tail: 'B', local: 'casTail(N0, B)', liveQueue: 'A → B', result: 'offer(B) = true', event: 'tail 两跳推进'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'A', tone: '', visible: true },
      { id: 'A', item: 'null', next: 'B', tone: 'deleted', visible: true },
      { id: 'B', item: 'B', next: 'null', tone: '', visible: true },
      { id: 'C', item: '-', next: '-', tone: '', visible: false }
    ],
    head: 'N0', tail: 'B', local: 'h=N0, p=A, q=B', liveQueue: 'B', result: 'poll() = A', event: 'A 逻辑删除'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'N0', tone: 'stale', visible: true },
      { id: 'A', item: 'null', next: 'B', tone: 'deleted', visible: true },
      { id: 'B', item: 'B', next: 'null', tone: 'active', visible: true },
      { id: 'C', item: '-', next: '-', tone: '', visible: false }
    ],
    head: 'B', tail: 'B', local: 'oldHead=N0（自链接）', liveQueue: 'B', result: 'A 已返回', event: 'head 推进到 B'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'N0', tone: 'active', visible: true },
      { id: 'A', item: 'null', next: 'B', tone: 'deleted', visible: true },
      { id: 'B', item: 'B', next: 'null', tone: 'published', visible: true },
      { id: 'C', item: 'C', next: 'null', tone: 'stale', visible: true }
    ],
    head: 'B', tail: 'B', local: 'stale p=N0 → tail=B', liveQueue: 'B', result: 'C 尚未发布', event: '从旧节点重新定位'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'N0', tone: 'stale', visible: true },
      { id: 'A', item: 'null', next: 'B', tone: 'deleted', visible: true },
      { id: 'B', item: 'B', next: 'C', tone: '', visible: true },
      { id: 'C', item: 'C', next: 'null', tone: 'published', visible: true }
    ],
    head: 'B', tail: 'B', local: 'p == t，tail 保持 B', liveQueue: 'B → C', result: 'offer(C) = true', event: 'C 已从 head 可达'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'N0', tone: 'stale', visible: true },
      { id: 'A', item: 'null', next: 'B', tone: 'deleted', visible: true },
      { id: 'B', item: 'null', next: 'C', tone: 'deleted', visible: true },
      { id: 'C', item: 'C', next: 'null', tone: '', visible: true }
    ],
    head: 'B', tail: 'B', local: 'p == h，不推进 head', liveQueue: 'C', result: 'poll() = B', event: 'B 逻辑删除'
  },
  {
    nodes: [
      { id: 'N0', item: 'null', next: 'N0', tone: 'stale', visible: true },
      { id: 'A', item: 'null', next: 'B', tone: 'deleted', visible: true },
      { id: 'B', item: 'null', next: 'B', tone: 'stale', visible: true },
      { id: 'C', item: 'null', next: 'null', tone: 'active', visible: true }
    ],
    head: 'C', tail: 'B', local: 'oldHead=B（自链接）', liveQueue: '空', result: 'poll() = C', event: 'tail 暂时掉出活跃链'
  }
]
</script>

<template>
  <SourceAnimation title="无锁队列如何发布节点、逻辑删除并从旧指针恢复" :steps="steps" :interval="2500">
    <template #visual="{ currentIndex }">
      <div class="clq-demo">
        <div class="clq-demo__status">
          <span>逻辑队列 <strong>{{ snapshots[currentIndex].liveQueue }}</strong></span>
          <code>{{ snapshots[currentIndex].event }}</code>
        </div>

        <div class="clq-demo__nodes" aria-label="ConcurrentLinkedQueue 节点快照">
          <section
            v-for="(node, index) in snapshots[currentIndex].nodes"
            :key="node.id"
            class="clq-node"
            :class="[
              `is-${node.tone}`,
              { 'is-hidden': !node.visible }
            ]"
          >
            <header>
              <strong>{{ node.id }}</strong>
              <span v-if="snapshots[currentIndex].head === node.id">head</span>
              <span v-if="snapshots[currentIndex].tail === node.id">tail</span>
            </header>
            <dl>
              <div>
                <dt>item</dt>
                <dd>{{ node.item }}</dd>
              </div>
              <div>
                <dt>next</dt>
                <dd>{{ node.next }}</dd>
              </div>
            </dl>
            <small>{{ node.next === node.id ? '自链接：重新定位' : `槽位 ${nodeIds[index]}` }}</small>
          </section>
        </div>

        <div class="clq-demo__footer">
          <span>线程局部 <code>{{ snapshots[currentIndex].local }}</code></span>
          <span>公开结果 <strong>{{ snapshots[currentIndex].result }}</strong></span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.clq-demo {
  display: grid;
  gap: 18px;
  min-width: 0;
  min-height: 280px;
}

.clq-demo__status,
.clq-demo__footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  align-items: center;
  justify-content: space-between;
  color: var(--vp-c-text-3);
  font-size: 0.74rem;
}

.clq-demo__status strong,
.clq-demo__status code,
.clq-demo__footer strong,
.clq-demo__footer code {
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
}

.clq-demo__nodes {
  display: grid;
  grid-template-columns: repeat(4, minmax(110px, 1fr));
  gap: 8px;
}

.clq-node {
  display: grid;
  align-content: start;
  min-width: 0;
  min-height: 142px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 220ms ease, background-color 220ms ease, opacity 220ms ease;
}

.clq-node header {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 6px;
  align-items: center;
  min-height: 39px;
  padding: 8px;
  border-bottom: 1px solid var(--atlas-line);
}

.clq-node header strong {
  margin-right: auto;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.clq-node header span {
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.62rem;
  font-weight: 700;
}

.clq-node dl {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 9px;
}

.clq-node dl div {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 6px;
}

.clq-node dt,
.clq-node dd {
  margin: 0;
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
  overflow-wrap: anywhere;
}

.clq-node dt {
  color: var(--vp-c-text-3);
}

.clq-node dd {
  color: var(--atlas-ink);
}

.clq-node small {
  margin-top: auto;
  padding: 0 9px 8px;
  color: var(--vp-c-text-3);
  font-size: 0.62rem;
}

.clq-node.is-active,
.clq-node.is-published {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  animation: clq-node-enter 320ms ease-out both;
}

.clq-node.is-deleted {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 8%, transparent);
}

.clq-node.is-deleted dd:first-of-type {
  color: var(--atlas-coral);
}

.clq-node.is-stale {
  border-style: dashed;
  opacity: 0.68;
}

.clq-node.is-hidden {
  border-style: dashed;
  background: transparent;
  opacity: 0.28;
}

.clq-demo__footer {
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
}

@keyframes clq-node-enter {
  from {
    transform: translateY(5px);
  }
  to {
    transform: translateY(0);
  }
}

@media (max-width: 700px) {
  .clq-demo__nodes {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 420px) {
  .clq-demo__nodes {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .clq-node {
    animation: none;
    transition: none;
  }
}
</style>
