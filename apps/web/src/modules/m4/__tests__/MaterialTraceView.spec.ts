import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MaterialTraceView from '../views/MaterialTraceView.vue'
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

const DEPT = 'f1000000-0000-4000-8000-000000000002'
const MATERIAL_ID = '4a000000-0000-4000-8000-000000000001'
const RECORD_ID = '7a000000-0000-4000-8000-000000000001'
const SCHEDULE_ID = '6a000000-0000-4000-8000-000000000001'
const UDI = '06937000000001^SN-DEMO-001'

function materialBody() {
  return {
    items: [
      {
        materialId: MATERIAL_ID,
        code: 'MAT-CVC-001',
        name: '中心静脉导管（演示）',
        specification: '7F 三腔',
        unit: '套',
        category: 'CATHETER',
        udiDi: '06937000000001',
        highValue: true,
        expiryManaged: true,
        status: 'ACTIVE',
        version: 1,
        createdAt: '2026-11-14T08:00:00+08:00',
        createdBy: '10000000-0000-4000-8000-000000000001',
        updatedAt: '2026-11-14T08:00:00+08:00',
        updatedBy: '10000000-0000-4000-8000-000000000001',
      },
    ],
    page: 1,
    size: 200,
    total: 1,
    totalPages: 1,
  }
}

function instance(overrides: Record<string, unknown> = {}) {
  return {
    udi: UDI,
    udiDi: '06937000000001',
    udiPi: 'SN-DEMO-001',
    serialNumber: 'SN-DEMO-001',
    materialId: MATERIAL_ID,
    materialCode: 'MAT-CVC-001',
    materialName: '中心静脉导管（演示）',
    batchNo: 'BATCH-DEMO-001',
    expiryDate: '2027-12-31',
    departmentId: DEPT,
    status: 'IN_STOCK',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000004',
    version: 1,
    ...overrides,
  }
}

function traceBody(overrides: Record<string, unknown> = {}) {
  return {
    instance: instance({
      status: 'USED',
      recordId: RECORD_ID,
      scheduleId: SCHEDULE_ID,
      usedAt: '2026-11-14T09:00:00+08:00',
      usedBy: '10000000-0000-4000-8000-000000000004',
      version: 2,
    }),
    material: {
      materialId: MATERIAL_ID,
      code: 'MAT-CVC-001',
      name: '中心静脉导管（演示）',
      specification: '7F 三腔',
      unit: '套',
      highValue: true,
      expiryManaged: true,
      status: 'ACTIVE',
    },
    movements: [
      {
        movementId: '4c000000-0000-4000-8000-000000000001',
        movementType: 'IN',
        materialId: MATERIAL_ID,
        materialCode: 'MAT-CVC-001',
        materialName: '中心静脉导管（演示）',
        batchNo: 'BATCH-DEMO-001',
        quantity: 1,
        unit: '套',
        departmentId: DEPT,
        counterparty: '中心库房（合成）',
        balanceAfter: 1,
        operatorId: '10000000-0000-4000-8000-000000000004',
        operatorName: '演示护士（合成）',
        occurredAt: '2026-11-14T08:00:00+08:00',
        createdAt: '2026-11-14T08:00:00+08:00',
      },
    ],
    record: { recordId: RECORD_ID, scheduleId: SCHEDULE_ID },
    ...overrides,
  }
}

function references() {
  return [
    jsonResponse({ items: [{ departmentId: DEPT, code: 'DEPT-ANES', name: '麻醉科（演示）' }] }),
    jsonResponse(materialBody()),
  ]
}

function mountView() {
  return mount(MaterialTraceView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 10; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('MaterialTraceView UDI 使用与追溯（M4-M14-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:material:read', 'm4:udi:manage'],
        roles: ['SYS_NURSE_ANESTHETIST'],
      }),
    )
    await router.push('/m4/materials/trace')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('登记 UDI 入库并自动查询追溯链', async () => {
    const queue = createFetchQueue([
      ...references(),
      jsonResponse(instance(), 201),
      jsonResponse(traceBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="udi-register-di"]').setValue('06937000000001')
    await wrapper.find('[data-testid="udi-register-pi"]').setValue('SN-DEMO-001')
    await wrapper.find('[data-testid="udi-register-batch"]').setValue('BATCH-DEMO-001')
    await wrapper.find('[data-testid="udi-register-source"]').setValue('中心库房（合成）')
    await wrapper.find('[data-testid="udi-register-submit"]').trigger('click')
    await settle()

    const postBody = JSON.parse(String(queue.calls[2]?.init?.body))
    expect(postBody).toMatchObject({
      materialId: MATERIAL_ID,
      udiDi: '06937000000001',
      udiPi: 'SN-DEMO-001',
      batchNo: 'BATCH-DEMO-001',
      departmentId: DEPT,
    })
    expect(wrapper.find('[data-testid="udi-register-result"]').text()).toContain('已登记')
    expect(wrapper.find('[data-testid="udi-status"]').text()).toContain('已使用')
  })

  it('查询追溯链展示耗材、流水与记录绑定', async () => {
    const queue = createFetchQueue([...references(), jsonResponse(traceBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="udi-input"]').setValue(UDI)
    await wrapper.find('[data-testid="udi-query"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="udi-material"]').text()).toContain('中心静脉导管（演示）')
    expect(wrapper.find('[data-testid="udi-movements"]').text()).toContain('入库')
    expect(wrapper.find('[data-testid="udi-record"]').text()).toContain(RECORD_ID)
  })

  it('使用 UDI 绑定记录并刷新状态', async () => {
    const queue = createFetchQueue([
      ...references(),
      jsonResponse(instance({ status: 'USED', recordId: RECORD_ID, scheduleId: SCHEDULE_ID })),
      jsonResponse(traceBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="udi-input"]').setValue(UDI)
    await wrapper.find('[data-testid="udi-use-record"]').setValue(RECORD_ID)
    await wrapper.find('[data-testid="udi-use-submit"]').trigger('click')
    await settle()

    const postBody = JSON.parse(String(queue.calls[2]?.init?.body))
    expect(postBody).toMatchObject({ recordId: RECORD_ID })
    expect(wrapper.find('[data-testid="udi-use-result"]').text()).toContain('已使用并绑定记录')
    expect(wrapper.find('[data-testid="udi-record"]').text()).toContain(RECORD_ID)
  })

  it('UDI 不存在时展示 404 提示且不渲染追溯链', async () => {
    const queue = createFetchQueue([
      ...references(),
      jsonResponse(
        {
          code: 'RESOURCE_NOT_FOUND',
          message: 'UDI 实例不存在或不可见',
          traceId: 'trace-udi-404',
          details: [],
        },
        404,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="udi-input"]').setValue('UNKNOWN^UDI')
    await wrapper.find('[data-testid="udi-query"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="udi-error"]').text()).toContain(
      '不存在或不在当前科室数据范围',
    )
    expect(wrapper.find('[data-testid="udi-material"]').exists()).toBe(false)
  })
})
