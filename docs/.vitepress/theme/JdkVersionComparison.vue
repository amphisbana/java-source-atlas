<script setup lang="ts">
import { withBase } from 'vitepress'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  differenceKindForDirection,
  findJdkSource,
  hasDifferenceBetween,
  jdkComparisonTopics,
  jdkComparisonVersions,
  jdkSourceUrl,
  jdkVersionMeta,
  type JdkComparisonTopic,
  type JdkComparisonVersion,
  type JdkDifferenceKind,
  type JdkDifferenceState,
  type JdkSourceCoordinate,
  type JdkVersionDifference
} from './jdk-version-comparison-data'
import { topicHomeUrl } from './source-explorer-data'

interface DirectionalDifference {
  difference: JdkVersionDifference
  kind: JdkDifferenceKind
}

interface ComparisonSide {
  id: 'left' | 'right'
  version: JdkComparisonVersion
}

const kindOptions: Array<{ kind: JdkDifferenceKind; label: string; description: string }> = [
  { kind: 'added', label: '新增', description: '右侧出现、左侧不存在' },
  { kind: 'removed', label: '移除', description: '左侧存在、右侧不存在' },
  { kind: 'signature', label: '签名', description: '声明或可调用入口发生变化' },
  { kind: 'implementation', label: '实现', description: '入口仍在，关键实现发生变化' }
]

const selectedTopicId = ref(jdkComparisonTopics[0].id)
const leftVersion = ref<JdkComparisonVersion>('8')
const rightVersion = ref<JdkComparisonVersion>('21')
const activeKinds = ref<Record<JdkDifferenceKind, boolean>>({
  added: true,
  removed: true,
  signature: true,
  implementation: true
})
const showOnlyChanged = ref(true)
const demoIndex = ref(0)
const isDemoPlaying = ref(false)
let demoTimer: ReturnType<typeof setInterval> | undefined

/**
 * 返回当前专题；数据异常时回退到第一个专题，保证页面始终可渲染。
 */
const selectedTopic = computed<JdkComparisonTopic>(() => (
  jdkComparisonTopics.find((topic) => topic.id === selectedTopicId.value) ?? jdkComparisonTopics[0]
))

/**
 * 返回当前专题对应的 source-index 元数据，供页面入口和迁移提示复用。
 */
const selectedSourceTopic = computed(() => selectedTopic.value.sourceTopic)

/**
 * 判断当前是向新版本迁移、反向回看还是同版本核对。
 */
const directionMode = computed<'forward' | 'reverse' | 'same'>(() => {
  const difference = Number(rightVersion.value) - Number(leftVersion.value)
  if (difference === 0) return 'same'
  return difference > 0 ? 'forward' : 'reverse'
})

/**
 * 生成版本方向说明，明确反向比较时新增和移除会互换。
 */
const directionDescription = computed(() => {
  if (directionMode.value === 'same') {
    return '左右版本相同：源码坐标可核对，但不会产生版本差异。'
  }
  if (directionMode.value === 'reverse') {
    return '正在反向回看：以左侧为基准，“新增”和“移除”语义已自动互换。'
  }
  return '正在向新版本迁移：所有类型都以“左侧 → 右侧”为方向。'
})

/**
 * 生成带稳定左右标识的比较侧，确保同版本比较时两个面板仍拥有不同的 Vue key。
 */
const comparisonSides = computed<ComparisonSide[]>(() => [
  { id: 'left', version: leftVersion.value },
  { id: 'right', version: rightVersion.value }
])

/**
 * 找出当前版本对之间确实变化的精选差异，并计算方向相关类型。
 */
const directionalDifferences = computed<DirectionalDifference[]>(() => (
  selectedTopic.value.differences
    .map((difference) => ({
      difference,
      kind: differenceKindForDirection(difference, leftVersion.value, rightVersion.value)
    }))
    .filter(({ difference }) => !showOnlyChanged.value
      || hasDifferenceBetween(difference, leftVersion.value, rightVersion.value))
))

/**
 * 应用差异类型筛选，保留用户明确开启的项目。
 */
const visibleDifferences = computed(() => directionalDifferences.value.filter(({ kind }) => (
  activeKinds.value[kind]
)))

/**
 * 统计当前版本对中每类差异数量，筛选关闭后仍显示真实总数。
 */
const differenceCounts = computed<Record<JdkDifferenceKind, number>>(() => {
  const counts: Record<JdkDifferenceKind, number> = { added: 0, removed: 0, signature: 0, implementation: 0 }
  directionalDifferences.value.forEach(({ kind }) => { counts[kind] += 1 })
  return counts
})

/**
 * 返回演示的当前步骤，并在专题切换期间保证索引不越界。
 */
const currentDemoStep = computed(() => (
  selectedTopic.value.demoSteps[demoIndex.value] ?? selectedTopic.value.demoSteps[0]
))

/**
 * 切换一个差异类型筛选状态。
 */
