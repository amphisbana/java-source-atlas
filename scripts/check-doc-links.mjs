#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises'
import { dirname, extname, posix, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(process.argv[2] ?? resolve(scriptDirectory, '..'))
const docsRoot = resolve(projectRoot, 'docs')
const sourceIndexRoot = resolve(projectRoot, 'source-index')
const vitePressConfigPath = resolve(docsRoot, '.vitepress/config.mts')

const controlCharacters = /[\u0000-\u001f]/g
const combiningCharacters = /[\u0300-\u036f]/g
const specialCharacters = /[\s~`!@#$%^&*()\-_+=[\]{}|\\;:"'“”‘’<>,.?/]+/g

/**
 * 递归收集目录中的普通文件，并保持稳定顺序。
 *
 * @param {string} directory 待扫描目录
 * @returns {Promise<string[]>} 文件绝对路径
 */
async function collectFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []

  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const entryPath = resolve(directory, entry.name)
    if (entry.isDirectory()) {
      files.push(...await collectFiles(entryPath))
    } else if (entry.isFile()) {
      files.push(entryPath)
    }
  }

  return files
}

/**
 * 将系统路径转换为相对 docs 的 POSIX 路径，确保 Linux CI 与 macOS 结果一致。
 *
 * @param {string} filePath 文件绝对路径
 * @returns {string} POSIX 相对路径
 */
function toDocsRelativePath(filePath) {
  return relative(docsRoot, filePath).replaceAll('\\', '/')
}

/**
 * 判断字符是否被奇数个反斜杠转义。
 *
 * @param {string} text 当前文本
 * @param {number} index 字符位置
 * @returns {boolean} 是否被转义
 */
function isEscaped(text, index) {
  let slashCount = 0
  for (let cursor = index - 1; cursor >= 0 && text[cursor] === '\\'; cursor -= 1) {
    slashCount += 1
  }
  return slashCount % 2 === 1
}

/**
 * 用等长空格屏蔽行内代码，防止把示例代码里的链接语法当成真实链接。
 * 保持字符串长度不变，后续才能把跨行链接偏移量还原为准确行号。
 *
 * @param {string} line Markdown 单行文本
 * @returns {string} 已屏蔽行内代码的文本
 */
function maskInlineCode(line) {
  return line.replace(/(`+)(.*?)\1/g, (code) => ' '.repeat(code.length))
}

/**
 * 从已屏蔽代码的 Markdown 中提取内联链接或图片目标。
 * 扫描范围可以跨行，但未使用尖括号包裹的目标仍按 CommonMark 规则在首个空白处结束。
 *
 * @param {string} markdown Markdown 可扫描文本
 * @returns {Array<{ href: string, offset: number }>} 链接目标及起始偏移量
 */
function extractMarkdownDestinations(markdown) {
  const destinations = []

  for (let cursor = 0; cursor < markdown.length - 1; cursor += 1) {
    if (markdown[cursor] !== ']' || markdown[cursor + 1] !== '(' || isEscaped(markdown, cursor)) {
      continue
    }

    const openingBracket = markdown.lastIndexOf('[', cursor)
    if (openingBracket < 0 || isEscaped(markdown, openingBracket)) {
      continue
    }

    let destinationStart = cursor + 2
    while (/\s/.test(markdown[destinationStart] ?? '')) {
      destinationStart += 1
    }

    if (markdown[destinationStart] === '<') {
      const closingAngle = markdown.indexOf('>', destinationStart + 1)
      if (closingAngle > destinationStart + 1) {
        destinations.push({
          href: markdown.slice(destinationStart + 1, closingAngle),
          offset: cursor
        })
        cursor = closingAngle
      }
      continue
    }

    let nestedParentheses = 0
    let destinationEnd = destinationStart
    for (; destinationEnd < markdown.length; destinationEnd += 1) {
      const character = markdown[destinationEnd]
      if (character === '\\' && destinationEnd + 1 < markdown.length) {
        destinationEnd += 1
        continue
      }
      if (character === '(') {
        nestedParentheses += 1
        continue
      }
      if (character === ')' && nestedParentheses > 0) {
        nestedParentheses -= 1
        continue
      }
      if ((character === ')' || /\s/.test(character)) && nestedParentheses === 0) {
        break
      }
    }

    const destination = markdown.slice(destinationStart, destinationEnd).replace(/\\([()])/g, '$1')
    if (destination) {
      destinations.push({ href: destination, offset: cursor })
    }
    cursor = destinationEnd
  }

  return destinations
}

