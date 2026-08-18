<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

const steps: SourceAnimationStep[] = [
  {
    title: '进入 putVal',
    method: 'put(K,V) → putVal(...)',
    description: 'key=C 的原始 hashCode 为 5；当前 table 长度为 4、size 为 2、threshold 为 3。'
  },
  {
    title: '扰动并计算下标',
    method: 'hash(key) / (n - 1) & hash',
    description: '示例哈希较小，扰动后仍为 5；5 & 3 得到桶下标 1。'
  },
  {
    title: '读取桶首',
    method: 'p = tab[i = (n - 1) & hash]',
    description: 'table[1] 已有节点 A，不能走空桶直接写入分支。'
  },
  {
    title: '比较并遍历链表',
    method: 'for (binCount = 0; ; ++binCount)',
    description: '先比较缓存 hash，再比较引用或 equals；C 与 A 不相等，继续查看 next。'
  },
  {
    title: '追加新节点',
    method: 'p.next = newNode(hash, key, value, null)',
    description: '遍历到链尾仍未找到相同键，于是把 C 追加到 table[1] 的链表尾部。'
  },
  {
    title: '更新结构状态',
    method: '++modCount; if (++size > threshold) resize()',
    description: 'size 从 2 增为 3，与 threshold 相等，因此本次不会扩容；判断条件是大于而不是大于等于。'
  }
]
</script>

<template>
  <SourceAnimation title="一次碰撞写入如何穿过 putVal" :steps="steps">
    <template #visual="{ currentIndex }">
      <div class="hash-put">
        <div class="hash-put__input" :class="{ 'is-moving': currentIndex >= 4 }">
          <span>待写入</span>
          <strong>C</strong>
          <code>hash=5</code>
        </div>

        <div class="hash-put__formula" :class="{ 'is-active': currentIndex === 1 }">
          <code>(4 - 1) &amp; 5 = 1</code>
        </div>

        <div class="hash-put__table" aria-label="HashMap 桶数组快照">
          <div
            v-for="bucket in 4"
            :key="bucket - 1"
            class="hash-put__bucket"
            :class="{ 'is-active': bucket - 1 === 1 && currentIndex >= 2 }"
          >
            <span class="hash-put__index">table[{{ bucket - 1 }}]</span>
            <div class="hash-put__chain">
              <template v-if="bucket - 1 === 1">
                <span class="hash-put__node" :class="{ 'is-comparing': currentIndex === 3 }">A</span>
                <span v-if="currentIndex >= 4" class="hash-put__arrow">→</span>
                <span v-if="currentIndex >= 4" class="hash-put__node is-new">C</span>
              </template>
              <span v-else-if="bucket - 1 === 2" class="hash-put__node">B</span>
              <span v-else class="hash-put__empty">null</span>
            </div>
          </div>
        </div>

        <div class="hash-put__state">
          <span>table.length <strong>4</strong></span>
          <span>size <strong>{{ currentIndex >= 5 ? 3 : 2 }}</strong></span>
          <span>threshold <strong>3</strong></span>
          <span>modCount <strong>{{ currentIndex >= 5 ? 3 : 2 }}</strong></span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.hash-put {
  display: grid;
  grid-template-columns: minmax(110px, 0.45fr) minmax(110px, 0.5fr) minmax(260px, 1.5fr);
  gap: 18px;
  align-items: center;
  min-height: 210px;
}

.hash-put__input,
.hash-put__formula {
  display: grid;
  justify-items: center;
  gap: 6px;
  transition: opacity 240ms ease, transform 320ms ease;
}

.hash-put__input span {
  color: var(--vp-c-text-3);
  font-size: 0.75rem;
}

.hash-put__input strong,
.hash-put__node {
  display: grid;
  place-items: center;
  width: 42px;
  height: 36px;
  border: 1px solid var(--vp-c-brand-1);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
}

.hash-put__input code,
.hash-put__formula code {
  color: var(--vp-c-text-2);
  font-size: 0.75rem;
}

.hash-put__input.is-moving {
  opacity: 0.35;
  transform: translateX(18px);
}

.hash-put__formula {
  padding: 12px 8px;
  border-left: 2px solid var(--atlas-line);
  color: var(--vp-c-text-3);
}

.hash-put__formula.is-active {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 8%, transparent);
}

.hash-put__table {
  display: grid;
  gap: 6px;
}

.hash-put__bucket {
  display: grid;
  grid-template-columns: 76px minmax(120px, 1fr);
  gap: 10px;
  align-items: center;
  min-height: 42px;
  padding: 4px 8px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease;
}

.hash-put__bucket.is-active {
  border-left-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.hash-put__index {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.hash-put__chain {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hash-put__node {
  width: 36px;
  height: 30px;
  font-size: 0.78rem;
}

.hash-put__node.is-comparing {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
  animation: compare-node 760ms ease-in-out infinite alternate;
}

.hash-put__node.is-new {
  animation: append-node 420ms ease-out both;
}

.hash-put__arrow,
.hash-put__empty {
  color: var(--vp-c-text-3);
  font-size: 0.78rem;
}

.hash-put__state {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
}

.hash-put__state span {
  padding: 10px 8px 0;
  color: var(--vp-c-text-3);
  font-size: 0.75rem;
  text-align: center;
}

.hash-put__state strong {
  margin-left: 4px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

@keyframes append-node {
  from { opacity: 0; transform: translateX(-24px); }
  to { opacity: 1; transform: translateX(0); }
}

@keyframes compare-node {
  from { box-shadow: 0 0 0 0 color-mix(in srgb, var(--atlas-coral) 20%, transparent); }
  to { box-shadow: 0 0 0 5px color-mix(in srgb, var(--atlas-coral) 20%, transparent); }
}

@media (max-width: 720px) {
  .hash-put {
    grid-template-columns: 1fr 1fr;
  }

  .hash-put__table {
    grid-column: 1 / -1;
  }

  .hash-put__state {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
