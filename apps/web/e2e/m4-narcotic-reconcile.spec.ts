import { expect, test, type Page } from '@playwright/test'

/**
 * 麻精五专日结对账（M4-QA-01 补充）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖负例 → 修正的主链路：日结参数不平 → 服务端 409（details 给出期望/实盘）→ 页面展示
 * 差异明细 → 修正期末后平账提交成功。真实算术校验、append-only、审计与数据范围由
 * `M4AcceptanceTest` / `M4DrugApiIntegrationTest` 覆盖；本用例只验证页面能正确呈现 409
 * 对账差异并完成修正。
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

const DEPARTMENT_ID = 'f1000000-0000-4000-8000-000000000002'
const DRUG_ID = '2a000000-0000-4000-8000-000000000001'

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

test.describe('M4-QA-01 麻精五专日结对账', () => {
  test('日结不平 409 → 差异明细 → 修正期末后平账', async ({ page }) => {
    await seedSession(page, PHARMACIST)

    const closeBodies: Record<string, unknown>[] = []
    const closeFailures: number[] = []
    const json = (body: unknown) => JSON.stringify(body)

    await page.route('**/api/v1/drugs**', (route) => {
      const url = new URL(route.request().url())
      if (url.pathname.endsWith('/pharmacy-adapter/status')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: json({
            adapterCode: 'EXT-02',
            name: '药房系统适配器（模拟）',
            mode: 'SIMULATED',
            available: true,
            degraded: false,
            lastSyncedAt: '2026-11-14T08:00:00+08:00',
            pendingSyncCount: 0,
            message: '未接入真实药房系统（EXT-02），当前为模拟同步',
          }),
        })
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: json({
          items: [
            {
              drugId: DRUG_ID,
              code: 'DRUG-SUF-001',
              name: '舒芬太尼（演示）',
              specification: '50ug/1ml',
              unit: '支',
              dosageForm: 'INJECTION',
              category: 'OPIOID',
              narcotic: true,
              highRisk: true,
              aliases: [],
              status: 'ACTIVE',
              version: 1,
            },
          ],
          page: 1,
          size: 100,
          total: 1,
          totalPages: 1,
        }),
      })
    })
    await page.route('**/api/v1/narcotic/departments', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: json({
          items: [{ departmentId: DEPARTMENT_ID, code: 'DEPT-ANES', name: '麻醉科（合成）' }],
        }),
      }),
    )
    await page.route('**/api/v1/narcotic/registers**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: json({ items: [] }) }),
    )
    await page.route('**/api/v1/narcotic/cabinet-handovers**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: json({ items: [] }) }),
    )
    await page.route('**/api/v1/narcotic/baselines**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: json({ items: [] }) }),
    )
    await page.route('**/api/v1/narcotic/daily-closes**', (route) => {
      const request = route.request()
      if (request.method() !== 'POST') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: json({ items: [] }),
        })
      }
      const body = JSON.parse(request.postData() ?? '{}') as Record<string, number | string>
      const opening = Number(body.openingQuantity ?? 0)
      const inbound = Number(body.inboundQuantity ?? 0)
      const used = Number(body.usedQuantity ?? 0)
      const returned = Number(body.returnedQuantity ?? 0)
      const closing = Number(body.closingQuantity ?? 0)
      const expected = opening + inbound - used - returned
      closeBodies.push(body)
      if (closing !== expected) {
        closeFailures.push(closing)
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: json({
            code: 'STATE_CONFLICT',
            message: '平账校验未通过，操作未生效',
            traceId: 'trace-e2e-close',
            details: [
              {
                field: 'closingQuantity',
                reason: `平账校验未通过：期望 ${expected}，实盘 ${closing}（期初 ${opening} + 入库 ${inbound} − 使用 ${used} − 退回 ${returned}）`,
              },
            ],
          }),
        })
      }
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: json({
          closeId: '2e000000-0000-4000-8000-000000000001',
          closeDate: body.closeDate,
          shift: body.shift,
          cabinetCode: body.cabinetCode,
          departmentId: DEPARTMENT_ID,
          openingQuantity: opening,
          inboundQuantity: inbound,
          usedQuantity: used,
          returnedQuantity: returned,
          closingQuantity: closing,
          balanced: true,
          createdAt: '2026-11-14T16:10:00+08:00',
          createdBy: PHARMACIST.userId,
        }),
      })
    })

    await page.goto('/m4/narcotic')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('麻精五专')
    await page.getByTestId('narcotic-department').click()
    await page.getByRole('option', { name: /麻醉科/ }).click()

    // 期初 10 + 入库 4 − 使用 3 − 退回 0 = 期望 11；先填实盘 10 制造不平。
    await page.getByRole('tab', { name: '日结/班结' }).click()
    await page.getByTestId('close-date').fill('2026-11-13')
    await page.getByTestId('close-shift').click()
    await page.getByRole('option', { name: '白班' }).click()
    await page.getByTestId('close-cabinet').fill('CAB-E2E-0001')
    await page.getByTestId('close-opening').locator('input').fill('10')
    await page.getByTestId('close-inbound').locator('input').fill('4')
    await page.getByTestId('close-used').locator('input').fill('3')
    await page.getByTestId('close-returned').locator('input').fill('0')
    await page.getByTestId('close-closing').locator('input').fill('10')
    await expect(page.getByTestId('close-expected')).toContainText('不一致')
    await expect(page.getByTestId('close-expected')).toContainText('期望 11')

    await page.getByTestId('close-submit').click()
    await expect(page.getByTestId('narcotic-error')).toContainText('平账校验未通过')
    await expect(page.getByTestId('close-details')).toContainText('closingQuantity')
    await expect(page.getByTestId('close-details')).toContainText('期望 11')
    await expect(page.getByTestId('close-details')).toContainText('实盘 10')
    expect(closeFailures).toEqual([10])

    // 修正期末为 11：提示“一致”，提交成功并出现在日结台账。
    await page.getByTestId('close-closing').locator('input').fill('11')
    await expect(page.getByTestId('close-expected')).toContainText('一致')
    await page.getByTestId('close-submit').click()
    await expect(page.getByTestId('close-table')).toContainText('2026-11-13')
    await expect(page.getByTestId('close-table')).toContainText('11')
    expect(closeBodies).toHaveLength(2)
    expect(closeBodies[1]?.closingQuantity).toBe(11)
    console.log(
      `[M4-QA-01][e2e] 日结不平 409 次数=${closeFailures.length} ` +
        `修正后期末=${String(closeBodies[1]?.closingQuantity)}`,
    )
  })
})
