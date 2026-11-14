import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from '../views/DashboardView.vue'
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

const ROOM_01 = 'f2000000-0000-4000-8000-0000000000c1'
const ROOM_02 = 'f2000000-0000-4000-8000-0000000000c2'

function realtimeBody(overrides: Record<string, unknown> = {}) {
  return {
    updatedAt: '2026-11-15T12:00:12+08:00',
    timezone: 'Asia/Shanghai',
    dataDelaySeconds: 12,
    dataQuality: 'OK',
    dataScope: 'ALL',
    roomStatusCounts: {
      total: 2,
      inSurgery: 1,
      idle: 1,
      cleaning: 0,
      preparing: 0,
      recovery: 0,
      outOfService: 0,
    },
    rooms: [
      {
        roomId: ROOM_01,
        roomNo: 'OR-01',
        roomName: '一号手术间（合成）',
        deptId: 'f1000000-0000-4000-8000-000000000002',
        deptName: '麻醉科（合成）',
        status: 'IN_SURGERY',
        caseInProgress: true,
        plannedCaseCount: 2,
      },
      {
        roomId: ROOM_02,
        roomNo: 'OR-02',
        roomName: '二号手术间（合成）',
        deptId: 'f1000000-0000-4000-8000-000000000002',
        deptName: '麻醉科（合成）',
        status: 'IDLE',
        caseInProgress: false,
        plannedCaseCount: 0,
      },
    ],
    caseProgress: {
      totalCount: 3,
      activeCount: 1,
      statusCounts: [
        { status: 'IN_PROGRESS', count: 1 },
        { status: 'PLANNED', count: 1 },
        { status: 'COMPLETED', count: 1 },
      ],
    },
    alerts: {
      unacknowledgedCount: 2,
      acknowledgedCount: 1,
      ignoredCount: 0,
      criticalCount: 1,
      warnCount: 1,
      infoCount: 0,
      latestTriggeredAt: '2026-11-15T11:58:40+08:00',
    },
    devices: { total: 4, online: 3, offline: 1, maintenance: 0, offlineTimeoutSeconds: 120 },
    ...overrides,
  }
}

function kpiBody(overrides: Record<string, unknown> = {}) {
  return {
    date: '2026-11-15',
    timezone: 'Asia/Shanghai',
    updatedAt: '2026-11-15T12:00:12+08:00',
    dataDelaySeconds: 12,
    dataQuality: 'OK',
    dataScope: 'ALL',
    caseCount: 3,
    totalCaseCount: 4,
    startedCount: 2,
    completedCount: 1,
    cancelledCount: 1,
    onTimeEvaluatedCount: 2,
    onTimeCount: 1,
    onTimeRate: 0.5,
    onTimeThresholdMinutes: 15,
    cancellationRate: 0.25,
    utilizationRate: 0.35,
    totalSurgeryMinutes: 630,
    availableMinutes: 1800,
    roomCount: 3,
    alertCount: 3,
    criticalAlertCount: 1,
    averageAnesthesiaMinutes: 107.5,
    recordedCaseCount: 2,
    unrecordedCaseCount: 1,
    formula: '台次 = 当日非取消台次；准点率 = 准点首台 / 已评估首台',
    pendingConfirmations: ['利用率计划口径【待确认】'],
    ...overrides,
  }
}

