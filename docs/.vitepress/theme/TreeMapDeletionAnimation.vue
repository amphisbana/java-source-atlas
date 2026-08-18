<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type NodeColor = 'black' | 'red'

interface DeletionNode {
  id: string
  key: string
  parent: string | null
  x: number
  color: NodeColor
  role?: string
  active?: boolean
  phantom?: boolean
}

interface DeletionSnapshot {
  scenario: string
  focus: string
  branch: string
  nodes: DeletionNode[]
}

interface PositionedDeletionNode extends DeletionNode {
  y: number
}

interface DeletionEdge {
  from: string
  to: string
  x1: number
  y1: number
  x2: number
  y2: number
}

const steps: SourceAnimationStep[] = [
  {
    title: '删除 4：先识别双孩子节点',
    method: 'deleteEntry(p = entry(4))',
    description: '递增插入 1..8 后，4 是黑色根且同时拥有左右孩子。源码不会直接拼接两棵子树，而是先寻找中序后继。'
  },
  {
    title: '后继 5 的内容复制到目标 Entry',
    method: 's = successor(p); p.key = s.key; p.value = s.value; p = s',
    description: '原根 Entry 暂时改为键 5，右子树中的后继 Entry 仍然也是 5；随后变量 p 改指后继，真正摘除的是这个至多一个孩子的节点。'
  },
  {
    title: '黑色后继作为幻影 x 保留方向',
    method: 'fixAfterDeletion(x = p)',
    description: '后继 5 是黑色叶子。它暂时仍挂在 6 的左侧，使修复代码能够知道 x 的方向；兄弟 7 为黑，远侄 8 为红。'
  },
  {
    title: '远侄红：一次左旋完成修复',
    method: 'setColor(sib, colorOf(parent)); rotateLeft(parent)',
    description: '7 继承父节点 6 的红色，6 和远侄 8 变黑，再围绕 6 左旋。x 被设为 root 结束循环，最终再真正断开后继 Entry。'
  },
  {
    title: '兄弟红：先转换为黑兄弟',
    method: 'delete 1: sib = 4(RED)',
    description: '序列 [2,1,4,3,7,5,6] 删除黑叶 1。兄弟 4 为红时，先把兄弟变黑、父节点 2 变红，并围绕 2 左旋。'
  },
  {
    title: '旋转后重新读取兄弟 3',
    method: 'sib = rightOf(parentOf(x))',
    description: '兄弟红分支并不直接补黑高；它只改变局部形状。旋转后 x 仍在 2 左侧，新兄弟变成黑色节点 3。'
  },
  {
    title: '兄弟与两个孩子全黑：问题上推',
    method: 'setColor(sib, RED); x = parentOf(x)',
    description: '3 没有孩子，按黑色处理。把 3 变红后，缺失黑高上推到原父节点 2；2 原本为红，循环结束时被设黑。'
  },
  {
    title: '近侄红、远侄黑：先旋转兄弟',
    method: 'delete 4: sib = 7; near = 6(RED); far = null',
    description: '序列 [3,2,4,5,7,1,6] 删除黑叶 4。x 在左侧，兄弟 7 的左孩子 6 是近侄，右孩子为空且按黑色处理。'
  },
  {
    title: '近侄分支转换成远侄红',
    method: 'setColor(near, BLACK); setColor(sib, RED); rotateRight(sib)',
    description: '围绕旧兄弟 7 右旋后，新兄弟是黑色 6，它的右孩子 7 为红；算法已转换到统一的远侄红终结分支。'
  },
  {
    title: '远侄红终结分支恢复黑高',
    method: 'rotateLeft(parentOf(x)); x = root',
    description: '6 继承父节点 5 的红色，5 与远侄 7 变黑，再围绕 5 左旋。删除 4 后，中序顺序和各条路径黑高同时恢复。'
  }
]

