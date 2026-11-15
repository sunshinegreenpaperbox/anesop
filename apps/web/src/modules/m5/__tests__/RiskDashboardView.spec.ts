import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RiskDashboardView from '../views/RiskDashboardView.vue'
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

function riskBody(overrides: Record<string, unknown> = {}) {
  return {
    updatedAt: '2026-11-15T12:00:12+08:00',
    timezone: 'Asia/Shanghai',
    date: '2026-11-15',
    dataDelaySeconds: 12,
    dataQuality: 'OK',
    dataScope: 'DEPT',
    totalCaseCount: 6,
    assessedCaseCount: 4,
    unassessedCaseCount: 2,
    highRiskCaseCount: 2,
    asaClassCounts: [
      { asaClass: 'I', count: 1 },
      { asaClass: 'II', count: 2 },
      { asaClass: 'III', count: 1 },
    ],
    riskLevelCounts: [
      { riskLevel: 'LOW', count: 2 },
      { riskLevel: 'MODERATE', count: 1 },
      { riskLevel: 'HIGH', count: 1 },
    ],
    severeAlertCount: 1,
    overdueCaseCount: 1,
    delayedStartCount: 1,
    overrunCaseCount: 0,
    overdueThresholdMinutes: 0,
    formula: '风险分层 = 非取消台次按 ASA 与风险等级计数',
    pendingConfirmations: ['超时宽限分钟数【待确认】'],
    ...overrides,
  }
}

function resourcesBody(overrides: Record<string, unknown> = {}) {
  return {
    updatedAt: '2026-11-15T12:00:12+08:00',
    timezone: 'Asia/Shanghai',
    date: '2026-11-15',
    dataDelaySeconds: 12,
    dataQuality: 'OK',
    dataScope: 'DEPT',
    roomUtilization: {
      roomCount: 3,
      surgeryMinutes: 630,
      availableMinutes: 1800,
      utilizationRate: 0.35,
    },
    roomStatusCounts: {
      total: 3,
      inSurgery: 1,
      idle: 1,
      cleaning: 1,
      preparing: 0,
      recovery: 0,
      outOfService: 0,
    },
    devices: { total: 4, online: 3, offline: 1, maintenance: 0, offlineTimeoutSeconds: 120 },
    deviceOnlineRate: 0.75,
    staffOnDuty: {
      total: 5,
      byShift: [
        { shift: 'DAY', count: 4 },
        { shift: 'ON_CALL', count: 1 },
      ],
    },
    resourceGaps: [
      {
        code: 'DEVICE_OFFLINE',
        severity: 'WARN',
        count: 1,
        message: '1 台设备离线（超时未上报），请检查采集网关与设备电源',
      },
    ],
    formula: '利用率 = 重叠手术分钟 / (术间数 × 每日可用分钟)',
    pendingConfirmations: ['备班是否计入当班【待确认】'],
    ...overrides,
  }
}

function trendsBody(overrides: Record<string, unknown> = {}) {
  return {
    updatedAt: '2026-11-15T12:00:12+08:00',
    timezone: 'Asia/Shanghai',
    dataScope: 'DEPT',
    windowMinutes: 1440,
    bucketMinutes: 60,
    from: '2026-11-14T12:00:12+08:00',
    to: '2026-11-15T12:00:12+08:00',
    roomCount: 3,
    points: [
      {
        startsAt: '2026-11-15T11:00:00+08:00',
        alertCount: 2,
        criticalAlertCount: 1,
        caseCount: 1,
        surgeryMinutes: 45,
        utilizationRate: 0.25,
      },
    ],
    formula: '告警 = 桶内触发告警数；台次 = 桶内计划开始的非取消台次',
    pendingConfirmations: ['台次趋势归桶口径【待确认】'],
    ...overrides,
  }
}

