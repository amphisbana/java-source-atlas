<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'

type Phase = 'buffer' | 'selector'
type Tone = 'idle' | 'active' | 'ready' | 'done' | 'warning'

interface TimelineStep {
  phase: Phase
  title: string
  method: string
  description: string
}

interface BufferSnapshot {
  action: string
  mode: string
  mark: number | null
  position: number
  limit: number
  capacity: number
  data: string[]
  focus: number[]
  result: string
  rule: string
}

interface ChannelSnapshot {
  role: string
  channel: string
  interest: string
  ready: string
  tone: Tone
}

interface SelectorSnapshot {
  event: string
  call: string
  keys: string[]
  selected: string[]
  cancelled: string[]
  channels: ChannelSnapshot[]
  discipline: string
}

const BUFFER_STEP_COUNT = 9
const PLAY_INTERVAL_MS = 2800

const steps: TimelineStep[] = [
  {
    phase: 'buffer',
    title: '分配八字节堆缓冲区',
    method: 'ByteBuffer.allocate(8)',
    description: '新 Buffer 的 position=0、limit=capacity=8、mark 未定义；整个 [0,8) 都是当前相对写入区间。'
  },
  {
    phase: 'buffer',
    title: '相对 put 推进 position',
    method: 'put(A).put(B).put(C).put(D)',
    description: '四次相对 put 各写入当前 position 后再加一；limit 和 capacity 不变，剩余可写区间变为 [4,8)。'
  },
  {
    phase: 'buffer',
    title: 'flip 把已写前缀变成可读区间',
    method: 'flip(): limit=position; position=0',
    description: 'flip 不移动也不清除字节，只把旧 position=4 设为新 limit，再把 position 归零。'
  },
  {
    phase: 'buffer',
    title: '读取 A 并保存回退点',
    method: 'get() → A; mark()',
    description: 'get 消费索引 0 后 position=1，mark 把 1 保存为 reset 的目标。'
  },
  {
    phase: 'buffer',
    title: '读取 B 后 reset 回到 mark',
    method: 'get() → B; reset(): position=mark',
    description: 'B 已经被观察，但 reset 让 position 从 2 回到 1，因此下一次相对 get 会再次得到 B。'
  },
  {
    phase: 'buffer',
    title: 'compact 保留 C、D',
    method: 'get() → B; compact()',
    description: 'compact 把未读 [2,4) 搬到 [0,2)，随后 position=2、limit=8，并丢弃 mark，准备在 D 后继续写。'
  },
  {
    phase: 'buffer',
    title: '追加 E 后再次 flip',
    method: 'put(E); flip()',
    description: 'E 写在 position=2；flip 将 C、D、E 组成新的可读区间 [0,3)。'
  },
  {
    phase: 'buffer',
    title: 'rewind 在原 limit 内重读',
    method: 'get() → C,D; rewind()',
    description: '读取两字节后 rewind 只把 position 归零，limit 仍为 3，所以可重新读取 C、D、E。'
  },
  {
    phase: 'buffer',
    title: 'clear 重新开放整段写入空间',
    method: 'clear(): position=0; limit=capacity',
    description: 'clear 只移动边界并丢弃 mark，旧的 C、D、E 仍可能留在存储中，但已不再被视为有效数据。'
  },
  {
    phase: 'selector',
    title: '默认 provider 创建 Selector',
    method: 'Selector.open() → SelectorProvider.provider().openSelector()',
    description: '新 Selector 的注册、已选和取消集合都为空；平台 provider 负责创建实际轮询器。'
  },
  {
    phase: 'selector',
    title: '非阻塞 Channel 注册兴趣位',
    method: 'configureBlocking(false) → register(selector, ops, attachment)',
    description: 'Server 注册 OP_ACCEPT；Client 若连接尚未完成则注册 OP_CONNECT。注册集合出现两个 key。'
  },
  {
    phase: 'selector',
    title: 'select 报告 ACCEPT 与 CONNECT',
    method: 'select(timeout) → selectedKeys += ready keys',
    description: 'readyOps 来自本轮就绪结果；selectedKeys 只是加入 key，不会替应用执行业务或自动删除。'
  },
  {
    phase: 'selector',
    title: '移除已选 key 并完成连接',
    method: 'iterator.remove(); accept(); finishConnect()',
    description: 'accept 得到 PEER 并注册 OP_READ；Client 完成连接后切换到 OP_WRITE，因为附件中确实有 PING 待发送。'
  },
  {
    phase: 'selector',
    title: 'Client 写完后移除 OP_WRITE',
    method: 'client.write(PING); interestOps(OP_READ)',
    description: '写操作推进输出 Buffer；没有剩余字节后立即取消 OP_WRITE，避免长期可写导致 Selector 空转。'
  },
  {
    phase: 'selector',
    title: 'Peer 读取请求并准备回声',
    method: 'peer.read(input); input.flip()',
    description: 'read 可能分多次完成；四字节到齐后才 flip，并把同样的字节挂为待发送输出，再启用 OP_WRITE。'
  },
  {
    phase: 'selector',
    title: 'Peer 写回后再次移除 OP_WRITE',
    method: 'peer.write(echo); interestOps(OP_READ)',
    description: '回声 Buffer 全部消费后，PEER 不再关注 WRITE，只保留下一条请求需要的 READ。'
  },
  {
    phase: 'selector',
    title: 'Client 读到完整 PING',
    method: 'client.read(response) → PING',
    description: 'Client 累计到预期长度才宣布完成；每个已选 key 均已通过迭代器移除，selectedKeys 回到空集合。'
  },
  {
    phase: 'selector',
    title: '跨线程命令先入队再 wakeup',
    method: 'controlQueue.add(command); selector.wakeup()',
    description: 'wakeup 让当前或下一次阻塞选择返回；它是合并的唤醒许可，不负责累计业务命令。'
  },
  {
    phase: 'selector',
    title: '取消在选择阶段完成注销',
    method: 'key.cancel(); wakeup(); selectNow(); close()',
    description: 'cancel 先进入取消集合，Selector 在选择前后处理注销；最终 Channel、key 集合和 Selector 都被清理。'
  }
]

