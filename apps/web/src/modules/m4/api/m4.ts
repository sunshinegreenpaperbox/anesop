import type { components as drugComponents } from '@anesop/contracts/drug'
import { http } from '../../../api/http'

/** 药品目录与麻精五专（drug.yaml 0.1.0 生成类型，禁止手写重复模型）。 */
export type Drug = drugComponents['schemas']['Drug']
export type DrugList = drugComponents['schemas']['DrugList']
export type CreateDrugRequest = drugComponents['schemas']['CreateDrugRequest']
export type UpdateDrugRequest = drugComponents['schemas']['UpdateDrugRequest']
export type DrugStatus = Drug['status']
export type NarcoticRegister = drugComponents['schemas']['NarcoticRegister']
export type NarcoticRegisterList = drugComponents['schemas']['NarcoticRegisterList']
export type CreateNarcoticRegisterRequest =
  drugComponents['schemas']['CreateNarcoticRegisterRequest']
export type CabinetHandover = drugComponents['schemas']['CabinetHandover']
export type CabinetHandoverList = drugComponents['schemas']['CabinetHandoverList']
export type CabinetHandoverItem = drugComponents['schemas']['CabinetHandoverItem']
export type CreateCabinetHandoverRequest = drugComponents['schemas']['CreateCabinetHandoverRequest']
export type DepartmentOption = drugComponents['schemas']['DepartmentOption']
export type DepartmentList = drugComponents['schemas']['DepartmentList']
export type DrugBaseline = drugComponents['schemas']['DrugBaseline']
export type DrugBaselineList = drugComponents['schemas']['DrugBaselineList']
export type UpdateDrugBaselinesRequest = drugComponents['schemas']['UpdateDrugBaselinesRequest']
export type DailyClose = drugComponents['schemas']['DailyClose']
export type CreateDailyCloseRequest = drugComponents['schemas']['CreateDailyCloseRequest']
export type PharmacyAdapterStatus = drugComponents['schemas']['PharmacyAdapterStatus']
export type Shift = CreateDailyCloseRequest['shift']

/** 药品状态中文标签。 */
export const drugStatusLabels: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
}

/** 班次中文标签（两班制为演示口径，三班制【待确认】）。 */
export const shiftLabels: Record<string, string> = {
  DAY: '白班',
  NIGHT: '夜班',
}

/* ---------------------------------------------------------------------------
 * 药品目录（m4:drug:read / m4:drug:write）
 * ------------------------------------------------------------------------- */

export function listDrugs(params: {
  keyword?: string
  status?: string
  page?: number
  size?: number
}): Promise<DrugList> {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.status) query.set('status', params.status)
  if (params.page) query.set('page', String(params.page))
  if (params.size) query.set('size', String(params.size))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<DrugList>(`/v1/drugs${suffix}`)
}

/** 用药联想/检索：q 为空时服务端返回空列表（不报错）。 */
export function searchDrugs(q: string, limit = 20): Promise<DrugList> {
  const query = new URLSearchParams({ q, limit: String(limit) })
  return http.get<DrugList>(`/v1/drugs/search?${query.toString()}`)
}

export function getDrug(drugId: string): Promise<Drug> {
  return http.get<Drug>(`/v1/drugs/${drugId}`)
}

export function createDrug(payload: CreateDrugRequest): Promise<Drug> {
  return http.post<Drug>('/v1/drugs', payload)
}

export function updateDrug(drugId: string, payload: UpdateDrugRequest): Promise<Drug> {
  return http.put<Drug>(`/v1/drugs/${drugId}`, payload)
}

/* ---------------------------------------------------------------------------
 * 麻精五专（m4:narcotic:manage；append-only + 双人签字 + 平账）
 * ------------------------------------------------------------------------- */

/** 调用者可访问的启用科室（B-3：页面必须显式选择，禁止用当前用户标识冒充科室）。 */
export function listNarcoticDepartments(): Promise<DepartmentList> {
  return http.get<DepartmentList>('/v1/narcotic/departments')
}

export function listNarcoticRegisters(params: {
  departmentId?: string
  drugId?: string
  limit?: number
}): Promise<NarcoticRegisterList> {
  const query = new URLSearchParams()
  if (params.departmentId) query.set('departmentId', params.departmentId)
  if (params.drugId) query.set('drugId', params.drugId)
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<NarcoticRegisterList>(`/v1/narcotic/registers${suffix}`)
}

export function createNarcoticRegister(
  payload: CreateNarcoticRegisterRequest,
): Promise<NarcoticRegister> {
  return http.post<NarcoticRegister>('/v1/narcotic/registers', payload)
}

export function listCabinetHandovers(params: {
  cabinetCode?: string
  departmentId?: string
  limit?: number
}): Promise<CabinetHandoverList> {
  const query = new URLSearchParams()
  if (params.cabinetCode) query.set('cabinetCode', params.cabinetCode)
  if (params.departmentId) query.set('departmentId', params.departmentId)
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<CabinetHandoverList>(`/v1/narcotic/cabinet-handovers${suffix}`)
}

export function createCabinetHandover(
  payload: CreateCabinetHandoverRequest,
): Promise<CabinetHandover> {
  return http.post<CabinetHandover>('/v1/narcotic/cabinet-handovers', payload)
}

export function listDrugBaselines(params: {
  cabinetCode?: string
  departmentId?: string
}): Promise<DrugBaselineList> {
  const query = new URLSearchParams()
  if (params.cabinetCode) query.set('cabinetCode', params.cabinetCode)
  if (params.departmentId) query.set('departmentId', params.departmentId)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<DrugBaselineList>(`/v1/narcotic/baselines${suffix}`)
}

export function updateDrugBaselines(
  payload: UpdateDrugBaselinesRequest,
): Promise<DrugBaselineList> {
  return http.put<DrugBaselineList>('/v1/narcotic/baselines', payload)
}

export function createDailyClose(payload: CreateDailyCloseRequest): Promise<DailyClose> {
  return http.post<DailyClose>('/v1/narcotic/daily-closes', payload)
}

export function getDailyClose(closeId: string): Promise<DailyClose> {
  return http.get<DailyClose>(`/v1/narcotic/daily-closes/${closeId}`)
}

/** 药房适配器状态（EXT-02，SIMULATED；失败时 degraded=true）。 */
export function getPharmacyAdapterStatus(): Promise<PharmacyAdapterStatus> {
  return http.get<PharmacyAdapterStatus>('/v1/drugs/pharmacy-adapter/status')
}

/** 平账期望值：期初 + 入库 − 使用 − 退回（前端仅提示，服务端仍做权威校验）。 */
export function expectedClosing(input: {
  openingQuantity: number
  inboundQuantity: number
  usedQuantity: number
  returnedQuantity: number
}): number {
  const value =
    input.openingQuantity + input.inboundQuantity - input.usedQuantity - input.returnedQuantity
  return Math.round(value * 100) / 100
}
