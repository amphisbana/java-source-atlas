<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type NodeColor = 'black' | 'red'

interface TreeNodeSnapshot {
  key: number
  parent: number | null
  color: NodeColor
  active?: boolean
}

interface TreeSnapshot {
  inserted: number
  focus: string
  rule: string
  nodes: TreeNodeSnapshot[]
}

interface PositionedNode extends TreeNodeSnapshot {
  x: number
  y: number
}

interface TreeEdge {
  from: number
  to: number
  x1: number
  y1: number
  x2: number
  y2: number
}

const steps: SourceAnimationStep[] = [
  {
    title: '1 成为黑色根节点',
    method: 'root = new Entry<>(1, value, null)',
    description: '空树先完成一次自比较检查，再建立默认颜色为黑色的根节点；这条短路径不会调用 fixAfterInsertion。'
  },
  {
    title: '2 直接挂到右侧',
    method: 'compare(2, 1) > 0',
    description: '新节点 2 作为 1 的右孩子并被标为红色。父节点是黑色，不需要旋转或继续重着色。'
  },
  {
    title: '3 造成连续红节点',
    method: 'x = 3; parent = 2; uncle = null',
    description: '3 挂到 2 的右侧后，x 与 parent 都是红色；null 叔父按黑色处理，进入右右直线分支。'
  },
  {
    title: '围绕 1 做第一次左旋',
    method: 'setColor(2, BLACK) → rotateLeft(1)',
    description: '父节点 2 变黑、祖父 1 变红，再以 1 为轴左旋。中序次序仍是 1、2、3。'
  },
  {
    title: '插入 4 后叔父同红',
    method: 'parent = 3; uncle = 1; colorOf(uncle) == RED',
    description: '父节点 3 和叔父 1 都由红变黑，祖父 2 临时变红；循环结束时根节点 2 再被强制设黑。'
  },
  {
    title: '插入 5 后再次左旋',
    method: 'setColor(4, BLACK) → rotateLeft(3)',
    description: '5 与父节点 4 形成右右直线，修复后 4 成为这棵局部子树的黑色根，3 和 5 为红色孩子。'
  },
  {
    title: '插入 6 后向上重着色',
    method: '3、5 → BLACK; 4 → RED; x = 4',
    description: '父节点 5 与叔父 3 同红，只需把二者变黑、祖父 4 变红，并把修复焦点提升到 4。'
  },
  {
    title: '插入 7 后旋转局部子树',
    method: 'setColor(6, BLACK) → rotateLeft(5)',
    description: '7 与父节点 6 形成右右直线，以 5 为轴左旋；根节点仍是 2，黑高保持一致。'
  },
  {
    title: '8 先以红色叶子接入',
    method: 'new Entry(8, value, parent = 7)',
    description: '8 按二叉搜索树规则挂到 7 的右侧。父节点 7 为红色，因此 fixAfterInsertion 必须继续处理。'
  },
  {
    title: '低层重着色并提升焦点',
    method: '5、7 → BLACK; 6 → RED; x = 6',
    description: '叔父 5 与父节点 7 同红，第一轮不旋转。焦点提升到祖父 6 后，6 与它的父节点 4 又形成连续红。'
  },
  {
    title: '祖先层左旋完成修复',
    method: '4 → BLACK; 2 → RED; rotateLeft(2)',
    description: '叔父 1 为黑色，6 与 4 是右右直线：重着色后围绕 2 左旋，4 成为黑色根，所有路径黑高相同。'
  }
]

