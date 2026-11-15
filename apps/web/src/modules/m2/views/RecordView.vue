<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { components as authComponents } from '@anesop/contracts/auth'
import { ApiError, http } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  acknowledgeAlert,
  alertMetricLabels,
  alertSeverityLabels,
  alertStatusLabels,
  appendRecordEvent,
  createAnesthesiaRecord,
  createRecordRevision,
  createResuscitation,
  evaluateRecordAlerts,
  getEffectiveAnesthesiaRecord,
  getIntakeOutputSummary,
  getRecordLockStatus,
  getRecordRevision,
  listAnesthesiaRecords,
  listDrugAdministrations,
  listFluidRecords,
  listRecordAlerts,
  listRecordEvents,
  listRecordRevisions,
  listResuscitations,
  lockAnesthesiaRecord,
  recordDrugAdministration,
  recordFluidRecord,
  recordEventLabels,
  resusOutcomeLabels,
  searchDrugDictionary,
  resusTypeLabels,
  revisionReasonLabels,
  type Alert,
  type AnesthesiaRecordDetail,
  type CreateRecordEventRequest,
  type CreateResuscitationRequest,
  type CreateRevisionRequest,
  type DrugAdministration,
  type DrugDictionaryItem,
  type FluidDirection,
  type FluidRecord,
  type IntakeOutputSummary,
  type LockPrecondition,
  type RecordEffectiveView,
  type RecordEvent,
  type RecordEventType,
  type RecordLockStatus,
  type RecordRevision,
  type RecordRevisionSummary,
  type ResuscitationDrug,
  type ResuscitationRecord,
  type ResusOutcome,
  type ResusType,
  type RevisionReasonCode,
} from '../api/m2'

function nowWithOffset(): string {
  const date = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  const offsetMinutes = -date.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const absolute = Math.abs(offsetMinutes)
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
  )
}

const EVENT_TYPES: RecordEventType[] = [
  'ANESTHESIA_START',
  'INDUCTION',
  'INTUBATION',
  'INCISION',
  'CUSTOM',
  'EXTUBATION',
  'ANESTHESIA_END',
]
const ANESTHESIA_TYPES = [
  { value: 'GENERAL', label: '全身麻醉' },
  { value: 'REGIONAL', label: '区域阻滞' },
  { value: 'COMBINED', label: '联合麻醉' },
  { value: 'LOCAL', label: '局部麻醉' },
  { value: 'SEDATION', label: '镇静/监护麻醉' },
] as const

/** 候选核对人只取脱敏用户摘要（auth.yaml UserSummary，不含手机号明文）。 */
type UserSummaryPage = authComponents['schemas']['UserSummaryPage']
type CheckerCandidate = { userId: string; username: string; displayName: string }

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const scheduleId = computed(() => {
  const value = route.params.scheduleId
  const raw = Array.isArray(value) ? value[0] : value
  return raw ?? ''
})

const record = ref<AnesthesiaRecordDetail | null>(null)
const events = ref<RecordEvent[]>([])
const drugs = ref<DrugAdministration[]>([])
const fluids = ref<FluidRecord[]>([])
const summary = ref<IntakeOutputSummary | null>(null)
const alerts = ref<Alert[]>([])
const resuscitations = ref<ResuscitationRecord[]>([])

/** 告警确认/忽略：先收集原因再提交（reason 必填，服务端审计不记录原文）。 */
const ackDialog = reactive({
  visible: false,
  alertId: '',
  decision: 'CONFIRMED' as 'CONFIRMED' | 'IGNORED',
  reason: '',
})

const resusForm = reactive({
  resusType: 'CPR' as ResusType,
  startedAt: nowWithOffset(),
  endedAt: nowWithOffset(),
  summary: '',
  interventions: '',
  // 人员留痕（V16/B-4）：负责人必填，参与人员至少 1 人；默认带出当前登录人。
  leaderName: auth.displayName,
  leaderUserId: auth.user?.userId ?? '',
  rescuers: '',
  outcome: 'SUCCESS' as ResusOutcome,
})

const resusDrugs = ref<ResuscitationDrug[]>([])
const resusDrugForm = reactive({
  drugName: '',
  dose: 1,
  unit: 'mg',
  route: 'IV',
  narcotic: false,
  secondCheckerId: '',
  secondCheckerName: '',
})
/* M9-01：抢救用药接药品字典联想；麻精判定以服务端字典为准，前端只做提前提示。 */
const resusDrugDictionaryMatch = ref<DrugDictionaryItem | null>(null)
const loading = ref(false)
const saving = ref(false)
const evaluating = ref(false)
const alertUnavailable = ref(false)
const resusUnavailable = ref(false)
const error = ref<ApiError | null>(null)

/* 锁单 / 修订 / 版本历史（M3-M2-03，anesthesia 0.8.0；CA 占位 EXT-05） */
const lockStatus = ref<RecordLockStatus | null>(null)
/** 当前有效值投影（锁单原始值 + 最新修订更正；详情页展示用，原行不可变）。 */
const effective = ref<RecordEffectiveView | null>(null)
const lockDialog = reactive({
  visible: false,
  loading: false,
  confirm: false,
})
const revisions = ref<RecordRevisionSummary[]>([])
const revisionsLoaded = ref(false)
const revisionsLoading = ref(false)
const revisionDialog = reactive({
  visible: false,
  reasonCode: 'ANESTHESIA_TYPE_CHANGE' as RevisionReasonCode,
  reason: '',
  changes: '',
  anesthesiaType: '',
})
const revisionDetail = ref<RecordRevision | null>(null)
const revisionDetailVisible = ref(false)
const revisionDetailLoading = ref(false)
const revisionUnavailable = ref(false)

/** 告警轮询间隔（评审 B-1：5–10s；实时触发为主，轮询仅兜底）。 */
const ALERT_POLL_INTERVAL_MS = 8000
let alertTimer: number | null = null

const createForm = reactive({
  anesthesiaType: 'GENERAL' as (typeof ANESTHESIA_TYPES)[number]['value'],
})

const eventForm = reactive({
  eventType: 'INDUCTION' as RecordEventType,
  occurredAt: nowWithOffset(),
  note: '',
  // 补录原因：事件时间早于最新事件或早于服务端容差窗口时必填（400 PARAM_INVALID）。
  entryReason: '',
})

const drugForm = reactive({
  drugName: '',
  dose: 10,
  unit: 'mg',
  route: 'IV',
  administeredAt: nowWithOffset(),
  narcotic: false,
  secondCheckerId: '',
  secondCheckerName: '',
  note: '',
})

/* S-4：用药名称接 /v1/drugs/search 字典联想；麻精标志只读、来自字典（T-4），
 * 保留自由文本时提交前解析，未收录/歧义直接提示，不把客户端声明当作判定依据。 */
const drugDictionaryMatch = ref<DrugDictionaryItem | null>(null)

/* M8-QA-01（E2E-05）：第二位核对人下拉。候选来自 /v1/auth/users（需 m0:user:read，
 * 仅用户管理只读角色可用）；无权限或加载失败时降级为手工填写标识与姓名。
 * 服务端仍强制校验：存在/在职/非本人/持 m2:record:write，前端校验只做提前提示。 */
const checkerCandidates = ref<CheckerCandidate[]>([])
const checkerCandidatesLoading = ref(false)
const checkerUnavailable = ref(false)
const canListCheckers = computed(() => auth.hasPermission('m0:user:read'))
const currentUserId = computed(() => auth.user?.userId ?? '')

async function loadCheckerCandidates(): Promise<void> {
  if (!canListCheckers.value || checkerCandidates.value.length > 0) return
  checkerCandidatesLoading.value = true
  try {
    const page = await http.get<UserSummaryPage>('/v1/auth/users?page=1&size=200')
    checkerCandidates.value = page.items
      .filter((item) => item.status === 'ACTIVE')
      .map((item) => ({
        userId: item.userId,
        username: item.username,
        displayName: item.displayName,
      }))
    checkerUnavailable.value = false
  } catch {
    // 403/网络失败不阻断记录单：保留手工填写路径，由服务端做资格校验。
    checkerUnavailable.value = true
  } finally {
    checkerCandidatesLoading.value = false
  }
}

/** 选择候选核对人后回填姓名，避免标识与姓名不一致。 */
function applyCheckerCandidate(userId: string): void {
  drugForm.secondCheckerId = userId
  drugForm.secondCheckerName =
    checkerCandidates.value.find((item) => item.userId === userId)?.displayName ?? ''
}

