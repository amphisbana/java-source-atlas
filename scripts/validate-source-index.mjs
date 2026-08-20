#!/usr/bin/env node

import { access, readdir, readFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(process.argv[2] ?? resolve(scriptDirectory, '..'))
const sourceIndexRoot = resolve(projectRoot, 'source-index')
const docsRoot = resolve(projectRoot, 'docs')
const schemaPath = resolve(sourceIndexRoot, 'schema.json')
const baselinesPath = resolve(sourceIndexRoot, 'baselines.json')

const annotationKeywords = new Set([
  '$schema',
  '$id',
  '$comment',
  'title',
  'description',
  'default',
  'examples',
  'deprecated',
  'readOnly',
  'writeOnly'
])

const validationKeywords = new Set([
  'type',
  'required',
  'properties',
  'items',
  'const',
  'minLength',
  'minItems',
  'format',
  'uniqueItems'
])

/**
 * 递归收集目录中的 JSON 文件，并保持稳定顺序，保证本地与 CI 输出一致。
 *
 * @param {string} directory 待扫描目录
 * @returns {Promise<string[]>} JSON 文件绝对路径
 */
async function collectJsonFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []

  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const entryPath = resolve(directory, entry.name)
    if (entry.isDirectory()) {
      files.push(...await collectJsonFiles(entryPath))
    } else if (entry.isFile() && entry.name.endsWith('.json')) {
      files.push(entryPath)
    }
  }

  return files
}

/**
 * 递归收集 Markdown 文件，用于检查文档中的上游源码地址是否仍指向固定版本。
 *
 * @param {string} directory 待扫描目录
 * @returns {Promise<string[]>} Markdown 文件绝对路径
 */
async function collectMarkdownFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []

  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const entryPath = resolve(directory, entry.name)
    if (entry.isDirectory()) {
      files.push(...await collectMarkdownFiles(entryPath))
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      files.push(entryPath)
    }
  }

  return files
}

/**
 * 将路径片段转义为 JSON Pointer，便于错误精确指向字段。
 *
 * @param {string | number} segment 路径片段
 * @returns {string} 转义后的片段
 */
function escapeJsonPointer(segment) {
  return String(segment).replaceAll('~', '~0').replaceAll('/', '~1')
}

/**
 * 判断运行时值是否符合 JSON Schema 的 type 约束。
 *
 * @param {unknown} value 待检查值
 * @param {string | string[]} expectedType schema 声明的类型
 * @returns {boolean} 是否匹配
 */
function matchesType(value, expectedType) {
  const expectedTypes = Array.isArray(expectedType) ? expectedType : [expectedType]

  return expectedTypes.some((type) => {
    switch (type) {
      case 'null':
        return value === null
      case 'array':
        return Array.isArray(value)
      case 'object':
        return value !== null && typeof value === 'object' && !Array.isArray(value)
      case 'integer':
        return typeof value === 'number' && Number.isInteger(value)
      case 'number':
        return typeof value === 'number' && Number.isFinite(value)
      case 'string':
      case 'boolean':
        return typeof value === type
      default:
        throw new Error(`schema 使用了尚未支持的 type: ${type}`)
    }
  })
}

/**
 * 深度比较两个 JSON 值，用于 const 与 uniqueItems 校验。
 *
 * @param {unknown} left 左值
 * @param {unknown} right 右值
 * @returns {boolean} 两个 JSON 值是否相同
 */
function jsonEquals(left, right) {
  if (Object.is(left, right)) {
    return true
  }

  if (Array.isArray(left) && Array.isArray(right)) {
    return left.length === right.length
      && left.every((item, index) => jsonEquals(item, right[index]))
  }

  if (
    left !== null
    && right !== null
    && typeof left === 'object'
    && typeof right === 'object'
    && !Array.isArray(left)
    && !Array.isArray(right)
  ) {
    const leftKeys = Object.keys(left).sort()
    const rightKeys = Object.keys(right).sort()
    return jsonEquals(leftKeys, rightKeys)
      && leftKeys.every((key) => jsonEquals(left[key], right[key]))
  }

  return false
}

