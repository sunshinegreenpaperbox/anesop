import type { components } from '@anesop/contracts/qc'
import { request } from '../../../api/http'

/**
 * 质控指标、不良事件 RCA、病历质量抽查与导出（qc.yaml 0.1.0 生成类型，禁止手写重复模型）。
 *
 * 口径 qc-1.0.0：指标按 code+version 版本化；分母 0 不判达标；不良事件状态机
 * REPORTED→RCA→RECTIFYING→CLOSED；导出 CSV 为 UTF-8 BOM、脱敏、无自由文本原文。
 */
export type QcIndicator = components['schemas']['QcIndicator']
export type QcIndicatorList = components['schemas']['QcIndicatorList']
export type QcIndicatorResult = components['schemas']['QcIndicatorResult']
export type QcIndicatorResultList = components['schemas']['QcIndicatorResultList']
export type AdverseEvent = components['schemas']['AdverseEvent']
export type AdverseEventList = components['schemas']['AdverseEventList']
export type AdverseRca = components['schemas']['AdverseRca']
export type ChartReview = components['schemas']['ChartReview']
export type ChartReviewItem = components['schemas']['ChartReviewItem']
export type ChartReviewList = components['schemas']['ChartReviewList']
export type Rectification = components['schemas']['Rectification']
export type PageMeta = components['schemas']['PageMeta']

export type QcIndicatorCategory = 'STRUCTURE' | 'PROCESS' | 'OUTCOME'
export type AdverseEventStatus = 'REPORTED' | 'RCA' | 'RECTIFYING' | 'CLOSED'
export type ChartReviewStatus = 'OPEN' | 'RECTIFYING' | 'CLOSED'
export type ChartReviewItemCode = 'COMPLETENESS' | 'TIMELINESS' | 'SIGNATURE' | 'CONSISTENCY'
export type ExportType = 'indicator' | 'adverse' | 'review'

export const categoryLabels: Record<QcIndicatorCategory, string> = {
  STRUCTURE: '结构',
  PROCESS: '过程',
  OUTCOME: '结果',
}

export const adverseStatusLabels: Record<AdverseEventStatus, string> = {
  REPORTED: '已上报',
  RCA: '根因分析中',
  RECTIFYING: '整改中',
  CLOSED: '已闭环',
}

export const reviewStatusLabels: Record<ChartReviewStatus, string> = {
  OPEN: '待整改',
  RECTIFYING: '整改中',
  CLOSED: '已闭环',
}

export const itemLabels: Record<ChartReviewItemCode, string> = {
  COMPLETENESS: '完整性',
  TIMELINESS: '时效性',
  SIGNATURE: '签名',
  CONSISTENCY: '逻辑一致性',
}

export const eventTypeLabels: Record<string, string> = {
  RESPIRATORY: '呼吸系统',
  CIRCULATORY: '循环系统',
  MEDICATION: '用药',
  TRANSFUSION: '输血',
  EQUIPMENT: '设备',
  NERVE_INJURY: '神经损伤',
  AWARENESS: '术中知晓',
  FALL: '跌倒',
  OTHER: '其他',
}

export const severityLabels: Record<string, string> = {
  LEVEL_I: 'I 级',
  LEVEL_II: 'II 级',
  LEVEL_III: 'III 级',
  LEVEL_IV: 'IV 级',
}

/** 契约固定上限：指标/导出周期（含首尾）≤ 366 天。 */
export const MAX_RANGE_DAYS = 366

/** 病历抽查合格线（契约 0.1.0：≥90% 为合格【待确认】）。 */
export const REVIEW_PASS_RATIO = 90

export interface QcRange {
  from: string
  to: string
  deptId?: string
}

export interface AdverseEventInput {
  eventType: string
  severity: string
  occurredAt: string
  deptId: string
  scheduleId?: string
  description: string
  anonymous?: boolean
}

export interface RcaTimelineInput {
  occurredAt: string
  description: string
}

export interface RcaInput {
  timeline: RcaTimelineInput[]
  rootCause: string
  correctiveAction: string
  responsibleName: string
  dueDate: string
}

export interface CloseRcaInput {
  reviewerName: string
  conclusion: string
  rectificationCompleted?: boolean
}

export interface ChartReviewInput {
  deptId: string
  reviewedAt: string
  scheduleId?: string
  recordId?: string
  items: {
    itemCode: ChartReviewItemCode
    itemName?: string
    score: number
    maxScore: number
    defectNote?: string
  }[]
}

export interface RectificationInput {
  correctiveAction: string
  responsibleName: string
  dueDate: string
}

export interface CloseRectificationInput {
  reviewerName: string
  conclusion: string
}

