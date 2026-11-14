import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StaffAuthorizationView from '../views/StaffAuthorizationView.vue'
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

const STAFF_ID = '3a000000-0000-4000-8000-000000000001'
const STAFF_ID_2 = '3a000000-0000-4000-8000-000000000002'
const DEPT_ID = 'f1000000-0000-4000-8000-000000000002'
const APPROVER_ID = '10000000-0000-4000-8000-000000000009'
const SECOND_APPROVER_ID = '10000000-0000-4000-8000-000000000010'

function staffRecord(staffId: string, staffNo: string) {
  return {
    staffId,
    staffNo,
    name: '演示麻醉医师甲（合成）',
    departmentId: DEPT_ID,
    departmentName: '麻醉科（合成）',
    title: '主任医师',
    employmentStatus: 'ACTIVE',
    specialties: ['心血管麻醉'],
    qualifications: [],
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000001',
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: '10000000-0000-4000-8000-000000000001',
    version: 1,
  }
}

function staffList() {
  return {
    items: [staffRecord(STAFF_ID, 'STAFF-ANES-001')],
    page: 1,
    size: 200,
    total: 1,
    totalPages: 1,
  }
}

function staffListTwo() {
  return {
    items: [staffRecord(STAFF_ID, 'STAFF-ANES-001'), staffRecord(STAFF_ID_2, 'STAFF-ANES-002')],
    page: 1,
    size: 200,
    total: 2,
    totalPages: 1,
  }
}

function authorization(
  version: number,
  status = 'ACTIVE',
  overrides: Record<string, unknown> = {},
) {
  return {
    authorizationId: `5a000000-0000-4000-8000-00000000000${version}`,
    staffId: STAFF_ID,
    version,
    level: 'R3',
    surgeryScopes: ['GENERAL'],
    ageMin: 18,
    ageMax: 60,
    asaMax: 'ASA2',
    validFrom: '2026-01-01',
    validTo: '2027-12-31',
    status,
    approvedBy: '10000000-0000-4000-8000-000000000001',
    approvedAt: '2026-11-14T08:00:00+08:00',
    changeReason: '年度授权复评（SYNTHETIC）',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000001',
    ...overrides,
  }
}

function historyBody(version = 1, status = 'ACTIVE') {
  const current = authorization(version, status)
  return { staffId: STAFF_ID, current, history: [current] }
}

