export type SourcePlatform = 'jdk' | 'spring-framework' | 'spring-boot'

export interface SourceLocation {
  className: string
  sourcePath: string
}

export interface SourceEntryPoint {
  method: string
  document: string
  purpose: string
  sourceClass?: string | null
}

export interface SourceBreakpoint {
  method: string
  scenario: string
  variables: string[]
  sourceClass?: string | null
}

export interface SourceVersionComparison {
  id: string
  summary: string
  supportedVersions: string[]
  migrationHint: string
}

export interface SourceTopic {
  schemaVersion: 1
  topicId: string
  title: string
  primaryVersion: string
  compatibleVersions: string[]
  designInsight?: string
  focusQuestion?: string
  readingGoal?: string
  recommendedNextTopicId?: string
  recommendedNextReason?: string
  versionComparison?: SourceVersionComparison
  platform: SourcePlatform
  platformLabel: string
  repository: string
  sourceRef: string
  source: SourceLocation
  relatedSources: SourceLocation[]
  entryPoints: SourceEntryPoint[]
  breakpoints: SourceBreakpoint[]
}

export interface SourceExplorerOption {
  value: string
  label: string
}

export interface SourceExplorerStats {
  topicCount: number
  sourceCount: number
  entryPointCount: number
  breakpointCount: number
}

interface RawSourceTopic {
  schemaVersion: 1
  topicId: string
  title: string
  primaryVersion: string
  sourceRef: string
  designInsight?: string
  focusQuestion?: string
  readingGoal?: string
  recommendedNextTopicId?: string
  recommendedNextReason?: string
  versionComparison?: SourceVersionComparison
  compatibleVersions?: string[]
  source: SourceLocation & { repository: string }
  relatedSources?: SourceLocation[]
  entryPoints: SourceEntryPoint[]
  breakpoints?: SourceBreakpoint[]
}

interface PlatformMetadata {
  platform: SourcePlatform
  platformLabel: string
  order: number
}

const PLATFORM_METADATA: Record<SourcePlatform, PlatformMetadata> = {
  jdk: {
    platform: 'jdk',
    platformLabel: 'JDK',
    order: 0
  },
  'spring-framework': {
    platform: 'spring-framework',
    platformLabel: 'Spring Framework',
    order: 1
  },
  'spring-boot': {
    platform: 'spring-boot',
    platformLabel: 'Spring Boot',
    order: 2
  }
}

const PLATFORM_BY_DIRECTORY: Record<string, SourcePlatform> = {
  jdk8: 'jdk',
  spring5: 'spring-framework',
  'spring-boot-2.7': 'spring-boot'
}

const topicModules = import.meta.glob('../../../source-index/*/*.json', {
  eager: true,
  import: 'default'
}) as Record<string, RawSourceTopic>

/**
 * 根据索引文件所在目录识别平台，并返回平台固定源码版本。
 */