const snapshots: DeletionSnapshot[] = [
  {
    scenario: '递增插入 1..8，remove(4)', focus: 'p = 4', branch: '双孩子节点',
    nodes: [
      { id: '4', key: '4', parent: null, x: 360, color: 'black', role: 'target', active: true },
      { id: '2', key: '2', parent: '4', x: 190, color: 'red' },
      { id: '6', key: '6', parent: '4', x: 530, color: 'red' },
      { id: '1', key: '1', parent: '2', x: 105, color: 'black' },
      { id: '3', key: '3', parent: '2', x: 275, color: 'black' },
      { id: '5', key: '5', parent: '6', x: 445, color: 'black', role: 'successor', active: true },
      { id: '7', key: '7', parent: '6', x: 615, color: 'black' },
      { id: '8', key: '8', parent: '7', x: 675, color: 'red' }
    ]
  },
  {
    scenario: '递增插入 1..8，remove(4)', focus: 'p → successor Entry', branch: '复制内容，未摘节点',
    nodes: [
      { id: 'root-copy', key: '5', parent: null, x: 360, color: 'black', role: 'copied', active: true },
      { id: '2', key: '2', parent: 'root-copy', x: 190, color: 'red' },
      { id: '6', key: '6', parent: 'root-copy', x: 530, color: 'red' },
      { id: '1', key: '1', parent: '2', x: 105, color: 'black' },
      { id: '3', key: '3', parent: '2', x: 275, color: 'black' },
      { id: 'successor', key: '5', parent: '6', x: 445, color: 'black', role: 'p', active: true },
      { id: '7', key: '7', parent: '6', x: 615, color: 'black' },
      { id: '8', key: '8', parent: '7', x: 675, color: 'red' }
    ]
  },
  {
    scenario: '递增插入 1..8，remove(4)', focus: 'x = successor 5', branch: '远侄 8 为红',
    nodes: [
      { id: '5-root', key: '5', parent: null, x: 360, color: 'black' },
      { id: '2', key: '2', parent: '5-root', x: 190, color: 'red' },
      { id: '6', key: '6', parent: '5-root', x: 530, color: 'red', role: 'parent', active: true },
      { id: '1', key: '1', parent: '2', x: 105, color: 'black' },
      { id: '3', key: '3', parent: '2', x: 275, color: 'black' },
      { id: 'x', key: '5*', parent: '6', x: 445, color: 'black', role: 'x', active: true, phantom: true },
      { id: '7', key: '7', parent: '6', x: 615, color: 'black', role: 'sib', active: true },
      { id: '8', key: '8', parent: '7', x: 675, color: 'red', role: 'far', active: true }
    ]
  },
  {
    scenario: '递增插入 1..8，remove(4)', focus: 'rotateLeft(6)', branch: '远侄红完成',
    nodes: [
      { id: '5', key: '5', parent: null, x: 360, color: 'black' },
      { id: '2', key: '2', parent: '5', x: 190, color: 'red' },
      { id: '7', key: '7', parent: '5', x: 570, color: 'red', active: true },
      { id: '1', key: '1', parent: '2', x: 105, color: 'black' },
      { id: '3', key: '3', parent: '2', x: 275, color: 'black' },
      { id: '6', key: '6', parent: '7', x: 480, color: 'black', active: true },
      { id: '8', key: '8', parent: '7', x: 660, color: 'black', active: true }
    ]
  },
  {
    scenario: '[2,1,4,3,7,5,6]，remove(1)', focus: 'x = 1', branch: '兄弟 4 为红',
    nodes: [
      { id: '2', key: '2', parent: null, x: 180, color: 'black', role: 'parent', active: true },
      { id: '1', key: '1*', parent: '2', x: 95, color: 'black', role: 'x', active: true, phantom: true },
      { id: '4', key: '4', parent: '2', x: 350, color: 'red', role: 'sib', active: true },
      { id: '3', key: '3', parent: '4', x: 265, color: 'black' },
      { id: '6', key: '6', parent: '4', x: 520, color: 'black' },
      { id: '5', key: '5', parent: '6', x: 435, color: 'red' },
      { id: '7', key: '7', parent: '6', x: 605, color: 'red' }
    ]
  },
  {
    scenario: '[2,1,4,3,7,5,6]，remove(1)', focus: 'rotateLeft(2) 后', branch: '重新读取黑兄弟 3',
    nodes: [
      { id: '4', key: '4', parent: null, x: 350, color: 'black', active: true },
      { id: '2', key: '2', parent: '4', x: 180, color: 'red', role: 'parent', active: true },
      { id: '6', key: '6', parent: '4', x: 520, color: 'black' },
      { id: '1', key: '1*', parent: '2', x: 95, color: 'black', role: 'x', active: true, phantom: true },
      { id: '3', key: '3', parent: '2', x: 265, color: 'black', role: 'sib', active: true },
      { id: '5', key: '5', parent: '6', x: 435, color: 'red' },
      { id: '7', key: '7', parent: '6', x: 605, color: 'red' }
    ]
  },
  {
    scenario: '[2,1,4,3,7,5,6]，remove(1)', focus: 'x = 2 → BLACK', branch: '全黑上推完成',
    nodes: [
      { id: '4', key: '4', parent: null, x: 350, color: 'black' },
      { id: '2', key: '2', parent: '4', x: 180, color: 'black', role: 'x', active: true },
      { id: '6', key: '6', parent: '4', x: 520, color: 'black' },
      { id: '3', key: '3', parent: '2', x: 265, color: 'red', role: 'old sib', active: true },
      { id: '5', key: '5', parent: '6', x: 435, color: 'red' },
      { id: '7', key: '7', parent: '6', x: 605, color: 'red' }
    ]
  },
  {
    scenario: '[3,2,4,5,7,1,6]，remove(4)', focus: 'x = 4', branch: '近侄 6 红，远侄黑',
    nodes: [
      { id: '3', key: '3', parent: null, x: 265, color: 'black' },
      { id: '2', key: '2', parent: '3', x: 180, color: 'black' },
      { id: '5', key: '5', parent: '3', x: 435, color: 'red', role: 'parent', active: true },
      { id: '1', key: '1', parent: '2', x: 95, color: 'red' },
      { id: '4', key: '4*', parent: '5', x: 350, color: 'black', role: 'x', active: true, phantom: true },
      { id: '7', key: '7', parent: '5', x: 605, color: 'black', role: 'sib', active: true },
      { id: '6', key: '6', parent: '7', x: 520, color: 'red', role: 'near', active: true }
    ]
  },
  {
    scenario: '[3,2,4,5,7,1,6]，remove(4)', focus: 'rotateRight(7) 后', branch: '转换为远侄 7 红',
    nodes: [
      { id: '3', key: '3', parent: null, x: 265, color: 'black' },
      { id: '2', key: '2', parent: '3', x: 180, color: 'black' },
      { id: '5', key: '5', parent: '3', x: 435, color: 'red', role: 'parent', active: true },
      { id: '1', key: '1', parent: '2', x: 95, color: 'red' },
      { id: '4', key: '4*', parent: '5', x: 350, color: 'black', role: 'x', active: true, phantom: true },
      { id: '6', key: '6', parent: '5', x: 520, color: 'black', role: 'sib', active: true },
      { id: '7', key: '7', parent: '6', x: 605, color: 'red', role: 'far', active: true }
    ]
  },
  {
    scenario: '[3,2,4,5,7,1,6]，remove(4)', focus: 'rotateLeft(5)', branch: '远侄红完成',
    nodes: [
      { id: '3', key: '3', parent: null, x: 265, color: 'black' },
      { id: '2', key: '2', parent: '3', x: 180, color: 'black' },
      { id: '6', key: '6', parent: '3', x: 520, color: 'red', active: true },
      { id: '1', key: '1', parent: '2', x: 95, color: 'red' },
      { id: '5', key: '5', parent: '6', x: 435, color: 'black', active: true },
      { id: '7', key: '7', parent: '6', x: 605, color: 'black', active: true }
    ]
  }
]

