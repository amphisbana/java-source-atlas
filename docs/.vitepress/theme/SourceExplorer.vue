<script setup lang="ts">
import { withBase } from 'vitepress'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  findSourceForMethod,
  githubSourceUrl,
  platformOptions as sourcePlatformOptions,
  sourceExplorerStats,
  sourceTopics,
  topicHomeUrl,
  topicLabUrl,
  type SourceLocation,
  type SourceTopic
} from './source-explorer-data'
import {
  loadLearningProgress,
  updateTopicProgress,
  type TopicProgress
} from './learning-progress'

type ExplorerView = 'entryPoints' | 'breakpoints'
type ProgressField = 'readMain' | 'ranLab'
interface QualifiedMethodQuery {
  className: string
  methodName: string
}

const query = ref('')
const selectedPlatform = ref('all')
const selectedVersion = ref('all')
const selectedTopic = ref('all')
const activeView = ref<ExplorerView>('entryPoints')
const progressByTopic = ref<Record<string, TopicProgress>>({})
const copiedBreakpoint = ref('')
const copyAnnouncement = ref('')
let copyFeedbackTimer: ReturnType<typeof setTimeout> | undefined

const explorerPlatformOptions = [
  { value: 'all', label: '全部技术栈' },
  ...sourcePlatformOptions
]

/**
 * 统一搜索词大小写和首尾空白，避免每个筛选分支重复处理。
 */
const normalizedQuery = computed(() => query.value.trim().toLowerCase())

/**
 * 识别“类名.方法名”精确定位写法，避免 HashMap.putVal 误命中 ConcurrentHashMap。
 */
const qualifiedMethodQuery = computed<QualifiedMethodQuery | null>(() => {
  const matched = normalizedQuery.value.match(/^([a-z_$][\w$]*)\.([a-z_$][\w$]*)$/i)
  return matched === null
    ? null
    : { className: matched[1], methodName: matched[2] }
})

/**
 * 判断专题声明的主版本或兼容版本是否命中当前版本筛选。
 */
function matchesSelectedVersion(topic: SourceTopic): boolean {
  return selectedVersion.value === 'all'
    || topic.primaryVersion === selectedVersion.value
    || topic.compatibleVersions.includes(selectedVersion.value)
}

/**
 * 根据已选技术栈生成仍然有效的版本选项。
 */
const versionOptions = computed(() => {
  const versions = sourceTopics
    .filter((topic) => selectedPlatform.value === 'all' || topic.platform === selectedPlatform.value)
    .flatMap((topic) => [topic.primaryVersion, ...topic.compatibleVersions])
  return [...new Set(versions)].sort()
})

/**
 * 根据技术栈和版本生成专题下拉项，避免出现选中后必然无结果的组合。
 */
const topicOptions = computed(() => sourceTopics
  .filter((topic) => selectedPlatform.value === 'all' || topic.platform === selectedPlatform.value)
  .filter(matchesSelectedVersion)
  .map((topic) => ({ value: topic.topicId, label: topic.title })))

/**
 * 把多个可能为空的字段拼成统一的可搜索文本。
 */
function searchableText(values: Array<string | undefined>): string {
  return values.filter(Boolean).join(' ').toLowerCase()
}

/**
 * 生成方法、所属类以及“类名.方法名”组合搜索词，兼容 IDE 中常见的定位写法。
 */
function methodSearchTerms(
  topic: SourceTopic,
  method: string,
  sourceClass?: string | null
): Array<string | undefined> {
  const source = findSourceForMethod(topic, method, sourceClass)
  const simpleClassName = source?.className.split('.').pop()
  return [
    method,
    source?.className,
    source === undefined ? undefined : `${source.className}.${method}`,
    simpleClassName === undefined ? undefined : `${simpleClassName}.${method}`
  ]
}

/**
 * 精确判断方法是否属于查询指定的简单类名，并忽略方法参数列表。
 */
