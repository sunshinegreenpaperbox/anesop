import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * 登录提交 + E2E-04 权限越权（UI 级 + API 拦截）。
 *
 * 数据全部为 SYNTHETIC：`page.route` 拦截 `/api/v1/auth/*` 返回构造的契约响应，
 * 不连接真实后端。覆盖：
 * - 空值校验不发请求；成功登录跳 /home 并展示角色/权限；失败展示 code/message/traceId；
 * - E2E-04：低权限用户访问 `/m0/sessions` → 403 页且菜单项不显示；
 * - 无感刷新：/me 首次 401 → /v1/auth/refresh → 用新令牌重放并成功；
 * - 登出清除本地会话并回到 /login。
 *
 * 边界：这是 UI 级越权断言（路由守卫 + 菜单过滤）加上合成 API 拦截，
 * 不代表真实后端鉴权已通过 E2E 验证；真实全栈 E2E 由后续 `scripts/e2e-full.sh` 承接，
 * 见 docs/testing/README.md §6。
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

const ADMIN: SyntheticUser = {
  userId: '1000000000000000001',
  username: 'synth_admin',
  displayName: '演示管理员（合成）',
  roles: ['SYS_ADMIN'],
  permissions: ['m0:auth:read', 'm0:auth:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

/** 低权限账号：无 m0:auth:read，因此 /m0/sessions 应被守卫拦到 403。 */
const LOW_PRIVILEGE: SyntheticUser = {
  userId: '1000000000000000002',
  username: 'synth_nurse',
  displayName: '麻醉护士（合成）',
  roles: ['SYS_NURSE_ANESTHETIST'],
  permissions: ['m1:application:read'],
  dataScopes: [{ type: 'DEPT', scopeIds: ['4000000000000000001'] }],
  siteIds: [],
  mustChangePassword: false,
}

function tokenBody(user: SyntheticUser, accessToken = 'SYNTH-ACCESS-TOKEN') {
  return {
    accessToken,
    refreshToken: 'SYNTH-REFRESH-TOKEN',
    tokenType: 'BEARER',
    expiresIn: 900,
    user,
  }
}

function errorBody(code: string, message: string, traceId: string) {
  return { code, message, traceId, details: [] }
}

async function fulfillJson(
  route: Route,
  status: number,
  body: unknown,
  traceId = 'SYNTH-E2E-TRACE',
) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'X-Trace-Id': traceId },
    body: JSON.stringify(body),
  })
}

async function gotoLogin(page: Page) {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '账号登录' })).toBeVisible()
}

async function submitLogin(page: Page, username: string, password: string) {
  await page.getByPlaceholder('工号或域账号').fill(username)
  await page.getByPlaceholder('请输入口令').fill(password)
  await page.locator('.login__submit').click()
}

