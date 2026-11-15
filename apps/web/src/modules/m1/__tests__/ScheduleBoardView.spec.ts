import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ScheduleBoardView from '../views/ScheduleBoardView.vue'
import { formatLocalDate } from '../utils/date'
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

const roomOne = {
  roomId: 'f2000000-0000-4000-8000-0000000000c1',
  roomNo: 'OR-01',
  roomName: '演示 1 号手术间（合成）',
  roomType: 'GENERAL' as const,
  siteId: 'f1000000-0000-4000-8000-000000000001',
  status: 'IDLE' as const,
  isolationRequired: false,
  equipmentTags: [],
  createdAt: '2026-11-13T08:00:00+08:00',
  createdBy: null,
  updatedAt: '2026-11-13T08:00:00+08:00',
  updatedBy: null,
  version: 1,
}

const roomTwo = {
  ...roomOne,
  roomId: 'f2000000-0000-4000-8000-0000000000c2',
  roomNo: 'OR-02',
  roomName: '演示 2 号杂交手术间（合成）',
  roomType: 'HYBRID' as const,
}

const schedule = {
  scheduleId: '80000000-0000-4000-8000-000000000001',
  applicationId: '60000000-0000-4000-8000-000000000001',
  patientId: '50000000-0000-4000-8000-000000000001',
  patientNo: 'SYNTH-HIS-P-0001',
  patientName: '演示患者',
  roomId: roomOne.roomId,
  roomNo: 'OR-01',
  sequenceNo: 1,
  plannedStart: '2026-12-02T08:00:00+08:00',
  plannedEnd: '2026-12-02T10:00:00+08:00',
  actualStart: null,
  actualEnd: null,
  status: 'PLANNED' as const,
  priority: 'ELECTIVE' as const,
  changeReason: null,
  createdAt: '2026-11-13T08:00:00+08:00',
  createdBy: 'e0000000-0000-4000-8000-000000000001',
  updatedAt: '2026-11-13T08:00:00+08:00',
  updatedBy: 'e0000000-0000-4000-8000-000000000001',
  version: 1,
}

const roomList = { items: [roomOne, roomTwo] }
const schedulePage = {
  items: [schedule],
  meta: { page: 1, size: 50, total: 1, totalPages: 1 },
}

const conflict = {
  conflictType: 'ROOM_OVERLAP',
  severity: 'BLOCKING' as const,
  message: '与术间 OR-01 第 1 台时段重叠',
  conflictingScheduleId: schedule.scheduleId,
}