function toggleKind(kind: JdkDifferenceKind): void {
  activeKinds.value = { ...activeKinds.value, [kind]: !activeKinds.value[kind] }
}

/**
 * 一次开启或关闭四类差异，方便读者在“只看变化”和全量核对之间切换。
 */
function setAllKinds(enabled: boolean): void {
  activeKinds.value = {
    added: enabled,
    removed: enabled,
    signature: enabled,
    implementation: enabled
  }
}

/**
 * 交换左右版本，便于从升级视角快速切换为回退视角。
 */
function swapVersions(): void {
  const previousLeftVersion = leftVersion.value
  leftVersion.value = rightVersion.value
  rightVersion.value = previousLeftVersion
}

/**
 * 返回差异在指定版本的源码状态；undefined 表示该入口尚不存在。
 */
function differenceState(
  difference: JdkVersionDifference,
  version: JdkComparisonVersion
): JdkDifferenceState | undefined {
  return difference.states[version]
}

/**
 * 返回差异状态对应的完整类名和文件路径。
 */
function sourceForState(
  difference: JdkVersionDifference,
  version: JdkComparisonVersion
): JdkSourceCoordinate | undefined {
  const state = differenceState(difference, version)
  return state === undefined
    ? undefined
    : findJdkSource(selectedTopic.value, version, state.sourceKey)
}

/**
 * 为差异状态生成带行号的固定源码链接。
 */
function differenceSourceUrl(
  difference: JdkVersionDifference,
  version: JdkComparisonVersion
): string {
  const state = differenceState(difference, version)
  return state === undefined
    ? ''
    : jdkSourceUrl(selectedTopic.value, version, state.sourceKey, state.line)
}

/**
 * 为专题源码坐标生成固定文件链接。
 */
function coordinateSourceUrl(version: JdkComparisonVersion, sourceKey: string): string {
  return jdkSourceUrl(selectedTopic.value, version, sourceKey)
}

/**
 * 返回差异类型中文标签。
 */
function kindLabel(kind: JdkDifferenceKind): string {
  return kindOptions.find((option) => option.kind === kind)?.label ?? kind
}

/**
 * 返回方向感知的迁移说明；反向时保留原说明并提示阅读方式。
 */
function migrationText(difference: JdkVersionDifference): string {
  if (directionMode.value === 'reverse') {
    return `当前是反向比较，请把下面的正向迁移结论反向理解：${difference.migrationImpact}`
  }
  return difference.migrationImpact
}

/**
 * 判断时间线节点是否为当前左右端点。
 */
function timelineRole(version: JdkComparisonVersion): 'left' | 'right' | 'both' | 'none' {
  if (version === leftVersion.value && version === rightVersion.value) return 'both'
  if (version === leftVersion.value) return 'left'
  if (version === rightVersion.value) return 'right'
  return 'none'
}

/**
 * 停止逐步演示的自动播放并清理计时器。
 */
function stopDemo(): void {
  if (demoTimer !== undefined) {
    clearInterval(demoTimer)
    demoTimer = undefined
  }
  isDemoPlaying.value = false
}

/**
 * 前进到下一演示步骤；到达末尾后从第一步重新开始。
 */
function nextDemoStep(): void {
  demoIndex.value = (demoIndex.value + 1) % selectedTopic.value.demoSteps.length
}

/**
 * 返回上一演示步骤；第一步继续向前时回到末尾。
 */
function previousDemoStep(): void {
  demoIndex.value = (
    demoIndex.value - 1 + selectedTopic.value.demoSteps.length
  ) % selectedTopic.value.demoSteps.length
}

/**
 * 切换自动播放状态，每 2.4 秒推进一个源码步骤。
 */
function toggleDemoPlayback(): void {
  if (isDemoPlaying.value) {
    stopDemo()
    return
  }
  isDemoPlaying.value = true
  demoTimer = setInterval(nextDemoStep, 2400)
}

/**
 * 专题或版本改变后回到第一步，避免旧演示状态干扰新的比较。
 */
function resetInteractiveState(): void {
  stopDemo()
  demoIndex.value = 0
}

watch([selectedTopicId, leftVersion, rightVersion], resetInteractiveState)
/**
 * 支持从源码索引或 IDEA 插件通过 ?topic=... 直接打开对应版本对比专题。
 */
onMounted(() => {
  const requestedTopicId = new URLSearchParams(window.location.search).get('topic')
  if (requestedTopicId !== null && jdkComparisonTopics.some((topic) => topic.id === requestedTopicId)) {
    selectedTopicId.value = requestedTopicId
  }
})
onBeforeUnmount(stopDemo)
</script>

