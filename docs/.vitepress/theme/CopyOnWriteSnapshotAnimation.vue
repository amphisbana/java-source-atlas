<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

const steps: SourceAnimationStep[] = [
  {
    title: '当前数组已发布',
    method: 'volatile array -> array#1',
    description: '列表的 volatile array 当前指向 array#1，内容为 A、B、C；普通读取可以直接取得这一份已发布快照。'
  },
  {
    title: '迭代器保存快照',
    method: 'new COWIterator(getArray(), 0)',
    description: '读线程 R 把 array#1 保存到 snapshot。之后列表发布新数组，也不会替换这个引用。'
  },
  {
    title: '持锁读取最新数组',
    method: 'lock.lock(); elements = getArray()',
    description: '写线程 W 先取得锁，再读取当前 array。持锁后的重新读取能避免等待期间其他写线程已经发布的新结果被覆盖。'
  },
  {
    title: '复制出新数组',
    method: 'Arrays.copyOf(elements, len + 1)',
    description: '创建 array#2 并复制 A、B、C。array#1 保持不变，所以旧迭代器仍可无锁读取。'
  },
  {
    title: '只修改副本',
    method: 'newElements[len] = "D"',
    description: 'D 只写入尚未发布的 array#2；当前列表和旧迭代器此时仍观察 array#1。'
  },
  {
    title: '发布新快照',
    method: 'setArray(newElements)',
    description: '写线程仍持有锁时执行 volatile 写，让列表改为指向 array#2；新读线程已经可以看到 D。'
  },
  {
    title: '释放写锁',
    method: 'finally { lock.unlock(); }',
    description: '写线程释放锁。旧迭代器继续稳定返回 A、B、C，之后的读取从 array#2 取得 A、B、C、D。'
  }
]

const oldValues = ['A', 'B', 'C']
const newValues = ['A', 'B', 'C', 'D']
</script>

<template>
  <SourceAnimation title="一次 add 如何同时保留旧快照并发布新数组" :steps="steps" :interval="2200">
    <template #visual="{ currentIndex }">
      <div class="cow-flow">
        <div class="cow-flow__pointers">
          <div class="cow-flow__pointer" :class="{ 'is-active': currentIndex === 0, 'is-new': currentIndex >= 5 }">
            <span>list.array</span>
            <strong>{{ currentIndex >= 5 ? 'array#2' : 'array#1' }}</strong>
          </div>
          <div class="cow-flow__pointer" :class="{ 'is-active': currentIndex >= 1 }">
            <span>R.snapshot</span>
            <strong>{{ currentIndex >= 1 ? 'array#1' : '未创建' }}</strong>
          </div>
          <div class="cow-flow__lock" :class="{ 'is-locked': currentIndex >= 2 && currentIndex < 6 }">
            <span>写锁</span>
            <strong>{{ currentIndex >= 2 && currentIndex < 6 ? 'W 持有' : '空闲' }}</strong>
          </div>
        </div>

        <div class="cow-flow__arrays">
          <section class="cow-array cow-array--old" :class="{ 'is-reader': currentIndex >= 1 }">
            <header>
              <strong>array#1</strong>
              <span>{{ currentIndex >= 5 ? '旧迭代器持有' : '当前已发布' }}</span>
            </header>
            <div class="cow-array__cells">
              <span v-for="(value, index) in oldValues" :key="value">
                <b>{{ value }}</b><small>{{ index }}</small>
              </span>
            </div>
            <p>发布后不再原位修改</p>
          </section>

          <div class="cow-flow__copy" :class="{ 'is-visible': currentIndex >= 3 }">
            <span>复制引用</span>
            <strong>→</strong>
          </div>

          <section class="cow-array cow-array--new" :class="{ 'is-visible': currentIndex >= 3, 'is-published': currentIndex >= 5 }">
            <header>
              <strong>{{ currentIndex >= 3 ? 'array#2' : 'newElements' }}</strong>
              <span>{{ currentIndex < 3 ? '尚未创建' : currentIndex >= 5 ? '新读线程可见' : 'W 的私有副本' }}</span>
            </header>
            <div v-if="currentIndex < 3" class="cow-array__pending">等待 Arrays.copyOf</div>
            <div v-else class="cow-array__cells">
              <span
                v-for="(value, index) in newValues"
                :key="value"
                :class="{ 'is-empty': value === 'D' && currentIndex < 4, 'is-added': value === 'D' && currentIndex >= 4 }"
              >
                <b>{{ value === 'D' && currentIndex < 4 ? '_' : value }}</b><small>{{ index }}</small>
              </span>
            </div>
            <p>{{ currentIndex < 3 ? '此时还没有第二份数组' : currentIndex >= 5 ? '通过 volatile array 一次发布' : '发布前不会被普通读线程取得' }}</p>
          </section>
        </div>

        <div class="cow-flow__result" :class="{ 'is-visible': currentIndex >= 5 }">
          <span>旧迭代器：A → B → C</span>
          <span>新读取：A → B → C → D</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.cow-flow {
  display: grid;
  gap: 20px;
  min-height: 250px;
}