/**
 * 根据 parent 链计算节点深度，保证每个真实删除快照的层级与链接一致。
 */
function depthOf(node: DeletionNode, nodes: DeletionNode[]): number {
  let depth = 0
  let parentId = node.parent
  while (parentId !== null) {
    depth += 1
    parentId = nodes.find(candidate => candidate.id === parentId)?.parent ?? null
  }
  return depth
}

/**
 * 为当前快照补充纵坐标；横坐标由稳定的键顺序位置显式给出。
 */
function positionedNodes(snapshot: DeletionSnapshot): PositionedDeletionNode[] {
  return snapshot.nodes.map(node => ({
    ...node,
    y: 40 + depthOf(node, snapshot.nodes) * 66
  }))
}

/**
 * 从 parent 引用生成连线，避免动画边与节点快照相互矛盾。
 */
function edgesFor(snapshot: DeletionSnapshot): DeletionEdge[] {
  const nodes = positionedNodes(snapshot)
  return nodes.flatMap(node => {
    if (node.parent === null) return []
    const parent = nodes.find(candidate => candidate.id === node.parent)
    return parent === undefined ? [] : [{
      from: parent.id,
      to: node.id,
      x1: parent.x,
      y1: parent.y,
      x2: node.x,
      y2: node.y
    }]
  })
}
</script>

