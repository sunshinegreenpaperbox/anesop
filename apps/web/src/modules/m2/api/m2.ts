import type { components as anesthesiaComponents } from '@anesop/contracts/anesthesia'
import { http } from '../../../api/http'

/** 术前访视与评估（anesthesia.yaml 0.3.0 生成类型，禁止手写重复模型）。 */
export type PreopPatientSnapshot = anesthesiaComponents['schemas']['PreopPatientSnapshot']
export type PreopAirwayAssessment = anesthesiaComponents['schemas']['PreopAirwayAssessment']
export type PreopScoreInput = anesthesiaComponents['schemas']['PreopScoreInput']
export type PreopScores = anesthesiaComponents['schemas']['PreopScores']
export type PreopPlan = anesthesiaComponents['schemas']['PreopPlan']
export type PreopAssessment = anesthesiaComponents['schemas']['PreopAssessment']
export type PreopAssessmentList = anesthesiaComponents['schemas']['PreopAssessmentList']
export type PreopApplicationSummary = anesthesiaComponents['schemas']['PreopApplicationSummary']
export type PreopAssessmentCreateRequest =
  anesthesiaComponents['schemas']['PreopAssessmentCreateRequest']
export type PreopAssessmentUpdateRequest =
  anesthesiaComponents['schemas']['PreopAssessmentUpdateRequest']
export type FinalizePreopAssessmentRequest =
  anesthesiaComponents['schemas']['FinalizePreopAssessmentRequest']
export type PreopAsaClass = PreopAssessment['asaClass']
export type PreopMallampati = PreopAirwayAssessment['mallampati']
export type PreopCervicalSpine = PreopAirwayAssessment['cervicalSpineMobility']
export type PreopPlanType = PreopPlan['planType']
export type PreopDasiActivity = PreopScoreInput['dasiActivities'][number]

/**
 * 按手术申请查询访视单与申请上下文（患者快照/术式/申请状态）。
 * 申请不可见时后端返回 403（集合查询越权）；页面据 ApiError 展示权限态。
 */
export function listPreopAssessments(applicationId: string): Promise<PreopAssessmentList> {
  const query = new URLSearchParams({ applicationId })
  return http.get<PreopAssessmentList>(`/v1/anesthesia/preop-assessments?${query.toString()}`)
}

/** 访视单详情（含服务端计算的评分）。 */
export function getPreopAssessment(assessmentId: string): Promise<PreopAssessment> {
  return http.get<PreopAssessment>(`/v1/anesthesia/preop-assessments/${assessmentId}`)
}

/** 创建草稿：只提交量表条目，分数由服务端计算。 */
export function createPreopAssessment(
  payload: PreopAssessmentCreateRequest,
): Promise<PreopAssessment> {
  return http.post<PreopAssessment>('/v1/anesthesia/preop-assessments', payload)
}

/** 全量保存草稿（必须回传 version；已最终化/版本过期 409）。 */
export function updatePreopAssessment(
  assessmentId: string,
  payload: PreopAssessmentUpdateRequest,
): Promise<PreopAssessment> {
  return http.put<PreopAssessment>(`/v1/anesthesia/preop-assessments/${assessmentId}`, payload)
}

/** 最终化访视单（锁定，只能新建版本；必须回传 version）。 */
export function finalizePreopAssessment(
  assessmentId: string,
  payload: FinalizePreopAssessmentRequest,
): Promise<PreopAssessment> {
  return http.post<PreopAssessment>(
    `/v1/anesthesia/preop-assessments/${assessmentId}/finalize`,
    payload,
  )
}

/* ---------------------------------------------------------------------------
 * 知情同意与三方核查（anesthesia.yaml 0.4.0，M2-M5-01）
 * CA 不可用（EXT-05）：签署返回 caStatus=SIMULATED 的占位签名，界面必须显式标注。
 * ------------------------------------------------------------------------- */

export type ConsentTemplate = anesthesiaComponents['schemas']['ConsentTemplate']
export type ConsentTemplateList = anesthesiaComponents['schemas']['ConsentTemplateList']
export type ConsentRecord = anesthesiaComponents['schemas']['ConsentRecord']
export type ConsentRecordList = anesthesiaComponents['schemas']['ConsentRecordList']
export type CreateConsentRequest = anesthesiaComponents['schemas']['CreateConsentRequest']
export type SignConsentRequest = anesthesiaComponents['schemas']['SignConsentRequest']
export type DeclineConsentRequest = anesthesiaComponents['schemas']['DeclineConsentRequest']
export type RevokeConsentRequest = anesthesiaComponents['schemas']['RevokeConsentRequest']
export type ConsentStatus = ConsentRecord['status']
export type ConsentCaStatus = ConsentRecord['caStatus']
export type ConsentSignerRelation = ConsentRecord['signerRelation']
export type ConsentSignMethod = ConsentRecord['signMethod']
export type AnesthesiaApplicationSummary =
  anesthesiaComponents['schemas']['AnesthesiaApplicationSummary']
