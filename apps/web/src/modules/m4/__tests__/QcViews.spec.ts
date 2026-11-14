import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import QcIndicatorView from '../views/QcIndicatorView.vue'
import AdverseEventView from '../views/AdverseEventView.vue'
import ChartReviewView from '../views/ChartReviewView.vue'
import { exportQcCsv, gradeOf, scorePercent, validateRange, MAX_RANGE_DAYS } from '../api/qc'
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

const DEPT_ANES = 'f1000000-0000-4000-8000-000000000002'

function indicatorDefs() {
  return {
    items: [
      {
        id: '7c000000-0000-4000-8000-000000000001',
        code: 'QC-SYN-STRUCT-001',
        name: '麻醉记录单完成率',
        category: 'STRUCTURE',
        definition: '已完成记录单台次 / 同期非取消台次 × 100%【待确认】',
        dataSource: 'M3 麻醉记录单 + M2 排程',
        numeratorLabel: '已完成记录单台次',
        denominatorLabel: '非取消台次',
        unit: 'PERCENT',
        thresholdOperator: 'GTE',
        thresholdValue: 95,
        version: '1.0.0',
        effectiveFrom: '2026-01-01',
        effectiveTo: null,
        status: 'ACTIVE',
        provisional: true,
      },
      {
        id: '7c000000-0000-4000-8000-000000000005',
        code: 'QC-SYN-OUT-005',
        name: '不良事件上报率',
        category: 'OUTCOME',
        definition: '上报事件数 / 台次 × 1000‰【待确认】',
        dataSource: 'M4 质控不良事件 + M2 排程',
        numeratorLabel: '上报事件数',
        denominatorLabel: '非取消台次',
        unit: 'PER_1000_CASES',
        thresholdOperator: 'GTE',
        thresholdValue: 3,
        version: '1.0.0',
        effectiveFrom: '2026-01-01',
        effectiveTo: null,
        status: 'ACTIVE',
        provisional: true,
      },
    ],
  }
}

function indicatorResults() {
  return {
    from: '2026-01-01',
    to: '2026-01-31',
    timezone: 'Asia/Shanghai',
    formula: 'value = round(numerator / denominator × 100, 2)',
    items: [
      {
        indicatorId: '7c000000-0000-4000-8000-000000000001',
        code: 'QC-SYN-STRUCT-001',
        name: '麻醉记录单完成率',
        category: 'STRUCTURE',
        indicatorVersion: '1.0.0',
        definition: '已完成记录单台次 / 同期非取消台次 × 100%【待确认】',
        unit: 'PERCENT',
        periodFrom: '2026-01-01',
        periodTo: '2026-01-31',
        deptId: null,
        numerator: 3,
        denominator: 4,
        value: 75,
        thresholdOperator: 'GTE',
        thresholdValue: 95,
        targetMet: false,
        computedAt: '2026-01-31T03:00:00Z',
        provisional: true,
      },
      {
        indicatorId: '7c000000-0000-4000-8000-000000000005',
        code: 'QC-SYN-OUT-005',
        name: '不良事件上报率',
        category: 'OUTCOME',
        indicatorVersion: '1.0.0',
        definition: '上报事件数 / 台次 × 1000‰【待确认】',
        unit: 'PER_1000_CASES',
        periodFrom: '2026-01-01',
        periodTo: '2026-01-31',
        deptId: null,
        numerator: 1,
        denominator: 4,
        value: 250,
        thresholdOperator: 'GTE',
        thresholdValue: 3,
        targetMet: true,
        computedAt: '2026-01-31T03:00:00Z',
        provisional: true,
      },
    ],
  }
}

function adverseEvent(status: string, rcaId: string | null, eventNo: string) {
  return {
    id: `7d000000-0000-4000-8000-00000000000${eventNo.slice(-1)}`,
    eventNo,
    eventType: 'MEDICATION',
    severity: 'LEVEL_II',
    occurredAt: '2026-01-06T10:30:00+08:00',
    deptId: DEPT_ANES,
    deptName: '麻醉科（合成）',
    scheduleId: null,
    description: '术中一过性血压下降（合成，已脱敏）',
    reporterName: 'demo_admin',
    anonymous: false,
    status,
    rcaId,
    version: 2,
    createdAt: '2026-01-06T03:05:00Z',
  }
}

