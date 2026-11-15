import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReportView from '../views/ReportView.vue'
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
const DEPT_OR = 'f1000000-0000-4000-8000-000000000003'

function workloadBody(overrides: Record<string, unknown> = {}) {
  return {
    from: '2026-12-01',
    to: '2026-12-31',
    timezone: 'Asia/Shanghai',
    dimension: 'DEPT',
    formula: '台次 = 统计边界内非取消台次；麻醉时长 = floor((ended_at - started_at) / 60s)',
    weightFormula: '加权台次 = Σ 台次权重（默认 1.0）',
    caseCount: 3,
    anesthesiaMinutes: 215,
    weightedCaseCount: 3.5,
    unrecordedCaseCount: 1,
    buckets: [
      {
        key: DEPT_ANES,
        label: '麻醉科（合成）',
        caseCount: 2,
        anesthesiaMinutes: 125,
        weightedCaseCount: 2.5,
        unrecordedCaseCount: 1,
        surgeryDistribution: [
          {
            surgeryCode: 'SYNTH-ART-001',
            surgeryName: '演示关节置换术（合成）',
            caseCount: 1,
            anesthesiaMinutes: 125,
          },
        ],
      },
      {
        key: DEPT_OR,
        label: '手术室（合成）',
        caseCount: 1,
        anesthesiaMinutes: 90,
        weightedCaseCount: 1,
        unrecordedCaseCount: 0,
        surgeryDistribution: [],
      },
    ],
    ...overrides,
  }
}

function multiBody(overrides: Record<string, unknown> = {}) {
  return {
    from: '2026-12-01',
    to: '2026-12-31',
    timezone: 'Asia/Shanghai',
    dimensions: ['DEPT', 'SURGERY'],
    metrics: ['caseCount', 'anesthesiaMinutes'],
    formula: '台次 / 麻醉时长',
    weightFormula: '加权台次 = Σ 台次权重（默认 1.0）',
    rows: [
      {
        deptId: DEPT_ANES,
        deptName: '麻醉科（合成）',
        surgeryCode: 'SYNTH-ART-001',
        surgeryName: '演示关节置换术（合成）',
        caseCount: 1,
        anesthesiaMinutes: 125,
        weightedCaseCount: 1.5,
        unrecordedCaseCount: 0,
      },
      {
        deptId: DEPT_ANES,
        deptName: '麻醉科（合成）',
        surgeryCode: 'SYNTH-GAS-002',
        surgeryName: '演示胃肠手术（合成）',
        caseCount: 1,
        anesthesiaMinutes: 0,
        weightedCaseCount: 1,
        unrecordedCaseCount: 1,
      },
    ],
    totals: {
      caseCount: 3,
      anesthesiaMinutes: 215,
      weightedCaseCount: 3.5,
      unrecordedCaseCount: 1,
      groupCount: 3,
    },
    page: 1,
    size: 20,
    total: 3,
    totalPages: 2,
    ...overrides,
  }
}

function emptyMultiBody() {
  return multiBody({
    rows: [],
    totals: {
      caseCount: 0,
      anesthesiaMinutes: 0,
      weightedCaseCount: 0,
      unrecordedCaseCount: 0,
      groupCount: 0,
    },
    total: 0,
    totalPages: 0,
  })
}

