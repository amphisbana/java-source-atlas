<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

interface ConcurrentSnapshot {
  threads: Array<{ name: string; state: string; tone: string }>
  bucket: string[]
  nextBucket: string[]
  triggerBucket: string
  sizeCtl: string
  transferIndex: string
  claimedRange: string
  route: string
}

const steps: SourceAnimationStep[] = [
  {
    title: 'T1 定位到空桶',
    method: 'tabAt(tab, i) == null',
    description: 'T1 写入 A，读取目标槽位为 null，因此不需要获取桶锁。'
  },
  {
    title: 'CAS 安装首节点',
    method: 'casTabAt(tab, i, null, new Node(A))',
    description: 'CAS 成功后 A 成为桶首；如果失败，外层循环会重新读取最新桶状态。'
  },
  {
    title: 'T2 锁内复查并追加',
    method: 'synchronized (f); tabAt(tab, i) == f',
    description: 'B 落入同一桶。T2 锁住桶首 A，获得锁后复查 table[3] 仍指向 A，再把 B 追加到链表。'
  },
  {
    title: '初始化扩容任务',
    method: 'nextTable = new Node[n << 1]; transferIndex = n',
    description: '旧表容量为 32。发起线程创建 64 槽的新表，并把待领取边界设置为 32；此时任何旧桶都还没有变成 MOVED。'
  },
  {
    title: 'T1 先领取高半区',
    method: 'CAS transferIndex: 32 -> 16',
    description: 'T1 领取 16..31，并从 31 向 16 扫描。table[3] 不在该区间内，因此仍必须保持 A → B，不能提前显示 MOVED。'
  },
  {
    title: 'T3 在高区看到路标',
    method: 'table[19].hash == MOVED',
    description: 'T1 已迁移高区的 table[19] 并放入 ForwardingNode。写入 C 的 T3 命中这个桶，得到它指向的同一张 nextTable。'
  },
  {
    title: 'helpTransfer 校验并登记协作者',
    method: 'CAS sizeCtl: sc -> sc + 1',
    description: 'T3 只有在 table、nextTable、扩容戳都仍匹配且还有区间时才登记为协作者，然后进入 transfer(tab, nextTab)。'
  },
  {
    title: 'T3 领取低半区',
    method: 'CAS transferIndex: 16 -> 0',
    description: 'T3 成功领取 0..15，并从 15 向 0 扫描。领取只是获得任务，table[3] 要等扫描真正到达它之后才会改变。'
  },
  {
    title: '低区迁移到 table[3]',
    method: 'setTabAt(nextTab, 3, lo); setTabAt(tab, 3, fwd)',
    description: 'T3 先把 A、B 安装到新表，再把旧 table[3] 替换为 ForwardingNode；从这一刻起，读会经 find 转发，写会经 helpTransfer 切表。'
  },
  {
    title: '末位迁移线程发布新表',
    method: 'nextTable = null; table = nextTab; sizeCtl = 48',
    description: '所有区间完成并经过 finishing 复扫后，最后退出者发布容量 64 的新表和阈值 48；T3 的 C 按新容量重新定位。'
  }
]

