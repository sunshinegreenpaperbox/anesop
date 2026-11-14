import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RecordView from '../views/RecordView.vue'
import { configureHttp } from '../../../api/http'
import router from '../../../router'
import { pinia } from '../../../stores'
import {
  createFetchQueue,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from '../../../__tests__/testUtils'

enableAutoUnmount(afterEach)

const SCHEDULE_ID = '90000000-0000-4000-8000-000000000001'
const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const SIGNATURE_HASH = 'a'.repeat(64)

const baseRecord = {
  recordId: RECORD_ID,
  scheduleId: SCHEDULE_ID,
  patientId: '50000000-0000-4000-8000-000000000001',
  patientNo: 'SYNTH-P-0001',
  patientName: '演示患者',
  status: 'IN_PROGRESS' as const,
  anesthesiaType: 'GENERAL' as const,
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
  createdBy: '10000000-0000-4000-8000-000000000001',
  updatedAt: '2026-12-01T10:50:00+08:00',
  updatedBy: '10000000-0000-4000-8000-000000000001',
  version: 8,
}

const lockedRecord = {
  ...baseRecord,
  status: 'LOCKED' as const,
  lockedAt: '2026-12-01T11:30:00+08:00',
  lockedBy: '10000000-0000-4000-8000-000000000001',
  signatureRef: `SIM-CA-${RECORD_ID}`,
  signatureHash: SIGNATURE_HASH,
  caStatus: 'SIMULATED' as const,
}

const preconditions = [
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
    detail: '2026-12-01T08:40:00+08:00',
  },
  {
    code: 'RECORD_ENDED',
    label: '已记录麻醉结束',
    satisfied: true,
    detail: '2026-12-01T10:50:00+08:00',
  },
  { code: 'AT_LEAST_ONE_EVENT', label: '至少 1 个时间轴事件', satisfied: true, detail: '事件数 2' },
  { code: 'NOT_LOCKED', label: '尚未锁单', satisfied: true, detail: '记录单未锁定' },
]

const lockStatus = {
  recordId: RECORD_ID,
  locked: false,
  lockedAt: null,
  lockedBy: null,
  status: 'IN_PROGRESS' as const,
  currentRevision: 0,
  canLock: true,
  signatureRef: null,
  signatureHash: null,
  caStatus: null,
  preconditions,
}

const revisionSummary = {
  revisionNo: 1,
  recordId: RECORD_ID,
  reasonCode: 'ANESTHESIA_TYPE_CHANGE' as const,
  reason: '锁单后发现麻醉方式记录笔误（演示）',
  changes: '将麻醉方式由 GENERAL 更正为 COMBINED（演示）',
  signatureRef: 'SIM-CA-14000000-0000-4000-8000-000000000001',
  signatureHash: 'b'.repeat(64),
  caStatus: 'SIMULATED' as const,
  createdBy: '10000000-0000-4000-8000-000000000001',
  createdAt: '2026-12-01T12:10:00+08:00',
}

const revisionDetail = {
  ...revisionSummary,
  snapshot: {
    record: {
      anesthesiaType: 'COMBINED',
      startedAt: '2026-12-01T08:40:00+08:00',
      endedAt: '2026-12-01T10:50:00+08:00',
    },
    events: [],
    drugs: [],
    fluids: [],
    ioSummary: { intakeMl: 0, outputMl: 0, balanceMl: 0, fluidCount: 0 },
    signatures: { lockedSignatureHash: SIGNATURE_HASH, previousRevisionSignatureHash: null },
  },
  diff: {
    previousRevisionNo: 0,
    summary: '共 1 项字段变更',
    changedFields: [
      { field: 'record.anesthesiaType', previousValue: 'GENERAL', newValue: 'COMBINED' },
    ],
  },
}

function detailResponses(record: Record<string, unknown>) {
  const currentRevision = Number(record.currentRevision ?? 0)
  const corrected = currentRevision >= 1
  return [
    jsonResponse({ items: [record] }),
    jsonResponse({ items: [] }),
    jsonResponse({ items: [] }),
    jsonResponse({ items: [] }),
    jsonResponse({
      recordId: RECORD_ID,
      intakeMl: 0,
      outputMl: 0,
      balanceMl: 0,
      fluidCount: 0,
      computedAt: '2026-12-01T10:55:00+08:00',
    }),
    jsonResponse({
      recordId: RECORD_ID,
      locked: record.lockedAt != null,
      lockedAt: record.lockedAt ?? null,
      lockedBy: record.lockedBy ?? null,
      status: record.status,
      currentRevision,
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
              revisionNo: 1,
              reasonCode: 'ANESTHESIA_TYPE_CHANGE',
            },
          ]
        : [],
      signatureRef: record.signatureRef ?? null,
      signatureHash: record.signatureHash ?? null,
      caStatus: record.caStatus ?? null,
    }),
    jsonResponse({ items: [] }),
  ]
}

