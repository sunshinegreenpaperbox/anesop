import { expect, test, type Page } from '@playwright/test'

/**
 * 术中预警与抢救记录（M3-M2-02）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：记录单告警面板（严重度/指标/确认原因）、抢救记录表单与列表、管理员专属
 * 预警规则页（版本化保存 v1→v2）与无 m2:alert:configure 访问被拦到 403。
 * 真实 409/55000/403/404 与审计由 `AlertRescueApiIntegrationTest` 覆盖。
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
  userId: '1000000000000000002',
  username: 'synth_anesthesiologist',
  displayName: '演示麻醉医师（合成）',
  roles: ['SYS_ANESTHESIOLOGIST'],
  permissions: [
    'm2:record:read',
    'm2:record:write',
    'm2:alert:read',
    'm2:alert:ack',
    'm2:rescue:write',
  ],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const ADMIN: SyntheticUser = {
  userId: '1000000000000000001',
  username: 'synth_admin',
  displayName: '演示管理员（合成）',
  roles: ['SYS_ADMIN'],
  permissions: ['m2:alert:read', 'm2:alert:configure'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const NURSE: SyntheticUser = {
  userId: '1000000000000000003',
  username: 'synth_nurse',
  displayName: '演示麻醉护士（合成）',
  roles: ['SYS_NURSE_ANESTHETIST'],
  permissions: ['m2:record:read'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const SCHEDULE_ID = '90000000-0000-4000-8000-000000000001'
const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const ALERT_ID = '1c000000-0000-4000-8000-000000000001'
const RULE_ID = '1b000000-0000-4000-8000-000000000001'

const record = {
  recordId: RECORD_ID,
  scheduleId: SCHEDULE_ID,
  patientId: '50000000-0000-4000-8000-000000000001',
  patientNo: 'SYNTH-P-0001',
  patientName: '演示患者',
  status: 'IN_PROGRESS',
  anesthesiaType: 'GENERAL',
  startedAt: '2026-12-01T08:40:00+08:00',
  endedAt: null,
  events: [],
  vitalSigns: [],
  threeWayCheckCompleted: true,
  threeWayCheckTimeOutAt: null,
  threeWayCheckSignOutAt: null,
  createdAt: '2026-12-01T08:35:00+08:00',
  createdBy: ANESTHESIOLOGIST.userId,
  updatedAt: '2026-12-01T08:46:00+08:00',
  updatedBy: ANESTHESIOLOGIST.userId,
  version: 2,
}

const rule = {
  ruleId: RULE_ID,
  code: 'SPO2_LOW',
  name: '血氧饱和度过低（演示阈值【待确认】）',
  metric: 'SPO2',
  operator: 'LT',
  thresholdLow: 90,
  thresholdHigh: null,
  durationSeconds: 0,
  severity: 'CRITICAL',
  version: 1,
  enabled: true,
  pendingConfirmation: true,
  createdAt: '2026-12-01T00:00:00+08:00',
  createdBy: ADMIN.userId,
  updatedAt: '2026-12-01T00:00:00+08:00',
  updatedBy: ADMIN.userId,
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

test.describe('M3 术中预警与抢救记录', () => {
  test('告警确认需原因→状态已确认；抢救记录写入后出现在列表', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    let alert = {
      alertId: ALERT_ID,
      recordId: RECORD_ID,
      ruleId: RULE_ID,
      ruleCode: 'SPO2_LOW',
      ruleVersion: 1,
      metric: 'SPO2',
      value: 87,
      unit: '%',
      severity: 'CRITICAL',
      triggeredAt: '2026-12-01T09:20:00+08:00',
      status: 'TRIGGERED',
      acknowledgedBy: null,
      acknowledgedAt: null,
      acknowledgeReason: null,
      dedupeKey: `${RECORD_ID}:SPO2_LOW:1796088000`,
      version: 1,
      createdAt: '2026-12-01T09:21:00+08:00',
      createdBy: null,
      updatedAt: '2026-12-01T09:21:00+08:00',
      updatedBy: null,
    }
    const resuscitations: Record<string, unknown>[] = []

    await page.route('**/api/v1/anesthesia/records**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      const method = request.method()
      if (method === 'GET' && path.endsWith('/records')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: [record] }),
        })
        return
      }
      if (method === 'GET' && path.endsWith('/alerts')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: [alert] }),
        })
        return
      }
      if (method === 'GET' && path.endsWith('/resuscitations')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: resuscitations }),
        })
        return
      }
      if (method === 'POST' && path.endsWith('/resuscitations')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        const created = {
          resuscitationId: '1d000000-0000-4000-8000-000000000001',
          recordId: RECORD_ID,
          resusType: body.resusType,
          startedAt: body.startedAt,
          endedAt: body.endedAt,
          eventSummary: body.eventSummary,
          leader: body.leader,
          rescuers: body.rescuers,
          drugs: body.drugs ?? [],
          outcome: body.outcome,
          createdAt: '2026-12-01T09:50:00+08:00',
          createdBy: ANESTHESIOLOGIST.userId,
        }
        resuscitations.push(created)
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(created),
        })
        return
      }
      if (method === 'GET' && path.endsWith('/io-summary')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            recordId: RECORD_ID,
            intakeMl: 0,
            outputMl: 0,
            balanceMl: 0,
            fluidCount: 0,
            computedAt: '2026-12-01T10:30:00+08:00',
          }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      })
    })

    await page.route('**/api/v1/anesthesia/alerts/**', async (route) => {
      const body = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      alert = {
        ...alert,
        status: body.decision === 'IGNORED' ? 'IGNORED' : 'ACKNOWLEDGED',
        acknowledgedBy: ANESTHESIOLOGIST.userId,
        acknowledgedAt: '2026-12-01T09:22:00+08:00',
        acknowledgeReason: String(body.reason),
        version: 2,
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(alert),
      })
    })

    await page.goto(`/m2/records/${SCHEDULE_ID}`)
    await expect(page.getByTestId('alert-list')).toContainText('血氧饱和度')
    await expect(page.getByTestId('alert-list')).toContainText('严重')
    await expect(page.getByTestId('alert-list')).toContainText('待处理')

    await page.getByTestId(`alert-confirm-${ALERT_ID}`).click()
    await page.getByTestId('ack-reason').fill('演示：已核对监护仪（合成）')
    await page.getByTestId('ack-submit').click()
    await expect(page.getByTestId('alert-list')).toContainText('已确认')

    await page.getByTestId('resus-summary').fill('演示：胸外按压（合成数据）')
    await page.getByTestId('resus-interventions').fill('胸外按压')
    await page.getByTestId('resus-rescuers').fill('演示麻醉医师（合成）,演示护士（合成）')
    await page.getByTestId('submit-resuscitation').click()
    await expect(page.getByTestId('resuscitation-list')).toContainText('心肺复苏')
    await expect(page.getByTestId('resuscitation-list')).toContainText('演示：胸外按压（合成数据）')
  })

  test('管理员从菜单进入预警规则页并保存新版本', async ({ page }) => {
    await seedSession(page, ADMIN)
    let current = rule
    await page.route('**/api/v1/anesthesia/alert-rules**', async (route) => {
      const request = route.request()
      const method = request.method()
      if (method === 'PUT') {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        current = {
          ...current,
          ruleId: '1b000000-0000-4000-8000-000000000011',
          thresholdLow: Number(body.thresholdLow),
          durationSeconds: Number(body.durationSeconds),
          version: current.version + 1,
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(current),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [current] }),
      })
    })

    await page.goto('/home')
    const nav = page.getByRole('navigation', { name: '主导航' })
    await nav.getByText('预警规则').click()
    await expect(page).toHaveURL(/\/m2\/alert-rules$/)
    await expect(page.getByTestId('rules-table')).toContainText('SPO2_LOW')
    await expect(page.getByTestId('rules-table')).toContainText('< 90')

    await page.getByTestId('rule-edit-SPO2_LOW').click()
    await page.getByTestId('rule-threshold-low').locator('input').fill('88')
    await page.getByTestId('rule-duration').locator('input').fill('15')
    await page.getByTestId('rule-save').click()
    await expect(page.getByTestId('rules-table')).toContainText('< 88')
    await expect(page.getByTestId('rules-table')).toContainText('v2')
  })

  test('无 m2:alert:configure 访问规则页被拦到 403 且菜单不显示', async ({ page }) => {
    await seedSession(page, NURSE)
    await page.goto('/home')
    const nav = page.getByRole('navigation', { name: '主导航' })
    await expect(nav.getByText('预警规则')).toHaveCount(0)

    await page.goto('/m2/alert-rules')
    await expect(page).toHaveURL(/\/403/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('无权访问该页面')
  })
})