<template>
  <section class="jdk-compare" aria-label="JDK 源码版本对比工作台">
    <header class="jdk-compare__hero">
      <div>
        <span class="jdk-compare__eyebrow">JDK VERSION LAB</span>
        <h2>同一入口，跨版本阅读</h2>
        <p>先确认调用边界和不变量，再看新增、移除、签名与实现变化。</p>
      </div>
      <div class="jdk-compare__scope">
        <strong>精选关键差异，不是全文 diff</strong>
        <span>{{ jdkVersionMeta[leftVersion].sourceRef }} → {{ jdkVersionMeta[rightVersion].sourceRef }}</span>
      </div>
    </header>

    <div class="jdk-compare__controls">
      <label for="jdk-compare-topic">
        <span>专题</span>
        <select id="jdk-compare-topic" v-model="selectedTopicId">
          <option v-for="topic in jdkComparisonTopics" :key="topic.id" :value="topic.id">
            {{ topic.title }}
          </option>
        </select>
      </label>
      <label for="jdk-compare-left">
        <span>左侧基准</span>
        <select id="jdk-compare-left" v-model="leftVersion">
          <option v-for="version in jdkComparisonVersions" :key="version" :value="version">
            {{ jdkVersionMeta[version].label }}
          </option>
        </select>
      </label>
      <button
        type="button"
        class="jdk-compare__direction"
        title="交换左右版本"
        aria-label="交换左右版本"
        @click="swapVersions"
      >
        <span aria-hidden="true">⇄</span>
      </button>
      <label for="jdk-compare-right">
        <span>右侧目标</span>
        <select id="jdk-compare-right" v-model="rightVersion">
          <option v-for="version in jdkComparisonVersions" :key="version" :value="version">
            {{ jdkVersionMeta[version].label }}
          </option>
        </select>
      </label>
    </div>

    <div class="jdk-compare__direction-note" :class="`is-${directionMode}`" role="status">
      <strong>{{ jdkVersionMeta[leftVersion].label }} → {{ jdkVersionMeta[rightVersion].label }}</strong>
      <span>{{ directionDescription }}</span>
    </div>

    <div class="jdk-compare__filters" aria-label="差异类型筛选">
      <label class="jdk-compare__changed-toggle">
        <input v-model="showOnlyChanged" type="checkbox" />
        <span>只看发生变化</span>
      </label>
      <button
        v-for="option in kindOptions"
        :key="option.kind"
        type="button"
        :class="[`is-${option.kind}`, { 'is-active': activeKinds[option.kind] }]"
        :aria-pressed="activeKinds[option.kind]"
        :title="option.description"
        @click="toggleKind(option.kind)"
      >
        <span>{{ option.label }}</span>
        <strong>{{ differenceCounts[option.kind] }}</strong>
      </button>
      <button type="button" class="jdk-compare__filter-reset" @click="setAllKinds(true)">全部类型</button>
    </div>

    <section class="jdk-compare__topic-intro">
      <div>
        <span>{{ selectedTopic.packageName }}</span>
        <h3>{{ selectedTopic.title }}</h3>
        <div v-if="selectedSourceTopic" class="jdk-compare__topic-links">
          <a :href="withBase(topicHomeUrl(selectedSourceTopic))">专题主线</a>
          <a :href="withBase(`/source-explorer/?topic=${selectedSourceTopic.topicId}`)">源码索引</a>
          <span>索引已接入 {{ selectedSourceTopic.versionComparison?.supportedVersions.join(' / ') }}</span>
        </div>
      </div>
      <p>{{ selectedTopic.question }}</p>
      <div class="jdk-compare__topic-conclusion">
        <strong>{{ selectedTopic.conclusion }}</strong>
        <span v-if="selectedSourceTopic?.versionComparison" class="jdk-compare__index-summary">
          索引摘要：{{ selectedSourceTopic.versionComparison.summary }}
        </span>
        <span v-if="selectedSourceTopic?.versionComparison">迁移提示：{{ selectedSourceTopic.versionComparison.migrationHint }}</span>
      </div>
    </section>

    <ol class="jdk-compare__timeline" aria-label="JDK 版本时间线">
      <li
        v-for="point in selectedTopic.timeline"
        :key="point.version"
        :class="`is-${timelineRole(point.version)}`"
      >
        <span class="jdk-compare__timeline-dot">{{ point.version }}</span>
        <div>
          <strong>{{ point.title }}</strong>
          <p>{{ point.summary }}</p>
        </div>
      </li>
    </ol>

    <section class="jdk-compare__coordinates" aria-label="当前专题真实源码坐标">
      <header>
        <h3>固定源码坐标</h3>
        <p>仓库、Tag 和路径共同确定一份不会漂移的源码快照。</p>
      </header>
      <div class="jdk-compare__coordinate-grid">
        <section v-for="side in comparisonSides" :key="`${side.id}-coordinates`">
          <header>
            <strong>{{ jdkVersionMeta[side.version].label }}</strong>
            <span>{{ jdkVersionMeta[side.version].snapshot }}</span>
          </header>
          <a
            v-for="source in selectedTopic.sources[side.version]"
            :key="source.sourceKey"
            :href="coordinateSourceUrl(side.version, source.sourceKey)"
            target="_blank"
            rel="noreferrer"
          >
            <span>{{ source.className }}</span>
            <code>{{ jdkVersionMeta[side.version].repository }}@{{ jdkVersionMeta[side.version].sourceRef }}/{{ source.sourcePath }}</code>
            <b>打开固定源码 ↗</b>
          </a>
        </section>
      </div>
    </section>

    <section class="jdk-compare__results" aria-label="精选源码差异">
      <header class="jdk-compare__results-head">
        <div>
          <span>SELECTED DELTAS</span>
          <h3>{{ visibleDifferences.length }} / {{ directionalDifferences.length }} 条差异</h3>
        </div>
        <p aria-live="polite">类型以 {{ jdkVersionMeta[leftVersion].label }} → {{ jdkVersionMeta[rightVersion].label }} 为准。</p>
      </header>

      <article
        v-for="item in visibleDifferences"
        :key="item.difference.id"
        class="jdk-diff"
      >
        <header class="jdk-diff__header">
          <span class="jdk-diff__kind" :class="`is-${item.kind}`">{{ kindLabel(item.kind) }}</span>
          <div>
            <h4>{{ item.difference.title }}</h4>
            <p>{{ item.difference.summary }}</p>
          </div>
        </header>

        <div class="jdk-diff__reason">
          <span>为什么变</span>
          <p>{{ item.difference.reason }}</p>
        </div>

        <div class="jdk-diff__panes">
          <section
            v-for="side in comparisonSides"
            :key="`${item.difference.id}-${side.id}`"
          >
            <header>
              <div>
                <strong>{{ jdkVersionMeta[side.version].label }}</strong>
                <code v-if="differenceState(item.difference, side.version)">
                  {{ differenceState(item.difference, side.version)?.symbol }}
                </code>
                <code v-else>入口不存在</code>
              </div>
              <a
                v-if="differenceState(item.difference, side.version)"
                :href="differenceSourceUrl(item.difference, side.version)"
                target="_blank"
                rel="noreferrer"
              >源码 L{{ differenceState(item.difference, side.version)?.line }} ↗</a>
            </header>

            <template v-if="differenceState(item.difference, side.version)">
              <div class="jdk-diff__location">
                <span>{{ sourceForState(item.difference, side.version)?.className }}</span>
                <code>{{ sourceForState(item.difference, side.version)?.sourcePath }}</code>
              </div>
              <pre><code>{{ differenceState(item.difference, side.version)?.code }}</code></pre>
              <p class="jdk-diff__note">{{ differenceState(item.difference, side.version)?.note }}</p>
            </template>
            <div v-else class="jdk-diff__absent">
              <strong>此版本尚无该入口</strong>
              <span>{{ item.kind === 'removed' ? '它存在于左侧版本，回退到右侧后不可用。' : '它将在右侧版本首次出现。' }}</span>
            </div>
          </section>
        </div>

        <footer class="jdk-diff__impact">
          <span>{{ directionMode === 'reverse' ? '反向阅读' : '迁移影响' }}</span>
          <p>{{ migrationText(item.difference) }}</p>
        </footer>
      </article>

      <div v-if="visibleDifferences.length === 0" class="jdk-compare__empty">
        <strong>{{ directionMode === 'same' ? '请选择两个不同版本' : '当前筛选下没有差异' }}</strong>
        <span>{{ directionMode === 'same' ? '同版本仍可在上方核对固定源码坐标。' : '重新开启至少一种差异类型即可继续。' }}</span>
      </div>
    </section>

    <section class="jdk-compare__migration">
      <header>
        <span>MIGRATION CHECK</span>
        <h3>读完后必须能回答</h3>
      </header>
      <ol>
        <li v-for="(item, index) in selectedTopic.migrationChecklist" :key="item">
          <span>0{{ index + 1 }}</span>
          <p>{{ item }}</p>
        </li>
      </ol>
    </section>

    <section class="jdk-demo" aria-label="版本差异逐步演示">
      <header class="jdk-demo__header">
        <div>
          <span>STEP DEMO</span>
          <h3>{{ selectedTopic.demoTitle }}</h3>
        </div>
        <div class="jdk-demo__actions">
          <button type="button" title="上一步" aria-label="上一步" @click="previousDemoStep">‹</button>
          <button
            type="button"
            :title="isDemoPlaying ? '暂停自动播放' : '自动播放'"
            :aria-label="isDemoPlaying ? '暂停自动播放' : '自动播放'"
            :aria-pressed="isDemoPlaying"
            @click="toggleDemoPlayback"
          >{{ isDemoPlaying ? 'Ⅱ' : '▶' }}</button>
          <button type="button" title="下一步" aria-label="下一步" @click="nextDemoStep">›</button>
        </div>
      </header>

      <div class="jdk-demo__rail" role="tablist" aria-label="演示步骤">
        <button
          v-for="(step, index) in selectedTopic.demoSteps"
          :key="step.title"
          type="button"
          role="tab"
          :aria-selected="demoIndex === index"
          :class="{ 'is-active': demoIndex === index, 'is-complete': demoIndex > index }"
          @click="demoIndex = index"
        >
          <span>{{ index + 1 }}</span>
          <strong>{{ step.title }}</strong>
        </button>
      </div>

      <div class="jdk-demo__stage" aria-live="polite">
        <header>
          <code>{{ currentDemoStep.method }}</code>
          <p>{{ currentDemoStep.description }}</p>
        </header>
        <div class="jdk-demo__lanes">
          <section>
            <span>{{ jdkVersionMeta[leftVersion].label }}</span>
            <strong>{{ currentDemoStep.states[leftVersion] }}</strong>
          </section>
          <div aria-hidden="true">→</div>
          <section>
            <span>{{ jdkVersionMeta[rightVersion].label }}</span>
            <strong>{{ currentDemoStep.states[rightVersion] }}</strong>
          </section>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.jdk-compare {
  container-name: jdk-compare;
  container-type: inline-size;
  margin: 24px 0 40px;
  border: 1px solid var(--atlas-line);
  border-radius: 6px;
  overflow: hidden;
  background: var(--vp-c-bg);
}

