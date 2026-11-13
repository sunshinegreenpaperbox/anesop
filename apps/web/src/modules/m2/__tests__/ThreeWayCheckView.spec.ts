import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ThreeWayCheckView from '../views/ThreeWayCheckView.vue'
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

const APPLICATION_ID = '60000000-0000-4000-8000-000000000001'

const patientSnapshot = {
  patientId: '50000000-0000-4000-8000-000000000001',
  hisPatientNo: 'SYNTH-HIS-P-0001',
  patientName: '演示患者',
  gender: 'FEMALE' as const,
  birthDate: '1972-05-18',
  age: 54,
  departmentName: '演示手术科室（合成）',
  ward: '演示病区（合成）',
  bedNo: '01',
}

const application = {
  applicationId: APPLICATION_ID,
  patientId: patientSnapshot.patientId,
  patientSnapshot,
  surgeryName: '演示腹腔镜胆囊切除术（合成）',
  departmentName: '演示手术科室（合成）',
  status: 'SCHEDULED',
}

const TIMEOUT_AT = '2026-12-01T08:50:00+08:00'
const SIGNOUT_AT = '2026-12-01T10:55:00+08:00'

function check(version: number, signOutAt: string | null, issues: string[] = []) {
  return {
    checkId: `18000000-0000-4000-8000-00000000000${version}`,
    applicationId: APPLICATION_ID,
    timeOutAt: TIMEOUT_AT,
    timeOutBy: 'e0000000-0000-4000-8000-000000000002',
    signOutAt,
    signOutBy: signOutAt ? 'e0000000-0000-4000-8000-000000000002' : null,
    specimenChecked: true,
    implantChecked: true,
    issues,
    createdAt: '2026-12-01T08:52:00+08:00',
    createdBy: 'e0000000-0000-4000-8000-000000000002',
    updatedAt: '2026-12-01T08:52:00+08:00',
    updatedBy: 'e0000000-0000-4000-8000-000000000002',
    version,
  }
}

function listBody(items: unknown[], completed = false) {
  return { application, completed, items }
}

function mountView() {
  return mount(ThreeWayCheckView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
}

describe('ThreeWayCheckView 三方核查', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:threeway:read', 'm2:threeway:write'] }))
    await router.push(`/m2/three-way/${APPLICATION_ID}`)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载申请与核查版本，无记录时显示未完成与空态', async () => {
    const queue = createFetchQueue([jsonResponse(listBody([]))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe(
      `/api/v1/anesthesia/applications/${APPLICATION_ID}/three-way-checks`,
    )
    expect(wrapper.find('[data-testid="application-context"]').text()).toContain('演示患者')
    expect(wrapper.find('[data-testid="threeway-completed"]').text()).toBe('未完成')
    expect(wrapper.find('[data-testid="threeway-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="record-check"]').exists()).toBe(true)
  })

  it('录入 Time-out 后新增版本并保持不变更历史', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const queue = createFetchQueue([
      jsonResponse(listBody([])),
      jsonResponse(check(1, null, ['演示问题项：标本标签待复核（合成数据）'])),
      jsonResponse(listBody([check(1, null, ['演示问题项：标本标签待复核（合成数据）'])])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="specimen-checked"] input').setValue(true)
    await wrapper.find('[data-testid="implant-checked"] input').setValue(true)
    await wrapper.find('[data-testid="issues"]').setValue('演示问题项：标本标签待复核（合成数据）')
    await wrapper.find('[data-testid="record-check"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0]?.[0])).toContain('不可修改')

    expect(queue.calls[1]?.url).toBe(
      `/api/v1/anesthesia/applications/${APPLICATION_ID}/three-way-checks`,
    )
    expect(queue.calls[1]?.init?.method).toBe('POST')
    const body = JSON.parse(String(queue.calls[1]?.init?.body)) as Record<string, unknown>
    expect(String(body.timeOutAt)).toMatch(/T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/)
    expect(body).toMatchObject({
      specimenChecked: true,
      implantChecked: true,
      issues: ['演示问题项：标本标签待复核（合成数据）'],
    })
    expect(body.signOutAt).toBeUndefined()
    expect(wrapper.find('[data-testid="threeway-completed"]').text()).toBe('未完成')
    expect(wrapper.find('[data-testid="threeway-versions"]').text()).toContain('v1')
  })

  it('补录 Sign-out 后最新版本完成，显示已完成与两条版本历史', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const queue = createFetchQueue([
      jsonResponse(listBody([check(1, null)])),
      jsonResponse(check(2, SIGNOUT_AT)),
      jsonResponse(listBody([check(2, SIGNOUT_AT), check(1, null)], true)),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="threeway-completed"]').text()).toBe('未完成')
    expect(wrapper.find('[data-testid="timeout-prefill-note"]').text()).toContain(TIMEOUT_AT)

    await wrapper.find('[data-testid="specimen-checked"] input').setValue(true)
    await wrapper.find('[data-testid="implant-checked"] input').setValue(true)
    await wrapper.find('[data-testid="signout-enabled"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="record-check"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    const body = JSON.parse(String(queue.calls[1]?.init?.body)) as Record<string, unknown>
    expect(body.timeOutAt).toBe(TIMEOUT_AT)
    expect(String(body.signOutAt)).toMatch(/T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/)
    expect(wrapper.find('[data-testid="threeway-completed"]').text()).toBe('已完成')
    expect(wrapper.find('[data-testid="threeway-versions"]').text()).toContain('v2')
    expect(wrapper.find('[data-testid="threeway-versions"]').text()).toContain('v1')
  })

  it('未逐项勾选标本/植入物确认时禁止提交', async () => {
    const queue = createFetchQueue([jsonResponse(listBody([]))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="record-check"]').trigger('click')
    await flushPromises()

    expect(queue.calls).toHaveLength(1)
    expect(queue.calls[0]?.init?.method).not.toBe('POST')
  })

  it('仅在二次确认通过后才提交核查版本', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const queue = createFetchQueue([jsonResponse(listBody([]))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="specimen-checked"] input').setValue(true)
    await wrapper.find('[data-testid="implant-checked"] input').setValue(true)
    await wrapper.find('[data-testid="record-check"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(queue.calls).toHaveLength(1)
  })

  it('已有版本时预填上一版本 Time-out 并沿用提交', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const queue = createFetchQueue([
      jsonResponse(listBody([check(1, null)])),
      jsonResponse(check(2, null)),
      jsonResponse(listBody([check(2, null), check(1, null)])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="timeout-prefill-note"]').text()).toContain(TIMEOUT_AT)
    await wrapper.find('[data-testid="specimen-checked"] input').setValue(true)
    await wrapper.find('[data-testid="implant-checked"] input').setValue(true)
    await wrapper.find('[data-testid="record-check"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    const body = JSON.parse(String(queue.calls[1]?.init?.body)) as Record<string, unknown>
    expect(body.timeOutAt).toBe(TIMEOUT_AT)
    expect(body.confirmTimeOutChange).toBeUndefined()
  })

  it('无 m2:threeway:read 时展示 403 权限态且不渲染表单', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    seedAuthenticatedUser(syntheticUser({ permissions: [] }))
    const wrapper = mountView()
    await flushPromises()

    const alert = wrapper.find('[data-testid="threeway-error"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('无权访问三方核查')
    expect(alert.text()).toContain('m2:threeway:read')
    expect(wrapper.find('[data-testid="record-check"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="threeway-versions"]').exists()).toBe(false)
  })
})
