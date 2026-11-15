import type { components } from '@anesop/contracts/dashboard'
import { request } from '../../../api/http'

/** 看板（dashboard.yaml 0.2.0 生成类型，禁止手写重复模型）。 */
export type RealtimeDashboard = components['schemas']['RealtimeDashboard']
export type DashboardKpi = components['schemas']['DashboardKpi']
export type RiskDashboard = components['schemas']['RiskDashboard']
export type ResourceDashboard = components['schemas']['ResourceDashboard']
export type TrendDashboard = components['schemas']['TrendDashboard']
export type TrendPoint = components['schemas']['TrendPoint']
export type AsaClass = components['schemas']['AsaClass']
export type RiskLevel = components['schemas']['RiskLevel']
export type Shift = components['schemas']['Shift']
export type ResourceGap = components['schemas']['ResourceGap']
export type ResourceGapCode = components['schemas']['ResourceGapCode']
export type ResourceGapSeverity = components['schemas']['ResourceGapSeverity']
export type RoomSituation = components['schemas']['RoomSituation']
export type RoomStatus = components['schemas']['RoomStatus']
export type CaseStatus = components['schemas']['CaseStatus']
export type DataQuality = components['schemas']['DataQuality']
export type DataScopeType = components['schemas']['DataScopeType']

/** 前端轮询间隔（契约固定 10s；后端无状态，不使用 SSE/WebSocket）。 */
export const REFRESH_INTERVAL_MS = 10_000

/** 趋势缺省窗口（小时，契约 0.5–24，即 30–1440 分钟）。 */
export const DEFAULT_TREND_HOURS = 24

export const roomStatusLabels: Record<RoomStatus, string> = {
  IN_SURGERY: '手术中',
  IDLE: '空闲',
  CLEANING: '清洁',
  PREPARING: '准备',
  RECOVERY: '复苏',
  OUT_OF_SERVICE: '停用',
}

export const caseStatusLabels: Record<CaseStatus, string> = {
  PLANNED: '计划',
  PUBLISHED: '已发布',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  DELAYED: '延迟',
}

export const dataQualityLabels: Record<DataQuality, string> = {
  OK: '数据正常',
  DEGRADED: '数据延迟',
  NO_DATA: '暂无数据',
}

export const dataScopeLabels: Record<DataScopeType, string> = {
  ALL: '全院',
  SITE: '院区',
  DEPT: '科室',
  ROOM: '术间',
  SELF: '本人',
}

export const asaClassLabels: Record<AsaClass, string> = {
  I: 'ASA I',
  II: 'ASA II',
  III: 'ASA III',
  IV: 'ASA IV',
  V: 'ASA V',
  VI: 'ASA VI',
}

export const riskLevelLabels: Record<RiskLevel, string> = {
  LOW: '低危',
  MODERATE: '中危',
  HIGH: '高危',
}

export const shiftLabels: Record<Shift, string> = {
  DAY: '白班',
  NIGHT: '夜班',
  ON_CALL: '备班',
}

export const resourceGapLabels: Record<ResourceGapCode, string> = {
  DEVICE_OFFLINE: '设备离线',
  DEVICE_MAINTENANCE: '设备维护',
  ROOM_OUT_OF_SERVICE: '停用术间',
  ROOM_OVERLOADED: '排程超载',
  STAFF_NOT_SCHEDULED: '当班人员为 0',
  NO_VISIBLE_ROOM: '无可见术间',
}

export const resourceGapSeverityLabels: Record<ResourceGapSeverity, string> = {
  INFO: '提示',
  WARN: '关注',
}

/** 实时态势（术间/台次/告警/设备 + 数据延迟）。 */
export function getRealtimeDashboard(): Promise<RealtimeDashboard> {
  return request<RealtimeDashboard>('/v1/dashboard/realtime')
}

/** 当日 KPI；date 缺省为服务端当日（YYYY-MM-DD，Asia/Shanghai）。 */
export function getDashboardKpi(date?: string): Promise<DashboardKpi> {
  const query = date ? `?${new URLSearchParams({ date }).toString()}` : ''
  return request<DashboardKpi>(`/v1/dashboard/kpi${query}`)
}

/** 风险分层（ASA/术前评分风险等级/未确认严重告警/超时台次；仅聚合计数）。 */
export function getRiskDashboard(): Promise<RiskDashboard> {
  return request<RiskDashboard>('/v1/dashboard/risk')
}

/** 资源态势（术间利用率与状态、设备在线率、当班人员、缺口提示）。 */
export function getResourceDashboard(): Promise<ResourceDashboard> {
  return request<ResourceDashboard>('/v1/dashboard/resources')
}

/** 告警/台次/利用率按小时趋势；hours 允许 0.5–24（缺省 24）。 */
export function getDashboardTrends(hours: number = DEFAULT_TREND_HOURS): Promise<TrendDashboard> {
  const query = new URLSearchParams({ hours: String(hours) }).toString()
  return request<TrendDashboard>(`/v1/dashboard/trends?${query}`)
}

/** 本地自然日 YYYY-MM-DD（不使用 UTC 的 toISOString，避免跨时区错日）。 */
export function localDate(value: Date = new Date()): string {
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${day}`
}

/** 比率（0–1）→ 百分比文案；分母 0 时服务端返回 0，展示为 0.0%。 */
export function formatRate(value: number | undefined): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}

/** 数据延迟文案：秒数 + 分级标签（延迟 > 120s 时由服务端标 DEGRADED）。 */
export function formatDelay(seconds: number | undefined): string {
  const value = Math.max(0, Math.round(seconds ?? 0))
  if (value < 60) return `${value} 秒`
  const minutes = Math.floor(value / 60)
  const rest = value % 60
  return rest === 0 ? `${minutes} 分钟` : `${minutes} 分 ${rest} 秒`
}

/** 时间戳（RFC 3339）→ 本地 HH:00（小时桶标签）；无效值返回 “—”。 */
export function formatHour(value: string | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return `${String(parsed.getHours()).padStart(2, '0')}:00`
}

/** 时间戳（RFC 3339）→ 本地 HH:MM:SS；无效值返回 “—”。 */
export function formatClock(value: string | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  const pad = (input: number) => String(input).padStart(2, '0')
  return `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
}

/** 数值文案（千分位）。 */
export function formatNumber(value: number | undefined): string {
  return (value ?? 0).toLocaleString('zh-CN')
}

/** 平均麻醉时长（分钟，2 位小数）。 */
export function formatMinutes(value: number | undefined): string {
  return `${(value ?? 0).toFixed(1)} 分钟`
}