function matchesQualifiedMethod(
  topic: SourceTopic,
  method: string,
  sourceClass?: string | null
): boolean {
  const qualified = qualifiedMethodQuery.value
  if (qualified === null) {
    return false
  }

  const source = findSourceForMethod(topic, method, sourceClass)
  if (source === undefined) {
    return false
  }
  const simpleClassName = source.className.split('.').pop()?.toLowerCase()
  const signatureHead = method.trim().split(/\s+\/\s+|\//, 1)[0].split('(', 1)[0]
  const methodName = signatureHead.split('.').pop()?.toLowerCase()
  return simpleClassName === qualified.className && methodName === qualified.methodName
}

/**
 * 判断关键词是否命中专题标题、版本或任一源码类。
 */
function matchesTopicMetadata(topic: SourceTopic): boolean {
  if (normalizedQuery.value === '') {
    return true
  }
  const sources = [topic.source, ...topic.relatedSources]
  return searchableText([
    topic.topicId,
    topic.title,
    topic.primaryVersion,
    ...topic.compatibleVersions,
    topic.platformLabel,
    ...sources.flatMap((source) => [source.className, source.sourcePath])
  ]).includes(normalizedQuery.value)
}

/**
 * 返回当前搜索词命中的源码入口；命中专题元数据时保留该专题全部入口。
 */
function visibleEntryPoints(topic: SourceTopic): SourceTopic['entryPoints'] {
  if (qualifiedMethodQuery.value !== null) {
    return topic.entryPoints.filter((entry) => (
      matchesQualifiedMethod(topic, entry.method, entry.sourceClass)
    ))
  }
  if (normalizedQuery.value === '' || matchesTopicMetadata(topic)) {
    return topic.entryPoints
  }
  return topic.entryPoints.filter((entry) => searchableText([
    ...methodSearchTerms(topic, entry.method, entry.sourceClass),
    entry.purpose,
    entry.document
  ]).includes(normalizedQuery.value))
}

/**
 * 返回当前搜索词命中的推荐断点；变量名也参与搜索。
 */
function visibleBreakpoints(topic: SourceTopic): SourceTopic['breakpoints'] {
  if (qualifiedMethodQuery.value !== null) {
    return topic.breakpoints.filter((breakpoint) => (
      matchesQualifiedMethod(topic, breakpoint.method, breakpoint.sourceClass)
    ))
  }
  if (normalizedQuery.value === '' || matchesTopicMetadata(topic)) {
    return topic.breakpoints
  }
  return topic.breakpoints.filter((breakpoint) => searchableText([
    ...methodSearchTerms(topic, breakpoint.method, breakpoint.sourceClass),
    breakpoint.scenario,
    ...breakpoint.variables
  ]).includes(normalizedQuery.value))
}

/**
 * 组合技术栈、版本、专题和当前视图的筛选条件。
 */
const filteredTopics = computed(() => sourceTopics.filter((topic) => {
  if (selectedPlatform.value !== 'all' && topic.platform !== selectedPlatform.value) {
    return false
  }
  if (!matchesSelectedVersion(topic)) {
    return false
  }
  if (selectedTopic.value !== 'all' && topic.topicId !== selectedTopic.value) {
    return false
  }
  return activeView.value === 'entryPoints'
    ? visibleEntryPoints(topic).length > 0
    : visibleBreakpoints(topic).length > 0
}))

/**
 * 统计当前筛选结果中实际展示的入口或断点数量。
 */
const visibleItemCount = computed(() => filteredTopics.value.reduce((total, topic) => total + (
  activeView.value === 'entryPoints'
    ? visibleEntryPoints(topic).length
    : visibleBreakpoints(topic).length
), 0))

/**
 * 汇总两个学习动作的完成数量。
 */
const progressSummary = computed(() => sourceTopics.reduce((summary, topic) => {
  const progress = progressByTopic.value[topic.topicId]
  if (progress?.readMain) {
    summary.readMain += 1
  }
  if (progress?.ranLab) {
    summary.ranLab += 1
  }
  return summary
}, { readMain: 0, ranLab: 0 }))

/**
 * 技术栈变化后清理下游筛选，确保选项组合始终有效。
 */
function handlePlatformChange(): void {
  selectedVersion.value = 'all'
  selectedTopic.value = 'all'
}

/**
 * 版本变化后清理专题筛选。
 */
function handleVersionChange(): void {
  selectedTopic.value = 'all'
}

/**
 * 一次清空所有筛选条件，但保留用户当前查看的入口/断点视图。
 */
function resetFilters(): void {
  query.value = ''
  selectedPlatform.value = 'all'
  selectedVersion.value = 'all'
  selectedTopic.value = 'all'
}

/**
 * 读取专题进度；尚未记录时返回稳定的未完成状态。
 */
function topicProgress(topicId: string): TopicProgress {
  return progressByTopic.value[topicId] ?? {
    readMain: false,
    ranLab: false,
    updatedAt: ''
  }
}

/**
 * 更新一个进度复选框，并同步组件内存状态与 localStorage。
 */
function handleProgressChange(topicId: string, field: ProgressField, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  const current = topicProgress(topicId)
  progressByTopic.value = {
    ...progressByTopic.value,
    [topicId]: updateTopicProgress(topicId, {
      readMain: field === 'readMain' ? checked : current.readMain,
      ranLab: field === 'ranLab' ? checked : current.ranLab
    })
  }
}

/**
 * 为源码位置生成固定版本链接；没有匹配源码时返回空字符串。
 */
function sourceLink(topic: SourceTopic, source: SourceLocation | undefined): string {
  return source === undefined ? '' : githubSourceUrl(topic, source)
}

/**
 * 返回索引项最终定位到的完整类名，便于读者在进入 GitHub 前确认源码归属。
 */
function sourceOwnerLabel(
  topic: SourceTopic,
  method: string,
  sourceClass?: string | null
): string {
  return findSourceForMethod(topic, method, sourceClass)?.className ?? ''
}

/**
 * 优先使用现代 Clipboard API，失败时回退到临时文本域复制。
 */
async function writeClipboard(text: string): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // 非安全上下文或权限被拒绝时，继续使用兼容性复制方案。
    }
  }

  const textarea = document.createElement('textarea')
  const previouslyFocused = document.activeElement instanceof HTMLElement
    ? document.activeElement
    : null
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.opacity = '0'
  textarea.setAttribute('readonly', '')
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()

  try {
    if (!document.execCommand('copy')) {
      throw new Error('浏览器拒绝复制文本')
    }
  } finally {
    textarea.remove()
    previouslyFocused?.focus()
  }
}

