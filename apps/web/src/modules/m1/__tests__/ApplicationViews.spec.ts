import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import ApplicationListView from '../views/ApplicationListView.vue'
import ApplicationDetailView from '../views/ApplicationDetailView.vue'
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

const application = {
  applicationId: '6000000000000000001',
  hisApplicationNo: 'SYNTH-HIS-APP-0001',
  patient: {
    patientId: '5000000000000000001',
    patientNo: 'SYNTH-P-0001',
    hisPatientNo: 'SYNTH-HIS-P-0001',
    name: '演示患者',
    gender: 'FEMALE' as const,
    birthDate: '1978-03-15',
    age: 48,
    inpatientNoMasked: 'SYNT****0001',
    departmentId: 'f1000000-0000-4000-8000-000000000002',
    departmentName: '麻醉科（合成）',
    ward: '演示病区',
    bedNo: '12',
    siteId: 'f1000000-0000-4000-8000-000000000001',
  },
  surgeryName: '演示术式（腹腔镜胆囊切除术）',
  surgeryCode: 'SYNTH-OP-0002',
  urgency: 'URGENT' as const,
  plannedDurationMin: 90,
  departmentName: '麻醉科（合成）',
  surgeonName: '演示术者',
  preopDiagnosis: '演示诊断（胆囊结石）',
  status: 'SUBMITTED' as const,
  submittedAt: '2026-11-08T10:00:00+08:00',
  source: 'HIS' as const,
  remark: '演示备注（合成数据）',
  statusHistory: [
    {
      fromStatus: null,
      toStatus: 'SUBMITTED' as const,
      reason: 'HIS 导入',
      operatorId: 'e0000000-0000-4000-8000-000000000001',
      occurredAt: '2026-11-08T10:00:00+08:00',
    },
  ],
  createdAt: '2026-11-08T10:00:00+08:00',
  createdBy: 'e0000000-0000-4000-8000-000000000001',
  updatedAt: '2026-11-08T10:00:00+08:00',
  updatedBy: 'e0000000-0000-4000-8000-000000000001',
  version: 1,
}

function page(items: unknown[] = [application]) {
  return {
    items,
    meta: { page: 1, size: 10, total: items.length, totalPages: items.length > 0 ? 1 : 0 },
  }
}

function mountList() {
  return mount(ApplicationListView, { global: { plugins: [pinia, router, ElementPlus] } })
}

function mountDetail() {
  return mount(ApplicationDetailView, { global: { plugins: [pinia, router, ElementPlus] } })
}

describe('ApplicationListView 手术申请列表', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ permissions: ['m1:application:read', 'm1:application:write'] }),
    )
    await router.push('/m1/applications')
  })

  afterEach(() => {
    resetAppState()
  })

  it('渲染申请列表并展示脱敏患者信息', async () => {
    const queue = createFetchQueue([jsonResponse(page())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountList()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/scheduling/applications?page=1&size=10')
    expect(wrapper.text()).toContain('SYNTH-HIS-APP-0001')
    expect(wrapper.text()).toContain('演示患者')
    expect(wrapper.text()).toContain('SYNT****0001')
    expect(wrapper.text()).toContain('限期')
    expect(wrapper.text()).toContain('已提交')
    expect(wrapper.find('[data-testid="import-button"]').exists()).toBe(true)
  })

  it('按状态/紧急度/科室筛选时重新请求并带查询参数', async () => {
    const queue = createFetchQueue([jsonResponse(page()), jsonResponse(page())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountList()
    await flushPromises()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    selects[0]?.vm.$emit('update:modelValue', 'SUBMITTED')
    selects[1]?.vm.$emit('update:modelValue', 'URGENT')
    await wrapper.find('input[data-testid="department-filter"]').setValue('麻醉科（合成）')
    await wrapper.find('[data-testid="search-button"]').trigger('click')
    await flushPromises()

    expect(queue.calls[1]?.url).toContain('status=SUBMITTED')
    expect(queue.calls[1]?.url).toContain('urgency=URGENT')
    const params = new URL(queue.calls[1]!.url, 'http://localhost').searchParams
    expect(params.get('departmentName')).toBe('麻醉科（合成）')
  })

  it('接口失败时展示契约错误码与追踪号', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'trace-403'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.text()).toContain('无权访问该资源或数据范围不足')
    expect(wrapper.text()).toContain('AUTH_FORBIDDEN')
    expect(wrapper.text()).toContain('trace-403')
  })

  it('具备 m2:preop:read 时提供“术前访视”入口并跳转 /m2/preop/:applicationId', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:application:read', 'm2:preop:read'] }))
    await router.push('/m1/applications')
    const queue = createFetchQueue([jsonResponse(page())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountList()
    await flushPromises()

    const entry = wrapper.find('[data-testid="preop-entry"]')
    expect(entry.exists()).toBe(true)
    await entry.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('m2-preop')
    expect(router.currentRoute.value.params.applicationId).toBe(application.applicationId)
  })

  it('无 m2:preop:read 时隐藏“术前访视”入口', async () => {
    const queue = createFetchQueue([jsonResponse(page())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('[data-testid="preop-entry"]').exists()).toBe(false)
  })

  it('无写权限时隐藏导入与取消按钮', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:application:read'] }))
    await router.push('/m1/applications')
    const queue = createFetchQueue([jsonResponse(page())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('[data-testid="import-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cancel-button"]').exists()).toBe(false)
  })
})

describe('ApplicationDetailView 手术申请详情', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ permissions: ['m1:application:read', 'm1:application:write'] }),
    )
    await router.push(`/m1/applications/${application.applicationId}`)
  })

  afterEach(() => {
    resetAppState()
  })

  it('展示详情、脱敏患者摘要与状态历史', async () => {
    const queue = createFetchQueue([jsonResponse(application)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountDetail()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe(`/api/v1/scheduling/applications/${application.applicationId}`)
    expect(wrapper.text()).toContain('SYNTH-HIS-APP-0001')
    expect(wrapper.text()).toContain('SYNT****0001')
    expect(wrapper.text()).toContain('HIS 导入')
    expect(wrapper.text()).toContain('已提交')
    expect(wrapper.find('[data-testid="cancel-button"]').exists()).toBe(true)
  })

  it('详情页提供“术前访视”入口并跳转 /m2/preop/:applicationId', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:application:read', 'm2:preop:read'] }))
    await router.push(`/m1/applications/${application.applicationId}`)
    const queue = createFetchQueue([jsonResponse(application)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountDetail()
    await flushPromises()

    const entry = wrapper.find('[data-testid="preop-entry"]')
    expect(entry.exists()).toBe(true)
    await entry.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('m2-preop')
    expect(router.currentRoute.value.params.applicationId).toBe(application.applicationId)
  })

  it('详情 404 时展示错误码且无取消按钮', async () => {
    const queue = createFetchQueue([
      errorResponse(404, 'RESOURCE_NOT_FOUND', '资源不存在或不可见', 'trace-404'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.text()).toContain('资源不存在或不可见')
    expect(wrapper.text()).toContain('RESOURCE_NOT_FOUND')
    expect(wrapper.find('[data-testid="cancel-button"]').exists()).toBe(false)
  })
})