/**
 * 根据字符偏移量计算一基行号，供跨行 Markdown 和配置文件错误定位使用。
 *
 * @param {string} content 完整文件内容
 * @param {number} offset 字符偏移量
 * @returns {number} 一基行号
 */
function lineNumberAt(content, offset) {
  return content.slice(0, offset).split(/\r?\n/).length
}

/**
 * 屏蔽 TypeScript 配置中的行注释与块注释，同时保留字符串和换行位置。
 * 该函数不执行配置，只为后续限定范围的静态属性扫描提供稳定输入。
 *
 * @param {string} content TypeScript 配置原文
 * @returns {string} 与原文等长的去注释文本
 */
function maskTypeScriptComments(content) {
  const characters = [...content]
  let quote = ''

  for (let cursor = 0; cursor < characters.length; cursor += 1) {
    const character = characters[cursor]

    if (quote) {
      if (character === '\\') {
        cursor += 1
      } else if (character === quote) {
        quote = ''
      }
      continue
    }

    if (character === '"' || character === "'" || character === '`') {
      quote = character
      continue
    }

    if (character === '/' && characters[cursor + 1] === '/') {
      characters[cursor] = ' '
      characters[cursor + 1] = ' '
      cursor += 2
      while (cursor < characters.length && characters[cursor] !== '\n' && characters[cursor] !== '\r') {
        characters[cursor] = ' '
        cursor += 1
      }
      cursor -= 1
      continue
    }

    if (character === '/' && characters[cursor + 1] === '*') {
      characters[cursor] = ' '
      characters[cursor + 1] = ' '
      cursor += 2
      while (cursor < characters.length - 1) {
        if (characters[cursor] === '*' && characters[cursor + 1] === '/') {
          characters[cursor] = ' '
          characters[cursor + 1] = ' '
          cursor += 1
          break
        }
        if (characters[cursor] !== '\n' && characters[cursor] !== '\r') {
          characters[cursor] = ' '
        }
        cursor += 1
      }
    }
  }

  return characters.join('')
}

/**
 * 在忽略字符串内容的前提下找到配对结束符，用于限定 defineConfig 调用范围。
 *
 * @param {string} content 已去除注释的配置文本
 * @param {number} openingIndex 起始分隔符位置
 * @param {string} openingCharacter 起始分隔符
 * @param {string} closingCharacter 结束分隔符
 * @returns {number} 配对结束符位置，未找到时返回 -1
 */
function findClosingDelimiter(content, openingIndex, openingCharacter, closingCharacter) {
  let depth = 0
  let quote = ''

  for (let cursor = openingIndex; cursor < content.length; cursor += 1) {
    const character = content[cursor]
    if (quote) {
      if (character === '\\') {
        cursor += 1
      } else if (character === quote) {
        quote = ''
      }
      continue
    }
    if (character === '"' || character === "'" || character === '`') {
      quote = character
      continue
    }
    if (character === openingCharacter) {
      depth += 1
    } else if (character === closingCharacter) {
      depth -= 1
      if (depth === 0) {
        return cursor
      }
    }
  }

  return -1
}

/**
 * 读取单引号或双引号字符串，不执行其中内容，仅解释常见反斜杠转义。
 *
 * @param {string} content 配置文本
 * @param {number} openingIndex 引号起始位置
 * @returns {{ value: string, end: number } | null} 字符串值与结束位置
 */
function readQuotedString(content, openingIndex) {
  const quote = content[openingIndex]
  if (quote !== '"' && quote !== "'") {
    return null
  }

  const escapeValues = new Map([
    ['n', '\n'],
    ['r', '\r'],
    ['t', '\t'],
    ['b', '\b'],
    ['f', '\f'],
    ['v', '\v']
  ])
  let value = ''

  for (let cursor = openingIndex + 1; cursor < content.length; cursor += 1) {
    const character = content[cursor]
    if (character === quote) {
      return { value, end: cursor }
    }
    if (character === '\\' && cursor + 1 < content.length) {
      const escapedCharacter = content[cursor + 1]
      value += escapeValues.get(escapedCharacter) ?? escapedCharacter
      cursor += 1
    } else {
      value += character
    }
  }

  return null
}

/**
 * 静态提取 defineConfig 内已知承载路由的字符串属性。
 * 只扫描 link/src/href/logo/light/dark，避免把普通说明字符串误判为站点路径。
 *
 * @param {string} content VitePress 配置原文
 * @returns {Array<{ href: string, line: number }>} 配置中的本地或外部引用
 */
