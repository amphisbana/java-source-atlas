<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type ReferenceState = 'active' | 'pending' | 'enqueued' | 'inactive'

interface ReferenceSnapshot {
  scenario: string
  root: string
  key: string
  entry: string
  state: ReferenceState
  value: string
  queue: string
  handler: string
  table: string
  action: string
}

// 三段场景依次展示弱键清理、错误的 value 回指，以及 PhantomReference 清理通知。
const steps: SourceAnimationStep[] = [
  {
    title: 'WeakHashMap 写入弱键 Entry',
    method: 'put(key, value) → Entry extends WeakReference<K>',
    description: 'table 强引用 Entry，Entry 只弱引用 key，却普通强引用 value。外部变量仍然强引用 key，所以映射稳定可见。'
  },
  {
    title: '外部代码释放最后一条强路径',
    method: 'key = null',
    description: '赋 null 本身不会改表。它只让 key 可能进入弱可达状态；GC 是否立刻运行不是 Java API 的承诺。'
  },
  {
    title: 'GC 清 referent 并登记 pending',
    method: 'Active → Pending；referent = null',
    description: 'JDK 8 收集器处理已注册引用时清除 referent，并把 Entry 链入 pending。动画把同一次引用处理画成一帧，不把“已清除但仍 Active”伪装成稳定 JVM 状态。'
  },
  {
    title: 'Reference Handler 摘取 pending',
    method: 'pending → r；r.discovered = null',
    description: '高优先级守护线程在 Reference.lock 下从 pending 摘链，再离开锁处理 Cleaner 或 ReferenceQueue；此时 Entry 仍在 WeakHashMap 桶中。'
  },
  {
    title: 'Reference Handler 完成入队',
    method: 'ReferenceHandler → referenceQueue.enqueue(entry)',
    description: '高优先级守护线程从 pending 链取出引用并放进 WeakHashMap 的 ReferenceQueue，状态进入 Enqueued。'
  },
  {
    title: '普通 Map 操作触发惰性清理',
    method: 'size/get/put → getTable → expungeStaleEntries',
    description: 'WeakHashMap 没有自己的后台清理线程。下一次公开操作轮询队列，按 Entry 保存的 hash 定位桶并按对象身份摘链。'
  },
  {
    title: '摘除 Entry 后 value 才可回收',
    method: 'entry.value = null; table[index] = next',
    description: 'expunge 主动清空 value 并递减 size。弱 key 被 GC 清除与强 value 获得释放机会是两个不同时间点。'
  },
  {
    title: 'null key 由强哨兵替代',
    method: 'maskNull(null) → NULL_KEY',
    description: 'WeakHashMap 允许 null key，但内部用静态强引用哨兵代替；它不会像普通弱 key 那样因 GC 自动消失。'
  },
  {
    title: 'value 回指 key 会破坏弱键语义',
    method: 'map → entry → value → key',
    description: '若 value 强引用自己的 key，Map 经 value 建立回到 key 的强路径，key 不再弱可达，条目就不能自动清理。'
  },
  {
    title: 'ReferenceQueue 只传递清理通知',
    method: 'queue.remove(timeout) → cleared Reference',
    description: '消费者拿到的是 Reference 对象，不会从队列恢复 referent。资源标识必须放在 Reference 子类的独立字段中。'
  },
  {
    title: 'PhantomReference 从来不暴露对象',
    method: 'phantom.get() == null',
    description: '虚引用用于获知对象已进入不可复活的清理阶段，而不是重新取得对象。它必须配合 ReferenceQueue 才有实际用途。'
  },
  {
    title: '队列消费者执行幂等清理',
    method: 'poll/remove → cleanup(resourceId) → clear metadata',
    description: '清理动作只使用 Reference 自己保存的资源信息，并保证可重复执行。较新 JDK 更推荐 Cleaner 或显式 close 管理本地资源。'
  }
]

