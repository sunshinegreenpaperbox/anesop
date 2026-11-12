import { expect, test } from '@playwright/test'

/**
 * E2E 冒烟（S0-11）：当前只有前端壳，先验证首页可达、标题/文案正确、路由重定向生效。
 * 业务 E2E-01–05 的落地计划见 docs/testing/README.md。
 */
test.describe('首页冒烟', () => {
  test('首页加载并展示标题与工作台文案', async ({ page }) => {
    await page.goto('/home')

    await expect(page).toHaveTitle(/ANESOP/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('ANESOP 麻醉信息平台')
    await expect(page.getByText('围术期全流程管理 · 工作台占位页')).toBeVisible()
  })

  test('根路径重定向到 /home', async ({ page }) => {
    await page.goto('/')

    await expect(page).toHaveURL(/\/home$/)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  test('未知路径兜底到 /home', async ({ page }) => {
    await page.goto('/no-such-page')

    await expect(page).toHaveURL(/\/home$/)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })
})
