import { expect, test } from '@playwright/test'

test.describe('Java Source Atlas 文档工作台', () => {
  /**
   * 验证源码索引可以从精确类方法切换到对应推荐断点。
   */
  test('按类和方法筛选源码入口与推荐断点', async ({ page }) => {
    await page.goto('./source-explorer/')
    const explorer = page.getByRole('region', { name: '源码索引工作台' })

    await explorer.getByPlaceholder('类、方法、用途或变量').fill('HashMap.putVal')
    await expect(explorer.getByText(/1 个专题 · \d+ 条结果/)).toBeVisible()
    await expect(explorer.locator('strong').filter({ hasText: 'OpenJDK 8 HashMap 源码解析' })).toBeVisible()

    await explorer.getByRole('button', { name: '推荐断点' }).click()
    await expect(explorer.getByRole('button', { name: /复制断点 putVal/ }).first()).toBeVisible()
  })

  /**
   * 验证源码索引刷新后仍从浏览器本地状态恢复专题阅读进度。
   */
  test('刷新页面后恢复专题学习进度', async ({ page }) => {
    await page.goto('./source-explorer/')
    await page.evaluate(() => window.localStorage.clear())
    await page.reload()
    const explorer = page.getByRole('region', { name: '源码索引工作台' })

    await explorer.getByLabel('专题').selectOption('openjdk8-java-util-hashmap')
    const readMain = explorer.getByRole('checkbox', { name: '主线已读' })
    await readMain.check()
    await expect(readMain).toBeChecked()

    await page.reload()
    const restoredExplorer = page.getByRole('region', { name: '源码索引工作台' })
    await restoredExplorer.getByLabel('专题').selectOption('openjdk8-java-util-hashmap')
    await expect(restoredExplorer.getByRole('checkbox', { name: '主线已读' })).toBeChecked()
  })

  /**
   * 验证源码动画的步进、重置和状态计数保持一致。
   */
  test('HashMap 动画可以步进并重置', async ({ page }) => {
    await page.goto('./jdk/collections/hashmap/put')
    const animation = page.getByRole('region', { name: '一次碰撞写入如何穿过 putVal 动画演示' })

    await expect(animation.getByText('1 / 6')).toBeVisible()
    await animation.getByRole('button', { name: '下一步 →' }).click()
    await expect(animation.getByText('2 / 6')).toBeVisible()
    await expect(animation.getByText('扰动并计算下标')).toBeVisible()

    await animation.getByRole('button', { name: '重置' }).click()
    await expect(animation.getByText('1 / 6')).toBeVisible()
  })

  /**
   * 验证窄屏下动画和页面正文不会制造横向滚动。
   */
  test('移动端页面没有横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('./jdk/collections/hashmap/put')
    await expect(page.getByRole('region', { name: '一次碰撞写入如何穿过 putVal 动画演示' })).toBeVisible()

    const dimensions = await page.evaluate(() => ({
      viewport: window.innerWidth,
      document: document.documentElement.scrollWidth
    }))
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport)
  })
})