/** 给药人展示：候选中可解析显示名时优先，否则回退用户标识（审计口径，不臆造姓名）。 */
function operatorLabel(userId: string | null | undefined): string {
  if (!userId) return '—'
  return checkerCandidates.value.find((item) => item.userId === userId)?.displayName ?? userId
}

function normalizedDrugName(value: string): string {
  return value.trim().toLocaleLowerCase()
}

async function fetchDrugSuggestions(
  query: string,
  callback: (items: { value: string; drug: DrugDictionaryItem }[]) => void,
): Promise<void> {
  const keyword = query.trim()
  if (!keyword) {
    callback([])
    return
  }
  try {
    const items = await searchDrugDictionary(keyword)
    callback(items.map((drug) => ({ value: drug.name, drug })))
  } catch {
    callback([])
  }
}

function applyDictionaryDrug(drug: DrugDictionaryItem): void {
  drugForm.drugName = drug.name
  drugForm.narcotic = drug.narcotic
  drugForm.unit = drug.unit || drugForm.unit
  drugDictionaryMatch.value = drug
}

function handleDrugSelect(item: { value: string; drug: DrugDictionaryItem }): void {
  applyDictionaryDrug(item.drug)
}

function handleDrugInput(value: string): void {
  if (
    !drugDictionaryMatch.value ||
    normalizedDrugName(drugDictionaryMatch.value.name) !== normalizedDrugName(value)
  ) {
    drugDictionaryMatch.value = null
  }
}

/** 提交前解析：按名称/通用名/别名（trim + 忽略大小写）精确匹配，0/多候选都拒绝。 */
async function resolveDictionaryDrugs(): Promise<DrugDictionaryItem[]> {
  const keyword = drugForm.drugName.trim()
  if (!keyword) return []
  const items = await searchDrugDictionary(keyword)
  return items.filter((drug) =>
    [drug.name, drug.genericName ?? '', ...(drug.aliases ?? [])].some(
      (candidate) => normalizedDrugName(candidate) === normalizedDrugName(keyword),
    ),
  )
}

const fluidForm = reactive({
  direction: 'INTAKE' as FluidDirection,
  fluidType: '',
  amountMl: 500,
  note: '',
})

const canWrite = computed(() => auth.hasPermission('m2:record:write'))
const canAck = computed(() => auth.hasPermission('m2:alert:ack'))
const canRescue = computed(() => auth.hasPermission('m2:rescue:write'))
const canLock = computed(() => auth.hasPermission('m2:record:lock'))
const canRevise = computed(() => auth.hasPermission('m2:record:revise'))
/** S-2：PACU 入口按 m2:pacu:read 显示；缺权限时隐藏，避免 403 跳转。 */
const canPacuRead = computed(() => auth.hasPermission('m2:pacu:read'))
/** M3-M11-01：术后随访入口按 m2:followup:read 显示；缺权限时隐藏，避免 403 跳转。 */
const canFollowupRead = computed(() => auth.hasPermission('m2:followup:read'))
/** 权限隔离（S-3）：缺 alert:read / rescue 权限时跳过对应请求，不影响时间轴/用药/出入量。 */
const canAlertRead = computed(() => auth.hasPermission('m2:alert:read'))
const canRescueView = computed(() => canRescue.value || auth.hasPermission('m2:record:write'))
const pendingAlertCount = computed(
  () => alerts.value.filter((item) => item.status === 'TRIGGERED').length,
)
/** 锁单后记录只读：所有书写表单隐藏，修正只能新增修订版本。 */
const locked = computed(() => record.value?.lockedAt != null)
/** 当前有效麻醉方式：有修订时取 effective 投影，否则取原记录。 */
const effectiveAnesthesiaType = computed(
  () => effective.value?.effective?.anesthesiaType ?? record.value?.anesthesiaType,
)
const originalAnesthesiaType = computed(
  () => effective.value?.original?.anesthesiaType ?? record.value?.anesthesiaType,
)
const effectiveCorrection = computed(() =>
  (effective.value?.corrections ?? []).find((item) => item.field === 'anesthesiaType'),
)
const editable = computed(() => canWrite.value && record.value !== null && !locked.value)
const busy = computed(() => loading.value || saving.value)
const statusLabel = computed(() => {
  switch (record.value?.status) {
    case 'IN_PROGRESS':
      return '进行中'
    case 'DRAFT':
      return '草稿'
    case 'LOCKED':
      return '已锁单'
    case 'REVISED':
      return '已修订'
    default:
      return ''
  }
})
const signatureHashShort = computed(() => {
  const value = record.value?.signatureHash
  return value && value.length > 16 ? `${value.slice(0, 16)}…` : (value ?? '—')
})
/** 体征展示上限（契约最多 2000 条；页面只渲染前 300 条，避免长记录卡顿）。 */
const VITAL_DISPLAY_LIMIT = 300
const vitalMetricLabels: Record<string, string> = {
  HR: '心率',
  SPO2: '血氧饱和度',
  NIBP_SYS: '收缩压',
  NIBP_DIA: '舒张压',
  ETCO2: '呼气末二氧化碳',
  TEMP: '体温',
}
const vitalSamples = computed(() => (record.value?.vitalSigns ?? []).slice(0, VITAL_DISPLAY_LIMIT))

function vitalMetricLabel(metric: string | undefined): string {
  if (!metric) return '—'
  return vitalMetricLabels[metric] ?? metric
}

function qualityLabel(quality: number | undefined): string {
  switch (quality) {
    case 0:
      return '正常'
    case 1:
      return '可疑'
    case 2:
      return '缺失'
    case 3:
      return '超时'
    default:
      return '—'
  }
}

function qualityTagType(quality: number | undefined): 'success' | 'warning' | 'danger' | 'info' {
  if (quality === 0 || quality === undefined) return 'success'
  if (quality === 1) return 'warning'
  if (quality >= 2) return 'danger'
  return 'info'
}

const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权访问麻醉记录（需要 m2:record:read）'
  if (error.value.status === 404) return '麻醉记录单不存在或超出数据范围'
  return '麻醉记录单操作失败'
})
const eventTypeOptions = computed(() =>
  EVENT_TYPES.map((type) => ({ value: type, label: recordEventLabels[type] ?? type })),
)

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  if (cause instanceof ApiError) {
    ElMessage.error(cause.message)
  } else {
    ElMessage.error('请求失败，请稍后重试')
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const list = await listAnesthesiaRecords(scheduleId.value)
    record.value = list.items[0] ?? null
    if (!record.value) {
      events.value = []
      drugs.value = []
      fluids.value = []
      summary.value = null
      alerts.value = []
      resuscitations.value = []
      effective.value = null
      return
    }
  } catch (cause) {
    record.value = null
    handleError(cause)
    return
  } finally {
    loading.value = false
  }
  // 明细加载失败不清空记录单本身（保留患者/状态上下文，错误提示可重试）。
  try {
    await loadRecordDetail(record.value.recordId)
  } catch (cause) {
    handleError(cause)
  }
  // 锁单后自动加载修订版本（只读；失败不阻断记录单主视图）。
  if (record.value.lockedAt) {
    await loadRevisions()
  }
}

async function loadRecordDetail(recordId: string): Promise<void> {
  // 权限隔离（S-3）：告警/抢救请求按权限跳过；任一侧失败不阻断时间轴/用药/出入量。
  // 注意：任务在数组内按固定顺序创建，保证请求时序稳定（时间轴→用药→出入量→告警→抢救）。
  const [eventR, drugR, fluidR, ioR, effectiveR, alertR, resusR] = await Promise.allSettled([
    listRecordEvents(recordId),
    listDrugAdministrations(recordId),
    listFluidRecords(recordId),
    getIntakeOutputSummary(recordId),
    getEffectiveAnesthesiaRecord(recordId),
    canAlertRead.value ? listRecordAlerts(recordId) : Promise.resolve(null),
    canRescueView.value ? listResuscitations(recordId) : Promise.resolve(null),
  ])
  if (eventR.status === 'fulfilled') events.value = eventR.value.items
  if (drugR.status === 'fulfilled') drugs.value = drugR.value.items
  if (fluidR.status === 'fulfilled') fluids.value = fluidR.value.items
  if (ioR.status === 'fulfilled') summary.value = ioR.value
  // 有效值投影失败不阻断记录单主视图（回退到原记录值展示）。
  effective.value = effectiveR.status === 'fulfilled' ? effectiveR.value : null
  if (alertR.status === 'fulfilled') {
    alerts.value = alertR.value?.items ?? []
    alertUnavailable.value = false
  } else {
    alerts.value = []
    alertUnavailable.value = true
  }
  if (resusR.status === 'fulfilled') {
    resuscitations.value = resusR.value?.items ?? []
    resusUnavailable.value = false
  } else {
    resuscitations.value = []
    resusUnavailable.value = true
  }
  const coreFailure = [eventR, drugR, fluidR, ioR].find((item) => item.status === 'rejected') as
    PromiseRejectedResult | undefined
  if (coreFailure) {
    throw coreFailure.reason
  }
}

