import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MaterialCatalogView from '../views/MaterialCatalogView.vue'
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

const MATERIAL_ID = '4a000000-0000-4000-8000-000000000001'

function material(overrides: Record<string, unknown> = {}) {
  return {
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
    remark: 'SYNTHETIC 演示耗材',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000001',
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: '10000000-0000-4000-8000-000000000001',
    version: 1,
    ...overrides,
  }
}

function listBody(items: unknown[] = [material()]) {
  return { items, page: 1, size: 50, total: items.length, totalPages: 1 }
}

function mountView() {
  return mount(MaterialCatalogView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('MaterialCatalogView 耗材目录（M4-M14-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:material:read', 'm4:material:write'],
        roles: ['SYS_OR_MANAGER'],
      }),
    )
    await router.push('/m4/materials')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载耗材列表并按权限展示维护入口', async () => {
    const queue = createFetchQueue([jsonResponse(listBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="material-table"]').text()).toContain('中心静脉导管（演示）')
    expect(wrapper.find('[data-testid="material-table"]').text()).toContain('高值')
    expect(wrapper.find('[data-testid="material-total"]').text()).toContain('共 1 条')
    expect(wrapper.find('[data-testid="material-create"]').exists()).toBe(true)
  })

  it('只读账号不显示新建入口', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m4:material:read'] }))
    const queue = createFetchQueue([jsonResponse(listBody([]))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="material-empty"]').text()).toContain('暂无耗材')
    expect(wrapper.find('[data-testid="material-create"]').exists()).toBe(false)
  })

  it('新建耗材提交编码/UDI-DI 并刷新列表', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody([])),
      jsonResponse(material({ materialId: '4a000000-0000-4000-8000-000000000002' }), 201),
      jsonResponse(listBody([material()])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="material-create"]').trigger('click')
    await settle()

    await wrapper.find('[data-testid="material-code"]').setValue('MAT-CVC-002')
    await wrapper.find('[data-testid="material-name"]').setValue('中心静脉导管（测试）')
    await wrapper.find('[data-testid="material-spec"]').setValue('7F 三腔')
    await wrapper.find('[data-testid="material-unit"]').setValue('套')
    await wrapper.find('[data-testid="material-udi-di"]').setValue('06937000000002')
    await wrapper.find('[data-testid="material-submit"]').trigger('click')
    await settle()

    expect(queue.calls[1]?.url).toContain('/v1/materials')
    const body = JSON.parse(String(queue.calls[1]?.init?.body))
    expect(body).toMatchObject({
      code: 'MAT-CVC-002',
      name: '中心静脉导管（测试）',
      udiDi: '06937000000002',
      expiryManaged: true,
    })
    expect(wrapper.find('[data-testid="material-table"]').text()).toContain('中心静脉导管（演示）')
  })

  it('停用耗材调用 PATCH 状态端点并刷新列表', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody()),
      jsonResponse(material({ status: 'INACTIVE', version: 2 })),
      jsonResponse(listBody([material({ status: 'INACTIVE', version: 2 })])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="material-toggle-status"]').trigger('click')
    await settle()

    expect(queue.calls[1]?.url).toContain('/v1/materials/' + MATERIAL_ID + '/status')
    expect(String(queue.calls[1]?.init?.method)).toBe('PATCH')
    expect(JSON.parse(String(queue.calls[1]?.init?.body))).toEqual({ status: 'INACTIVE' })
    expect(wrapper.find('[data-testid="material-toggle-status"]').text()).toBe('启用')
  })

  it('展示 409 冲突提示且不静默成功', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody([])),
      jsonResponse(
        {
          code: 'STATE_CONFLICT',
          message: '耗材编码已存在',
          traceId: 'trace-conflict',
          details: [],
        },
        409,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="material-create"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="material-code"]').setValue('MAT-CVC-001')
    await wrapper.find('[data-testid="material-name"]').setValue('重复耗材')
    await wrapper.find('[data-testid="material-spec"]').setValue('7F')
    await wrapper.find('[data-testid="material-unit"]').setValue('套')
    await wrapper.find('[data-testid="material-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="material-error"]').text()).toContain('已被他人修改')
  })
})
