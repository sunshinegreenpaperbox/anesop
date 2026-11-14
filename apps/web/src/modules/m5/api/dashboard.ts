import type { components } from '@anesop/contracts/dashboard'
import { request } from '../../../api/http'

/** 实时态势/KPI（dashboard.yaml 0.1.0 生成类型，禁止手写重复模型）。 */
export type RealtimeDashboard = components['schemas']['RealtimeDashboard']
export type DashboardKpi = components['schemas']['DashboardKpi']
export type RoomSituation = components['schemas']['RoomSituation']
export type RoomStatus = components['schemas']['RoomStatus']
export type CaseStatus = components['schemas']['CaseStatus']
export type DataQuality = components['schemas']['DataQuality']
export type DataScopeType = components['schemas']['DataScopeType']

/** 前端轮询间隔（契约固定 10s；后端无状态，不使用 SSE/WebSocket）。 */
export const REFRESH_INTERVAL_MS = 10_000

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

/** 实时态势（术间/台次/告警/设备 + 数据延迟）。 */
export function getRealtimeDashboard(): Promise<RealtimeDashboard> {
  return request<RealtimeDashboard>('/v1/dashboard/realtime')
}

/** 当日 KPI；date 缺省为服务端当日（YYYY-MM-DD，Asia/Shanghai）。 */
export function getDashboardKpi(date?: string): Promise<DashboardKpi> {
  const query = date ? `?${new URLSearchParams({ date }).toString()}` : ''
  return request<DashboardKpi>(`/v1/dashboard/kpi${query}`)
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
