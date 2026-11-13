import type { components as schedulingComponents } from '@anesop/contracts/scheduling'
import type { components as patientComponents } from '@anesop/contracts/patient'
import { http } from '../../../api/http'

/** 手术申请（scheduling.yaml 0.3.0 生成类型，禁止手写重复模型）。 */
export type SurgeryApplication = schedulingComponents['schemas']['SurgeryApplication']
export type SurgeryApplicationPage = schedulingComponents['schemas']['SurgeryApplicationPage']
export type SurgeryApplicationCreateRequest =
  schedulingComponents['schemas']['SurgeryApplicationCreateRequest']
export type ImportSurgeryApplicationsRequest =
  schedulingComponents['schemas']['ImportSurgeryApplicationsRequest']
export type ImportSurgeryApplicationsResult =
  schedulingComponents['schemas']['ImportSurgeryApplicationsResult']
export type CancelSurgeryApplicationRequest =
  schedulingComponents['schemas']['CancelSurgeryApplicationRequest']
export type ApplicationStatus = SurgeryApplication['status']
export type ApplicationUrgency = SurgeryApplication['urgency']

/** 排程工作台（scheduling.yaml 0.4.0）：术间、台次、冲突。 */
export type OperatingRoom = schedulingComponents['schemas']['OperatingRoom']
export type OperatingRoomList = schedulingComponents['schemas']['OperatingRoomList']
export type OperatingRoomType = NonNullable<OperatingRoom['roomType']>
export type Schedule = schedulingComponents['schemas']['Schedule']
export type SchedulePage = schedulingComponents['schemas']['SchedulePage']
export type CreateScheduleRequest = schedulingComponents['schemas']['CreateScheduleRequest']
export type UpdateScheduleRequest = schedulingComponents['schemas']['UpdateScheduleRequest']
export type CancelScheduleRequest = schedulingComponents['schemas']['CancelScheduleRequest']
export type ConflictCheckRequest = schedulingComponents['schemas']['ConflictCheckRequest']
export type ConflictCheckResult = schedulingComponents['schemas']['ConflictCheckResult']
export type ScheduleConflict = ConflictCheckResult['conflicts'][number]

/** 患者摘要（patient.yaml 0.3.0）：不含证件号与手机号。 */
export type PatientSummary = patientComponents['schemas']['PatientSummary']
export type PatientPage = patientComponents['schemas']['PatientPage']
export type PatientSearchRequest = patientComponents['schemas']['PatientSearchRequest']

export interface ApplicationFilters {
  status?: ApplicationStatus | ''
  urgency?: ApplicationUrgency | ''
  departmentName?: string
}

/** 分页查询手术申请（状态/紧急度/科室过滤）。 */
export function listApplications(
  filters: ApplicationFilters,
  page: number,
  size: number,
): Promise<SurgeryApplicationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.status) query.set('status', filters.status)
  if (filters.urgency) query.set('urgency', filters.urgency)
  if (filters.departmentName?.trim()) query.set('departmentName', filters.departmentName.trim())
  return http.get<SurgeryApplicationPage>(`/v1/scheduling/applications?${query.toString()}`)
}

/** 申请详情（含完整状态历史）。 */
export function getApplication(applicationId: string): Promise<SurgeryApplication> {
  return http.get<SurgeryApplication>(`/v1/scheduling/applications/${applicationId}`)
}

/** 手工/测试创建申请。 */
export function createApplication(
  payload: SurgeryApplicationCreateRequest,
): Promise<SurgeryApplication> {
  return http.post<SurgeryApplication>('/v1/scheduling/applications', payload)
}

/** 模拟 HIS 拉取导入（EXT-01）；响应固定 simulated=true。 */
export function importApplications(
  payload: ImportSurgeryApplicationsRequest,
): Promise<ImportSurgeryApplicationsResult> {
  return http.post<ImportSurgeryApplicationsResult>('/v1/scheduling/applications/import', payload)
}

/** 取消申请（写状态历史与审计）。 */
export function cancelApplication(
  applicationId: string,
  payload: CancelSurgeryApplicationRequest,
): Promise<{ id: string }> {
  return http.post<{ id: string }>(`/v1/scheduling/applications/${applicationId}/cancel`, payload)
}

/**
 * 患者摘要检索（B-3：POST 请求体传条件，避免姓名/住院号等 PHI 进入 URL 与访问日志）。
 * 旧 GET /v1/patients 已在契约标记 deprecated，生产禁用。
 */
export function searchPatients(payload: PatientSearchRequest): Promise<PatientPage> {
  return http.post<PatientPage>('/v1/patients/search', payload)
}

/** 术间列表（受数据范围约束：DEPT 本科室 / ROOM 绑定术间）。 */
export function listRooms(): Promise<OperatingRoomList> {
  return http.get<OperatingRoomList>('/v1/scheduling/rooms')
}

/** 按日期（必填）与术间查询日排程。 */
export function listSchedules(date: string, roomId?: string): Promise<SchedulePage> {
  const query = new URLSearchParams({ date })
  if (roomId) query.set('roomId', roomId)
  return http.get<SchedulePage>(`/v1/scheduling/schedules?${query.toString()}`)
}

/** 创建台次；冲突时后端返回 409 + conflicts[]。 */
export function createSchedule(payload: CreateScheduleRequest): Promise<Schedule> {
  return http.post<Schedule>('/v1/scheduling/schedules', payload)
}

/** 调整台次（时间/术间/台次序号），必须回传 version。 */
export function updateSchedule(
  scheduleId: string,
  payload: UpdateScheduleRequest,
): Promise<Schedule> {
  return http.put<Schedule>(`/v1/scheduling/schedules/${scheduleId}`, payload)
}

/** 取消/停台（写原因与审计）。 */
export function cancelSchedule(
  scheduleId: string,
  payload: CancelScheduleRequest,
): Promise<{ id: string }> {
  return http.post<{ id: string }>(`/v1/scheduling/schedules/${scheduleId}/cancel`, payload)
}

/** 冲突即时预检（只读，不落库），供工作台提交前提示。 */
export function checkScheduleConflicts(
  payload: ConflictCheckRequest,
): Promise<ConflictCheckResult> {
  return http.post<ConflictCheckResult>('/v1/scheduling/schedules/conflicts', payload)
}
