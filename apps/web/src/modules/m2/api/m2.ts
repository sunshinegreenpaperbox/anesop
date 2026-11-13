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