<template>
  <SourceAnimation title="TreeMap 删除中的后继替换与四类修复" :steps="steps" :interval="2700">
    <template #visual="{ currentIndex }">
      <div class="tree-delete">
        <div class="tree-delete__meta">
          <span>输入 <strong>{{ snapshots[currentIndex].scenario }}</strong></span>
          <span>焦点 <strong>{{ snapshots[currentIndex].focus }}</strong></span>
          <span>分支 <strong>{{ snapshots[currentIndex].branch }}</strong></span>
        </div>

        <div class="tree-delete__canvas">
          <svg viewBox="0 0 720 300" role="img" :aria-label="snapshots[currentIndex].branch">
            <g class="tree-delete__edges">
              <line
                v-for="edge in edgesFor(snapshots[currentIndex])"
                :key="`${edge.from}-${edge.to}`"
                :x1="edge.x1"
                :y1="edge.y1"
                :x2="edge.x2"
                :y2="edge.y2"
              />
            </g>
            <g
              v-for="node in positionedNodes(snapshots[currentIndex])"
              :key="node.id"
              class="tree-delete__node"
              :class="[`is-${node.color}`, { 'is-active': node.active, 'is-phantom': node.phantom }]"
              :transform="`translate(${node.x} ${node.y})`"
            >
              <circle r="21" />
              <text text-anchor="middle" dominant-baseline="central">{{ node.key }}</text>
              <text v-if="node.role" class="tree-delete__role" y="55" text-anchor="middle">{{ node.role }}</text>
              <title>键 {{ node.key }}，{{ node.color === 'red' ? '红色' : '黑色' }}{{ node.role ? `，${node.role}` : '' }}</title>
            </g>
          </svg>
        </div>

        <div class="tree-delete__legend">
          <span><i class="is-black" />黑节点</span>
          <span><i class="is-red" />红节点</span>
          <span><i class="is-phantom" />待摘除的幻影 x</span>
          <code>近/远方向始终以 x 为参照</code>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.tree-delete {
  display: grid;
  gap: 12px;
  min-width: 0;
  min-height: 350px;
}

.tree-delete__meta,
.tree-delete__legend {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 8px 18px;
  align-items: center;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.tree-delete__meta strong {
  margin-left: 4px;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.tree-delete__canvas {
  min-width: 0;
  overflow: hidden;
  border-block: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.tree-delete__canvas svg {
  display: block;
  width: 100%;
  min-width: 0;
  height: 278px;
}

.tree-delete__edges line {
  stroke: var(--atlas-line-strong, var(--vp-c-divider));
  stroke-width: 2;
  vector-effect: non-scaling-stroke;
}

.tree-delete__node {
  animation: tree-delete-enter 280ms ease-out both;
}

.tree-delete__node circle {
  stroke-width: 2;
  transition: fill 200ms ease, stroke 200ms ease;
}

.tree-delete__node > text:not(.tree-delete__role) {
  fill: white;
  font-family: var(--vp-font-family-mono);
  font-size: 15px;
  font-weight: 800;
}

.tree-delete__node.is-black circle {
  fill: #303640;
  stroke: #303640;
}

.tree-delete__node.is-red circle {
  fill: var(--atlas-coral);
  stroke: var(--atlas-coral);
}

.tree-delete__node.is-active circle {
  stroke: var(--vp-c-brand-1);
  stroke-width: 5;
}

.tree-delete__node.is-phantom circle {
  fill: var(--vp-c-bg);
  stroke: var(--atlas-coral);
  stroke-dasharray: 4 3;
}

.tree-delete__node.is-phantom > text:not(.tree-delete__role) {
  fill: var(--atlas-coral);
}

.tree-delete__role {
  fill: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 15px;
}

.tree-delete__legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tree-delete__legend i {
  width: 11px;
  height: 11px;
  border: 2px solid transparent;
  border-radius: 50%;
}

.tree-delete__legend i.is-black { background: #303640; }
.tree-delete__legend i.is-red { background: var(--atlas-coral); }
.tree-delete__legend i.is-phantom { border-color: var(--atlas-coral); border-style: dashed; }

.tree-delete__legend code {
  margin-left: auto;
  color: var(--vp-c-brand-1);
  font-size: 0.68rem;
}

@keyframes tree-delete-enter {
  from { opacity: 0.5; }
  to { opacity: 1; }
}

@media (max-width: 640px) {
  .tree-delete { min-height: 350px; }
  .tree-delete__node circle { r: 28px; }
  .tree-delete__node > text:not(.tree-delete__role),
  .tree-delete__role { font-size: 27px; }
  .tree-delete__legend code { width: 100%; margin-left: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .tree-delete__node { animation: none; }
}
</style>
