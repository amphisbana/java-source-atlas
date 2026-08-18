<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type GuaranteeTone = 'race' | 'release' | 'acquire' | 'atomicity' | 'final' | 'version'

interface JmmSnapshot {
  scene: string
  writer: string
  reader: string
  shared: string
  edge: string
  observed: string
  guarantee: string
  tone: GuaranteeTone
}

// 每一帧表达 JMM 允许或保证的关系，不把处理器缓存画成 Java 规范中的真实部件。
const steps: SourceAnimationStep[] = [
  {
    title: '普通字段之间没有发布边',
    method: 'payload = 42; ready = true',
    description: '写线程内存在程序顺序，但普通 ready 写与另一线程的普通 ready 读之间没有 synchronizes-with；跨线程尚未形成 happens-before。'
  },
  {
    title: '读到标志也不保证 payload',
    method: 'if (ready) use(payload)',
    description: '这是数据竞争程序的一种允许结果示意，不代表每次都会读到旧值。不能用“本机一直正确”证明普通标志具备发布语义。'
  },
  {
    title: 'volatile 写执行 release 发布',
    method: 'payload = 42; volatile ready = true',
    description: '写线程中 ready 之前的普通写，通过程序顺序到达 volatile 写；该写成为待匹配的发布端。'
  },
  {
    title: 'volatile 读执行 acquire 获取',
    method: 'while (!ready) {}; return payload',
    description: '读到该 volatile 写产生的 true 后形成 synchronizes-with，再经传递性保证后续普通 payload 读取看见发布前的 42。'
  },
  {
    title: '两个线程读取同一 volatile 旧值',
    method: 'snapshot = counter',
    description: 'volatile 保证每次读取本身的可见性，但 counter++ 会拆成读、计算、写。示例强制两个线程都先读到 0。'
  },
  {
    title: '两次写回覆盖成一次结果',
    method: 'counter = snapshot + 1',
    description: '两个 volatile 写各自合法，最终值却只有 1。需要 CAS、AtomicInteger 或锁来保证整个 read-modify-write 的原子性。'
  },
  {
    title: 'start 前动作发布给新线程',
    method: 'configuration = 7; worker.start()',
    description: '调用 start 之前的所有动作 happens-before 被启动线程中的所有动作，不需要把只在启动前写一次的配置逐个声明为 volatile。'
  },
  {
    title: '工作线程读取启动前配置',
    method: 'result = configuration * 6',
    description: 'start 边经传递性覆盖工作线程里的普通读取。规则保证可见性，但不保证新线程何时获得 CPU。'
  },
  {
    title: '工作线程先写普通结果',
    method: 'result = 42; run() returns',
    description: '线程终止前的全部动作将通过终止检测边界发布；此时主线程若尚未成功 join，仍不能仅凭时间推测结果可见。'
  },
  {
    title: 'join 返回后结果可见',
    method: 'worker.join(); read(result)',
    description: '目标线程中的全部动作 happens-before 另一个线程检测到它已终止；成功 join 返回后读取普通 result 必须得到已发布值。'
  },
  {
    title: '构造器写入 final 状态',
    method: 'this.name = name; this.value = value',
    description: '对象正常构造完成时，JMM 对 final 字段建立冻结语义；前提是构造期间没有把 this 泄漏给其他线程。'
  },
  {
    title: '安全发布不可变快照',
    method: 'snapshot = new Snapshot(...); start()',
    description: 'final 语义与 start、volatile、锁等正式发布边配合后，读线程可稳定看到构造完成的不可变状态。final 不会让引用指向的可变对象自动线程安全。'
  },
  {
    title: '构造期 this 逃逸破坏前提',
    method: 'GLOBAL = this; this.value = 42',
    description: '对象在 final 写入或构造完成前被其他线程取得，读取方可能观察到部分初始化状态；final 不是修复不安全构造发布的兜底机制。'
  },
  {
    title: 'JDK 8 类库依赖 Unsafe',
    method: 'Unsafe.compareAndSwapInt / putOrderedObject',
    description: 'JDK 8 并发类通过内部 Unsafe 访问 CAS、volatile 与 ordered 写。它是当前实现入口，不是普通应用应依赖的可移植公共契约。'
  },
  {
    title: 'JDK 9+ 用 VarHandle 表达模式',
    method: 'getAcquire / setRelease / compareAndSet',
    description: 'VarHandle 把 plain、opaque、acquire/release、volatile 与原子更新公开为分层 API，让调用点直接表达所需内存顺序。'
  },
  {
    title: '按不变量选择最小正确语义',
    method: 'volatile / CAS / lock / VarHandle',
    description: '先确定状态不变量和线性化点，再选择同步工具。更弱的模式必须有完整证明；VarHandle 只提供访问语义，不会替代并发算法。'
  }
]