const snapshots: TreeSnapshot[] = [
  {
    inserted: 1,
    focus: 'root',
    rule: '根节点必须为黑色',
    nodes: [{ key: 1, parent: null, color: 'black', active: true }]
  },
  {
    inserted: 2,
    focus: 'x = 2',
    rule: '父黑：直接结束',
    nodes: [
      { key: 1, parent: null, color: 'black' },
      { key: 2, parent: 1, color: 'red', active: true }
    ]
  },
  {
    inserted: 3,
    focus: 'x = 3',
    rule: '父红、叔黑、右右',
    nodes: [
      { key: 1, parent: null, color: 'black' },
      { key: 2, parent: 1, color: 'red', active: true },
      { key: 3, parent: 2, color: 'red', active: true }
    ]
  },
  {
    inserted: 3,
    focus: 'rotateLeft(1)',
    rule: '直线：一次旋转',
    nodes: [
      { key: 2, parent: null, color: 'black', active: true },
      { key: 1, parent: 2, color: 'red', active: true },
      { key: 3, parent: 2, color: 'red' }
    ]
  },
  {
    inserted: 4,
    focus: 'x = 2',
    rule: '叔父红：颜色上推',
    nodes: [
      { key: 2, parent: null, color: 'black', active: true },
      { key: 1, parent: 2, color: 'black', active: true },
      { key: 3, parent: 2, color: 'black', active: true },
      { key: 4, parent: 3, color: 'red' }
    ]
  },
  {
    inserted: 5,
    focus: 'rotateLeft(3)',
    rule: '父红、叔黑、右右',
    nodes: [
      { key: 2, parent: null, color: 'black' },
      { key: 1, parent: 2, color: 'black' },
      { key: 4, parent: 2, color: 'black', active: true },
      { key: 3, parent: 4, color: 'red', active: true },
      { key: 5, parent: 4, color: 'red', active: true }
    ]
  },
  {
    inserted: 6,
    focus: 'x = 4',
    rule: '叔父红：颜色上推',
    nodes: [
      { key: 2, parent: null, color: 'black' },
      { key: 1, parent: 2, color: 'black' },
      { key: 4, parent: 2, color: 'red', active: true },
      { key: 3, parent: 4, color: 'black', active: true },
      { key: 5, parent: 4, color: 'black', active: true },
      { key: 6, parent: 5, color: 'red' }
    ]
  },
  {
    inserted: 7,
    focus: 'rotateLeft(5)',
    rule: '父红、叔黑、右右',
    nodes: [
      { key: 2, parent: null, color: 'black' },
      { key: 1, parent: 2, color: 'black' },
      { key: 4, parent: 2, color: 'red' },
      { key: 3, parent: 4, color: 'black' },
      { key: 6, parent: 4, color: 'black', active: true },
      { key: 5, parent: 6, color: 'red', active: true },
      { key: 7, parent: 6, color: 'red', active: true }
    ]
  },
  {
    inserted: 8,
    focus: 'x = 8',
    rule: '父红：进入修复循环',
    nodes: [
      { key: 2, parent: null, color: 'black' },
      { key: 1, parent: 2, color: 'black' },
      { key: 4, parent: 2, color: 'red' },
      { key: 3, parent: 4, color: 'black' },
      { key: 6, parent: 4, color: 'black' },
      { key: 5, parent: 6, color: 'red' },
      { key: 7, parent: 6, color: 'red', active: true },
      { key: 8, parent: 7, color: 'red', active: true }
    ]
  },
  {
    inserted: 8,
    focus: 'x = 6',
    rule: '第一轮：叔父红',
    nodes: [
      { key: 2, parent: null, color: 'black' },
      { key: 1, parent: 2, color: 'black' },
      { key: 4, parent: 2, color: 'red', active: true },
      { key: 3, parent: 4, color: 'black' },
      { key: 6, parent: 4, color: 'red', active: true },
      { key: 5, parent: 6, color: 'black', active: true },
      { key: 7, parent: 6, color: 'black', active: true },
      { key: 8, parent: 7, color: 'red' }
    ]
  },
  {
    inserted: 8,
    focus: 'rotateLeft(2)',
    rule: '第二轮：右右旋转',
    nodes: [
      { key: 4, parent: null, color: 'black', active: true },
      { key: 2, parent: 4, color: 'red', active: true },
      { key: 1, parent: 2, color: 'black' },
      { key: 3, parent: 2, color: 'black' },
      { key: 6, parent: 4, color: 'red', active: true },
      { key: 5, parent: 6, color: 'black' },
      { key: 7, parent: 6, color: 'black' },
      { key: 8, parent: 7, color: 'red' }
    ]
  }
]

/**
 * 根据 parent 链计算节点深度；动画数据量很小，逐层查找更便于核对每个快照。
 */
function depthOf(node: TreeNodeSnapshot, nodes: TreeNodeSnapshot[]): number {
  let depth = 0
  let parentKey = node.parent
  while (parentKey !== null) {
    depth += 1
    parentKey = nodes.find(candidate => candidate.key === parentKey)?.parent ?? null
  }
  return depth
}

/**
 * 按中序键排名确定横坐标，既保持二叉搜索树顺序，也让不同节点数的快照居中显示。
 */
function positionedNodes(snapshot: TreeSnapshot): PositionedNode[] {
  const sortedKeys = snapshot.nodes.map(node => node.key).sort((left, right) => left - right)
  const width = sortedKeys.length === 1 ? 0 : Math.min(600, (sortedKeys.length - 1) * 86)
  const start = (720 - width) / 2
  const interval = sortedKeys.length === 1 ? 0 : width / (sortedKeys.length - 1)

  return snapshot.nodes.map(node => ({
    ...node,
    x: start + sortedKeys.indexOf(node.key) * interval,
    y: 38 + depthOf(node, snapshot.nodes) * 66
  }))
}

