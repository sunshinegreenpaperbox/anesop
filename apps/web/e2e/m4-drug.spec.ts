import { expect, test, type Page } from '@playwright/test'

/**
 * 药品目录与麻精五专（M4-M15-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：药品目录新建 → 麻精专册登记（双人签字）→ 日结/班结（平账提示 + 提交）。
 * 真实 409/400/422、append-only 55000、科室数据范围 403、审计脱敏与适配器降级由
 * `M4DrugApiIntegrationTest` 与单测覆盖；本用例只验证页面可完成业务流程。
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

const PHARMACIST: SyntheticUser = {
  userId: '10000000-0000-4000-8000-000000000003',
  username: 'synth_pharmacist',
  displayName: '演示药房药师（合成）',
  roles: ['SYS_PHARMACIST'],
  permissions: ['m4:drug:read', 'm4:drug:write', 'm4:narcotic:manage'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const DRUG_ID = '2a000000-0000-4000-8000-000000000001'
const DEPARTMENT_ID = 'f1000000-0000-4000-8000-000000000002'
const SECOND_SIGNER_ID = '10000000-0000-4000-8000-000000000004'

function drugRecord(overrides: Record<string, unknown> = {}) {
  return {
    drugId: DRUG_ID,
    code: 'DRUG-SUF-001',
    name: '舒芬太尼（演示）',
    genericName: '枸橼酸舒芬太尼注射液（演示）',
    specification: '50ug/1ml',
    unit: '支',
    dosageForm: 'INJECTION',
    category: 'OPIOID',
    narcotic: true,
    highRisk: true,
    aliases: ['舒芬（演示）'],
    status: 'ACTIVE',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: PHARMACIST.userId,
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: PHARMACIST.userId,
    version: 1,
    ...overrides,
  }
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

test.describe('M4 药品目录与麻精五专', () => {
  test('新建药品 → 专册登记 → 日结平账', async ({ page }) => {
    await seedSession(page, PHARMACIST)

    const drugs = [drugRecord()]
    const registers: Record<string, unknown>[] = []
    const closes: Record<string, unknown>[] = []

    // 通用药品路由（具体 status 路由在其后注册，Playwright 后注册优先）。
    await page.route('**/api/v1/drugs**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const json = (body: unknown, status = 200) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
      if (request.method() === 'POST' && url.pathname.endsWith('/drugs')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        const created = drugRecord({
          drugId: '2a000000-0000-4000-8000-000000000002',
          code: body.code,
          name: body.name,
          specification: body.specification,
          unit: body.unit,
          narcotic: body.narcotic ?? false,
          aliases: body.aliases ?? [],
        })
        drugs.push(created)
        return json(created, 201)
      }
      if (request.method() === 'GET') {
        return json({ items: drugs, page: 1, size: 50, total: drugs.length, totalPages: 1 })
      }
      return json(
        { code: 'RESOURCE_NOT_FOUND', message: '资源不存在', traceId: 'trace-e2e', details: [] },
        404,
      )
    })
    await page.route('**/api/v1/drugs/pharmacy-adapter/status', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          adapterCode: 'EXT-02',
          name: '药房系统适配器（模拟）',
          mode: 'SIMULATED',
          available: true,
          degraded: false,
          lastSyncedAt: '2026-11-14T08:00:00+08:00',
          pendingSyncCount: 0,
          message: '未接入真实药房系统（EXT-02），当前为模拟同步',
        }),
      }),
    )

    await page.route('**/api/v1/narcotic/departments', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [{ departmentId: DEPARTMENT_ID, code: 'DEPT-ANES', name: '麻醉科（合成）' }],
        }),
      }),
    )

    let registerDepartment = ''
    await page.route('**/api/v1/narcotic/registers**', async (route) => {
      const request = route.request()
      const json = (body: unknown, status = 200) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
      if (request.method() === 'POST') {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        registerDepartment = String(body.departmentId ?? '')
        const created = {
          registerId: '2b000000-0000-4000-8000-000000000001',
          drugId: body.drugId,
          drugName: '舒芬太尼（演示）',
          batchNo: body.batchNo,
          quantity: body.quantity,
          unit: body.unit,
          prescriptionNo: body.prescriptionNo,
          usedBy: body.usedBy,
          departmentId: DEPARTMENT_ID,
          usageAt: body.usageAt,
          destination: body.destination,
          firstSignerId: body.firstSignerId,
          firstSignerName: body.firstSignerName,
          secondSignerId: body.secondSignerId,
          secondSignerName: body.secondSignerName,
          createdAt: '2026-11-14T09:12:00+08:00',
          createdBy: PHARMACIST.userId,
        }
        registers.push(created)
        return json(created, 201)
      }
      return json({ items: registers })
    })
    await page.route('**/api/v1/narcotic/cabinet-handovers**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      }),
    )
    await page.route('**/api/v1/narcotic/baselines**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      }),
    )
    await page.route('**/api/v1/narcotic/daily-closes**', async (route) => {
      const body = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      const created = {
        closeId: '2e000000-0000-4000-8000-000000000001',
        closeDate: body.closeDate,
        shift: body.shift,
        cabinetCode: body.cabinetCode,
        departmentId: DEPARTMENT_ID,
        openingQuantity: body.openingQuantity,
        inboundQuantity: body.inboundQuantity,
        usedQuantity: body.usedQuantity,
        returnedQuantity: body.returnedQuantity,
        closingQuantity: body.closingQuantity,
        balanced: true,
        createdAt: '2026-11-14T16:10:00+08:00',
        createdBy: PHARMACIST.userId,
      }
      closes.push(created)
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(created),
      })
    })

    // 1) 药品目录：新建一个非麻精药品。
    await page.goto('/m4/drugs')
    await expect(page.getByTestId('drug-table')).toContainText('舒芬太尼（演示）')
    await page.getByTestId('drug-create').click()
    await page.getByTestId('drug-code').fill('DRUG-PRO-002')
    await page.getByTestId('drug-name').fill('丙泊酚（E2E 演示）')
    await page.getByTestId('drug-spec').fill('200mg/20ml')
    await page.getByTestId('drug-unit').fill('支')
    await page.getByTestId('drug-aliases').fill('丙泊酚注射液（E2E）')
    await page.getByTestId('drug-submit').click()
    await expect(page.getByTestId('drug-table')).toContainText('丙泊酚（E2E 演示）')

    // 2) 麻精五专：专册登记（双人签字不同人）。
    await page.goto('/m4/narcotic')
    await expect(page.getByTestId('adapter-card')).toContainText('模拟同步正常')
    // B-3：必须显式选择科室，禁止用当前用户标识回退。
    await page.getByTestId('narcotic-department').click()
    await page.getByRole('option', { name: /麻醉科/ }).click()
    await page.getByTestId('register-batch').fill('BATCH-E2E-0001')
    await page.getByTestId('register-rx').fill('RX-E2E-0001')
    await page.getByTestId('register-used-by').fill('演示使用人（合成）')
    await page.getByTestId('register-signer-a').fill(PHARMACIST.userId)
    await page.getByTestId('register-signer-b').fill(SECOND_SIGNER_ID)
    await page.getByTestId('register-submit').click()
    await expect(page.getByTestId('register-table')).toContainText('BATCH-E2E-0001')
    await expect(page.getByTestId('register-table')).toContainText('演示使用人（合成）')
    expect(registerDepartment).toBe(DEPARTMENT_ID)

    // 3) 日结/班结：期初 10 + 入库 4 − 使用 3 − 退回 1 = 期末 10，平账提示“一致”后提交。
    await page.getByRole('tab', { name: '日结/班结' }).click()
    await page.getByTestId('close-date').fill('2026-11-13')
    await page.getByTestId('close-opening').locator('input').fill('10')
    await page.getByTestId('close-inbound').locator('input').fill('4')
    await page.getByTestId('close-used').locator('input').fill('3')
    await page.getByTestId('close-returned').locator('input').fill('1')
    await page.getByTestId('close-closing').locator('input').fill('10')
    await expect(page.getByTestId('close-expected')).toContainText('一致')
    await page.getByTestId('close-submit').click()
    await expect(page.getByTestId('close-table')).toContainText('2026-11-13')
  })
})