const snapshots: JmmSnapshot[] = [
  { scene: '普通数据竞争', writer: '写 payload=42，再写 ready=true', reader: '普通读取 ready 与 payload', shared: 'payload / ready 都是普通字段', edge: '仅有线程内程序顺序', observed: '可能读到 ready=true、payload=0', guarantee: '跨线程结果不保证', tone: 'race' },
  { scene: '普通数据竞争', writer: '写入已执行', reader: '观察到标志后读取数据', shared: '冲突访问之间无 HB', edge: 'writer ⇢ reader：缺失', observed: '旧值、新值等合法结果取决于执行', guarantee: '不能据此编写正确协议', tone: 'race' },
  { scene: 'volatile 发布', writer: '普通写 payload=42', reader: '尚未读取 ready', shared: 'ready 是 volatile', edge: 'payload 写 → volatile 写', observed: '发布端已就绪', guarantee: 'release 约束发布前动作', tone: 'release' },
  { scene: 'volatile 获取', writer: 'volatile 写 ready=true', reader: 'volatile 读到 true，再读 payload', shared: '同一个 volatile 变量', edge: 'write ready → read ready → read payload', observed: 'payload 必须是 42', guarantee: '建立完整 happens-before', tone: 'acquire' },
  { scene: '复合更新', writer: 'T1 读取 counter=0', reader: 'T2 也读取 counter=0', shared: 'volatile counter=0', edge: '每次读独立可见', observed: '两个 snapshot 都为 0', guarantee: '尚未提交原子更新', tone: 'atomicity' },
  { scene: '丢失更新', writer: 'T1 写 counter=1', reader: 'T2 随后也写 counter=1', shared: 'volatile counter=1', edge: '两次写进入同步顺序', observed: '执行两次 ++，最终只有 1', guarantee: '可见性不等于复合原子性', tone: 'atomicity' },
  { scene: 'Thread.start', writer: 'main 写 configuration=7', reader: 'worker 尚未运行', shared: '普通 configuration', edge: 'main 动作 → start → worker 动作', observed: '启动前配置被发布', guarantee: 'JLS start 规则', tone: 'release' },
  { scene: 'Thread.start', writer: 'main 已从 start 返回', reader: 'worker 读取 7 并计算', shared: 'result 暂为 0', edge: 'start 边已传递到读取', observed: '计算得到 42', guarantee: '不保证调度时刻', tone: 'acquire' },
  { scene: 'Thread 终止', writer: 'worker 写 result=42', reader: 'main 正在 join', shared: '普通 result=42', edge: 'worker 动作 → termination', observed: 'main 尚未越过终止边', guarantee: '等待终止检测', tone: 'release' },
  { scene: 'Thread.join', writer: 'worker 已 TERMINATED', reader: 'main 从 join 返回并读取', shared: '普通 result=42', edge: 'termination → join return → read', observed: 'main 必须读到 42', guarantee: 'JLS 终止检测规则', tone: 'acquire' },
  { scene: 'final freeze', writer: '构造器写 name 与 value', reader: '尚未取得对象引用', shared: 'final name / final value', edge: 'final 写 → 构造完成', observed: '不可变状态已冻结', guarantee: '要求 this 不逃逸', tone: 'final' },
  { scene: '不可变对象发布', writer: '构造完成后通过 start 发布', reader: '读取 snapshot.name/value', shared: 'final 状态 + 正式发布边', edge: 'freeze + start → reader', observed: 'atlas / 42', guarantee: '构造与发布前提都成立', tone: 'final' },
  { scene: '错误的 this 逃逸', writer: '先 GLOBAL=this，后写 value', reader: '可能提前取得 GLOBAL', shared: '部分初始化对象', edge: '读取发生在 freeze 之前', observed: '可能观察默认值', guarantee: '违反 final 语义前提', tone: 'race' },
  { scene: 'JDK 8 实现', writer: 'Unsafe ordered / volatile 写', reader: 'Unsafe volatile 读 / CAS', shared: '偏移量 + 内部 native 入口', edge: '由具体调用语义决定', observed: '并发类实现正确发布', guarantee: '内部 API，版本化实现', tone: 'version' },
  { scene: 'JDK 9+ API', writer: 'setRelease(value)', reader: 'getAcquire()', shared: 'VarHandle 坐标变量', edge: 'release → matching acquire', observed: '公开表达所需顺序', guarantee: '模式强度可被审查', tone: 'version' },
  { scene: '同步策略选择', writer: '发布、原子更新或互斥', reader: '按同一协议读取', shared: '先定义共享状态不变量', edge: '画出 HB 与线性化点', observed: '选择足够且可证明的工具', guarantee: '算法正确性优先', tone: 'version' }
]
</script>

