import type { components as staffComponents } from '@anesop/contracts/staff'
import { http } from '../../../api/http'

/** 人员档案、分级授权、排班与培训（staff.yaml 0.1.0 生成类型，禁止手写重复模型）。 */
export type Staff = staffComponents['schemas']['Staff']
export type StaffList = staffComponents['schemas']['StaffList']
export type StaffQualification = staffComponents['schemas']['StaffQualification']
export type StaffQualificationInput = staffComponents['schemas']['StaffQualificationInput']
export type CreateStaffRequest = staffComponents['schemas']['CreateStaffRequest']
export type UpdateStaffRequest = staffComponents['schemas']['UpdateStaffRequest']
export type StaffAuthorization = staffComponents['schemas']['StaffAuthorization']
export type StaffAuthorizationHistory = staffComponents['schemas']['StaffAuthorizationHistory']
export type UpdateStaffAuthorizationRequest =
  staffComponents['schemas']['UpdateStaffAuthorizationRequest']
export type AuthorizationCheckRequest = staffComponents['schemas']['AuthorizationCheckRequest']
export type AuthorizationCheckResult = staffComponents['schemas']['AuthorizationCheckResult']
export type AuthorizationCheckReason = staffComponents['schemas']['AuthorizationCheckReason']
export type StaffSchedule = staffComponents['schemas']['StaffSchedule']
export type StaffScheduleList = staffComponents['schemas']['StaffScheduleList']
export type CreateStaffScheduleRequest = staffComponents['schemas']['CreateStaffScheduleRequest']
export type UpdateStaffScheduleRequest = staffComponents['schemas']['UpdateStaffScheduleRequest']
export type CancelStaffScheduleRequest = staffComponents['schemas']['CancelStaffScheduleRequest']
export type StaffTraining = staffComponents['schemas']['StaffTraining']
export type StaffTrainingList = staffComponents['schemas']['StaffTrainingList']
export type ExpiringTrainingList = staffComponents['schemas']['ExpiringTrainingList']
export type CreateStaffTrainingRequest = staffComponents['schemas']['CreateStaffTrainingRequest']
export type DepartmentOption = staffComponents['schemas']['DepartmentOption']
export type DepartmentList = staffComponents['schemas']['DepartmentList']
export type AuthorizationLevel = NonNullable<StaffAuthorization['level']>
export type AsaClass = NonNullable<StaffAuthorization['asaMax']>
export type Shift = StaffSchedule['shift']

/** 在职状态中文标签。 */
export const employmentStatusLabels: Record<string, string> = {
  ACTIVE: '在职',
  INACTIVE: '离职/停用',
}

/** 授权级别标签（R1–R5 的业务含义与默认映射【待确认】，见 docs/modules/m4/README.md）。 */
export const authorizationLevelLabels: Record<string, string> = {
  R1: 'R1（最高）',
  R2: 'R2',
  R3: 'R3',
  R4: 'R4',
  R5: 'R5（基础）',
}

/** 班次中文标签（方案书“日常班/值班/备班”映射为 DAY/NIGHT/ON_CALL；三班制【待确认】）。 */
export const staffShiftLabels: Record<string, string> = {
  DAY: '白班',
  NIGHT: '夜班',
  ON_CALL: '备班',
}

/** 排班状态标签。 */
export const scheduleStatusLabels: Record<string, string> = {
  PLANNED: '已排班',
  CANCELLED: '已取消',
}

/** 培训到期状态标签。 */
export const trainingStatusLabels: Record<string, string> = {
  VALID: '有效',
  EXPIRING: '即将到期',
  EXPIRED: '已过期',
  NO_EXPIRY: '长期有效',
}

/** 资质类型标签。 */
export const qualificationTypeLabels: Record<string, string> = {
  PRACTICE_LICENSE: '执业资格',
  SPECIALTY_CERT: '专业资质',
  NURSE_LICENSE: '护士执业证',
  OTHER: '其他',
}

/* ---------------------------------------------------------------------------
 * 人员档案（m4:staff:read / m4:staff:write）
 * ------------------------------------------------------------------------- */

