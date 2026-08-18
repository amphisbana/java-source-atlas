/**
 * 单个源码专题的学习进度。
 */
export interface TopicProgress {
  readMain: boolean
  ranLab: boolean
  updatedAt: string
}

type TopicProgressPatch = Partial<Pick<TopicProgress, 'readMain' | 'ranLab'>>
type LearningProgress = Record<string, TopicProgress>

interface LearningProgressSnapshot {
  progress: LearningProgress
  storage: Storage | null
  canPersist: boolean
}

const STORAGE_KEY = 'java-source-atlas:learning-progress:v1'
const UNSAFE_TOPIC_IDS = new Set(['__proto__', 'constructor', 'prototype'])

/**
 * 返回当前环境可用的 localStorage；SSR、隐私模式或安全策略拒绝访问时返回 null。
 *
 * @return 可安全访问的浏览器存储
 */
function getLocalStorage(): Storage | null {
  if (typeof window === 'undefined') {
    return null
  }

  try {
    return window.localStorage
  } catch {
    return null
  }
}

/**
 * 判断输入是否为普通键值对象，数组和 null 均不属于进度容器。
 *
 * @param value 待校验数据
 * @return 普通对象返回 true
 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 判断专题编号能否安全作为普通对象键使用。
 *
 * @param topicId 专题编号
 * @return 非空且不会污染对象原型时返回 true
 */
function isSafeTopicId(topicId: unknown): topicId is string {
  return typeof topicId === 'string'
    && topicId.trim().length > 0
    && !UNSAFE_TOPIC_IDS.has(topicId)
}

/**
 * 判断更新时间是否为可解析的非空日期字符串。
 *
 * @param value 待校验更新时间
 * @return 可解析日期字符串返回 true
 */
function isValidUpdatedAt(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0) {
    return false
  }

  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString() === value
}

/**
 * 从未知数据中提取完整有效的专题进度，字段缺失或类型错误时丢弃整条记录。
 *
 * @param value 待解析记录
 * @return 有效专题进度，无效时返回 null
 */
function parseTopicProgress(value: unknown): TopicProgress | null {
  if (!isRecord(value)
    || typeof value.readMain !== 'boolean'
    || typeof value.ranLab !== 'boolean'
    || !isValidUpdatedAt(value.updatedAt)) {
    return null
  }

  return {
    readMain: value.readMain,
    ranLab: value.ranLab,
    updatedAt: value.updatedAt
  }
}

/**
 * 解析 localStorage 中的 JSON，并逐条过滤无效结构和不安全键名。
 *
 * @param raw 持久化原文
 * @return 清洗后的进度表
 */
function parseLearningProgress(raw: string): LearningProgress {
  const parsed: unknown = JSON.parse(raw)
  if (!isRecord(parsed)) {
    return {}
  }

  const progress: LearningProgress = {}
  for (const [topicId, value] of Object.entries(parsed)) {
    if (!isSafeTopicId(topicId)) {
      continue
    }

    const topicProgress = parseTopicProgress(value)
    if (topicProgress !== null) {
      progress[topicId] = topicProgress
    }
  }
  return progress
}

/**
 * 读取进度与存储状态，区分“没有记录”和“读取失败”，避免失败后覆盖已有数据。
 *
 * @return 当前进度、存储引用以及本次是否允许安全回写
 */
function readLearningProgressSnapshot(): LearningProgressSnapshot {
  const storage = getLocalStorage()
  if (storage === null) {
    return { progress: {}, storage: null, canPersist: false }
  }

  try {
    const raw = storage.getItem(STORAGE_KEY)
    return {
      progress: raw === null ? {} : parseLearningProgress(raw),
      storage,
      canPersist: true
    }
  } catch {
    // 2026-08-16：读取或解析失败不能等同于空存储，否则后续 setItem 会覆盖其他专题的历史进度。
    return { progress: {}, storage, canPersist: false }
  }
}

/**
 * 创建尚未记录的默认进度；空时间表示这条记录从未发生有效更新。
 *
 * @return 默认未完成状态
 */
function emptyTopicProgress(): TopicProgress {
  return { readMain: false, ranLab: false, updatedAt: '' }
}

/**
 * 从浏览器读取全部学习进度。
 *
 * <p>SSR、存储不可用、JSON 损坏或读取异常时统一返回空对象，不影响页面渲染。</p>
 *
 * @return 通过结构校验的专题进度表
 */
export function loadLearningProgress(): LearningProgress {
  return readLearningProgressSnapshot().progress
}

/**
 * 合并并持久化一个专题的学习进度。
 *
 * <p>只接受布尔类型的 readMain/ranLab 补丁；没有有效布尔字段时不更新时间。
 * 读取失败时不会覆盖原存储，写入失败时仍返回合并结果，页面可继续更新内存状态。</p>
 *
 * @param topicId 专题编号
 * @param patch 要更新的阅读或实验状态
 * @return 本次合并后的专题进度
 */
export function updateTopicProgress(topicId: string, patch: TopicProgressPatch): TopicProgress {
  const snapshot = readLearningProgressSnapshot()
  const current = snapshot.progress
  const previous = isSafeTopicId(topicId) ? current[topicId] : undefined
  const hasReadMain = typeof patch?.readMain === 'boolean'
  const hasRanLab = typeof patch?.ranLab === 'boolean'
  if (!hasReadMain && !hasRanLab) {
    return previous ?? emptyTopicProgress()
  }

  const next: TopicProgress = {
    readMain: hasReadMain ? patch.readMain as boolean : previous?.readMain ?? false,
    ranLab: hasRanLab ? patch.ranLab as boolean : previous?.ranLab ?? false,
    updatedAt: new Date().toISOString()
  }

  if (!isSafeTopicId(topicId)) {
    return next
  }

  current[topicId] = next
  if (!snapshot.canPersist || snapshot.storage === null) {
    return next
  }

  try {
    snapshot.storage.setItem(STORAGE_KEY, JSON.stringify(current))
  } catch {
    // localStorage 可能因禁用、配额或安全策略失败；进度 UI 仍使用本次返回值继续工作。
  }
  return next
}
