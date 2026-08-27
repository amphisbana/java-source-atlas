<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onContentUpdated, useData } from 'vitepress'

interface OutlineItem {
  children: OutlineItem[]
  id: string
  level: number
  link: string
  title: string
}

type OutlineLevel = number | [number, number] | 'deep'
type OutlineSetting = boolean | OutlineLevel | { label?: string; level?: OutlineLevel }

const { frontmatter, theme } = useData()
const activeLink = ref('')
const outlineItems = ref<OutlineItem[]>([])
const outlineTitle = ref('本页目录')

let headingElements: HTMLElement[] = []
let refreshFrame = 0
let scrollFrame = 0

/**
 * 读取页面级或站点级目录配置，页面配置优先。
 */
function currentOutlineSetting(): OutlineSetting | undefined {
  return (frontmatter.value.outline ?? theme.value.outline) as OutlineSetting | undefined
}

/**
 * 把 VitePress 的目录配置转换为可比较的标题级别范围。
 */
function resolveLevelRange(setting: OutlineSetting | undefined): [number, number] | null {
  if (setting === false) {
    return null
  }

  const level = typeof setting === 'object' && !Array.isArray(setting)
    ? setting.level
    : setting

  if (level === 'deep') {
    return [2, 6]
  }
  if (Array.isArray(level)) {
    return level
  }
  if (typeof level === 'number') {
    return [level, level]
  }
  return [2, 2]
}

/**
 * 读取目录标题，兼容当前 outline.label 和旧版 outlineTitle 配置。
 */
function resolveOutlineTitle(setting: OutlineSetting | undefined): string {
  if (typeof setting === 'object' && !Array.isArray(setting) && setting.label) {
    return setting.label
  }
  return (theme.value.outlineTitle as string | undefined) ?? '本页目录'
}

/**
 * 找到当前可见的正文节点；路由切换短暂保留旧节点时优先使用最后渲染的新正文。
 */
function findCurrentDocument(): HTMLElement | null {
  const documents = Array.from(
    document.querySelectorAll<HTMLElement>('#VPContent .VPDoc .main > .vp-doc')
  )

  for (let index = documents.length - 1; index >= 0; index -= 1) {
    if (documents[index].offsetParent !== null) {
      return documents[index]
    }
  }
  return documents.at(-1) ?? null
}

/**
 * 提取标题的可见文本，排除永久链接、徽标和明确标记为忽略的内容。
 */
function serializeHeading(heading: HTMLElement): string {
  const clone = heading.cloneNode(true) as HTMLElement
  clone
    .querySelectorAll('.VPBadge, .header-anchor, .footnote-ref, .ignore-header')
    .forEach((element) => element.remove())
  return clone.textContent?.trim() ?? ''
}

/**
 * 从单个当前正文中生成目录树，并按锚点去重，避免路由更新时旧标题被重复收集。
 */
function collectOutlineItems(range: [number, number]): OutlineItem[] {
  const documentRoot = findCurrentDocument()
  if (documentRoot === null) {
    headingElements = []
    return []
  }

  const seenLinks = new Set<string>()
  const flatItems: OutlineItem[] = []
  headingElements = []

  documentRoot.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6').forEach((heading) => {
    const level = Number(heading.tagName.slice(1))
    const link = `#${heading.id}`
    if (
      !heading.id ||
      !heading.hasChildNodes() ||
      heading.classList.contains('ignore-header') ||
      level < range[0] ||
      level > range[1] ||
      seenLinks.has(link)
    ) {
      return
    }

    const title = serializeHeading(heading)
    if (!title) {
      return
    }

    seenLinks.add(link)
    flatItems.push({ children: [], id: heading.id, level, link, title })
    headingElements.push(heading)
  })

  const roots: OutlineItem[] = []
  const stack: OutlineItem[] = []
  flatItems.forEach((item) => {
    while (stack.length > 0 && stack[stack.length - 1].level >= item.level) {
      stack.pop()
    }
    const parent = stack[stack.length - 1]
    if (parent === undefined) {
      roots.push(item)
    } else {
      parent.children.push(item)
    }
    stack.push(item)
  })
  return roots
}