/**
 * 校验 schema 中使用的 format；未知格式直接失败，避免悄悄跳过约束。
 *
 * @param {string} format schema 格式名
 * @param {string} value 待检查字符串
 * @returns {boolean} 是否符合格式
 */
function matchesFormat(format, value) {
  if (format !== 'uri') {
    throw new Error(`schema 使用了尚未支持的 format: ${format}`)
  }

  try {
    const url = new URL(value)
    return Boolean(url.protocol)
  } catch {
    return false
  }
}

/**
 * 预检查整份 schema，只允许脚本已实现的校验关键字。
 * schema 扩展后若出现新约束，CI 会明确失败，避免产生“看似通过但未校验”的结果。
 *
 * @param {Record<string, unknown>} schema 当前 schema 节点
 * @param {string} schemaPointer schema 节点位置
 */
function assertSupportedSchema(schema, schemaPointer = '#') {
  for (const keyword of Object.keys(schema)) {
    if (!annotationKeywords.has(keyword) && !validationKeywords.has(keyword)) {
      throw new Error(`${schemaPointer} 使用了尚未实现的 JSON Schema 关键字: ${keyword}`)
    }
  }

  if (schema.properties && typeof schema.properties === 'object') {
    for (const [propertyName, propertySchema] of Object.entries(schema.properties)) {
      assertSupportedSchema(propertySchema, `${schemaPointer}/properties/${escapeJsonPointer(propertyName)}`)
    }
  }

  if (schema.items && typeof schema.items === 'object' && !Array.isArray(schema.items)) {
    assertSupportedSchema(schema.items, `${schemaPointer}/items`)
  }
}

/**
 * 按 schema 节点递归校验一个 JSON 值，并将全部问题追加到 errors。
 *
 * @param {Record<string, unknown>} schema 当前 schema 节点
 * @param {unknown} value 当前数据值
 * @param {string} pointer 当前数据的 JSON Pointer
 * @param {string[]} errors 错误收集器
 */
function validateValue(schema, value, pointer, errors) {
  if (schema.type !== undefined && !matchesType(value, schema.type)) {
    const expected = Array.isArray(schema.type) ? schema.type.join(' | ') : schema.type
    errors.push(`${pointer}: 类型应为 ${expected}`)
    return
  }

  if (schema.const !== undefined && !jsonEquals(value, schema.const)) {
    errors.push(`${pointer}: 值必须等于 ${JSON.stringify(schema.const)}`)
  }

  if (typeof value === 'string') {
    if (schema.minLength !== undefined && [...value].length < schema.minLength) {
      errors.push(`${pointer}: 字符串长度不能小于 ${schema.minLength}`)
    }
    if (schema.format !== undefined && !matchesFormat(schema.format, value)) {
      errors.push(`${pointer}: 不符合 ${schema.format} 格式`)
    }
  }

  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) {
      errors.push(`${pointer}: 数组元素数量不能小于 ${schema.minItems}`)
    }

    if (schema.uniqueItems === true) {
      for (let left = 0; left < value.length; left += 1) {
        for (let right = left + 1; right < value.length; right += 1) {
          if (jsonEquals(value[left], value[right])) {
            errors.push(`${pointer}/${right}: 与 ${pointer}/${left} 重复`)
          }
        }
      }
    }

    if (schema.items && typeof schema.items === 'object') {
      value.forEach((item, index) => {
        validateValue(schema.items, item, `${pointer}/${index}`, errors)
      })
    }
  }

  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    if (Array.isArray(schema.required)) {
      for (const propertyName of schema.required) {
        if (!Object.hasOwn(value, propertyName)) {
          errors.push(`${pointer}/${escapeJsonPointer(propertyName)}: 缺少必填字段`)
        }
      }
    }

    if (schema.properties && typeof schema.properties === 'object') {
      for (const [propertyName, propertySchema] of Object.entries(schema.properties)) {
        if (Object.hasOwn(value, propertyName)) {
          validateValue(
            propertySchema,
            value[propertyName],
            `${pointer}/${escapeJsonPointer(propertyName)}`,
            errors
          )
        }
      }
    }
  }
}

/**
 * 读取并解析 JSON 文件，给解析错误补充相对路径上下文。
 *
 * @param {string} filePath JSON 文件绝对路径
 * @returns {Promise<unknown>} 解析后的 JSON 值
 */
