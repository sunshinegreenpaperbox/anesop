import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AnalyticsView from '../views/AnalyticsView.vue'
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
  deptId: 'f1000000-0000-4000-8000-000000000002',
  deptName: '麻醉科（合成）',
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
  roomId: 'f2000000-0000-4000-8000-0000000000c5',
  roomNo: 'OR-05',
  roomName: '演示 5 号手术间（合成）',
  deptId: 'f1000000-0000-4000-8000-000000000003',
  deptName: '手术室（合成）',
}

const utilizationReport = {
  from: '2026-12-01',
  to: '2026-12-01',
  timezone: 'Asia/Shanghai',
  formula: '总手术时长 / 可用时长',
  availableMinutesPerRoomPerDay: 600,
  days: 1,
  roomCount: 2,
  summary: {
    totalSurgeryMinutes: 180,
    availableMinutes: 1200,
    utilizationRate: 0.15,
    caseCount: 2,
    roomCount: 2,
    days: 1,
  },
  rooms: [
    {
      roomId: roomOne.roomId,
      roomNo: 'OR-01',
      deptId: roomOne.deptId,
      deptName: roomOne.deptName,
      totalSurgeryMinutes: 120,
      availableMinutes: 600,
      utilizationRate: 0.2,
      caseCount: 1,
    },
    {
      roomId: roomTwo.roomId,
      roomNo: 'OR-05',
      deptId: roomTwo.deptId,
      deptName: roomTwo.deptName,
      totalSurgeryMinutes: 60,
      availableMinutes: 600,
      utilizationRate: 0.1,
      caseCount: 1,
    },
  ],
}

const onTimeReport = {
  from: '2026-12-01',
  to: '2026-12-01',
  timezone: 'Asia/Shanghai',
  thresholdMinutes: 15,
  actualStartSource: 'SCHEDULE_UPDATED_AT' as const,
  summary: {
    firstCaseCount: 2,
    evaluatedCaseCount: 2,
    excludedDays: 0,
    onTimeCount: 1,
    delayedCount: 1,
    onTimeRate: 0.5,
  },
  rooms: [
    {
      roomId: roomOne.roomId,
      roomNo: 'OR-01',
      deptId: roomOne.deptId,
      deptName: roomOne.deptName,
      firstCaseCount: 1,
      evaluatedCaseCount: 1,
      excludedDays: 0,
      onTimeCount: 1,
      delayedCount: 0,
      onTimeRate: 1.0,
    },
    {
      roomId: roomTwo.roomId,
      roomNo: 'OR-05',
      deptId: roomTwo.deptId,
      deptName: roomTwo.deptName,
      firstCaseCount: 1,
      evaluatedCaseCount: 1,
      excludedDays: 0,
      onTimeCount: 0,
      delayedCount: 1,
      onTimeRate: 0.0,
    },
  ],
}

const cancellationReport = {
  from: '2026-12-01',
  to: '2026-12-01',
  timezone: 'Asia/Shanghai',
  summary: { totalCases: 4, cancelledCases: 1, cancellationRate: 0.25 },
  byReason: [{ reasonCode: 'PATIENT_CONDITION', cancelledCases: 1, shareOfCancelled: 1.0 }],
  byRoom: [
    {
      roomId: roomOne.roomId,
      roomNo: 'OR-01',
      deptId: roomOne.deptId,
      deptName: roomOne.deptName,
      totalCases: 3,
      cancelledCases: 1,
      cancellationRate: 0.3333,
    },
    {
      roomId: roomTwo.roomId,
      roomNo: 'OR-05',
      deptId: roomTwo.deptId,
      deptName: roomTwo.deptName,
      totalCases: 1,
      cancelledCases: 0,
      cancellationRate: 0.0,
    },
  ],
}

const emptyUtilization = {
  ...utilizationReport,
  roomCount: 0,
  summary: {
    totalSurgeryMinutes: 0,
    availableMinutes: 0,
    utilizationRate: 0,
    caseCount: 0,
    roomCount: 0,
    days: 1,
  },
  rooms: [],
}
const emptyOnTime = {
  ...onTimeReport,
  summary: {
    firstCaseCount: 0,
    evaluatedCaseCount: 0,
    excludedDays: 0,
    onTimeCount: 0,
    delayedCount: 0,
    onTimeRate: 0,
  },
  rooms: [],
}
const emptyCancellations = {
  ...cancellationReport,
  summary: { totalCases: 0, cancelledCases: 0, cancellationRate: 0 },
  byReason: [],
  byRoom: [],
}

