import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
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

const record = {
  recordId: RECORD_ID,
  scheduleId: SCHEDULE_ID,
  patientId: '50000000-0000-4000-8000-000000000001',
  patientNo: 'SYNTH-P-0001',
  patientName: '演示患者',
  status: 'IN_PROGRESS' as const,
  anesthesiaType: 'GENERAL' as const,
  startedAt: '2026-12-01T08:40:00+08:00',
  endedAt: null,
  events: [],
  vitalSigns: [],
  threeWayCheckCompleted: true,
  createdAt: '2026-12-01T08:35:00+08:00',
  createdBy: '10000000-0000-4000-8000-000000000001',
  updatedAt: '2026-12-01T08:46:00+08:00',
  updatedBy: '10000000-0000-4000-8000-000000000001',
  version: 3,
}

const event = {
  eventId: '13000000-0000-4000-8000-000000000001',
  recordId: RECORD_ID,
  seq: 1,
  eventType: 'ANESTHESIA_START' as const,
  occurredAt: '2026-12-01T08:40:00+08:00',
  source: 'MANUAL' as const,
  note: null,
  entryReason: null,
  recordedAt: '2026-12-01T08:41:00+08:00',
  recordedBy: '10000000-0000-4000-8000-000000000001',
  createdAt: '2026-12-01T08:41:00+08:00',
}

const drug = {
  drugId: '14000000-0000-4000-8000-000000000002',
  recordId: RECORD_ID,
  drugName: '舒芬太尼（演示）',
  dose: 10,
  unit: 'ug',
  route: 'IV',
  administeredAt: '2026-12-01T09:10:00+08:00',
  narcotic: true,
  secondCheckerId: '10000000-0000-4000-8000-000000000002',
  secondCheckerName: '演示核对护士（合成）',
  note: null,
  createdAt: '2026-12-01T09:11:00+08:00',
  createdBy: '10000000-0000-4000-8000-000000000001',
}

const fluid = {
  fluidId: '16000000-0000-4000-8000-000000000001',
  recordId: RECORD_ID,
  direction: 'INTAKE' as const,
  fluidType: '复方氯化钠（演示）',
  amountMl: 500,
  recordedAt: '2026-12-01T09:00:00+08:00',
  note: null,
  createdAt: '2026-12-01T09:01:00+08:00',
  createdBy: '10000000-0000-4000-8000-000000000001',
}