export type ApplicationThreeWayCheck = anesthesiaComponents['schemas']['ApplicationThreeWayCheck']
export type ApplicationThreeWayCheckList =
  anesthesiaComponents['schemas']['ApplicationThreeWayCheckList']
export type RecordApplicationThreeWayCheckRequest =
  anesthesiaComponents['schemas']['RecordApplicationThreeWayCheckRequest']

/** 模板与版本（内容不可变；新版本另建）。 */
export function listConsentTemplates(params?: {
  templateCode?: string
  effectiveOnly?: boolean
}): Promise<ConsentTemplateList> {
  const query = new URLSearchParams()
  if (params?.templateCode) query.set('templateCode', params.templateCode)
  if (params?.effectiveOnly !== undefined) query.set('effectiveOnly', String(params.effectiveOnly))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<ConsentTemplateList>(`/v1/anesthesia/consent-templates${suffix}`)
}

/** 按手术申请查询同意记录与申请上下文；申请不可见 403（集合语义）。 */
export function listConsents(applicationId: string): Promise<ConsentRecordList> {
  const query = new URLSearchParams({ applicationId })
  return http.get<ConsentRecordList>(`/v1/anesthesia/consents?${query.toString()}`)
}

/** 创建同意记录（WAITING_SIGN）；同一申请 + 模板版本重复创建 409。 */
export function createConsent(payload: CreateConsentRequest): Promise<ConsentRecord> {
  return http.post<ConsentRecord>('/v1/anesthesia/consents', payload)
}

/** 签署（CA 占位，caStatus=SIMULATED）；重复签署/版本过期 409。 */
export function signConsent(
  consentId: string,
  payload: SignConsentRequest,
): Promise<ConsentRecord> {
  return http.post<ConsentRecord>(`/v1/anesthesia/consents/${consentId}/sign`, payload)
}

/** 拒绝（必须填原因）。 */
export function declineConsent(
  consentId: string,
  payload: DeclineConsentRequest,
): Promise<ConsentRecord> {
  return http.post<ConsentRecord>(`/v1/anesthesia/consents/${consentId}/decline`, payload)
}

/** 撤销已签署的同意（必须填原因）。 */
export function revokeConsent(
  consentId: string,
  payload: RevokeConsentRequest,
): Promise<ConsentRecord> {
  return http.post<ConsentRecord>(`/v1/anesthesia/consents/${consentId}/revoke`, payload)
}

/** 按手术申请查询三方核查版本与完成标记；申请不可见 404。 */
export function listThreeWayChecks(applicationId: string): Promise<ApplicationThreeWayCheckList> {
  return http.get<ApplicationThreeWayCheckList>(
    `/v1/anesthesia/applications/${applicationId}/three-way-checks`,
  )
}

/** 录入三方核查（append-only，每次新增版本）。 */
export function recordThreeWayCheck(
  applicationId: string,
  payload: RecordApplicationThreeWayCheckRequest,
): Promise<ApplicationThreeWayCheck> {
  return http.post<ApplicationThreeWayCheck>(
    `/v1/anesthesia/applications/${applicationId}/three-way-checks`,
    payload,
  )
}

/* ---------------------------------------------------------------------------
 * 麻醉记录单与时间轴（anesthesia.yaml 0.5.0，M3-M2-01）
 * 事件/用药/出入量 append-only；麻精类用药必须双人核对；记录单未锁定时可改（乐观锁 version）。
 * ------------------------------------------------------------------------- */

export type AnesthesiaRecordDetail = anesthesiaComponents['schemas']['AnesthesiaRecord']
export type AnesthesiaRecordList = anesthesiaComponents['schemas']['AnesthesiaRecordList']
export type CreateAnesthesiaRecordRequest =
  anesthesiaComponents['schemas']['CreateAnesthesiaRecordRequest']
export type UpdateAnesthesiaRecordRequest =
  anesthesiaComponents['schemas']['UpdateAnesthesiaRecordRequest']
