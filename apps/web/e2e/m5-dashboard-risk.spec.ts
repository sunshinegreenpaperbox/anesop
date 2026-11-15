import { expect, test, type Page } from '@playwright/test'

/**
 * 风险与资源大屏（M5-M18-02）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：风险分层/资源/趋势首屏渲染 → 手动刷新 → 暂停刷新。
 * 聚合口径（手算）、空数据、403/DataScope 与无 PHI 由
 * `M5DashboardRiskResourceApiIntegrationTest` 与 `DashboardRiskResourcesServiceImplTest` 覆盖。
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
  userId: '1000000000000000021',
  username: 'synth_risk_or',
  displayName: '演示手术室管理员（合成）',
  roles: ['SYS_OR_MANAGER'],
  permissions: ['m5:dashboard:read'],
  dataScopes: [{ type: 'DEPT', scopeIds: ['4000000000000000021'] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const RISK = {
  updatedAt: '2026-11-15T12:00:12+08:00',
  timezone: 'Asia/Shanghai',
  date: '2026-11-15',
  dataDelaySeconds: 12,
  dataQuality: 'OK',
  dataScope: 'DEPT',
  totalCaseCount: 6,
  assessedCaseCount: 4,
  unassessedCaseCount: 2,
  highRiskCaseCount: 2,
  asaClassCounts: [
    { asaClass: 'I', count: 1 },
    { asaClass: 'II', count: 2 },
    { asaClass: 'III', count: 1 },
  ],
  riskLevelCounts: [
    { riskLevel: 'LOW', count: 2 },
    { riskLevel: 'MODERATE', count: 1 },
    { riskLevel: 'HIGH', count: 1 },
  ],
  severeAlertCount: 1,
  overdueCaseCount: 1,
  delayedStartCount: 1,
  overrunCaseCount: 0,
  overdueThresholdMinutes: 0,
  formula: '风险分层 = 非取消台次按 ASA 与风险等级计数',
  pendingConfirmations: ['超时宽限分钟数【待确认】'],
}

const RESOURCES = {
  updatedAt: '2026-11-15T12:00:12+08:00',
  timezone: 'Asia/Shanghai',
  date: '2026-11-15',
  dataDelaySeconds: 12,
  dataQuality: 'OK',
  dataScope: 'DEPT',
  roomUtilization: {
    roomCount: 3,
    surgeryMinutes: 630,
    availableMinutes: 1800,
    utilizationRate: 0.35,
  },
  roomStatusCounts: {
    total: 3,
    inSurgery: 1,
    idle: 1,
    cleaning: 1,
    preparing: 0,
    recovery: 0,
    outOfService: 0,
  },
  devices: { total: 4, online: 3, offline: 1, maintenance: 0, offlineTimeoutSeconds: 120 },
  deviceOnlineRate: 0.75,
  staffOnDuty: {
    total: 5,
    byShift: [
      { shift: 'DAY', count: 4 },
      { shift: 'ON_CALL', count: 1 },
    ],
  },
  resourceGaps: [
    {
      code: 'DEVICE_OFFLINE',
      severity: 'WARN',
      count: 1,
      message: '1 台设备离线（超时未上报），请检查采集网关与设备电源',
    },
  ],
  formula: '利用率 = 重叠手术分钟 / (术间数 × 每日可用分钟)',
  pendingConfirmations: ['备班是否计入当班【待确认】'],
}

const TRENDS = {
  updatedAt: '2026-11-15T12:00:12+08:00',
  timezone: 'Asia/Shanghai',
  dataScope: 'DEPT',
  windowMinutes: 1440,
  bucketMinutes: 60,
  from: '2026-11-14T12:00:12+08:00',
  to: '2026-11-15T12:00:12+08:00',
  roomCount: 3,
  points: [
    {
      startsAt: '2026-11-15T11:00:00+08:00',
      alertCount: 2,
      criticalAlertCount: 1,
      caseCount: 1,
      surgeryMinutes: 45,
      utilizationRate: 0.25,
    },
  ],
  formula: '告警 = 桶内触发告警数；台次 = 桶内计划开始的非取消台次',
  pendingConfirmations: ['台次趋势归桶口径【待确认】'],
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

test.describe('M5-M18-02 风险与资源大屏', () => {
  test('大屏加载 → 风险分层/资源/趋势渲染 → 手动刷新 → 暂停刷新', async ({ page }) => {
    await seedSession(page, OR_MANAGER)
    const riskUrls: string[] = []
    const resourceUrls: string[] = []
    const trendUrls: string[] = []
    await page.route(/\/api\/v1\/dashboard\/risk/, (route) => {
      riskUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(RISK),
      })
    })
    await page.route(/\/api\/v1\/dashboard\/resources/, (route) => {
      resourceUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(RESOURCES),
      })
    })
    await page.route(/\/api\/v1\/dashboard\/trends/, (route) => {
      trendUrls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(TRENDS),
      })
    })

    await page.goto('/m5/dashboard/risk')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('风险与资源大屏')
    await expect(page.getByTestId('risk-highRiskCaseCount')).toHaveText('2')
    await expect(page.getByTestId('risk-severeAlertCount')).toHaveText('1')
    await expect(page.getByTestId('risk-overdueCaseCount')).toHaveText('1')
    await expect(page.getByTestId('risk-unassessedCaseCount')).toHaveText('2')
    await expect(page.getByTestId('asa-class-list')).toContainText('ASA III')
    await expect(page.getByTestId('risk-level-list')).toContainText('高危')
    await expect(page.getByTestId('resource-utilization')).toHaveText('35.0%')
    await expect(page.getByTestId('resource-device-online-rate')).toHaveText('75.0%')
    await expect(page.getByTestId('resource-staff')).toHaveText('5')
    await expect(page.getByTestId('resource-staff')).toBeVisible()
    await expect(page.getByTestId('resource-gaps')).toContainText('设备离线')
    await expect(page.getByTestId('trend-table')).toContainText('45')
    await expect(page.getByTestId('trend-table')).toContainText('25.0%')
    await expect(page.getByTestId('data-scope')).toHaveText('范围：科室')
    await expect(page.getByTestId('data-quality')).toHaveText('数据正常')
    await expect(page.getByTestId('pending-confirmations')).toContainText('待确认')
    expect(riskUrls.length).toBe(1)
    expect(resourceUrls.length).toBe(1)
    expect(trendUrls.length).toBe(1)
    expect(trendUrls[0]).toContain('/api/v1/dashboard/trends?hours=24')
    console.log(
      `[M5-M18-02][e2e] role=SYS_OR_MANAGER risk=${riskUrls.length} resources=${resourceUrls.length} trends=${trendUrls.length}`,
    )

    // 手动刷新：三个端点各再请求一次。
    await page.getByTestId('refresh-now').click()
    await expect.poll(() => riskUrls.length).toBe(2)
    await expect.poll(() => resourceUrls.length).toBe(2)
    await expect.poll(() => trendUrls.length).toBe(2)

    // 暂停刷新：按钮文案切换，不再继续轮询。
    await page.getByTestId('refresh-toggle').click()
    await expect(page.getByTestId('refresh-toggle')).toHaveText('继续刷新')
  })
})
