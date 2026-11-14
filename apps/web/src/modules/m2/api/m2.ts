import type { components as anesthesiaComponents } from '@anesop/contracts/anesthesia'
import type { components as drugComponents } from '@anesop/contracts/drug'
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

/** 药品字典联想项（drug.yaml 0.1.0 生成类型）；麻精标志只读、来自字典（T-4）。 */
export type DrugDictionaryItem = drugComponents['schemas']['Drug']

/**
 * 用药联想/解析：命中药品名称、通用名或别名（服务端 trim + 忽略大小写）。
 * 页面用返回值把自由文本解析为字典项，麻精标志不由用户填写。
 */
export async function searchDrugDictionary(q: string, limit = 20): Promise<DrugDictionaryItem[]> {
  const query = new URLSearchParams({ q, limit: String(limit) })
  const list = await http.get<{ items: DrugDictionaryItem[] }>(
    `/v1/drugs/search?${query.toString()}`,
  )
  return list.items
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

/* ---------------------------------------------------------------------------
 * 术中预警与抢救记录（anesthesia.yaml 0.7.0，M3-M2-02）
 * 规则版本化（保存=新版本）；告警由采集落库事件实时评估、前端 5–10s 轮询兜底，
 * 手动 evaluate 仅调试/补偿；抢救记录 append-only 并留痕负责人/参与人员。
 * 临床阈值默认值标【待确认】，以临床顾问确认为准。
 * ------------------------------------------------------------------------- */

export type AlertRule = anesthesiaComponents['schemas']['AlertRule']
export type AlertRuleList = anesthesiaComponents['schemas']['AlertRuleList']
export type UpdateAlertRuleRequest = anesthesiaComponents['schemas']['UpdateAlertRuleRequest']
export type Alert = anesthesiaComponents['schemas']['Alert']
export type AlertList = anesthesiaComponents['schemas']['AlertList']
export type AcknowledgeAlertRequest = anesthesiaComponents['schemas']['AcknowledgeAlertRequest']
export type AlertEvaluationResult = anesthesiaComponents['schemas']['AlertEvaluationResult']
export type ResuscitationRecord = anesthesiaComponents['schemas']['ResuscitationRecord']
export type ResuscitationRecordList = anesthesiaComponents['schemas']['ResuscitationRecordList']
export type CreateResuscitationRequest =
  anesthesiaComponents['schemas']['CreateResuscitationRequest']
export type ResuscitationDrug = anesthesiaComponents['schemas']['ResuscitationDrug']
export type ResuscitationParticipant = anesthesiaComponents['schemas']['ResuscitationParticipant']
export type ResuscitationEventSummary = anesthesiaComponents['schemas']['ResuscitationEventSummary']
export type AlertSeverity = Alert['severity']
export type AlertStatus = Alert['status']
export type AlertMetric = Alert['metric']
export type ResusType = ResuscitationRecord['resusType']
export type ResusOutcome = ResuscitationRecord['outcome']

/** 指标中文标签（单位由服务端返回的 unit 展示）。 */
export const alertMetricLabels: Record<string, string> = {
  HR: '心率',
  SPO2: '血氧饱和度',
  MAP: '平均动脉压',
  ETCO2: '呼气末二氧化碳',
  TEMP: '体温',
}

/** 严重度中文标签。 */
export const alertSeverityLabels: Record<string, string> = {
  INFO: '提示',
  WARN: '警告',
  CRITICAL: '严重',
}

/** 处理状态中文标签。 */
export const alertStatusLabels: Record<string, string> = {
  TRIGGERED: '待处理',
  ACKNOWLEDGED: '已确认',
  IGNORED: '已忽略',
}

/** 抢救类型中文标签（枚举口径【待确认】）。 */
export const resusTypeLabels: Record<string, string> = {
  CPR: '心肺复苏',
  AIRWAY: '气道干预',
  VASOPRESSOR: '血管活性药支持',
  DEFIBRILLATION: '电除颤',
  OTHER: '其他',
}

/** 抢救转归中文标签（【待确认】）。 */
export const resusOutcomeLabels: Record<string, string> = {
  SUCCESS: '复苏成功',
  PARTIAL: '部分改善',
  FAILED: '未成功',
  UNKNOWN: '未记录',
}

/** 规则列表：每个规则编码返回当前版本（含启用状态）。 */
export function listAlertRules(): Promise<AlertRuleList> {
  return http.get<AlertRuleList>('/v1/anesthesia/alert-rules')
}

/** 规则历史版本（version 倒序，用于告警 ruleVersion 追溯）。 */
export function listAlertRuleVersions(ruleId: string): Promise<AlertRuleList> {
  return http.get<AlertRuleList>(`/v1/anesthesia/alert-rules/${ruleId}/versions`)
}

/** 保存规则：生成新版本并停用旧版本；必须回传当前 version（过期 409）。 */
export function updateAlertRule(
  ruleId: string,
  payload: UpdateAlertRuleRequest,
): Promise<AlertRule> {
  return http.put<AlertRule>(`/v1/anesthesia/alert-rules/${ruleId}`, payload)
}

/** 记录单告警列表（只读；实时告警由采集落库事件评估产生）。 */
export function listRecordAlerts(recordId: string): Promise<AlertList> {
  return http.get<AlertList>(`/v1/anesthesia/records/${recordId}/alerts`)
}

/** 手动评估（调试/采集补偿）：按水位只处理新采样，不重评历史窗口。 */
export function evaluateRecordAlerts(recordId: string): Promise<AlertEvaluationResult> {
  return http.post<AlertEvaluationResult>(`/v1/anesthesia/records/${recordId}/alerts/evaluate`)
}

/** 确认/忽略告警：只允许一次流转，重复处理 409；reason 必填。 */
export function acknowledgeAlert(
  alertId: string,
  payload: AcknowledgeAlertRequest,
): Promise<Alert> {
  return http.post<Alert>(`/v1/anesthesia/alerts/${alertId}/acknowledge`, payload)
}

/** 抢救记录列表（按开始时间倒序）。 */
export function listResuscitations(recordId: string): Promise<ResuscitationRecordList> {
  return http.get<ResuscitationRecordList>(`/v1/anesthesia/records/${recordId}/resuscitations`)
}

/** 新增抢救记录（append-only；时间窗必须落在记录单时间窗内）。 */
export function createResuscitation(
  recordId: string,
  payload: CreateResuscitationRequest,
): Promise<ResuscitationRecord> {
  return http.post<ResuscitationRecord>(
    `/v1/anesthesia/records/${recordId}/resuscitations`,
    payload,
  )
}

/* ---------------------------------------------------------------------------
 * 锁单、修订与 CA 占位签名（anesthesia.yaml 0.8.0，M3-M2-03）
 * 锁单前置清单由服务端评估（前端只展示）；锁单后记录只读，修正只能新增修订版本。
 * CA 不可用（EXT-05）：锁单/修订签名为 SIMULATED 占位，不得表述为真实 CA 有效签名。
 * ------------------------------------------------------------------------- */

export type RecordLockStatus = anesthesiaComponents['schemas']['RecordLockStatus']
export type RecordEffectiveView = anesthesiaComponents['schemas']['RecordEffectiveView']
export type LockPrecondition = anesthesiaComponents['schemas']['LockPrecondition']
export type LockRecordRequest = anesthesiaComponents['schemas']['LockRecordRequest']
export type LockRecordResult = anesthesiaComponents['schemas']['LockRecordResult']
export type CreateRevisionRequest = anesthesiaComponents['schemas']['CreateRevisionRequest']
export type RecordRevisionSummary = anesthesiaComponents['schemas']['RecordRevisionSummary']
export type RecordRevisionList = anesthesiaComponents['schemas']['RecordRevisionList']
export type RecordRevision = anesthesiaComponents['schemas']['RecordRevision']
export type RevisionDiff = anesthesiaComponents['schemas']['RevisionDiff']
export type RevisionFieldChange = anesthesiaComponents['schemas']['RevisionFieldChange']
export type RevisionCorrections = anesthesiaComponents['schemas']['RevisionCorrections']
export type RevisionReasonCode = RecordRevisionSummary['reasonCode']
export type RevisionCaStatus = RecordRevisionSummary['caStatus']

/** 修订原因码中文标签（白名单与契约一致）。 */
export const revisionReasonLabels: Record<string, string> = {
  ANESTHESIA_TYPE_CHANGE: '更正麻醉方式',
  OTHER: '其他（须在原因中说明）',
}

/** 查询锁单状态与前置条件清单（服务端评估；前端不得自行推导）。 */
export function getRecordLockStatus(recordId: string): Promise<RecordLockStatus> {
  return http.get<RecordLockStatus>(`/v1/anesthesia/records/${recordId}/lock-status`)
}

/** 锁单：必须回传 version 并确认前置清单；重复锁单/前置未满足 409（details 列出条件）。 */
export function lockAnesthesiaRecord(
  recordId: string,
  payload: LockRecordRequest,
): Promise<LockRecordResult> {
  return http.post<LockRecordResult>(`/v1/anesthesia/records/${recordId}/lock`, payload)
}

/**
 * 当前有效记录值（锁单原始值 + 最新修订更正；读时投影，不改原记录行）。
 * 详情页/打印使用 effective，原记录与历史修订仍保持不可变。
 */
export function getEffectiveAnesthesiaRecord(recordId: string): Promise<RecordEffectiveView> {
  return http.get<RecordEffectiveView>(`/v1/anesthesia/records/${recordId}/effective`)
}

/** 修订版本列表（revisionNo 升序；未锁单/无修订为空数组）。 */
export function listRecordRevisions(recordId: string): Promise<RecordRevisionList> {
  return http.get<RecordRevisionList>(`/v1/anesthesia/records/${recordId}/revisions`)
}

/** 修订版本详情（快照 + 签名摘要 + 与上一版差异）。 */
export function getRecordRevision(recordId: string, revisionNo: number): Promise<RecordRevision> {
  return http.get<RecordRevision>(`/v1/anesthesia/records/${recordId}/revisions/${revisionNo}`)
}

/** 创建修订版本（锁单后修正唯一入口）：原因码白名单、原因/变更摘要必填。 */
export function createRecordRevision(
  recordId: string,
  payload: CreateRevisionRequest,
): Promise<RecordRevision> {
  return http.post<RecordRevision>(`/v1/anesthesia/records/${recordId}/revisions`, payload)
}

/* ---------------------------------------------------------------------------
 * PACU 复苏记录与转出（anesthesia.yaml 0.9.0，M3-M10-01）
 * 入室 → Aldrete 评估（服务端算总分）→ 转出（去向 + SBAR + 达标确认，阈值不足 409）。
 * 转出阈值由服务端配置（默认 9，【待确认】），前端只展示服务端结论，不自行判定。
 * ------------------------------------------------------------------------- */

export type PacuVitalSigns = anesthesiaComponents['schemas']['PacuVitalSigns']
export type CreatePacuRecordRequest = anesthesiaComponents['schemas']['CreatePacuRecordRequest']
export type PacuRecord = anesthesiaComponents['schemas']['PacuRecord']
export type PacuRecordList = anesthesiaComponents['schemas']['PacuRecordList']
export type RecordPacuAssessmentRequest =
  anesthesiaComponents['schemas']['RecordPacuAssessmentRequest']
export type PacuAssessment = anesthesiaComponents['schemas']['PacuAssessment']
export type PacuAssessmentList = anesthesiaComponents['schemas']['PacuAssessmentList']
export type PacuHandover = anesthesiaComponents['schemas']['PacuHandover']
export type PacuDischargeRequest = anesthesiaComponents['schemas']['PacuDischargeRequest']
export type PacuDischargeCriteria = anesthesiaComponents['schemas']['PacuDischargeCriteria']
export type PacuDischarge = anesthesiaComponents['schemas']['PacuDischarge']
export type PacuStatus = PacuRecord['status']
export type PacuConsciousness = PacuRecord['consciousness']
export type PacuAirway = PacuRecord['airway']
export type PacuDestination = PacuDischarge['destination']

/** 复苏状态中文标签。 */
export const pacuStatusLabels: Record<string, string> = {
  ADMITTED: '已入室',
  MONITORING: '监测中',
  READY: '已达转出标准',
  DISCHARGED: '已转出',
}

/** 入室意识中文标签（枚举为演示口径【待确认】）。 */
export const pacuConsciousnessLabels: Record<string, string> = {
  ALERT: '清醒',
  DROWSY: '嗜睡',
  SEDATED: '镇静',
  UNRESPONSIVE: '无反应',
}

/** 入室气道中文标签（枚举为演示口径【待确认】）。 */
export const pacuAirwayLabels: Record<string, string> = {
  NATURAL: '自然气道',
  ORAL_AIRWAY: '口咽通气道',
  NASAL_AIRWAY: '鼻咽通气道',
  LARYNGEAL_MASK: '喉罩',
  ENDOTRACHEAL: '气管插管',
  TRACHEOSTOMY: '气管切开',
}

/** 转出去向中文标签（枚举为演示口径【待确认】）。 */
export const pacuDestinationLabels: Record<string, string> = {
  WARD: '病房',
  ICU: 'ICU',
  HDU: 'HDU（过渡监护）',
  DAY_SURGERY: '日间手术区',
  HOME: '离院',
}

/** 按麻醉记录单查询复苏记录（创建时间倒序；记录单不可见 403）。 */
export function listPacuRecords(recordId: string): Promise<PacuRecordList> {
  const query = new URLSearchParams({ recordId })
  return http.get<PacuRecordList>(`/v1/anesthesia/pacu-records?${query.toString()}`)
}

/** 入室（创建复苏记录）：记录单未开始 409、不可见 422、重复未转出记录 409。 */
export function admitPacuRecord(payload: CreatePacuRecordRequest): Promise<PacuRecord> {
  return http.post<PacuRecord>('/v1/anesthesia/pacu-records', payload)
}

/** 复苏记录详情（含最近一次 Aldrete 总分与服务端转出阈值）。 */
export function getPacuRecord(pacuId: string): Promise<PacuRecord> {
  return http.get<PacuRecord>(`/v1/anesthesia/pacu-records/${pacuId}`)
}

/** Aldrete 评估时间序列（assessedAt 升序）。 */
export function listPacuAssessments(pacuId: string): Promise<PacuAssessmentList> {
  return http.get<PacuAssessmentList>(`/v1/anesthesia/pacu-records/${pacuId}/assessments`)
}

/** 记录 Aldrete 评估（总分由服务端计算；越界 400、已转出 409）。 */
export function recordPacuAssessment(
  pacuId: string,
  payload: RecordPacuAssessmentRequest,
): Promise<PacuAssessment> {
  return http.post<PacuAssessment>(`/v1/anesthesia/pacu-records/${pacuId}/assessments`, payload)
}

/** 转出记录（未转出 404）。 */
export function getPacuDischarge(pacuId: string): Promise<PacuDischarge> {
  return http.get<PacuDischarge>(`/v1/anesthesia/pacu-records/${pacuId}/discharge`)
}

/** 转出：去向 + SBAR + 达标确认；Aldrete 未达标/未评估 409（details 列出条件）。 */
export function dischargePacuRecord(
  pacuId: string,
  payload: PacuDischargeRequest,
): Promise<PacuDischarge> {
  return http.post<PacuDischarge>(`/v1/anesthesia/pacu-records/${pacuId}/discharge`, payload)
}

/* ---------------------------------------------------------------------------
 * 术后随访与镇痛（anesthesia.yaml 0.10.0，M3-M11-01）
 * 随访 H24/H48（NRS 0–10、PONV 0–3、并发症多选 + 反馈；记录单结束后录入、append-only）；
 * 镇痛 PCIA/PCEA/口服/静脉/神经阻滞（bolus/background/lockout、效果、调整记录）。
 * 并发症字典、PONV 分级、随访窗口与 PCA 默认值均为【待确认】演示口径；自由文本脱敏后落库。
 * ------------------------------------------------------------------------- */

export type PostopFollowup = anesthesiaComponents['schemas']['PostopFollowup']
export type PostopFollowupList = anesthesiaComponents['schemas']['PostopFollowupList']
export type CreatePostopFollowupRequest =
  anesthesiaComponents['schemas']['CreatePostopFollowupRequest']
export type FollowupType = anesthesiaComponents['schemas']['FollowupType']
export type FollowupComplication = anesthesiaComponents['schemas']['FollowupComplication']
export type PainManagement = anesthesiaComponents['schemas']['PainManagement']
export type PainManagementList = anesthesiaComponents['schemas']['PainManagementList']
export type PainManagementModality = anesthesiaComponents['schemas']['PainManagementModality']
export type PainManagementEffect = anesthesiaComponents['schemas']['PainManagementEffect']
export type PainAdjustment = anesthesiaComponents['schemas']['PainAdjustment']
export type RecordPainManagementRequest =
  anesthesiaComponents['schemas']['RecordPainManagementRequest']

/** 随访类型中文标签。 */
export const followupTypeLabels: Record<string, string> = {
  H24: '术后 24 小时',
  H48: '术后 48 小时',
}

/** 并发症中文标签（演示字典【待确认】）。 */
export const followupComplicationLabels: Record<string, string> = {
  PONV: '恶心/呕吐（PONV）',
  PAIN_INADEQUATE: '镇痛不全',
  RESPIRATORY_DEPRESSION: '呼吸抑制',
  AIRWAY_INJURY: '气道损伤',
  HYPOTENSION: '低血压',
  HYPERTENSION: '高血压',
  ARRHYTHMIA: '心律失常',
  SHIVERING: '寒战',
  DELAYED_AWAKENING: '苏醒延迟',
  NERVE_INJURY: '神经损伤',
  SURGICAL_BLEEDING: '手术部位出血',
  INFECTION: '感染',
  OTHER: '其他',
}

/** 镇痛方式中文标签（演示口径【待确认】）。 */
export const painManagementModalityLabels: Record<string, string> = {
  PCIA: 'PCIA（自控静脉）',
  PCEA: 'PCEA（自控硬膜外）',
  ORAL: '口服',
  IV: '静脉',
  NERVE_BLOCK: '神经阻滞',
  OTHER: '其他',
}

/** 镇痛效果中文标签（演示口径【待确认】）。 */
export const painManagementEffectLabels: Record<string, string> = {
  EFFECTIVE: '有效',
  PARTIAL: '部分有效',
  INEFFECTIVE: '无效',
  NOT_ASSESSED: '未评估',
}

/** 按麻醉记录单查询随访（assessedAt 倒序；记录单不可见 403）。 */
export function listFollowups(recordId: string): Promise<PostopFollowupList> {
  const query = new URLSearchParams({ recordId })
  return http.get<PostopFollowupList>(`/v1/anesthesia/followups?${query.toString()}`)
}

/** 录入随访（append-only）：记录单未结束 409、时间/评分越界 400、不可见 422。 */
export function createFollowup(payload: CreatePostopFollowupRequest): Promise<PostopFollowup> {
  return http.post<PostopFollowup>('/v1/anesthesia/followups', payload)
}

/** 随访详情（不存在/越界 404）。 */
export function getFollowup(followupId: string): Promise<PostopFollowup> {
  return http.get<PostopFollowup>(`/v1/anesthesia/followups/${followupId}`)
}

/** 该随访的镇痛记录（录入时间升序）。 */
export function listPainManagement(followupId: string): Promise<PainManagementList> {
  return http.get<PainManagementList>(`/v1/anesthesia/followups/${followupId}/pain-management`)
}

/** 追加镇痛记录（append-only）：参数越界 400、随访不存在/越界 404。 */
export function recordPainManagement(
  followupId: string,
  payload: RecordPainManagementRequest,
): Promise<PainManagement> {
  return http.post<PainManagement>(
    `/v1/anesthesia/followups/${followupId}/pain-management`,
    payload,
  )
}