.jdk-compare * {
  box-sizing: border-box;
  min-width: 0;
}

.jdk-compare__hero {
  display: flex;
  justify-content: space-between;
  gap: 28px;
  padding: 24px;
  border-bottom: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jdk-compare__eyebrow,
.jdk-compare__results-head span,
.jdk-compare__migration header span,
.jdk-demo__header span {
  color: var(--vp-c-brand-1);
  font-size: 0.68rem;
  font-weight: 800;
  letter-spacing: 0;
}

.jdk-compare h2,
.jdk-compare h3,
.jdk-compare h4,
.jdk-compare p {
  margin: 0;
  letter-spacing: 0;
}

.jdk-compare__hero h2 {
  margin-top: 5px;
  border: 0;
  padding: 0;
  font-size: 1.25rem;
}

.jdk-compare__hero p {
  margin-top: 7px;
  color: var(--vp-c-text-2);
  font-size: 0.84rem;
  line-height: 1.65;
}

.jdk-compare__scope {
  display: grid;
  align-content: center;
  gap: 5px;
  max-width: 330px;
  text-align: right;
}

.jdk-compare__scope strong {
  color: var(--atlas-coral);
  font-size: 0.76rem;
}

.jdk-compare__scope span {
  overflow-wrap: anywhere;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
}

.jdk-compare__controls {
  display: grid;
  grid-template-columns: minmax(190px, 1.35fr) minmax(150px, 1fr) 30px minmax(150px, 1fr);
  gap: 12px;
  align-items: end;
  padding: 20px 24px 14px;
}

.jdk-compare__controls label {
  display: grid;
  gap: 7px;
  color: var(--vp-c-text-2);
  font-size: 0.72rem;
  font-weight: 700;
}

.jdk-compare__controls select {
  width: 100%;
  height: 40px;
  border: 1px solid var(--atlas-line);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
  padding: 0 34px 0 10px;
  font: inherit;
  font-size: 0.8rem;
}

.jdk-compare__direction {
  display: grid;
  place-items: center;
  height: 40px;
  border: 1px solid var(--atlas-line);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  cursor: pointer;
  font-size: 1.1rem;
}

.jdk-compare__direction:hover {
  border-color: var(--vp-c-brand-1);
}

.jdk-compare__direction > span {
  display: block;
  line-height: 1;
}

.jdk-compare__direction-note {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 14px;
  margin: 0 24px;
  padding: 10px 12px;
  border-left: 3px solid var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-text-2);
  font-size: 0.74rem;
}