/**
 * 复制可直接用于 IDE 定位的断点方法和观察变量，并提供短暂状态反馈。
 */
async function copyBreakpoint(topicId: string, method: string, variables: string[]): Promise<void> {
  const copyKey = `${topicId}:${method}`
  try {
    await writeClipboard(`${method}\n观察变量：${variables.join(', ')}`)
    copiedBreakpoint.value = copyKey
    copyAnnouncement.value = `已复制断点 ${method}`
    if (copyFeedbackTimer !== undefined) {
      clearTimeout(copyFeedbackTimer)
    }
    copyFeedbackTimer = setTimeout(() => {
      copiedBreakpoint.value = ''
    }, 1800)
  } catch {
    copiedBreakpoint.value = ''
    copyAnnouncement.value = `复制失败，请手动复制断点 ${method}`
  }
}

/**
 * 页面只在客户端挂载后访问 localStorage，避免影响 VitePress 服务端构建。
 */
onMounted(() => {
  progressByTopic.value = loadLearningProgress()
})

/**
 * 页面切换前清理复制提示计时器，避免卸载后继续修改组件状态。
 */
onBeforeUnmount(() => {
  if (copyFeedbackTimer !== undefined) {
    clearTimeout(copyFeedbackTimer)
  }
})
</script>

<template>
  <section class="source-explorer" aria-label="源码索引工作台">
    <div class="source-explorer__overview" aria-label="索引统计">
      <div><strong>{{ sourceExplorerStats.topicCount }}</strong><span>专题</span></div>
      <div><strong>{{ sourceExplorerStats.entryPointCount }}</strong><span>源码入口</span></div>
      <div><strong>{{ sourceExplorerStats.breakpointCount }}</strong><span>推荐断点</span></div>
      <div><strong>{{ sourceExplorerStats.sourceCount }}</strong><span>去重源码文件</span></div>
      <div class="is-progress"><strong>{{ progressSummary.readMain }}/{{ sourceExplorerStats.topicCount }}</strong><span>主线已读</span></div>
      <div class="is-progress"><strong>{{ progressSummary.ranLab }}/{{ sourceExplorerStats.topicCount }}</strong><span>实验完成</span></div>
    </div>

    <div class="source-explorer__toolbar">
      <label class="source-explorer__search">
        <span>搜索</span>
        <input v-model="query" type="search" placeholder="类、方法、用途或变量" />
      </label>
      <label>
        <span>技术栈</span>
        <select v-model="selectedPlatform" @change="handlePlatformChange">
          <option v-for="option in explorerPlatformOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <label>
        <span>版本</span>
        <select v-model="selectedVersion" @change="handleVersionChange">
          <option value="all">全部版本</option>
          <option v-for="version in versionOptions" :key="version" :value="version">
            {{ version }}
          </option>
        </select>
      </label>
      <label>
        <span>专题</span>
        <select v-model="selectedTopic">
          <option value="all">全部专题</option>
          <option v-for="option in topicOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <button type="button" class="source-explorer__reset" @click="resetFilters">重置筛选</button>
    </div>

    <div class="source-explorer__result-bar">
      <div class="source-explorer__tabs" role="group" aria-label="索引内容类型">
        <button
          type="button"
          :aria-pressed="activeView === 'entryPoints'"
          :class="{ 'is-active': activeView === 'entryPoints' }"
          @click="activeView = 'entryPoints'"
        >
          源码入口
        </button>
        <button
          type="button"
          :aria-pressed="activeView === 'breakpoints'"
          :class="{ 'is-active': activeView === 'breakpoints' }"
          @click="activeView = 'breakpoints'"
        >
          推荐断点
        </button>
      </div>
      <span aria-live="polite">{{ filteredTopics.length }} 个专题 · {{ visibleItemCount }} 条结果</span>
      <span class="source-explorer__sr-only" aria-live="polite">{{ copyAnnouncement }}</span>
    </div>

    <div v-if="filteredTopics.length > 0" class="source-explorer__topics">
      <details
        v-for="topic in filteredTopics"
        :key="topic.topicId"
        class="source-topic"
        :open="normalizedQuery !== '' || selectedTopic === topic.topicId"
      >
        <summary>
          <span class="source-topic__identity">
            <span class="source-topic__meta">
              {{ topic.platformLabel }} · {{ topic.primaryVersion }} · 源码 {{ topic.sourceRef }}
            </span>
            <strong>{{ topic.title }}</strong>
            <code>{{ topic.source.className }}</code>
          </span>
          <span class="source-topic__count">
            {{ activeView === 'entryPoints' ? visibleEntryPoints(topic).length : visibleBreakpoints(topic).length }} 条
          </span>
        </summary>

        <div class="source-topic__body">
          <div class="source-topic__actions">
            <a :href="withBase(topicHomeUrl(topic))">专题主线</a>
            <a :href="withBase(topicLabUrl(topic))">断点实验</a>
            <a :href="githubSourceUrl(topic, topic.source)" target="_blank" rel="noreferrer">固定版本源码</a>
          </div>

          <fieldset class="source-topic__progress">
            <legend>学习进度</legend>
            <label>
              <input
                type="checkbox"
                :checked="topicProgress(topic.topicId).readMain"
                @change="handleProgressChange(topic.topicId, 'readMain', $event)"
              />
              主线已读
            </label>
            <label>
              <input
                type="checkbox"
                :checked="topicProgress(topic.topicId).ranLab"
                @change="handleProgressChange(topic.topicId, 'ranLab', $event)"
              />
              实验已运行
            </label>
          </fieldset>

          <details class="source-topic__related">
            <summary>关联源码（{{ topic.relatedSources.length }}）</summary>
            <ul>
              <li v-for="source in topic.relatedSources" :key="source.className">
                <code>{{ source.className }}</code>
                <a :href="githubSourceUrl(topic, source)" target="_blank" rel="noreferrer">源码</a>
              </li>
            </ul>
          </details>

          <div v-if="activeView === 'entryPoints'" class="source-topic__items">
            <article v-for="entry in visibleEntryPoints(topic)" :key="entry.method" class="source-index-row">
              <div>
                <code class="source-index-row__method">{{ entry.method }}</code>
                <div class="source-index-row__owner">
                  <span>定位</span>
                  <code>{{ sourceOwnerLabel(topic, entry.method, entry.sourceClass) }}</code>
                </div>
                <p>{{ entry.purpose }}</p>
              </div>
              <div class="source-index-row__actions">
                <a :href="withBase(entry.document)">讲解</a>
                <a
                  v-if="sourceLink(topic, findSourceForMethod(topic, entry.method, entry.sourceClass))"
                  :href="sourceLink(topic, findSourceForMethod(topic, entry.method, entry.sourceClass))"
                  target="_blank"
                  rel="noreferrer"
                >源码</a>
              </div>
            </article>
          </div>

          <div v-else class="source-topic__items">
            <article
              v-for="breakpoint in visibleBreakpoints(topic)"
              :key="breakpoint.method"
              class="source-index-row is-breakpoint"
            >
              <div>
                <code class="source-index-row__method">{{ breakpoint.method }}</code>
                <div v-if="breakpoint.sourceClass !== null" class="source-index-row__owner">
                  <span>定位</span>
                  <code>{{ sourceOwnerLabel(topic, breakpoint.method, breakpoint.sourceClass) }}</code>
                </div>
                <div v-else class="source-index-row__owner is-lab">
                  <span>定位</span>
                  <code>教学 Lab 本地方法</code>
                </div>
                <p>{{ breakpoint.scenario }}</p>
                <div class="source-index-row__variables">
                  <code v-for="variable in breakpoint.variables" :key="variable">{{ variable }}</code>
                </div>
              </div>
              <div class="source-index-row__actions">
                <a
                  v-if="sourceLink(topic, findSourceForMethod(topic, breakpoint.method, breakpoint.sourceClass))"
                  :href="sourceLink(topic, findSourceForMethod(topic, breakpoint.method, breakpoint.sourceClass))"
                  target="_blank"
                  rel="noreferrer"
                >源码</a>
                <button
                  type="button"
                  :aria-label="copiedBreakpoint === `${topic.topicId}:${breakpoint.method}`
                    ? `已复制断点 ${breakpoint.method}`
                    : `复制断点 ${breakpoint.method}`"
                  @click="copyBreakpoint(topic.topicId, breakpoint.method, breakpoint.variables)"
                >
                  {{ copiedBreakpoint === `${topic.topicId}:${breakpoint.method}` ? '已复制' : '复制断点' }}
                </button>
              </div>
            </article>
          </div>
        </div>
      </details>
    </div>

    <div v-else class="source-explorer__empty" role="status">
      没有匹配结果
      <button type="button" @click="resetFilters">清空筛选</button>
    </div>
  </section>