/** 仅刷新告警（轮询兜底；不重取时间轴/用药/出入量）。 */
async function refreshAlerts(): Promise<void> {
  if (!record.value || !canAlertRead.value) return
  try {
    const list = await listRecordAlerts(record.value.recordId)
    alerts.value = list.items
    alertUnavailable.value = false
  } catch {
    // 轮询失败不打断页面（保留已加载告警，仅标记不可用）。
    alertUnavailable.value = true
  }
}

function stopAlertPolling(): void {
  if (alertTimer !== null) {
    window.clearInterval(alertTimer)
    alertTimer = null
  }
}

function startAlertPolling(): void {
  stopAlertPolling()
  if (!canAlertRead.value) return
  alertTimer = window.setInterval(() => {
    void refreshAlerts()
  }, ALERT_POLL_INTERVAL_MS)
}

/** 手动评估（调试/补偿）：服务端按水位只处理新采样，不重评历史窗口。 */
async function evaluateAlerts(): Promise<void> {
  if (!record.value) return
  evaluating.value = true
  try {
    const result = await evaluateRecordAlerts(record.value.recordId)
    ElMessage.success(
      `评估完成：规则 ${result.evaluatedRules} 条、采样 ${result.sampledFrames} 帧、新增告警 ${result.createdAlerts} 条`,
    )
    await refreshAlerts()
  } catch (cause) {
    handleError(cause)
  } finally {
    evaluating.value = false
  }
}

/** 追加事件会改变记录单状态/开始结束时间，刷新必须同时重取记录单本身。 */
async function refresh(): Promise<void> {
  await load()
}