/** 区间天数（含首尾）；非法日期返回 0。 */
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
  if (days > MAX_RANGE_DAYS) return `查询跨度不能超过 ${MAX_RANGE_DAYS} 天`
  return null
}

/** 抽查得分率（百分比，2 位小数）；满分为 0 时返回 0。 */
export function scorePercent(totalScore: number, maxScore: number): number {
  if (!maxScore || maxScore <= 0) return 0
  return Math.round((totalScore * 10000) / maxScore) / 100
}

/** 契约等级口径：A≥95%、B≥90%、C≥80%、D<80%（与后端一致，仅用于表单预览）。 */
export function gradeOf(totalScore: number, maxScore: number): 'A' | 'B' | 'C' | 'D' {
  const percent = scorePercent(totalScore, maxScore)
  if (percent >= 95) return 'A'
  if (percent >= REVIEW_PASS_RATIO) return 'B'
  if (percent >= 80) return 'C'
  return 'D'
}

export function getIndicators(): Promise<QcIndicatorList> {
  return request<QcIndicatorList>('/v1/qc/indicators')
}

export function getIndicatorResults(range: QcRange): Promise<QcIndicatorResultList> {
  const query = new URLSearchParams({ from: range.from, to: range.to })
  if (range.deptId) query.set('deptId', range.deptId)
  return request<QcIndicatorResultList>(`/v1/qc/indicator-results?${query.toString()}`)
}

export function listAdverseEvents(
  params: QcRange & { status?: string; page?: number; size?: number },
): Promise<AdverseEventList> {
  const query = new URLSearchParams({
    page: String(params.page ?? 1),
    size: String(params.size ?? 20),
  })
  if (params.from) query.set('from', params.from)
  if (params.to) query.set('to', params.to)
  if (params.status) query.set('status', params.status)
  if (params.deptId) query.set('deptId', params.deptId)
  return request<AdverseEventList>(`/v1/qc/adverse-events?${query.toString()}`)
}

export function createAdverseEvent(input: AdverseEventInput): Promise<AdverseEvent> {
  return request<AdverseEvent>('/v1/qc/adverse-events', { method: 'POST', body: input })
}

export function createAdverseRca(eventId: string, input: RcaInput): Promise<AdverseRca> {
  return request<AdverseRca>(`/v1/qc/adverse-events/${eventId}/rca`, {
    method: 'POST',
    body: input,
  })
}

export function closeAdverseRca(rcaId: string, input: CloseRcaInput): Promise<AdverseRca> {
  return request<AdverseRca>(`/v1/qc/rca/${rcaId}/close`, { method: 'POST', body: input })
}

export function listChartReviews(
  params: QcRange & { status?: string; page?: number; size?: number },
): Promise<ChartReviewList> {
  const query = new URLSearchParams({
    page: String(params.page ?? 1),
    size: String(params.size ?? 20),
  })
  if (params.from) query.set('from', params.from)
  if (params.to) query.set('to', params.to)
  if (params.status) query.set('status', params.status)
  if (params.deptId) query.set('deptId', params.deptId)
  return request<ChartReviewList>(`/v1/qc/chart-reviews?${query.toString()}`)
}

export function createChartReview(input: ChartReviewInput): Promise<ChartReview> {
  return request<ChartReview>('/v1/qc/chart-reviews', { method: 'POST', body: input })
}

export function createRectification(
  reviewId: string,
  input: RectificationInput,
): Promise<Rectification> {
  return request<Rectification>(`/v1/qc/chart-reviews/${reviewId}/rectify`, {
    method: 'POST',
    body: input,
  })
}

export function closeRectification(
  rectificationId: string,
  input: CloseRectificationInput,
): Promise<Rectification> {
  return request<Rectification>(`/v1/qc/rectify/${rectificationId}/close`, {
    method: 'POST',
    body: input,
  })
}

export interface QcExportInput {
  type: ExportType
  range: QcRange
}

/**
 * 导出 CSV（服务端 UTF-8 BOM + 防公式注入 + 脱敏，不含自由文本原文）。
 * 与报表导出一致：以 `Accept: text/csv` 取文本后包装 Blob 触发下载。
 */
export async function exportQcCsv(input: QcExportInput): Promise<Blob> {
  const query = new URLSearchParams({
    type: input.type,
    format: 'csv',
    from: input.range.from,
    to: input.range.to,
  })
  if (input.range.deptId) query.set('deptId', input.range.deptId)
  const text = await request<string>(`/v1/qc/export?${query.toString()}`, {
    headers: { Accept: 'text/csv' },
  })
  return new Blob([text], { type: 'text/csv;charset=utf-8' })
}

/** 导出文件名（与服务端 Content-Disposition 同一命名规则）。 */
export function exportFileName(input: QcExportInput): string {
  return `qc-${input.type}-${input.range.from}-${input.range.to}.csv`
}
