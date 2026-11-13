import { expect, test, type Page } from '@playwright/test'

/**
 * M1 手术申请（S-9）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：列表加载、状态筛选、详情（含状态历史）、只读用户权限按钮隐藏。
 * 所有患者/申请均为合成数据（演示患者 / SYNTH-HIS-APP-*），不含真实 PHI。
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

const WRITE_USER: SyntheticUser = {
  userId: '1000000000000000001',
  username: 'synth_or_manager',
  displayName: '演示手术室管理员（合成）',
  roles: ['SYS_OR_MANAGER'],
  permissions: ['m1:application:read', 'm1:application:write', 'm1:patient:read'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const READ_ONLY_USER: SyntheticUser = {
  ...WRITE_USER,
  userId: '1000000000000000002',
  username: 'synth_surgeon',
  displayName: '演示外科医师（合成）',
  roles: ['SYS_SURGEON'],
  permissions: ['m1:application:read', 'm1:patient:read'],
}

const APPLICATION = {
  applicationId: '60000000-0000-4000-8000-000000000001',
  hisApplicationNo: 'SYNTH-HIS-APP-0001',
  patient: {
    patientId: '50000000-0000-4000-8000-000000000001',
    hisPatientNo: 'SYNTH-HIS-P-0001',
    name: '演示患者',
    gender: 'FEMALE',
    birthDate: '1978-03-15',
    age: 47,
    inpatientNoMasked: 'SYNT****0001',
    departmentId: '2000000000000000001',
    departmentName: '演示外科',
    ward: '演示外科病区',
    bedNo: '12',
    siteId: '3000000000000000001',
  },
  surgeryName: '演示术式（全膝关节置换术）',
  surgeryCode: 'SYNTH-OP-0001',
  urgency: 'ELECTIVE',
  plannedDurationMin: 150,
  departmentName: '演示外科',
  surgeonName: '演示术者',
  preopDiagnosis: '演示诊断（膝关节退行性病变）',
  status: 'SUBMITTED',
  submittedAt: '2026-11-08T10:00:00+08:00',
  source: 'HIS',
  remark: '演示备注（合成数据）',
  statusHistory: [],
  createdAt: '2026-11-08T10:00:00+08:00',
  createdBy: '1000000000000000001',
  updatedAt: '2026-11-08T10:00:00+08:00',
  updatedBy: '1000000000000000001',
  version: 1,
}

const DETAIL = {
  ...APPLICATION,
  statusHistory: [
    {
      fromStatus: null,
      toStatus: 'SUBMITTED',
      reasonCode: null,
      reason: 'HIS 导入',
      operatorId: '1000000000000000001',
      occurredAt: '2026-11-08T10:00:00+08:00',
    },
  ],
}

function pageBody(items: unknown[]) {
  return { items, meta: { page: 1, size: 10, total: items.length, totalPages: 1 } }
}

async function seedSession(page: Page, user: SyntheticUser): Promise<void> {
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(user),
    }),
  )
  await page.addInitScript((value) => {
    window.sessionStorage.setItem('anesop.auth.accessToken', 'SYNTH-ACCESS-TOKEN')
    window.sessionStorage.setItem('anesop.auth.refreshToken', 'SYNTH-REFRESH-TOKEN')
    window.sessionStorage.setItem('anesop.auth.currentUser', JSON.stringify(value))
    window.sessionStorage.setItem('anesop.auth.expiresAt', String(Date.now() + 900_000))
  }, user)
}

test.describe('M1 手术申请', () => {
  test('列表加载并展示脱敏患者与状态', async ({ page }) => {
    await seedSession(page, WRITE_USER)
    await page.route(/\/api\/v1\/scheduling\/applications(\?.*)?$/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageBody([APPLICATION])),
      }),
    )

    await page.goto('/m1/applications')

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('手术申请')
    await expect(page.getByText('SYNTH-HIS-APP-0001')).toBeVisible()
    await expect(page.getByText('演示患者')).toBeVisible()
    await expect(page.getByText('SYNT****0001')).toBeVisible()
    await expect(page.getByTestId('import-button')).toBeVisible()
    await expect(page.getByTestId('cancel-button').first()).toBeVisible()
  })

  test('状态筛选发出带 status 的查询并按结果渲染', async ({ page }) => {
    const urls: string[] = []
    await seedSession(page, WRITE_USER)
    await page.route(/\/api\/v1\/scheduling\/applications(\?.*)?$/, (route) => {
      urls.push(route.request().url())
      const accepted = { ...APPLICATION, status: 'ACCEPTED' }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageBody(urls.length === 1 ? [APPLICATION] : [accepted])),
      })
    })

    await page.goto('/m1/applications')
    await expect(page.getByText('SYNTH-HIS-APP-0001')).toBeVisible()

    await page.getByTestId('status-filter').click()
    await page.locator('.el-select-dropdown__item').filter({ hasText: '已受理' }).click()
    await page.getByTestId('search-button').click()

    await expect.poll(() => urls.length).toBeGreaterThanOrEqual(2)
    expect(urls[urls.length - 1]).toContain('status=ACCEPTED')
    await expect(page.locator('tbody').getByText('已受理')).toBeVisible()
  })

  test('详情页展示申请与状态历史', async ({ page }) => {
    await seedSession(page, WRITE_USER)
    await page.route(/\/api\/v1\/scheduling\/applications(\?.*)?$/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageBody([APPLICATION])),
      }),
    )
    await page.route(/\/api\/v1\/scheduling\/applications\/[^/?]+$/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(DETAIL),
      }),
    )

    await page.goto('/m1/applications')
    await page.getByRole('button', { name: '详情' }).first().click()

    await expect(page).toHaveURL(new RegExp(`/m1/applications/${APPLICATION.applicationId}$`))
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('手术申请详情')
    await expect(page.getByText('状态历史')).toBeVisible()
    await expect(page.getByText('创建 → 已提交')).toBeVisible()
  })

  test('只读用户不显示导入与取消按钮', async ({ page }) => {
    await seedSession(page, READ_ONLY_USER)
    await page.route(/\/api\/v1\/scheduling\/applications(\?.*)?$/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageBody([APPLICATION])),
      }),
    )

    await page.goto('/m1/applications')

    await expect(page.getByText('SYNTH-HIS-APP-0001')).toBeVisible()
    await expect(page.getByTestId('import-button')).toHaveCount(0)
    await expect(page.getByTestId('cancel-button')).toHaveCount(0)
  })
})