function mountView() {
  return mount(RecordView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
  await flushPromises()
}

describe('RecordView 锁单与修订（M3-M2-03）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m2:record:read', 'm2:record:write', 'm2:record:lock', 'm2:record:revise'],
      }),
    )
    await router.push(`/m2/records/${SCHEDULE_ID}`)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('展示服务端前置清单，二次确认后锁单并进入只读', async () => {
    const queue = createFetchQueue([
      ...detailResponses(baseRecord),
      jsonResponse(lockStatus),
      jsonResponse(
        {
          id: RECORD_ID,
          status: 'LOCKED',
          lockedAt: '2026-12-01T11:30:00+08:00',
          lockedBy: '10000000-0000-4000-8000-000000000001',
          signatureStatus: 'SIGNED',
          signatureRef: `SIM-CA-${RECORD_ID}`,
          signatureHash: SIGNATURE_HASH,
          caStatus: 'SIMULATED',
          currentRevision: 0,
        },
        200,
      ),
      ...detailResponses(lockedRecord),
      jsonResponse({ items: [] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    expect(wrapper.find('[data-testid="append-event"]').exists()).toBe(true)

    await wrapper.find('[data-testid="lock-open"]').trigger('click')
    await settle()

    const dialogText = wrapper.find('[data-testid="lock-precondition-list"]').text()
    expect(dialogText).toContain('三方核查完成')
    expect(dialogText).toContain('当前有效知情同意已签署')
    expect(dialogText).toContain('已满足')
    expect(wrapper.find('[data-testid="lock-submit"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-testid="lock-confirm"] input').setValue(true)
    await settle()
    expect(wrapper.find('[data-testid="lock-submit"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('[data-testid="lock-submit"]').trigger('click')
    await settle()

    const lockCall = queue.calls.find((call) => call.url.endsWith(`/records/${RECORD_ID}/lock`))
    expect(lockCall?.init?.method).toBe('POST')
    expect(JSON.parse(String(lockCall?.init?.body))).toMatchObject({
      version: 8,
      signatureRequested: true,
      confirmChecklist: true,
    })
    expect(wrapper.find('[data-testid="lock-banner"]').text()).toContain('已锁单')
    expect(wrapper.find('[data-testid="record-status"]').text()).toBe('已锁单')
    expect(wrapper.find('[data-testid="record-locked"]').text()).toBe('已锁单')
    expect(wrapper.find('[data-testid="append-event"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="revise-open"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="lock-open"]').exists()).toBe(false)
  })

  it('前置未满足时禁用锁单提交并展示未满足项', async () => {
    const blocked = {
      ...lockStatus,
      canLock: false,
      preconditions: preconditions.map((item) =>
        item.code === 'CONSENT_SIGNED'
          ? { ...item, satisfied: false, detail: '未找到 SIGNED 同意记录' }
          : item,
      ),
    }
    const queue = createFetchQueue([...detailResponses(baseRecord), jsonResponse(blocked)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="lock-open"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="lock-precondition-list"]').text()).toContain('未满足')
    expect(wrapper.find('[data-testid="lock-precondition-list"]').text()).toContain(
      '未找到 SIGNED 同意记录',
    )
    await wrapper.find('[data-testid="lock-confirm"] input').setValue(true)
    await settle()
    expect(wrapper.find('[data-testid="lock-submit"]').attributes('disabled')).toBeDefined()
  })

  it('锁单后带原因修订，新版本可见且原记录内容不变', async () => {
    const queue = createFetchQueue([
      ...detailResponses(lockedRecord),
      jsonResponse({ items: [] }),
      jsonResponse(revisionDetail, 201),
      ...detailResponses({
        ...lockedRecord,
        status: 'REVISED' as const,
        currentRevision: 1,
        version: 9,
      }),
      jsonResponse({ items: [revisionSummary] }),
      jsonResponse(revisionDetail),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    expect(wrapper.find('[data-testid="lock-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="revision-empty"]').exists()).toBe(true)

    await wrapper.find('[data-testid="revise-open"]').trigger('click')
    await settle()
    await wrapper
      .find('[data-testid="revision-reason"]')
      .setValue('锁单后发现麻醉方式记录笔误（演示）')
    await wrapper
      .find('[data-testid="revision-changes"]')
      .setValue('将麻醉方式由 GENERAL 更正为 COMBINED（演示）')
    // el-select 在 jsdom 下没有原生 option 交互：直接向组件模型提交更正后的麻醉方式。
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    expect(selects.length).toBeGreaterThanOrEqual(2)
    selects[1]?.vm.$emit('update:modelValue', 'COMBINED')
    await settle()
    await wrapper.find('[data-testid="revision-submit"]').trigger('click')
    await settle()

    const reviseCall = queue.calls.find(
      (call) =>
        call.url.endsWith(`/records/${RECORD_ID}/revisions`) && call.init?.method === 'POST',
    )
    expect(reviseCall?.init?.method).toBe('POST')
    const body = JSON.parse(String(reviseCall?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({
      version: 8,
      reasonCode: 'ANESTHESIA_TYPE_CHANGE',
      reason: '锁单后发现麻醉方式记录笔误（演示）',
      changes: '将麻醉方式由 GENERAL 更正为 COMBINED（演示）',
      corrections: { anesthesiaType: 'COMBINED' },
    })

    const revisionList = wrapper.find('[data-testid="revision-list"]').text()
    expect(revisionList).toContain('v1')
    expect(revisionList).toContain('更正麻醉方式')
    expect(revisionList).toContain('bbbbbbbbbbbb')

    await wrapper.find('[data-testid="revision-detail-1"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-testid="revision-diff-summary"]').text()).toContain('1 项字段变更')
    const diff = wrapper.find('[data-testid="revision-diff"]').text()
    expect(diff).toContain('record.anesthesiaType')
    expect(diff).toContain('GENERAL')
    expect(diff).toContain('COMBINED')
    expect(wrapper.find('[data-testid="revision-snapshot"]').text()).toContain('COMBINED')
    // 当前有效值使用修订后的麻醉方式；原记录内容不变，单独展示锁单原始值。
    expect(wrapper.find('[data-testid="record-effective-anesthesia"]').text()).toBe('联合麻醉')
    expect(wrapper.find('[data-testid="record-original-anesthesia"]').text()).toContain('全身麻醉')
  })
})
