import { expect, test } from '@playwright/test'

/**
 * E2E 冒烟（S0-11/S0-08）：前端壳可达、标题/文案正确、路由重定向与 404 生效。
 *
 * 说明：S0 尚无可用后端，登录 E2E 明确留到 M1（真实账号 + 真实 API），这里不伪造登录。
 * 为验证登录后的主壳，使用 addInitScript 注入 SYNTHETIC 会话快照；该数据仅用于浏览器
 * 渲染冒烟，不调用 /v1/auth/login，也不代表“登录已通过 E2E”。
 */
const syntheticUser = {
  userId: '1000000000000000001',
  username: 'demo_admin',
  displayName: '演示管理员（合成）',
  roles: ['SYS_ADMIN'],
  permissions: ['m0:auth:read', 'm0:auth:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

test.beforeEach(async ({ page }) => {
  // 守卫首屏会用 /v1/auth/me 校验会话；S0 无后端，这里只返回 SYNTHETIC 快照。
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'X-Trace-Id': 'SYNTH-E2E-TRACE' },
      body: JSON.stringify(syntheticUser),
    })
  })
  await page.addInitScript((user) => {
    const expiresAt = Date.now() + 900_000
    window.sessionStorage.setItem('anesop.auth.accessToken', 'SYNTH-ACCESS-TOKEN')
    window.sessionStorage.setItem('anesop.auth.refreshToken', 'SYNTH-REFRESH-TOKEN')
    window.sessionStorage.setItem('anesop.auth.currentUser', JSON.stringify(user))
    window.sessionStorage.setItem('anesop.auth.expiresAt', String(expiresAt))
  }, syntheticUser)
})

test.describe('前端壳冒烟', () => {
  test('首页加载并展示欢迎语、角色与权限概览', async ({ page }) => {
    await page.goto('/home')

    await expect(page).toHaveTitle(/ANESOP/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('欢迎回来，演示管理员（合成）')
    await expect(page.getByText('信息科管理员（R9）').first()).toBeVisible()
    await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
    await expect(page.getByText('工作台占位页')).toHaveCount(0)
  })

  test('根路径重定向到 /home', async ({ page }) => {
    await page.goto('/')

    await expect(page).toHaveURL(/\/home$/)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.getByRole('banner')).toBeVisible()
  })

  test('未知路径保留 URL 并渲染 404', async ({ page }) => {
    await page.goto('/no-such-page')

    await expect(page).toHaveURL(/\/no-such-page$/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('页面不存在')
  })
})