test.describe('登录提交', () => {
  test('空表单触发前端校验且不发出登录请求', async ({ page }) => {
    let loginCalls = 0
    await page.route('**/api/v1/auth/login', async (route) => {
      loginCalls += 1
      await fulfillJson(
        route,
        500,
        errorBody('UNEXPECTED', '不应发出请求', 'SYNTH-SHOULD-NOT-HAPPEN'),
      )
    })
    await gotoLogin(page)

    await page.locator('.login__submit').click()

    await expect(page.getByText('请输入登录账号')).toBeVisible()
    await expect(page.getByText('请输入口令')).toBeVisible()
    expect(loginCalls).toBe(0)
    await expect(page).toHaveURL(/\/login$/)
  })

  test('登录成功跳转 /home 并展示角色与权限概览', async ({ page }) => {
    await page.route('**/api/v1/auth/login', (route) => fulfillJson(route, 200, tokenBody(ADMIN)))
    await page.route('**/api/v1/auth/me', (route) => fulfillJson(route, 200, ADMIN))

    await gotoLogin(page)
    await submitLogin(page, ADMIN.username, 'Synth-Passw0rd!')

    await expect(page).toHaveURL(/\/home$/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('欢迎回来，演示管理员（合成）')
    await expect(page.locator('.app-header__role')).toHaveText('信息科管理员（R9）')
    await expect(page.getByText('当前共 2 个权限点')).toBeVisible()
    await expect(
      page.getByRole('navigation', { name: '主导航' }).getByText('会话管理'),
    ).toBeVisible()
  })

  test('登录失败停留登录页并展示契约 code/message/traceId', async ({ page }) => {
    await page.route('**/api/v1/auth/login', (route) =>
      fulfillJson(
        route,
        401,
        errorBody('AUTH_INVALID_CREDENTIALS', '账号或口令错误', 'SYNTH-TRACE-LOGIN-FAIL'),
        'SYNTH-TRACE-LOGIN-FAIL',
      ),
    )

    await gotoLogin(page)
    await submitLogin(page, 'synth_admin', 'wrong-password')

    const alert = page.locator('.login__notice')
    await expect(alert.getByText('登录失败', { exact: true })).toBeVisible()
    await expect(alert.locator('.login__error-message')).toHaveText('账号或口令错误')
    await expect(alert.getByText(/错误码：AUTH_INVALID_CREDENTIALS/)).toBeVisible()
    await expect(alert.getByText(/追踪号：SYNTH-TRACE-LOGIN-FAIL/)).toBeVisible()
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', { name: '账号登录' })).toBeVisible()
  })
})

test.describe('E2E-04 权限越权（UI 级）', () => {
  test('低权限用户直接访问受限路由被拦到 403 且菜单不显示', async ({ page }) => {
    await page.route('**/api/v1/auth/login', (route) =>
      fulfillJson(route, 200, tokenBody(LOW_PRIVILEGE)),
    )
    await page.route('**/api/v1/auth/me', (route) => fulfillJson(route, 200, LOW_PRIVILEGE))

    await gotoLogin(page)
    await submitLogin(page, LOW_PRIVILEGE.username, 'Synth-Passw0rd!')
    await expect(page).toHaveURL(/\/home$/)

    const nav = page.getByRole('navigation', { name: '主导航' })
    await expect(nav.getByText('会话管理')).toHaveCount(0)
    await expect(nav.getByText('首页')).toBeVisible()

    await page.goto('/m0/sessions')

    await expect(page).toHaveURL(/\/403/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('无权访问该页面')
    await expect(page.getByText('目标页面：/m0/sessions')).toBeVisible()
    await expect(page.getByText(/角色：SYS_NURSE_ANESTHETIST/)).toBeVisible()
  })
})

test.describe('会话续期与登出', () => {
  test('/me 首次 401 触发单次 refresh 并用新令牌重放', async ({ page }) => {
    let meCalls = 0
    let refreshCalls = 0
    const meAuthorizations: string[] = []

    await page.route('**/api/v1/auth/login', (route) =>
      fulfillJson(route, 200, tokenBody(ADMIN, 'SYNTH-OLD-ACCESS')),
    )
    await page.route('**/api/v1/auth/me', async (route) => {
      meCalls += 1
      const authorization = route.request().headers()['authorization'] ?? ''
      meAuthorizations.push(authorization)
      if (meCalls === 1) {
        await fulfillJson(
          route,
          401,
          errorBody('AUTH_TOKEN_EXPIRED', '访问令牌已过期', 'SYNTH-TRACE-ME-401'),
          'SYNTH-TRACE-ME-401',
        )
        return
      }
      await fulfillJson(route, 200, ADMIN)
    })
    await page.route('**/api/v1/auth/refresh', async (route) => {
      refreshCalls += 1
      const body = route.request().postDataJSON() as { refreshToken?: string }
      expect(body.refreshToken).toBe('SYNTH-REFRESH-TOKEN')
      await fulfillJson(route, 200, tokenBody(ADMIN, 'SYNTH-NEW-ACCESS'))
    })

    await gotoLogin(page)
    await submitLogin(page, ADMIN.username, 'Synth-Passw0rd!')
    await expect(page).toHaveURL(/\/home$/)

    // 刷新页面：内存令牌丢失，但 sessionStorage 仍有旧令牌，守卫用 /me 校验。
    await page.reload()

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('欢迎回来，演示管理员（合成）')
    expect(refreshCalls).toBe(1)
    expect(meCalls).toBe(2)
    expect(meAuthorizations[0]).toBe('Bearer SYNTH-OLD-ACCESS')
    expect(meAuthorizations[1]).toBe('Bearer SYNTH-NEW-ACCESS')
  })

  test('登出清除本地会话并回到 /login', async ({ page }) => {
    let logoutCalls = 0
    await page.route('**/api/v1/auth/login', (route) => fulfillJson(route, 200, tokenBody(ADMIN)))
    await page.route('**/api/v1/auth/me', (route) => fulfillJson(route, 200, ADMIN))
    await page.route('**/api/v1/auth/logout', async (route) => {
      logoutCalls += 1
      await route.fulfill({ status: 204 })
    })

    await gotoLogin(page)
    await submitLogin(page, ADMIN.username, 'Synth-Passw0rd!')
    await expect(page).toHaveURL(/\/home$/)

    await page.getByRole('button', { name: '用户菜单' }).click()
    await page.getByText('退出登录').click()

    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', { name: '账号登录' })).toBeVisible()
    expect(logoutCalls).toBe(1)
    const session = await page.evaluate(() => ({
      accessToken: window.sessionStorage.getItem('anesop.auth.accessToken'),
      refreshToken: window.sessionStorage.getItem('anesop.auth.refreshToken'),
      user: window.sessionStorage.getItem('anesop.auth.currentUser'),
    }))
    expect(session).toEqual({ accessToken: null, refreshToken: null, user: null })
  })
})