</template>

<style scoped>
.source-explorer {
  --explorer-line: color-mix(in srgb, var(--vp-c-divider) 88%, transparent);
  container-name: source-explorer;
  container-type: inline-size;
  margin: 24px 0 48px;
  color: var(--vp-c-text-1);
}

.source-explorer__overview {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border-block: 1px solid var(--explorer-line);
}

.source-explorer__overview > div {
  min-width: 0;
  padding: 14px 16px;
  border-right: 1px solid var(--explorer-line);
}

.source-explorer__overview > div:last-child {
  border-right: 0;
}

.source-explorer__overview strong,
.source-explorer__overview span {
  display: block;
}

.source-explorer__overview strong {
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 1.12rem;
}

.source-explorer__overview span {
  margin-top: 3px;
  color: var(--vp-c-text-3);
  font-size: 0.76rem;
}

.source-explorer__overview .is-progress strong {
  color: var(--vp-c-brand-1);
}

.source-explorer__toolbar {
  display: grid;
  grid-template-columns: minmax(230px, 1.45fr) repeat(3, minmax(145px, 0.75fr)) auto;
  gap: 12px;
  align-items: end;
  padding: 18px 0;
  border-bottom: 1px solid var(--explorer-line);
}

.source-explorer__toolbar label {
  display: grid;
  min-width: 0;
  gap: 6px;
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
  font-weight: 700;
}