function mountView() {
  return mount(ReportView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let index = 0; index < 8; index += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

describe('ReportView 工作量与多维报表（M4-M16-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:report:read', 'm4:report:export'],
        roles: ['SYS_QC_MANAGER'],
      }),
    )
    await router.push('/m4/reports')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载工作量与多维报表并展示台次/时长/加权合计', async () => {
    const queue = createFetchQueue([jsonResponse(workloadBody()), jsonResponse(multiBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(queue.calls[0]?.url).toContain('/api/v1/reports/workload?')
    expect(queue.calls[0]?.url).toContain('dimension=DEPT')
    expect(queue.calls[1]?.url).toContain('/api/v1/reports/multi?')
    expect(queue.calls[1]?.url).toContain('dimensions=DEPT%2CSURGERY')

    expect(wrapper.find('[data-testid="workload-table"]').text()).toContain('麻醉科（合成）')
    expect(wrapper.find('[data-testid="workload-table"]').text()).toContain(
      '演示关节置换术（合成）',
    )
    expect(wrapper.find('[data-testid="multi-table"]').text()).toContain('演示胃肠手术（合成）')
    expect(wrapper.find('[data-testid="multi-total"]').text()).toContain('合计：3 台次')
    expect(wrapper.find('[data-testid="multi-total"]').text()).toContain('215 分钟')
    expect(wrapper.find('[data-testid="multi-pagination"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('台次')
    expect(wrapper.text()).toContain('无时长台次')
  })

  it('锁定报表后展示快照标识（source=SNAPSHOT）', async () => {
    seedAuthenticatedUser(
      syntheticUser({
        permissions: ['m4:report:read', 'm4:report:export', 'm4:report:lock'],
        roles: ['SYS_QC_MANAGER'],
      }),
    )
    const queue = createFetchQueue([
      jsonResponse(workloadBody()),
      jsonResponse(multiBody()),
      jsonResponse(
        {
          lockId: '5a000000-0000-4000-8000-000000000001',
          report: 'workload',
          period: '2026-12-01~2026-12-31',
          from: '2026-12-01',
          to: '2026-12-31',
          params: { report: 'workload', from: '2026-12-01', to: '2026-12-31' },
          paramsHash: 'a'.repeat(64),
          payloadHash: 'b'.repeat(64),
          dataScope: 'ALL',
          rowCount: 2,
          lockedBy: '10000000-0000-4000-8000-000000000001',
          lockedByName: '演示管理员（合成）',
          lockedAt: '2026-12-01T09:30:00+08:00',
        },
        201,
      ),
      jsonResponse(
        workloadBody({
          source: 'SNAPSHOT',
          lockId: '5a000000-0000-4000-8000-000000000001',
          lockedAt: '2026-12-01T09:30:00+08:00',
        }),
      ),
      jsonResponse(multiBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    expect(wrapper.find('[data-testid="report-snapshot"]').exists()).toBe(false)

    await wrapper.find('[data-testid="report-lock"]').trigger('click')
    await settle()

    const lockCall = queue.calls.find((call) => call.url.includes('/v1/reports/locks'))
    expect(lockCall?.init?.method).toBe('POST')
    expect(String(lockCall?.init?.body)).toContain('"report":"workload"')
    expect(wrapper.find('[data-testid="report-snapshot"]').text()).toContain('快照')
    expect(wrapper.find('[data-testid="report-snapshot"]').text()).toContain('2026-12-01')
  })

  it('无 m4:report:read 时展示权限态且不发请求', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m4:material:read'] }))
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="forbidden-alert"]').exists()).toBe(true)
    expect(queue.calls).toHaveLength(0)
  })

  it('无 m4:report:export 时隐藏导出按钮', async () => {
    seedAuthenticatedUser(syntheticUser({ permissions: ['m4:report:read'] }))
    const queue = createFetchQueue([jsonResponse(workloadBody()), jsonResponse(multiBody())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="workload-table"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="export-workload"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="export-multi"]').exists()).toBe(false)
  })

  it('服务端 403 时展示错误态且不渲染表格', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-9403'),
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'SYNTH-TRACE-9403'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="error-alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('无权访问该资源或数据范围不足')
    expect(wrapper.text()).toContain('SYNTH-TRACE-9403')
    expect(wrapper.find('[data-testid="workload-table"]').exists()).toBe(false)
  })

  it('空数据展示空态且合计为 0', async () => {
    const queue = createFetchQueue([
      jsonResponse(
        workloadBody({
          caseCount: 0,
          anesthesiaMinutes: 0,
          weightedCaseCount: 0,
          unrecordedCaseCount: 0,
          buckets: [],
        }),
      ),
      jsonResponse(emptyMultiBody()),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(true)
  })

  it('导出工作量 CSV 携带维度与日期并触发下载', async () => {
    const createObjectURL = vi.fn(() => 'blob:report')
    const revokeObjectURL = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURL, configurable: true })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    const queue = createFetchQueue([
      jsonResponse(workloadBody()),
      jsonResponse(multiBody()),
      new Response('report,level,dimension\r\nworkload,TOTAL,DEPT\r\n', {
        status: 200,
        headers: { 'Content-Type': 'text/csv;charset=UTF-8' },
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="export-workload"]').trigger('click')
    await settle()

    expect(queue.calls[2]?.url).toContain('/api/v1/reports/export?')
    expect(queue.calls[2]?.url).toContain('report=workload')
    expect(queue.calls[2]?.url).toContain('format=csv')
    expect(queue.calls[2]?.url).toContain('dimension=DEPT')
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(document.body.textContent ?? '').toContain('工作量 CSV 已导出')
  })

  it('导出多维 CSV 携带维度组合与指标', async () => {
    const createObjectURL = vi.fn(() => 'blob:multi')
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    const queue = createFetchQueue([
      jsonResponse(workloadBody()),
      jsonResponse(multiBody()),
      new Response('report,level,dimension\r\nmulti,ROW,DEPT+SURGERY\r\n', {
        status: 200,
        headers: { 'Content-Type': 'text/csv;charset=UTF-8' },
      }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="export-multi"]').trigger('click')
    await settle()

    expect(queue.calls[2]?.url).toContain('report=multi')
    expect(queue.calls[2]?.url).toContain('dimensions=DEPT%2CSURGERY')
    expect(queue.calls[2]?.url).toContain('metrics=caseCount%2CanesthesiaMinutes')
    expect(document.body.textContent ?? '').toContain('多维报表 CSV 已导出')
  })
})