async function createRecord(): Promise<void> {
  saving.value = true
  error.value = null
  try {
    const created = await createAnesthesiaRecord({
      scheduleId: scheduleId.value,
      anesthesiaType: createForm.anesthesiaType,
    })
    record.value = created
    events.value = []
    drugs.value = []
    fluids.value = []
    alerts.value = []
    resuscitations.value = []
    effective.value = null
    summary.value = {
      recordId: created.recordId,
      intakeMl: 0,
      outputMl: 0,
      balanceMl: 0,
      fluidCount: 0,
      computedAt: nowWithOffset(),
    }
    ElMessage.success('麻醉记录单已创建（草稿）')
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitEvent(): Promise<void> {
  if (!record.value) return
  if (!eventForm.occurredAt) {
    ElMessage.warning('请填写事件发生时间')
    return
  }
  const payload: CreateRecordEventRequest = {
    eventType: eventForm.eventType,
    occurredAt: eventForm.occurredAt,
    source: 'MANUAL',
  }
  if (eventForm.note.trim()) {
    payload.note = eventForm.note.trim()
  }
  if (eventForm.entryReason.trim()) {
    payload.entryReason = eventForm.entryReason.trim()
  }
  saving.value = true
  try {
    await appendRecordEvent(record.value.recordId, payload)
    ElMessage.success(
      `已追加事件：${recordEventLabels[eventForm.eventType] ?? eventForm.eventType}`,
    )
    eventForm.note = ''
    eventForm.entryReason = ''
    eventForm.occurredAt = nowWithOffset()
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitDrug(): Promise<void> {
  if (!record.value) return
  if (!drugForm.drugName.trim()) {
    ElMessage.warning('请填写药品名称')
    return
  }
  let matches: DrugDictionaryItem[]
  try {
    matches = await resolveDictionaryDrugs()
  } catch (cause) {
    handleError(cause)
    return
  }
  if (matches.length === 0) {
    ElMessage.warning('药品未收录于药品字典，无法判定麻精属性；请从字典联想中选择')
    return
  }
  if (matches.length > 1) {
    ElMessage.warning('药品名称在字典中命中多个药品（歧义），请改用唯一名称或联系药房')
    return
  }
  const dictionary = matches[0]
  applyDictionaryDrug(dictionary)
  if (drugForm.narcotic) {
    const checkerId = drugForm.secondCheckerId.trim()
    const checkerName = drugForm.secondCheckerName.trim()
    if (!checkerId || !checkerName) {
      ElMessage.warning(
        '麻精药品必须双人核对：请选择第二位核对人（或在下方手工填写标识与姓名，不得为给药人本人）',
      )
      return
    }
    if (checkerId === currentUserId.value) {
      ElMessage.warning('麻精药品双人核对：第二位核对人不得为给药人本人，请选择其他在职用户')
      return
    }
  }
  saving.value = true
  try {
    await recordDrugAdministration(record.value.recordId, {
      drugName: dictionary.name,
      dose: drugForm.dose,
      unit: drugForm.unit,
      route: drugForm.route,
      administeredAt: drugForm.administeredAt,
      narcotic: dictionary.narcotic,
      ...(drugForm.narcotic
        ? {
            secondCheckerId: drugForm.secondCheckerId.trim(),
            secondCheckerName: drugForm.secondCheckerName.trim(),
          }
        : {}),
      ...(drugForm.note.trim() ? { note: drugForm.note.trim() } : {}),
    })
    ElMessage.success('用药记录已保存（append-only）')
    drugForm.drugName = ''
    drugForm.note = ''
    drugForm.narcotic = false
    drugDictionaryMatch.value = null
    drugForm.secondCheckerId = ''
    drugForm.secondCheckerName = ''
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitFluid(): Promise<void> {
  if (!record.value) return
  if (!fluidForm.fluidType.trim()) {
    ElMessage.warning('请填写液体/出量类型')
    return
  }
  if (!Number.isFinite(fluidForm.amountMl) || fluidForm.amountMl <= 0) {
    ElMessage.warning('量必须大于 0 毫升')
    return
  }
  saving.value = true
  try {
    await recordFluidRecord(record.value.recordId, {
      direction: fluidForm.direction,
      fluidType: fluidForm.fluidType.trim(),
      amountMl: fluidForm.amountMl,
      ...(fluidForm.note.trim() ? { note: fluidForm.note.trim() } : {}),
    })
    ElMessage.success('出入量明细已记录')
    fluidForm.fluidType = ''
    fluidForm.note = ''
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function openAck(alert: Alert, decision: 'CONFIRMED' | 'IGNORED'): void {
  ackDialog.alertId = alert.alertId
  ackDialog.decision = decision
  ackDialog.reason = ''
  ackDialog.visible = true
}

async function submitAck(): Promise<void> {
  if (!ackDialog.reason.trim()) {
    ElMessage.warning('请填写处理原因（确认/忽略均需留痕）')
    return
  }
  saving.value = true
  try {
    await acknowledgeAlert(ackDialog.alertId, {
      decision: ackDialog.decision,
      reason: ackDialog.reason.trim(),
      version: alerts.value.find((item) => item.alertId === ackDialog.alertId)?.version ?? 1,
    })
    ElMessage.success(ackDialog.decision === 'CONFIRMED' ? '告警已确认' : '告警已忽略')
    ackDialog.visible = false
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function severityTagType(severity: string): 'info' | 'warning' | 'danger' {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARN') return 'warning'
  return 'info'
}

function applyResusDictionaryDrug(drug: DrugDictionaryItem): void {
  resusDrugForm.drugName = drug.name
  resusDrugForm.narcotic = drug.narcotic
  resusDrugForm.unit = drug.unit || resusDrugForm.unit
  resusDrugDictionaryMatch.value = drug
}

function handleResusDrugSelect(item: { value: string; drug: DrugDictionaryItem }): void {
  applyResusDictionaryDrug(item.drug)
}

function handleResusDrugInput(value: string): void {
  if (
    !resusDrugDictionaryMatch.value ||
    normalizedDrugName(resusDrugDictionaryMatch.value.name) !== normalizedDrugName(value)
  ) {
    resusDrugDictionaryMatch.value = null
    resusDrugForm.narcotic = false
  }
}

/** 选择候选核对人后回填姓名（抢救用药复用记录单核对人候选与同一服务端资格校验）。 */
function applyResusCheckerCandidate(userId: string): void {
  resusDrugForm.secondCheckerId = userId
  resusDrugForm.secondCheckerName =
    checkerCandidates.value.find((item) => item.userId === userId)?.displayName ?? ''
}

/** 抢救用药提交前解析字典（0/多候选都拒绝，交服务端复核）。 */
async function resolveResusDictionaryDrug(): Promise<DrugDictionaryItem | null> {
  const keyword = resusDrugForm.drugName.trim()
  if (!keyword) return null
  const cached = resusDrugDictionaryMatch.value
  if (cached && normalizedDrugName(cached.name) === normalizedDrugName(keyword)) {
    return cached
  }
  const items = await searchDrugDictionary(keyword)
  const exact = items.filter((drug) =>
    [drug.name, drug.genericName ?? '', ...(drug.aliases ?? [])].some(
      (candidate) => normalizedDrugName(candidate) === normalizedDrugName(keyword),
    ),
  )
  if (exact.length === 1) {
    resusDrugDictionaryMatch.value = exact[0]
    return exact[0]
  }
  return null
}

async function addResusDrug(): Promise<void> {
  if (!resusDrugForm.drugName.trim()) {
    ElMessage.warning('请填写抢救用药名称')
    return
  }
  if (!Number.isFinite(resusDrugForm.dose) || resusDrugForm.dose <= 0) {
    ElMessage.warning('剂量必须大于 0')
    return
  }
  let dictionary: DrugDictionaryItem | null = null
  try {
    dictionary = await resolveResusDictionaryDrug()
  } catch (cause) {
    handleError(cause)
    return
  }
  if (!dictionary) {
    ElMessage.warning('药品未收录于药品字典或有多个同名药品，无法判定麻精属性；请从联想中选择')
    return
  }
  resusDrugForm.narcotic = dictionary.narcotic
  if (dictionary.narcotic) {
    const checkerId = resusDrugForm.secondCheckerId.trim()
    const checkerName = resusDrugForm.secondCheckerName.trim()
    if (!checkerId || !checkerName) {
      ElMessage.warning('麻精药品必须双人核对：请选择第二位核对人（不得为给药人本人）')
      return
    }
    if (checkerId === currentUserId.value) {
      ElMessage.warning('麻精药品双人核对：第二位核对人不得为给药人本人，请选择其他在职用户')
      return
    }
  }
  const narcotic = dictionary.narcotic
  resusDrugs.value.push({
    drugName: dictionary.name,
    dose: resusDrugForm.dose,
    unit: resusDrugForm.unit.trim() || dictionary.unit || 'mg',
    route: resusDrugForm.route.trim() || undefined,
    narcotic,
    ...(narcotic
      ? {
          secondCheckerId: resusDrugForm.secondCheckerId.trim(),
          secondCheckerName: resusDrugForm.secondCheckerName.trim(),
        }
      : {}),
  })
  resusDrugForm.drugName = ''
  resusDrugForm.narcotic = false
  resusDrugForm.secondCheckerId = ''
  resusDrugForm.secondCheckerName = ''
  resusDrugDictionaryMatch.value = null
}

function removeResusDrug(index: number): void {
  resusDrugs.value.splice(index, 1)
}

async function submitResuscitation(): Promise<void> {
  if (!record.value) return
  if (!resusForm.summary.trim()) {
    ElMessage.warning('请填写事件经过摘要')
    return
  }
  const leaderName = resusForm.leaderName.trim()
  if (!leaderName) {
    ElMessage.warning('请填写抢救负责人（必填）')
    return
  }
  const rescuerNames = resusForm.rescuers
    .split(/[，,]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
  if (rescuerNames.length === 0) {
    ElMessage.warning('请填写至少 1 名抢救参与人员')
    return
  }
  const interventions = resusForm.interventions
    .split(/[，,]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
  const leaderUserId = resusForm.leaderUserId.trim()
  const payload: CreateResuscitationRequest = {
    resusType: resusForm.resusType,
    startedAt: resusForm.startedAt,
    endedAt: resusForm.endedAt,
    leader: {
      name: leaderName,
      ...(leaderUserId ? { userId: leaderUserId } : {}),
    },
    rescuers: rescuerNames.map((name) => ({ name })),
    eventSummary: {
      summary: resusForm.summary.trim(),
      ...(interventions.length > 0 ? { interventions } : {}),
    },
    outcome: resusForm.outcome,
    ...(resusDrugs.value.length > 0 ? { drugs: [...resusDrugs.value] } : {}),
  }
  saving.value = true
  try {
    await createResuscitation(record.value.recordId, payload)
    ElMessage.success('抢救记录已保存（append-only）')
    resusForm.summary = ''
    resusForm.interventions = ''
    resusForm.rescuers = ''
    resusDrugs.value = []
    resusDrugForm.drugName = ''
    resusDrugForm.narcotic = false
    resusDrugForm.secondCheckerId = ''
    resusDrugForm.secondCheckerName = ''
    resusDrugDictionaryMatch.value = null
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function directionLabel(direction: string): string {
  return direction === 'INTAKE' ? '入量' : '出量'
}

function anesthesiaTypeLabel(value: string | undefined): string {
  if (!value) return '—'
  return ANESTHESIA_TYPES.find((item) => item.value === value)?.label ?? value
}

function formatDose(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3)
}

/* ---------------------------------------------- 锁单 / 修订 / 版本历史 -- */

/** 打开锁单对话框前先取服务端前置清单（前端不自行推导条件）。 */
async function openLockDialog(): Promise<void> {
  if (!record.value) return
  lockDialog.loading = true
  lockDialog.confirm = false
  try {
    lockStatus.value = await getRecordLockStatus(record.value.recordId)
    lockDialog.visible = true
  } catch (cause) {
    handleError(cause)
  } finally {
    lockDialog.loading = false
  }
}

/** 锁单：必须二次确认清单；服务端仍会重新校验（409 时逐项列出未满足条件）。 */
async function submitLock(): Promise<void> {
  if (!record.value) return
  if (!lockDialog.confirm) {
    ElMessage.warning('请先逐项确认锁单前置条件清单')
    return
  }
  if (!lockStatus.value?.canLock) {
    ElMessage.warning('锁单前置条件未满足，不能锁单')
    return
  }
  saving.value = true
  try {
    const result = await lockAnesthesiaRecord(record.value.recordId, {
      version: record.value.version,
      signatureRequested: true,
      confirmChecklist: true,
    })
    ElMessage.success(
      `已锁单：CA 占位签名 ${result.caStatus ?? 'SIMULATED'}（EXT-05，非真实 CA 有效签名）`,
    )
    lockDialog.visible = false
    // refresh() 内会在记录已锁单时自动加载修订版本。
    await refresh()
  } catch (cause) {
    // 409 前置未满足：后端 details 逐项给出条件码，刷新清单供操作人补齐后重试。
    if (cause instanceof ApiError && cause.status === 409 && cause.details.length > 0) {
      ElMessage.error(`锁单被拒，未满足：${cause.details.map((detail) => detail.field).join('、')}`)
      lockStatus.value = await getRecordLockStatus(record.value.recordId).catch(() => null)
    } else {
      handleError(cause)
    }
  } finally {
    saving.value = false
  }
}

/** 修订版本列表（按 revisionNo 升序；失败不阻断记录单主视图）。 */
async function loadRevisions(): Promise<void> {
  if (!record.value) return
  revisionsLoading.value = true
  try {
    const list = await listRecordRevisions(record.value.recordId)
    revisions.value = list.items
    revisionsLoaded.value = true
    revisionUnavailable.value = false
  } catch {
    revisions.value = []
    revisionUnavailable.value = true
  } finally {
    revisionsLoading.value = false
  }
}

function openRevisionDialog(): void {
  revisionDialog.reasonCode = 'ANESTHESIA_TYPE_CHANGE'
  revisionDialog.reason = ''
  revisionDialog.changes = ''
  revisionDialog.anesthesiaType = ''
  revisionDialog.visible = true
}

/** 创建修订版本：原因码/原因/变更摘要必填；更正只写入修订快照，原记录不变。 */
async function submitRevision(): Promise<void> {
  if (!record.value) return
  if (!revisionDialog.reason.trim()) {
    ElMessage.warning('请填写修订原因')
    return
  }
  if (!revisionDialog.changes.trim()) {
    ElMessage.warning('请填写变更摘要')
    return
  }
  const payload: CreateRevisionRequest = {
    version: record.value.version,
    reasonCode: revisionDialog.reasonCode,
    reason: revisionDialog.reason.trim(),
    changes: revisionDialog.changes.trim(),
  }
  if (revisionDialog.anesthesiaType) {
    payload.corrections = {
      anesthesiaType: revisionDialog.anesthesiaType as NonNullable<
        CreateRevisionRequest['corrections']
      >['anesthesiaType'],
    }
  }
  saving.value = true
  try {
    const created = await createRecordRevision(record.value.recordId, payload)
    ElMessage.success(
      `修订版本 v${created.revisionNo} 已保存（CA 占位签名 ${created.caStatus}，原记录不变）`,
    )
    revisionDialog.visible = false
    // refresh() 内会在记录已锁单时自动加载修订版本（含刚提交的新版本）。
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

/** 版本详情：快照 + 签名摘要 + 与上一版差异（首版对比锁单原始记录）。 */
async function openRevisionDetail(row: RecordRevisionSummary): Promise<void> {
  if (!record.value) return
  revisionDetailLoading.value = true
  revisionDetailVisible.value = true
  revisionDetail.value = null
  try {
    revisionDetail.value = await getRecordRevision(record.value.recordId, row.revisionNo)
  } catch (cause) {
    handleError(cause)
    revisionDetailVisible.value = false
  } finally {
    revisionDetailLoading.value = false
  }
}

function revisionReasonLabel(code: string): string {
  return revisionReasonLabels[code] ?? code
}

function preconditionTagType(precondition: LockPrecondition): 'success' | 'danger' {
  return precondition.satisfied ? 'success' : 'danger'
}

/** 快照 JSON 只读预览（避免直接渲染对象）。 */
function snapshotText(revision: RecordRevision | null): string {
  return revision ? JSON.stringify(revision.snapshot, null, 2) : ''
}

onMounted(async () => {
  await load()
  await loadCheckerCandidates()
  startAlertPolling()
})

onUnmounted(stopAlertPolling)
</script>

<template>
  <section class="record">
    <header class="record__header">
      <div>
        <h1 class="record__title">麻醉记录单</h1>
        <p class="record__subtitle">
          台次 <code>{{ scheduleId }}</code
          >；一个台次一张记录单，时间轴/用药/出入量 append-only（不可改删），
          记录单未锁定时可改（乐观锁版本）。
        </p>
      </div>
      <div class="record__actions">
        <el-button data-testid="record-refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="record && canPacuRead"
          data-testid="pacu-entry"
          :disabled="busy"
          @click="router.push(`/m2/pacu/${record.recordId}`)"
        >
          复苏记录（PACU）
        </el-button>
        <el-button
          v-if="record && canFollowupRead"
          data-testid="followup-entry"
          :disabled="busy"
          @click="router.push(`/m2/followups/${record.recordId}`)"
        >
          术后随访
        </el-button>
        <el-button
          v-if="record && canLock && !locked"
          data-testid="lock-open"
          type="warning"
          :loading="lockDialog.loading"
          :disabled="busy"
          @click="openLockDialog"
        >
          锁单
        </el-button>
        <el-button
          v-if="record && locked && canRevise"
          data-testid="revise-open"
          type="primary"
          :disabled="busy"
          @click="openRevisionDialog"
        >
          修订
        </el-button>
        <el-button
          v-if="record && locked"
          data-testid="revision-history"
          :loading="revisionsLoading"
          @click="loadRevisions"
        >
          版本历史
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      data-testid="record-error"
      class="record__error"
      type="error"
      :closable="false"
      show-icon
      :title="errorTitle"
      :description="`${error.code}：${error.message}${error.traceId ? `（traceId：${error.traceId}）` : ''}`"
    />

    <el-alert
      v-if="record && locked"
      data-testid="lock-banner"
      type="info"
      :closable="false"
      show-icon
      title="记录单已锁单：内容只读，修正必须新增修订版本（原记录与历史版本不可改）"
      :description="`锁单时间 ${record.lockedAt ?? '—'}；签名 ${record.signatureRef ?? '—'}（CA 占位 ${record.caStatus ?? 'SIMULATED'} / EXT-05，非真实 CA 有效签名）；摘要 ${record.signatureHash ?? '—'}`"
    />

    <el-skeleton v-if="loading && !record" :rows="4" animated />

    <section v-else-if="!record" class="record__empty" data-testid="record-empty">
      <el-empty description="该台次尚未创建麻醉记录单" />
      <div v-if="canWrite" class="record__create">
        <el-select v-model="createForm.anesthesiaType" data-testid="create-anesthesia-type">
          <el-option
            v-for="option in ANESTHESIA_TYPES"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button
          type="primary"
          data-testid="create-record"
          :loading="saving"
          @click="createRecord"
        >
          创建记录单
        </el-button>
      </div>
      <p v-else class="record__hint">当前角色无 m2:record:write，不能创建记录单。</p>
    </section>

    <template v-else>
      <el-descriptions data-testid="record-context" class="record__context" :column="4" border>
        <el-descriptions-item label="患者">
          {{ record.patientName }}（{{ record.patientNo }}）
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag
            data-testid="record-status"
            :type="record.status === 'DRAFT' ? 'info' : 'success'"
          >
            {{ statusLabel }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="麻醉方式（当前有效）">
          <span data-testid="record-effective-anesthesia">
            {{ anesthesiaTypeLabel(effectiveAnesthesiaType) }}
          </span>
          <el-tag
            v-if="effectiveCorrection"
            data-testid="record-effective-revision"
            type="warning"
            size="small"
            class="record__effective-tag"
          >
            修订 v{{ effective?.currentRevision }}
          </el-tag>
          <div
            v-if="effectiveCorrection"
            class="record__hint"
            data-testid="record-original-anesthesia"
          >
            锁单原始值：{{ anesthesiaTypeLabel(originalAnesthesiaType) }}（原记录不可变）
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="版本">v{{ record.version }}</el-descriptions-item>
        <el-descriptions-item label="锁定">
          <el-tag data-testid="record-locked" :type="locked ? 'warning' : 'info'" size="small">
            {{ locked ? '已锁单' : '未锁单' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="修订版本">
          <span data-testid="record-revision">v{{ record.currentRevision ?? 0 }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="锁单签名">
          <span data-testid="record-signature">
            {{
              locked
                ? `${record.signatureRef ?? '—'}（${record.caStatus ?? 'SIMULATED'}，摘要 ${signatureHashShort}）`
                : '—'
            }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ record.startedAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ record.endedAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="三方核查">
          <el-tag :type="record.threeWayCheckCompleted ? 'success' : 'warning'">
            {{ record.threeWayCheckCompleted ? '已完成' : '未完成' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="体征">
          <span data-testid="vital-placeholder">
            {{
              (record.vitalSigns?.length ?? 0) === 0
                ? '暂无体征采样（未开始或未接入采集）'
                : `${record.vitalSigns?.length} 条${record.vitalSignsTruncated ? '（已截断）' : ''}`
            }}
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 生命体征（M9-01：采集落库映射；无图表库时以表格 + 质量标记展示） -->
      <section class="record__panel" data-testid="vital-panel">
        <h2 class="record__panel-title">生命体征（采集映射）</h2>
        <p class="record__hint">
          按记录时间窗与该台次术间设备采样映射，按时间升序；质量码与标记原样展示（可疑/缺失帧不丢弃）。
          趋势图占位：未引入图表库时以表格与质量标记呈现，接入图表库后再替换，不替代临床判断。
        </p>
        <el-alert
          v-if="record.vitalSignsTruncated"
          data-testid="vital-truncated"
          type="warning"
          :closable="false"
          show-icon
          title="体征采样超过 2000 条上限，仅显示前 2000 条；完整波形请到设备页按窗口查询"
        />
        <p v-if="vitalSamples.length === 0" class="record__hint" data-testid="vital-empty">
          暂无体征采样：记录未开始、术间未绑定设备，或采集端尚未上报。
        </p>
        <template v-else>
          <p class="record__hint" data-testid="vital-trend-placeholder">
            趋势占位：共 {{ record.vitalSigns?.length ?? 0 }} 条，本页显示前
            {{ vitalSamples.length }} 条。
          </p>
          <el-table :data="vitalSamples" data-testid="vital-list" size="small" border>
            <el-table-column label="测量时间" prop="measuredAt" width="220" />
            <el-table-column label="指标" width="150">
              <template #default="{ row }">{{ vitalMetricLabel(row.metric) }}</template>
            </el-table-column>
            <el-table-column label="数值" width="110">
              <template #default="{ row }">{{ row.value ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="单位" prop="unit" width="90" />
            <el-table-column label="质量" width="100">
              <template #default="{ row }">
                <el-tag :type="qualityTagType(row.quality)" size="small">
                  {{ qualityLabel(row.quality) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="质量标记">
              <template #default="{ row }">
                {{ (row.qualityFlags ?? []).join(' / ') || '—' }}
              </template>
            </el-table-column>
          </el-table>
        </template>
      </section>

      <!-- 术中告警（权限隔离：无 alert:read 时不请求、不渲染） -->
      <section v-if="canAlertRead" class="record__panel" data-testid="alert-panel">
        <h2 class="record__panel-title">
          术中告警（实时评估）
          <el-tag v-if="pendingAlertCount > 0" type="danger" size="small">
            {{ pendingAlertCount }} 条待处理
          </el-tag>
          <el-button
            class="record__panel-action"
            size="small"
            data-testid="alert-evaluate"
            :loading="evaluating"
            :disabled="!canWrite"
            @click="evaluateAlerts"
          >
            手动评估（调试）
          </el-button>
        </h2>
        <p class="record__hint">
          采集落库后按启用规则实时评估（仅进行中记录；按记录单创建时快照的术间匹配设备）， 本页每 8
          秒轮询兜底。手动评估仅用于调试/采集补偿，按已评估水位只处理新采样，
          不重评历史窗口。阈值口径【待确认】，以临床顾问确认为准；告警只做提示，不替代临床判断。
        </p>
        <el-alert
          v-if="record.status === 'DRAFT'"
          data-testid="alert-draft-hint"
          type="info"
          :closable="false"
          show-icon
          title="草稿记录不评估：麻醉开始（记录状态变为进行中）后才产生告警"
        />
        <el-alert
          v-if="alertUnavailable"
          data-testid="alert-unavailable"
          type="warning"
          :closable="false"
          show-icon
          title="告警加载失败（不影响时间轴/用药/出入量），可稍后刷新"
        />
        <el-table :data="alerts" data-testid="alert-list" size="small" border>
          <el-table-column label="严重度" width="100">
            <template #default="{ row }">
              <el-tag :type="severityTagType(row.severity)" size="small">
                {{ alertSeverityLabels[row.severity] ?? row.severity }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="规则" width="150">
            <template #default="{ row }"> {{ row.ruleCode }} v{{ row.ruleVersion }} </template>
          </el-table-column>
          <el-table-column label="指标" width="150">
            <template #default="{ row }">
              {{ alertMetricLabels[row.metric] ?? row.metric }}
            </template>
          </el-table-column>
          <el-table-column label="实测值" width="110">
            <template #default="{ row }">{{ row.value }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="触发时间" prop="triggeredAt" width="220" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'TRIGGERED' ? 'warning' : 'info'" size="small">
                {{ alertStatusLabels[row.status] ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理" width="170">
            <template #default="{ row }">
              <template v-if="row.status === 'TRIGGERED' && canAck">
                <el-button
                  link
                  type="primary"
                  :data-testid="`alert-confirm-${row.alertId}`"
                  @click="openAck(row, 'CONFIRMED')"
                >
                  确认
                </el-button>
                <el-button
                  link
                  type="warning"
                  :data-testid="`alert-ignore-${row.alertId}`"
                  @click="openAck(row, 'IGNORED')"
                >
                  忽略
                </el-button>
              </template>
              <span v-else-if="row.acknowledgeReason">{{ row.acknowledgeReason }}</span>
              <span v-else class="record__hint">—</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 修订版本（锁单后修正留痕；原记录不变） -->
      <section v-if="locked" class="record__panel" data-testid="revision-panel">
        <h2 class="record__panel-title">
          修订版本（append-only）
          <el-tag type="info" size="small">CA 占位 SIMULATED / EXT-05</el-tag>
        </h2>
        <p class="record__hint">
          锁单后修正只能新增修订版本：原因码、原因、变更摘要与全量快照留痕，原记录与历史版本不可改。
          签名摘要为规范化记录 JSON 的 SHA-256 占位，不代表真实 CA 有效签名。
        </p>
        <el-alert
          v-if="revisionUnavailable"
          data-testid="revision-unavailable"
          type="warning"
          :closable="false"
          show-icon
          title="修订版本加载失败（不影响记录单只读视图），可稍后刷新"
        />
        <el-table :data="revisions" data-testid="revision-list" size="small" border>
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.revisionNo }}</template>
          </el-table-column>
          <el-table-column label="原因码" width="130">
            <template #default="{ row }">{{ revisionReasonLabel(row.reasonCode) }}</template>
          </el-table-column>
          <el-table-column label="原因" prop="reason" />
          <el-table-column label="变更摘要" prop="changes" />
          <el-table-column label="签名摘要" width="150">
            <template #default="{ row }">
              <code>{{ String(row.signatureHash).slice(0, 12) }}…</code>
            </template>
          </el-table-column>
          <el-table-column label="时间" prop="createdAt" width="210" />
          <el-table-column label="对比" width="100">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :data-testid="`revision-detail-${row.revisionNo}`"
                @click="openRevisionDetail(row)"
              >
                查看差异
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="revisionsLoaded && revisions.length === 0" data-testid="revision-empty">
          暂无修订版本（记录单锁单后未发生修正）。
        </p>
      </section>

      <!-- 出入量汇总 -->
      <section class="record__panel" data-testid="io-summary">
        <h2 class="record__panel-title">出入量汇总（服务端计算）</h2>
        <div class="record__io">
          <div class="record__io-item">
            <span class="record__io-label">入量</span>
            <strong data-testid="io-intake">{{ summary?.intakeMl ?? 0 }} ml</strong>
          </div>
          <div class="record__io-item">
            <span class="record__io-label">出量</span>
            <strong data-testid="io-output">{{ summary?.outputMl ?? 0 }} ml</strong>
          </div>
          <div class="record__io-item">
            <span class="record__io-label">平衡</span>
            <strong data-testid="io-balance">{{ summary?.balanceMl ?? 0 }} ml</strong>
          </div>
          <div class="record__io-item">
            <span class="record__io-label">明细条数</span>
            <strong>{{ summary?.fluidCount ?? 0 }}</strong>
          </div>
        </div>
      </section>

      <!-- 时间轴 -->
      <section class="record__panel">
        <h2 class="record__panel-title">时间轴事件（append-only）</h2>
        <el-table :data="events" data-testid="event-list" size="small" border>
          <el-table-column label="序号" prop="seq" width="70" />
          <el-table-column label="事件" width="120">
            <template #default="{ row }">
              {{ recordEventLabels[row.eventType] ?? row.eventType }}
            </template>
          </el-table-column>
          <el-table-column label="发生时间" prop="occurredAt" width="220" />
          <el-table-column label="备注" prop="note" />
          <el-table-column label="补录原因" prop="entryReason" width="180" />
          <el-table-column label="录入时间" prop="recordedAt" width="220" />
        </el-table>

        <el-form v-if="editable" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="事件类型">
            <el-select v-model="eventForm.eventType" data-testid="event-type">
              <el-option
                v-for="option in eventTypeOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="发生时间">
            <el-date-picker
              v-model="eventForm.occurredAt"
              data-testid="event-occurred-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="eventForm.note" data-testid="event-note" maxlength="500" />
          </el-form-item>
          <el-form-item label="补录原因">
            <el-input
              v-model="eventForm.entryReason"
              data-testid="event-entry-reason"
              maxlength="200"
              placeholder="事件时间早于最新事件或超出容差窗口时必填"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="append-event"
              :loading="saving"
              :disabled="busy"
              @click="submitEvent"
            >
              追加事件
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <!-- 用药 -->
      <section class="record__panel">
        <h2 class="record__panel-title">用药记录（麻精类双人核对）</h2>
        <el-table :data="drugs" data-testid="drug-list" size="small" border>
          <el-table-column label="药品" prop="drugName" />
          <el-table-column label="剂量" width="120">
            <template #default="{ row }">{{ formatDose(row.dose) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="途径" prop="route" width="100" />
          <el-table-column label="给药时间" prop="administeredAt" width="220" />
          <el-table-column label="麻精" width="80">
            <template #default="{ row }">
              <el-tag :type="row.narcotic ? 'danger' : 'info'" size="small">
                {{ row.narcotic ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="给药人" width="170">
            <template #default="{ row }">
              <span :data-testid="`drug-operator-${row.drugId}`">{{
                operatorLabel(row.createdBy)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="核对人" width="160">
            <template #default="{ row }">
              <span :data-testid="`drug-checker-${row.drugId}`">{{
                row.secondCheckerName ?? '—'
              }}</span>
            </template>
          </el-table-column>
        </el-table>

        <el-form v-if="editable" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="药品名称">
            <el-autocomplete
              v-model="drugForm.drugName"
              data-testid="drug-name"
              :fetch-suggestions="fetchDrugSuggestions"
              :debounce="0"
              placeholder="输入药品名称/通用名/别名，选择字典项"
              maxlength="200"
              style="width: 100%"
              @select="handleDrugSelect"
              @input="handleDrugInput"
            >
              <template #default="{ item }">
                <span>{{ item.value }}</span>
                <span class="record__hint">{{ item.drug.narcotic ? '麻精' : '非麻精' }}</span>
              </template>
            </el-autocomplete>
          </el-form-item>
          <el-form-item label="剂量/单位">
            <el-input-number
              v-model="drugForm.dose"
              data-testid="drug-dose"
              :min="0.001"
              :step="1"
            />
            <el-input
              v-model="drugForm.unit"
              data-testid="drug-unit"
              class="record__short"
              maxlength="16"
            />
          </el-form-item>
          <el-form-item label="给药途径">
            <el-input v-model="drugForm.route" data-testid="drug-route" maxlength="32" />
          </el-form-item>
          <el-form-item label="给药时间">
            <el-date-picker
              v-model="drugForm.administeredAt"
              data-testid="drug-administered-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </el-form-item>
          <el-form-item label="麻精药品">
            <el-switch :model-value="drugForm.narcotic" disabled data-testid="drug-narcotic" />
            <span class="record__hint">
              麻精标志只读、来自药品字典；麻精药品必须双人核对，核对人不得为给药人本人
            </span>
          </el-form-item>
          <template v-if="drugForm.narcotic">
            <el-form-item label="第二位核对人">
              <el-select
                v-model="drugForm.secondCheckerId"
                data-testid="drug-second-checker"
                filterable
                clearable
                :loading="checkerCandidatesLoading"
                placeholder="选择在职且持 m2:record:write 的用户（不得为本人）"
                style="width: 100%"
                @change="applyCheckerCandidate"
              >
                <el-option
                  v-for="candidate in checkerCandidates"
                  :key="candidate.userId"
                  :label="`${candidate.displayName}（${candidate.username}）${candidate.userId === currentUserId ? '·本人' : ''}`"
                  :value="candidate.userId"
                />
              </el-select>
              <p class="record__hint" data-testid="drug-second-checker-hint">
                {{
                  canListCheckers
                    ? checkerUnavailable
                      ? '候选核对人加载失败，可手工填写下方标识与姓名'
                      : '候选来自 /v1/auth/users（在职用户）；提交时服务端校验存在/在职/非本人/持 m2:record:write'
                    : '当前账号无 m0:user:read，无法加载候选核对人，请在下方手工填写标识与姓名'
                }}
              </p>
            </el-form-item>
            <el-form-item label="核对人标识">
              <el-input
                v-model="drugForm.secondCheckerId"
                data-testid="drug-second-checker-id"
                placeholder="核对人用户标识（UUID）"
                maxlength="64"
              />
            </el-form-item>
            <el-form-item label="核对人姓名">
              <el-input
                v-model="drugForm.secondCheckerName"
                data-testid="drug-second-checker-name"
                maxlength="64"
              />
            </el-form-item>
          </template>
          <el-form-item label="备注">
            <el-input v-model="drugForm.note" data-testid="drug-note" maxlength="500" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="record-drug"
              :loading="saving"
              :disabled="busy"
              @click="submitDrug"
            >
              保存用药
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <!-- 输液/出入量 -->
      <section class="record__panel">
        <h2 class="record__panel-title">输液/出入量明细（append-only）</h2>
        <el-table :data="fluids" data-testid="fluid-list" size="small" border>
          <el-table-column label="方向" width="90">
            <template #default="{ row }">{{ directionLabel(row.direction) }}</template>
          </el-table-column>
          <el-table-column label="类型" prop="fluidType" />
          <el-table-column label="量（ml）" prop="amountMl" width="110" />
          <el-table-column label="记录时间" prop="recordedAt" width="220" />
          <el-table-column label="备注" prop="note" />
        </el-table>

        <el-form v-if="editable" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="方向">
            <el-select v-model="fluidForm.direction" data-testid="fluid-direction">
              <el-option label="入量" value="INTAKE" />
              <el-option label="出量" value="OUTPUT" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型">
            <el-input v-model="fluidForm.fluidType" data-testid="fluid-type" maxlength="64" />
          </el-form-item>
          <el-form-item label="量（ml）">
            <el-input-number v-model="fluidForm.amountMl" data-testid="fluid-amount" :min="1" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="fluidForm.note" data-testid="fluid-note" maxlength="200" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="record-fluid"
              :loading="saving"
              :disabled="busy"
              @click="submitFluid"
            >
              记录出入量
            </el-button>
          </el-form-item>
        </el-form>
      </section>
      <!-- 抢救记录（权限隔离：无 rescue 权限时不请求、不渲染） -->
      <section v-if="canRescueView" class="record__panel" data-testid="resuscitation-panel">
        <h2 class="record__panel-title">抢救记录（append-only）</h2>
        <el-alert
          v-if="resusUnavailable"
          data-testid="resus-unavailable"
          type="warning"
          :closable="false"
          show-icon
          title="抢救记录加载失败（不影响时间轴/用药/出入量），可稍后刷新"
        />
        <el-table :data="resuscitations" data-testid="resuscitation-list" size="small" border>
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              {{ resusTypeLabels[row.resusType] ?? row.resusType }}
            </template>
          </el-table-column>
          <el-table-column label="负责人" width="140">
            <template #default="{ row }">{{ row.leader?.name ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="参与人员" width="110">
            <template #default="{ row }">{{ row.rescuers?.length ?? 0 }} 人</template>
          </el-table-column>
          <el-table-column label="开始" prop="startedAt" width="220" />
          <el-table-column label="结束" prop="endedAt" width="220" />
          <el-table-column label="经过摘要">
            <template #default="{ row }">{{ row.eventSummary?.summary }}</template>
          </el-table-column>
          <el-table-column label="用药" width="100">
            <template #default="{ row }">{{ row.drugs?.length ?? 0 }} 条</template>
          </el-table-column>
          <el-table-column label="转归" width="120">
            <template #default="{ row }">
              {{ resusOutcomeLabels[row.outcome] ?? row.outcome }}
            </template>
          </el-table-column>
        </el-table>

        <el-form v-if="canRescue" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="抢救类型">
            <el-select v-model="resusForm.resusType" data-testid="resus-type">
              <el-option
                v-for="(label, value) in resusTypeLabels"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="负责人">
            <el-input
              v-model="resusForm.leaderName"
              data-testid="resus-leader"
              maxlength="100"
              placeholder="抢救负责人姓名（必填）"
            />
          </el-form-item>
          <el-form-item label="负责人账号">
            <el-input
              v-model="resusForm.leaderUserId"
              data-testid="resus-leader-id"
              maxlength="64"
              placeholder="可选：人员账号标识（UUID）"
            />
          </el-form-item>
          <el-form-item label="参与人员">
            <el-input
              v-model="resusForm.rescuers"
              data-testid="resus-rescuers"
              placeholder="至少 1 人，逗号分隔姓名"
            />
          </el-form-item>
          <el-form-item label="开始时间">
            <el-date-picker
              v-model="resusForm.startedAt"
              data-testid="resus-started-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </el-form-item>
          <el-form-item label="结束时间">
            <el-date-picker
              v-model="resusForm.endedAt"
              data-testid="resus-ended-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </el-form-item>
          <el-form-item label="经过摘要">
            <el-input
              v-model="resusForm.summary"
              data-testid="resus-summary"
              type="textarea"
              :rows="2"
              maxlength="2000"
              placeholder="不得写入姓名/证件号/住院号等直接标识"
            />
          </el-form-item>
          <el-form-item label="关键干预">
            <el-input
              v-model="resusForm.interventions"
              data-testid="resus-interventions"
              placeholder="逗号分隔，如：胸外按压,肾上腺素"
            />
          </el-form-item>
          <el-form-item label="抢救用药">
            <div class="record__drug-row">
              <el-autocomplete
                v-model="resusDrugForm.drugName"
                data-testid="resus-drug-name"
                :fetch-suggestions="fetchDrugSuggestions"
                :debounce="0"
                placeholder="输入药品名称/通用名/别名，选择字典项"
                maxlength="200"
                style="width: 260px"
                @select="handleResusDrugSelect"
                @input="handleResusDrugInput"
              >
                <template #default="{ item }">
                  <span>{{ item.value }}</span>
                  <span class="record__hint">{{ item.drug.narcotic ? '麻精' : '非麻精' }}</span>
                </template>
              </el-autocomplete>
              <el-tag v-if="resusDrugForm.narcotic" type="danger" size="small">麻精</el-tag>
              <el-input-number
                v-model="resusDrugForm.dose"
                data-testid="resus-drug-dose"
                :min="0.001"
                :step="1"
              />
              <el-input
                v-model="resusDrugForm.unit"
                data-testid="resus-drug-unit"
                class="record__short"
                maxlength="16"
              />
              <el-button data-testid="resus-add-drug" @click="addResusDrug">添加</el-button>
            </div>
            <div v-if="resusDrugForm.narcotic" class="record__drug-row">
              <el-select
                v-if="checkerCandidates.length > 0"
                v-model="resusDrugForm.secondCheckerId"
                data-testid="resus-second-checker"
                filterable
                placeholder="第二位核对人（麻精必填）"
                style="width: 260px"
                @change="applyResusCheckerCandidate"
              >
                <el-option
                  v-for="item in checkerCandidates"
                  :key="item.userId"
                  :label="`${item.displayName}（${item.username}）`"
                  :value="item.userId"
                />
              </el-select>
              <el-input
                v-else
                v-model="resusDrugForm.secondCheckerId"
                data-testid="resus-second-checker-id"
                placeholder="核对人用户标识（UUID）"
                maxlength="64"
                style="width: 260px"
              />
              <el-input
                v-model="resusDrugForm.secondCheckerName"
                data-testid="resus-second-checker-name"
                placeholder="核对人姓名"
                maxlength="64"
                style="width: 180px"
              />
              <span class="record__hint">服务端校验存在/在职/非本人/持 m2:record:write</span>
            </div>
            <ul v-if="resusDrugs.length > 0" class="record__drug-list">
              <li v-for="(item, index) in resusDrugs" :key="`${item.drugName}-${index}`">
                {{ item.drugName }} {{ item.dose }} {{ item.unit }}
                <el-tag v-if="item.narcotic" type="danger" size="small">麻精</el-tag>
                <span v-if="item.secondCheckerName" class="record__hint">
                  核对：{{ item.secondCheckerName }}
                </span>
                <el-button link type="danger" @click="removeResusDrug(index)">移除</el-button>
              </li>
            </ul>
          </el-form-item>
          <el-form-item label="转归">
            <el-select v-model="resusForm.outcome" data-testid="resus-outcome">
              <el-option
                v-for="(label, value) in resusOutcomeLabels"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="submit-resuscitation"
              :loading="saving"
              :disabled="busy"
              @click="submitResuscitation"
            >
              保存抢救记录
            </el-button>
          </el-form-item>
        </el-form>
      </section>
    </template>

    <el-dialog v-model="lockDialog.visible" title="锁单确认（记录将不可修改）" width="680px">
      <p class="record__hint">
        锁单后记录内容不可修改，修正只能新增修订版本（保留原值、修改人、时间、原因）。
        以下前置条件由服务端评估，前端只展示，不能替代服务端校验：
      </p>
      <el-table
        :data="lockStatus?.preconditions ?? []"
        data-testid="lock-precondition-list"
        size="small"
        border
      >
        <el-table-column label="前置条件" width="190">
          <template #default="{ row }">{{ row.label }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="preconditionTagType(row)" size="small">
              {{ row.satisfied ? '已满足' : '未满足' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前值">
          <template #default="{ row }">{{ row.detail ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <p class="record__hint">
        版本 v{{ record?.version }}；CA 不可用（EXT-05）：锁单生成 SIMULATED 占位签名 （signatureRef
        + SHA-256 摘要），不代表真实 CA 有效签名。
      </p>
      <el-checkbox v-model="lockDialog.confirm" data-testid="lock-confirm">
        我已逐项核对上述前置条件，确认锁单
      </el-checkbox>
      <template #footer>
        <el-button data-testid="lock-cancel" @click="lockDialog.visible = false">取消</el-button>
        <el-button
          type="warning"
          data-testid="lock-submit"
          :loading="saving"
          :disabled="!lockStatus?.canLock || !lockDialog.confirm"
          @click="submitLock"
        >
          确认锁单
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="revisionDialog.visible" title="新增修订版本（原记录不变）" width="560px">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="原因码">
          <el-select v-model="revisionDialog.reasonCode" data-testid="revision-reason-code">
            <el-option
              v-for="(label, value) in revisionReasonLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-input
            v-model="revisionDialog.reason"
            data-testid="revision-reason"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="必填；只落库与授权用户可见，不写日志"
          />
        </el-form-item>
        <el-form-item label="变更摘要">
          <el-input
            v-model="revisionDialog.changes"
            data-testid="revision-changes"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="必填；用于版本历史与差异对比阅读"
          />
        </el-form-item>
        <el-form-item label="更正麻醉方式">
          <el-select
            v-model="revisionDialog.anesthesiaType"
            data-testid="revision-anesthesia-type"
            clearable
            placeholder="不更正则留空"
          >
            <el-option
              v-for="option in ANESTHESIA_TYPES"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="record__hint">
        仅麻醉方式可经修订更正；时间轴/用药/出入量为 append-only，需以新增事件/记录补录。 修订签名为
        SIMULATED 占位（EXT-05）。
      </p>
      <template #footer>
        <el-button data-testid="revision-cancel" @click="revisionDialog.visible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          data-testid="revision-submit"
          :loading="saving"
          @click="submitRevision"
        >
          提交修订
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="revisionDetailVisible" title="修订版本详情与差异" width="760px">
      <el-skeleton v-if="revisionDetailLoading" :rows="4" animated />
      <template v-else-if="revisionDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="版本">v{{ revisionDetail.revisionNo }}</el-descriptions-item>
          <el-descriptions-item label="原因码">
            {{ revisionReasonLabel(revisionDetail.reasonCode) }}
          </el-descriptions-item>
          <el-descriptions-item label="原因">{{ revisionDetail.reason }}</el-descriptions-item>
          <el-descriptions-item label="变更摘要">{{ revisionDetail.changes }}</el-descriptions-item>
          <el-descriptions-item label="签名">
            {{ revisionDetail.signatureRef }}（{{ revisionDetail.caStatus }}）
          </el-descriptions-item>
          <el-descriptions-item label="签名摘要">
            <code>{{ revisionDetail.signatureHash }}</code>
          </el-descriptions-item>
        </el-descriptions>
        <h3 class="record__panel-title">
          与上一版差异（v{{ revisionDetail.diff.previousRevisionNo }} → v{{
            revisionDetail.revisionNo
          }}）
        </h3>
        <p data-testid="revision-diff-summary">{{ revisionDetail.diff.summary }}</p>
        <el-table
          :data="revisionDetail.diff.changedFields"
          data-testid="revision-diff"
          size="small"
          border
        >
          <el-table-column label="字段" prop="field" />
          <el-table-column label="上一版" width="200">
            <template #default="{ row }">{{ row.previousValue ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="本版" width="200">
            <template #default="{ row }">{{ row.newValue ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <h3 class="record__panel-title">快照（只读，含全部子记录与签名摘要）</h3>
        <pre data-testid="revision-snapshot" class="record__snapshot">{{
          snapshotText(revisionDetail)
        }}</pre>
      </template>
      <template #footer>
        <el-button data-testid="revision-detail-close" @click="revisionDetailVisible = false">
          关闭
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ackDialog.visible" title="处理告警" width="480px">
      <p class="record__hint">
        {{ ackDialog.decision === 'CONFIRMED' ? '确认告警有效并已处置' : '忽略告警并说明理由' }}
      </p>
      <el-input
        v-model="ackDialog.reason"
        data-testid="ack-reason"
        type="textarea"
        :rows="3"
        maxlength="500"
        placeholder="处理原因（必填；审计不记录原文）"
      />
      <template #footer>
        <el-button data-testid="ack-cancel" @click="ackDialog.visible = false">取消</el-button>
        <el-button type="primary" data-testid="ack-submit" :loading="saving" @click="submitAck">
          提交
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.record {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1120px;
}

.record__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.record__title {
  margin: 0 0 6px;
  font-size: 22px;
}

.record__subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

.record__panel {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
  background: var(--el-bg-color);
}

.record__panel-title {
  margin: 0 0 12px;
  font-size: 16px;
}

.record__io {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}

.record__io-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.record__io-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.record__form {
  margin-top: 16px;
}

.record__short {
  width: 120px;
  margin-left: 8px;
}

.record__hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.record__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.record__snapshot {
  max-height: 260px;
  overflow: auto;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.6;
}

.record__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.record__create {
  display: flex;
  gap: 12px;
}

.record__drug-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.record__drug-list {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