.source-explorer__toolbar input,
.source-explorer__toolbar select,
.source-explorer__reset {
  width: 100%;
  height: 40px;
  border: 1px solid var(--explorer-line);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
  font: inherit;
  letter-spacing: 0;
}

.source-explorer__toolbar input,
.source-explorer__toolbar select {
  padding: 0 10px;
}

.source-explorer__reset {
  width: auto;
  padding: 0 14px;
  cursor: pointer;
  white-space: nowrap;
}

.source-explorer__reset:hover {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.source-explorer__result-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  color: var(--vp-c-text-2);
  font-size: 0.82rem;
}

.source-explorer__tabs {
  display: inline-grid;
  grid-template-columns: repeat(2, minmax(108px, 1fr));
  padding: 3px;
  border: 1px solid var(--explorer-line);
  border-radius: 5px;
  background: var(--vp-c-bg-soft);
}

.source-explorer__tabs button {
  min-height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: var(--vp-c-text-2);
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}

.source-explorer__tabs button.is-active {
  background: var(--vp-c-bg);
  color: var(--vp-c-brand-1);
  box-shadow: 0 1px 4px rgba(23, 32, 42, 0.12);
}

.source-explorer__topics {
  border-top: 1px solid var(--explorer-line);
}

.source-topic {
  border-bottom: 1px solid var(--explorer-line);
}

