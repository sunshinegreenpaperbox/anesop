import { expect, test, type Page } from '@playwright/test'

/**
 * 麻醉记录单与时间轴（M3-M2-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：台次无记录单 → 创建草稿 → 追加麻醉开始事件 → 状态/开始时间更新 → 出入量汇总；
 * 麻精用药双人核对（缺核对人前端拦截不发请求，补齐后提交）。真实 409/55000/403/审计由
 * `AnesthesiaRecordApiIntegrationTest` 覆盖（E2E-03 锁单修订为 M3-M2-03）。
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
  permissions: ['m2:record:read', 'm2:record:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const SCHEDULE_ID = '90000000-0000-4000-8000-000000000001'
const RECORD_ID = '12000000-0000-4000-8000-000000000001'

type RecordBody = {
  recordId: string
  scheduleId: string
  patientId: string
  patientNo: string
  patientName: string
  status: 'DRAFT' | 'IN_PROGRESS'
  anesthesiaType: string
  startedAt: string | null
  endedAt: string | null
  events: unknown[]
  vitalSigns: unknown[]
  threeWayCheckCompleted: boolean
  createdAt: string
  createdBy: string
  updatedAt: string
  updatedBy: string
  version: number
}

function newRecord(): RecordBody {
  return {
    recordId: RECORD_ID,
    scheduleId: SCHEDULE_ID,
    patientId: '50000000-0000-4000-8000-000000000001',
    patientNo: 'SYNTH-P-0001',
    patientName: '演示患者',
    status: 'DRAFT',
    anesthesiaType: 'GENERAL',
    startedAt: null,
    endedAt: null,
    events: [],
    vitalSigns: [],
    threeWayCheckCompleted: true,
    createdAt: '2026-12-01T08:35:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
    updatedAt: '2026-12-01T08:35:00+08:00',
    updatedBy: ANESTHESIOLOGIST.userId,
    version: 1,
  }
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

test.describe('M3 麻醉记录单', () => {
  test('创建记录单→追加麻醉开始→出入量汇总', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    let record: RecordBody | null = null
    const events: Record<string, unknown>[] = []
    const fluids: Record<string, unknown>[] = []

    await page.route('**/api/v1/drugs/search**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [
            {
              drugId: '2a000000-0000-4000-8000-000000000003',
              code: 'DRUG-REM-001',
              name: '瑞芬太尼（演示）',
              genericName: '瑞芬太尼注射液（演示）',
              specification: '1mg/1ml',
              unit: 'mg',
              dosageForm: 'INJECTION',
              category: 'OPIOID',
              narcotic: true,
              highRisk: false,
              aliases: [],
              status: 'ACTIVE',
              createdAt: '2026-11-14T08:00:00+08:00',
              createdBy: ANESTHESIOLOGIST.userId,
              updatedAt: '2026-11-14T08:00:00+08:00',
              updatedBy: ANESTHESIOLOGIST.userId,
              version: 1,
            },
          ],
        }),
      }),
    )
    await page.route('**/api/v1/anesthesia/records**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      const method = request.method()

      if (method === 'GET' && path.endsWith('/records')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: record ? [record] : [] }),
        })
        return
      }
      if (method === 'POST' && path.endsWith('/records')) {
        record = newRecord()
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(record),
        })
        return
      }
      if (path.endsWith('/events')) {
        if (method === 'POST') {
          const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
          const event = {
            eventId: `13000000-0000-4000-8000-00000000000${events.length + 1}`,
            recordId: RECORD_ID,
            seq: events.length + 1,
            eventType: body.eventType,
            occurredAt: body.occurredAt,
            source: 'MANUAL',
            note: body.note ?? null,
            entryReason: null,
            recordedAt: body.occurredAt,
            recordedBy: ANESTHESIOLOGIST.userId,
            createdAt: body.occurredAt,
          }
          events.push(event)
          if (record && body.eventType === 'ANESTHESIA_START') {
            record = { ...record, status: 'IN_PROGRESS', startedAt: String(body.occurredAt) }
          }
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify(event),
          })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: events }),
        })
        return
      }
      if (path.endsWith('/fluids')) {
        if (method === 'POST') {
          const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
          const fluid = {
            fluidId: `16000000-0000-4000-8000-00000000000${fluids.length + 1}`,
            recordId: RECORD_ID,
            direction: body.direction,
            fluidType: body.fluidType,
            amountMl: body.amountMl,
            recordedAt: '2026-12-01T09:00:00+08:00',
            note: body.note ?? null,
            createdAt: '2026-12-01T09:01:00+08:00',
            createdBy: ANESTHESIOLOGIST.userId,
          }
          fluids.push(fluid)
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify(fluid),
          })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: fluids }),
        })
        return
      }
      if (path.endsWith('/io-summary')) {
        const intake = fluids
          .filter((item) => item.direction === 'INTAKE')
          .reduce((sum, item) => sum + Number(item.amountMl), 0)
        const output = fluids
          .filter((item) => item.direction === 'OUTPUT')
          .reduce((sum, item) => sum + Number(item.amountMl), 0)
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            recordId: RECORD_ID,
            intakeMl: intake,
            outputMl: output,
            balanceMl: intake - output,
            fluidCount: fluids.length,
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

    await page.goto(`/m2/records/${SCHEDULE_ID}`)
    await expect(page.getByTestId('record-empty')).toBeVisible()
    await page.getByTestId('create-record').click()

    await expect(page.getByTestId('record-status')).toHaveText('草稿')
    await expect(page.getByTestId('record-context')).toContainText('演示患者')

    // 默认事件类型为诱导，切换为麻醉开始。
    await page.getByTestId('event-type').click()
    await page.getByRole('option', { name: '麻醉开始' }).click()
    await page.getByTestId('append-event').click()

    await expect(page.getByTestId('record-status')).toHaveText('进行中')
    await expect(page.getByTestId('event-list')).toContainText('麻醉开始')
    await expect(page.getByTestId('event-list')).toContainText('1')

    await page.getByTestId('fluid-type').fill('复方氯化钠（演示）')
    await page.getByTestId('record-fluid').click()
    await expect(page.getByTestId('io-intake')).toHaveText('500 ml')
    await expect(page.getByTestId('io-balance')).toHaveText('500 ml')
  })

  test('麻精用药缺双人核对被前端拦截，补齐后提交', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    const record = newRecord()
    const drugs: Record<string, unknown>[] = []
    let drugPosts = 0

    await page.route('**/api/v1/drugs/search**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [
            {
              drugId: '2a000000-0000-4000-8000-000000000003',
              code: 'DRUG-REM-001',
              name: '瑞芬太尼（演示）',
              genericName: '瑞芬太尼注射液（演示）',
              specification: '1mg/1ml',
              unit: 'mg',
              dosageForm: 'INJECTION',
              category: 'OPIOID',
              narcotic: true,
              highRisk: false,
              aliases: [],
              status: 'ACTIVE',
              createdAt: '2026-11-14T08:00:00+08:00',
              createdBy: ANESTHESIOLOGIST.userId,
              updatedAt: '2026-11-14T08:00:00+08:00',
              updatedBy: ANESTHESIOLOGIST.userId,
              version: 1,
            },
          ],
        }),
      }),
    )
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
      if (path.endsWith('/drugs')) {
        if (method === 'POST') {
          drugPosts += 1
          const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
          const drug = {
            drugId: '14000000-0000-4000-8000-000000000002',
            recordId: RECORD_ID,
            drugName: body.drugName,
            dose: body.dose,
            unit: body.unit,
            route: body.route,
            administeredAt: body.administeredAt,
            narcotic: body.narcotic,
            secondCheckerId: body.secondCheckerId ?? null,
            secondCheckerName: body.secondCheckerName ?? null,
            note: body.note ?? null,
            createdAt: '2026-12-01T09:11:00+08:00',
            createdBy: ANESTHESIOLOGIST.userId,
          }
          drugs.push(drug)
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify(drug),
          })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: drugs }),
        })
        return
      }
      if (path.endsWith('/io-summary')) {
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

    await page.goto(`/m2/records/${SCHEDULE_ID}`)
    await expect(page.getByTestId('record-context')).toContainText('演示患者')

    await page.getByTestId('drug-name').fill('瑞芬太尼（演示）')
    await page.getByTestId('record-drug').click()

    await expect(page.locator('.el-message').last()).toContainText('麻精药品必须双人核对')
    expect(drugPosts).toBe(0)

    await page.getByTestId('drug-second-checker-id').fill('1000000000000000003')
    await page.getByTestId('drug-second-checker-name').fill('演示核对护士（合成）')
    await page.getByTestId('record-drug').click()

    await expect(page.getByTestId('drug-list')).toContainText('瑞芬太尼（演示）')
    await expect(page.getByTestId('drug-list')).toContainText('演示核对护士（合成）')
    expect(drugPosts).toBe(1)
  })
})