const bufferSnapshots: BufferSnapshot[] = [
  {
    action: 'allocate(8)', mode: '准备写入', mark: null, position: 0, limit: 8, capacity: 8,
    data: ['', '', '', '', '', '', '', ''], focus: [], result: 'remaining=8',
    rule: '可写区间 [position, limit) = [0,8)'
  },
  {
    action: 'put A · B · C · D', mode: '写入完成一部分', mark: null, position: 4, limit: 8, capacity: 8,
    data: ['A', 'B', 'C', 'D', '', '', '', ''], focus: [0, 1, 2, 3], result: 'remaining=4',
    rule: '相对 put 成功后 position 前进'
  },
  {
    action: 'flip', mode: '准备读取', mark: null, position: 0, limit: 4, capacity: 8,
    data: ['A', 'B', 'C', 'D', '', '', '', ''], focus: [0, 1, 2, 3], result: 'readable=4',
    rule: '新 limit = 旧 position = 4'
  },
  {
    action: 'get A · mark', mode: '读取中', mark: 1, position: 1, limit: 4, capacity: 8,
    data: ['A', 'B', 'C', 'D', '', '', '', ''], focus: [0], result: '返回 A',
    rule: 'mark 保存当前 position=1'
  },
  {
    action: 'get B · reset', mode: '回退后可重读', mark: 1, position: 1, limit: 4, capacity: 8,
    data: ['A', 'B', 'C', 'D', '', '', '', ''], focus: [1], result: '返回 B，再回到索引 1',
    rule: 'reset 不修改数据和 limit'
  },
  {
    action: 'get B · compact', mode: '保留半包并继续写', mark: null, position: 2, limit: 8, capacity: 8,
    data: ['C', 'D', '', '', '', '', '', ''], focus: [0, 1], result: '保留 C · D',
    rule: 'remaining=2 搬到头部，position=2'
  },
  {
    action: 'put E · flip', mode: '组合数据可读', mark: null, position: 0, limit: 3, capacity: 8,
    data: ['C', 'D', 'E', '', '', '', '', ''], focus: [0, 1, 2], result: 'readable=3',
    rule: '旧半包与新字节组成 C · D · E'
  },
  {
    action: 'get C,D · rewind', mode: '从头重读', mark: null, position: 0, limit: 3, capacity: 8,
    data: ['C', 'D', 'E', '', '', '', '', ''], focus: [0, 1], result: '已读 C · D，现回到 0',
    rule: 'rewind 保持 limit=3'
  },
  {
    action: 'clear', mode: '整段重新可写', mark: null, position: 0, limit: 8, capacity: 8,
    data: ['C', 'D', 'E', '', '', '', '', ''], focus: [], result: 'remaining=8',
    rule: '边界已清空，旧字节没有被擦除'
  }
]