.source-topic > summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  align-items: center;
  min-height: 78px;
  padding: 12px 4px;
  cursor: pointer;
  list-style-position: outside;
}

.source-topic > summary:hover {
  background: var(--vp-c-bg-soft);
}

.source-topic__identity {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.source-topic__identity strong {
  font-size: 0.98rem;
}

.source-topic__identity code,
.source-index-row__method {
  overflow-wrap: anywhere;
  white-space: normal;
}

.source-topic__meta,
.source-topic__count {
  color: var(--vp-c-text-3);
  font-size: 0.74rem;
  font-weight: 700;
}

.source-topic__body {
  padding: 0 4px 22px;
}

.source-topic__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  padding: 12px 0;
  border-block: 1px solid var(--explorer-line);
}

.source-topic__actions a,
.source-index-row__actions a {
  color: var(--vp-c-brand-1);
  font-size: 0.8rem;
  font-weight: 700;
  text-decoration: none;
}

.source-topic__actions a:hover,
.source-index-row__actions a:hover {
  color: var(--vp-c-brand-2);
}

.source-topic__progress {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 22px;
  margin: 14px 0 0;
  padding: 0;
  border: 0;
}

.source-topic__progress legend {
  width: 100%;
  margin-bottom: 2px;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-weight: 700;
}

.source-topic__progress label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--vp-c-text-2);
  cursor: pointer;
  font-size: 0.82rem;
}

.source-topic__progress input {
  width: 16px;
  height: 16px;
  accent-color: var(--vp-c-brand-1);
}

.source-topic__related {
  margin-top: 14px;
  padding-block: 10px;
  border-block: 1px dashed var(--explorer-line);
}