.cow-flow__pointers {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.cow-flow__pointer,
.cow-flow__lock {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  padding: 9px 11px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  color: var(--vp-c-text-2);
  font-size: 0.75rem;
  transition: border-color 180ms ease, background 180ms ease;
}

.cow-flow__pointer strong,
.cow-flow__lock strong {
  overflow-wrap: anywhere;
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.cow-flow__pointer.is-active,
.cow-flow__pointer.is-new {
  border-left-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.cow-flow__lock.is-locked {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 10%, var(--vp-c-bg));
}

.cow-flow__arrays {
  display: grid;
  grid-template-columns: minmax(190px, 1fr) 70px minmax(220px, 1.15fr);
  gap: 12px;
  align-items: center;
}

.cow-array {
  display: grid;
  gap: 11px;
  padding: 13px;
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  transition: border-color 220ms ease, opacity 220ms ease, transform 220ms ease;
}

.cow-array header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  color: var(--vp-c-text-2);
  font-size: 0.72rem;
}

.cow-array header strong {
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
}

.cow-array p {
  margin: 0;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.cow-array--old.is-reader,
.cow-array--new.is-published {
  border-color: var(--vp-c-brand-1);
}

.cow-array--new {
  opacity: 0.28;
  transform: translateX(10px);
}

.cow-array--new.is-visible {
  opacity: 1;
  transform: translateX(0);
}

.cow-array__cells {
  display: grid;
  grid-template-columns: repeat(4, minmax(38px, 1fr));
  gap: 6px;
}

.cow-array--old .cow-array__cells {
  grid-template-columns: repeat(3, minmax(38px, 1fr));
}

.cow-array__cells > span {
  display: grid;
  place-items: center;
  min-width: 0;
  height: 50px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.cow-array__pending {
  display: grid;
  place-items: center;
  min-height: 50px;
  border: 1px dashed var(--atlas-line);
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.cow-array__cells b {
  font-family: var(--vp-font-family-mono);
  font-size: 0.82rem;
}

.cow-array__cells small {
  color: var(--vp-c-text-3);
  font-size: 0.62rem;
}

.cow-array__cells .is-empty {
  color: var(--vp-c-text-3);
  border-style: dashed;
}

.cow-array__cells .is-added {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 10%, var(--vp-c-bg));
  color: var(--atlas-coral);
}

.cow-flow__copy {
  display: grid;
  place-items: center;
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
  opacity: 0.2;
  transition: opacity 180ms ease;
}

.cow-flow__copy strong {
  font-size: 1.4rem;
}

.cow-flow__copy.is-visible {
  color: var(--vp-c-brand-1);
  opacity: 1;
}

.cow-flow__result {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 18px;
  padding: 9px 12px;
  background: var(--atlas-surface);
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  opacity: 0.28;
}

.cow-flow__result.is-visible {
  opacity: 1;
}

@media (max-width: 700px) {
  .cow-flow__pointers {
    grid-template-columns: 1fr;
  }

  .cow-flow__arrays {
    grid-template-columns: 1fr;
  }

  .cow-flow__copy {
    min-height: 30px;
  }

  .cow-flow__copy strong {
    transform: rotate(90deg);
  }

  .cow-array--new {
    transform: translateY(8px);
  }

  .cow-array--new.is-visible {
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .cow-flow__pointer,
  .cow-flow__lock,
  .cow-array,
  .cow-flow__copy {
    transition: none;
  }
}
</style>