const selectorSnapshots: SelectorSnapshot[] = [
  {
    event: 'Provider 初始化', call: 'Selector.open()', keys: [], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: '尚未注册', interest: '-', ready: '-', tone: 'idle' },
      { role: 'CLIENT', channel: '尚未注册', interest: '-', ready: '-', tone: 'idle' },
      { role: 'PEER', channel: '尚未 accept', interest: '-', ready: '-', tone: 'idle' }
    ],
    discipline: '三个集合均为空；实现类由当前 JDK 与操作系统决定。'
  },
  {
    event: '注册兴趣', call: 'register(ACCEPT) + register(CONNECT)', keys: ['server', 'client'], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: 'ServerSocketChannel', interest: 'ACCEPT', ready: '-', tone: 'active' },
      { role: 'CLIENT', channel: 'SocketChannel', interest: 'CONNECT', ready: '-', tone: 'active' },
      { role: 'PEER', channel: '等待 accept', interest: '-', ready: '-', tone: 'idle' }
    ],
    discipline: '注册前必须 configureBlocking(false)，ops 还必须属于 channel.validOps()。'
  },
  {
    event: '操作系统报告就绪', call: 'select(timeout) → 2', keys: ['server', 'client'], selected: ['server', 'client'], cancelled: [],
    channels: [
      { role: 'SERVER', channel: 'ServerSocketChannel', interest: 'ACCEPT', ready: 'ACCEPT', tone: 'ready' },
      { role: 'CLIENT', channel: 'SocketChannel', interest: 'CONNECT', ready: 'CONNECT', tone: 'ready' },
      { role: 'PEER', channel: '等待 accept', interest: '-', ready: '-', tone: 'idle' }
    ],
    discipline: '演示把两个事件画在同一帧；真实到达顺序和单次 select 数量都不固定。'
  },
  {
    event: '完成 accept/connect', call: 'iterator.remove() → handler', keys: ['server', 'client', 'peer'], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: '继续监听', interest: 'ACCEPT', ready: '-', tone: 'active' },
      { role: 'CLIENT', channel: '已连接 / pending PING', interest: 'WRITE', ready: '-', tone: 'active' },
      { role: 'PEER', channel: '已 accept', interest: 'READ', ready: '-', tone: 'active' }
    ],
    discipline: '先从 selectedKeys 移除，再进入可能抛异常的处理逻辑。'
  },
  {
    event: 'Client 发送请求', call: 'write(PING) → 4', keys: ['server', 'client', 'peer'], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: '继续监听', interest: 'ACCEPT', ready: '-', tone: 'idle' },
      { role: 'CLIENT', channel: '输出已清空', interest: 'READ', ready: 'WRITE', tone: 'done' },
      { role: 'PEER', channel: '等待请求', interest: 'READ', ready: '-', tone: 'active' }
    ],
    discipline: 'pending output 为空后立即移除 OP_WRITE，避免一直可写造成空转。'
  },
  {
    event: 'Peer 收到 PING', call: 'read(input) → flip()', keys: ['server', 'client', 'peer'], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: '继续监听', interest: 'ACCEPT', ready: '-', tone: 'idle' },
      { role: 'CLIENT', channel: '等待响应', interest: 'READ', ready: '-', tone: 'active' },
      { role: 'PEER', channel: 'pending echo PING', interest: 'WRITE', ready: 'READ', tone: 'ready' }
    ],
    discipline: 'read 可以部分推进；只有附件累计到预期长度后才生成回声输出。'
  },
  {
    event: 'Peer 写回 PING', call: 'write(echo) → 4', keys: ['server', 'client', 'peer'], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: '继续监听', interest: 'ACCEPT', ready: '-', tone: 'idle' },
      { role: 'CLIENT', channel: '等待响应', interest: 'READ', ready: '-', tone: 'active' },
      { role: 'PEER', channel: '输出已清空', interest: 'READ', ready: 'WRITE', tone: 'done' }
    ],
    discipline: 'write 只承诺本次进度；本帧全部写完，因此 PEER 也移除 OP_WRITE。'
  },
  {
    event: 'Client 收齐响应', call: 'read(response) → PING', keys: ['server', 'client', 'peer'], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: '继续监听', interest: 'ACCEPT', ready: '-', tone: 'idle' },
      { role: 'CLIENT', channel: 'response=PING', interest: 'READ', ready: 'READ', tone: 'done' },
      { role: 'PEER', channel: '等待下一条', interest: 'READ', ready: '-', tone: 'active' }
    ],
    discipline: 'selectedKeys 已由应用清空；响应按预期长度累计，不假设一次 read 完成。'
  },
  {
    event: '控制线程唤醒选择线程', call: 'queue.add(command) → wakeup()', keys: ['server', 'client', 'peer'], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: 'select 被唤醒', interest: 'ACCEPT', ready: '-', tone: 'warning' },
      { role: 'CLIENT', channel: '控制命令待执行', interest: 'READ', ready: '-', tone: 'warning' },
      { role: 'PEER', channel: '无新 I/O', interest: 'READ', ready: '-', tone: 'idle' }
    ],
    discipline: 'wakeup 可以在 select 前或期间生效，多次调用可能合并；业务命令必须另存队列。'
  },
  {
    event: '取消、注销并关闭资源', call: 'cancel() → selectNow() → close()', keys: [], selected: [], cancelled: [],
    channels: [
      { role: 'SERVER', channel: 'closed', interest: '-', ready: '-', tone: 'done' },
      { role: 'CLIENT', channel: 'closed', interest: '-', ready: '-', tone: 'done' },
      { role: 'PEER', channel: 'closed', interest: '-', ready: '-', tone: 'done' }
    ],
    discipline: 'cancel 先排队，选择阶段完成注销；finally 最终关闭所有 Channel 与 Selector。'
  }
]

