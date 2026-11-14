import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StaffTrainingView from '../views/StaffTrainingView.vue'
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
const TODAY = '2026-11-14'

function staffList() {
  return {
    items: [
      {
        staffId: STAFF_ID,
        staffNo: 'STAFF-ANES-001',
        name: '演示麻醉医师甲（合成）',
        departmentId: DEPT_ID,
        departmentName: '麻醉科（合成）',
        title: '主任医师',
        employmentStatus: 'ACTIVE',
        specialties: [],
        qualifications: [],
        createdAt: '2026-11-14T08:00:00+08:00',
        createdBy: '10000000-0000-4000-8000-000000000001',
        updatedAt: '2026-11-14T08:00:00+08:00',
        updatedBy: '10000000-0000-4000-8000-000000000001',
        version: 1,
      },
    ],
    page: 1,
    size: 200,
    total: 1,
    totalPages: 1,
  }
}

function trainingRecord(overrides: Record<string, unknown> = {}) {
  return {
    trainingId: '7a000000-0000-4000-8000-000000000001',
    staffId: STAFF_ID,
    staffNo: 'STAFF-ANES-001',
    courseCode: 'TRN-AIRWAY-001',
    courseName: '困难气道处理（SYNTHETIC）',
    completedOn: '2026-03-01',
    expiresOn: '2026-12-01',
    certificateNo: 'SYNTH-TRN-0001',
    status: 'EXPIRING',
    daysUntilExpiry: 17,
    createdAt: '2026-03-02T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000001',
    ...overrides,
  }
}

function trainingList(items: unknown[] = [trainingRecord()]) {
  return { items }
}

function expiringBody(items: unknown[] = [trainingRecord()]) {
  return { items, days: 30, asOf: TODAY }
}

function mountView() {
  return mount(StaffTrainingView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('StaffTrainingView 培训与到期提醒（M4-M13-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:staff:read', 'm4:training:manage'],
        roles: ['SYS_ANESTHESIOLOGIST'],
      }),
    )
    await router.push('/m4/staff/trainings')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载培训记录与到期提醒并展示状态', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(
        trainingList([
          trainingRecord(),
          trainingRecord({
            trainingId: '7a000000-0000-4000-8000-000000000002',
            courseCode: 'TRN-EXPIRED-001',
            status: 'EXPIRED',
            expiresOn: '2026-01-01',
            daysUntilExpiry: -317,
          }),
        ]),
      ),
      jsonResponse(expiringBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    const table = wrapper.find('[data-testid="trn-table"]').text()
    expect(table).toContain('即将到期')
    expect(table).toContain('已过期')
    expect(table).toContain('317')
    expect(wrapper.find('[data-testid="trn-expiring-asof"]').text()).toContain(TODAY)
    expect(wrapper.find('[data-testid="trn-expiring-asof"]').text()).toContain('30 天')
  })

  it('登记培训记录提交课程/完成/到期时间', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(trainingList([])),
      jsonResponse(expiringBody([])),
      jsonResponse(trainingRecord(), 201),
      jsonResponse(trainingList()),
      jsonResponse(expiringBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="trn-create"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="trn-course-code"]').setValue('TRN-AIRWAY-002')
    await wrapper.find('[data-testid="trn-course-name"]').setValue('困难气道处理（SYNTHETIC）')
    await wrapper.find('[data-testid="trn-completed"]').setValue('2026-03-01')
    await wrapper.find('[data-testid="trn-expires"]').setValue('2027-02-28')
    await wrapper.find('[data-testid="trn-cert"]').setValue('SYNTH-TRN-0002')
    await wrapper.find('[data-testid="trn-submit"]').trigger('click')
    await settle()

    const post = queue.calls[3]
    const body = JSON.parse(String(post.init?.body))
    expect(post.url).toContain('/v1/staff/trainings')
    expect(body.staffId).toBe(STAFF_ID)
    expect(body.courseCode).toBe('TRN-AIRWAY-002')
    expect(body.expiresOn).toBe('2027-02-28')
  })

  it('同一课程重复登记 409 时展示冲突提示', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(trainingList([])),
      jsonResponse(expiringBody([])),
      errorResponse(409, 'STATE_CONFLICT', '状态或版本冲突，操作未生效'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="trn-create"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="trn-course-code"]').setValue('TRN-DUP-001')
    await wrapper.find('[data-testid="trn-course-name"]').setValue('重复课程（SYNTHETIC）')
    await wrapper.find('[data-testid="trn-completed"]').setValue('2026-03-01')
    await wrapper.find('[data-testid="trn-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="trn-error"]').text()).toContain('课程')
  })
})