function extractVitePressConfigReferences(content) {
  const maskedContent = maskTypeScriptComments(content)
  const callMatch = /\bdefineConfig\s*\(/g.exec(maskedContent)
  if (!callMatch) {
    throw new Error('docs/.vitepress/config.mts 中找不到 defineConfig(...)')
  }

  const openingIndex = callMatch.index + callMatch[0].lastIndexOf('(')
  const closingIndex = findClosingDelimiter(maskedContent, openingIndex, '(', ')')
  if (closingIndex < 0) {
    throw new Error('docs/.vitepress/config.mts 中 defineConfig(...) 未闭合')
  }

  const scope = maskedContent.slice(openingIndex + 1, closingIndex)
  const scopeOffset = openingIndex + 1
  const references = []
  const propertyPattern = /\b(link|src|href|logo|light|dark)\s*:\s*/g

  for (const match of scope.matchAll(propertyPattern)) {
    const valueStart = match.index + match[0].length
    const parsedString = readQuotedString(scope, valueStart)
    if (parsedString) {
      references.push({
        href: parsedString.value,
        line: lineNumberAt(content, scopeOffset + valueStart)
      })
    }
  }

  const uniqueReferences = new Map()
  for (const reference of references) {
    uniqueReferences.set(`${reference.line}:${reference.href}`, reference)
  }
  return [...uniqueReferences.values()]
}

/**
 * 解码标题中的常见 HTML 实体，使锚点文本与 Markdown 渲染结果一致。
 *
 * @param {string} text 标题文本
 * @returns {string} 解码后的文本
 */
function decodeHtmlEntities(text) {
  const namedEntities = new Map([
    ['amp', '&'],
    ['lt', '<'],
    ['gt', '>'],
    ['quot', '"'],
    ['apos', "'"]
  ])

  return text.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (entity, name) => {
    if (name.startsWith('#x') || name.startsWith('#X')) {
      return String.fromCodePoint(Number.parseInt(name.slice(2), 16))
    }
    if (name.startsWith('#')) {
      return String.fromCodePoint(Number.parseInt(name.slice(1), 10))
    }
    return namedEntities.get(name.toLowerCase()) ?? entity
  })
}

/**
 * 提取 Markdown 标题的可见文本，保留代码内容与链接文案，移除标记本身。
 *
 * @param {string} heading Markdown 标题内容
 * @returns {string} 用于生成锚点的纯文本
 */
