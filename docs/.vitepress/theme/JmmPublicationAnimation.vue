<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type ActionState = 'idle' | 'active' | 'complete'
type PublicationTone = 'plain' | 'release' | 'acquire' | 'visible'

interface PublicationSnapshot {
  phase: string
  payload: string
  ready: string
  readyMode: string
  actions: [ActionState, ActionState, ActionState, ActionState]
  bridge: string
  conclusion: string
  tone: PublicationTone
}

// 2026-08-27：用同一组共享字段贯穿全部步骤，先讲清发布链，再进入其他 JMM 规则。
const steps: SourceAnimationStep[] = [
  {
    title: '两个线程共享 payload 和 ready',
    method: 'payload = 0; ready = false',
    description: '先只关注一个问题：写线程把 payload 改成 42 后，读线程在什么条件下必须看到 42？'
  },
  {
    title: '写线程先更新普通数据',
    method: 'A1: payload = 42',
    description: 'A1 只是在写线程内完成了一次普通写。此时还没有任何动作把结果发布给读线程。'
  },
  {
    title: '普通 ready 不能完成发布',
    method: 'A2: ready = true // 普通字段',
    description: 'A1 发生在 A2 之前，但普通 ready 的写和另一线程的读之间没有跨线程同步边。即使读到 ready=true，也不能据此保证 payload=42。'
  },
  {
    title: '把 ready 改为 volatile 并重新发布',
    method: 'A2: volatile ready = true',
    description: 'volatile 写成为发布端。写线程中位于它之前的 payload=42，会跟随这次发布等待读取方获取。'
  },
  {
    title: '读线程读取同一个 volatile 字段',
    method: 'B1: read ready == true',
    description: 'B1 读到 A2 写入的 true，A2 到 B1 建立跨线程同步边。注意：必须读写同一个 volatile 字段。'
  },
  {
    title: '传递性保证 payload 可见',
    method: 'B2: read payload == 42',
    description: 'A1 → A2 → B1 → B2 连成完整 happens-before 链，所以 B2 必须看到 A1 写入的 42。'
  }
]

const snapshots: PublicationSnapshot[] = [
  {
    phase: '起点',
    payload: '0',
    ready: 'false',
    readyMode: '普通字段',
    actions: ['idle', 'idle', 'idle', 'idle'],
    bridge: '还没有跨线程关系',
    conclusion: '尚未发布，也尚未读取',
    tone: 'plain'
  },
  {
    phase: '普通写入',
    payload: '42',
    ready: 'false',
    readyMode: '普通字段',
    actions: ['active', 'idle', 'idle', 'idle'],
    bridge: '只有写线程内的程序顺序',
    conclusion: '写线程自己知道 payload=42',
    tone: 'plain'
  },
  {
    phase: '错误协议',
    payload: '42',
    ready: 'true',
    readyMode: '普通字段',
    actions: ['complete', 'active', 'idle', 'idle'],
    bridge: 'A2 ⇢ B1：缺少同步边',
    conclusion: '读线程看见什么，没有 JMM 保证',
    tone: 'plain'
  },
  {
    phase: '建立发布端',
    payload: '42',
    ready: 'true',
    readyMode: 'volatile',
    actions: ['complete', 'active', 'idle', 'idle'],
    bridge: 'A1 → A2：程序顺序',
    conclusion: 'A2 已准备把此前写入发布出去',
    tone: 'release'
  },
  {
    phase: '建立跨线程边',
    payload: '42',
    ready: 'true',
    readyMode: 'volatile',
    actions: ['complete', 'complete', 'active', 'idle'],
    bridge: 'A2 → B1：volatile 规则',
    conclusion: '读线程已经取得写线程发布的结果',
    tone: 'acquire'
  },
  {
    phase: '完成可见性链',
    payload: '42',
    ready: 'true',
    readyMode: 'volatile',
    actions: ['complete', 'complete', 'complete', 'active'],
    bridge: 'A1 → A2 → B1 → B2',
    conclusion: 'B2 必须读到 payload=42',
    tone: 'visible'
  }
]
</script>

