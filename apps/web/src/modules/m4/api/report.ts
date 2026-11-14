import type { components } from '@anesop/contracts/report'
import { request } from '../../../api/http'

/** 工作量/多维报表（report.yaml 0.1.0 生成类型，禁止手写重复模型）。 */
export type WorkloadReport = components['schemas']['WorkloadReport']
export type WorkloadBucket = components['schemas']['WorkloadBucket']
export type SurgeryDistribution = components['schemas']['SurgeryDistribution']
export type MultiReport = components['schemas']['MultiReport']
export type MultiRow = components['schemas']['MultiRow']
export type ReportTotals = components['schemas']['ReportTotals']

/** 维度白名单（DEPT=申请科室、STAFF=麻醉医师、SURGERY=术式、ROOM=术间）。 */
export type ReportDimension = 'DEPT' | 'STAFF' | 'SURGERY' | 'ROOM'

/** 指标白名单（台次/麻醉时长/加权台次）。 */
export type ReportMetricCode = 'caseCount' | 'anesthesiaMinutes' | 'weightedCaseCount'

export const dimensionLabels: Record<ReportDimension, string> = {
  DEPT: '科室',
  STAFF: '麻醉医师',
  SURGERY: '术式',
  ROOM: '术间',
}

export const metricLabels: Record<ReportMetricCode, string> = {
  caseCount: '台次',
  anesthesiaMinutes: '麻醉时长（分钟）',
  weightedCaseCount: '加权台次',
}

/** 契约固定上限：统计跨度（含首尾）≤ 366 天。 */
export const MAX_RANGE_DAYS = 366

export interface ReportRange {
  from: string
  to: string
  deptId?: string
}

/** 区间天数（含首尾）；非法日期返回 0，由页面拦截提示。 */
export function rangeDays(from: string, to: string): number {
  const start = Date.parse(`${from}T00:00:00`)
  const end = Date.parse(`${to}T00:00:00`)
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return 0
  return Math.round((end - start) / 86_400_000) + 1
}

/** 前端预校验：返回提示文案；通过返回 null（服务端仍做权威校验）。 */
export function validateRange(from: string, to: string): string | null {
  if (!from || !to) return '请选择完整的日期范围'
  const days = rangeDays(from, to)
  if (days <= 0) return '结束日期不能早于起始日期'
  if (days > MAX_RANGE_DAYS) return `统计跨度不能超过 ${MAX_RANGE_DAYS} 天`
  return null
}

export function getWorkloadReport(
  range: ReportRange,
  dimension: ReportDimension,
): Promise<WorkloadReport> {
  const query = new URLSearchParams({ from: range.from, to: range.to, dimension })
  if (range.deptId) query.set('deptId', range.deptId)
  return request<WorkloadReport>(`/v1/reports/workload?${query.toString()}`)
}

export function getMultiReport(
  range: ReportRange,
  dimensions: ReportDimension[],
  metrics: ReportMetricCode[],
  page = 1,
  size = 20,
): Promise<MultiReport> {
  const query = new URLSearchParams({
    from: range.from,
    to: range.to,
    dimensions: dimensions.join(','),
    metrics: metrics.join(','),
    page: String(page),
    size: String(size),
  })
  if (range.deptId) query.set('deptId', range.deptId)
  return request<MultiReport>(`/v1/reports/multi?${query.toString()}`)
}

export interface ReportExportInput {
  report: 'workload' | 'multi'
  range: ReportRange
  dimension?: ReportDimension
  dimensions?: ReportDimension[]
  metrics?: ReportMetricCode[]
}

/**
 * 导出 CSV（服务端 UTF-8 BOM + 防公式注入 + 脱敏）。
 *
 * 与排程分析导出一致：以 `Accept: text/csv` 取文本后包装 Blob 触发下载；
 * 非法参数在服务端返回 JSON 400，由页面统一以 ApiError 展示。
 */
export async function exportReportCsv(input: ReportExportInput): Promise<Blob> {
  const query = new URLSearchParams({
    report: input.report,
    format: 'csv',
    from: input.range.from,
    to: input.range.to,
  })
  if (input.dimension) query.set('dimension', input.dimension)
  if (input.dimensions && input.dimensions.length > 0) {
    query.set('dimensions', input.dimensions.join(','))
  }
  if (input.metrics && input.metrics.length > 0) {
    query.set('metrics', input.metrics.join(','))
  }
  if (input.range.deptId) query.set('deptId', input.range.deptId)
  const text = await request<string>(`/v1/reports/export?${query.toString()}`, {
    headers: { Accept: 'text/csv' },
  })
  return new Blob([text], { type: 'text/csv;charset=utf-8' })
}

/** 导出文件名（与服务端 Content-Disposition 保持同一命名规则）。 */
export function exportFileName(input: ReportExportInput): string {
  const suffix = `${input.range.from}-${input.range.to}.csv`
  if (input.report === 'workload') {
    return `workload-${input.dimension ?? 'DEPT'}-${suffix}`
  }
  return `multi-${(input.dimensions ?? []).join('+')}-${suffix}`
}
