<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

const steps: SourceAnimationStep[] = [
  {
    title: '触发扩容',
    method: 'if (++size > threshold) resize()',
    description: '旧容量为 4，新增映射让 size 超过 threshold，putVal 转入 resize。'
  },
  {
    title: '计算新容量',
    method: 'newCap = oldCap << 1',
    description: '容量从 4 翻倍为 8，新掩码只比旧掩码多检查 oldCap 对应的二进制位。'
  },
  {
    title: '分配并发布新表',
    method: 'threshold = newThr; table = newTab',
    description: '先创建长度 8 的数组并赋给 table，再进入旧桶迁移循环；oldTab 仍由局部变量持有。'
  },
  {
    title: '读取旧桶',
    method: 'e = oldTab[j]',
    description: 'oldTab[1] 中的 A、B、C 在旧掩码下都落入下标 1。'
  },
  {
    title: '按 oldCap 位拆分',
    method: '(e.hash & oldCap) == 0',
    description: 'A(1) 与 C(9) 的 oldCap 位为 0，进入低位链；B(5) 的该位为 1，进入高位链。'
  },
  {
    title: '挂载两条链',
    method: 'newTab[j] / newTab[j + oldCap]',
    description: '低位链挂到 newTab[1]，高位链挂到 newTab[5]；分组内原相对顺序保持不变。'
  }
]

const nodes = [
  { key: 'A', hash: 1, branch: 'lo' },
  { key: 'B', hash: 5, branch: 'hi' },
  { key: 'C', hash: 9, branch: 'lo' }
]
</script>

<template>
  <SourceAnimation title="resize 如何把一条旧链拆成两条新链" :steps="steps" :interval="2100">
    <template #visual="{ currentIndex }">
      <div class="resize-flow">
        <div class="resize-flow__table">
          <strong>旧数组快照：capacity = 4</strong>
          <div class="resize-flow__bucket" :class="{ 'is-reading': currentIndex === 3 }">
            <code>oldTab[1]</code>
            <div class="resize-flow__nodes">
              <template v-for="(node, index) in nodes" :key="node.key">
                <span class="resize-flow__node" :class="{ 'is-dimmed': currentIndex >= 5 }">{{ node.key }}</span>
                <span v-if="index < nodes.length - 1" class="resize-flow__arrow">→</span>
              </template>
            </div>
          </div>
        </div>

        <div class="resize-flow__decision" :class="{ 'is-active': currentIndex === 4 }">
          <code>hash &amp; 4</code>
          <div v-for="node in nodes" :key="node.key" class="resize-flow__bit">
            <span>{{ node.key }}({{ node.hash }})</span>
            <strong :class="`is-${node.branch}`">{{ node.hash & 4 }}</strong>
          </div>
        </div>

        <div class="resize-flow__table resize-flow__table--new" :class="{ 'is-visible': currentIndex >= 2 }">
          <strong>新表：capacity = 8</strong>
          <div class="resize-flow__bucket" :class="{ 'is-target': currentIndex >= 5 }">
            <code>newTab[1]</code>
            <div class="resize-flow__nodes">
              <template v-if="currentIndex >= 5">
                <span class="resize-flow__node is-arriving">A</span>
                <span class="resize-flow__arrow">→</span>
                <span class="resize-flow__node is-arriving is-late">C</span>
              </template>
              <span v-else class="resize-flow__empty">等待低位链</span>
            </div>
          </div>
          <div class="resize-flow__bucket" :class="{ 'is-target': currentIndex >= 5 }">
            <code>newTab[5]</code>
            <div class="resize-flow__nodes">
              <span v-if="currentIndex >= 5" class="resize-flow__node is-arriving">B</span>
              <span v-else class="resize-flow__empty">等待高位链</span>
            </div>
          </div>
        </div>

        <div class="resize-flow__publish" :class="{ 'is-published': currentIndex >= 2 }">
          <span>当前 table</span>
          <strong>{{ currentIndex >= 2 ? 'newTab[8]' : 'oldTab[4]' }}</strong>
          <span>threshold</span>
          <strong>{{ currentIndex >= 2 ? 6 : 3 }}</strong>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.resize-flow {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(110px, 0.55fr) minmax(210px, 1.15fr);
  gap: 18px;
  align-items: center;
  min-height: 230px;
}

.resize-flow__table {
  display: grid;
  gap: 10px;
}

.resize-flow__table > strong {
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
}

.resize-flow__table--new {
  opacity: 0.35;
  transform: translateX(12px);
  transition: opacity 280ms ease, transform 280ms ease;
}

.resize-flow__table--new.is-visible {
  opacity: 1;
  transform: translateX(0);
}

.resize-flow__bucket {
  display: grid;
  gap: 8px;
  min-height: 72px;
  padding: 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease;
}

.resize-flow__bucket.is-reading,
.resize-flow__bucket.is-target {
  border-left-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.resize-flow__bucket code {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.resize-flow__nodes {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
}

.resize-flow__node {
  display: grid;
  place-items: center;
  width: 32px;
  height: 30px;
  border: 1px solid var(--vp-c-brand-1);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
  transition: opacity 220ms ease;
}

.resize-flow__node.is-dimmed {
  opacity: 0.32;
}

.resize-flow__node.is-arriving {
  animation: arrive-node 440ms ease-out both;
}

.resize-flow__node.is-late {
  animation-delay: 140ms;
}

.resize-flow__arrow,
.resize-flow__empty {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.resize-flow__decision {
  display: grid;
  gap: 7px;
  padding: 12px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  opacity: 0.55;
  transition: opacity 180ms ease, border-color 180ms ease;
}

.resize-flow__decision.is-active {
  border-color: var(--atlas-coral);
  opacity: 1;
}

.resize-flow__decision > code {
  margin-bottom: 3px;
  color: var(--atlas-coral);
  font-size: 0.75rem;
  text-align: center;
}

.resize-flow__bit {
  display: flex;
  justify-content: space-between;
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.resize-flow__bit .is-lo {
  color: var(--vp-c-brand-1);
}

.resize-flow__bit .is-hi {
  color: var(--atlas-coral);
}

.resize-flow__publish {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: auto 1fr auto 1fr;
  gap: 8px 12px;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-3);
  font-size: 0.76rem;
  transition: color 180ms ease;
}

.resize-flow__publish strong {
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.resize-flow__publish.is-published {
  color: var(--vp-c-brand-1);
}

@keyframes arrive-node {
  from { opacity: 0; transform: translateX(-22px); }
  to { opacity: 1; transform: translateX(0); }
}

@media (max-width: 760px) {
  .resize-flow {
    grid-template-columns: 1fr;
  }

  .resize-flow__publish {
    grid-column: 1;
    grid-template-columns: auto 1fr;
  }
}
</style>