function mountBoard() {
  return mount(ScheduleBoardView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
}

const anesthesiaRecordId = '12000000-0000-4000-8000-000000000001'

/** 打开台次卡片的「流程入口」下拉（Element Plus 下拉内容 teleport 到 body）。 */
async function openFlowMenu(wrapper: ReturnType<typeof mountBoard>): Promise<void> {
  await wrapper.find('[data-testid="flow-menu-trigger"]').trigger('click')
  await flushPromises()
}

function flowItem(testId: string): HTMLElement | null {
  return document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
}

async function clickFlowItem(testId: string): Promise<void> {
  const item = flowItem(testId)
  expect(item).not.toBeNull()
  item?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

describe('ScheduleBoardView 排程工作台', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read', 'm1:schedule:write'] }))
    await router.push('/m1/schedule')
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    resetAppState()
  })

  it('按日期加载术间列与台次，展示患者与状态', async () => {
    const queue = createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/scheduling/rooms')
    expect(queue.calls[1]?.url).toBe(`/api/v1/scheduling/schedules?date=${formatLocalDate()}`)
    expect(wrapper.findAll('[data-testid="room-column"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('OR-01')
    expect(wrapper.text()).toContain('杂交')
    expect(wrapper.text()).toContain('演示患者')
    expect(wrapper.text()).toContain('SYNTH-HIS-P-0001')
    expect(wrapper.text()).toContain('已排程')
    expect(wrapper.find('[data-testid="adjust-button"]').exists()).toBe(true)
  })

  it('冲突预检返回冲突时展示结构化提示且不提交', async () => {
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse({ hasConflict: true, conflicts: [conflict] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="create-schedule-button"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="precheck-button"]').trigger('click')
    await flushPromises()

    expect(queue.calls[2]?.url).toBe('/api/v1/scheduling/schedules/conflicts')
    expect(wrapper.find('[data-testid="conflict-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('ROOM_OVERLAP')
    expect(wrapper.text()).toContain('与术间 OR-01 第 1 台时段重叠')
    expect(queue.calls).toHaveLength(3)
  })

  it('创建返回 409 时展示 conflicts 且保留原列表', async () => {
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse(
        {
          code: 'SCHEDULE_CONFLICT',
          message: '排程冲突，操作未生效',
          traceId: 'trace-conflict',
          details: [{ field: 'ROOM_OVERLAP', reason: '与术间 OR-01 第 1 台时段重叠' }],
          conflicts: [conflict],
        },
        409,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="create-schedule-button"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="application-input"]').setValue('SYNTH-APP-0001')
    await wrapper.find('[data-testid="submit-button"]').trigger('click')
    await flushPromises()

    expect(queue.calls[2]?.init?.method).toBe('POST')
    expect(String(queue.calls[2]?.init?.body)).toContain('SYNTH-APP-0001')
    expect(wrapper.find('[data-testid="conflict-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('ROOM_OVERLAP')
    expect(wrapper.text()).toContain('冲突台次：')
    expect(wrapper.text()).toContain(schedule.scheduleId)
    expect(wrapper.findAll('[data-testid="schedule-row"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="room-column"]')).toHaveLength(2)
  })

  it('冲突解决后提交成功并刷新列表', async () => {
    const resolved = { ...schedule, scheduleId: '80000000-0000-4000-8000-000000000002' }
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse(resolved, 201),
      jsonResponse(roomList),
      jsonResponse({
        items: [schedule, resolved],
        meta: { page: 1, size: 50, total: 2, totalPages: 1 },
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="create-schedule-button"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="application-input"]').setValue('SYNTH-APP-0002')
    await wrapper.find('[data-testid="submit-button"]').trigger('click')
    await flushPromises()

    expect(queue.calls[2]?.init?.method).toBe('POST')
    expect(queue.calls).toHaveLength(5)
    expect(wrapper.findAll('[data-testid="schedule-row"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="conflict-alert"]').exists()).toBe(false)
  })

  it('本地日期按本地时区计算（不使用 UTC 的 toISOString）', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 11, 2, 0, 30))
    expect(formatLocalDate()).toBe('2026-12-02')
    vi.setSystemTime(new Date(2026, 0, 1, 0, 30))
    expect(formatLocalDate()).toBe('2026-01-01')
  })

  it('仅 PLANNED/PUBLISHED 显示调整与取消按钮', async () => {
    const published = {
      ...schedule,
      scheduleId: '80000000-0000-4000-8000-000000000002',
      status: 'PUBLISHED' as const,
    }
    const completed = {
      ...schedule,
      scheduleId: '80000000-0000-4000-8000-000000000003',
      status: 'COMPLETED' as const,
    }
    const cancelled = {
      ...schedule,
      scheduleId: '80000000-0000-4000-8000-000000000004',
      status: 'CANCELLED' as const,
    }
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse({
        items: [schedule, published, completed, cancelled],
        meta: { page: 1, size: 50, total: 4, totalPages: 1 },
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()

    expect(wrapper.findAll('[data-testid="schedule-row"]')).toHaveLength(4)
    expect(wrapper.findAll('[data-testid="adjust-button"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="cancel-button"]')).toHaveLength(2)
  })

  it('409 缺少 conflicts[] 时回退 details 展示', async () => {
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse(
        {
          code: 'SCHEDULE_CONFLICT',
          message: '排程冲突，操作未生效',
          traceId: 'trace-fallback',
          details: [{ field: 'VERSION_CONFLICT', reason: '台次版本已过期，请刷新后重试' }],
        },
        409,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="create-schedule-button"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="application-input"]').setValue('SYNTH-APP-0003')
    await wrapper.find('[data-testid="submit-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="conflict-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('VERSION_CONFLICT')
    expect(wrapper.text()).toContain('台次版本已过期')
    expect(wrapper.text()).not.toContain('冲突台次：')
  })

  it('只读权限隐藏新建/调整/取消按钮', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read'] }))
    await router.push('/m1/schedule')
    const queue = createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()

    expect(wrapper.find('[data-testid="create-schedule-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="adjust-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cancel-button"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('SYNTH-HIS-P-0001')
  })

  it('停台对话框提交原因码与版本并刷新列表', async () => {
    const stopped = { ...schedule, status: 'CANCELLED' as const, version: 2 }
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse(stopped),
      jsonResponse(roomList),
      jsonResponse({
        items: [stopped],
        meta: { page: 1, size: 50, total: 1, totalPages: 1 },
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="cancel-button"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="stop-reason-select"]').exists()).toBe(true)
    await wrapper.find('[data-testid="stop-reason-input"]').setValue('演示停台说明（合成数据）')
    await wrapper.find('[data-testid="stop-submit"]').trigger('click')
    await flushPromises()

    const stopCall = queue.calls[2]
    expect(stopCall?.url).toBe(`/api/v1/scheduling/schedules/${schedule.scheduleId}/stop`)
    expect(stopCall?.init?.method).toBe('POST')
    const body = JSON.parse(String(stopCall?.init?.body)) as Record<string, unknown>
    expect(body.reasonCode).toBe('PATIENT_CONDITION')
    expect(body.reason).toBe('演示停台说明（合成数据）')
    expect(body.version).toBe(1)
    expect(wrapper.text()).toContain('已取消')
  })

  it('术中停台无审批权限时提交返回 403 并保留台次', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read', 'm1:schedule:write'] }))
    await router.push('/m1/schedule')
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse({ ...schedulePage, items: [{ ...schedule, status: 'IN_PROGRESS' }] }),
      jsonResponse(
        {
          code: 'AUTH_FORBIDDEN',
          message: '无权访问该资源或数据范围不足',
          traceId: 't403',
          details: [],
        },
        403,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    // 无 approve 权限：术中停台按钮可见（服务端双保险拒绝），点击后真实触发 403。
    expect(wrapper.find('[data-testid="adjust-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cancel-button"]').exists()).toBe(false)
    const stopButton = wrapper.find('[data-testid="stop-in-progress-button"]')
    expect(stopButton.exists()).toBe(true)
    await stopButton.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="stop-approval-alert"]').text()).toContain(
      'm1:schedule:approve',
    )
    await wrapper.find('[data-testid="stop-submit"]').trigger('click')
    await flushPromises()

    const stopCall = queue.calls[2]
    expect(stopCall?.url).toBe(`/api/v1/scheduling/schedules/${schedule.scheduleId}/stop`)
    expect(wrapper.text()).toContain('AUTH_FORBIDDEN')
    expect(wrapper.findAll('[data-testid="schedule-row"]')).toHaveLength(1)
  })

  it('改台未选择原因码时不提交（Q-1 必填）', async () => {
    const queue = createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="adjust-button"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="modify-reason-select"]').exists()).toBe(true)
    await wrapper.find('[data-testid="adjust-submit"]').trigger('click')
    await flushPromises()

    // 仅初始 rooms/schedules 两次请求，未提交 PUT。
    expect(queue.calls).toHaveLength(2)
  })

  it('急诊加台无冲突时返回 201 并带审批记录', async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m1:schedule:read', 'm1:schedule:write', 'm1:schedule:approve'],
      }),
    )
    await router.push('/m1/schedule')
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse(
        {
          accepted: true,
          scheduleId: '80000000-0000-4000-8000-000000000009',
          approvalRequired: true,
          conflicts: [],
          approval: {
            changeId: '90000000-0000-4000-8000-000000000009',
            changeType: 'EMERGENCY',
            status: 'APPROVED',
            requestedAt: '2026-12-02T12:55:00+08:00',
            approvedAt: '2026-12-02T12:55:00+08:00',
            preemptScheduleId: null,
          },
        },
        201,
      ),
      jsonResponse(roomList),
      jsonResponse(schedulePage),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="emergency-button"]').trigger('click')
    await flushPromises()
    await wrapper
      .find('[data-testid="emergency-application-input"]')
      .setValue('60000000-0000-4000-8000-000000000009')
    await wrapper.find('[data-testid="emergency-submit"]').trigger('click')
    await flushPromises()

    const call = queue.calls[2]
    expect(call?.url).toBe('/api/v1/scheduling/emergency-cases')
    expect(call?.init?.method).toBe('POST')
    const body = JSON.parse(String(call?.init?.body)) as Record<string, unknown>
    expect(body.reasonCode).toBe('EMERGENCY_GREEN_CHANNEL')
    expect(body.expectedStart).toBeTruthy()
    expect(queue.calls).toHaveLength(5)
  })

  it('急诊冲突提示可抢占择期台次，抢占重试成功', async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m1:schedule:read', 'm1:schedule:write', 'm1:schedule:approve'],
      }),
    )
    await router.push('/m1/schedule')
    const conflictBody = {
      code: 'SCHEDULE_CONFLICT',
      message: '排程冲突，操作未生效',
      traceId: 'trace-emergency-conflict',
      details: [{ field: 'ROOM_OVERLAP', reason: '与术间 OR-01 第 1 台时段重叠' }],
      conflicts: [conflict],
    }
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse(conflictBody, 409),
      jsonResponse(
        {
          accepted: true,
          scheduleId: '80000000-0000-4000-8000-000000000010',
          approvalRequired: true,
          conflicts: [],
          approval: {
            changeId: '90000000-0000-4000-8000-000000000010',
            changeType: 'EMERGENCY',
            status: 'APPROVED',
            requestedAt: '2026-12-02T12:55:00+08:00',
            approvedAt: '2026-12-02T12:55:00+08:00',
            preemptScheduleId: schedule.scheduleId,
          },
        },
        201,
      ),
      jsonResponse(roomList),
      jsonResponse(schedulePage),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="emergency-button"]').trigger('click')
    await flushPromises()
    await wrapper
      .find('[data-testid="emergency-application-input"]')
      .setValue('60000000-0000-4000-8000-000000000010')
    await wrapper.find('[data-testid="emergency-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="conflict-alert"]').exists()).toBe(true)
    const preemptButton = wrapper.find('[data-testid="emergency-preempt-button"]')
    expect(preemptButton.exists()).toBe(true)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    await preemptButton.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(String(confirmSpy.mock.calls[0]?.[0])).toContain(schedule.scheduleId)

    const retryBody = JSON.parse(String(queue.calls[3]?.init?.body)) as Record<string, unknown>
    expect(retryBody.preemptScheduleId).toBe(schedule.scheduleId)
    expect(queue.calls).toHaveLength(6)
  })

  it('变更历史抽屉展示停台/急诊审批与抢占关系', async () => {
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse({
        items: [
          {
            changeId: '90000000-0000-4000-8000-000000000001',
            scheduleId: schedule.scheduleId,
            applicationId: schedule.applicationId,
            changeType: 'STOP',
            reasonCode: 'EMERGENCY_PREEMPT',
            reason: '急诊抢占停止',
            status: 'APPROVED',
            preemptScheduleId: '80000000-0000-4000-8000-000000000099',
            requestedBy: '1000000000000000001',
            requestedAt: '2026-12-02T09:10:00+08:00',
            approvedBy: '1000000000000000001',
            approvedAt: '2026-12-02T09:10:00+08:00',
            version: 1,
          },
        ],
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()
    await wrapper.find('[data-testid="history-button"]').trigger('click')
    await flushPromises()

    expect(queue.calls[2]?.url).toBe(`/api/v1/scheduling/schedules/${schedule.scheduleId}/changes`)
    expect(wrapper.findAll('[data-testid="history-item"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('停台')
    expect(wrapper.text()).toContain('已批准')
    expect(wrapper.text()).toContain('EMERGENCY_PREEMPT')
    expect(wrapper.text()).toContain('80000000-0000-4000-8000-000000000099')
  })

  it('无 m2:record:read 时不显示麻醉记录入口', async () => {
    const queue = createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountBoard()
    await flushPromises()

    expect(wrapper.find('[data-testid="record-link"]').exists()).toBe(false)
  })

  it('有 m2:record:read 时点击麻醉记录入口跳转对应台次记录单', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read', 'm2:record:read'] }))
    const queue = createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)])
    configureHttp({ fetchImpl: queue.fetchImpl })
    const pushSpy = vi.spyOn(router, 'push').mockResolvedValue(undefined as never)

    const wrapper = mountBoard()
    await flushPromises()

    const link = wrapper.find('[data-testid="record-link"]')
    expect(link.exists()).toBe(true)
    await link.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith(`/m2/records/${schedule.scheduleId}`)
  })

  it('有权限时流程入口显示申请维度环节并跳转 applicationId 路由', async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m1:schedule:read', 'm2:preop:read', 'm2:consent:read', 'm2:threeway:read'],
      }),
    )
    await router.push('/m1/schedule')
    const queue = createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)])
    configureHttp({ fetchImpl: queue.fetchImpl })
    const pushSpy = vi.spyOn(router, 'push').mockResolvedValue(undefined as never)

    const wrapper = mountBoard()
    await flushPromises()
    await openFlowMenu(wrapper)

    expect(flowItem('flow-preop')).not.toBeNull()
    expect(flowItem('flow-consent')).not.toBeNull()
    expect(flowItem('flow-threeway')).not.toBeNull()
    // 缺 m2:record:read / m2:pacu:read / m2:followup:read：对应项不渲染。
    expect(flowItem('flow-record')).toBeNull()
    expect(flowItem('flow-pacu')).toBeNull()
    expect(flowItem('flow-followup')).toBeNull()

    await clickFlowItem('flow-preop')
    expect(pushSpy).toHaveBeenLastCalledWith(`/m2/preop/${schedule.applicationId}`)

    await openFlowMenu(wrapper)
    await clickFlowItem('flow-consent')
    expect(pushSpy).toHaveBeenLastCalledWith(`/m2/consent/${schedule.applicationId}`)

    await openFlowMenu(wrapper)
    await clickFlowItem('flow-threeway')
    expect(pushSpy).toHaveBeenLastCalledWith(`/m2/three-way/${schedule.applicationId}`)

    wrapper.unmount()
  })

  it('无流程读权限时整组入口不渲染，仅渲染有权限的环节项', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read'] }))
    await router.push('/m1/schedule')
    configureHttp({
      fetchImpl: createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)]).fetchImpl,
    })

    const wrapper = mountBoard()
    await flushPromises()
    expect(wrapper.find('[data-testid="flow-menu"]').exists()).toBe(false)
    expect(flowItem('flow-preop')).toBeNull()
    expect(flowItem('flow-pacu')).toBeNull()
    wrapper.unmount()

    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read', 'm2:preop:read'] }))
    await router.push('/m1/schedule')
    configureHttp({
      fetchImpl: createFetchQueue([jsonResponse(roomList), jsonResponse(schedulePage)]).fetchImpl,
    })

    const partialWrapper = mountBoard()
    await flushPromises()
    await openFlowMenu(partialWrapper)

    expect(flowItem('flow-preop')).not.toBeNull()
    expect(flowItem('flow-consent')).toBeNull()
    expect(flowItem('flow-threeway')).toBeNull()
    expect(flowItem('flow-record')).toBeNull()
    expect(flowItem('flow-pacu')).toBeNull()
    expect(flowItem('flow-followup')).toBeNull()

    partialWrapper.unmount()
  })

  it('复苏入口先按台次查询记录单，再跳转记录单维度路由', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read', 'm2:pacu:read'] }))
    await router.push('/m1/schedule')
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse({ items: [{ recordId: anesthesiaRecordId, scheduleId: schedule.scheduleId }] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })
    const pushSpy = vi.spyOn(router, 'push').mockResolvedValue(undefined as never)

    const wrapper = mountBoard()
    await flushPromises()
    await openFlowMenu(wrapper)
    expect(flowItem('flow-pacu')).not.toBeNull()
    expect(flowItem('flow-followup')).toBeNull()

    await clickFlowItem('flow-pacu')

    expect(queue.calls[2]?.url).toBe(`/api/v1/anesthesia/records?scheduleId=${schedule.scheduleId}`)
    expect(pushSpy).toHaveBeenLastCalledWith(`/m2/pacu/${anesthesiaRecordId}`)
    wrapper.unmount()
  })

  it('无麻醉记录单时随访入口提示先创建记录单且不跳转', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read', 'm2:followup:read'] }))
    await router.push('/m1/schedule')
    const queue = createFetchQueue([
      jsonResponse(roomList),
      jsonResponse(schedulePage),
      jsonResponse({ items: [] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })
    const pushSpy = vi.spyOn(router, 'push').mockResolvedValue(undefined as never)
    const warningSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)

    const wrapper = mountBoard()
    await flushPromises()
    await openFlowMenu(wrapper)
    expect(flowItem('flow-followup')).not.toBeNull()

    await clickFlowItem('flow-followup')

    expect(queue.calls[2]?.url).toBe(`/api/v1/anesthesia/records?scheduleId=${schedule.scheduleId}`)
    expect(warningSpy).toHaveBeenCalledWith('请先创建麻醉记录单')
    expect(pushSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