const currentIndex = ref(0)
const playing = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

const currentStep = computed(() => steps[currentIndex.value])
const currentPhase = computed<Phase>(() => currentStep.value.phase)
const phaseStart = computed(() => currentPhase.value === 'buffer' ? 0 : BUFFER_STEP_COUNT)
const phaseSteps = computed(() => steps.filter(step => step.phase === currentPhase.value))
const phaseLocalIndex = computed(() => currentIndex.value - phaseStart.value)
const phaseLastIndex = computed(() => phaseStart.value + phaseSteps.value.length - 1)
const bufferSnapshot = computed(() => bufferSnapshots[currentIndex.value])
const selectorSnapshot = computed(() => selectorSnapshots[currentIndex.value - BUFFER_STEP_COUNT])
const isFirstStep = computed(() => currentIndex.value === 0)
const isLastStep = computed(() => currentIndex.value === steps.length - 1)

/**
 * 停止自动播放并清理定时器，避免组件卸载后继续修改响应式状态。
 */
function stop(): void {
  if (timer !== undefined) {
    clearInterval(timer)
    timer = undefined
  }
  playing.value = false
}

/**
 * 切换 Buffer 或 Selector 阶段，并定位到该阶段的第一帧。
 */
function switchPhase(phase: Phase): void {
  stop()
  currentIndex.value = phase === 'buffer' ? 0 : BUFFER_STEP_COUNT
}

/**
 * 在当前阶段的步骤轨道内定位指定帧。
 */
function selectPhaseStep(index: number): void {
  stop()
  currentIndex.value = phaseStart.value + index
}

/**
 * 返回整条时间线的上一帧，跨阶段时自动切换视图。
 */
function previous(): void {
  stop()
  currentIndex.value = Math.max(0, currentIndex.value - 1)
}

/**
 * 进入整条时间线的下一帧，跨阶段时自动切换视图。
 */
function next(): void {
  stop()
  currentIndex.value = Math.min(steps.length - 1, currentIndex.value + 1)
}