<template>
  <SourceAnimation title="从数据竞争到发布、原子性与 VarHandle" :steps="steps" :interval="2600">
    <template #visual="{ currentIndex }">
      <div class="jmm-flow" :class="`is-${snapshots[currentIndex].tone}`">
        <div class="jmm-flow__headline">
          <div>
            <span>当前场景</span>
            <strong>{{ snapshots[currentIndex].scene }}</strong>
          </div>
          <code>{{ snapshots[currentIndex].guarantee }}</code>
        </div>

        <div class="jmm-flow__threads" aria-label="两个线程的动作">
          <section>
            <span>写入方 / main</span>
            <strong>{{ snapshots[currentIndex].writer }}</strong>
          </section>
          <div class="jmm-flow__edge" aria-label="happens-before 关系">
            <span>happens-before</span>
            <i aria-hidden="true">→</i>
            <code>{{ snapshots[currentIndex].edge }}</code>
          </div>
          <section>
            <span>读取方 / worker</span>
            <strong>{{ snapshots[currentIndex].reader }}</strong>
          </section>
        </div>

        <div class="jmm-flow__state">
          <div>
            <span>共享状态</span>
            <strong>{{ snapshots[currentIndex].shared }}</strong>
          </div>
          <div>
            <span>这一帧能得出的结论</span>
            <strong>{{ snapshots[currentIndex].observed }}</strong>
          </div>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.jmm-flow {
  display: grid;
  min-width: 0;
  min-height: 310px;
  gap: 18px;
  align-content: center;
}

.jmm-flow__headline,
.jmm-flow__state {
  display: grid;
  min-width: 0;
  gap: 12px;
}

.jmm-flow__headline {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.jmm-flow__headline > div,
.jmm-flow__state > div {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.jmm-flow span {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-weight: 700;
}

.jmm-flow strong,
.jmm-flow code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.jmm-flow__headline code {
  padding: 7px 10px;
  border-radius: 4px;
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
  text-align: center;
}

.jmm-flow__threads {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(145px, 0.8fr) minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
}

.jmm-flow__threads section,
.jmm-flow__state > div {
  display: grid;
  min-width: 0;
  min-height: 86px;
  gap: 8px;
  align-content: center;
  padding: 14px;
  border-block: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jmm-flow__threads section:first-child { border-left: 4px solid var(--atlas-coral); }
.jmm-flow__threads section:last-child { border-left: 4px solid var(--vp-c-brand-1); }

.jmm-flow__edge {
  display: grid;
  min-width: 0;
  gap: 5px;
  place-content: center;
  text-align: center;
}

.jmm-flow__edge i {
  color: var(--vp-c-brand-1);
  font-size: 1.35rem;
  font-style: normal;
}

.jmm-flow.is-race .jmm-flow__edge i,
.jmm-flow.is-race .jmm-flow__headline code {
  color: var(--atlas-coral);
}

.jmm-flow.is-race .jmm-flow__headline code { background: color-mix(in srgb, var(--atlas-coral) 12%, transparent); }
.jmm-flow.is-atomicity .jmm-flow__headline code { color: var(--atlas-gold); }
.jmm-flow.is-final .jmm-flow__headline code { color: var(--atlas-purple); }

.jmm-flow__state {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (max-width: 700px) {
  .jmm-flow { min-height: 440px; }
  .jmm-flow__headline { grid-template-columns: 1fr; }
  .jmm-flow__headline code { justify-self: stretch; }
  .jmm-flow__threads { grid-template-columns: 1fr; }
  .jmm-flow__edge { grid-template-columns: auto auto; align-items: center; justify-content: start; text-align: left; }
  .jmm-flow__edge code { grid-column: 1 / -1; }
  .jmm-flow__edge i { transform: rotate(90deg); }
  .jmm-flow__state { grid-template-columns: 1fr; }
}

@media (max-width: 420px) {
  .jmm-flow { min-height: 510px; gap: 12px; }
  .jmm-flow__threads section,
  .jmm-flow__state > div { min-height: 74px; padding: 11px; }
}
</style>