export function listStaff(params: {
  keyword?: string
  departmentId?: string
  employmentStatus?: string
  page?: number
  size?: number
}): Promise<StaffList> {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.departmentId) query.set('departmentId', params.departmentId)
  if (params.employmentStatus) query.set('employmentStatus', params.employmentStatus)
  if (params.page) query.set('page', String(params.page))
  if (params.size) query.set('size', String(params.size))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<StaffList>(`/v1/staff${suffix}`)
}

export function listStaffDepartments(): Promise<DepartmentList> {
  return http.get<DepartmentList>('/v1/staff/departments')
}

export function getStaff(staffId: string): Promise<Staff> {
  return http.get<Staff>(`/v1/staff/${staffId}`)
}

export function createStaff(payload: CreateStaffRequest): Promise<Staff> {
  return http.post<Staff>('/v1/staff', payload)
}

export function updateStaff(staffId: string, payload: UpdateStaffRequest): Promise<Staff> {
  return http.put<Staff>(`/v1/staff/${staffId}`, payload)
}

/* ---------------------------------------------------------------------------
 * 分级授权（m4:staff:write 授予/撤销；append-only 新版本）
 * ------------------------------------------------------------------------- */

export function getStaffAuthorizations(staffId: string): Promise<StaffAuthorizationHistory> {
  return http.get<StaffAuthorizationHistory>(`/v1/staff/${staffId}/authorizations`)
}

export function updateStaffAuthorizations(
  staffId: string,
  payload: UpdateStaffAuthorizationRequest,
): Promise<StaffAuthorizationHistory> {
  return http.put<StaffAuthorizationHistory>(`/v1/staff/${staffId}/authorizations`, payload)
}

/** 授权校验（排程/术中集成点；只做规则判定，不替代临床决策）。 */
export function checkStaffAuthorization(
  payload: AuthorizationCheckRequest,
): Promise<AuthorizationCheckResult> {
  return http.post<AuthorizationCheckResult>('/v1/staff/authorization-checks', payload)
}

/* ---------------------------------------------------------------------------
 * 排班（m4:schedule:manage）
 * ------------------------------------------------------------------------- */

export function listStaffSchedules(params: {
  staffId?: string
  departmentId?: string
  from?: string
  to?: string
  shift?: string
  status?: string
  limit?: number
}): Promise<StaffScheduleList> {
  const query = new URLSearchParams()
  if (params.staffId) query.set('staffId', params.staffId)
  if (params.departmentId) query.set('departmentId', params.departmentId)
  if (params.from) query.set('from', params.from)
  if (params.to) query.set('to', params.to)
  if (params.shift) query.set('shift', params.shift)
  if (params.status) query.set('status', params.status)
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<StaffScheduleList>(`/v1/staff/schedules${suffix}`)
}

export function createStaffSchedule(payload: CreateStaffScheduleRequest): Promise<StaffSchedule> {
  return http.post<StaffSchedule>('/v1/staff/schedules', payload)
}

export function updateStaffSchedule(
  scheduleId: string,
  payload: UpdateStaffScheduleRequest,
): Promise<StaffSchedule> {
  return http.put<StaffSchedule>(`/v1/staff/schedules/${scheduleId}`, payload)
}

export function cancelStaffSchedule(
  scheduleId: string,
  payload: CancelStaffScheduleRequest,
): Promise<StaffSchedule> {
  return http.post<StaffSchedule>(`/v1/staff/schedules/${scheduleId}/cancel`, payload)
}

/* ---------------------------------------------------------------------------
 * 培训与到期提醒（m4:training:manage）
 * ------------------------------------------------------------------------- */

export function listStaffTrainings(params: {
  staffId?: string
  limit?: number
}): Promise<StaffTrainingList> {
  const query = new URLSearchParams()
  if (params.staffId) query.set('staffId', params.staffId)
  if (params.limit) query.set('limit', String(params.limit))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<StaffTrainingList>(`/v1/staff/trainings${suffix}`)
}

export function listExpiringStaffTrainings(params: {
  days?: number
  departmentId?: string
}): Promise<ExpiringTrainingList> {
  const query = new URLSearchParams()
  if (params.days) query.set('days', String(params.days))
  if (params.departmentId) query.set('departmentId', params.departmentId)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<ExpiringTrainingList>(`/v1/staff/trainings/expiring${suffix}`)
}

export function createStaffTraining(payload: CreateStaffTrainingRequest): Promise<StaffTraining> {
  return http.post<StaffTraining>('/v1/staff/trainings', payload)
}
