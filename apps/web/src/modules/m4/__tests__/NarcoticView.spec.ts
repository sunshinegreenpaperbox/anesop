import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NarcoticView from '../views/NarcoticView.vue'
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

const DRUG_ID = '2a000000-0000-4000-8000-000000000001'
const ACTOR_ID = '10000000-0000-4000-8000-000000000003'
const SECOND_SIGNER_ID = '10000000-0000-4000-8000-000000000004'
const DEPARTMENT_ID = 'f1000000-0000-4000-8000-000000000002'

function drug() {
  return {
    drugId: DRUG_ID,
    code: 'DRUG-SUF-001',
    name: '舒芬太尼（演示）',
    specification: '50ug/1ml',
    unit: '支',
    dosageForm: 'INJECTION',
    category: 'OPIOID',
    narcotic: true,
    highRisk: true,
    aliases: [],
    status: 'ACTIVE',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: ACTOR_ID,
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: ACTOR_ID,
    version: 1,
  }
}

function adapter(overrides: Record<string, unknown> = {}) {
  return {
    adapterCode: 'EXT-02',
    name: '药房系统适配器（模拟）',
    mode: 'SIMULATED',
    available: true,
    degraded: false,
    lastSyncedAt: '2026-11-14T08:00:00+08:00',
    pendingSyncCount: 0,
    message: '未接入真实药房系统（EXT-02），当前为模拟同步',
    ...overrides,
  }
}

function register(overrides: Record<string, unknown> = {}) {
  return {
    registerId: '2b000000-0000-4000-8000-000000000001',
    drugId: DRUG_ID,
    drugName: '舒芬太尼（演示）',
    batchNo: 'BATCH-SYNTH-0001',
    quantity: 1,
    unit: '支',
    prescriptionNo: 'RX-SYNTH-0001',
    usedBy: '演示使用人（合成）',
    departmentId: 'f1000000-0000-4000-8000-000000000002',
    usageAt: '2026-11-14T09:10:00+08:00',
    destination: '术中镇痛（演示）',
    firstSignerId: ACTOR_ID,
    firstSignerName: '演示给药人（合成）',
    secondSignerId: SECOND_SIGNER_ID,
    secondSignerName: '演示核对人（合成）',
    createdAt: '2026-11-14T09:12:00+08:00',
    createdBy: ACTOR_ID,
    ...overrides,
  }
}

/** 每次调用都新建 Response：Response body 只能消费一次，复用会导致后续用例读到空响应。 */
function initialResponses(): Response[] {
  return [
    jsonResponse(adapter()),
    jsonResponse({
      items: [{ departmentId: DEPARTMENT_ID, code: 'DEPT-ANES', name: '麻醉科（合成）' }],
    }),
    jsonResponse({ items: [drug()], page: 1, size: 100, total: 1, totalPages: 1 }),
    jsonResponse({ items: [] }),
    jsonResponse({ items: [] }),
    jsonResponse({ items: [] }),
  ]
}

/** B-3：页面必须显式选择科室；用组件事件模拟用户选择（Element Plus Select）。 */
async function selectDepartment(wrapper: ReturnType<typeof mountView>): Promise<void> {
  wrapper
    .find('[data-testid="narcotic-department"]')
    .findComponent({ name: 'ElSelect' })
    .vm.$emit('update:modelValue', DEPARTMENT_ID)
  await settle()
}

