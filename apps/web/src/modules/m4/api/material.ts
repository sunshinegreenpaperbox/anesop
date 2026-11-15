import type { components as materialComponents } from '@anesop/contracts/material'
import { http } from '../../../api/http'

/** 耗材、UDI 追溯与 SPD 适配器（material.yaml 0.2.0 生成类型，禁止手写重复模型）。 */
export type Material = materialComponents['schemas']['Material']
export type MaterialList = materialComponents['schemas']['MaterialList']
export type CreateMaterialRequest = materialComponents['schemas']['CreateMaterialRequest']
export type UpdateMaterialRequest = materialComponents['schemas']['UpdateMaterialRequest']
export type MaterialStatusRequest = materialComponents['schemas']['MaterialStatusRequest']
export type DepartmentOption = materialComponents['schemas']['DepartmentOption']
export type DepartmentList = materialComponents['schemas']['DepartmentList']
export type MaterialStockLevel = materialComponents['schemas']['MaterialStockLevel']
export type MaterialStockLevelList = materialComponents['schemas']['MaterialStockLevelList']
export type StockMovement = materialComponents['schemas']['StockMovement']
export type StockMovementList = materialComponents['schemas']['StockMovementList']
export type CreateStockMovementRequest = materialComponents['schemas']['CreateStockMovementRequest']
export type UdiInstance = materialComponents['schemas']['UdiInstance']
export type UdiTrace = materialComponents['schemas']['UdiTrace']
export type RegisterUdiInstanceRequest = materialComponents['schemas']['RegisterUdiInstanceRequest']
export type UseUdiInstanceRequest = materialComponents['schemas']['UseUdiInstanceRequest']
export type SpdAdapterStatus = materialComponents['schemas']['SpdAdapterStatus']
export type SpdSyncResult = materialComponents['schemas']['SpdSyncResult']
export type MaterialStockAlert = materialComponents['schemas']['MaterialStockAlert']
export type MaterialStockAlertList = materialComponents['schemas']['MaterialStockAlertList']
export type StockAlertType = materialComponents['schemas']['StockAlertType']
export type LowStockThresholdRequest = materialComponents['schemas']['LowStockThresholdRequest']
export type MovementType = CreateStockMovementRequest['movementType']
export type UdiStatus = UdiInstance['status']

/** 耗材状态中文标签。 */
export const materialStatusLabels: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
}

/** 出入库类型中文标签。 */
export const movementTypeLabels: Record<string, string> = {
  IN: '入库',
  OUT: '出库',
  RETURN: '退回',
  ADJUST: '盘点调整',
}

/** 库存预警类型中文标签（0.2.0：低阈值 / 零库存 / 临期 90 天）。 */
export const alertTypeLabels: Record<StockAlertType, string> = {
  LOW_STOCK: '低库存',
  ZERO_STOCK: '零库存',
  NEAR_EXPIRY: '临期（≤90 天）',
}

/** UDI 状态中文标签。 */
export const udiStatusLabels: Record<string, string> = {
  IN_STOCK: '在库',
  USED: '已使用',
  RETURNED: '已退回',
}

/* ---------------------------------------------------------------------------
 * 耗材主数据（m4:material:read / m4:material:write）
 * ------------------------------------------------------------------------- */

export function listMaterials(params: {
  keyword?: string
  status?: string
  highValue?: boolean
  page?: number
  size?: number
}): Promise<MaterialList> {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.status) query.set('status', params.status)
  if (params.highValue !== undefined) query.set('highValue', String(params.highValue))
  if (params.page) query.set('page', String(params.page))
  if (params.size) query.set('size', String(params.size))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<MaterialList>(`/v1/materials${suffix}`)
}

/** 扫码/录入联想：q 为空时服务端返回空列表（不报错）。 */
export function searchMaterials(q: string, limit = 20): Promise<MaterialList> {
  const query = new URLSearchParams({ q, limit: String(limit) })
  return http.get<MaterialList>(`/v1/materials/search?${query.toString()}`)
}

export function getMaterial(materialId: string): Promise<Material> {
  return http.get<Material>(`/v1/materials/${materialId}`)
}

export function createMaterial(payload: CreateMaterialRequest): Promise<Material> {
  return http.post<Material>('/v1/materials', payload)
}

export function updateMaterial(
  materialId: string,
  payload: UpdateMaterialRequest,
): Promise<Material> {
  return http.put<Material>(`/v1/materials/${materialId}`, payload)
}