const snapshots: ReferenceSnapshot[] = [
  { scenario: '普通弱键', root: '局部变量 → key', key: 'Key#42（强可达）', entry: 'Entry.get() = Key#42', state: 'active', value: 'Value#42（Entry 强引用）', queue: '空', handler: '等待', table: 'bucket[3] → Entry，size=1', action: '映射稳定可读' },
  { scenario: '普通弱键', root: 'key = null', key: 'Key#42（仅剩弱路径）', entry: 'Entry.get() = Key#42', state: 'active', value: 'Value#42（仍被 Entry 保留）', queue: '空', handler: '等待', table: 'bucket[3] → Entry，size=1', action: '尚未发生 GC' },
  { scenario: '普通弱键', root: '无强路径', key: 'Key#42（已清除）', entry: 'discovered → pending', state: 'pending', value: 'Value#42（仍被 Entry 保留）', queue: '空', handler: '等待唤醒', table: 'bucket[3] → stale Entry', action: 'GC 清除并登记 pending' },
  { scenario: '普通弱键', root: '无强路径', key: 'referent = null', entry: '从 pending 摘链', state: 'pending', value: 'Value#42（仍存活）', queue: '空', handler: '持有 Entry#42', table: 'bucket[3] → stale Entry', action: 'Handler 接管' },
  { scenario: '普通弱键', root: '无强路径', key: 'referent = null', entry: 'queue.next 已链接', state: 'enqueued', value: 'Value#42（仍存活）', queue: 'Entry#42', handler: 'enqueue 完成', table: 'bucket[3] → stale Entry', action: '等待 Map 操作' },
  { scenario: '普通弱键', root: '调用 map.size()', key: 'referent = null', entry: '按 hash + identity 摘链', state: 'inactive', value: 'entry.value = null', queue: '已 poll', handler: '等待', table: 'bucket[3] → null，size=0', action: 'expungeStaleEntries' },
  { scenario: '普通弱键', root: '无 Map 强路径', key: '已回收', entry: '可回收', state: 'inactive', value: '可回收', queue: '空', handler: '等待', table: '空表，size=0', action: '弱键生命周期完成' },
  { scenario: 'null key', root: 'WeakHashMap.NULL_KEY', key: 'null（API 视图）', entry: 'Entry.get() = NULL_KEY', state: 'active', value: 'null-value', queue: '空', handler: '等待', table: 'bucket → Entry，size=1', action: '不会因 GC 自动消失' },
  { scenario: 'value 回指 key', root: 'map → Entry → Value', key: 'Value.owner = Key#7', entry: 'Entry 弱引用 Key#7', state: 'active', value: '强引用 Key#7', queue: '空', handler: '等待', table: 'bucket → Entry，size=1', action: '形成 Map 到 key 的强路径' },
  { scenario: '显式队列消费', root: '消费者线程', key: 'referent 已清除', entry: 'WeakReference<Resource>', state: 'enqueued', value: 'metadata: resourceId=9', queue: 'remove() 返回 Reference', handler: '已入队', table: '不涉及 Map', action: '不能重新取得 referent' },
  { scenario: '虚引用清理', root: '强持有 PhantomReference', key: 'Resource referent', entry: 'phantom.get() = null', state: 'active', value: 'metadata: nativeHandle=18', queue: '空', handler: '等待 GC 判定', table: '不涉及 Map', action: '只观察清理阶段' },
  { scenario: '虚引用清理', root: '清理工作线程', key: 'referent 不可恢复', entry: 'PhantomReference', state: 'inactive', value: 'metadata 已清空', queue: '已消费', handler: '等待', table: '不涉及 Map', action: 'close(handle) 幂等完成' }
]
</script>

<template>
  <SourceAnimation title="Reference 处理与 WeakHashMap 惰性清理" :steps="steps" :interval="2700">
    <template #visual="{ currentIndex }">
      <div class="reference-flow">
        <div class="reference-flow__meta">
          <span>场景</span>
          <strong>{{ snapshots[currentIndex].scenario }}</strong>
          <code>{{ snapshots[currentIndex].action }}</code>
        </div>

        <div class="reference-flow__path" aria-label="对象可达路径">
          <section>
            <span>GC Root / owner</span>
            <strong>{{ snapshots[currentIndex].root }}</strong>
          </section>
          <i aria-hidden="true">→</i>
          <section>
            <span>key / referent</span>
            <strong>{{ snapshots[currentIndex].key }}</strong>
          </section>
          <i aria-hidden="true">←</i>
          <section :class="`is-${snapshots[currentIndex].state}`">
            <span>Reference / Entry</span>
            <strong>{{ snapshots[currentIndex].entry }}</strong>
            <small>{{ snapshots[currentIndex].state }}</small>
          </section>
        </div>

        <div class="reference-flow__runtime">
          <section>
            <span>强 value / metadata</span>
            <strong>{{ snapshots[currentIndex].value }}</strong>
          </section>
          <section>
            <span>ReferenceQueue</span>
            <strong>{{ snapshots[currentIndex].queue }}</strong>
          </section>
          <section>
            <span>Reference Handler</span>
            <strong>{{ snapshots[currentIndex].handler }}</strong>
          </section>
        </div>

        <div class="reference-flow__table">
          <span>WeakHashMap table</span>
          <code>{{ snapshots[currentIndex].table }}</code>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.reference-flow {
  display: grid;
  gap: 16px;
  min-width: 0;
  min-height: 330px;
}

.reference-flow__meta,
.reference-flow__table {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
}

.reference-flow__meta span,
.reference-flow__path span,
.reference-flow__runtime span,
.reference-flow__table span {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-weight: 700;
}

.reference-flow__meta strong,
.reference-flow__meta code,
.reference-flow__table code,
.reference-flow__path strong,
.reference-flow__runtime strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.reference-flow__meta code {
  margin-left: auto;
  color: var(--vp-c-brand-1);
}

.reference-flow__path {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr) 24px minmax(0, 1.2fr);
  gap: 8px;
  align-items: center;
}

.reference-flow__path section,
.reference-flow__runtime section {
  display: grid;
  min-width: 0;
  min-height: 84px;
  gap: 8px;
  align-content: center;
  padding: 12px;
  border-block: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.reference-flow__path section:last-child {
  border-left: 4px solid var(--vp-c-brand-1);
}

.reference-flow__path section.is-pending { border-left-color: var(--atlas-coral); }
.reference-flow__path section.is-enqueued { border-left-color: var(--atlas-gold); }
.reference-flow__path section.is-inactive { border-left-color: var(--vp-c-text-3); opacity: 0.78; }

.reference-flow__path i {
  color: var(--vp-c-brand-1);
  font-style: normal;
  text-align: center;
}

.reference-flow__path small {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
}

.reference-flow__runtime {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.reference-flow__table {
  padding: 12px;
  border-block: 1px solid var(--atlas-line);
  background: var(--vp-c-bg-soft);
}

@media (max-width: 640px) {
  .reference-flow { min-height: 520px; }
  .reference-flow__meta code { width: 100%; margin-left: 0; }
  .reference-flow__path,
  .reference-flow__runtime { grid-template-columns: minmax(0, 1fr); }
  .reference-flow__path i { transform: rotate(90deg); }
  .reference-flow__path section,
  .reference-flow__runtime section { min-height: 58px; }
}

@media (prefers-reduced-motion: reduce) {
  .reference-flow__path section { transition: none; }
}
</style>