const alert = {
  alertId: '1c000000-0000-4000-8000-000000000001',
  recordId: RECORD_ID,
  ruleId: '1b000000-0000-4000-8000-000000000001',
  ruleCode: 'SPO2_LOW',
  ruleVersion: 1,
  metric: 'SPO2' as const,
  value: 87,
  unit: '%',
  severity: 'CRITICAL' as const,
  triggeredAt: '2026-12-01T09:20:00+08:00',
  status: 'TRIGGERED' as const,
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

const resuscitation = {
  resuscitationId: '1d000000-0000-4000-8000-000000000001',
  leader: { userId: '10000000-0000-4000-8000-000000000002', name: '演示负责人（合成）' },
  rescuers: [{ name: '演示负责人（合成）' }, { name: '演示护士（合成）' }],
  recordId: RECORD_ID,
  resusType: 'CPR' as const,
  startedAt: '2026-12-01T09:40:00+08:00',
  endedAt: '2026-12-01T09:46:00+08:00',
  eventSummary: { summary: '演示：胸外按压（合成数据）', interventions: ['胸外按压'] },
  drugs: [{ drugName: '肾上腺素（演示）', dose: 1, unit: 'mg', route: 'IV' }],
  outcome: 'SUCCESS' as const,
  createdAt: '2026-12-01T09:50:00+08:00',
  createdBy: '10000000-0000-4000-8000-000000000002',
}

const summary = {
  recordId: RECORD_ID,
  intakeMl: 1500,
  outputMl: 400,
  balanceMl: 1100,
  fluidCount: 3,
  computedAt: '2026-12-01T10:30:00+08:00',
}

const effective = {
  recordId: RECORD_ID,
  locked: false,
  lockedAt: null,
  lockedBy: null,
  status: 'IN_PROGRESS' as const,
  currentRevision: 0,
  original: {
    anesthesiaType: 'GENERAL',
    startedAt: record.startedAt,
    endedAt: record.endedAt,
  },
  effective: {
    anesthesiaType: 'GENERAL',
    startedAt: record.startedAt,
    endedAt: record.endedAt,
  },
  corrections: [],
  signatureRef: null,
  signatureHash: null,
  caStatus: null,
}

function loadedResponses(overrides: Partial<Record<string, unknown>> = {}) {
  return [
    jsonResponse({ items: [record] }),
    jsonResponse({ items: [event] }),
    jsonResponse({ items: [drug] }),
    jsonResponse({ items: [fluid] }),
    jsonResponse(overrides.summary ?? summary),
    jsonResponse(overrides.effective ?? effective),
    jsonResponse(overrides.alerts ?? { items: [alert] }),
    jsonResponse(overrides.resuscitations ?? { items: [resuscitation] }),
  ]
}

function mountView() {
  return mount(RecordView, { global: { plugins: [pinia, router, ElementPlus] } })
}

/** 记录单页有两级串行请求（记录单 → 明细），双次 flush 避免并行跑测时过早断言。 */
async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

describe('RecordView 麻醉记录单', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m2:record:read', 'm2:record:write', 'm2:alert:read', 'm2:rescue:write'],
      }),
    )
    await router.push(`/m2/records/${SCHEDULE_ID}`)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('PACU 入口按 m2:pacu:read 权限显示', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:record:read'] }))
    configureHttp({ fetchImpl: createFetchQueue(loadedResponses()).fetchImpl })
    const withoutPermission = mountView()
    await settle()
    expect(withoutPermission.find('[data-testid="pacu-entry"]').exists()).toBe(false)
    withoutPermission.unmount()

    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:record:read', 'm2:pacu:read'] }))
    configureHttp({ fetchImpl: createFetchQueue(loadedResponses()).fetchImpl })
    const withPermission = mountView()
    await settle()
    expect(withPermission.find('[data-testid="pacu-entry"]').exists()).toBe(true)
  })

  it('台次无记录单时显示空态并可创建草稿', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [] }),
      jsonResponse({ ...record, status: 'DRAFT', startedAt: null, version: 1 }, 201),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(queue.calls[0]?.url).toBe(`/api/v1/anesthesia/records?scheduleId=${SCHEDULE_ID}`)
    expect(wrapper.find('[data-testid="record-empty"]').exists()).toBe(true)

    await wrapper.find('[data-testid="create-record"]').trigger('click')
    await settle()

    expect(queue.calls[1]?.url).toBe('/api/v1/anesthesia/records')
    expect(queue.calls[1]?.init?.method).toBe('POST')
    const body = JSON.parse(String(queue.calls[1]?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({ scheduleId: SCHEDULE_ID, anesthesiaType: 'GENERAL' })
    expect(wrapper.find('[data-testid="record-status"]').text()).toBe('草稿')
    expect(wrapper.find('[data-testid="record-empty"]').exists()).toBe(false)
  })

  it('加载时间轴/用药/出入量并显示服务端汇总', async () => {
    const queue = createFetchQueue(loadedResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(queue.calls.map((call) => call.url)).toEqual([
      `/api/v1/anesthesia/records?scheduleId=${SCHEDULE_ID}`,
      `/api/v1/anesthesia/records/${RECORD_ID}/events`,
      `/api/v1/anesthesia/records/${RECORD_ID}/drugs`,
      `/api/v1/anesthesia/records/${RECORD_ID}/fluids`,
      `/api/v1/anesthesia/records/${RECORD_ID}/io-summary`,
      `/api/v1/anesthesia/records/${RECORD_ID}/effective`,
      `/api/v1/anesthesia/records/${RECORD_ID}/alerts`,
      `/api/v1/anesthesia/records/${RECORD_ID}/resuscitations`,
    ])
    expect(wrapper.find('[data-testid="record-context"]').text()).toContain('演示患者')
    expect(wrapper.find('[data-testid="record-status"]').text()).toBe('进行中')
    expect(wrapper.find('[data-testid="vital-placeholder"]').text()).toContain('体征待采集接入')
    expect(wrapper.find('[data-testid="io-intake"]').text()).toBe('1500 ml')
    expect(wrapper.find('[data-testid="io-output"]').text()).toBe('400 ml')
    expect(wrapper.find('[data-testid="io-balance"]').text()).toBe('1100 ml')
    expect(wrapper.find('[data-testid="event-list"]').text()).toContain('麻醉开始')
    expect(wrapper.find('[data-testid="event-list"]').text()).toContain('1')
    expect(wrapper.find('[data-testid="drug-list"]').text()).toContain('舒芬太尼（演示）')
    expect(wrapper.find('[data-testid="drug-list"]').text()).toContain('演示核对护士（合成）')
    expect(wrapper.find('[data-testid="fluid-list"]').text()).toContain('入量')
    expect(wrapper.find('[data-testid="alert-list"]').text()).toContain('血氧饱和度')
    expect(wrapper.find('[data-testid="alert-list"]').text()).toContain('严重')
    expect(wrapper.find('[data-testid="resuscitation-list"]').text()).toContain('心肺复苏')
  })

  it('追加事件按 append-only 提交并刷新时间轴', async () => {
    const queue = createFetchQueue([
      ...loadedResponses(),
      jsonResponse(
        {
          ...event,
          eventId: '13000000-0000-4000-8000-000000000002',
          seq: 2,
          eventType: 'INDUCTION',
          note: '演示诱导（合成）',
        },
        201,
      ),
      jsonResponse({ items: [record] }),
      jsonResponse({ items: [event] }),
      jsonResponse({ items: [drug] }),
      jsonResponse({ items: [fluid] }),
      jsonResponse(summary),
      jsonResponse(effective),
      jsonResponse({ items: [alert] }),
      jsonResponse({ items: [resuscitation] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="event-note"]').setValue('演示诱导（合成）')
    await wrapper.find('[data-testid="event-entry-reason"]').setValue('补录：由麻醉单补记')
    await wrapper.find('[data-testid="append-event"]').trigger('click')
    await settle()

    const appendCall = queue.calls.find(
      (call) => call.url.endsWith('/events') && call.init?.method === 'POST',
    )
    expect(appendCall?.url).toBe(`/api/v1/anesthesia/records/${RECORD_ID}/events`)
    expect(appendCall?.init?.method).toBe('POST')
    const body = JSON.parse(String(appendCall?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({
      eventType: 'INDUCTION',
      source: 'MANUAL',
      note: '演示诱导（合成）',
      entryReason: '补录：由麻醉单补记',
    })
    expect(String(body.occurredAt)).toMatch(/T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/)
    expect(queue.calls).toHaveLength(17)
  })

  it('麻精用药必须双人核对：缺核对人不发请求，补齐后可保存', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    const queue = createFetchQueue([
      ...loadedResponses(),
      jsonResponse(
        {
          ...drug,
          drugId: '14000000-0000-4000-8000-000000000003',
          drugName: '瑞芬太尼（演示）',
        },
        201,
      ),
      jsonResponse({ items: [record] }),
      jsonResponse({ items: [event] }),
      jsonResponse({ items: [drug] }),
      jsonResponse({ items: [fluid] }),
      jsonResponse(summary),
      jsonResponse(effective),
      jsonResponse({ items: [alert] }),
      jsonResponse({ items: [resuscitation] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    await wrapper.find('[data-testid="drug-name"]').setValue('瑞芬太尼（演示）')
    await wrapper.find('[data-testid="drug-narcotic"] input').setValue(true)
    await wrapper.find('[data-testid="record-drug"]').trigger('click')
    await settle()

    expect(warning).toHaveBeenCalledTimes(1)
    expect(String(warning.mock.calls[0]?.[0])).toContain('双人核对')
    expect(queue.calls).toHaveLength(8)

    await wrapper
      .find('[data-testid="drug-second-checker-id"]')
      .setValue('10000000-0000-4000-8000-000000000002')
    await wrapper.find('[data-testid="drug-second-checker-name"]').setValue('演示核对护士（合成）')
    await wrapper.find('[data-testid="record-drug"]').trigger('click')
    await settle()

    const postCall = queue.calls.find(
      (call) => call.url.endsWith('/drugs') && call.init?.method === 'POST',
    )
    expect(postCall?.url).toBe(`/api/v1/anesthesia/records/${RECORD_ID}/drugs`)
    const body = JSON.parse(String(postCall?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({
      drugName: '瑞芬太尼（演示）',
      narcotic: true,
      secondCheckerId: '10000000-0000-4000-8000-000000000002',
      secondCheckerName: '演示核对护士（合成）',
    })
  })

  it('记录出入量与刷新汇总', async () => {
    const queue = createFetchQueue([
      ...loadedResponses(),
      jsonResponse({ ...fluid, fluidId: '16000000-0000-4000-8000-000000000002' }, 201),
      jsonResponse({ items: [record] }),
      jsonResponse({ items: [event] }),
      jsonResponse({ items: [drug] }),
      jsonResponse({ items: [fluid] }),
      jsonResponse({ ...summary, intakeMl: 2000, outputMl: 400, balanceMl: 1600, fluidCount: 4 }),
      jsonResponse(effective),
      jsonResponse({ items: [alert] }),
      jsonResponse({ items: [resuscitation] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    await wrapper.find('[data-testid="fluid-type"]').setValue('琥珀酰明胶（演示）')
    await wrapper.find('[data-testid="record-fluid"]').trigger('click')
    await settle()

    const postCall = queue.calls.find(
      (call) => call.url.endsWith('/fluids') && call.init?.method === 'POST',
    )
    expect(postCall?.url).toBe(`/api/v1/anesthesia/records/${RECORD_ID}/fluids`)
    const body = JSON.parse(String(postCall?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({
      direction: 'INTAKE',
      fluidType: '琥珀酰明胶（演示）',
      amountMl: 500,
    })
    expect(wrapper.find('[data-testid="io-intake"]').text()).toBe('2000 ml')
    expect(wrapper.find('[data-testid="io-balance"]').text()).toBe('1600 ml')
  })

  it('确认告警需填写原因并提交 version', async () => {
    const queue = createFetchQueue([
      ...loadedResponses(),
      jsonResponse({ ...alert, status: 'ACKNOWLEDGED', version: 2, acknowledgeReason: '已处置' }),
      jsonResponse({ items: [record] }),
      jsonResponse({ items: [event] }),
      jsonResponse({ items: [drug] }),
      jsonResponse({ items: [fluid] }),
      jsonResponse(summary),
      jsonResponse(effective),
      jsonResponse({ items: [{ ...alert, status: 'ACKNOWLEDGED', version: 2 }] }),
      jsonResponse({ items: [resuscitation] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m2:record:read', 'm2:record:write', 'm2:alert:read', 'm2:alert:ack'],
      }),
    )

    const wrapper = mountView()
    await settle()
    await wrapper.find(`[data-testid="alert-confirm-${alert.alertId}"]`).trigger('click')
    await settle()

    await wrapper.find('[data-testid="ack-submit"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="ack-reason"]').setValue('演示：已核对监护仪（合成）')
    await wrapper.find('[data-testid="ack-submit"]').trigger('click')
    await settle()

    const ackCall = queue.calls.find((call) => call.url.endsWith('/acknowledge'))
    expect(ackCall?.url).toBe(`/api/v1/anesthesia/alerts/${alert.alertId}/acknowledge`)
    expect(ackCall?.init?.method).toBe('POST')
    const body = JSON.parse(String(ackCall?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({
      decision: 'CONFIRMED',
      reason: '演示：已核对监护仪（合成）',
      version: 1,
    })
    expect(String(wrapper.find('[data-testid="alert-list"]').text())).toContain('已确认')
  }, 30_000)

  it('保存抢救记录提交结构化字段并刷新列表', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [record] }),
      jsonResponse({ items: [event] }),
      jsonResponse({ items: [drug] }),
      jsonResponse({ items: [fluid] }),
      jsonResponse(summary),
      jsonResponse(effective),
      jsonResponse({ items: [] }),
      jsonResponse(resuscitation, 201),
      jsonResponse({ items: [record] }),
      jsonResponse({ items: [event] }),
      jsonResponse({ items: [drug] }),
      jsonResponse({ items: [fluid] }),
      jsonResponse(summary),
      jsonResponse(effective),
      jsonResponse({ items: [resuscitation] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m2:record:read', 'm2:record:write', 'm2:rescue:write'],
      }),
    )

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="resus-summary"]').setValue('演示：胸外按压（合成）')
    await wrapper.find('[data-testid="resus-interventions"]').setValue('胸外按压,肾上腺素')
    await wrapper
      .find('[data-testid="resus-rescuers"]')
      .setValue('演示负责人（合成）,演示护士（合成）')
    await wrapper.find('[data-testid="resus-drug-name"]').setValue('肾上腺素（演示）')
    await wrapper.find('[data-testid="resus-add-drug"]').trigger('click')
    await wrapper.find('[data-testid="submit-resuscitation"]').trigger('click')
    await settle()

    const postCall = queue.calls.find(
      (call) => call.url.endsWith('/resuscitations') && call.init?.method === 'POST',
    )
    expect(postCall?.url).toBe(`/api/v1/anesthesia/records/${RECORD_ID}/resuscitations`)
    expect(postCall?.init?.method).toBe('POST')
    const body = JSON.parse(String(postCall?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({
      resusType: 'CPR',
      outcome: 'SUCCESS',
      eventSummary: { summary: '演示：胸外按压（合成）', interventions: ['胸外按压', '肾上腺素'] },
      leader: { userId: '1000000000000000001', name: '演示管理员（合成）' },
      rescuers: [{ name: '演示负责人（合成）' }, { name: '演示护士（合成）' }],
    })
    const drugs = body.drugs as Array<Record<string, unknown>>
    expect(drugs).toHaveLength(1)
    expect(drugs[0]).toMatchObject({ drugName: '肾上腺素（演示）', dose: 1, unit: 'mg' })
  }, 30_000)

  it('无 alert:ack 时不显示确认/忽略按钮', async () => {
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m2:record:read', 'm2:record:write', 'm2:alert:read'],
      }),
    )
    const queue = createFetchQueue(loadedResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find(`[data-testid="alert-confirm-${alert.alertId}"]`).exists()).toBe(false)
    // 无 m2:rescue:write 时同样不显示抢救表单（只读角色）。
    expect(wrapper.find('[data-testid="resus-summary"]').exists()).toBe(false)
  })

  it('权限隔离：缺 alert:read/rescue 时跳过对应请求，时间轴/用药/出入量仍加载', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:record:read'] }))
    const queue = createFetchQueue([
      jsonResponse({ items: [record] }),
      jsonResponse({ items: [event] }),
      jsonResponse({ items: [drug] }),
      jsonResponse({ items: [fluid] }),
      jsonResponse(summary),
      jsonResponse(effective),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    const urls = queue.calls.map((call) => call.url)
    expect(urls.some((url) => url.endsWith('/alerts'))).toBe(false)
    expect(urls.some((url) => url.endsWith('/resuscitations'))).toBe(false)
    expect(wrapper.find('[data-testid="alert-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="resuscitation-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="event-list"]').text()).toContain('麻醉开始')
    expect(wrapper.find('[data-testid="drug-list"]').text()).toContain('舒芬太尼（演示）')
    expect(wrapper.find('[data-testid="io-balance"]').text()).toBe('1100 ml')
  })

  it('手动评估按钮提交 evaluate 并只刷新告警（调试/补偿入口）', async () => {
    const queue = createFetchQueue([
      ...loadedResponses(),
      jsonResponse({
        recordId: RECORD_ID,
        evaluatedRules: 5,
        sampledFrames: 4,
        createdAlerts: 1,
        evaluatedThrough: '2026-12-01T10:30:00+08:00',
        evaluatedAt: '2026-12-01T10:35:00+08:00',
      }),
      jsonResponse({ items: [{ ...alert, alertId: '1c000000-0000-4000-8000-000000000099' }] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="alert-evaluate"]').trigger('click')
    await settle()

    const evaluateCall = queue.calls.find((call) => call.url.endsWith('/alerts/evaluate'))
    expect(evaluateCall?.url).toBe(`/api/v1/anesthesia/records/${RECORD_ID}/alerts/evaluate`)
    expect(evaluateCall?.init?.method).toBe('POST')
    expect(queue.calls).toHaveLength(10)
    const alertCalls = queue.calls.filter(
      (call) => call.url === `/api/v1/anesthesia/records/${RECORD_ID}/alerts`,
    )
    expect(alertCalls.length).toBeGreaterThanOrEqual(2)
    expect(String(wrapper.find('[data-testid="alert-list"]').text())).toContain('血氧饱和度')
  })

  it('只读权限下不显示写入表单', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:record:read'] }))
    const queue = createFetchQueue(loadedResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="record-context"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="append-event"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="record-drug"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="record-fluid"]').exists()).toBe(false)
  })
})