export type RecordEvent = anesthesiaComponents['schemas']['RecordEvent']
export type RecordEventList = anesthesiaComponents['schemas']['RecordEventList']
export type CreateRecordEventRequest = anesthesiaComponents['schemas']['CreateRecordEventRequest']
export type DrugAdministration = anesthesiaComponents['schemas']['DrugAdministration']
export type DrugAdministrationList = anesthesiaComponents['schemas']['DrugAdministrationList']
export type AdministerDrugRequest = anesthesiaComponents['schemas']['AdministerDrugRequest']
export type FluidRecord = anesthesiaComponents['schemas']['FluidRecord']
export type FluidRecordList = anesthesiaComponents['schemas']['FluidRecordList']
export type RecordFluidRequest = anesthesiaComponents['schemas']['RecordFluidRequest']
export type IntakeOutputSummary = anesthesiaComponents['schemas']['IntakeOutputSummary']
export type RecordEventType = RecordEvent['eventType']
export type FluidDirection = FluidRecord['direction']

/** 事件类型中文标签；CUSTOM 为自定义关键事件。 */
export const recordEventLabels: Record<string, string> = {
  ANESTHESIA_START: '麻醉开始',
  INDUCTION: '诱导',
  INTUBATION: '插管',
  INCISION: '切皮',
  CUSTOM: '关键事件',
  EXTUBATION: '拔管',
  ANESTHESIA_END: '麻醉结束',
}

/** 按台次查询记录单（0 或 1 条）；台次不可见 403。 */
export function listAnesthesiaRecords(scheduleId: string): Promise<AnesthesiaRecordList> {
  const query = new URLSearchParams({ scheduleId })
  return http.get<AnesthesiaRecordList>(`/v1/anesthesia/records?${query.toString()}`)
}

/** 按台次创建记录单（DRAFT）；重复创建 409，台次不可见 422。 */
export function createAnesthesiaRecord(
  payload: CreateAnesthesiaRecordRequest,
): Promise<AnesthesiaRecordDetail> {
  return http.post<AnesthesiaRecordDetail>('/v1/anesthesia/records', payload)
}

/** 记录单详情；不存在或越界 404。 */
export function getAnesthesiaRecord(recordId: string): Promise<AnesthesiaRecordDetail> {
  return http.get<AnesthesiaRecordDetail>(`/v1/anesthesia/records/${recordId}`)
}

/** 保存记录单（未锁定可改；必须回传 version，过期 409）。 */
export function updateAnesthesiaRecord(
  recordId: string,
  payload: UpdateAnesthesiaRecordRequest,
): Promise<AnesthesiaRecordDetail> {
  return http.put<AnesthesiaRecordDetail>(`/v1/anesthesia/records/${recordId}`, payload)
}

/** 时间轴事件（occurredAt 升序，同刻按 seq）。 */
export function listRecordEvents(recordId: string): Promise<RecordEventList> {
  return http.get<RecordEventList>(`/v1/anesthesia/records/${recordId}/events`)
}

/** 追加时间轴事件（append-only）；早于记录开始 400。 */
export function appendRecordEvent(
  recordId: string,
  payload: CreateRecordEventRequest,
): Promise<RecordEvent> {
  return http.post<RecordEvent>(`/v1/anesthesia/records/${recordId}/events`, payload)
}

/** 用药记录（按给药时间倒序）。 */
export function listDrugAdministrations(recordId: string): Promise<DrugAdministrationList> {
  return http.get<DrugAdministrationList>(`/v1/anesthesia/records/${recordId}/drugs`)
}

/** 记录用药：麻精类必须双人核对（缺失/同人 400）。 */
export function recordDrugAdministration(
  recordId: string,
  payload: AdministerDrugRequest,
): Promise<DrugAdministration> {
  return http.post<DrugAdministration>(`/v1/anesthesia/records/${recordId}/drugs`, payload)
}

/** 输液/出入量明细（按记录时间倒序）。 */
export function listFluidRecords(recordId: string): Promise<FluidRecordList> {
  return http.get<FluidRecordList>(`/v1/anesthesia/records/${recordId}/fluids`)
}

/** 记录入量/出量（amountMl 必须大于 0）。 */
export function recordFluidRecord(
  recordId: string,
  payload: RecordFluidRequest,
): Promise<FluidRecord> {
  return http.post<FluidRecord>(`/v1/anesthesia/records/${recordId}/fluids`, payload)
}

/** 出入量汇总（服务端计算，balanceMl = intakeMl - outputMl）。 */
export function getIntakeOutputSummary(recordId: string): Promise<IntakeOutputSummary> {
  return http.get<IntakeOutputSummary>(`/v1/anesthesia/records/${recordId}/io-summary`)
}