function adverseBody() {
  return {
    items: [
      adverseEvent('REPORTED', null, 'AE-20260106-0000a1'),
      adverseEvent('RECTIFYING', '7e000000-0000-4000-8000-000000000001', 'AE-20260107-0000a2'),
      adverseEvent('CLOSED', '7e000000-0000-4000-8000-000000000002', 'AE-20260108-0000a3'),
    ],
    meta: { page: 1, size: 20, total: 3, totalPages: 1 },
  }
}

function reviewBody() {
  return {
    items: [
      {
        id: '7f000000-0000-4000-8000-000000000001',
        reviewNo: 'CR-20260110-0000a1',
        scheduleId: null,
        recordId: null,
        rectificationId: null,
        deptId: DEPT_ANES,
        deptName: '麻醉科（合成）',
        reviewerName: 'demo_admin',
        reviewedAt: '2026-01-10T09:00:00+08:00',
        items: [
          {
            itemCode: 'COMPLETENESS',
            itemName: '完整性',
            score: 28,
            maxScore: 30,
            defectNote: '缺术后随访（合成）',
          },
        ],
        totalScore: 88,
        maxScore: 100,
        grade: 'C',
        defectCount: 2,
        status: 'OPEN',
        createdAt: '2026-01-10T01:00:00Z',
      },
      {
        id: '7f000000-0000-4000-8000-000000000002',
        reviewNo: 'CR-20260111-0000a2',
        scheduleId: null,
        recordId: null,
        rectificationId: '7a000000-0000-4000-8000-000000000001',
        deptId: DEPT_ANES,
        deptName: '麻醉科（合成）',
        reviewerName: 'demo_admin',
        reviewedAt: '2026-01-11T09:00:00+08:00',
        items: [],
        totalScore: 95,
        maxScore: 100,
        grade: 'A',
        defectCount: 1,
        status: 'RECTIFYING',
        createdAt: '2026-01-11T01:00:00Z',
      },
    ],
    meta: { page: 1, size: 20, total: 2, totalPages: 1 },
  }
}