async function readJson(filePath) {
  const displayPath = relative(projectRoot, filePath)
  try {
    return JSON.parse(await readFile(filePath, 'utf8'))
  } catch (error) {
    throw new Error(`${displayPath}: JSON 解析失败: ${error.message}`)
  }
}

/**
 * 登记专题编号并检查跨文件重复，避免 Vue key、筛选值和学习进度互相覆盖。
 *
 * @param {unknown} document 当前索引文档
 * @param {string} indexFile 当前索引文件
 * @param {Map<string, string>} topicOwners 已登记的专题编号与文件
 * @param {string[]} failures 错误收集器
 */
function validateUniqueTopicId(document, indexFile, topicOwners, failures) {
  if (document === null || typeof document !== 'object' || Array.isArray(document)) {
    return
  }

  const topicId = document.topicId
  if (typeof topicId !== 'string' || topicId.trim() === '') {
    return
  }

  const displayPath = relative(projectRoot, indexFile)
  const previousOwner = topicOwners.get(topicId)
  if (previousOwner !== undefined) {
    failures.push(`${displayPath} #/topicId: ${topicId} 与 ${previousOwner} 重复`)
    return
  }
  topicOwners.set(topicId, displayPath)
}

/**
 * 校验下一专题关系的字段是否成对出现，并收集待解析的目标编号。
 * 目标是否存在要等全部索引登记完成后再判断，避免依赖文件扫描顺序。
 *
 * @param {unknown} document 当前索引文档
 * @param {string} indexFile 当前索引文件
 * @param {Array<{ topicId: string, indexFile: string, targetId: string }>} references 待解析关系
 * @param {string[]} failures 错误收集器
 */
function collectRecommendedNextReference(document, indexFile, references, failures) {
  if (document === null || typeof document !== 'object' || Array.isArray(document)) {
    return
  }

  const targetId = document.recommendedNextTopicId
  const reason = document.recommendedNextReason
  const hasTarget = targetId !== undefined
  const hasReason = reason !== undefined
  const displayPath = relative(projectRoot, indexFile)

  if (hasTarget !== hasReason) {
    failures.push(`${displayPath} #/recommendedNextTopicId 与 #/recommendedNextReason 必须同时出现`)
  }
  if (hasTarget && typeof targetId === 'string' && targetId.trim() !== '') {
    references.push({
      topicId: typeof document.topicId === 'string' ? document.topicId : displayPath,
      indexFile: displayPath,
      targetId
    })
  }
}

/**
 * 确认所有推荐目标都指向现有专题，防止页面出现失效的下一步链接。
 *
 * @param {Array<{ topicId: string, indexFile: string, targetId: string }>} references 待解析关系
 * @param {Map<string, string>} topicOwners 已登记的专题编号与文件
 * @param {string[]} failures 错误收集器
 */
function validateRecommendedNextTargets(references, topicOwners, failures) {
  references.forEach(({ topicId, indexFile, targetId }) => {
    if (!topicOwners.has(targetId)) {
      failures.push(`${indexFile} #/recommendedNextTopicId: ${topicId} 指向不存在的专题 ${targetId}`)
    }
  })
}

/**
 * 校验入口和断点显式声明的源码类确实存在于专题源码清单中。
 * sourceClass 为 null 表示教学 Lab 方法，不参与远端源码定位。
 *
 * @param {unknown} document 当前索引文档
 * @param {string} indexFile 当前索引文件
 * @param {string[]} failures 错误收集器
 */
function validateSourceClassReferences(document, indexFile, failures) {
  if (document === null || typeof document !== 'object' || Array.isArray(document)) {
    return
  }

  const sourceClasses = new Set()
  if (document.source && typeof document.source.className === 'string') {
    sourceClasses.add(document.source.className)
  }
  if (Array.isArray(document.relatedSources)) {
    document.relatedSources.forEach((source) => {
      if (source && typeof source.className === 'string') {
        sourceClasses.add(source.className)
      }
    })
  }

  const displayPath = relative(projectRoot, indexFile)
  for (const collectionName of ['entryPoints', 'breakpoints']) {
    const entries = document[collectionName]
    if (!Array.isArray(entries)) {
      continue
    }
    entries.forEach((entry, index) => {
      if (entry && typeof entry.sourceClass === 'string' && !sourceClasses.has(entry.sourceClass)) {
        failures.push(
          `${displayPath} #/${collectionName}/${index}/sourceClass: ${entry.sourceClass} 不在专题源码清单中`
        )
      }
    })
  }
}