.jdk-compare__direction-note.is-reverse {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, var(--vp-c-bg));
}

.jdk-compare__direction-note.is-same {
  border-left-color: var(--vp-c-warning-1);
  background: color-mix(in srgb, var(--vp-c-warning-1) 9%, var(--vp-c-bg));
}

.jdk-compare__direction-note strong {
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
}

.jdk-compare__filters {
  display: grid;
  grid-template-columns: minmax(150px, 1.2fr) repeat(4, minmax(72px, 1fr)) auto;
  gap: 1px;
  margin: 14px 24px 24px;
  border: 1px solid var(--atlas-line);
  border-radius: 4px;
  overflow: hidden;
  background: var(--atlas-line);
}

.jdk-compare__changed-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 40px;
  padding: 0 11px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-2);
  cursor: pointer;
  font-size: 0.74rem;
  font-weight: 700;
}

.jdk-compare__changed-toggle input {
  width: 15px;
  height: 15px;
  accent-color: var(--vp-c-brand-1);
}

.jdk-compare__filters button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 40px;
  border: 0;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-3);
  padding: 0 12px;
  cursor: pointer;
  font-size: 0.76rem;
}

.jdk-compare__filters button.is-active {
  color: var(--vp-c-text-1);
  box-shadow: inset 0 -3px var(--vp-c-brand-1);
}