function mountView() {
  return mount(RiskDashboardView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let index = 0; index < 8; index += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('RiskDashboardView 风险与资源大屏（M5-M18-02）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ permissions: ['m5:dashboard:read'], roles: ['SYS_OR_MANAGER'] }),
    )
    await router.push('/m5/dashboard/risk')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载风险分层/资源/趋势并渲染卡片、分布、缺口与趋势表', async () => {
    const queue = createFetchQueue([
      jsonResponse(riskBody()),
      jsonResponse(resourcesBody()),
      jsonResponse(trendsBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(queue.calls[0]?.url).toContain('/api/v1/dashboard/risk')
    expect(queue.calls[1]?.url).toContain('/api/v1/dashboard/resources')
    expect(queue.calls[2]?.url).toContain('/api/v1/dashboard/trends?hours=24')

    expect(wrapper.find('[data-testid="risk-highRiskCaseCount"]').text()).toBe('2')
    expect(wrapper.find('[data-testid="risk-severeAlertCount"]').text()).toBe('1')
    expect(wrapper.find('[data-testid="risk-overdueCaseCount"]').text()).toBe('1')
    expect(wrapper.find('[data-testid="risk-unassessedCaseCount"]').text()).toBe('2')
    expect(wrapper.find('[data-testid="asa-class-list"]').text()).toContain('ASA III')
    expect(wrapper.find('[data-testid="asa-class-list"]').text()).toContain('1')
    expect(wrapper.find('[data-testid="risk-level-list"]').text()).toContain('高危')
    expect(wrapper.find('[data-testid="resource-utilization"]').text()).toBe('35.0%')
    expect(wrapper.find('[data-testid="resource-device-online-rate"]').text()).toBe('75.0%')
    expect(wrapper.find('[data-testid="resource-staff"]').text()).toBe('5')
    expect(wrapper.text()).toContain('白班 4')
    expect(wrapper.find('[data-testid="resource-room-inSurgery"]').text()).toContain('手术中 1')
    expect(wrapper.find('[data-testid="resource-gaps"]').text()).toContain('设备离线')
    expect(wrapper.find('[data-testid="resource-gaps"]').text()).toContain('1 台设备离线')
    expect(wrapper.find('[data-testid="trend-table"]').text()).toContain('45')
    expect(wrapper.find('[data-testid="trend-table"]').text()).toContain('25.0%')
    expect(wrapper.find('[data-testid="pending-confirmations"]').text()).toContain('待确认')
    expect(wrapper.find('[data-testid="data-scope"]').text()).toContain('科室')
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
    expect(wrapper.find('[data-testid="trend-table"]').exists()).toBe(false)
    expect(queue.calls).toHaveLength(0)
  })

  it('服务端 403 时展示错误态与追踪号，且不渲染风险卡', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-0913'),
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-0913'),
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-0913'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="error-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('无权访问该资源或数据范围不足')
    expect(wrapper.text()).toContain('SYNTH-TRACE-0913')
    expect(wrapper.find('[data-testid="risk-highRiskCaseCount"]').exists()).toBe(false)
  })

  it('空数据展示空态且不渲染风险卡与趋势表', async () => {
    const queue = createFetchQueue([
      jsonResponse(
        riskBody({
          totalCaseCount: 0,
          assessedCaseCount: 0,
          unassessedCaseCount: 0,
          highRiskCaseCount: 0,
          asaClassCounts: [],
          riskLevelCounts: [],
          severeAlertCount: 0,
          overdueCaseCount: 0,
          delayedStartCount: 0,
          overrunCaseCount: 0,
          dataQuality: 'NO_DATA',
          dataDelaySeconds: 0,
        }),
      ),
      jsonResponse(
        resourcesBody({
          roomUtilization: {
            roomCount: 0,
            surgeryMinutes: 0,
            availableMinutes: 0,
            utilizationRate: 0,
          },
          roomStatusCounts: {
            total: 0,
            inSurgery: 0,
            idle: 0,
            cleaning: 0,
            preparing: 0,
            recovery: 0,
            outOfService: 0,
          },
          devices: { total: 0, online: 0, offline: 0, maintenance: 0, offlineTimeoutSeconds: 120 },
          deviceOnlineRate: 0,
          staffOnDuty: { total: 0, byShift: [] },
          resourceGaps: [
            {
              code: 'STAFF_NOT_SCHEDULED',
              severity: 'WARN',
              count: 0,
              message: '今日无当班排班（M4 排班未录入），请核对班组安排',
            },
          ],
          dataQuality: 'NO_DATA',
        }),
      ),
      jsonResponse(trendsBody({ points: [], roomCount: 0 })),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="risk-highRiskCaseCount"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="trend-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="data-quality"]').text()).toBe('暂无数据')
  })

  it('数据延迟超阈值时展示“数据延迟”标识与分秒文案', async () => {
    const queue = createFetchQueue([
      jsonResponse(riskBody({ dataDelaySeconds: 615, dataQuality: 'DEGRADED' })),
      jsonResponse(resourcesBody({ dataDelaySeconds: 615, dataQuality: 'DEGRADED' })),
      jsonResponse(trendsBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="data-quality"]').text()).toBe('数据延迟')
    expect(wrapper.find('[data-testid="last-updated"]').text()).toContain('延迟 10 分 15 秒')
  })

  it('每 10 秒轮询一次并可通过“暂停刷新”停止', async () => {
    const queue = createFetchQueue([
      jsonResponse(riskBody()),
      jsonResponse(resourcesBody()),
      jsonResponse(trendsBody()),
      jsonResponse(riskBody()),
      jsonResponse(resourcesBody()),
      jsonResponse(trendsBody()),
      jsonResponse(riskBody()),
      jsonResponse(resourcesBody()),
      jsonResponse(trendsBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const intervals: Array<{ handler: () => void; delay: number }> = []
    let nextId = 1
    vi.spyOn(window, 'setInterval').mockImplementation((handler: TimerHandler, delay?: number) => {
      intervals.push({ handler: handler as () => void, delay: delay ?? 0 })
      return nextId++ as unknown as number
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')

    const polls = () => intervals.filter((item) => item.delay === 10_000)

    const wrapper = mountView()
    await settle()
    expect(queue.calls).toHaveLength(3)
    expect(polls()).toHaveLength(1)

    polls()[0]?.handler()
    await settle()
    expect(queue.calls).toHaveLength(6)

    await wrapper.find('[data-testid="refresh-toggle"]').trigger('click')
    await settle()
    expect(clearIntervalSpy).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="refresh-toggle"]').text()).toBe('继续刷新')
    expect(polls()).toHaveLength(1)

    await wrapper.find('[data-testid="refresh-toggle"]').trigger('click')
    await settle()
    expect(queue.calls).toHaveLength(9)
    expect(polls()).toHaveLength(2)
    expect(wrapper.find('[data-testid="refresh-toggle"]').text()).toBe('暂停刷新')
  })
})