function headingToPlainText(heading) {
  return decodeHtmlEntities(heading)
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\[[^\]]*\]/g, '$1')
    .replace(/(`+)(.*?)\1/g, '$2')
    .replace(/<[^>]+>/g, '')
    .replace(/\\([\\`*_[\]{}()#+.!-])/g, '$1')
    .trim()
}

/**
 * 按 VitePress 1.6 使用的规则生成标题锚点。
 *
 * @param {string} text 标题纯文本
 * @returns {string} 基础锚点
 */
function slugify(text) {
  return text
    .normalize('NFKD')
    .replace(combiningCharacters, '')
    .replace(controlCharacters, '')
    .replace(specialCharacters, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
    .replace(/^(\d)/, '_$1')
    .toLowerCase()
}

/**
 * 为同页重复标题追加稳定序号，行为与 markdown-it-anchor 一致。
 *
 * @param {string} baseSlug 基础锚点
 * @param {Set<string>} usedSlugs 本页已使用锚点
 * @returns {string} 唯一锚点
 */
function uniqueSlug(baseSlug, usedSlugs) {
  if (!usedSlugs.has(baseSlug)) {
    usedSlugs.add(baseSlug)
    return baseSlug
  }

  let suffix = 1
  while (usedSlugs.has(`${baseSlug}-${suffix}`)) {
    suffix += 1
  }
  const result = `${baseSlug}-${suffix}`
  usedSlugs.add(result)
  return result
}

/**
 * 扫描一篇 Markdown，提取真实链接与 VitePress 可定位锚点。
 * 代码围栏和文件头会被跳过，避免示例文本造成误报。
 *
 * @param {string} content Markdown 正文
 * @returns {{ anchors: Set<string>, links: Array<{ href: string, line: number }> }} 扫描结果
 */
function scanMarkdown(content) {
  const lines = content.split('\n')
  const anchors = new Set()
  const links = []
  const visibleLines = []
  let inFrontmatter = lines[0]?.trim() === '---'
  let fenceCharacter = null
  let fenceLength = 0

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]

    if (inFrontmatter) {
      visibleLines.push(' '.repeat(line.length))
      if (index > 0 && line.trim() === '---') {
        inFrontmatter = false
      }
      continue
    }

    const fenceMatch = line.match(/^ {0,3}(`{3,}|~{3,})/)
    if (fenceMatch) {
      visibleLines.push(' '.repeat(line.length))
      const marker = fenceMatch[1]
      if (fenceCharacter === null) {
        fenceCharacter = marker[0]
        fenceLength = marker.length
      } else if (marker[0] === fenceCharacter && marker.length >= fenceLength) {
        fenceCharacter = null
        fenceLength = 0
      }
      continue
    }
    if (fenceCharacter !== null) {
      visibleLines.push(' '.repeat(line.length))
      continue
    }

    const headingMatch = line.match(/^ {0,3}#{1,6}(?:[ \t]+|$)(.*)$/)
    if (headingMatch) {
      const heading = headingMatch[1].replace(/[ \t]+#+[ \t]*$/, '')
      const anchor = uniqueSlug(slugify(headingToPlainText(heading)), anchors)
      anchors.add(anchor)
    }

    const visibleLine = maskInlineCode(line)
    visibleLines.push(visibleLine)

    const referenceDefinition = visibleLine.match(/^ {0,3}\[[^\]]+\]:\s*(?:<([^>]+)>|(\S+))/)
    if (referenceDefinition) {
      links.push({ href: referenceDefinition[1] ?? referenceDefinition[2], line: index + 1 })
    }

    for (const match of visibleLine.matchAll(/\b(?:href|src)\s*=\s*(["'])(.*?)\1/gi)) {
      links.push({ href: match[2], line: index + 1 })
    }

    for (const match of visibleLine.matchAll(/\b(?:id|name)\s*=\s*(["'])(.*?)\1/gi)) {
      anchors.add(match[2])
    }
  }

  // 等长屏蔽后的全文允许链接标签、左括号和目标分布在多行，同时保持原始偏移量。
  const visibleContent = visibleLines.join('\n')
  for (const destination of extractMarkdownDestinations(visibleContent)) {
    links.push({
      href: destination.href,
      line: lineNumberAt(content, destination.offset)
    })
  }

  return { anchors, links }
}

/**
 * 解码 URL 片段；非法百分号编码会返回明确错误。
 *
 * @param {string} value 待解码内容
 * @param {string} label 错误字段名
 * @returns {{ value?: string, error?: string }} 解码结果
 */
function decodeUrlPart(value, label) {
  try {
    return { value: decodeURIComponent(value) }
  } catch {
    return { error: `${label} 包含非法 URL 编码: ${value}` }
  }
}

/**
 * 判断链接是否由外部协议或协议相对地址处理。
 *
 * @param {string} href 链接目标
 * @returns {boolean} 是否为外部链接
 */
function isExternalLink(href) {
  return href.startsWith('//') || /^[a-z][a-z\d+.-]*:/i.test(href)
}

/**
 * 将 VitePress 路由解析为 docs 内候选文件，兼容 cleanUrls 与目录首页。
 *
 * @param {string} sourcePath 来源 Markdown 相对路径
 * @param {string} routePath 不含 query/hash 的链接路径
 * @returns {{ candidates: string[], error?: string }} 候选相对路径
 */
function resolveRouteCandidates(sourcePath, routePath) {
  if (routePath === '') {
    return { candidates: [sourcePath] }
  }

  const absoluteRoute = routePath.startsWith('/')
  const decoded = decodeUrlPart(absoluteRoute ? routePath.slice(1) : routePath, '路径')
  if (decoded.error) {
    return { candidates: [], error: decoded.error }
  }

  const joinedPath = absoluteRoute
    ? decoded.value
    : posix.join(posix.dirname(sourcePath), decoded.value)
  const normalizedPath = posix.normalize(joinedPath).replace(/^\.\//, '')

  if (normalizedPath === '..' || normalizedPath.startsWith('../')) {
    return { candidates: [], error: `路径越过 docs 根目录: ${routePath}` }
  }

  if (routePath.endsWith('/') || normalizedPath === '.') {
    const directory = normalizedPath === '.' ? '' : normalizedPath
    return { candidates: [posix.join(directory, 'index.md')] }
  }

  const extension = extname(normalizedPath)
  if (extension === '.md') {
    return { candidates: [normalizedPath] }
  }
  if (extension === '.html') {
    return { candidates: [`${normalizedPath.slice(0, -5)}.md`] }
  }
  if (extension) {
    const candidates = [normalizedPath]
    if (absoluteRoute) {
      candidates.push(posix.join('public', normalizedPath))
    }
    return { candidates }
  }

  return {
    candidates: [
      `${normalizedPath}.md`,
      posix.join(normalizedPath, 'index.md')
    ]
  }
}

/**
 * 拆分链接的路径与锚点，并去掉 query 参数。
 *
 * @param {string} href 原始链接
 * @returns {{ routePath: string, fragment: string }} 拆分结果
 */
function splitLink(href) {
  const hashIndex = href.indexOf('#')
  const pathAndQuery = hashIndex >= 0 ? href.slice(0, hashIndex) : href
  const fragment = hashIndex >= 0 ? href.slice(hashIndex + 1) : ''
  const queryIndex = pathAndQuery.indexOf('?')
  return {
    routePath: queryIndex >= 0 ? pathAndQuery.slice(0, queryIndex) : pathAndQuery,
    fragment
  }
}

/**
 * 在 JSON 文本中定位文档入口，给 source-index 错误补充近似行号。
 *
 * @param {string} content JSON 原文
 * @param {string} documentRoute 文档路由
 * @returns {number} 一基行号
 */
function findJsonValueLine(content, documentRoute) {
  const offset = content.indexOf(JSON.stringify(documentRoute))
  return offset < 0 ? 1 : content.slice(0, offset).split(/\r?\n/).length
}

const docFiles = (await collectFiles(docsRoot)).filter((filePath) => filePath.endsWith('.md'))
const allDocFiles = new Set((await collectFiles(docsRoot)).map(toDocsRelativePath))
const documents = new Map()
const references = []

for (const docFile of docFiles) {
  const sourcePath = toDocsRelativePath(docFile)
  const content = await readFile(docFile, 'utf8')
  const scanResult = scanMarkdown(content)
  documents.set(sourcePath, scanResult)
  references.push(...scanResult.links.map((link) => ({ ...link, sourcePath })))
}

const vitePressConfigContent = await readFile(vitePressConfigPath, 'utf8')
const vitePressConfigSourcePath = relative(projectRoot, vitePressConfigPath).replaceAll('\\', '/')
references.push(...extractVitePressConfigReferences(vitePressConfigContent).map((reference) => ({
  ...reference,
  sourcePath: vitePressConfigSourcePath,
  routeBase: 'index.md'
})))

const indexFiles = (await collectFiles(sourceIndexRoot))
  .filter((filePath) => filePath.endsWith('.json') && filePath !== resolve(sourceIndexRoot, 'schema.json'))

for (const indexFile of indexFiles) {
  const content = await readFile(indexFile, 'utf8')
  const index = JSON.parse(content)
  const sourcePath = relative(projectRoot, indexFile).split(/\\/g).join('/')
  for (const entryPoint of index.entryPoints ?? []) {
    references.push({
      href: entryPoint.document,
      line: findJsonValueLine(content, entryPoint.document),
      sourcePath,
      routeBase: 'index.md'
    })
  }
}

const failures = []
let checkedLinks = 0

for (const reference of references) {
  const href = reference.href.trim()
  if (!href || isExternalLink(href)) {
    continue
  }

  checkedLinks += 1
  const { routePath, fragment } = splitLink(href)
  const sourcePath = reference.routeBase ?? reference.sourcePath
  const resolution = resolveRouteCandidates(sourcePath, routePath)

  if (resolution.error) {
    failures.push(`${reference.sourcePath}:${reference.line} ${resolution.error}`)
    continue
  }

  const targetPath = resolution.candidates.find((candidate) => allDocFiles.has(candidate))
  if (!targetPath) {
    failures.push(
      `${reference.sourcePath}:${reference.line} 找不到 ${href}，候选: ${resolution.candidates.join(', ')}`
    )
    continue
  }

  if (fragment && targetPath.endsWith('.md')) {
    const decodedFragment = decodeUrlPart(fragment, '锚点')
    if (decodedFragment.error) {
      failures.push(`${reference.sourcePath}:${reference.line} ${decodedFragment.error}`)
      continue
    }

    const targetDocument = documents.get(targetPath)
    if (!targetDocument?.anchors.has(decodedFragment.value)) {
      failures.push(`${reference.sourcePath}:${reference.line} ${href} 的锚点不存在`)
    }
  }
}

if (failures.length > 0) {
  console.error(`文档链接校验失败，共 ${failures.length} 个问题：`)
  failures.forEach((failure) => console.error(`- ${failure}`))
  process.exitCode = 1
} else {
  console.log(
    `文档链接校验通过：${docFiles.length} 篇文档，${checkedLinks} 个内部链接、配置路由与 source-index 入口`
  )
}
