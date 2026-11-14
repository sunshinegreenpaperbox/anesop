import { expect, test, type Page } from '@playwright/test'

/**
 * E2E-03 记录锁单修订（M3-M2-03）：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：锁单前置清单展示 → 二次确认锁单 → 记录只读（编辑被拒 409）→ 带原因修订 →
 * 新版本可见且原版本不变（差异对比）。CA 不可用（EXT-05），签名为 SIMULATED 占位。
 * 真实锁单不可变（触发器 55000）、修订 append-only 与审计由
 * `RecordLockRevisionApiIntegrationTest` 覆盖。
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
  permissions: ['m2:record:read', 'm2:record:write', 'm2:record:lock', 'm2:record:revise'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const SCHEDULE_ID = '90000000-0000-4000-8000-000000000001'
const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const SIGNATURE_HASH = 'c'.repeat(64)
const REVISION_HASH = 'd'.repeat(64)
const LOCKED_AT = '2026-12-01T11:30:00+08:00'
const SIGNATURE_REF = `SIM-CA-${RECORD_ID}`

type RecordBody = {
  recordId: string
  scheduleId: string
  patientId: string
  patientNo: string
  patientName: string
  status: string
  anesthesiaType: string
  startedAt: string | null
  endedAt: string | null
  events: unknown[]
  vitalSigns: unknown[]
  threeWayCheckCompleted: boolean
  lockedAt: string | null
  lockedBy: string | null
  currentRevision: number
  signatureRef: string | null
  signatureHash: string | null
  caStatus: string | null
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
    status: 'IN_PROGRESS',
    anesthesiaType: 'GENERAL',
    startedAt: '2026-12-01T08:40:00+08:00',
    endedAt: '2026-12-01T10:50:00+08:00',
    events: [],
    vitalSigns: [],
    threeWayCheckCompleted: true,
    lockedAt: null,
    lockedBy: null,
    currentRevision: 0,
    signatureRef: null,
    signatureHash: null,
    caStatus: null,
    createdAt: '2026-12-01T08:35:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
    updatedAt: '2026-12-01T10:55:00+08:00',
    updatedBy: ANESTHESIOLOGIST.userId,
    version: 8,
  }
}

const events = [
  {
    eventId: '13000000-0000-4000-8000-000000000001',
    recordId: RECORD_ID,
    seq: 1,
    eventType: 'ANESTHESIA_START',
    occurredAt: '2026-12-01T08:40:00+08:00',
    source: 'MANUAL',
    note: '演示麻醉开始（合成）',
    entryReason: null,
    recordedAt: '2026-12-01T08:41:00+08:00',
    recordedBy: ANESTHESIOLOGIST.userId,
    createdAt: '2026-12-01T08:41:00+08:00',
  },
  {
    eventId: '13000000-0000-4000-8000-000000000002',
    recordId: RECORD_ID,
    seq: 2,
    eventType: 'ANESTHESIA_END',
    occurredAt: '2026-12-01T10:50:00+08:00',
    source: 'MANUAL',
    note: '演示麻醉结束（合成）',
    entryReason: null,
    recordedAt: '2026-12-01T10:51:00+08:00',
    recordedBy: ANESTHESIOLOGIST.userId,
    createdAt: '2026-12-01T10:51:00+08:00',
  },
]

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

test.describe('M3 记录锁单与修订（E2E-03）', () => {
  test('锁单前置→锁单→编辑被拒→带原因修订→新版本可见且原版本不变', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    let record: RecordBody = newRecord()
    const revisions: Record<string, unknown>[] = []

    await page.route('**/api/v1/anesthesia/records**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      const method = request.method()
      const json = (body: unknown, status = 200) =>
        route.fulfill({
          status,
          contentType: 'application/json',
          body: JSON.stringify(body),
        })

      if (method === 'GET' && path.endsWith('/records')) {
        return json({ items: [record] })
      }
      if (method === 'GET' && path.endsWith('/lock-status')) {
        return json({
          recordId: RECORD_ID,
          locked: record.lockedAt != null,
          lockedAt: record.lockedAt,
          lockedBy: record.lockedBy,
          status: record.status,
          currentRevision: record.currentRevision,
          canLock: record.lockedAt == null,
          signatureRef: record.signatureRef,
          signatureHash: record.signatureHash,
          caStatus: record.caStatus,
          preconditions: [
            {
              code: 'THREE_WAY_CHECK_COMPLETED',
              label: '三方核查完成',
              satisfied: true,
              detail: 'Time-out 与 Sign-out 均已记录',
            },
            {
              code: 'CONSENT_SIGNED',
              label: '当前有效知情同意已签署',
              satisfied: true,
              detail: '存在 SIGNED 同意记录',
            },
            {
              code: 'RECORD_STARTED',
              label: '已记录麻醉开始',
              satisfied: true,
              detail: record.startedAt,
            },
            {
              code: 'RECORD_ENDED',
              label: '已记录麻醉结束',
              satisfied: true,
              detail: record.endedAt,
            },
            {
              code: 'AT_LEAST_ONE_EVENT',
              label: '至少 1 个时间轴事件',
              satisfied: true,
              detail: '事件数 2',
            },
            {
              code: 'NOT_LOCKED',
              label: '尚未锁单',
              satisfied: record.lockedAt == null,
              detail: record.lockedAt == null ? '记录单未锁定' : '记录单已锁单',
            },
          ],
        })
      }
      if (method === 'POST' && path.endsWith('/lock')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        expect(body).toMatchObject({ version: record.version, confirmChecklist: true })
        record = {
          ...record,
          status: 'LOCKED',
          lockedAt: LOCKED_AT,
          lockedBy: ANESTHESIOLOGIST.userId,
          signatureRef: SIGNATURE_REF,
          signatureHash: SIGNATURE_HASH,
          caStatus: 'SIMULATED',
          updatedAt: LOCKED_AT,
          version: record.version + 1,
        }
        return json({
          id: RECORD_ID,
          status: 'LOCKED',
          lockedAt: LOCKED_AT,
          lockedBy: ANESTHESIOLOGIST.userId,
          signatureStatus: 'SIGNED',
          signatureRef: SIGNATURE_REF,
          signatureHash: SIGNATURE_HASH,
          caStatus: 'SIMULATED',
          currentRevision: 0,
        })
      }
      if (method === 'GET' && path.endsWith('/effective')) {
        const corrected = record.currentRevision >= 1
        return json({
          recordId: RECORD_ID,
          locked: record.lockedAt != null,
          lockedAt: record.lockedAt,
          lockedBy: record.lockedBy,
          status: record.status,
          currentRevision: record.currentRevision,
          original: {
            anesthesiaType: 'GENERAL',
            startedAt: record.startedAt,
            endedAt: record.endedAt,
          },
          effective: {
            anesthesiaType: corrected ? 'COMBINED' : 'GENERAL',
            startedAt: record.startedAt,
            endedAt: record.endedAt,
          },
          corrections: corrected
            ? [
                {
                  field: 'anesthesiaType',
                  originalValue: 'GENERAL',
                  effectiveValue: 'COMBINED',
                  revisionNo: record.currentRevision,
                  reasonCode: 'ANESTHESIA_TYPE_CHANGE',
                },
              ]
            : [],
          signatureRef: record.signatureRef,
          signatureHash: record.signatureHash,
          caStatus: record.caStatus,
        })
      }
      if (method === 'GET' && path.endsWith('/revisions')) {
        return json({ items: revisions })
      }
      if (method === 'POST' && path.endsWith('/revisions')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        const revisionNo = record.currentRevision + 1
        const corrections = (body.corrections ?? {}) as Record<string, unknown>
        const correctedType = String(corrections.anesthesiaType ?? record.anesthesiaType)
        const revision = {
          revisionNo,
          recordId: RECORD_ID,
          reasonCode: body.reasonCode,
          reason: body.reason,
          changes: body.changes,
          signatureRef: `SIM-CA-14000000-0000-4000-8000-00000000000${revisionNo}`,
          signatureHash: REVISION_HASH,
          caStatus: 'SIMULATED',
          createdBy: ANESTHESIOLOGIST.userId,
          createdAt: '2026-12-01T12:10:00+08:00',
          snapshot: {
            record: {
              anesthesiaType: correctedType,
              startedAt: record.startedAt,
              endedAt: record.endedAt,
            },
            events,
            drugs: [],
            fluids: [],
            ioSummary: { intakeMl: 0, outputMl: 0, balanceMl: 0, fluidCount: 0 },
            signatures: {
              lockedSignatureHash: SIGNATURE_HASH,
              previousRevisionSignatureHash: null,
            },
          },
          diff: {
            previousRevisionNo: 0,
            summary: '共 1 项字段变更',
            changedFields: [
              {
                field: 'record.anesthesiaType',
                previousValue: 'GENERAL',
                newValue: correctedType,
              },
            ],
          },
        }
        revisions.push(revision)
        // 原记录内容不变：只推进修订号/状态与版本（与后端一致）。
        record = {
          ...record,
          status: 'REVISED',
          currentRevision: revisionNo,
          updatedAt: '2026-12-01T12:10:00+08:00',
          version: record.version + 1,
        }
        return json(revision, 201)
      }
      const revisionMatch = /\/revisions\/(\d+)$/.exec(path)
      if (method === 'GET' && revisionMatch) {
        const revision = revisions.find((item) => item.revisionNo === Number(revisionMatch[1]))
        return revision ? json(revision) : json({ code: 'RESOURCE_NOT_FOUND' }, 404)
      }
      if (path.endsWith('/events')) {
        if (method === 'POST') {
          // 锁单后写入被服务端拒绝（409）：E2E 通过页面内 fetch 验证“编辑被拒”。
          if (record.lockedAt != null) {
            return json(
              {
                code: 'STATE_CONFLICT',
                message: '记录单已锁单，不可追加事件；请新增修订版本',
                traceId: 'SYNTH-TRACE-LOCK',
                details: [],
              },
              409,
            )
          }
          return json(events[0], 201)
        }
        return json({ items: events })
      }
      if (path.endsWith('/io-summary')) {
        return json({
          recordId: RECORD_ID,
          intakeMl: 0,
          outputMl: 0,
          balanceMl: 0,
          fluidCount: 0,
          computedAt: '2026-12-01T10:55:00+08:00',
        })
      }
      return json({ items: [] })
    })

    await page.goto(`/m2/records/${SCHEDULE_ID}`)
    await expect(page.getByTestId('record-status')).toHaveText('进行中')
    await expect(page.getByTestId('append-event')).toBeVisible()

    // 锁单前：展示服务端前置清单，未确认不能提交，确认后锁单。
    await page.getByTestId('lock-open').click()
    await expect(page.getByTestId('lock-precondition-list')).toContainText('三方核查完成')
    await expect(page.getByTestId('lock-precondition-list')).toContainText('已满足')
    await expect(page.getByTestId('lock-submit')).toBeDisabled()
    await page.getByTestId('lock-confirm').click()
    await expect(page.getByTestId('lock-submit')).toBeEnabled()
    await page.getByTestId('lock-submit').click()

    await expect(page.getByTestId('lock-banner')).toBeVisible()
    await expect(page.getByTestId('lock-banner')).toContainText('已锁单')
    await expect(page.getByTestId('lock-banner')).toContainText('SIMULATED')
    await expect(page.getByTestId('record-status')).toHaveText('已锁单')
    await expect(page.getByTestId('append-event')).toHaveCount(0)
    await expect(page.getByTestId('lock-open')).toHaveCount(0)
    await expect(page.getByTestId('revise-open')).toBeVisible()

    // 锁单后编辑被拒：通过页面内 fetch 直击接口，服务端返回 409（表单已隐藏）。
    const rejectedStatus = await page.evaluate(async (recordId) => {
      const token = window.sessionStorage.getItem('anesop.auth.accessToken')
      const response = await fetch(`/api/v1/anesthesia/records/${recordId}/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ eventType: 'CUSTOM', occurredAt: new Date().toISOString() }),
      })
      return response.status
    }, RECORD_ID)
    expect(rejectedStatus).toBe(409)

    // 带原因修订：原因码白名单 + 原因 + 变更摘要 + 可选的麻醉方式更正。
    await page.getByTestId('revise-open').click()
    await page.getByTestId('revision-reason').fill('锁单后发现麻醉方式记录笔误（演示）')
    await page.getByTestId('revision-changes').fill('将麻醉方式由 GENERAL 更正为 COMBINED（演示）')
    await page.getByTestId('revision-anesthesia-type').click()
    await page.getByRole('option', { name: '联合麻醉' }).click()
    await page.getByTestId('revision-submit').click()

    await expect(page.getByTestId('record-status')).toHaveText('已修订')
    await expect(page.getByTestId('revision-list')).toContainText('v1')
    await expect(page.getByTestId('revision-list')).toContainText('更正麻醉方式')
    // 当前有效值展示修订后的麻醉方式；原记录不变，单独展示锁单原始值。
    await expect(page.getByTestId('record-effective-anesthesia')).toHaveText('联合麻醉')
    await expect(page.getByTestId('record-original-anesthesia')).toContainText('全身麻醉')

    // 新版本可见且可对比：差异显示 GENERAL → COMBINED，快照为修订后内容。
    await page.getByTestId('revision-detail-1').click()
    await expect(page.getByTestId('revision-diff-summary')).toContainText('1 项字段变更')
    await expect(page.getByTestId('revision-diff')).toContainText('record.anesthesiaType')
    await expect(page.getByTestId('revision-diff')).toContainText('GENERAL')
    await expect(page.getByTestId('revision-diff')).toContainText('COMBINED')
    await expect(page.getByTestId('revision-snapshot')).toContainText('COMBINED')
  })
})