function mountView(component: typeof QcIndicatorView) {
  return mount(component, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let index = 0; index < 8; index += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

function seedQcUser(permissions = ['m4:qc:read', 'm4:qc:write', 'm4:qc:review', 'm4:qc:export']) {
  seedAuthenticatedUser(
    syntheticUser({
      permissions,
      roles: ['SYS_QC_MANAGER'],
      dataScopes: [{ type: 'ALL', scopeIds: [] }],
    }),
  )
}

describe('M4-M17-01 质控前端', () => {
  beforeEach(async () => {
    resetAppState()
    seedQcUser()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('指标看板加载定义与结果并展示达标/未达标状态', async () => {
    await router.push('/m4/qc/indicators')
    const queue = createFetchQueue([
      jsonResponse(indicatorDefs()),
      jsonResponse(indicatorResults()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView(QcIndicatorView)
    await settle()

    expect(queue.calls[0]?.url).toContain('/api/v1/qc/indicators')
    expect(queue.calls[1]?.url).toContain('/api/v1/qc/indicator-results?')
    expect(queue.calls[1]?.url).toContain('from=')
    expect(wrapper.find('[data-testid="result-table"]').text()).toContain('麻醉记录单完成率')
    expect(wrapper.find('[data-testid="result-table"]').text()).toContain('75.00%')
    expect(wrapper.find('[data-testid="target-QC-SYN-STRUCT-001"]').text()).toContain('未达标')
    expect(wrapper.find('[data-testid="target-QC-SYN-OUT-005"]').text()).toContain('达标')
    expect(wrapper.find('[data-testid="target-met-count"]').text()).toContain('达标 1 / 2')
    expect(wrapper.find('[data-testid="indicator-table"]').text()).toContain('待确认')

    await wrapper.find('[data-testid="query-button"]').trigger('click')
    await settle()
    expect(queue.calls.length).toBe(4)
  })

  it('无 m4:qc:read 时展示权限态且不发请求', async () => {
    seedQcUser(['m4:qc:write'])
    await router.push('/m4/qc/indicators')
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView(QcIndicatorView)
    await settle()

    expect(wrapper.find('[data-testid="forbidden-alert"]').exists()).toBe(true)
    expect(queue.calls).toHaveLength(0)
  })

  it('指标结果为 0 条时展示空态', async () => {
    await router.push('/m4/qc/indicators')
    const queue = createFetchQueue([
      jsonResponse({ items: [] }),
      jsonResponse({ ...indicatorResults(), items: [] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView(QcIndicatorView)
    await settle()

    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(true)
  })

  it('不良事件台账按状态渲染可执行动作（RCA/闭环/已闭环）', async () => {
    await router.push('/m4/qc/adverse-events')
    const queue = createFetchQueue([jsonResponse(adverseBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView(AdverseEventView)
    await settle()

    expect(wrapper.find('[data-testid="adverse-table"]').text()).toContain('AE-20260106-0000a1')
    expect(wrapper.find('[data-testid="rca-AE-20260106-0000a1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="close-AE-20260107-0000a2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="status-AE-20260108-0000a3"]').text()).toContain('已闭环')
    expect(wrapper.find('[data-testid="register-button"]').exists()).toBe(true)
  })

  it('无质控权限时不请求且不渲染表格', async () => {
    seedQcUser(['m4:report:read'])
    await router.push('/m4/qc/adverse-events')
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView(AdverseEventView)
    await settle()

    expect(wrapper.find('[data-testid="forbidden-alert"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="adverse-table"]').exists()).toBe(false)
    expect(queue.calls).toHaveLength(0)
  })

  it('病历抽查列表展示总分/等级/状态与整改动作', async () => {
    await router.push('/m4/qc/chart-reviews')
    const queue = createFetchQueue([jsonResponse(reviewBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView(ChartReviewView)
    await settle()

    const table = wrapper.find('[data-testid="review-table"]').text()
    expect(table).toContain('CR-20260110-0000a1')
    expect(table).toContain('88 / 100')
    expect(table).toContain('88.00%')
    expect(table).toContain('待整改')
    expect(wrapper.find('[data-testid="rectify-CR-20260110-0000a1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="close-CR-20260111-0000a2"]').exists()).toBe(true)
  })

  it('服务端 403 时展示错误态与 traceId', async () => {
    await router.push('/m4/qc/chart-reviews')
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-10003'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView(ChartReviewView)
    await settle()

    expect(wrapper.find('[data-testid="error-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('SYNTH-TRACE-10003')
    expect(wrapper.find('[data-testid="review-table"]').exists()).toBe(false)
  })

  it('区间校验、得分率与导出请求参数符合契约', async () => {
    expect(validateRange('', '2026-01-01')).toContain('完整')
    expect(validateRange('2026-01-31', '2026-01-01')).toContain('结束日期')
    expect(validateRange('2020-01-01', '2026-01-01')).toContain(String(MAX_RANGE_DAYS))
    expect(validateRange('2026-01-01', '2026-01-31')).toBeNull()
    expect(scorePercent(88, 100)).toBe(88)
    expect(gradeOf(95, 100)).toBe('A')
    expect(gradeOf(88, 100)).toBe('C')
    expect(gradeOf(79, 100)).toBe('D')

    const queue = createFetchQueue([jsonResponse('type,eventNo\r\n')])
    configureHttp({ fetchImpl: queue.fetchImpl })
    const blob = await exportQcCsv({
      type: 'adverse',
      range: { from: '2026-01-01', to: '2026-01-31', deptId: DEPT_ANES },
    })
    expect(queue.calls[0]?.url).toContain('/api/v1/qc/export?')
    expect(queue.calls[0]?.url).toContain('type=adverse')
    expect(queue.calls[0]?.url).toContain('format=csv')
    expect(queue.calls[0]?.url).toContain(`deptId=${DEPT_ANES}`)
    expect(blob.type).toContain('text/csv')
    expect(blob.size).toBeGreaterThan(0)
  })
})