const snapshots: ConcurrentSnapshot[] = [
  {
    threads: [{ name: 'T1', state: '读取 table[3]', tone: 'active' }, { name: 'T2', state: '未开始', tone: '' }, { name: 'T3', state: '未开始', tone: '' }],
    bucket: [], nextBucket: [], triggerBucket: 'Q', sizeCtl: '24', transferIndex: '-', claimedRange: '-', route: '当前表'
  },
  {
    threads: [{ name: 'T1', state: 'CAS 成功', tone: 'success' }, { name: 'T2', state: '未开始', tone: '' }, { name: 'T3', state: '未开始', tone: '' }],
    bucket: ['A'], nextBucket: [], triggerBucket: 'Q', sizeCtl: '24', transferIndex: '-', claimedRange: '-', route: '当前表'
  },
  {
    threads: [{ name: 'T1', state: '完成', tone: '' }, { name: 'T2', state: '复查后追加 B', tone: 'success' }, { name: 'T3', state: '未开始', tone: '' }],
    bucket: ['A', 'B'], nextBucket: [], triggerBucket: 'Q', sizeCtl: '24', transferIndex: '-', claimedRange: '-', route: '当前表'
  },
  {
    threads: [{ name: 'T1', state: '创建 nextTable', tone: 'active' }, { name: 'T2', state: '完成', tone: '' }, { name: 'T3', state: '准备写入 C', tone: '' }],
    bucket: ['A', 'B'], nextBucket: [], triggerBucket: 'Q', sizeCtl: '扩容戳 + 2', transferIndex: '32', claimedRange: '尚未领取', route: 'nextTable 已创建'
  },
  {
    threads: [{ name: 'T1', state: '迁移 31 → 16', tone: 'active' }, { name: 'T2', state: '完成', tone: '' }, { name: 'T3', state: '准备写入 C', tone: '' }],
    bucket: ['A', 'B'], nextBucket: [], triggerBucket: 'Q（处理中）', sizeCtl: '扩容中（T1）', transferIndex: '16', claimedRange: 'T1 [16, 31]', route: 'table[3] 尚未迁移'
  },
  {
    threads: [{ name: 'T1', state: '高区接近完成', tone: 'active' }, { name: 'T2', state: '完成', tone: '' }, { name: 'T3', state: 'table[19] 命中 MOVED', tone: 'active' }],
    bucket: ['A', 'B'], nextBucket: [], triggerBucket: 'MOVED → nextTable', sizeCtl: '扩容中（T1）', transferIndex: '16', claimedRange: 'T1 [16, 31]', route: 'table[19] 转发'
  },
  {
    threads: [{ name: 'T1', state: '高区收尾', tone: 'active' }, { name: 'T2', state: '完成', tone: '' }, { name: 'T3', state: 'helpTransfer 登记成功', tone: 'success' }],
    bucket: ['A', 'B'], nextBucket: [], triggerBucket: 'MOVED → nextTable', sizeCtl: '扩容中（T1 + T3）', transferIndex: '16', claimedRange: '低区仍待领取', route: '校验同一轮扩容'
  },
  {
    threads: [{ name: 'T1', state: '高区完成', tone: 'success' }, { name: 'T2', state: '完成', tone: '' }, { name: 'T3', state: '迁移 15 → 0', tone: 'active' }],
    bucket: ['A', 'B'], nextBucket: [], triggerBucket: 'MOVED → nextTable', sizeCtl: '扩容中（T1 + T3）', transferIndex: '0', claimedRange: 'T3 [0, 15]', route: 'table[3] 尚待扫描'
  },
  {
    threads: [{ name: 'T1', state: '退出迁移', tone: '' }, { name: 'T2', state: '完成', tone: '' }, { name: 'T3', state: '迁移 table[3]', tone: 'active' }],
    bucket: ['MOVED'], nextBucket: ['A', 'B'], triggerBucket: 'MOVED → nextTable', sizeCtl: '扩容中（T3）', transferIndex: '0', claimedRange: 'T3 [0, 15]', route: 'ForwardingNode →'
  },
  {
    threads: [{ name: 'T1', state: '完成', tone: '' }, { name: 'T2', state: '完成', tone: '' }, { name: 'T3', state: 'C 在新表重新定位', tone: 'success' }],
    bucket: ['旧表退出'], nextBucket: ['A', 'B'], triggerBucket: '旧 table[19] 退出', sizeCtl: '新阈值 48', transferIndex: '0', claimedRange: '全部完成', route: 'table = nextTable'
  }
]
</script>

<template>
  <SourceAnimation title="CAS、桶锁与协作扩容如何接力" :steps="steps" :interval="2200">
    <template #visual="{ currentIndex }">
      <div class="chm-flow">
        <div class="chm-flow__threads" aria-label="参与线程状态">
          <div
            v-for="thread in snapshots[currentIndex].threads"
            :key="thread.name"
            class="chm-flow__thread"
            :class="[`is-${thread.tone}`]"
          >
            <strong>{{ thread.name }}</strong>
            <span>{{ thread.state }}</span>
          </div>
        </div>

        <div class="chm-flow__maps">
          <div class="chm-flow__table">
            <span>旧 table[3]</span>
            <div class="chm-flow__bucket" :class="{ 'is-forwarding': snapshots[currentIndex].bucket.includes('MOVED') }">
              <template v-if="snapshots[currentIndex].bucket.length">
                <template v-for="(node, index) in snapshots[currentIndex].bucket" :key="node">
                  <strong>{{ node }}</strong>
                  <i v-if="index < snapshots[currentIndex].bucket.length - 1">→</i>
                </template>
              </template>
              <em v-else>null</em>
            </div>
          </div>

          <div class="chm-flow__route" :class="{ 'is-active': currentIndex >= 3 }">
            <span>{{ snapshots[currentIndex].route }}</span>
          </div>

          <div class="chm-flow__table" :class="{ 'is-dormant': currentIndex < 4 }">
            <span>nextTable[3]（A、B 的扩容位为 0）</span>
            <div class="chm-flow__bucket">
              <template v-if="snapshots[currentIndex].nextBucket.length">
                <template v-for="(node, index) in snapshots[currentIndex].nextBucket" :key="node">
                  <strong class="is-arriving">{{ node }}</strong>
                  <i v-if="index < snapshots[currentIndex].nextBucket.length - 1">→</i>
                </template>
              </template>
              <em v-else>尚未创建</em>
            </div>
          </div>
        </div>

        <div class="chm-flow__trigger" :class="{ 'is-forwarding': snapshots[currentIndex].triggerBucket.includes('MOVED') }">
          <span>协助触发桶 old table[19]</span>
          <strong>{{ snapshots[currentIndex].triggerBucket }}</strong>
        </div>

        <div class="chm-flow__state">
          <span>sizeCtl <strong>{{ snapshots[currentIndex].sizeCtl }}</strong></span>
          <span>transferIndex <strong>{{ snapshots[currentIndex].transferIndex }}</strong></span>
          <span>已领取区间 <strong>{{ snapshots[currentIndex].claimedRange }}</strong></span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.chm-flow {
  display: grid;
  gap: 20px;
  min-height: 240px;
}

