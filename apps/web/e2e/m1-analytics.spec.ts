import { expect, test, type Page } from '@playwright/test'

/**
 * 排程分析页（M2-M6-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：三指标卡片/表格、查询刷新、CSV 导出请求、无权限 403 页面。
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

const ANALYST: SyntheticUser = {
  userId: '1000000000000000003',
  username: 'synth_qc',
  displayName: '演示质控专员（合成）',
  roles: ['SYS_QC_MANAGER'],
  permissions: ['m1:analytics:read'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const NO_ANALYTICS: SyntheticUser = {
  ...ANALYST,
  userId: '1000000000000000004',
  username: 'synth_surgeon',
  roles: ['SYS_SURGEON'],
  permissions: ['m1:schedule:read'],
}

const ROOM_ONE = {
  roomId: 'f2000000-0000-4000-8000-0000000000c1',
  roomNo: 'OR-01',
  roomName: '演示 1 号手术间（合成）',
  roomType: 'GENERAL',
  siteId: 'f1000000-0000-4000-8000-000000000001',
  deptId: 'f1000000-0000-4000-8000-000000000002',
  deptName: '麻醉科（合成）',
  status: 'IDLE',
  isolationRequired: false,
  equipmentTags: [],
  createdAt: '2026-11-13T08:00:00+08:00',
  createdBy: null,
  updatedAt: '2026-11-13T08:00:00+08:00',
  updatedBy: null,
  version: 1,
}

const ROOM_TWO = {
  ...ROOM_ONE,
  roomId: 'f2000000-0000-4000-8000-0000000000c2',
  roomNo: 'OR-02',
}

const UTILIZATION = {
  from: '2026-12-01',
  to: '2026-12-02',
  timezone: 'Asia/Shanghai',
  formula: '总手术时长 / 可用时长',
  availableMinutesPerRoomPerDay: 600,
  days: 2,
  roomCount: 2,
  summary: {
    totalSurgeryMinutes: 420,
    availableMinutes: 2400,
    utilizationRate: 0.175,
    caseCount: 5,
    roomCount: 2,
    days: 2,
  },
  rooms: [
    {
      roomId: ROOM_ONE.roomId,
      roomNo: 'OR-01',
      deptId: ROOM_ONE.deptId,
      deptName: ROOM_ONE.deptName,
      totalSurgeryMinutes: 270,
      availableMinutes: 1200,
      utilizationRate: 0.225,
      caseCount: 3,
    },
    {
      roomId: ROOM_TWO.roomId,
      roomNo: 'OR-02',
      deptId: ROOM_TWO.deptId,
      deptName: ROOM_TWO.deptName,
      totalSurgeryMinutes: 150,
      availableMinutes: 1200,
      utilizationRate: 0.125,
      caseCount: 2,
    },
  ],
}

const ON_TIME = {
  from: '2026-12-01',
  to: '2026-12-02',
  timezone: 'Asia/Shanghai',
  thresholdMinutes: 15,
  actualStartSource: 'SCHEDULE_UPDATED_AT',
  summary: {
    firstCaseCount: 3,
    evaluatedCaseCount: 3,
    onTimeCount: 2,
    delayedCount: 1,
    onTimeRate: 0.6667,
  },
  rooms: [
    {
      roomId: ROOM_ONE.roomId,
      roomNo: 'OR-01',
      deptId: ROOM_ONE.deptId,
      deptName: ROOM_ONE.deptName,
      firstCaseCount: 1,
      evaluatedCaseCount: 1,
      onTimeCount: 1,
      delayedCount: 0,
      onTimeRate: 1,
    },
    {
      roomId: ROOM_TWO.roomId,
      roomNo: 'OR-02',
      deptId: ROOM_TWO.deptId,
      deptName: ROOM_TWO.deptName,
      firstCaseCount: 2,
      evaluatedCaseCount: 2,
      onTimeCount: 1,
      delayedCount: 1,
      onTimeRate: 0.5,
    },
  ],
}

const CANCELLATIONS = {
  from: '2026-12-01',
  to: '2026-12-02',
  timezone: 'Asia/Shanghai',
  summary: { totalCases: 7, cancelledCases: 2, cancellationRate: 0.2857 },
  byReason: [
    { reasonCode: 'PATIENT_CONDITION', cancelledCases: 1, shareOfCancelled: 0.5 },
    { reasonCode: 'EMERGENCY_PREEMPT', cancelledCases: 1, shareOfCancelled: 0.5 },
  ],
  byRoom: [
    {
      roomId: ROOM_ONE.roomId,
      roomNo: 'OR-01',
      deptId: ROOM_ONE.deptId,
      deptName: ROOM_ONE.deptName,
      totalCases: 3,
      cancelledCases: 0,
      cancellationRate: 0,
    },
    {
      roomId: ROOM_TWO.roomId,
      roomNo: 'OR-02',
      deptId: ROOM_TWO.deptId,
      deptName: ROOM_TWO.deptName,
      totalCases: 2,
      cancelledCases: 1,
      cancellationRate: 0.5,
    },
  ],
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

async function routeReports(page: Page): Promise<void> {
  await page.route(/\/api\/v1\/scheduling\/analytics\/utilization\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(UTILIZATION),
    }),
  )
  await page.route(/\/api\/v1\/scheduling\/analytics\/on-time\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ON_TIME),
    }),
  )
  await page.route(/\/api\/v1\/scheduling\/analytics\/cancellations\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(CANCELLATIONS),
    }),
  )
}

test.describe('M2-M6-01 排程分析', () => {
  test('SYS_QC_MANAGER 加载三指标、科室下拉可用并触发 CSV 导出（不请求 rooms）', async ({
    page,
  }) => {
    await seedSession(page, ANALYST)
    await routeReports(page)
    let roomRequests = 0
    await page.route('**/api/v1/scheduling/rooms', (route) => {
      roomRequests += 1
      return route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'AUTH_FORBIDDEN', message: '无权限', details: [] }),
      })
    })
    const exportUrls: string[] = []
    await page.route(/\/api\/v1\/scheduling\/analytics\/export\?/, (route) => {
      exportUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'text/csv;charset=UTF-8',
        body: 'metric,from,to,scope,roomNo,deptName,reasonCode\r\n',
      })
    })

    await page.goto('/m1/analytics')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('排程分析')
    await expect(page.getByTestId('utilization-card')).toContainText('17.5%')
    await expect(page.getByTestId('on-time-card')).toContainText('66.7%')
    await expect(page.getByTestId('cancellations-card')).toContainText('28.6%')
    await expect(page.getByTestId('utilization-table')).toContainText('OR-01')
    await expect(page.getByTestId('utilization-table')).toContainText('270')
    await expect(page.getByTestId('on-time-table')).toContainText('延误')
    await expect(page.getByTestId('cancellation-reason-table')).toContainText('患者情况变化')
    await expect(page.getByTestId('cancellation-reason-table')).toContainText('急诊抢占')

    // S-1：QC 仅有 m1:analytics:read，科室下拉从分析响应 rooms[] 派生，不调用 rooms 接口。
    await page.getByTestId('dept-filter').click()
    await expect(
      page.locator('.el-select-dropdown__item', { hasText: '麻醉科（合成）' }),
    ).toBeVisible()
    expect(roomRequests).toBe(0)
    await page.keyboard.press('Escape')

    await page.getByTestId('query-button').click()
    await expect(page.getByTestId('utilization-card')).toContainText('17.5%')

    await page.getByTestId('export-utilization').click()
    await expect.poll(() => exportUrls.length).toBe(1)
    expect(exportUrls[0]).toContain('metric=utilization')
    expect(exportUrls[0]).toContain('format=csv')
    await expect(page.getByText('术间利用率 CSV 已导出')).toBeVisible()
    console.log(
      `[M2-M6-01][e2e] role=SYS_QC_MANAGER cards=3 tables=4 deptOptions=2 roomsCalls=${roomRequests} export=${exportUrls[0]}`,
    )
  })

  test('无 m1:analytics:read 时守卫跳转 403 页面', async ({ page }) => {
    await seedSession(page, NO_ANALYTICS)
    await page.goto('/m1/analytics')
    await expect(page.getByRole('heading', { name: '无权访问该页面' })).toBeVisible()
    await expect(page).toHaveURL(/\/403/)
    await expect(page.getByTestId('analytics-page')).toHaveCount(0)
  })
})
