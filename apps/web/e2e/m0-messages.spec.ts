import { expect, test, type Page } from '@playwright/test'

/**
 * M0 消息中心（M8-M0-04）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：未读角标 → 单条已读（角标减一、状态变已读）→ 全部已读（角标消失、列表刷新）。
 * 后端「仅本人消息 404」、审计 MESSAGE_READ/MESSAGE_READ_ALL、参数白名单与权限 403 由
 * `M0PlatformApiIntegrationTest` 与 `PlatformMessageServiceTest` 覆盖。
 */

interface SyntheticUser {
  userId: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
  dataScopes: { type: string; scopeIds: string[] }[]
  siteIds: string[]
  mustChangePassword: boolean
}

const ANESTHESIOLOGIST: SyntheticUser = {
  userId: '1000000000000000011',
  username: 'synth_msg_doc',
  displayName: '演示麻醉医师（合成）',
  roles: ['SYS_ANESTHESIOLOGIST'],
  permissions: ['m0:message:read'],
  dataScopes: [{ type: 'SELF', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const MESSAGE_A = {
  messageId: '9a000000-0000-4000-8000-000000000001',
  type: 'SCHEDULE',
  templateCode: 'SCHEDULE_CHANGED',
  title: '台次时间已调整',
  params: { roomNo: 'OR-01' },
  read: false,
  createdAt: '2026-11-15T18:20:00+08:00',
  readAt: null,
}

const MESSAGE_B = {
  messageId: '9a000000-0000-4000-8000-000000000002',
  type: 'SYSTEM',
  templateCode: 'SYSTEM_BROADCAST',
  title: '系统维护通知',
  params: {},
  read: false,
  createdAt: '2026-11-15T18:10:00+08:00',
  readAt: null,
}

async function seedSession(page: Page, user: SyntheticUser): Promise<void> {
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(user) }),
  )
  await page.addInitScript((value) => {
    window.sessionStorage.setItem('anesop.auth.accessToken', 'SYNTH-ACCESS-TOKEN')
    window.sessionStorage.setItem('anesop.auth.refreshToken', 'SYNTH-REFRESH-TOKEN')
    window.sessionStorage.setItem('anesop.auth.currentUser', JSON.stringify(value))
    window.sessionStorage.setItem('anesop.auth.expiresAt', String(Date.now() + 900_000))
  }, user)
}

test.describe('M0 消息中心', () => {
  test('未读角标 → 单条已读 → 全部已读', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)

    const messages = [MESSAGE_A, MESSAGE_B]
    let readAllCalled = 0
    let initialLoaded = 0

    // 通用列表路由先注册，具体 POST 路由后注册（Playwright 后注册优先）。
    await page.route('**/api/v1/messages?**', async (route) => {
      initialLoaded += 1
      const unread = messages.filter((message) => !message.read)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: messages,
          meta: { page: 1, size: 20, total: messages.length, totalPages: 1 },
          unreadCount: unread.length,
        }),
      })
    })

    await page.route('**/api/v1/messages/read-all', async (route) => {
      readAllCalled += 1
      const unread = messages.filter((message) => !message.read).length
      messages.forEach((message) => {
        message.read = true
        message.readAt = '2026-11-15T18:30:00+08:00'
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ updatedCount: unread }),
      })
    })

    await page.route('**/api/v1/messages/*/read', async (route) => {
      const id = route.request().url().split('/messages/')[1]?.split('/read')[0]
      const target = messages.find((message) => message.messageId === id)
      if (target) {
        target.read = true
        target.readAt = '2026-11-15T18:25:00+08:00'
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(target ?? MESSAGE_A),
      })
    })

    await page.goto('/m0/messages')

    await expect(page.getByRole('heading', { name: /消息中心/ })).toBeVisible()
    await expect(page.getByText('台次时间已调整')).toBeVisible()
    await expect(page.getByText('系统维护通知')).toBeVisible()
    await expect(page.getByText('roomNo=OR-01')).toBeVisible()
    await expect(page.locator('[data-testid="unread-badge"]')).toContainText('2')

    // 单条已读：角标 2 → 1，第一行状态变已读。
    await page.getByRole('button', { name: '标记已读' }).first().click()
    await expect(page.locator('[data-testid="unread-badge"]')).toContainText('1')
    await expect(page.getByRole('button', { name: '标记已读' })).toHaveCount(1)

    // 全部已读：角标消失，列表重新加载且全部已读。
    await page.getByRole('button', { name: '全部已读' }).click()
    await expect.poll(() => readAllCalled).toBe(1)
    await expect.poll(() => initialLoaded).toBeGreaterThanOrEqual(2)
    await expect(page.locator('[data-testid="unread-badge"]')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '标记已读' })).toHaveCount(0)
    await expect(page.getByText('已读').first()).toBeVisible()
  })
})
