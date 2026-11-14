import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DrugCatalogView from '../views/DrugCatalogView.vue'
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

const DRUG_ID = '2a000000-0000-4000-8000-000000000001'

function drug(overrides: Record<string, unknown> = {}) {
  return {
    drugId: DRUG_ID,
    code: 'DRUG-SUF-001',
    name: '舒芬太尼（演示）',
    genericName: '枸橼酸舒芬太尼注射液（演示）',
    specification: '50ug/1ml',
    unit: '支',
    dosageForm: 'INJECTION',
    category: 'OPIOID',
    narcotic: true,
    highRisk: true,
    aliases: ['舒芬（演示）'],
    status: 'ACTIVE',
    remark: 'SYNTHETIC 演示药品',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000001',
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: '10000000-0000-4000-8000-000000000001',
    version: 1,
    ...overrides,
  }
}

function listBody(items: unknown[] = [drug()]) {
  return { items, page: 1, size: 50, total: items.length, totalPages: 1 }
}

function mountView() {
  return mount(DrugCatalogView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('DrugCatalogView 药品目录（M4-M15-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ permissions: ['m4:drug:read', 'm4:drug:write'], roles: ['SYS_PHARMACIST'] }),
    )
    await router.push('/m4/drugs')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载药品列表并按权限展示维护入口', async () => {
    const queue = createFetchQueue([jsonResponse(listBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="drug-table"]').text()).toContain('舒芬太尼（演示）')
    expect(wrapper.find('[data-testid="drug-total"]').text()).toContain('共 1 条')
    expect(wrapper.find('[data-testid="drug-create"]').exists()).toBe(true)
  })

  it('只读账号不显示新建入口', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m4:drug:read'] }))
    const queue = createFetchQueue([jsonResponse(listBody([]))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="drug-empty"]').text()).toContain('暂无药品')
    expect(wrapper.find('[data-testid="drug-create"]').exists()).toBe(false)
  })

  it('新建药品提交编码/别名并刷新列表', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody([])),
      jsonResponse(drug({ drugId: '2a000000-0000-4000-8000-000000000002' }), 201),
      jsonResponse(listBody([drug()])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="drug-create"]').trigger('click')
    await settle()

    await wrapper.find('[data-testid="drug-code"]').setValue('DRUG-SUF-002')
    await wrapper.find('[data-testid="drug-name"]').setValue('舒芬太尼（测试）')
    await wrapper.find('[data-testid="drug-spec"]').setValue('50ug/1ml')
    await wrapper.find('[data-testid="drug-unit"]').setValue('支')
    await wrapper.find('[data-testid="drug-aliases"]').setValue('舒芬（测试），备用别名')
    wrapper.findComponent({ name: 'ElSwitch' }).vm.$emit('update:modelValue', true)
    await wrapper.find('[data-testid="drug-submit"]').trigger('click')
    await settle()

    const post = queue.calls[1]
    const body = JSON.parse(String(post.init?.body))
    expect(post.url).toContain('/v1/drugs')
    expect(body.code).toBe('DRUG-SUF-002')
    expect(body.name).toBe('舒芬太尼（测试）')
    expect(body.aliases).toEqual(['舒芬（测试）', '备用别名'])
    expect(body.narcotic).toBe(true)
    expect(wrapper.find('[data-testid="drug-table"]').text()).toContain('舒芬太尼（演示）')
  })

  it('编码重复 409 时展示冲突提示', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody([])),
      errorResponse(409, 'STATE_CONFLICT', '状态或版本冲突，操作未生效'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="drug-create"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="drug-code"]').setValue('DRUG-DUP-001')
    await wrapper.find('[data-testid="drug-name"]').setValue('重复药品（测试）')
    await wrapper.find('[data-testid="drug-spec"]').setValue('1mg')
    await wrapper.find('[data-testid="drug-unit"]').setValue('支')
    await wrapper.find('[data-testid="drug-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="drug-error"]').text()).toContain('重复')
    expect(wrapper.find('[data-testid="drug-error"]').text()).toContain('trace-test-0001')
  })
})