function platformMetadataFor(modulePath: string): PlatformMetadata {
  const directory = modulePath.match(/source-index\/([^/]+)\//)?.[1]
  const platform = directory === undefined ? undefined : PLATFORM_BY_DIRECTORY[directory]
  if (platform === undefined) {
    throw new Error(`无法识别源码索引平台目录: ${modulePath}`)
  }
  return PLATFORM_METADATA[platform]
}

/**
 * 把一份原始 JSON 索引转换成组件可直接消费的稳定结构。
 */
function normalizeTopic(modulePath: string, rawTopic: RawSourceTopic): SourceTopic {
  const platformMetadata = platformMetadataFor(modulePath)
  const { repository, ...source } = rawTopic.source

  return {
    schemaVersion: rawTopic.schemaVersion,
    topicId: rawTopic.topicId,
    title: rawTopic.title,
    primaryVersion: rawTopic.primaryVersion,
    compatibleVersions: rawTopic.compatibleVersions ?? [],
    designInsight: rawTopic.designInsight,
    focusQuestion: rawTopic.focusQuestion,
    readingGoal: rawTopic.readingGoal,
    recommendedNextTopicId: rawTopic.recommendedNextTopicId,
    recommendedNextReason: rawTopic.recommendedNextReason,
    versionComparison: rawTopic.versionComparison,
    platform: platformMetadata.platform,
    platformLabel: platformMetadata.platformLabel,
    repository: repository.replace(/\/$/, ''),
    sourceRef: rawTopic.sourceRef,
    source,
    relatedSources: rawTopic.relatedSources ?? [],
    entryPoints: rawTopic.entryPoints,
    breakpoints: rawTopic.breakpoints ?? []
  }
}

/**
 * 按平台和专题名称排序，保证不同构建环境中的展示顺序一致。
 */
function compareTopics(left: SourceTopic, right: SourceTopic): number {
  const platformDifference = PLATFORM_METADATA[left.platform].order
    - PLATFORM_METADATA[right.platform].order
  if (platformDifference !== 0) {
    return platformDifference
  }
  return left.title.localeCompare(right.title, 'zh-CN')
}

/**
 * 对 GitHub 路径逐段编码，同时保留目录分隔符。
 */
function encodeSourcePath(sourcePath: string): string {
  return sourcePath.split('/').map((segment) => encodeURIComponent(segment)).join('/')
}

/**
 * 提取方法签名中的类名部分；未写类名的方法返回空字符串。
 */
function methodOwner(method: string, sources: SourceLocation[]): string {
  const firstSignature = method.trim().split(/\s+\/\s+|\//, 1)[0]
  const signatureHead = firstSignature.split('(', 1)[0].trim()
  const lastDot = signatureHead.lastIndexOf('.')

  if (lastDot >= 0) {
    return signatureHead.slice(0, lastDot)
  }

  const constructorSource = sources.find((source) => {
    const simpleName = source.className.split('.').pop()
    return simpleName === signatureHead
  })
  return constructorSource ? signatureHead : ''
}

/**
 * 计算方法所属类与索引源码类的匹配程度，优先精确类名和外部类前缀。
 */
function sourceMatchScore(source: SourceLocation, owner: string): number {
  const simpleName = source.className.split('.').pop() ?? source.className
  if (owner === source.className || owner === simpleName) {
    return 100
  }
  if (owner.startsWith(`${source.className}.`) || owner.startsWith(`${simpleName}.`)) {
    return 80
  }
  return 0
}

/**
 * 用仓库、固定标签和源码路径组成稳定标识，避免跨专题重复统计同一文件。
 */
function sourceIdentity(topic: SourceTopic, source: SourceLocation): string {
  return `${topic.repository}\u0000${topic.sourceRef}\u0000${source.sourcePath}`
}

export const sourceTopics: SourceTopic[] = Object.entries(topicModules)
  .map(([modulePath, rawTopic]) => normalizeTopic(modulePath, rawTopic))
  .sort(compareTopics)

export const platformOptions: SourceExplorerOption[] = Object.values(PLATFORM_METADATA)
  .sort((left, right) => left.order - right.order)
  .map(({ platform, platformLabel }) => ({ value: platform, label: platformLabel }))

export const versionOptions: SourceExplorerOption[] = Array.from(
  new Set(sourceTopics.flatMap((topic) => [topic.primaryVersion, ...topic.compatibleVersions]))
).map((version) => ({ value: version, label: version }))

const uniqueSourceIdentities = new Set(sourceTopics.flatMap((topic) => (
  [topic.source, ...topic.relatedSources].map((source) => sourceIdentity(topic, source))
)))

export const sourceExplorerStats: SourceExplorerStats = {
  topicCount: sourceTopics.length,
  sourceCount: uniqueSourceIdentities.size,
  entryPointCount: sourceTopics.reduce((total, topic) => total + topic.entryPoints.length, 0),
  breakpointCount: sourceTopics.reduce((total, topic) => total + topic.breakpoints.length, 0)
}

/**
 * 生成带固定 tag 的 GitHub 源码地址，避免默认分支变化导致讲解漂移。
 */
export function githubSourceUrl(topic: SourceTopic, source: SourceLocation): string {
  return `${topic.repository}/blob/${encodeURIComponent(topic.sourceRef)}/${encodeSourcePath(source.sourcePath)}`
}

/**
 * 从首个入口文档推导专题首页，先去掉锚点，再去掉具体章节文件名。
 */
export function topicHomeUrl(topic: SourceTopic): string {
  const firstDocument = topic.entryPoints[0]?.document.split('#', 1)[0] ?? '/'
  if (firstDocument.endsWith('/')) {
    return firstDocument
  }
  const lastSlash = firstDocument.lastIndexOf('/')
  return lastSlash >= 0 ? firstDocument.slice(0, lastSlash + 1) : '/'
}

/**
 * 根据统一目录约定生成该专题的可运行实验文档地址。
 */
export function topicLabUrl(topic: SourceTopic): string {
  return `${topicHomeUrl(topic)}debug-lab`
}

/**
 * 根据方法签名中的类名，在主源码和关联源码中找到最可能的源码文件。
 */
export function findSourceForMethod(
  topic: SourceTopic,
  method: string,
  sourceClass?: string | null
): SourceLocation | undefined {
  const sources = [topic.source, ...topic.relatedSources]
  if (sourceClass === null) {
    return undefined
  }
  if (sourceClass !== undefined) {
    return sources.find((source) => source.className === sourceClass)
  }

  const owner = methodOwner(method, sources)
  if (!owner) {
    return topic.source
  }

  const matchedSource = sources
    .map((source) => ({ source, score: sourceMatchScore(source, owner) }))
    .sort((left, right) => right.score - left.score)
    .find((candidate) => candidate.score > 0)

  // 2026-08-16：旧逻辑在无法匹配类名时返回 undefined，导致未写类名前缀的真实源码入口大量缺少链接。
  // return matchedSource?.source
  // 索引约定未显式声明 sourceClass 的入口属于专题主源码；Lab 方法必须用 sourceClass: null 标明。
  return matchedSource?.source ?? topic.source
}
