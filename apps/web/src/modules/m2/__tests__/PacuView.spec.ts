import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PacuView from '../views/PacuView.vue'
import { configureHttp } from '../../../api/http'
import router from '../../../router'
import { pinia } from '../../../stores'
import {
  createFetchQueue,
  errorResponse,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from '../../../__tests__/testUtils'

enableAutoUnmount(afterEach)

const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const PACU_ID = '19000000-0000-4000-8000-000000000001'

const vitals = {
  systolicBp: 118,
  diastolicBp: 72,
  heartRate: 82,
  spo2: 97,
  respiratoryRate: 14,
  temperature: 36.5,
}

function pacuRecord(overrides: Record<string, unknown> = {}) {
  return {
    pacuId: PACU_ID,
    recordId: RECORD_ID,
    patientId: '50000000-0000-4000-8000-000000000001',
    admittedAt: '2026-12-01T11:05:00+08:00',
    dischargedAt: null,
    status: 'MONITORING',
    vitalSigns: vitals,
    consciousness: 'ALERT',
    painScore: 3,
    nausea: false,
    airway: 'NATURAL',
    latestAssessmentId: null,
    latestAssessmentAt: null,
    latestAldreteTotal: null,
    aldreteThreshold: 9,
    createdAt: '2026-12-01T11:05:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000002',
    updatedAt: '2026-12-01T11:05:00+08:00',
    updatedBy: '10000000-0000-4000-8000-000000000002',
    version: 1,
    ...overrides,
  }
}

function assessment(total: number, overrides: Record<string, unknown> = {}) {
  return {
    assessmentId: `1a000000-0000-4000-8000-00000000000${total}`,
    pacuId: PACU_ID,
    assessedAt: '2026-12-01T11:15:00+08:00',
    activity: 2,
    respiration: 2,
    circulation: 2,
    consciousness: 2,
    oxygenation: 1,
    aldreteTotal: total,
    notes: null,
    createdAt: '2026-12-01T11:15:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000002',
    ...overrides,
  }
}

function dischargeBody(overrides: Record<string, unknown> = {}) {
  return {
    dischargeId: '1b000000-0000-4000-8000-000000000001',
    pacuId: PACU_ID,
    dischargedAt: '2026-12-01T12:10:00+08:00',
    destination: 'WARD',
    handover: {
      situation: '演示：PACU 观察 65 分钟（合成）',
      background: '演示：ASA II，术中平稳（合成）',
      assessment: '演示：Aldrete 10 分（合成）',
      recommendation: '演示：返病房继续监测（合成）',
    },
    criteria: {
      aldreteThreshold: 9,
      aldreteTotal: 10,
      meetsThreshold: true,
      itemsComplete: true,
      latestAssessmentId: '1a000000-0000-4000-8000-000000000010',
      latestAssessmentAt: '2026-12-01T11:30:00+08:00',
    },
    createdAt: '2026-12-01T12:10:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000002',
    ...overrides,
  }
}

/** 一次完整加载：list → detail/assessments/discharge。 */
function loadResponses(
  records: unknown[],
  detail: unknown,
  items: unknown[],
  discharge: unknown | null,
) {
  return [
    jsonResponse({ items: records }),
    jsonResponse(detail),
    jsonResponse({ items }),
    discharge ? jsonResponse(discharge) : errorResponse(404, 'NOT_FOUND', '不存在'),
  ]
}

function mountView() {
  return mount(PacuView, { global: { plugins: [pinia, router, ElementPlus] } })
}

/**
 * 入室表单无默认值：向 el-input-number / el-select 组件模型逐项显式录入
 * （jsdom 下没有原生数字输入/下拉交互）。
 */
function fillAdmission(wrapper: ReturnType<typeof mountView>): void {
  const numbers = wrapper.findAllComponents({ name: 'ElInputNumber' })
  expect(numbers.length).toBe(7)
  const values = [118, 72, 82, 97, 14, 36.5, 3]
  numbers.forEach((input, index) => input.vm.$emit('update:modelValue', values[index]))
  const selects = wrapper.findAllComponents({ name: 'ElSelect' })
  expect(selects.length).toBe(2)
  selects[0]?.vm.$emit('update:modelValue', 'ALERT')
  selects[1]?.vm.$emit('update:modelValue', 'NATURAL')
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('PacuView 复苏记录与转出（M3-M10-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:pacu:read', 'm2:pacu:write'] }))
    await router.push(`/m2/pacu/${RECORD_ID}`)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('空态入室：体征不预填，必须逐项录入后提交', async () => {
    const created = pacuRecord({ status: 'ADMITTED' })
    const queue = createFetchQueue([
      jsonResponse({ items: [] }),
      jsonResponse(created, 201),
      ...loadResponses([created], created, [], null),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    expect(wrapper.find('[data-testid="pacu-empty"]').exists()).toBe(true)

    await wrapper.find('[data-testid="pacu-admit-open"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-testid="admit-required-hint"]').exists()).toBe(true)

    // 未录入体征直接提交：不发请求（不得以默认 118/72/82/97/14/36.5 伪造数据）。
    await wrapper.find('[data-testid="admit-submit"]').trigger('click')
    await settle()
    expect(
      queue.calls.filter(
        (call) => call.url.endsWith('/pacu-records') && call.init?.method === 'POST',
      ),
    ).toHaveLength(0)

    fillAdmission(wrapper)
    await settle()
    await wrapper.find('[data-testid="admit-submit"]').trigger('click')
    await settle()

    const admitCall = queue.calls.find(
      (call) => call.url.endsWith('/pacu-records') && call.init?.method === 'POST',
    )
    expect(admitCall).toBeDefined()
    expect(JSON.parse(String(admitCall?.init?.body))).toMatchObject({
      recordId: RECORD_ID,
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
    })
    expect(wrapper.find('[data-testid="pacu-status"]').text()).toBe('已入室')
    expect(wrapper.find('[data-testid="pacu-vitals"]').text()).toContain('HR 82 bpm')
    expect(wrapper.find('[data-testid="pacu-refresh"]').exists()).toBe(true)
  })

  it('Aldrete 分项未选齐时不可提交（不预填默认分值）', async () => {
    const active = pacuRecord({ status: 'ADMITTED' })
    const queue = createFetchQueue([...loadResponses([active], active, [], null)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="aldrete-total"]').text()).toContain('—')
    expect(wrapper.find('[data-testid="aldrete-incomplete"]').text()).toContain('未选齐不可提交')
    const submit = wrapper.find('[data-testid="aldrete-submit"]')
    expect(submit.attributes('disabled')).toBeDefined()

    await submit.trigger('click')
    await settle()
    const assessmentPosts = queue.calls.filter(
      (call) =>
        call.url.endsWith(`/pacu-records/${PACU_ID}/assessments`) && call.init?.method === 'POST',
    )
    expect(assessmentPosts).toHaveLength(0)
  })

  it('逐项显式选择 Aldrete 后提交（总分以服务端为准）', async () => {
    const active = pacuRecord({ status: 'ADMITTED' })
    const first = assessment(10)
    const updated = pacuRecord({ status: 'READY', latestAldreteTotal: 10, version: 2 })
    const queue = createFetchQueue([
      ...loadResponses([active], active, [], null),
      jsonResponse(first, 201),
      jsonResponse(updated),
      jsonResponse({ items: [first] }),
      errorResponse(404, 'NOT_FOUND', '转出记录不存在'),
      ...loadResponses([updated], updated, [first], null),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    expect(wrapper.find('[data-testid="aldrete-total"]').text()).toContain('—')

    const groups = wrapper.findAllComponents({ name: 'ElRadioGroup' })
    expect(groups.length).toBe(5)
    for (const group of groups) {
      group.vm.$emit('update:modelValue', 2)
    }
    await settle()
    expect(wrapper.find('[data-testid="aldrete-total"]').text()).toContain('10 / 10')
    expect(wrapper.find('[data-testid="aldrete-threshold-hint"]').text()).toContain('已达到')

    await wrapper.find('[data-testid="aldrete-submit"]').trigger('click')
    await settle()

    const assessmentCall = queue.calls.find(
      (call) =>
        call.url.endsWith(`/pacu-records/${PACU_ID}/assessments`) && call.init?.method === 'POST',
    )
    expect(JSON.parse(String(assessmentCall?.init?.body))).toMatchObject({
      activity: 2,
      respiration: 2,
      circulation: 2,
      consciousness: 2,
      oxygenation: 2,
    })
    expect(wrapper.find('[data-testid="pacu-status"]').text()).toBe('已达转出标准')
    expect(wrapper.find('[data-testid="aldrete-timeline"]').text()).toContain('10')
    // 提交成功后清空分项，下一次评估必须重新逐项显式选择。
    expect(wrapper.find('[data-testid="aldrete-total"]').text()).toContain('—')
    expect(wrapper.find('[data-testid="aldrete-submit"]').attributes('disabled')).toBeDefined()
  })

  it('转出对话框校验 SBAR/去向/达标确认并展示转出交接', async () => {
    const active = pacuRecord({ status: 'READY', latestAldreteTotal: 10 })
    const created = dischargeBody()
    const discharged = pacuRecord({
      status: 'DISCHARGED',
      dischargedAt: created.dischargedAt,
      latestAldreteTotal: 10,
      version: 4,
    })
    const queue = createFetchQueue([
      ...loadResponses([active], active, [assessment(10)], null),
      jsonResponse(created, 201),
      ...loadResponses([discharged], discharged, [assessment(10)], created),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="pacu-discharge-open"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-testid="discharge-criteria-hint"]').text()).toContain(
      '服务端最近一次 Aldrete 10 分',
    )

    await wrapper.find('[data-testid="sbar-situation"]').setValue('演示 S（合成）')
    await wrapper.find('[data-testid="sbar-background"]').setValue('演示 B（合成）')
    await wrapper.find('[data-testid="sbar-assessment"]').setValue('演示 A（合成）')
    await wrapper.find('[data-testid="sbar-recommendation"]').setValue('演示 R（合成）')
    await wrapper.find('[data-testid="discharge-confirm"] input').setValue(true)
    await settle()
    await wrapper.find('[data-testid="discharge-submit"]').trigger('click')
    await settle()

    const dischargeCall = queue.calls.find(
      (call) =>
        call.url.endsWith(`/pacu-records/${PACU_ID}/discharge`) && call.init?.method === 'POST',
    )
    expect(JSON.parse(String(dischargeCall?.init?.body))).toMatchObject({
      destination: 'WARD',
      criteriaConfirmed: true,
      handover: {
        situation: '演示 S（合成）',
        background: '演示 B（合成）',
        assessment: '演示 A（合成）',
        recommendation: '演示 R（合成）',
      },
    })
    expect(wrapper.find('[data-testid="pacu-status"]').text()).toBe('已转出')
    expect(wrapper.text()).toContain('S 现状')
    expect(wrapper.text()).toContain('演示：返病房继续监测（合成）')
    expect(wrapper.find('[data-testid="pacu-discharge-open"]').exists()).toBe(false)
  })

  it('阈值不足时 409 展示服务端未满足条件且不关闭对话框', async () => {
    const active = pacuRecord({ status: 'MONITORING', latestAldreteTotal: 8 })
    const queue = createFetchQueue([
      ...loadResponses([active], active, [assessment(8)], null),
      jsonResponse(
        {
          code: 'STATE_CONFLICT',
          message: '复苏转出条件未满足，操作未生效',
          traceId: 'trace-0001',
          details: [{ field: 'ALDRETE_THRESHOLD_MET', reason: 'Aldrete 总分未达到转出阈值 9' }],
        },
        409,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="pacu-discharge-open"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-testid="discharge-criteria-hint"]').text()).toContain(
      '服务端最近一次 Aldrete 8 分',
    )
    await wrapper.find('[data-testid="discharge-confirm"] input').setValue(true)
    await settle()
    await wrapper.find('[data-testid="discharge-submit"]').trigger('click')
    await settle()

    const unmet = wrapper.find('[data-testid="discharge-unmet"]')
    expect(unmet.exists()).toBe(true)
    expect(unmet.text()).toContain('ALDRETE_THRESHOLD_MET')
    expect(wrapper.find('[data-testid="pacu-error"]').text()).toContain('STATE_CONFLICT')
    expect(wrapper.find('[data-testid="discharge-submit"]').exists()).toBe(true)
  })
})
