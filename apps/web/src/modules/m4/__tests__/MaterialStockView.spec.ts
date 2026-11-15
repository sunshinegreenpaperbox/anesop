import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MaterialStockView from '../views/MaterialStockView.vue'
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

function stockLevel() {
  return {
    stockId: '4b000000-0000-4000-8000-000000000001',
    materialId: MATERIAL_ID,
    materialCode: 'MAT-CVC-001',
    materialName: '中心静脉导管（演示）',
    batchNo: 'BATCH-DEMO-001',
    departmentId: DEPT,
    quantity: 3,
    unit: '套',
    expiryDate: '2027-12-31',
    updatedAt: '2026-11-14T08:00:00+08:00',
  }
}

function movement(overrides: Record<string, unknown> = {}) {
  return {
    movementId: '4c000000-0000-4000-8000-000000000001',
    movementType: 'IN',
    materialId: MATERIAL_ID,
    materialCode: 'MAT-CVC-001',
    materialName: '中心静脉导管（演示）',
    batchNo: 'BATCH-DEMO-001',
    expiryDate: '2027-12-31',
    quantity: 1,
    unit: '套',
    departmentId: DEPT,
    counterparty: '中心库房（合成）',
    balanceAfter: 20,
    operatorId: '10000000-0000-4000-8000-000000000004',
    operatorName: '演示护士（合成）',
    occurredAt: '2026-11-14T08:00:00+08:00',
    createdAt: '2026-11-14T08:00:00+08:00',
    ...overrides,
  }
}

function spdStatus() {
  return {
    adapterCode: 'EXT-03',
    name: 'SPD 供应链适配器（模拟）',
    mode: 'SIMULATED',
    available: true,
    degraded: false,
    lastSyncedAt: '2026-11-14T08:00:00+08:00',
    pendingSyncCount: 0,
    message: '未接入真实 SPD 系统（EXT-03），当前为模拟同步',
  }
}

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
        highValue: false,
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

function stockAlert(overrides: Record<string, unknown> = {}) {
  return {
    materialId: MATERIAL_ID,
    materialCode: 'MAT-CVC-001',
    materialName: '中心静脉导管（演示）',
    unit: '套',
    departmentId: DEPT,
    departmentName: '麻醉科（演示）',
    availableQuantity: 2,
    lowStockThreshold: 10,
    suggestedQuantity: 8,
    alertTypes: ['LOW_STOCK'],
    batchCount: 1,
    nearestExpiryDate: '2027-12-31',
    daysToExpiry: 411,
    nearExpiryBatchCount: 0,
    ...overrides,
  }
}

function referenceResponses() {
  return [
    jsonResponse({ items: [{ departmentId: DEPT, code: 'DEPT-ANES', name: '麻醉科（演示）' }] }),
    jsonResponse(materialBody()),
    jsonResponse({ items: [stockLevel()] }),
    jsonResponse({ items: [movement()] }),
    jsonResponse({ items: [stockAlert()], total: 1 }),
    jsonResponse(spdStatus()),
  ]
}

function mountView() {
  return mount(MaterialStockView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 10; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('MaterialStockView 耗材出入库与 SPD（M4-M14-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:material:read', 'm4:material:write'],
        roles: ['SYS_OR_MANAGER'],
      }),
    )
    await router.push('/m4/materials/stock')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载科室、批次库存、流水与 SPD 状态卡', async () => {
    const queue = createFetchQueue(referenceResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="stock-table"]').text()).toContain('BATCH-DEMO-001')
    expect(wrapper.find('[data-testid="movement-table"]').text()).toContain('入库')
    expect(wrapper.find('[data-testid="spd-status"]').text()).toContain('模拟同步正常')
    expect(queue.calls[0]?.url).toContain('/v1/materials/departments')
    expect(queue.calls[2]?.url).toContain(`departmentId=${DEPT}`)
  })

  it('提交入库流水并显示批次余额', async () => {
    const queue = createFetchQueue([
      ...referenceResponses(),
      jsonResponse(movement({ balanceAfter: 20 }), 201),
      jsonResponse({ items: [stockLevel()] }),
      jsonResponse({ items: [movement()] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="movement-batch"]').setValue('BATCH-DEMO-001')
    await wrapper.find('[data-testid="movement-counterparty"]').setValue('中心库房（合成）')
    await wrapper.find('[data-testid="movement-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="stock-result"]').text()).toContain('入库成功')
    expect(wrapper.find('[data-testid="stock-result"]').text()).toContain('20')
  })

  it('数量不足 409 时展示可用量明细', async () => {
    const queue = createFetchQueue([
      ...referenceResponses(),
      jsonResponse(
        {
          code: 'STATE_CONFLICT',
          message: '批次库存不足，操作未生效',
          traceId: 'trace-stock-409',
          details: [{ field: 'quantity', reason: '批次库存不足：可用 3，请求 10' }],
        },
        409,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="movement-batch"]').setValue('BATCH-DEMO-001')
    await wrapper.find('[data-testid="movement-counterparty"]').setValue('手术室（合成）')
    await wrapper.find('[data-testid="movement-quantity"] input').setValue('10')
    await wrapper.find('[data-testid="movement-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="stock-result"]').text()).toContain('可用 3')
    expect(wrapper.find('[data-testid="stock-error"]').exists()).toBe(true)
  })

  it('展示库存预警标签与建议补货量', async () => {
    const queue = createFetchQueue(referenceResponses())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    const table = wrapper.find('[data-testid="alert-table"]')
    expect(table.text()).toContain('低库存')
    expect(table.text()).toContain('8')
    expect(table.text()).toContain('麻醉科（演示）')
    expect(queue.calls.some((call) => call.url.includes('/v1/materials/stock-alerts'))).toBe(true)
  })

  it('按预警类型筛选（服务端 alertType）并渲染零库存/临期标签', async () => {
    const queue = createFetchQueue([
      ...referenceResponses(),
      jsonResponse({
        items: [
          stockAlert({
            availableQuantity: 0,
            suggestedQuantity: 10,
            alertTypes: ['LOW_STOCK', 'ZERO_STOCK'],
            daysToExpiry: -2,
            nearestExpiryDate: '2027-12-30',
            nearExpiryBatchCount: 1,
          }),
        ],
        total: 1,
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    wrapper
      .find('[data-testid="alert-filter"]')
      .findComponent({ name: 'ElSelect' })
      .vm.$emit('update:modelValue', 'NEAR_EXPIRY')
    wrapper
      .find('[data-testid="alert-filter"]')
      .findComponent({ name: 'ElSelect' })
      .vm.$emit('change', 'NEAR_EXPIRY')
    await settle()

    expect(queue.calls.some((call) => call.url.includes('alertType=NEAR_EXPIRY'))).toBe(true)
    expect(wrapper.find('[data-testid="alert-table"]').text()).toContain('零库存')
  })

  it('触发 SPD 模拟同步并刷新状态', async () => {
    const queue = createFetchQueue([
      ...referenceResponses(),
      jsonResponse({
        adapterCode: 'EXT-03',
        success: false,
        degraded: true,
        syncedAt: '2026-11-14T09:00:00+08:00',
        pulledCount: 0,
        pushedCount: 0,
        message: 'SPD 适配器不可用（EXT-03 模拟失败），已降级为本地库存可用',
      }),
      jsonResponse({ ...spdStatus(), available: false, degraded: true, pendingSyncCount: 1 }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="spd-sync"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="stock-result"]').text()).toContain('已降级')
    expect(wrapper.find('[data-testid="spd-status"]').text()).toContain('降级')
  })
})
