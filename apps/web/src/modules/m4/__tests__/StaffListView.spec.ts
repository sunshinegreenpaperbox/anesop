import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StaffListView from '../views/StaffListView.vue'
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

const STAFF_ID = '3a000000-0000-4000-8000-000000000001'
const DEPT_ID = 'f1000000-0000-4000-8000-000000000002'

function staffRecord(overrides: Record<string, unknown> = {}) {
  return {
    staffId: STAFF_ID,
    userId: '10000000-0000-4000-8000-000000000012',
    staffNo: 'STAFF-ANES-001',
    name: '演示麻醉医师甲（合成）',
    departmentId: DEPT_ID,
    departmentName: '麻醉科（合成）',
    title: '主任医师',
    jobGrade: '正高',
    employmentStatus: 'ACTIVE',
    specialties: ['心血管麻醉'],
    qualifications: [
      {
        qualificationId: '4a000000-0000-4000-8000-000000000001',
        qualificationType: 'PRACTICE_LICENSE',
        certificateNo: 'SYNTH-CERT-0001',
        issuingAuthority: '省卫生健康委员会（合成）',
        validFrom: '2020-01-01',
        validTo: '2030-12-31',
        status: 'ACTIVE',
        expired: false,
      },
    ],
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000001',
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: '10000000-0000-4000-8000-000000000001',
    version: 1,
    ...overrides,
  }
}

function listBody(items: unknown[] = [staffRecord()]) {
  return { items, page: 1, size: 50, total: items.length, totalPages: 1 }
}

function departmentBody() {
  return { items: [{ departmentId: DEPT_ID, code: 'DEPT-ANES', name: '麻醉科（合成）' }] }
}

function mountView() {
  return mount(StaffListView, { global: { plugins: [pinia, router, ElementPlus] } })
}

function componentByTestId(wrapper: VueWrapper, name: string, testId: string): VueWrapper {
  const found = (wrapper.findAllComponents({ name }) as VueWrapper[]).find(
    (item) => item.attributes('data-testid') === testId,
  )
  if (!found) throw new Error(`component not found: ${testId}`)
  return found
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('StaffListView 人员档案（M4-M13-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:staff:read', 'm4:staff:write'],
        roles: ['SYS_ANESTHESIOLOGIST'],
      }),
    )
    await router.push('/m4/staff')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载人员档案与科室并按权限展示维护入口', async () => {
    const queue = createFetchQueue([jsonResponse(listBody()), jsonResponse(departmentBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="staff-table"]').text()).toContain('演示麻醉医师甲（合成）')
    expect(wrapper.find('[data-testid="staff-table"]').text()).toContain('在职')
    expect(wrapper.find('[data-testid="staff-total"]').text()).toContain('共 1 条')
    expect(wrapper.find('[data-testid="staff-create"]').exists()).toBe(true)
  })

  it('只读账号不显示新建与编辑入口', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m4:staff:read'] }))
    const queue = createFetchQueue([jsonResponse(listBody([])), jsonResponse(departmentBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="staff-empty"]').text()).toContain('暂无人员档案')
    expect(wrapper.find('[data-testid="staff-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="staff-edit"]').exists()).toBe(false)
  })

  it('新建档案提交工号/科室/资质并刷新列表', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody([])),
      jsonResponse(departmentBody()),
      jsonResponse(staffRecord({ staffId: '3a000000-0000-4000-8000-000000000002' }), 201),
      jsonResponse(listBody([staffRecord()])),
      jsonResponse(departmentBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="staff-create"]').trigger('click')
    await settle()

    await wrapper.find('[data-testid="staff-no"]').setValue('STAFF-ANES-002')
    await wrapper.find('[data-testid="staff-name"]').setValue('演示麻醉医师乙（合成）')
    await wrapper.find('[data-testid="staff-title"]').setValue('主治医师')
    await wrapper.find('[data-testid="staff-specialties"]').setValue('心血管麻醉，老年麻醉')
    await wrapper.find('[data-testid="staff-cert-no"]').setValue('SYNTH-CERT-0002')
    await wrapper.find('[data-testid="staff-cert-authority"]').setValue('省卫生健康委员会（合成）')
    await wrapper.find('[data-testid="staff-valid-from"]').setValue('2021-01-01')
    await componentByTestId(wrapper, 'ElSelect', 'staff-dept').vm.$emit(
      'update:modelValue',
      DEPT_ID,
    )
    await settle()
    await wrapper.find('[data-testid="staff-submit"]').trigger('click')
    await settle()

    const post = queue.calls[2]
    const body = JSON.parse(String(post.init?.body))
    expect(post.url).toContain('/v1/staff')
    expect(body.staffNo).toBe('STAFF-ANES-002')
    expect(body.departmentId).toBe(DEPT_ID)
    expect(body.specialties).toEqual(['心血管麻醉', '老年麻醉'])
    expect(body.qualifications[0].certificateNo).toBe('SYNTH-CERT-0002')
    expect(wrapper.find('[data-testid="staff-table"]').text()).toContain('演示麻醉医师甲（合成）')
  })

  it('工号重复 409 时展示冲突提示与 traceId', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody([])),
      jsonResponse(departmentBody()),
      errorResponse(409, 'STATE_CONFLICT', '状态或版本冲突，操作未生效'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="staff-create"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="staff-no"]').setValue('STAFF-DUP-001')
    await wrapper.find('[data-testid="staff-name"]').setValue('重复人员（合成）')
    await wrapper.find('[data-testid="staff-title"]').setValue('主治医师')
    await componentByTestId(wrapper, 'ElSelect', 'staff-dept').vm.$emit(
      'update:modelValue',
      DEPT_ID,
    )
    await settle()
    await wrapper.find('[data-testid="staff-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="staff-error"]').text()).toContain('重复')
    expect(wrapper.find('[data-testid="staff-error"]').text()).toContain('trace-test-0001')
  })
})