function mountView() {
  return mount(DashboardView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let index = 0; index < 8; index += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('DashboardView 实时态势与 KPI 看板（M5-M18-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ permissions: ['m5:dashboard:read'], roles: ['SYS_OR_MANAGER'] }),
    )
    await router.push('/m5/dashboard')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载实时态势与当日 KPI 并渲染术间/台次/告警/设备/指标', async () => {
    const queue = createFetchQueue([jsonResponse(realtimeBody()), jsonResponse(kpiBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(queue.calls[0]?.url).toContain('/api/v1/dashboard/realtime')
    expect(queue.calls[1]?.url).toContain('/api/v1/dashboard/kpi?date=')
    expect(queue.calls[1]?.url).toMatch(/date=\d{4}-\d{2}-\d{2}/)

    expect(wrapper.find('[data-testid="room-status-inSurgery"]').text()).toBe('1')
    expect(wrapper.find('[data-testid="room-status-idle"]').text()).toBe('1')
    expect(wrapper.find('[data-testid="room-status-outOfService"]').text()).toBe('0')
    expect(wrapper.find('[data-testid="case-progress"]').text()).toContain('进行中台次')
    expect(wrapper.find('[data-testid="case-progress-detail"]').text()).toContain('进行中 1')
    expect(wrapper.find('[data-testid="alert-summary"]').text()).toContain('未确认告警')
    expect(wrapper.find('[data-testid="alert-summary"]').text()).toContain('2')
    expect(wrapper.find('[data-testid="device-summary"]').text()).toContain('3 /')
    expect(wrapper.find('[data-testid="kpi-caseCount"]').text()).toBe('3')
    expect(wrapper.find('[data-testid="kpi-onTimeRate"]').text()).toBe('50.0%')
    expect(wrapper.find('[data-testid="kpi-cancellationRate"]').text()).toBe('25.0%')
    expect(wrapper.find('[data-testid="kpi-averageAnesthesiaMinutes"]').text()).toBe('107.5 分钟')
    expect(wrapper.find('[data-testid="room-list"]').text()).toContain('OR-01')
    expect(wrapper.find('[data-testid="room-list"]').text()).toContain('手术中')
    expect(wrapper.find('[data-testid="pending-confirmations"]').text()).toContain('待确认')
    expect(wrapper.find('[data-testid="data-scope"]').text()).toContain('全院')
    expect(wrapper.find('[data-testid="data-quality"]').text()).toBe('数据正常')
    expect(wrapper.find('[data-testid="last-updated"]').text()).toContain('延迟 12 秒')
  })

  it('无 m5:dashboard:read 时展示权限态且不发请求', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m4:report:read'] }))
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="forbidden-alert"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="kpi-grid"]').exists()).toBe(false)
    expect(queue.calls).toHaveLength(0)
  })

  it('服务端 403 时展示错误态与追踪号且不渲染指标', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-0913'),
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-0913'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="error-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('无权访问该资源或数据范围不足')
    expect(wrapper.text()).toContain('SYNTH-TRACE-0913')
    expect(wrapper.find('[data-testid="kpi-grid"]').exists()).toBe(false)
  })

  it('空数据展示空态且不渲染指标网格', async () => {
    const queue = createFetchQueue([
      jsonResponse(
        realtimeBody({
          rooms: [],
          roomStatusCounts: {
            total: 0,
            inSurgery: 0,
            idle: 0,
            cleaning: 0,
            preparing: 0,
            recovery: 0,
            outOfService: 0,
          },
          caseProgress: { totalCount: 0, activeCount: 0, statusCounts: [] },
          alerts: {
            unacknowledgedCount: 0,
            acknowledgedCount: 0,
            ignoredCount: 0,
            criticalCount: 0,
            warnCount: 0,
            infoCount: 0,
          },
          devices: { total: 0, online: 0, offline: 0, maintenance: 0, offlineTimeoutSeconds: 120 },
          dataQuality: 'NO_DATA',
          dataDelaySeconds: 0,
        }),
      ),
      jsonResponse(
        kpiBody({
          caseCount: 0,
          totalCaseCount: 0,
          startedCount: 0,
          completedCount: 0,
          cancelledCount: 0,
          onTimeEvaluatedCount: 0,
          onTimeCount: 0,
          onTimeRate: 0,
          cancellationRate: 0,
          utilizationRate: 0,
          totalSurgeryMinutes: 0,
          alertCount: 0,
          criticalAlertCount: 0,
          averageAnesthesiaMinutes: 0,
          recordedCaseCount: 0,
          unrecordedCaseCount: 0,
          dataQuality: 'NO_DATA',
        }),
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="kpi-grid"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="data-quality"]').text()).toBe('暂无数据')
  })

  it('每 10 秒轮询一次并可通过“暂停刷新”停止', async () => {
    const queue = createFetchQueue([
      jsonResponse(realtimeBody()),
      jsonResponse(kpiBody()),
      jsonResponse(realtimeBody()),
      jsonResponse(kpiBody()),
      jsonResponse(realtimeBody()),
      jsonResponse(kpiBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const intervals: Array<{ handler: () => void; delay: number }> = []
    let nextId = 1
    vi.spyOn(window, 'setInterval').mockImplementation((handler: TimerHandler, delay?: number) => {
      intervals.push({ handler: handler as () => void, delay: delay ?? 0 })
      return nextId++ as unknown as number
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')

    // 只统计看板的 10s 轮询（其他库也可能注册定时器，不能按 setInterval 总数断言）。
    const polls = () => intervals.filter((item) => item.delay === 10_000)

    const wrapper = mountView()
    await settle()
    expect(queue.calls).toHaveLength(2)
    expect(polls()).toHaveLength(1)

    // 触发一次轮询：两个端点各再请求一次。
    polls()[0]?.handler()
    await settle()
    expect(queue.calls).toHaveLength(4)

    // 暂停：清除定时器，按钮文案变为“继续刷新”。
    await wrapper.find('[data-testid="refresh-toggle"]').trigger('click')
    await settle()
    expect(clearIntervalSpy).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="refresh-toggle"]').text()).toBe('继续刷新')
    expect(polls()).toHaveLength(1)

    // 继续：立刻补一次数据并重新注册 10s 定时器。
    await wrapper.find('[data-testid="refresh-toggle"]').trigger('click')
    await settle()
    expect(queue.calls).toHaveLength(6)
    expect(polls()).toHaveLength(2)
    expect(wrapper.find('[data-testid="refresh-toggle"]').text()).toBe('暂停刷新')
  })

  it('数据延迟超过阈值时展示“数据延迟”标识与分秒文案', async () => {
    const queue = createFetchQueue([
      jsonResponse(realtimeBody({ dataDelaySeconds: 615, dataQuality: 'DEGRADED' })),
      jsonResponse(kpiBody({ dataDelaySeconds: 615, dataQuality: 'DEGRADED' })),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="data-quality"]').text()).toBe('数据延迟')
    expect(wrapper.find('[data-testid="last-updated"]').text()).toContain('延迟 10 分 15 秒')
  })
})