/**
 * 自动播放当前阶段；到达阶段末尾后停止，避免用户切换主题时突然跳段。
 */
function play(): void {
  if (playing.value) {
    stop()
    return
  }

  if (currentIndex.value === phaseLastIndex.value) {
    currentIndex.value = phaseStart.value
  }

  playing.value = true
  timer = setInterval(() => {
    if (currentIndex.value >= phaseLastIndex.value) {
      stop()
      return
    }
    currentIndex.value += 1
  }, PLAY_INTERVAL_MS)
}

/**
 * 把当前阶段恢复到第一帧，便于重新核对状态变化。
 */
function resetPhase(): void {
  stop()
  currentIndex.value = phaseStart.value
}

/**
 * 返回 Buffer 单元格在当前边界中的视觉状态类。
 */
function bufferCellClasses(index: number): Record<string, boolean> {
  const snapshot = bufferSnapshot.value
  return {
    'is-focus': snapshot.focus.includes(index),
    'is-available': index >= snapshot.position && index < snapshot.limit,
    'is-outside-limit': index >= snapshot.limit,
    'is-position': index === snapshot.position,
    'is-mark': snapshot.mark === index
  }
}

/**
 * 把空 key 集合格式化为直观占位符，防止布局随内容消失而跳动。
 */
function formatKeySet(keys: string[]): string {
  return keys.length === 0 ? '∅' : `{ ${keys.join(', ')} }`
}

onBeforeUnmount(stop)
</script>

<template>
  <section class="nio-animation" aria-label="ByteBuffer 与 Selector 状态动画">
    <header class="nio-animation__header">
      <div>
        <span>源码动态演示</span>
        <h3>ByteBuffer 状态与 Selector 事件循环</h3>
      </div>
      <strong>{{ currentIndex + 1 }} / {{ steps.length }}</strong>
    </header>

    <div class="nio-animation__phase" aria-label="切换动画阶段">
      <button
        type="button"
        :aria-pressed="currentPhase === 'buffer'"
        @click="switchPhase('buffer')"
      >
        Buffer 状态 · 9 帧
      </button>
      <button
        type="button"
        :aria-pressed="currentPhase === 'selector'"
        @click="switchPhase('selector')"
      >
        Selector 循环 · 10 帧
      </button>
    </div>

    <div class="nio-animation__track" :aria-label="`${currentPhase} 执行步骤`">
      <button
        v-for="(step, index) in phaseSteps"
        :key="step.title"
        type="button"
        :class="{
          'is-active': index === phaseLocalIndex,
          'is-complete': index < phaseLocalIndex
        }"
        :aria-current="index === phaseLocalIndex ? 'step' : undefined"
        :aria-label="`第 ${index + 1} 帧：${step.title}`"
        :title="step.title"
        @click="selectPhaseStep(index)"
      >
        <span>{{ index + 1 }}</span>
      </button>
    </div>

    <div class="nio-animation__stage">
      <div v-if="currentPhase === 'buffer'" class="buffer-stage">
        <div class="buffer-stage__status">
          <strong>{{ bufferSnapshot.action }}</strong>
          <span>{{ bufferSnapshot.mode }}</span>
          <code>{{ bufferSnapshot.result }}</code>
        </div>

        <div class="buffer-stage__cells" aria-label="八字节 Buffer 内容与边界">
          <div
            v-for="(value, index) in bufferSnapshot.data"
            :key="index"
            class="buffer-cell"
            :class="bufferCellClasses(index)"
          >
            <small>{{ index }}</small>
            <strong>{{ value || '·' }}</strong>
            <span>{{ index === bufferSnapshot.position ? 'P' : bufferSnapshot.mark === index ? 'M' : '' }}</span>
          </div>
        </div>

        <div class="buffer-stage__metrics" aria-label="Buffer 四个状态指标">
          <div>
            <small>mark</small>
            <strong>{{ bufferSnapshot.mark === null ? '未定义' : bufferSnapshot.mark }}</strong>
          </div>
          <div>
            <small>position</small>
            <strong>{{ bufferSnapshot.position }}</strong>
          </div>
          <div>
            <small>limit</small>
            <strong>{{ bufferSnapshot.limit }}</strong>
          </div>
          <div>
            <small>capacity</small>
            <strong>{{ bufferSnapshot.capacity }}</strong>
          </div>
        </div>

        <div class="buffer-stage__rule">
          <code>-1 ≤ mark ≤ position ≤ limit ≤ capacity</code>
          <span>{{ bufferSnapshot.rule }}</span>
        </div>
      </div>

      <div v-else class="selector-stage">
        <div class="selector-stage__status">
          <strong>{{ selectorSnapshot.event }}</strong>
          <code>{{ selectorSnapshot.call }}</code>
        </div>

        <div class="selector-stage__sets" aria-label="Selector 三个 key 集合">
          <div>
            <small>registered keys</small>
            <strong>{{ formatKeySet(selectorSnapshot.keys) }}</strong>
          </div>
          <div>
            <small>selected keys</small>
            <strong>{{ formatKeySet(selectorSnapshot.selected) }}</strong>
          </div>
          <div>
            <small>cancelled keys</small>
            <strong>{{ formatKeySet(selectorSnapshot.cancelled) }}</strong>
          </div>
        </div>

        <div class="selector-stage__channels" aria-label="回环 Channel 状态">
          <div
            v-for="channel in selectorSnapshot.channels"
            :key="channel.role"
            class="selector-channel"
            :class="`is-${channel.tone}`"
          >
            <div>
              <small>{{ channel.role }}</small>
              <strong>{{ channel.channel }}</strong>
            </div>
            <dl>
              <div>
                <dt>interestOps</dt>
                <dd>{{ channel.interest }}</dd>
              </div>
              <div>
                <dt>readyOps</dt>
                <dd>{{ channel.ready }}</dd>
              </div>
            </dl>
          </div>
        </div>

        <div class="selector-stage__discipline">
          <strong>循环纪律</strong>
          <span>{{ selectorSnapshot.discipline }}</span>
        </div>
      </div>
    </div>

    <div class="nio-animation__explanation" aria-live="polite">
      <code>{{ currentStep.method }}</code>
      <strong>{{ currentStep.title }}</strong>
      <p>{{ currentStep.description }}</p>
    </div>

    <footer class="nio-animation__controls">
      <button type="button" :disabled="isFirstStep" title="查看上一帧" @click="previous">
        ← 上一步
      </button>
      <button type="button" class="is-primary" :aria-pressed="playing" @click="play">
        {{ playing ? '暂停' : '自动播放' }}
      </button>
      <button type="button" :disabled="isLastStep" title="查看下一帧" @click="next">
        下一步 →
      </button>
      <button type="button" title="重置当前阶段" @click="resetPhase">
        重置
      </button>
    </footer>
  </section>