<template>
  <SourceAnimation title="一条 volatile 发布链是怎样建立的" :steps="steps" :interval="3200">
    <template #visual="{ currentIndex }">
      <div class="jmm-publication" :class="`is-${snapshots[currentIndex].tone}`">
        <div class="jmm-publication__summary">
          <div>
            <span>当前阶段</span>
            <strong>{{ snapshots[currentIndex].phase }}</strong>
          </div>
          <code>{{ snapshots[currentIndex].bridge }}</code>
        </div>

        <div class="jmm-publication__lanes">
          <section class="jmm-publication__lane jmm-publication__lane--writer">
            <span>写线程 A</span>
            <div class="jmm-publication__action" :class="`is-${snapshots[currentIndex].actions[0]}`">
              <small>A1 · 普通写</small>
              <code>payload = 42</code>
            </div>
            <div class="jmm-publication__action" :class="`is-${snapshots[currentIndex].actions[1]}`">
              <small>A2 · 发布动作</small>
              <code>ready = true</code>
            </div>
          </section>

          <section class="jmm-publication__shared" aria-label="共享变量当前状态">
            <span>共享变量</span>
            <div>
              <small>payload</small>
              <strong>{{ snapshots[currentIndex].payload }}</strong>
              <em>普通字段</em>
            </div>
            <div>
              <small>ready</small>
              <strong>{{ snapshots[currentIndex].ready }}</strong>
              <em>{{ snapshots[currentIndex].readyMode }}</em>
            </div>
          </section>

          <section class="jmm-publication__lane jmm-publication__lane--reader">
            <span>读线程 B</span>
            <div class="jmm-publication__action" :class="`is-${snapshots[currentIndex].actions[2]}`">
              <small>B1 · 获取动作</small>
              <code>read ready</code>
            </div>
            <div class="jmm-publication__action" :class="`is-${snapshots[currentIndex].actions[3]}`">
              <small>B2 · 普通读</small>
              <code>read payload</code>
            </div>
          </section>
        </div>

        <div class="jmm-publication__conclusion">
          <span>现在能下的结论</span>
          <strong>{{ snapshots[currentIndex].conclusion }}</strong>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.jmm-publication {
  display: grid;
  min-width: 0;
  min-height: 350px;
  gap: 18px;
  align-content: center;
}

.jmm-publication__summary {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.jmm-publication__summary > div,
.jmm-publication__conclusion {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.jmm-publication span,
.jmm-publication small,
.jmm-publication em {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-style: normal;
  font-weight: 700;
}

.jmm-publication__summary code {
  max-width: 58%;
  padding: 7px 10px;
  border-radius: 4px;
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
  overflow-wrap: anywhere;
  text-align: center;
}

.jmm-publication__lanes {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) minmax(145px, 0.7fr) minmax(0, 1fr);
  gap: 12px;
}

.jmm-publication__lane,
.jmm-publication__shared {
  display: grid;
  min-width: 0;
  gap: 9px;
  align-content: start;
  padding: 13px;
  border-block: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jmm-publication__lane--writer {
  border-left: 4px solid var(--atlas-coral);
}

.jmm-publication__lane--reader {
  border-left: 4px solid var(--vp-c-brand-1);
}

.jmm-publication__shared {
  border: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
}

.jmm-publication__action,
.jmm-publication__shared > div {
  display: grid;
  min-width: 0;
  min-height: 58px;
  gap: 3px;
  align-content: center;
  padding: 8px 10px;
  border-left: 3px solid var(--atlas-line);
}

.jmm-publication__action code,
.jmm-publication__shared strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.jmm-publication__action.is-active {
  border-left-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}

.jmm-publication__action.is-complete {
  border-left-color: var(--vp-c-brand-2);
}

.jmm-publication__action.is-idle {
  opacity: 0.5;
}

.jmm-publication__shared > div {
  grid-template-columns: minmax(0, 1fr) auto;
  border-left-color: var(--atlas-gold);
}

.jmm-publication__shared > div small,
.jmm-publication__shared > div em {
  grid-column: 1;
}

.jmm-publication__shared > div em {
  white-space: nowrap;
}

.jmm-publication__shared > div strong {
  grid-row: 1 / 3;
  grid-column: 2;
  align-self: center;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 1.05rem;
}

.jmm-publication__conclusion {
  min-height: 58px;
  align-content: center;
  padding: 10px 14px;
  border-left: 4px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jmm-publication.is-plain .jmm-publication__summary code,
.jmm-publication.is-plain .jmm-publication__conclusion strong {
  color: var(--atlas-coral);
}

.jmm-publication.is-release .jmm-publication__conclusion,
.jmm-publication.is-acquire .jmm-publication__conclusion,
.jmm-publication.is-visible .jmm-publication__conclusion {
  border-left-color: var(--vp-c-brand-1);
}

.jmm-publication.is-visible .jmm-publication__conclusion {
  background: var(--vp-c-brand-soft);
}

@media (max-width: 700px) {
  .jmm-publication {
    min-height: 650px;
  }

  .jmm-publication__summary {
    align-items: stretch;
    flex-direction: column;
  }

  .jmm-publication__summary code {
    max-width: none;
  }

  .jmm-publication__lanes {
    grid-template-columns: 1fr;
  }
}
</style>