function mountView() {
  return mount(StaffAuthorizationView, { global: { plugins: [pinia, router, ElementPlus] } })
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

async function selectStaff(
  wrapper: ReturnType<typeof mountView>,
  staffId = STAFF_ID,
): Promise<void> {
  const select = componentByTestId(wrapper, 'ElSelect', 'auth-staff-select')
  select.vm.$emit('update:modelValue', staffId)
  select.vm.$emit('change', staffId)
  await settle()
}

describe('StaffAuthorizationView 分级授权（M4-M13-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        userId: '10000000-0000-4000-8000-000000000001',
        permissions: ['m4:staff:read', 'm4:staff:write'],
        roles: ['SYS_ANESTHESIOLOGIST'],
      }),
    )
    await router.push('/m4/staff/authorizations')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('选择人员后加载当前授权与历史版本', async () => {
    const queue = createFetchQueue([jsonResponse(staffList()), jsonResponse(historyBody(2))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectStaff(wrapper)

    expect(wrapper.find('[data-testid="auth-current"]').text()).toContain('R3')
    expect(wrapper.find('[data-testid="auth-current"]').text()).toContain('18–60 岁')
    expect(wrapper.find('[data-testid="auth-history"]').text()).toContain('2027-12-31')
  })

  it('授予授权以当前版本 +1 写入并回显新版本', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(historyBody(1)),
      jsonResponse(historyBody(2)),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectStaff(wrapper)
    await wrapper.find('[data-testid="auth-scopes"]').setValue('GENERAL，CARDIAC')
    await wrapper.find('[data-testid="auth-approver"]').setValue(APPROVER_ID)
    await wrapper.find('[data-testid="auth-valid-from"]').setValue('2026-11-01')
    await wrapper.find('[data-testid="auth-valid-to"]').setValue('2027-10-31')
    await wrapper.find('[data-testid="auth-grant"]').trigger('click')
    await settle()

    const put = queue.calls[2]
    const body = JSON.parse(String(put.init?.body))
    expect(put.url).toContain(`/v1/staff/${STAFF_ID}/authorizations`)
    expect(put.init?.method).toBe('PUT')
    expect(body.action).toBe('GRANT')
    expect(body.version).toBe(1)
    expect(body.surgeryScopes).toEqual(['GENERAL', 'CARDIAC'])
    expect(body.approvedBy).toBe(APPROVER_ID)
    expect(wrapper.find('[data-testid="auth-history"]').text()).toContain('2')
  })

  it('R1/R2、通配范围或高 ASA 必须提交第二审批人', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(historyBody(1)),
      jsonResponse(historyBody(2)),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectStaff(wrapper)
    await wrapper.find('[data-testid="auth-approver"]').setValue(APPROVER_ID)
    await wrapper.find('[data-testid="auth-scopes"]').setValue('*')
    await settle()
    expect(wrapper.find('[data-testid="auth-dual-hint"]').exists()).toBe(true)

    // 缺第二审批人不发请求。
    await wrapper.find('[data-testid="auth-grant"]').trigger('click')
    await settle()
    expect(queue.calls).toHaveLength(2)

    await wrapper.find('[data-testid="auth-second-approver"]').setValue(SECOND_APPROVER_ID)
    await wrapper.find('[data-testid="auth-second-reason"]').setValue('复核（SYNTHETIC）')
    await wrapper.find('[data-testid="auth-valid-from"]').setValue('2026-11-01')
    await wrapper.find('[data-testid="auth-valid-to"]').setValue('2027-10-31')
    await wrapper.find('[data-testid="auth-grant"]').trigger('click')
    await settle()

    const body = JSON.parse(String(queue.calls[2].init?.body))
    expect(body.secondApproverId).toBe(SECOND_APPROVER_ID)
    expect(body.secondApproverReason).toBe('复核（SYNTHETIC）')
  })

  it('切换人员重置表单，避免上一个人的范围/审批人泄漏', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffListTwo()),
      jsonResponse(historyBody(1)),
      jsonResponse({ staffId: STAFF_ID_2, current: null, history: [] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectStaff(wrapper)
    await wrapper.find('[data-testid="auth-scopes"]').setValue('CARDIAC')
    await wrapper.find('[data-testid="auth-approver"]').setValue(APPROVER_ID)
    await selectStaff(wrapper, STAFF_ID_2)

    expect((wrapper.find('[data-testid="auth-scopes"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('[data-testid="auth-approver"]').element as HTMLInputElement).value).toBe(
      '',
    )
    expect(wrapper.find('[data-testid="auth-empty"]').text()).toContain('当前无分级授权')
  })

  it('撤销授权必须填写原因，未填写不发请求', async () => {
    const queue = createFetchQueue([jsonResponse(staffList()), jsonResponse(historyBody(1))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectStaff(wrapper)
    await wrapper.find('[data-testid="auth-revoke"]').trigger('click')
    await settle()

    expect(queue.calls).toHaveLength(2)
  })

  it('清空年龄时传 undefined，并可声明 AGE 不适用', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(historyBody(1)),
      jsonResponse({
        allowed: true,
        reasons: [],
        level: 'R3',
        checkedAt: '2026-11-14T10:00:00+08:00',
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectStaff(wrapper)
    const age = componentByTestId(wrapper, 'ElInputNumber', 'auth-check-age')
    age.vm.$emit('update:modelValue', null)
    const notApplicable = componentByTestId(wrapper, 'ElSelect', 'auth-check-not-applicable')
    notApplicable.vm.$emit('update:modelValue', ['AGE'])
    await settle()
    await wrapper.find('[data-testid="auth-check-run"]').trigger('click')
    await settle()

    const body = JSON.parse(String(queue.calls[2].init?.body))
    expect(body.age).toBeUndefined()
    expect(body.notApplicable).toEqual(['AGE'])
    expect(body.surgeryTypeId).toBe('GENERAL')
  })

  it('授权校验不通过时逐条展示原因', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(historyBody(1)),
      jsonResponse({
        allowed: false,
        reasons: [
          { code: 'AGE_OUT_OF_RANGE', message: '患者年龄 72 超出授权年龄段' },
          { code: 'ASA_EXCEEDS_LIMIT', message: 'ASA 分级超出授权上限' },
        ],
        level: 'R3',
        validUntil: '2027-12-31',
        checkedAt: '2026-11-14T10:00:00+08:00',
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await selectStaff(wrapper)
    componentByTestId(wrapper, 'ElInputNumber', 'auth-check-age').vm.$emit('update:modelValue', 72)
    await settle()
    await wrapper.find('[data-testid="auth-check-run"]').trigger('click')
    await settle()

    const reasons = wrapper.find('[data-testid="auth-check-reasons"]').text()
    expect(reasons).toContain('AGE_OUT_OF_RANGE')
    expect(reasons).toContain('ASA_EXCEEDS_LIMIT')
    expect(wrapper.find('[data-testid="auth-check-result"]').text()).toContain('授权不通过')
  })
})