</template>

<style scoped>
.nio-animation {
  --nio-green: var(--vp-c-green-1);
  --nio-green-soft: var(--vp-c-green-soft);
  --nio-yellow: var(--vp-c-yellow-1);
  --nio-yellow-soft: var(--vp-c-yellow-soft);
  margin: 24px 0 32px;
  overflow: hidden;
  border: 1px solid var(--atlas-line);
  border-radius: 6px;
  background: var(--vp-c-bg);
}

.nio-animation__header,
.nio-animation__status,
.buffer-stage__status,
.selector-stage__status {
  min-width: 0;
}

.nio-animation__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--atlas-line);
}

.nio-animation__header div {
  min-width: 0;
}

.nio-animation__header span,
.nio-animation__header > strong {
  color: var(--vp-c-text-3);
  font-size: 0.76rem;
  font-weight: 700;
}

.nio-animation__header > strong {
  flex: 0 0 auto;
}

.nio-animation__header h3 {
  margin: 3px 0 0;
  overflow-wrap: anywhere;
  font-size: 1rem;
  line-height: 1.4;
}

.nio-animation__phase {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding: 14px 20px 0;
}

.nio-animation__phase button,
.nio-animation__controls button {
  min-width: 0;
  min-height: 36px;
  border: 1px solid var(--atlas-line);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
  font: inherit;
  font-size: 0.82rem;
  font-weight: 700;
  cursor: pointer;
}

.nio-animation__phase button {
  padding: 6px 10px;
  overflow-wrap: anywhere;
}

