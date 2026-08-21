#!/usr/bin/env node

import { access, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const projectRoot = resolve(process.argv[2] ?? process.cwd())

const requiredFiles = [
  '.github/ISSUE_TEMPLATE/config.yml',
  '.github/ISSUE_TEMPLATE/bug_report.yml',
  '.github/ISSUE_TEMPLATE/topic_request.yml',
  '.github/pull_request_template.md',
  '.github/DISCUSSION_TEMPLATE/new-topic.yml',
  '.github/DISCUSSION_TEMPLATE/lab-showcase.yml',
  'CHANGELOG.md',
  'docs/roadmap/index.md',
  'docs/guide/feedback.md',
  'docs/guide/contribution-walkthrough.md',
  'docs/guide/topic-template.md',
  'docs/guide/lab-authoring.md'
]

/**
 * 检查社区文件是否存在，避免仓库只更新了文档链接却漏提交模板。
 *
 * @param {string} relativePath 相对仓库根目录的路径
 * @returns {Promise<boolean>} 文件是否存在
 */
async function fileExists(relativePath) {
  try {
    await access(resolve(projectRoot, relativePath))
    return true
  } catch {
    return false
  }
}

/**
 * 读取文本并确认模板保留了必要的协作字段。
 *
 * @param {string} relativePath 相对仓库根目录的路径
 * @returns {Promise<string>} 文件文本
 */
async function readProjectFile(relativePath) {
  return readFile(resolve(projectRoot, relativePath), 'utf8')
}

const missingFiles = []
for (const relativePath of requiredFiles) {
  if (!(await fileExists(relativePath))) {
    missingFiles.push(relativePath)
  }
}

const failures = missingFiles.map((relativePath) => `缺少必需社区文件：${relativePath}`)
if (await fileExists('.github/ISSUE_TEMPLATE/bug_report.yml')) {
  const bugTemplate = await readProjectFile('.github/ISSUE_TEMPLATE/bug_report.yml')
  for (const field of ['name:', 'description:', 'body:', 'id: reproduction', 'id: environment']) {
    if (!bugTemplate.includes(field)) {
      failures.push(`Bug 模板缺少字段：${field}`)
    }
  }
}
if (await fileExists('.github/ISSUE_TEMPLATE/topic_request.yml')) {
  const topicTemplate = await readProjectFile('.github/ISSUE_TEMPLATE/topic_request.yml')
  for (const field of ['name:', 'description:', 'body:', 'id: question', 'id: evidence']) {
    if (!topicTemplate.includes(field)) {
      failures.push(`专题建议模板缺少字段：${field}`)
    }
  }
}
if (await fileExists('.github/pull_request_template.md')) {
  const pullRequestTemplate = await readProjectFile('.github/pull_request_template.md')
  for (const command of ['mvn --batch-mode test', 'npm run verify:docs', 'git diff --check']) {
    if (!pullRequestTemplate.includes(command)) {
      failures.push(`Pull Request 模板缺少验证命令：${command}`)
    }
  }
}
if (await fileExists('docs/guide/contribution-walkthrough.md')) {
  const walkthrough = await readProjectFile('docs/guide/contribution-walkthrough.md')
  for (const requiredText of [
    'shouldKeepMappingsAfterResize',
    '"evidenceId": "resize-boundary"',
    'mvn --batch-mode test',
    'npm run verify:docs',
    './gradlew test buildPlugin --offline --no-daemon'
  ]) {
    if (!walkthrough.includes(requiredText)) {
      failures.push(`完整贡献示例缺少闭环内容：${requiredText}`)
    }
  }
}

if (failures.length > 0) {
  console.error(`社区文件校验失败，共 ${failures.length} 个问题：`)
  failures.forEach((failure) => console.error(`- ${failure}`))
  process.exitCode = 1
} else {
  console.log(`社区文件校验通过：${requiredFiles.length} 个模板、指南和变更记录文件`)
}