.source-topic__related > summary {
  color: var(--vp-c-text-2);
  cursor: pointer;
  font-size: 0.82rem;
  font-weight: 700;
}

.source-topic__related ul {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.source-topic__related li {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 7px 0;
  border-bottom: 1px solid var(--explorer-line);
}

.source-topic__related code {
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: normal;
}

.source-topic__related a {
  flex: 0 0 auto;
  color: var(--vp-c-brand-1);
  font-size: 0.78rem;
  font-weight: 700;
  text-decoration: none;
}

.source-topic__items {
  margin-top: 16px;
}

.source-index-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  align-items: start;
  padding: 14px 0;
  border-bottom: 1px solid var(--explorer-line);
}

.source-index-row:last-child {
  border-bottom: 0;
}

.source-index-row p {
  margin: 6px 0 0;
  color: var(--vp-c-text-2);
  font-size: 0.84rem;
  line-height: 1.65;
}

.source-index-row__owner {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  margin-top: 7px;
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
}

.source-index-row__owner span {
  font-weight: 700;
}

.source-index-row__owner code {
  overflow-wrap: anywhere;
  white-space: normal;
}

.source-index-row__owner.is-lab code {
  color: var(--atlas-coral);
}

.source-index-row__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 28px;
  white-space: nowrap;
}

.source-index-row__actions button {
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid var(--explorer-line);
  border-radius: 4px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-2);
  cursor: pointer;
  font-size: 0.76rem;
  font-weight: 700;
}

.source-index-row__actions button:hover {
  border-color: var(--atlas-coral);
  color: var(--atlas-coral);
}

.source-index-row__variables {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

.source-index-row__variables code {
  overflow-wrap: anywhere;
  white-space: normal;
}

.source-explorer__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 180px;
  border-block: 1px solid var(--explorer-line);
  color: var(--vp-c-text-2);
}

.source-explorer__empty button {
  border: 0;
  background: transparent;
  color: var(--vp-c-brand-1);
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}

.source-explorer__sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@container source-explorer (max-width: 820px) {
  .source-explorer__toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .source-explorer__search {
    grid-column: 1 / -1;
  }

  .source-explorer__reset {
    width: 100%;
  }
}

@media (max-width: 960px) {
  .source-explorer__overview {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .source-explorer__overview > div:nth-child(3n) {
    border-right: 0;
  }

  .source-explorer__overview > div:nth-child(-n + 3) {
    border-bottom: 1px solid var(--explorer-line);
  }

  .source-explorer__toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .source-explorer__search {
    grid-column: 1 / -1;
  }

  .source-explorer__reset {
    width: 100%;
  }
}

@media (max-width: 560px) {
  .source-explorer {
    margin-top: 18px;
  }

  .source-explorer__overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .source-explorer__overview > div,
  .source-explorer__overview > div:nth-child(3n) {
    border-right: 1px solid var(--explorer-line);
    border-bottom: 1px solid var(--explorer-line);
  }

  .source-explorer__overview > div:nth-child(2n) {
    border-right: 0;
  }

  .source-explorer__overview > div:nth-last-child(-n + 2) {
    border-bottom: 0;
  }

  .source-explorer__toolbar {
    grid-template-columns: 1fr;
  }

  .source-explorer__search {
    grid-column: auto;
  }

  .source-explorer__result-bar {
    align-items: stretch;
    flex-direction: column;
    padding: 14px 0;
  }

  .source-explorer__tabs {
    width: 100%;
  }

  .source-topic > summary,
  .source-index-row {
    grid-template-columns: 1fr;
    gap: 9px;
  }

  .source-topic__count {
    justify-self: start;
  }

  .source-index-row__actions {
    flex-wrap: wrap;
  }

  .source-topic__related li {
    align-items: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .source-explorer *,
  .source-explorer *::before,
  .source-explorer *::after {
    scroll-behavior: auto !important;
    transition: none !important;
  }
}
</style>