.jdk-compare__filters button.is-active.is-added { box-shadow: inset 0 -3px #16825d; }
.jdk-compare__filters button.is-active.is-removed { box-shadow: inset 0 -3px var(--atlas-coral); }
.jdk-compare__filters button.is-active.is-signature { box-shadow: inset 0 -3px #3b6fb6; }
.jdk-compare__filters button.is-active.is-implementation { box-shadow: inset 0 -3px #9b6414; }

.jdk-compare__filters strong {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--atlas-line);
  border-radius: 50%;
  color: inherit;
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.jdk-compare__filter-reset {
  min-height: 40px;
  border: 0;
  border-left: 1px solid var(--atlas-line);
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  cursor: pointer;
  font-size: 0.72rem;
  font-weight: 800;
  padding: 0 12px;
  white-space: nowrap;
}

.jdk-compare__topic-intro {
  display: grid;
  grid-template-columns: minmax(150px, 0.55fr) minmax(220px, 1.15fr) minmax(220px, 1.1fr);
  gap: 20px;
  padding: 22px 24px;
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jdk-compare__topic-intro > div span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.67rem;
}

.jdk-compare__topic-intro h3 {
  margin-top: 4px;
  font-size: 1rem;
}

.jdk-compare__topic-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 9px;
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.jdk-compare__topic-links a {
  color: var(--vp-c-brand-1);
  font-weight: 800;
  text-decoration: none;
}

.jdk-compare__topic-links a:hover {
  text-decoration: underline;
}

.jdk-compare__topic-links span {
  color: var(--vp-c-text-3);
}

.jdk-compare__topic-intro p,
.jdk-compare__topic-conclusion {
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
  font-weight: 500;
  line-height: 1.7;
}

.jdk-compare__topic-conclusion {
  display: grid;
  gap: 7px;
}

.jdk-compare__topic-conclusion strong {
  color: var(--vp-c-text-1);
}

.jdk-compare__topic-conclusion span {
  color: var(--atlas-coral);
  font-size: 0.72rem;
}

.jdk-compare__topic-conclusion .jdk-compare__index-summary {
  color: var(--vp-c-text-2);
}

.jdk-compare__timeline {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
  padding: 24px;
  list-style: none;
}

.jdk-compare__timeline li {
  position: relative;
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 10px;
  padding-right: 16px;
}

.jdk-compare__timeline li::before {
  position: absolute;
  top: 16px;
  left: 34px;
  right: 0;
  height: 1px;
  background: var(--atlas-line);
  content: '';
}

.jdk-compare__timeline li:last-child::before { display: none; }

.jdk-compare__timeline-dot {
  z-index: 1;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--atlas-line);
  border-radius: 50%;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
  font-weight: 800;
}

.jdk-compare__timeline li.is-left .jdk-compare__timeline-dot,
.jdk-compare__timeline li.is-both .jdk-compare__timeline-dot {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.jdk-compare__timeline li.is-right .jdk-compare__timeline-dot {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.jdk-compare__timeline li > div { padding-top: 1px; }
.jdk-compare__timeline strong { font-size: 0.78rem; }
.jdk-compare__timeline p { margin-top: 5px; color: var(--vp-c-text-3); font-size: 0.7rem; line-height: 1.55; }

.jdk-compare__coordinates {
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
  padding: 22px 24px;
}

.jdk-compare__coordinates > header,
.jdk-compare__results-head,
.jdk-compare__migration > header,
.jdk-demo__header {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: end;
}

.jdk-compare__coordinates h3,
.jdk-compare__results-head h3,
.jdk-compare__migration h3,
.jdk-demo__header h3 { font-size: 0.94rem; }

.jdk-compare__coordinates > header p,
.jdk-compare__results-head p {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.jdk-compare__coordinate-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  margin-top: 14px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-line);
}

.jdk-compare__coordinate-grid > section {
  display: grid;
  align-content: start;
  gap: 1px;
  background: var(--atlas-line);
}

.jdk-compare__coordinate-grid section > header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  background: var(--atlas-surface);
}

.jdk-compare__coordinate-grid header strong { font-size: 0.76rem; }
.jdk-compare__coordinate-grid header span { color: var(--vp-c-text-3); font-size: 0.65rem; }

.jdk-compare__coordinate-grid a {
  display: grid;
  gap: 5px;
  padding: 12px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
  text-decoration: none;
}

.jdk-compare__coordinate-grid a:hover { background: var(--vp-c-brand-soft); }
.jdk-compare__coordinate-grid a span { font-size: 0.74rem; font-weight: 700; }
.jdk-compare__coordinate-grid a code { overflow-wrap: anywhere; color: var(--vp-c-text-3); font-size: 0.62rem; line-height: 1.5; }
.jdk-compare__coordinate-grid a b { color: var(--vp-c-brand-1); font-size: 0.68rem; }

.jdk-compare__results { padding: 24px; }
.jdk-compare__results-head { margin-bottom: 14px; }
.jdk-compare__results-head h3 { margin-top: 4px; }

.jdk-diff {
  margin: 0;
  padding: 22px 0;
  border-top: 1px solid var(--atlas-line);
}

.jdk-diff__header {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 14px;
}

.jdk-diff__kind {
  display: grid;
  place-items: center;
  align-self: start;
  min-height: 28px;
  border-left: 3px solid var(--vp-c-brand-1);
  background: var(--atlas-surface);
  color: var(--vp-c-text-2);
  font-size: 0.68rem;
  font-weight: 800;
}

.jdk-diff__kind.is-added { border-left-color: #16825d; }
.jdk-diff__kind.is-removed { border-left-color: var(--atlas-coral); }
.jdk-diff__kind.is-signature { border-left-color: #3b6fb6; }
.jdk-diff__kind.is-implementation { border-left-color: #9b6414; }

.jdk-diff__header h4 { font-size: 0.9rem; }
.jdk-diff__header p { margin-top: 5px; color: var(--vp-c-text-2); font-size: 0.76rem; line-height: 1.65; }

.jdk-diff__reason,
.jdk-diff__impact {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 12px;
  margin: 14px 0;
  color: var(--vp-c-text-2);
  font-size: 0.73rem;
  line-height: 1.65;
}

.jdk-diff__reason span,
.jdk-diff__impact span { color: var(--vp-c-text-3); font-weight: 700; }

.jdk-diff__panes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-line);
}

.jdk-diff__panes > section { display: grid; align-content: start; background: var(--vp-c-bg); }

.jdk-diff__panes section > header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  min-height: 54px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jdk-diff__panes header > div { display: grid; gap: 3px; }
.jdk-diff__panes header strong { font-size: 0.75rem; }
.jdk-diff__panes header code { overflow-wrap: anywhere; color: var(--vp-c-text-3); font-size: 0.62rem; }
.jdk-diff__panes header a { align-self: start; color: var(--vp-c-brand-1); font-size: 0.65rem; font-weight: 700; text-decoration: none; }

.jdk-diff__location {
  display: grid;
  gap: 3px;
  padding: 9px 12px;
  color: var(--vp-c-text-2);
  font-size: 0.66rem;
}

.jdk-diff__location code { overflow-wrap: anywhere; color: var(--vp-c-text-3); font-size: 0.6rem; }
.jdk-diff__location span { overflow-wrap: anywhere; }

.jdk-diff pre {
  min-height: 150px;
  margin: 0;
  border-radius: 0;
  padding: 13px;
  overflow: auto;
  background: #18211f;
  color: #e9f1ee;
  font-size: 0.66rem;
  line-height: 1.65;
}

.jdk-diff pre code { color: inherit; }

.jdk-diff__note {
  min-height: 58px;
  padding: 10px 12px;
  color: var(--vp-c-text-2);
  font-size: 0.7rem;
  line-height: 1.6;
}

.jdk-diff__absent {
  display: grid;
  place-items: center;
  align-content: center;
  gap: 7px;
  min-height: 254px;
  padding: 22px;
  background: repeating-linear-gradient(-45deg, var(--vp-c-bg), var(--vp-c-bg) 9px, var(--atlas-surface) 9px, var(--atlas-surface) 18px);
  text-align: center;
}

.jdk-diff__absent strong { font-size: 0.78rem; }
.jdk-diff__absent span { max-width: 260px; color: var(--vp-c-text-3); font-size: 0.7rem; line-height: 1.6; }

.jdk-diff__impact {
  margin-bottom: 0;
  padding: 10px 12px;
  border-left: 3px solid var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 8%, var(--vp-c-bg));
}

.jdk-compare__empty {
  display: grid;
  place-items: center;
  gap: 6px;
  min-height: 170px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-2);
  text-align: center;
}

.jdk-compare__empty span { color: var(--vp-c-text-3); font-size: 0.74rem; }

.jdk-compare__migration {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 24px;
  padding: 24px;
  border-top: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jdk-compare__migration > header { display: block; }
.jdk-compare__migration h3 { margin-top: 4px; }
.jdk-compare__migration ol { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; margin: 0; padding: 0; background: var(--atlas-line); list-style: none; }
.jdk-compare__migration li { display: grid; grid-template-columns: 26px minmax(0, 1fr); gap: 8px; padding: 12px; background: var(--vp-c-bg); }
.jdk-compare__migration li span { color: var(--atlas-coral); font-family: var(--vp-font-family-mono); font-size: 0.65rem; font-weight: 800; }
.jdk-compare__migration li p { color: var(--vp-c-text-2); font-size: 0.7rem; line-height: 1.6; }

.jdk-demo {
  padding: 24px;
  border-top: 1px solid var(--atlas-line);
}

.jdk-demo__actions { display: grid; flex: 0 0 auto; grid-template-columns: repeat(3, 34px); gap: 6px; }
.jdk-demo__actions button { display: grid; place-items: center; width: 34px; height: 34px; border: 1px solid var(--atlas-line); border-radius: 4px; background: var(--vp-c-bg); color: var(--vp-c-text-1); cursor: pointer; font-size: 0.9rem; }
.jdk-demo__actions button:hover { border-color: var(--vp-c-brand-1); color: var(--vp-c-brand-1); }

.jdk-demo__rail {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  margin-top: 16px;
  background: var(--atlas-line);
}

.jdk-demo__rail button {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  min-height: 46px;
  border: 0;
  background: var(--atlas-surface);
  color: var(--vp-c-text-3);
  padding: 8px 10px;
  cursor: pointer;
  text-align: left;
}

.jdk-demo__rail button.is-active { background: var(--vp-c-brand-soft); color: var(--vp-c-text-1); }
.jdk-demo__rail button.is-complete { color: var(--vp-c-brand-1); }
.jdk-demo__rail button span { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid currentColor; border-radius: 50%; font-size: 0.65rem; }
.jdk-demo__rail button strong { overflow-wrap: anywhere; font-size: 0.7rem; }

.jdk-demo__stage {
  margin-top: 1px;
  border: 1px solid var(--atlas-line);
  overflow: hidden;
}

.jdk-demo__stage > header { display: grid; grid-template-columns: minmax(180px, 0.7fr) minmax(0, 1.3fr); gap: 18px; padding: 13px 14px; background: var(--vp-c-bg); }
.jdk-demo__stage header code { overflow-wrap: anywhere; color: var(--vp-c-brand-1); font-size: 0.72rem; }
.jdk-demo__stage header p { color: var(--vp-c-text-2); font-size: 0.72rem; line-height: 1.6; }

.jdk-demo__lanes {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 42px minmax(0, 1fr);
  align-items: stretch;
  border-top: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.jdk-demo__lanes > div { display: grid; place-items: center; color: var(--atlas-coral); animation: compare-pulse 1.4s ease-in-out infinite; }
.jdk-demo__lanes section { display: grid; gap: 8px; align-content: center; min-height: 112px; padding: 16px; background: var(--vp-c-bg); }
.jdk-demo__lanes section span { color: var(--vp-c-text-3); font-size: 0.68rem; font-weight: 700; }
.jdk-demo__lanes section strong { overflow-wrap: anywhere; color: var(--vp-c-text-1); font-family: var(--vp-font-family-mono); font-size: 0.74rem; line-height: 1.55; }

@keyframes compare-pulse {
  0%, 100% { opacity: 0.35; transform: translateX(-2px); }
  50% { opacity: 1; transform: translateX(2px); }
}

@media (max-width: 820px) {
  .jdk-compare__topic-intro { grid-template-columns: 1fr 1fr; }
  .jdk-compare__topic-conclusion { grid-column: 1 / -1; }
  .jdk-compare__migration { grid-template-columns: 1fr; }
}

@container jdk-compare (max-width: 760px) {
  .jdk-compare__topic-intro { grid-template-columns: 1fr 1fr; }
  .jdk-compare__topic-conclusion { grid-column: 1 / -1; }
  .jdk-compare__migration { grid-template-columns: 1fr; }
}

/* VitePress 双侧栏会在宽视口下继续压缩正文，因此交互控件按组件宽度切换布局。 */
@container jdk-compare (max-width: 600px) {
  .jdk-compare__controls { grid-template-columns: 1fr; }
  .jdk-compare__direction { height: 40px; }
  .jdk-compare__direction > span { transform: rotate(90deg); }
  .jdk-compare__filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .jdk-compare__filter-reset { border-left: 0; }
}

@media (max-width: 680px) {
  .jdk-compare__hero,
  .jdk-compare__coordinates > header,
  .jdk-compare__results-head,
  .jdk-demo__header { display: grid; }

  .jdk-compare__scope { max-width: none; text-align: left; }
  .jdk-compare__controls { grid-template-columns: 1fr; }
  .jdk-compare__direction { height: 40px; }
  .jdk-compare__direction > span { transform: rotate(90deg); }
  .jdk-compare__filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .jdk-compare__filter-reset { border-left: 0; }
  .jdk-compare__topic-intro,
  .jdk-compare__coordinate-grid,
  .jdk-diff__panes,
  .jdk-compare__migration ol { grid-template-columns: 1fr; }
  .jdk-compare__topic-conclusion { grid-column: auto; }
  .jdk-compare__timeline { grid-template-columns: 1fr; gap: 16px; }
  .jdk-compare__timeline li::before { top: 34px; bottom: -16px; left: 16px; right: auto; width: 1px; height: auto; }
  .jdk-compare__timeline li:last-child::before { display: none; }
  .jdk-demo__rail { grid-template-columns: 1fr; }
  .jdk-demo__stage > header { grid-template-columns: 1fr; }
  .jdk-demo__lanes { grid-template-columns: 1fr; }
  .jdk-demo__lanes > div { min-height: 30px; transform: rotate(90deg); animation: none; }
  .jdk-diff pre { min-height: 0; max-height: 320px; }
}

@media (prefers-reduced-motion: reduce) {
  .jdk-demo__lanes > div { animation: none; }
}
</style>
