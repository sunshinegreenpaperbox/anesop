<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  acknowledgeAlert,
  alertMetricLabels,
  alertSeverityLabels,
  alertStatusLabels,
  appendRecordEvent,
  createAnesthesiaRecord,
  createResuscitation,
  evaluateRecordAlerts,
  getIntakeOutputSummary,
  listAnesthesiaRecords,
  listDrugAdministrations,
  listFluidRecords,
  listRecordAlerts,
  listRecordEvents,
  listResuscitations,
  recordDrugAdministration,
  recordFluidRecord,
  recordEventLabels,
  resusOutcomeLabels,
  resusTypeLabels,
  type Alert,
  type AnesthesiaRecordDetail,
  type CreateRecordEventRequest,
  type CreateResuscitationRequest,
  type DrugAdministration,
  type FluidDirection,
  type FluidRecord,
  type IntakeOutputSummary,
  type RecordEvent,
  type RecordEventType,
  type ResuscitationDrug,
  type ResuscitationRecord,
  type ResusOutcome,
  type ResusType,
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

const route = useRoute()
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
})
const loading = ref(false)
const saving = ref(false)
const evaluating = ref(false)
const alertUnavailable = ref(false)
const resusUnavailable = ref(false)
const error = ref<ApiError | null>(null)

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

const fluidForm = reactive({
  direction: 'INTAKE' as FluidDirection,
  fluidType: '',
  amountMl: 500,
  note: '',
})

const canWrite = computed(() => auth.hasPermission('m2:record:write'))
const canAck = computed(() => auth.hasPermission('m2:alert:ack'))
const canRescue = computed(() => auth.hasPermission('m2:rescue:write'))
/** 权限隔离（S-3）：缺 alert:read / rescue 权限时跳过对应请求，不影响时间轴/用药/出入量。 */
const canAlertRead = computed(() => auth.hasPermission('m2:alert:read'))
const canRescueView = computed(() => canRescue.value || auth.hasPermission('m2:record:write'))
const pendingAlertCount = computed(
  () => alerts.value.filter((item) => item.status === 'TRIGGERED').length,
)
const editable = computed(() => canWrite.value && record.value !== null)
const busy = computed(() => loading.value || saving.value)
const statusLabel = computed(() =>
  record.value?.status === 'IN_PROGRESS'
    ? '进行中'
    : record.value?.status === 'DRAFT'
      ? '草稿'
      : '',
)
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
}

async function loadRecordDetail(recordId: string): Promise<void> {
  // 权限隔离（S-3）：告警/抢救请求按权限跳过；任一侧失败不阻断时间轴/用药/出入量。
  // 注意：任务在数组内按固定顺序创建，保证请求时序稳定（时间轴→用药→出入量→告警→抢救）。
  const [eventR, drugR, fluidR, ioR, alertR, resusR] = await Promise.allSettled([
    listRecordEvents(recordId),
    listDrugAdministrations(recordId),
    listFluidRecords(recordId),
    getIntakeOutputSummary(recordId),
    canAlertRead.value ? listRecordAlerts(recordId) : Promise.resolve(null),
    canRescueView.value ? listResuscitations(recordId) : Promise.resolve(null),
  ])
  if (eventR.status === 'fulfilled') events.value = eventR.value.items
  if (drugR.status === 'fulfilled') drugs.value = drugR.value.items
  if (fluidR.status === 'fulfilled') fluids.value = fluidR.value.items
  if (ioR.status === 'fulfilled') summary.value = ioR.value
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
  if (
    drugForm.narcotic &&
    (!drugForm.secondCheckerId.trim() || !drugForm.secondCheckerName.trim())
  ) {
    ElMessage.warning('麻精药品必须双人核对：请填写核对人标识与姓名（不同人）')
    return
  }
  saving.value = true
  try {
    await recordDrugAdministration(record.value.recordId, {
      drugName: drugForm.drugName.trim(),
      dose: drugForm.dose,
      unit: drugForm.unit,
      route: drugForm.route,
      administeredAt: drugForm.administeredAt,
      narcotic: drugForm.narcotic,
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

function addResusDrug(): void {
  if (!resusDrugForm.drugName.trim()) {
    ElMessage.warning('请填写抢救用药名称')
    return
  }
  if (!Number.isFinite(resusDrugForm.dose) || resusDrugForm.dose <= 0) {
    ElMessage.warning('剂量必须大于 0')
    return
  }
  resusDrugs.value.push({
    drugName: resusDrugForm.drugName.trim(),
    dose: resusDrugForm.dose,
    unit: resusDrugForm.unit.trim() || 'mg',
    route: resusDrugForm.route.trim() || undefined,
  })
  resusDrugForm.drugName = ''
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

function formatDose(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3)
}

onMounted(async () => {
  await load()
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
      <el-button data-testid="record-refresh" :loading="loading" @click="load">刷新</el-button>
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
        <el-descriptions-item label="麻醉方式">
          {{
            ANESTHESIA_TYPES.find((item) => item.value === record?.anesthesiaType)?.label ??
            record.anesthesiaType
          }}
        </el-descriptions-item>
        <el-descriptions-item label="版本">v{{ record.version }}</el-descriptions-item>
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
                ? '体征待采集接入（M3-M8-01）'
                : `${record.vitalSigns?.length} 条`
            }}
          </span>
        </el-descriptions-item>
      </el-descriptions>

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
          <el-table-column label="核对人" width="160">
            <template #default="{ row }">{{ row.secondCheckerName ?? '—' }}</template>
          </el-table-column>
        </el-table>

        <el-form v-if="editable" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="药品名称">
            <el-input v-model="drugForm.drugName" data-testid="drug-name" maxlength="200" />
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
            <el-switch v-model="drugForm.narcotic" data-testid="drug-narcotic" />
            <span class="record__hint">开启后必须双人核对，核对人不得为给药人本人</span>
          </el-form-item>
          <template v-if="drugForm.narcotic">
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
              <el-input
                v-model="resusDrugForm.drugName"
                data-testid="resus-drug-name"
                placeholder="药品名称"
                maxlength="200"
              />
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
            <ul v-if="resusDrugs.length > 0" class="record__drug-list">
              <li v-for="(item, index) in resusDrugs" :key="`${item.drugName}-${index}`">
                {{ item.drugName }} {{ item.dose }} {{ item.unit }}
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