function mountView() {
  return mount(AnalyticsView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
}

function reports() {
  return [
    jsonResponse(utilizationReport),
    jsonResponse(onTimeReport),
    jsonResponse(cancellationReport),
  ]
}

describe('AnalyticsView 排程分析', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ permissions: ['m1:analytics:read'], roles: ['SYS_QC_MANAGER'] }),
    )
    await router.push('/m1/analytics')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('仅请求三指标，科室选项从分析响应派生并按术间展示', async () => {
    const queue = createFetchQueue(reports())
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(queue.calls.map((call) => call.url.split('?')[0])).toEqual([
      '/api/v1/scheduling/analytics/utilization',
      '/api/v1/scheduling/analytics/on-time',
      '/api/v1/scheduling/analytics/cancellations',
    ])
    expect(queue.calls[0]?.url).toContain('from=')
    expect(queue.calls[0]?.url).toContain('to=')
    expect(wrapper.find('[data-testid="utilization-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="on-time-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="cancellations-card"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('15.0%')
    expect(wrapper.text()).toContain('50.0%')
    expect(wrapper.text()).toContain('25.0%')
    expect(wrapper.findAll('[data-testid="utilization-table"] tbody tr')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="on-time-table"] tbody tr')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="cancellation-table"] tbody tr')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="cancellation-reason-table"] tbody tr')).toHaveLength(1)
    expect(wrapper.text()).toContain('患者情况变化（PATIENT_CONDITION）')
  })

  it('SYS_QC_MANAGER 仅有分析权限时科室下拉可用，且不请求 /scheduling/rooms', async () => {
    const queue = createFetchQueue([...reports(), ...reports()])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    expect(queue.calls.every((call) => !call.url.includes('/scheduling/rooms'))).toBe(true)
    expect(wrapper.text()).toContain('麻醉科（合成）')
    expect(wrapper.text()).toContain('手术室（合成）')

    await wrapper.find('[data-testid="query-button"]').trigger('click')
    await flushPromises()
    expect(queue.calls).toHaveLength(6)
    expect(wrapper.text()).toContain('手术室（合成）')
  })

  it('空数据集展示空态而不是除零错误', async () => {
    const queue = createFetchQueue([
      jsonResponse(emptyUtilization),
      jsonResponse(emptyOnTime),
      jsonResponse(emptyCancellations),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="utilization-table"]').exists()).toBe(false)
  })

  it('Q-1：evaluatedCaseCount=0 时准点卡片显示暂无数据而非 0.0%', async () => {
    const onTimeNoActual = {
      ...onTimeReport,
      summary: {
        firstCaseCount: 2,
        evaluatedCaseCount: 0,
        excludedDays: 2,
        onTimeCount: 0,
        delayedCount: 0,
        onTimeRate: 0,
      },
      rooms: [],
    }
    const queue = createFetchQueue([
      jsonResponse(utilizationReport),
      jsonResponse(onTimeNoActual),
      jsonResponse(cancellationReport),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    const metric = wrapper.find('[data-testid="on-time-metric"]')
    expect(metric.exists()).toBe(true)
    expect(metric.text()).toBe('暂无数据')
    expect(wrapper.text()).toContain('排除 2 个术间-日')
    expect(wrapper.text()).toContain('依赖 M3/HIS 回写')
  })

  it('403 时展示错误码与 traceId，不渲染指标表', async () => {
    const queue = createFetchQueue([
      jsonResponse(
        {
          code: 'AUTH_FORBIDDEN',
          message: '无权访问该资源或数据范围不足',
          traceId: 'SYNTH-TRACE-403',
          details: [],
        },
        403,
      ),
      jsonResponse(onTimeReport),
      jsonResponse(cancellationReport),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="error-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('无权访问该资源或数据范围不足')
    expect(wrapper.text()).toContain('SYNTH-TRACE-403')
    expect(wrapper.find('[data-testid="utilization-table"]').exists()).toBe(false)
  })

  it('无 m1:analytics:read 时展示权限态且不发分析请求', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m1:schedule:read'] }))
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="forbidden-alert"]').exists()).toBe(true)
    expect(queue.calls).toHaveLength(0)
  })

  it('导出按钮请求 CSV 并触发 Blob 下载', async () => {
    const createObjectURL = vi.fn(() => 'blob:analytics')
    const revokeObjectURL = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', {
      value: createObjectURL,
      configurable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: revokeObjectURL,
      configurable: true,
    })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    const queue = createFetchQueue([
      ...reports(),
      new Response('metric,from,to\r\nutilization,2026-12-01,2026-12-01\r\n', {
        status: 200,
        headers: { 'Content-Type': 'text/csv;charset=UTF-8' },
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="export-utilization"]').trigger('click')
    await flushPromises()

    expect(queue.calls[3]?.url).toContain('/api/v1/scheduling/analytics/export?')
    expect(queue.calls[3]?.url).toContain('metric=utilization')
    expect(queue.calls[3]?.url).toContain('format=csv')
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledTimes(1)
  })
})
