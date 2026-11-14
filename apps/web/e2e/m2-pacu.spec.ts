import { expect, test, type Page } from '@playwright/test'

/**
 * PACU 复苏记录与转出（M3-M10-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：空态入室 → Aldrete 评分表实时总分（10/10）→ 记录评估（服务端返回总分）→
 * 转出对话框（去向 + SBAR + 达标确认）→ 状态「已转出」与交接展示。
 * 真实 409（阈值不足/重复转出/转出后再评估）、append-only 55000、权限 403/404 与审计由
 * `PacuRecoveryApiIntegrationTest` 覆盖。
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
  permissions: ['m2:record:read', 'm2:record:write', 'm2:pacu:read', 'm2:pacu:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const PACU_ID = '19000000-0000-4000-8000-000000000001'
const ASSESSMENT_ID = '1a000000-0000-4000-8000-000000000001'

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

function newPacu() {
  return {
    pacuId: PACU_ID,
    recordId: RECORD_ID,
    patientId: '50000000-0000-4000-8000-000000000001',
    admittedAt: '2026-12-01T11:05:00+08:00',
    dischargedAt: null as string | null,
    status: 'ADMITTED',
    vitalSigns: {
      systolicBp: 118,
      diastolicBp: 72,
      heartRate: 82,
      spo2: 97,
      respiratoryRate: 14,
      temperature: 36.5,
    },
    consciousness: 'ALERT',
    painScore: 3,
    nausea: false,
    airway: 'NATURAL',
    latestAssessmentId: null as string | null,
    latestAssessmentAt: null as string | null,
    latestAldreteTotal: null as number | null,
    aldreteThreshold: 9,
    createdAt: '2026-12-01T11:05:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
    updatedAt: '2026-12-01T11:05:00+08:00',
    updatedBy: ANESTHESIOLOGIST.userId,
    version: 1,
  }
}

test.describe('M3 PACU 复苏记录与转出', () => {
  test('入室→Aldrete 10 分→SBAR 转出，状态与交接可见', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)

    let pacu: ReturnType<typeof newPacu> | null = null
    const assessments: Record<string, unknown>[] = []
    let discharge: Record<string, unknown> | null = null

    await page.route('**/api/v1/anesthesia/pacu-records**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname
      const method = request.method()
      const json = (body: unknown, status = 200) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

      if (method === 'GET' && path.endsWith('/pacu-records')) {
        return json({ items: pacu ? [pacu] : [] })
      }
      if (method === 'POST' && path.endsWith('/pacu-records')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        pacu = { ...newPacu(), admittedAt: String(body.admittedAt) }
        return json(pacu, 201)
      }
      if (method === 'GET' && path.endsWith('/assessments')) {
        return json({ items: assessments })
      }
      if (method === 'POST' && path.endsWith('/assessments')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, number>
        const total =
          (body.activity ?? 0) +
          (body.respiration ?? 0) +
          (body.circulation ?? 0) +
          (body.consciousness ?? 0) +
          (body.oxygenation ?? 0)
        const created = {
          assessmentId: ASSESSMENT_ID,
          pacuId: PACU_ID,
          assessedAt: body.assessedAt,
          activity: body.activity,
          respiration: body.respiration,
          circulation: body.circulation,
          consciousness: body.consciousness,
          oxygenation: body.oxygenation,
          aldreteTotal: total,
          notes: null,
          createdAt: '2026-12-01T11:30:00+08:00',
          createdBy: ANESTHESIOLOGIST.userId,
        }
        assessments.push(created)
        if (pacu) {
          pacu = {
            ...pacu,
            status: 'READY',
            latestAssessmentId: ASSESSMENT_ID,
            latestAssessmentAt: String(body.assessedAt),
            latestAldreteTotal: total,
            version: (pacu.version ?? 1) + 1,
          }
        }
        return json(created, 201)
      }
      if (method === 'GET' && path.endsWith('/discharge')) {
        return discharge
          ? json(discharge)
          : json({ code: 'NOT_FOUND', message: '不存在', traceId: 'trace-e2e', details: [] }, 404)
      }
      if (method === 'POST' && path.endsWith('/discharge')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        discharge = {
          dischargeId: '1b000000-0000-4000-8000-000000000001',
          pacuId: PACU_ID,
          dischargedAt: '2026-12-01T12:10:00+08:00',
          destination: body.destination,
          handover: body.handover,
          criteria: {
            aldreteThreshold: 9,
            aldreteTotal: pacu?.latestAldreteTotal ?? 0,
            meetsThreshold: true,
            itemsComplete: true,
            latestAssessmentId: ASSESSMENT_ID,
            latestAssessmentAt: '2026-12-01T11:30:00+08:00',
          },
          createdAt: '2026-12-01T12:10:00+08:00',
          createdBy: ANESTHESIOLOGIST.userId,
        }
        if (pacu) {
          pacu = {
            ...pacu,
            status: 'DISCHARGED',
            dischargedAt: '2026-12-01T12:10:00+08:00',
            version: (pacu.version ?? 1) + 1,
          }
        }
        return json(discharge, 201)
      }
      if (method === 'GET' && path.endsWith('/pacu-records/' + PACU_ID)) {
        return json(pacu)
      }
      return json({ code: 'NOT_FOUND', message: '不存在', traceId: 'trace-e2e', details: [] }, 404)
    })

    await page.goto(`/m2/pacu/${RECORD_ID}`)
    await expect(page.getByTestId('pacu-empty')).toBeVisible()

    // 入室：体征/意识/疼痛/气道不预填；空表单提交不生效。
    await page.getByTestId('pacu-admit-open').click()
    await expect(page.getByTestId('admit-required-hint')).toBeVisible()
    await page.getByTestId('admit-submit').click()
    await expect(page.getByTestId('pacu-empty')).toBeVisible()
    await page.getByTestId('admit-systolic').locator('input').fill('118')
    await page.getByTestId('admit-diastolic').locator('input').fill('72')
    await page.getByTestId('admit-heart-rate').locator('input').fill('82')
    await page.getByTestId('admit-spo2').locator('input').fill('97')
    await page.getByTestId('admit-respiratory-rate').locator('input').fill('14')
    await page.getByTestId('admit-temperature').locator('input').fill('36.5')
    await page.getByTestId('admit-pain-score').locator('input').fill('3')
    await page.getByTestId('admit-consciousness').click()
    await page.getByRole('option', { name: '清醒', exact: true }).click()
    await page.getByTestId('admit-airway').click()
    await page.getByRole('option', { name: '自然气道', exact: true }).click()
    await page.getByTestId('admit-submit').click()
    await expect(page.getByTestId('pacu-status')).toHaveText('已入室')
    await expect(page.getByTestId('pacu-vitals')).toContainText('HR 82 bpm')

    // Aldrete 五项无默认分：未逐项显式选择（0/1/2）时不可提交。
    await expect(page.getByTestId('aldrete-total')).toContainText('—')
    await expect(page.getByTestId('aldrete-incomplete')).toBeVisible()
    await expect(page.getByTestId('aldrete-submit')).toBeDisabled()
    for (const key of ['activity', 'respiration', 'circulation', 'consciousness', 'oxygenation']) {
      await page.getByTestId(`aldrete-${key}`).getByText(/^2 分/).click()
    }
    await expect(page.getByTestId('aldrete-total')).toContainText('10 / 10（阈值 9）')
    await page.getByTestId('aldrete-submit').click()
    await expect(page.getByTestId('pacu-status')).toHaveText('已达转出标准')
    await expect(page.getByTestId('aldrete-timeline')).toContainText('10')

    // 转出对话框展示服务端最近一次评估，而不是本地未提交分值。
    await page.getByTestId('pacu-discharge-open').click()
    await expect(page.getByTestId('discharge-criteria-hint')).toContainText(
      '服务端最近一次 Aldrete 10 分',
    )
    await page.getByTestId('sbar-situation').fill('演示：PACU 观察 65 分钟（合成）')
    await page.getByTestId('sbar-background').fill('演示：ASA II，术中平稳（合成）')
    await page.getByTestId('sbar-assessment').fill('演示：Aldrete 10 分（合成）')
    await page.getByTestId('sbar-recommendation').fill('演示：返病房继续监测（合成）')
    await page.getByTestId('discharge-confirm').click()
    await page.getByTestId('discharge-submit').click()

    await expect(page.getByTestId('pacu-status')).toHaveText('已转出')
    await expect(page.locator('.pacu__sbar')).toContainText('演示：返病房继续监测（合成）')
    await expect(page.getByTestId('pacu-discharge-open')).toHaveCount(0)
  })
})
