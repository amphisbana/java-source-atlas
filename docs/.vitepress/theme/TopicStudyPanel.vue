<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { withBase } from 'vitepress'
import {
  findSourceForMethod,
  githubSourceUrl,
  sourceTopics,
  topicHomeUrl,
  topicLabUrl,
  type SourceTopic
} from './source-explorer-data'
import {
  loadLearningProgress,
  updateTopicProgress,
  type TopicProgress
} from './learning-progress'

interface TopicStudyPanelProps {
  topicId: string
  designInsight: string
  focusQuestion: string
}

type ProgressField = 'readMain' | 'ranLab'

const props = defineProps<TopicStudyPanelProps>()
const progress = ref<TopicProgress>({ readMain: false, ranLab: false, updatedAt: '' })

/**
 * 根据索引编号找到当前专题；索引缺失时保留空值，让文档正文仍可正常渲染。
 */
const topic = computed<SourceTopic | undefined>(() => sourceTopics.find((item) => item.topicId === props.topicId))

/**
 * 只展示最值得先跟踪的两个入口，避免面板变成专题正文的重复目录。
 */
const featuredEntries = computed(() => topic.value?.entryPoints.slice(0, 2) ?? [])

/**
 * 只展示两个高价值断点，让读者先观察主线，再回到源码细节扩展。
 */
const featuredBreakpoints = computed(() => topic.value?.breakpoints.slice(0, 2) ?? [])

/**
 * 判断当前专题是否已经完成“主线阅读 + Lab 实验”两个动作。
 */
const isComplete = computed(() => progress.value.readMain && progress.value.ranLab)

/**
 * 生成带版本固定源码标签的 GitHub 地址，帮助读者从文档直接回到真实实现。
 */
function sourceUrl(method: string, sourceClass?: string | null): string {
  if (topic.value === undefined) {
    return ''
  }
  const source = findSourceForMethod(topic.value, method, sourceClass)
  return source === undefined ? '' : githubSourceUrl(topic.value, source)
}

/**
 * 把索引中的根路径文档转换为当前 VitePress 部署基路径下的链接。
 */
function documentUrl(document: string): string {
  return withBase(document)
}

/**
 * 读取当前专题的本地学习状态，保证专题页与学习路线、源码工作台共享进度。
 */
onMounted(() => {
  const saved = loadLearningProgress()[props.topicId]
  if (saved !== undefined) {
    progress.value = saved
  }
})

/**
 * 更新一个学习动作，并将合并后的结果写回共享进度存储。
 */
function handleProgressChange(field: ProgressField, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  progress.value = updateTopicProgress(props.topicId, { [field]: checked })
}

/**
 * 将 ISO 时间转换为适合面板展示的中文日期，旧数据异常时不显示时间。
 */
function formatUpdatedAt(value: string): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : `最近更新：${date.toLocaleDateString('zh-CN')}`
}
</script>

<template>
  <section v-if="topic" class="topic-study-panel" aria-label="专题学习闭环">
    <div class="topic-study-panel__header">
      <div>
        <p class="topic-study-panel__eyebrow">专题学习闭环</p>
        <h2>先理解设计，再验证执行</h2>
        <p class="topic-study-panel__intro">
          从一处公开入口进入固定版本源码，用断点确认关键状态，最后运行 Lab 验证自己的推理。
        </p>
      </div>
      <span v-if="isComplete" class="topic-study-panel__complete">已完成本轮</span>
    </div>

    <div class="topic-study-panel__insight">
      <span>这份源码的精妙之处</span>
      <strong>{{ designInsight }}</strong>
      <p>阅读时先回答：{{ focusQuestion }}</p>
    </div>

    <div class="topic-study-panel__grid">
      <div class="topic-study-panel__column">
        <div class="topic-study-panel__section-title">建议先跟的源码入口</div>
        <a
          v-for="entry in featuredEntries"
          :key="`${entry.method}-${entry.document}`"
          class="topic-study-panel__item"
          :href="documentUrl(entry.document)"
        >
          <span class="topic-study-panel__item-topline">
            <code>{{ entry.method }}</code>
            <span aria-hidden="true">↗</span>
          </span>
          <small>{{ entry.purpose }}</small>
        </a>
      </div>

      <div class="topic-study-panel__column">
        <div class="topic-study-panel__section-title">推荐断点</div>
        <div
          v-for="breakpoint in featuredBreakpoints"
          :key="`${breakpoint.method}-${breakpoint.scenario}`"
          class="topic-study-panel__item topic-study-panel__item--breakpoint"
        >
          <a
            v-if="sourceUrl(breakpoint.method, breakpoint.sourceClass)"
            :href="sourceUrl(breakpoint.method, breakpoint.sourceClass)"
            target="_blank"
            rel="noreferrer"
          >
            <span class="topic-study-panel__item-topline">
              <code>{{ breakpoint.method }}</code>
              <span aria-hidden="true">↗</span>
            </span>
          </a>
          <code v-else>{{ breakpoint.method }}</code>
          <small>{{ breakpoint.scenario }}</small>
          <span class="topic-study-panel__variables">观察：{{ breakpoint.variables.join('、') }}</span>
        </div>
      </div>
    </div>

    <div class="topic-study-panel__footer">
      <div class="topic-study-panel__checks">
        <label>
          <input
            type="checkbox"
            :checked="progress.readMain"
            @change="handleProgressChange('readMain', $event)"
          />
          <span>我已完成主线阅读</span>
        </label>
        <label>
          <input
            type="checkbox"
            :checked="progress.ranLab"
            @change="handleProgressChange('ranLab', $event)"
          />
          <span>我已运行并理解 Lab</span>
        </label>
        <small v-if="progress.updatedAt">{{ formatUpdatedAt(progress.updatedAt) }}</small>
      </div>
      <div class="topic-study-panel__actions">
        <a class="topic-study-panel__button topic-study-panel__button--primary" :href="withBase(topicLabUrl(topic))">
          打开 Debug Lab
        </a>
        <a class="topic-study-panel__button" :href="withBase('/source-explorer/')">
          查看全部入口
        </a>
        <a class="topic-study-panel__next" :href="withBase(topicHomeUrl(topic))">
          回到专题目录
        </a>
      </div>
    </div>
  </section>
</template>
