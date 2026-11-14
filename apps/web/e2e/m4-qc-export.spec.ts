import { expect, test, type Page } from '@playwright/test'

/**
 * 质控指标导出（M4-QA-01 补充）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：指标看板加载定义/结果 → 导出按钮按当前区间请求 `GET /v1/qc/export`
 * （type=indicator、format=csv、Accept: text/csv）→ 页面提示“已导出（脱敏）”。
 * 服务端 BOM/防公式注入/无自由文本/审计 dataRows 由 `M4AcceptanceTest` 与
 * `M4QcApiIntegrationTest` 覆盖；本用例只验证前端把当前筛选区间与导出类型真实传下去。
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

const QC_USER: SyntheticUser = {
  userId: '1000000000000000011',
  username: 'synth_qc_manager',
  displayName: '演示质控专员（合成）',
  roles: ['SYS_QC_MANAGER'],
  permissions: ['m4:qc:read', 'm4:qc:write', 'm4:qc:review', 'm4:qc:export'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const DEFINITIONS = {
  items: [
    {
      id: '7c000000-0000-4000-8000-000000000001',
      code: 'QC-SYN-STRUCT-001',
      name: '麻醉记录单完成率',
      category: 'STRUCTURE',
      definition: '已完成记录单台次 / 同期非取消台次 × 100%【待确认】',
      dataSource: 'M3 麻醉记录单 + M2 排程',
      numeratorLabel: '已完成记录单台次',
      denominatorLabel: '非取消台次',
      unit: 'PERCENT',
      thresholdOperator: 'GTE',
      thresholdValue: 95,
      version: '1.0.0',
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
      status: 'ACTIVE',
      provisional: true,
    },
  ],
}

const RESULTS = {
  from: '2026-10-01',
  to: '2026-10-31',
  timezone: 'Asia/Shanghai',
  formula: 'value = round(numerator / denominator × 100, 2)',
  items: [
    {
      indicatorId: '7c000000-0000-4000-8000-000000000001',
      code: 'QC-SYN-STRUCT-001',
      name: '麻醉记录单完成率',
      category: 'STRUCTURE',
      indicatorVersion: '1.0.0',
      definition: '已完成记录单台次 / 同期非取消台次 × 100%【待确认】',
      unit: 'PERCENT',
      periodFrom: '2026-10-01',
      periodTo: '2026-10-31',
      deptId: null,
      numerator: 2,
      denominator: 4,
      value: 50,
      thresholdOperator: 'GTE',
      thresholdValue: 95,
      targetMet: false,
      computedAt: '2026-10-31T03:00:00Z',
      provisional: true,
    },
  ],
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

test.describe('M4-QA-01 质控指标导出', () => {
  test('按当前区间导出质控指标 CSV（脱敏提示）', async ({ page }) => {
    await seedSession(page, QC_USER)

    const exportUrls: string[] = []
    const exportAccepts: string[] = []
    await page.route('**/api/v1/qc/indicators', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(DEFINITIONS),
      }),
    )
    await page.route(/\/api\/v1\/qc\/indicator-results\?/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(RESULTS),
      }),
    )
    await page.route(/\/api\/v1\/qc\/export\?/, (route) => {
      exportUrls.push(route.request().url())
      exportAccepts.push(route.request().headers()['accept'] ?? '')
      return route.fulfill({
        status: 200,
        contentType: 'text/csv;charset=UTF-8',
        body:
          '\uFEFFtype,code,name,category,indicatorVersion,periodFrom,periodTo,deptId,deptName,' +
          'numerator,denominator,value,unit,thresholdOperator,thresholdValue,targetMet,' +
          'inTargetStatistics,provisional,timezone\r\n' +
          'qc-indicator,QC-SYN-STRUCT-001,麻醉记录单完成率,STRUCTURE,1.0.0,2026-10-01,' +
          '2026-10-31,,,2,4,50.00,PERCENT,GTE,95.00,false,true,true,Asia/Shanghai\r\n',
      })
    })

    await page.goto('/m4/qc/indicators')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('质控指标看板')
    await expect(page.getByTestId('result-table')).toContainText('麻醉记录单完成率')
    await expect(page.getByTestId('result-table')).toContainText('50.00%')
    await expect(page.getByTestId('target-QC-SYN-STRUCT-001')).toContainText('未达标')
    await expect(page.getByTestId('target-met-count')).toContainText('达标 0 / 1')

    await page.getByTestId('export-indicator').click()
    await expect.poll(() => exportUrls.length).toBe(1)
    const url = new URL(exportUrls[0] ?? 'http://127.0.0.1/')
    expect(url.pathname).toBe('/api/v1/qc/export')
    expect(url.searchParams.get('type')).toBe('indicator')
    expect(url.searchParams.get('format')).toBe('csv')
    expect(url.searchParams.get('from')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(url.searchParams.get('to')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(exportAccepts[0]).toContain('text/csv')
    await expect(page.getByText('质控指标 CSV 已导出（脱敏）')).toBeVisible()
    console.log(`[M4-QA-01][e2e] QC export=${exportUrls[0]} accept=${exportAccepts[0]}`)
  })
})