/** 启用/停用耗材（ACTIVE/INACTIVE，m4:material:write）；状态未变化时服务端幂等返回。 */
export function updateMaterialStatus(
  materialId: string,
  status: MaterialStatusRequest['status'],
): Promise<Material> {
  return http.patch<Material>(`/v1/materials/${materialId}/status`, { status })
}

/* ---------------------------------------------------------------------------
 * 出入库与批次库存（m4:material:read / m4:material:write；按科室数据范围）
 * ------------------------------------------------------------------------- */

export function listMaterialDepartments(): Promise<DepartmentList> {
  return http.get<DepartmentList>('/v1/materials/departments')
}

export function listMaterialStock(params: {
  materialId?: string
  departmentId?: string
  limit?: number
}): Promise<MaterialStockLevelList> {
  const query = new URLSearchParams()
  if (params.materialId) query.set('materialId', params.materialId)
  if (params.departmentId) query.set('departmentId', params.departmentId)
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<MaterialStockLevelList>(`/v1/materials/stock${suffix}`)
}

export function listStockMovements(params: {
  materialId?: string
  departmentId?: string
  from?: string
  to?: string
  limit?: number
}): Promise<StockMovementList> {
  const query = new URLSearchParams()
  if (params.materialId) query.set('materialId', params.materialId)
  if (params.departmentId) query.set('departmentId', params.departmentId)
  if (params.from) query.set('from', params.from)
  if (params.to) query.set('to', params.to)
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<StockMovementList>(`/v1/materials/stock-movements${suffix}`)
}

export function createStockMovement(payload: CreateStockMovementRequest): Promise<StockMovement> {
  return http.post<StockMovement>('/v1/materials/stock-movements', payload)
}

/* ---------------------------------------------------------------------------
 * 库存预警与阈值（material.yaml 0.2.0；m4:material:read / m4:material:write）
 * ------------------------------------------------------------------------- */

/**
 * 库存预警（材料 × 科室；低于阈值 / 零库存 / 临期 90 天，含建议补货量）。
 *
 * 数据范围 ALL/SITE/DEPT；SELF/ROOM 403（服务端 fail-closed）。
 */
export function listStockAlerts(params: {
  departmentId?: string
  alertType?: StockAlertType
  limit?: number
}): Promise<MaterialStockAlertList> {
  const query = new URLSearchParams()
  if (params.departmentId) query.set('departmentId', params.departmentId)
  if (params.alertType) query.set('alertType', params.alertType)
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<MaterialStockAlertList>(`/v1/materials/stock-alerts${suffix}`)
}

/** 维护库存预警阈值（乐观锁；threshold 为 null 表示清除阈值 = 不按阈值预警）。 */
export function updateLowStockThreshold(
  materialId: string,
  payload: LowStockThresholdRequest,
): Promise<Material> {
  return http.put<Material>(`/v1/materials/${materialId}/low-stock-threshold`, payload)
}

/* ---------------------------------------------------------------------------
 * UDI 一物一码（m4:udi:manage；追溯查询 m4:material:read）
 * ------------------------------------------------------------------------- */

export function registerUdiInstance(payload: RegisterUdiInstanceRequest): Promise<UdiInstance> {
  return http.post<UdiInstance>('/v1/materials/udi-instances', payload)
}

/** UDI 内部规范编码 UDI-DI^UDI-PI；路径参数必须编码（^ → %5E）。 */
export function udiPath(udi: string): string {
  return encodeURIComponent(udi)
}

export function getUdiTrace(udi: string): Promise<UdiTrace> {
  return http.get<UdiTrace>(`/v1/materials/udi-instances/${udiPath(udi)}`)
}

export function useUdiInstance(udi: string, payload: UseUdiInstanceRequest): Promise<UdiInstance> {
  return http.post<UdiInstance>(`/v1/materials/udi-instances/${udiPath(udi)}/use`, payload)
}

/* ---------------------------------------------------------------------------
 * SPD 适配器（EXT-03，SIMULATED；失败降级）
 * ------------------------------------------------------------------------- */

export function getSpdAdapterStatus(): Promise<SpdAdapterStatus> {
  return http.get<SpdAdapterStatus>('/v1/materials/spd-adapter/status')
}

export function syncSpdAdapter(): Promise<SpdSyncResult> {
  return http.post<SpdSyncResult>('/v1/materials/spd-adapter/sync')
}