/**
 * 校验专题声明的 Lab 模块、主类与源码路径保持一致，并确认文件真实存在。
 *
 * @param {unknown} document 当前专题索引
 * @param {string} indexFile 当前索引文件
 * @param {string[]} failures 错误收集器
 */
async function validateLabMetadata(document, indexFile, failures) {
  if (document === null || typeof document !== 'object' || Array.isArray(document)) {
    return
  }

  const { lab } = document
  if (lab === null || typeof lab !== 'object' || Array.isArray(lab)) {
    return
  }

  const displayPath = relative(projectRoot, indexFile)
  if (typeof lab.module !== 'string' || typeof lab.mainClass !== 'string' || typeof lab.sourcePath !== 'string') {
    return
  }

  const expectedSourcePath = `${lab.module}/src/main/java/${lab.mainClass.replaceAll('.', '/')}.java`
  if (lab.sourcePath !== expectedSourcePath) {
    failures.push(
      `${displayPath} #/lab/sourcePath: 应与 mainClass 对应为 ${expectedSourcePath}`
    )
    return
  }

  try {
    await access(resolve(projectRoot, lab.sourcePath))
  } catch {
    failures.push(`${displayPath} #/lab/sourcePath: 文件不存在 ${lab.sourcePath}`)
  }
}

/**
 * 校验集中版本基线并建立“仓库 -> 允许 ref”查询表。
 * resolvedCommit 固定到 tag 解引用后的提交，便于后续同步本地源码时复核。
 *
 * @param {unknown} document 版本基线文档
 * @param {string[]} failures 错误收集器
 * @returns {Map<string, Set<string>>} 仓库及其允许的固定 ref
 */
function validateBaselines(document, failures) {
  const repositoryRefs = new Map()
  const baselineIds = new Set()

  if (
    document === null
    || typeof document !== 'object'
    || Array.isArray(document)
    || document.schemaVersion !== 1
    || !Array.isArray(document.baselines)
  ) {
    failures.push('source-index/baselines.json: 必须包含 schemaVersion=1 与 baselines 数组')
    return repositoryRefs
  }

  document.baselines.forEach((baseline, index) => {
    const pointer = `source-index/baselines.json #/baselines/${index}`
    if (baseline === null || typeof baseline !== 'object' || Array.isArray(baseline)) {
      failures.push(`${pointer}: 必须是对象`)
      return
    }

    const { id, repository, sourceRef, resolvedCommit } = baseline
    if (typeof id !== 'string' || id.trim() === '') {
      failures.push(`${pointer}/id: 必须是非空字符串`)
    } else if (baselineIds.has(id)) {
      failures.push(`${pointer}/id: ${id} 重复`)
    } else {
      baselineIds.add(id)
    }

    if (typeof repository !== 'string' || !repository.startsWith('https://github.com/')) {
      failures.push(`${pointer}/repository: 必须是 GitHub 仓库地址`)
      return
    }
    if (typeof sourceRef !== 'string' || sourceRef.trim() === '' || ['main', 'master'].includes(sourceRef)) {
      failures.push(`${pointer}/sourceRef: 必须是固定 tag，不能使用默认分支`)
      return
    }
    if (typeof resolvedCommit !== 'string' || !/^[0-9a-f]{40}$/.test(resolvedCommit)) {
      failures.push(`${pointer}/resolvedCommit: 必须是 40 位小写提交哈希`)
    }

    const normalizedRepository = repository.replace(/\/$/, '')
    const refs = repositoryRefs.get(normalizedRepository) ?? new Set()
    refs.add(sourceRef)
    repositoryRefs.set(normalizedRepository, refs)
  })

  return repositoryRefs
}

/**
 * 校验专题索引使用的仓库与 ref 已登记在集中版本基线中。
 *
 * @param {unknown} document 当前专题索引
 * @param {string} indexFile 当前索引文件
 * @param {Map<string, Set<string>>} repositoryRefs 已登记版本
 * @param {string[]} failures 错误收集器
 */
