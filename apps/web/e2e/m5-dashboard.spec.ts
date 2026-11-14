import { expect, test, type Page } from '@playwright/test'

/**
 * 实时态势与 KPI 看板（M5-M18-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：大屏首屏加载（术间状态/台次/告警/设备/KPI）→ 手动刷新 → 10s 自动刷新
 * → 暂停刷新。聚合口径/权限 403/DataScope/无 PHI 由 `M5DashboardApiIntegrationTest`
 * 与 `DashboardServiceImplTest` 覆盖。
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

const OR_MANAGER: SyntheticUser = {
  userId: '1000000000000000011',
  username: 'synth_dashboard_or',
  displayName: '演示手术室管理员（合成）',
  roles: ['SYS_OR_MANAGER'],
  permissions: ['m5:dashboard:read'],
  dataScopes: [{ type: 'DEPT', scopeIds: ['4000000000000000011'] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const NO_DASHBOARD: SyntheticUser = {
  ...OR_MANAGER,
  userId: '1000000000000000012',
  username: 'synth_dashboard_surgeon',
  roles: ['SYS_SURGEON'],
  permissions: ['m1:schedule:read'],
}

const REALTIME = {
  updatedAt: '2026-11-15T12:00:12+08:00',
  timezone: 'Asia/Shanghai',
  dataDelaySeconds: 12,
  dataQuality: 'OK',
  dataScope: 'DEPT',
  roomStatusCounts: {
    total: 3,
    inSurgery: 1,
    idle: 1,
    cleaning: 1,
    preparing: 0,
    recovery: 0,
    outOfService: 0,
  },
  rooms: [
    {
      roomId: 'f2000000-0000-4000-8000-0000000000c1',
      roomNo: 'OR-01',
      roomName: '一号手术间（合成）',
      deptId: 'f1000000-0000-4000-8000-000000000002',
      deptName: '麻醉科（合成）',
      status: 'IN_SURGERY',
      caseInProgress: true,
      plannedCaseCount: 2,
    },
    {
      roomId: 'f2000000-0000-4000-8000-0000000000c2',
      roomNo: 'OR-02',
      roomName: '二号手术间（合成）',
      deptId: 'f1000000-0000-4000-8000-000000000002',
      deptName: '麻醉科（合成）',
      status: 'IDLE',
      caseInProgress: false,
      plannedCaseCount: 0,
    },
    {
      roomId: 'f2000000-0000-4000-8000-0000000000c5',
      roomNo: 'OR-05',
      roomName: '五号手术间（合成）',
      deptId: 'f1000000-0000-4000-8000-000000000003',
      deptName: '手术室（合成）',
      status: 'CLEANING',
      caseInProgress: false,
      plannedCaseCount: 1,
    },
  ],
  caseProgress: {
    totalCount: 3,
    activeCount: 1,
    statusCounts: [
      { status: 'IN_PROGRESS', count: 1 },
      { status: 'PLANNED', count: 1 },
      { status: 'COMPLETED', count: 1 },
    ],
  },
  alerts: {
    unacknowledgedCount: 2,
    acknowledgedCount: 1,
    ignoredCount: 0,
    criticalCount: 1,
    warnCount: 1,
    infoCount: 0,
    latestTriggeredAt: '2026-11-15T11:58:40+08:00',
  },
  devices: { total: 4, online: 3, offline: 1, maintenance: 0, offlineTimeoutSeconds: 120 },
}

const KPI = {
  date: '2026-11-15',
  timezone: 'Asia/Shanghai',
  updatedAt: '2026-11-15T12:00:12+08:00',
  dataDelaySeconds: 12,
  dataQuality: 'OK',
  dataScope: 'DEPT',
  caseCount: 3,
  totalCaseCount: 4,
  startedCount: 2,
  completedCount: 1,
  cancelledCount: 1,
  onTimeEvaluatedCount: 2,
  onTimeCount: 1,
  onTimeRate: 0.5,
  onTimeThresholdMinutes: 15,
  cancellationRate: 0.25,
  utilizationRate: 0.35,
  totalSurgeryMinutes: 630,
  availableMinutes: 1800,
  roomCount: 3,
  alertCount: 3,
  criticalAlertCount: 1,
  averageAnesthesiaMinutes: 107.5,
  recordedCaseCount: 2,
  unrecordedCaseCount: 1,
  formula: '台次 = 当日非取消台次；准点率 = 准点首台 / 已评估首台',
  pendingConfirmations: ['利用率计划口径【待确认】'],
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

test.describe('M5-M18-01 实时态势与 KPI 看板', () => {
  test('看板加载 → 手动刷新 → 10s 自动刷新 → 暂停刷新', async ({ page }) => {
    await seedSession(page, OR_MANAGER)
    const realtimeUrls: string[] = []
    const kpiUrls: string[] = []
    await page.route(/\/api\/v1\/dashboard\/realtime/, (route) => {
      realtimeUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(REALTIME),
      })
    })
    await page.route(/\/api\/v1\/dashboard\/kpi/, (route) => {
      kpiUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(KPI),
      })
    })

    await page.goto('/m5/dashboard')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('实时态势与 KPI 看板')
    await expect(page.getByTestId('room-status-inSurgery')).toHaveText('1')
    await expect(page.getByTestId('room-status-cleaning')).toHaveText('1')
    await expect(page.getByTestId('case-progress-detail')).toContainText('进行中 1')
    await expect(page.getByTestId('alert-summary')).toContainText('未确认告警')
    await expect(page.getByTestId('device-summary')).toContainText('3 /')
    await expect(page.getByTestId('kpi-caseCount')).toHaveText('3')
    await expect(page.getByTestId('kpi-onTimeRate')).toHaveText('50.0%')
    await expect(page.getByTestId('kpi-averageAnesthesiaMinutes')).toHaveText('107.5 分钟')
    await expect(page.getByTestId('room-list')).toContainText('OR-01')
    await expect(page.getByTestId('data-quality')).toHaveText('数据正常')
    await expect(page.getByTestId('last-updated')).toContainText('延迟 12 秒')
    expect(realtimeUrls.length).toBe(1)
    expect(kpiUrls.length).toBe(1)
    expect(kpiUrls[0]).toContain('/api/v1/dashboard/kpi?date=')
    console.log(
      `[M5-M18-01][e2e] role=SYS_OR_MANAGER realtime=${realtimeUrls.length} kpi=${kpiUrls.length}`,
    )

    // 手动刷新：两个端点各再请求一次。
    await page.getByTestId('refresh-now').click()
    await expect.poll(() => realtimeUrls.length).toBe(2)
    await expect.poll(() => kpiUrls.length).toBe(2)

    // 10s 自动刷新：不做任何操作，等待轮询再请求一次（契约固定 10s）。
    await expect.poll(() => realtimeUrls.length, { timeout: 15_000 }).toBe(3)
    await expect.poll(() => kpiUrls.length, { timeout: 15_000 }).toBe(3)

    // 暂停刷新：按钮切换文案，并停止后续轮询。
    await page.getByTestId('refresh-toggle').click()
    await expect(page.getByTestId('refresh-toggle')).toHaveText('继续刷新')
  })

  test('无 m5:dashboard:read 时守卫跳转 403 页面', async ({ page }) => {
    await seedSession(page, NO_DASHBOARD)
    await page.goto('/m5/dashboard')
    await expect(page.getByRole('heading', { name: '无权访问该页面' })).toBeVisible()
    await expect(page).toHaveURL(/\/403/)
    await expect(page.getByTestId('dashboard-page')).toHaveCount(0)
  })
})