.nio-animation__phase button[aria-pressed='true'] {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.nio-animation__track {
  display: grid;
  grid-template-columns: repeat(10, minmax(0, 1fr));
  padding: 12px 20px 0;
}

.nio-animation__track button {
  position: relative;
  display: grid;
  place-items: center;
  min-width: 0;
  height: 28px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--vp-c-text-3);
  cursor: pointer;
}

.nio-animation__track button::before {
  position: absolute;
  z-index: 0;
  top: 13px;
  right: 50%;
  left: -50%;
  height: 2px;
  background: var(--atlas-line);
  content: '';
}

.nio-animation__track button:first-child::before {
  display: none;
}

.nio-animation__track button span {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--atlas-line);
  border-radius: 50%;
  background: var(--vp-c-bg);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
}

.nio-animation__track button.is-complete::before,
.nio-animation__track button.is-active::before {
  background: var(--vp-c-brand-1);
}

.nio-animation__track button.is-complete span {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.nio-animation__track button.is-active span {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-1);
  color: white;
  box-shadow: 0 0 0 4px var(--vp-c-brand-soft);
}

.nio-animation__stage {
  min-width: 0;
  min-height: 392px;
  padding: 20px;
}

.buffer-stage,
.selector-stage {
  display: grid;
  min-width: 0;
  gap: 18px;
}

.buffer-stage__status,
.selector-stage__status {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 14px;
}

.buffer-stage__status strong,
.selector-stage__status strong {
  font-size: 0.95rem;
}

.buffer-stage__status span {
  color: var(--vp-c-text-2);
  font-size: 0.82rem;
}

.buffer-stage__status code,
.selector-stage__status code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-brand-1);
  font-size: 0.78rem;
}

.buffer-stage__cells {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 4px;
  min-width: 0;
}

.buffer-cell {
  position: relative;
  display: grid;
  grid-template-rows: 14px 36px 16px;
  min-width: 0;
  border-top: 3px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  text-align: center;
}

.buffer-cell small {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
}

.buffer-cell strong {
  display: grid;
  place-items: center;
  min-width: 0;
  color: var(--vp-c-text-2);
  font-family: var(--vp-font-family-mono);
  font-size: 1rem;
}

.buffer-cell span {
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
  font-weight: 800;
}

.buffer-cell.is-available {
  border-top-color: var(--nio-green);
  background: var(--nio-green-soft);
}

.buffer-cell.is-outside-limit {
  opacity: 0.48;
}

.buffer-cell.is-focus strong {
  color: var(--vp-c-brand-1);
}

.buffer-cell.is-position {
  box-shadow: inset 0 -3px 0 var(--vp-c-brand-1);
}

.buffer-cell.is-mark::after {
  position: absolute;
  top: -9px;
  right: 2px;
  color: var(--nio-yellow);
  content: 'M';
  font-size: 0.64rem;
  font-weight: 800;
}

.buffer-stage__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}

.buffer-stage__metrics div {
  min-width: 0;
  padding: 10px;
  border-right: 1px solid var(--atlas-line);
}

.buffer-stage__metrics div:last-child {
  border-right: 0;
}

.buffer-stage__metrics small,
.selector-stage__sets small,
.selector-channel small {
  display: block;
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
  font-weight: 700;
  text-transform: uppercase;
}

.buffer-stage__metrics strong {
  display: block;
  min-width: 0;
  margin-top: 3px;
  overflow-wrap: anywhere;
  font-family: var(--vp-font-family-mono);
  font-size: 0.9rem;
}

.buffer-stage__rule,
.selector-stage__discipline {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding-left: 12px;
  border-left: 3px solid var(--vp-c-brand-1);
}

.buffer-stage__rule code,
.buffer-stage__rule span,
.selector-stage__discipline span {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 0.8rem;
}

.buffer-stage__rule span,
.selector-stage__discipline span {
  color: var(--vp-c-text-2);
}

.selector-stage__sets {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}

.selector-stage__sets > div {
  min-width: 0;
  min-height: 68px;
  padding: 10px 12px;
  border-right: 1px solid var(--atlas-line);
}

.selector-stage__sets > div:last-child {
  border-right: 0;
}

