import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StaffScheduleView from '../views/StaffScheduleView.vue'
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
const SCHEDULE_ID = '6a000000-0000-4000-8000-000000000001'
const DEPT_ID = 'f1000000-0000-4000-8000-000000000002'
const ROOM_ID = 'f2000000-0000-4000-8000-000000000001'

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

function scheduleRecord(overrides: Record<string, unknown> = {}) {
  return {
    scheduleId: SCHEDULE_ID,
    staffId: STAFF_ID,
    staffNo: 'STAFF-ANES-001',
    scheduleDate: '2026-11-20',
    shift: 'DAY',
    roomId: ROOM_ID,
    departmentId: DEPT_ID,
    status: 'PLANNED',
    note: 'SYNTHETIC 演示排班',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: '10000000-0000-4000-8000-000000000001',
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: '10000000-0000-4000-8000-000000000001',
    version: 1,
    ...overrides,
  }
}

function scheduleList(items: unknown[] = [scheduleRecord()]) {
  return { items }
}

function conflictResponse() {
  return jsonResponse(
    {
      code: 'STATE_CONFLICT',
      message: '状态或版本冲突，操作未生效',
      traceId: 'trace-test-0001',
      details: [
        {
          field: 'staffId',
          reason: '该人员 2026-11-20 白班已有排班（排班标识 ' + SCHEDULE_ID + '）',
        },
      ],
    },
    409,
  )
}

function mountView() {
  return mount(StaffScheduleView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('StaffScheduleView 人员排班（M4-M13-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:staff:read', 'm4:schedule:manage'],
        roles: ['SYS_OR_MANAGER'],
      }),
    )
    await router.push('/m4/staff/schedules')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载人员与排班列表并展示班次', async () => {
    const queue = createFetchQueue([jsonResponse(staffList()), jsonResponse(scheduleList())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    const table = wrapper.find('[data-testid="sched-table"]').text()
    expect(table).toContain('2026-11-20')
    expect(table).toContain('白班')
    expect(table).toContain('STAFF-ANES-001')
    expect(wrapper.find('[data-testid="sched-create"]').exists()).toBe(true)
  })

  it('新建排班提交人员/日期/班次并按人员科室落库', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(scheduleList([])),
      jsonResponse(scheduleRecord(), 201),
      jsonResponse(scheduleList()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="sched-create"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="sched-date"]').setValue('2026-11-21')
    await wrapper.find('[data-testid="sched-room"]').setValue(ROOM_ID)
    await wrapper.find('[data-testid="sched-submit"]').trigger('click')
    await settle()

    const post = queue.calls[2]
    const body = JSON.parse(String(post.init?.body))
    expect(post.url).toContain('/v1/staff/schedules')
    expect(body.staffId).toBe(STAFF_ID)
    expect(body.scheduleDate).toBe('2026-11-21')
    expect(body.shift).toBe('DAY')
    expect(body.departmentId).toBe(DEPT_ID)
  })

  it('排班冲突 409 时展示冲突明细', async () => {
    const queue = createFetchQueue([
      jsonResponse(staffList()),
      jsonResponse(scheduleList([])),
      conflictResponse(),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="sched-create"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="sched-date"]').setValue('2026-11-20')
    await wrapper.find('[data-testid="sched-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="sched-error"]').text()).toContain('冲突')
    expect(wrapper.find('[data-testid="sched-conflicts"]').text()).toContain('staffId')
    expect(wrapper.find('[data-testid="sched-conflicts"]').text()).toContain('已有排班')
  })
})