function validateTopicBaseline(document, indexFile, repositoryRefs, failures) {
  if (document === null || typeof document !== 'object' || Array.isArray(document)) {
    return
  }

  const repository = typeof document.source?.repository === 'string'
    ? document.source.repository.replace(/\/$/, '')
    : ''
  const sourceRef = document.sourceRef
  const allowedRefs = repositoryRefs.get(repository)
  const displayPath = relative(projectRoot, indexFile)

  if (allowedRefs === undefined) {
    failures.push(`${displayPath} #/source/repository: ${repository || '(空)'} 未登记版本基线`)
  } else if (!allowedRefs.has(sourceRef)) {
    failures.push(`${displayPath} #/sourceRef: ${sourceRef} 不在仓库固定版本清单中`)
  }
}

/**
 * 扫描 Markdown 中的 GitHub blob 地址，拒绝默认分支以及已登记仓库的未知 ref。
 *
 * @param {Map<string, Set<string>>} repositoryRefs 已登记版本
 * @param {string[]} failures 错误收集器
 * @returns {Promise<number>} 已检查 Markdown 文件数量
 */
async function validateMarkdownSourceLinks(repositoryRefs, failures) {
  const markdownFiles = await collectMarkdownFiles(docsRoot)
  const sourceLinkPattern = /https:\/\/github\.com\/([^/\s)]+\/[^/\s)]+)\/blob\/([^/\s)#]+)\//g

  for (const markdownFile of markdownFiles) {
    const displayPath = relative(projectRoot, markdownFile)
    const lines = (await readFile(markdownFile, 'utf8')).split(/\r?\n/)
    lines.forEach((line, lineIndex) => {
      for (const match of line.matchAll(sourceLinkPattern)) {
        const repository = `https://github.com/${match[1]}`
        const sourceRef = decodeURIComponent(match[2])
        if (['main', 'master'].includes(sourceRef)) {
          failures.push(`${displayPath}:${lineIndex + 1}: 源码链接不能使用默认分支 ${sourceRef}`)
          continue
        }

        const allowedRefs = repositoryRefs.get(repository)
        if (allowedRefs !== undefined && !allowedRefs.has(sourceRef)) {
          failures.push(`${displayPath}:${lineIndex + 1}: ${repository} 未登记固定版本 ${sourceRef}`)
        }
      }
    })
  }

  return markdownFiles.length
}

const schema = await readJson(schemaPath)
assertSupportedSchema(schema)

const failures = []
const baselines = await readJson(baselinesPath)
const repositoryRefs = validateBaselines(baselines, failures)

const indexFiles = (await collectJsonFiles(sourceIndexRoot))
  .filter((filePath) => filePath !== schemaPath && filePath !== baselinesPath)

const topicOwners = new Map()
const recommendedReferences = []

if (indexFiles.length === 0) {
  failures.push('source-index: 至少需要一个专题索引')
}

for (const indexFile of indexFiles) {
  const document = await readJson(indexFile)
  const errors = []
  validateValue(schema, document, '#', errors)
  validateUniqueTopicId(document, indexFile, topicOwners, failures)
  collectRecommendedNextReference(document, indexFile, recommendedReferences, failures)
  validateSourceClassReferences(document, indexFile, failures)
  await validateLabMetadata(document, indexFile, failures)
  validateTopicBaseline(document, indexFile, repositoryRefs, failures)

  if (errors.length > 0) {
    const displayPath = relative(projectRoot, indexFile)
    failures.push(...errors.map((error) => `${displayPath} ${error}`))
  }
}

validateRecommendedNextTargets(recommendedReferences, topicOwners, failures)

const markdownFileCount = await validateMarkdownSourceLinks(repositoryRefs, failures)

if (failures.length > 0) {
  console.error(`source-index 校验失败，共 ${failures.length} 个问题：`)
  failures.forEach((failure) => console.error(`- ${failure}`))
  process.exitCode = 1
} else {
  console.log(
    `source-index 校验通过：${indexFiles.length} 个专题索引、${baselines.baselines.length} 个固定版本、${markdownFileCount} 篇 Markdown`
  )
}
