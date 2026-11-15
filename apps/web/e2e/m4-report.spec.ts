import { expect, test, type Page } from '@playwright/test'

/**
 * 工作量与多维报表页（M4-M16-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：筛选切换（日期/维度/多维组合）→ 查询 → 工作量与多维结果 → 导出 CSV 请求；
 * 以及无 m4:report:read 时的 403 守卫。手算口径/白名单 400/DataScope/CSV BOM 与防注入
 * 由 `M4ReportApiIntegrationTest` 与 `ReportServiceImplTest` 覆盖。
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

const QC: SyntheticUser = {
  userId: '1000000000000000005',
  username: 'synth_report_qc',
  displayName: '演示质控专员（合成）',
  roles: ['SYS_QC_MANAGER'],
  permissions: ['m4:report:read', 'm4:report:export'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const LOCKER: SyntheticUser = {
  ...QC,
  userId: '1000000000000000007',
  username: 'synth_report_locker',
  permissions: ['m4:report:read', 'm4:report:export', 'm4:report:lock'],
}

const NO_REPORT: SyntheticUser = {
  ...QC,
  userId: '1000000000000000006',
  username: 'synth_report_surgeon',
  roles: ['SYS_SURGEON'],
  permissions: ['m1:schedule:read'],
}

const DEPT_ANES = 'f1000000-0000-4000-8000-000000000002'
const DEPT_OR = 'f1000000-0000-4000-8000-000000000003'

const WORKLOAD = {
  from: '2031-03-01',
  to: '2031-03-31',
  timezone: 'Asia/Shanghai',
  dimension: 'DEPT',
  formula: '台次 = 统计边界内非取消台次；麻醉时长 = floor((ended_at - started_at) / 60s)',
  weightFormula: '加权台次 = Σ 台次权重（默认 1.0，可按术式编码配置）',
  caseCount: 3,
  anesthesiaMinutes: 215,
  weightedCaseCount: 3.5,
  unrecordedCaseCount: 1,
  buckets: [
    {
      key: DEPT_ANES,
      label: '麻醉科（合成）',
      caseCount: 2,
      anesthesiaMinutes: 125,
      weightedCaseCount: 2.5,
      unrecordedCaseCount: 1,
      surgeryDistribution: [
        {
          surgeryCode: 'SYNTH-ART-001',
          surgeryName: '演示关节置换术（合成）',
          caseCount: 1,
          anesthesiaMinutes: 125,
        },
      ],
    },
    {
      key: DEPT_OR,
      label: '手术室（合成）',
      caseCount: 1,
      anesthesiaMinutes: 90,
      weightedCaseCount: 1,
      unrecordedCaseCount: 0,
      surgeryDistribution: [],
    },
  ],
}

const MULTI = {
  from: '2031-03-01',
  to: '2031-03-31',
  timezone: 'Asia/Shanghai',
  dimensions: ['DEPT', 'SURGERY'],
  metrics: ['caseCount', 'anesthesiaMinutes'],
  formula: WORKLOAD.formula,
  weightFormula: WORKLOAD.weightFormula,
  rows: [
    {
      deptId: DEPT_ANES,
      deptName: '麻醉科（合成）',
      surgeryCode: 'SYNTH-ART-001',
      surgeryName: '演示关节置换术（合成）',
      caseCount: 1,
      anesthesiaMinutes: 125,
      weightedCaseCount: 1.5,
      unrecordedCaseCount: 0,
    },
    {
      deptId: DEPT_ANES,
      deptName: '麻醉科（合成）',
      surgeryCode: 'SYNTH-GAS-002',
      surgeryName: '演示胃肠手术（合成）',
      caseCount: 1,
      anesthesiaMinutes: 0,
      weightedCaseCount: 1,
      unrecordedCaseCount: 1,
    },
  ],
  totals: {
    caseCount: 3,
    anesthesiaMinutes: 215,
    weightedCaseCount: 3.5,
    unrecordedCaseCount: 1,
    groupCount: 3,
  },
  page: 1,
  size: 20,
  total: 3,
  totalPages: 1,
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

test.describe('M4-M16-01 工作量与多维报表', () => {
  test('筛选切换 → 查询 → 导出工作量 CSV', async ({ page }) => {
    await seedSession(page, QC)
    const workloadUrls: string[] = []
    const multiUrls: string[] = []
    const exportUrls: string[] = []
    await page.route(/\/api\/v1\/reports\/export\?/, (route) => {
      exportUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'text/csv;charset=UTF-8',
        body: 'report,level,dimension,key,label\r\nworkload,TOTAL,DEPT,ALL,全部\r\n',
      })
    })
    await page.route(/\/api\/v1\/reports\/workload\?/, (route) => {
      workloadUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(WORKLOAD),
      })
    })
    await page.route(/\/api\/v1\/reports\/multi\?/, (route) => {
      multiUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MULTI),
      })
    })

    await page.goto('/m4/reports')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('工作量与多维报表')
    await expect(page.getByTestId('workload-table')).toContainText('麻醉科（合成）')
    await expect(page.getByTestId('workload-table')).toContainText('演示关节置换术（合成）')
    await expect(page.getByTestId('multi-table')).toContainText('演示胃肠手术（合成）')
    await expect(page.getByTestId('multi-total')).toContainText('合计：3 台次')
    await expect(page.getByTestId('multi-total')).toContainText('215 分钟')
    expect(workloadUrls.length).toBe(1)
    expect(multiUrls.length).toBe(1)
    expect(workloadUrls[0]).toContain('dimension=DEPT')
    expect(multiUrls[0]).toContain('dimensions=DEPT%2CSURGERY')

    // 重新查询：两个接口各再请求一次（筛选条件来自页面默认值）。
    await page.getByTestId('query-button').click()
    await expect.poll(() => workloadUrls.length).toBe(2)
    await expect.poll(() => multiUrls.length).toBe(2)

    // 切换工作量维度（DEPT → SURGERY）后重查：断言筛选条件真实进入请求，
    // 而不是只点“查询”复用默认值（用例名与内容一致）。
    await page.getByTestId('dimension-select').click()
    await page.getByRole('option', { name: '工作量维度：术式' }).click()
    await page.getByTestId('query-button').click()
    await expect.poll(() => workloadUrls.length).toBe(3)
    await expect.poll(() => multiUrls.length).toBe(3)
    expect(workloadUrls[2]).toContain('dimension=SURGERY')
    await expect(page.getByTestId('workload-table')).toBeVisible()

    await page.getByTestId('export-workload').click()
    await expect.poll(() => exportUrls.length).toBe(1)
    expect(exportUrls[0]).toContain('report=workload')
    expect(exportUrls[0]).toContain('format=csv')
    expect(exportUrls[0]).toContain('dimension=SURGERY')
    await expect(page.getByText('工作量 CSV 已导出')).toBeVisible()
    console.log(
      `[M4-M16-01][e2e] role=SYS_QC_MANAGER workloadCalls=${workloadUrls.length} ` +
        `multiCalls=${multiUrls.length} export=${exportUrls[0]}`,
    )
  })

  test('锁定当前报表 → 同参数查询返回快照标识', async ({ page }) => {
    await seedSession(page, LOCKER)
    let locked = false
    const lockBodies: string[] = []
    await page.route(/\/api\/v1\/reports\/locks$/, (route) => {
      lockBodies.push(route.request().postData() ?? '')
      locked = true
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          lockId: '5a000000-0000-4000-8000-0000000000e1',
          report: 'workload',
          period: '2031-03-01~2031-03-31',
          from: '2031-03-01',
          to: '2031-03-31',
          params: { report: 'workload', from: '2031-03-01', to: '2031-03-31' },
          paramsHash: 'a'.repeat(64),
          payloadHash: 'b'.repeat(64),
          dataScope: 'ALL',
          rowCount: 2,
          lockedBy: '1000000000000000007',
          lockedByName: '演示质控专员（合成）',
          lockedAt: '2031-04-01T09:30:00+08:00',
        }),
      })
    })
    await page.route(/\/api\/v1\/reports\/workload\?/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          locked
            ? {
                ...WORKLOAD,
                source: 'SNAPSHOT',
                lockId: '5a000000-0000-4000-8000-0000000000e1',
                lockedAt: '2031-04-01T09:30:00+08:00',
              }
            : { ...WORKLOAD, source: 'LIVE' },
        ),
      }),
    )
    await page.route(/\/api\/v1\/reports\/multi\?/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ...MULTI, source: 'LIVE' }),
      }),
    )

    await page.goto('/m4/reports')
    await expect(page.getByTestId('report-lock')).toBeVisible()
    await expect(page.getByTestId('report-snapshot')).toHaveCount(0)

    await page.getByTestId('report-lock').click()
    await expect(page.getByText('同参数查询/导出改用快照')).toBeVisible()
    expect(lockBodies).toHaveLength(1)
    expect(lockBodies[0]).toContain('"report":"workload"')
    await expect(page.getByTestId('report-snapshot')).toContainText('快照')
    await expect(page.getByTestId('report-snapshot')).toContainText('2031-04-01')
    console.log(`[M8-M4-02][e2e] report lock snapshot body=${lockBodies[0]}`)
  })

  test('无 m4:report:read 时守卫跳转 403 页面', async ({ page }) => {
    await seedSession(page, NO_REPORT)
    await page.goto('/m4/reports')
    await expect(page.getByRole('heading', { name: '无权访问该页面' })).toBeVisible()
    await expect(page).toHaveURL(/\/403/)
    await expect(page.getByTestId('report-page')).toHaveCount(0)
  })
})