/**
 * 根据当前滚动位置更新高亮目录项。
 */
function updateActiveLink(): void {
  let nextActiveLink = ''
  const activationOffset = 96

  headingElements.forEach((heading) => {
    if (heading.getBoundingClientRect().top <= activationOffset) {
      nextActiveLink = `#${heading.id}`
    }
  })

  const reachesPageBottom = window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2
  if (reachesPageBottom && headingElements.length > 0) {
    nextActiveLink = `#${headingElements[headingElements.length - 1].id}`
  }
  activeLink.value = nextActiveLink
}

/**
 * 用动画帧合并高频滚动事件，避免反复计算标题位置。
 */
function handleScroll(): void {
  window.cancelAnimationFrame(scrollFrame)
  scrollFrame = window.requestAnimationFrame(updateActiveLink)
}

/**
 * 在正文完成更新后重建目录，确保读取的是当前路由对应的最终 DOM。
 */
async function refreshOutline(): Promise<void> {
  await nextTick()
  window.cancelAnimationFrame(refreshFrame)
  refreshFrame = window.requestAnimationFrame(() => {
    const setting = currentOutlineSetting()
    const range = resolveLevelRange(setting)
    outlineTitle.value = resolveOutlineTitle(setting)
    outlineItems.value = range === null ? [] : collectOutlineItems(range)
    updateActiveLink()
  })
}

/**
 * 目录跳转后把焦点交给正文标题，保持键盘浏览体验。
 */
function focusHeading(id: string): void {
  window.requestAnimationFrame(() => {
    document.getElementById(id)?.focus({ preventScroll: true })
  })
}

onContentUpdated(refreshOutline)

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
  void refreshOutline()
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', handleScroll)
  window.cancelAnimationFrame(refreshFrame)
  window.cancelAnimationFrame(scrollFrame)
})
</script>

<template>
  <nav
    v-if="outlineItems.length > 0"
    class="atlas-doc-outline"
    aria-labelledby="atlas-doc-outline-title"
  >
    <div
      id="atlas-doc-outline-title"
      class="atlas-doc-outline__title"
      role="heading"
      aria-level="2"
    >
      {{ outlineTitle }}
    </div>
    <ul class="atlas-doc-outline__list">
      <li v-for="item in outlineItems" :key="item.link">
        <a
          class="atlas-doc-outline__link"
          :class="{ active: activeLink === item.link }"
          :href="item.link"
          :title="item.title"
          @click="focusHeading(item.id)"
        >
          {{ item.title }}
        </a>
        <ul v-if="item.children.length > 0" class="atlas-doc-outline__list atlas-doc-outline__list--nested">
          <li v-for="child in item.children" :key="child.link">
            <a
              class="atlas-doc-outline__link"
              :class="{ active: activeLink === child.link }"
              :href="child.link"
              :title="child.title"
              @click="focusHeading(child.id)"
            >
              {{ child.title }}
            </a>
          </li>
        </ul>
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.atlas-doc-outline {
  position: relative;
  border-left: 1px solid var(--vp-c-divider);
  padding-left: 16px;
  font-size: 14px;
}

.atlas-doc-outline__title {
  color: var(--vp-c-text-1);
  font-weight: 600;
  line-height: 32px;
}

.atlas-doc-outline__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.atlas-doc-outline__list--nested {
  padding-left: 16px;
}

.atlas-doc-outline__link {
  display: block;
  overflow: hidden;
  color: var(--vp-c-text-2);
  font-weight: 400;
  line-height: 32px;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.25s;
}

.atlas-doc-outline__link:hover,
.atlas-doc-outline__link.active {
  color: var(--vp-c-text-1);
}
</style>