/**
 * 从每个节点的 parent 引用生成连线，避免快照中的边与父子关系出现偏差。
 */
function edgesFor(snapshot: TreeSnapshot): TreeEdge[] {
  const nodes = positionedNodes(snapshot)
  return nodes.flatMap(node => {
    if (node.parent === null) {
      return []
    }
    const parent = nodes.find(candidate => candidate.key === node.parent)
    return parent === undefined ? [] : [{
      from: parent.key,
      to: node.key,
      x1: parent.x,
      y1: parent.y,
      x2: node.x,
      y2: node.y
    }]
  })
}
</script>

<template>
  <SourceAnimation title="TreeMap 递增插入中的重着色与旋转" :steps="steps" :interval="2300">
    <template #visual="{ currentIndex }">
      <div class="tree-map-flow">
        <div class="tree-map-flow__meta">
          <span>本轮插入 <strong>{{ snapshots[currentIndex].inserted }}</strong></span>
          <span>修复焦点 <strong>{{ snapshots[currentIndex].focus }}</strong></span>
          <span>{{ snapshots[currentIndex].rule }}</span>
        </div>

        <div class="tree-map-flow__canvas">
          <svg
            viewBox="0 0 720 280"
            role="img"
            :aria-label="`插入 ${snapshots[currentIndex].inserted} 后的红黑树：${snapshots[currentIndex].rule}`"
          >
            <g class="tree-map-flow__edges">
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
              :key="node.key"
              class="tree-map-flow__node"
              :class="[`is-${node.color}`, { 'is-active': node.active }]"
              :transform="`translate(${node.x} ${node.y})`"
            >
              <circle r="22" />
              <text text-anchor="middle" dominant-baseline="central">{{ node.key }}</text>
              <title>键 {{ node.key }}，{{ node.color === 'red' ? '红色' : '黑色' }}</title>
            </g>
          </svg>
        </div>

        <div class="tree-map-flow__legend" aria-label="节点颜色图例">
          <span><i class="is-black" />黑节点</span>
          <span><i class="is-red" />红节点</span>
          <span><i class="is-active" />本步关注</span>
          <code>已插入键始终按升序排列</code>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.tree-map-flow {
  display: grid;
  gap: 12px;
  min-height: 310px;
}

.tree-map-flow__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  color: var(--vp-c-text-3);
  font-size: 0.76rem;
}

.tree-map-flow__meta strong {
  margin-left: 4px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.tree-map-flow__canvas {
  min-width: 0;
  overflow-x: auto;
  border-block: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.tree-map-flow__canvas svg {
  display: block;
  width: 100%;
  min-width: 600px;
  height: 250px;
}

.tree-map-flow__edges line {
  stroke: var(--atlas-line-strong, var(--vp-c-divider));
  stroke-width: 2;
  vector-effect: non-scaling-stroke;
}

.tree-map-flow__node {
  animation: tree-node-enter 320ms ease-out both;
}

.tree-map-flow__node circle {
  stroke-width: 2;
  transition: fill 220ms ease, stroke 220ms ease;
}

.tree-map-flow__node text {
  fill: white;
  font-family: var(--vp-font-family-mono);
  font-size: 14px;
  font-weight: 800;
}

.tree-map-flow__node.is-black circle {
  fill: #303640;
  stroke: #303640;
}

.tree-map-flow__node.is-red circle {
  fill: var(--atlas-coral);
  stroke: var(--atlas-coral);
}

.tree-map-flow__node.is-active circle {
  stroke: var(--vp-c-brand-1);
  stroke-width: 5;
}

.tree-map-flow__legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 18px;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.tree-map-flow__legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tree-map-flow__legend i {
  width: 11px;
  height: 11px;
  border: 2px solid transparent;
  border-radius: 50%;
}

.tree-map-flow__legend i.is-black {
  background: #303640;
}

.tree-map-flow__legend i.is-red {
  background: var(--atlas-coral);
}

.tree-map-flow__legend i.is-active {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-bg);
}

.tree-map-flow__legend code {
  margin-left: auto;
  color: var(--vp-c-brand-1);
}

@keyframes tree-node-enter {
  from { opacity: 0.45; }
  to { opacity: 1; }
}

@media (max-width: 640px) {
  .tree-map-flow {
    min-height: 330px;
  }

  .tree-map-flow__canvas svg {
    width: 600px;
  }

  .tree-map-flow__legend code {
    width: 100%;
    margin-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .tree-map-flow__node {
    animation: none;
  }
}
</style>