function mountView() {
  return mount(NarcoticView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('NarcoticView 麻精五专（M4-M15-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        userId: ACTOR_ID,
        permissions: ['m4:drug:read', 'm4:narcotic:manage'],
        roles: ['SYS_ANESTHESIOLOGIST'],
      }),
    )
    await router.push('/m4/narcotic')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载适配器状态与专册列表，无权限时给出提示', async () => {
    const queue = createFetchQueue(initialResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="adapter-card"]').text()).toContain('模拟同步正常')
    expect(wrapper.find('[data-testid="adapter-message"]').text()).toContain('EXT-02')
    expect(wrapper.find('[data-testid="register-empty"]').text()).toContain('暂无专册记录')
    expect(wrapper.find('[data-testid="narcotic-forbidden"]').exists()).toBe(false)
  })

  it('适配器降级时显示降级标记', async () => {
    const queue = createFetchQueue([
      jsonResponse(
        adapter({
          available: false,
          degraded: true,
          lastSyncedAt: null,
          pendingSyncCount: 1,
          message: '药房适配器不可用（EXT-02 模拟失败），已降级为本地字典可用',
        }),
      ),
      ...initialResponses().slice(1),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="adapter-card"]').text()).toContain('已降级')
  })

  it('双人签字同一人时前端拦截，不发请求', async () => {
    const queue = createFetchQueue(initialResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    const callsBefore = queue.calls.length

    await wrapper.find('[data-testid="register-batch"]').setValue('BATCH-SYNTH-0009')
    await wrapper.find('[data-testid="register-rx"]').setValue('RX-SYNTH-0009')
    await wrapper.find('[data-testid="register-signer-a"]').setValue(ACTOR_ID)
    await wrapper.find('[data-testid="register-signer-b"]').setValue(ACTOR_ID)
    await wrapper.find('[data-testid="register-submit"]').trigger('click')
    await settle()

    expect(queue.calls.length).toBe(callsBefore)
    expect(wrapper.find('[data-testid="register-table"]').text()).toContain('暂无专册记录')
  })

  it('登记专册提交双人签字、批号与处方号并刷新', async () => {
    const queue = createFetchQueue([
      ...initialResponses(),
      jsonResponse(register(), 201),
      jsonResponse({ items: [register()] }),
      jsonResponse({ items: [] }),
      jsonResponse({ items: [] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectDepartment(wrapper)

    await wrapper.find('[data-testid="register-batch"]').setValue('BATCH-SYNTH-0001')
    await wrapper.find('[data-testid="register-rx"]').setValue('RX-SYNTH-0001')
    await wrapper.find('[data-testid="register-used-by"]').setValue('演示使用人（合成）')
    await wrapper.find('[data-testid="register-signer-a"]').setValue(ACTOR_ID)
    await wrapper.find('[data-testid="register-signer-b"]').setValue(SECOND_SIGNER_ID)
    await wrapper.find('[data-testid="register-submit"]').trigger('click')
    await settle()

    // eslint-disable-next-line no-console
    console.log('CALLS', queue.calls.map((call) => `${call.init?.method} ${call.url}`).join(' | '))
    const post = queue.calls.find((call) => call.init?.method === 'POST')
    const body = JSON.parse(String(post?.init?.body))
    expect(post?.url).toContain('/v1/narcotic/registers')
    expect(body.drugId).toBe(DRUG_ID)
    expect(body.departmentId).toBe(DEPARTMENT_ID)
    expect(body.batchNo).toBe('BATCH-SYNTH-0001')
    expect(body.prescriptionNo).toBe('RX-SYNTH-0001')
    expect(body.firstSignerId).toBe(ACTOR_ID)
    expect(body.secondSignerId).toBe(SECOND_SIGNER_ID)
    expect(wrapper.find('[data-testid="register-table"]').text()).toContain('舒芬太尼（演示）')
  })

  it('未选择科室时前端拦截，不提交专册（B-3）', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    const queue = createFetchQueue(initialResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    expect(wrapper.find('[data-testid="narcotic-department"]').exists()).toBe(true)
    const callsBefore = queue.calls.length

    await wrapper.find('[data-testid="register-batch"]').setValue('BATCH-SYNTH-NO-DEPT')
    await wrapper.find('[data-testid="register-rx"]').setValue('RX-SYNTH-NO-DEPT')
    await wrapper.find('[data-testid="register-signer-a"]').setValue(ACTOR_ID)
    await wrapper.find('[data-testid="register-signer-b"]').setValue(SECOND_SIGNER_ID)
    await wrapper.find('[data-testid="register-submit"]').trigger('click')
    await settle()

    expect(queue.calls.length).toBe(callsBefore)
    expect(warning).toHaveBeenCalled()
    expect(String(warning.mock.calls.at(-1)?.[0])).toContain('科室')
  })

  it('日结不平：服务端 409 时展示平账差异明细', async () => {
    const queue = createFetchQueue([
      ...initialResponses(),
      jsonResponse(
        {
          code: 'STATE_CONFLICT',
          message: '状态或版本冲突，操作未生效',
          traceId: 'trace-close-0001',
          details: [
            {
              field: 'closingQuantity',
              reason: '平账校验未通过：期望 10，实盘 8（期初 10 + 入库 4 − 使用 3 − 退回 1）',
            },
          ],
        },
        409,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectDepartment(wrapper)
    wrapper
      .find('[data-testid="close-opening"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:modelValue', 10)
    wrapper
      .find('[data-testid="close-inbound"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:modelValue', 4)
    wrapper
      .find('[data-testid="close-used"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:modelValue', 3)
    wrapper
      .find('[data-testid="close-returned"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:modelValue', 1)
    wrapper
      .find('[data-testid="close-closing"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:modelValue', 8)
    await settle()

    expect(wrapper.find('[data-testid="close-expected"]').text()).toContain('不一致')
    await wrapper.find('[data-testid="close-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="close-details"]').text()).toContain('期望 10')
    expect(wrapper.find('[data-testid="narcotic-error"]').text()).toContain('trace-close-0001')
  })

  it('平账时提交日结并加入列表', async () => {
    const queue = createFetchQueue([
      ...initialResponses(),
      jsonResponse(
        {
          closeId: '2e000000-0000-4000-8000-000000000001',
          closeDate: '2026-11-13',
          shift: 'DAY',
          cabinetCode: 'CAB-ANES-01',
          departmentId: 'f1000000-0000-4000-8000-000000000002',
          openingQuantity: 10,
          inboundQuantity: 4,
          usedQuantity: 3,
          returnedQuantity: 1,
          closingQuantity: 10,
          balanced: true,
          createdAt: '2026-11-14T16:10:00+08:00',
          createdBy: ACTOR_ID,
        },
        201,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectDepartment(wrapper)
    for (const [testid, value] of [
      ['close-opening', 10],
      ['close-inbound', 4],
      ['close-used', 3],
      ['close-returned', 1],
      ['close-closing', 10],
    ] as const) {
      wrapper
        .find(`[data-testid="${testid}"]`)
        .findComponent({ name: 'ElInputNumber' })
        .vm.$emit('update:modelValue', value)
    }
    await settle()
    expect(wrapper.find('[data-testid="close-expected"]').text()).toContain('一致')

    await wrapper.find('[data-testid="close-submit"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-testid="close-table"]').text()).toContain('2026-11-13')
  })
})