.selector-stage__sets strong {
  display: block;
  margin-top: 6px;
  overflow-wrap: anywhere;
  font-family: var(--vp-font-family-mono);
  font-size: 0.78rem;
}

.selector-stage__channels {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  min-width: 0;
}

.selector-channel {
  min-width: 0;
  padding: 10px 0 0;
  border-top: 3px solid var(--atlas-line);
}

.selector-channel.is-active {
  border-top-color: var(--vp-c-brand-1);
}

.selector-channel.is-ready {
  border-top-color: var(--nio-yellow);
}

.selector-channel.is-done {
  border-top-color: var(--nio-green);
}

.selector-channel.is-warning {
  border-top-color: var(--vp-c-red-1);
}

.selector-channel > div strong {
  display: block;
  min-height: 42px;
  margin-top: 3px;
  overflow-wrap: anywhere;
  font-size: 0.82rem;
  line-height: 1.45;
}

.selector-channel dl {
  display: grid;
  gap: 4px;
  margin: 8px 0 0;
}

.selector-channel dl div {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
}

.selector-channel dt {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.selector-channel dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.76rem;
  font-weight: 700;
}

.selector-stage__discipline strong {
  font-size: 0.78rem;
}

.nio-animation__explanation {
  display: grid;
  grid-template-columns: minmax(150px, 0.7fr) minmax(150px, 0.8fr) minmax(260px, 1.8fr);
  gap: 14px;
  min-width: 0;
  min-height: 76px;
  padding: 14px 20px;
  border-top: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.nio-animation__explanation code,
.nio-animation__explanation strong,
.nio-animation__explanation p {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.nio-animation__explanation code {
  color: var(--vp-c-brand-1);
  font-size: 0.78rem;
}

.nio-animation__explanation strong {
  font-size: 0.9rem;
}

.nio-animation__explanation p {
  color: var(--vp-c-text-2);
  font-size: 0.84rem;
  line-height: 1.65;
}

.nio-animation__controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 20px 18px;
}

.nio-animation__controls button {
  padding: 0 12px;
}

.nio-animation__controls button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.nio-animation__controls button:hover:not(:disabled),
.nio-animation__phase button:hover {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.nio-animation__controls .is-primary {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-1);
  color: white;
}

@media (max-width: 720px) {
  .nio-animation__header,
  .nio-animation__stage,
  .nio-animation__explanation,
  .nio-animation__controls {
    padding-right: 14px;
    padding-left: 14px;
  }

  .nio-animation__phase,
  .nio-animation__track {
    padding-right: 14px;
    padding-left: 14px;
  }

  .nio-animation__stage {
    min-height: 500px;
  }

  .selector-stage__channels {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }

  .selector-channel {
    display: grid;
    grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
    gap: 10px;
  }

  .selector-channel > div strong {
    min-height: 0;
  }

  .selector-channel dl {
    margin-top: 0;
  }

  .nio-animation__explanation {
    grid-template-columns: minmax(0, 1fr);
    gap: 6px;
  }
}

@media (max-width: 420px) {
  .nio-animation__phase {
    grid-template-columns: minmax(0, 1fr);
  }

  .nio-animation__track button span {
    width: 22px;
    height: 22px;
    font-size: 0.68rem;
  }

  .buffer-stage__cells {
    gap: 2px;
  }

  .buffer-cell {
    grid-template-rows: 13px 32px 15px;
  }

  .buffer-cell strong {
    font-size: 0.88rem;
  }

  .buffer-stage__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .buffer-stage__metrics div:nth-child(2) {
    border-right: 0;
  }

  .buffer-stage__metrics div:nth-child(-n + 2) {
    border-bottom: 1px solid var(--atlas-line);
  }

  .selector-stage__sets {
    grid-template-columns: minmax(0, 1fr);
  }

  .selector-stage__sets > div {
    min-height: 0;
    border-right: 0;
    border-bottom: 1px solid var(--atlas-line);
  }

  .selector-stage__sets > div:last-child {
    border-bottom: 0;
  }

  .selector-channel {
    grid-template-columns: minmax(0, 1fr);
    gap: 5px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .nio-animation *,
  .nio-animation *::before,
  .nio-animation *::after {
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
</style>