.chm-flow__threads {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.chm-flow__thread {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 9px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, transform 180ms ease;
}

.chm-flow__thread strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
}

.chm-flow__thread span {
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.chm-flow__thread.is-active {
  border-left-color: var(--atlas-coral);
  transform: translateY(-2px);
}

.chm-flow__thread.is-success {
  border-left-color: var(--vp-c-brand-1);
}

.chm-flow__maps {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 110px minmax(180px, 1fr);
  gap: 12px;
  align-items: center;
}

.chm-flow__table {
  display: grid;
  gap: 7px;
  transition: opacity 220ms ease;
}

.chm-flow__table > span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.chm-flow__table.is-dormant {
  opacity: 0.35;
}

.chm-flow__bucket {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 58px;
  padding: 10px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.chm-flow__bucket strong {
  display: grid;
  place-items: center;
  min-width: 40px;
  height: 32px;
  border: 1px solid var(--vp-c-brand-1);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.chm-flow__bucket.is-forwarding strong {
  min-width: 74px;
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
  animation: forwarding-pulse 900ms ease-in-out infinite alternate;
}

.chm-flow__bucket strong.is-arriving {
  animation: chm-arrive 380ms ease-out both;
}

.chm-flow__bucket i,
.chm-flow__bucket em {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-style: normal;
}

.chm-flow__route {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  text-align: center;
  opacity: 0.35;
  transition: opacity 180ms ease, color 180ms ease;
}

.chm-flow__route.is-active {
  color: var(--atlas-coral);
  opacity: 1;
}

.chm-flow__trigger {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.chm-flow__trigger strong {
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  text-align: right;
}

.chm-flow__trigger.is-forwarding {
  border-left-color: var(--atlas-coral);
}

.chm-flow__state {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--atlas-line);
}

.chm-flow__state span {
  min-width: 0;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  text-align: center;
}

.chm-flow__state strong {
  display: block;
  margin-top: 4px;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

@keyframes chm-arrive {
  from { opacity: 0; transform: translateX(-15px); }
  to { opacity: 1; transform: translateX(0); }
}

@keyframes forwarding-pulse {
  from { box-shadow: 0 0 0 0 color-mix(in srgb, var(--atlas-coral) 15%, transparent); }
  to { box-shadow: 0 0 0 5px color-mix(in srgb, var(--atlas-coral) 18%, transparent); }
}

@media (max-width: 720px) {
  :deep(.source-animation__track) {
    grid-template-columns: repeat(10, minmax(0, 1fr));
  }

  :deep(.source-animation__track-step) {
    min-width: 0;
    height: 26px;
  }

  :deep(.source-animation__track-step span) {
    width: 20px;
    height: 20px;
    font-size: 0.62rem;
  }

  :deep(.source-animation__track-step.is-active span) {
    box-shadow: 0 0 0 2px var(--vp-c-brand-soft);
  }

  .chm-flow__threads,
  .chm-flow__state {
    grid-template-columns: 1fr;
  }

  .chm-flow__maps {
    grid-template-columns: 1fr;
  }

  .chm-flow__route {
    text-align: left;
  }

  .chm-flow__trigger {
    align-items: flex-start;
    flex-direction: column;
  }

  .chm-flow__trigger strong {
    text-align: left;
  }

  .chm-flow__state span {
    text-align: left;
  }
}
</style>
