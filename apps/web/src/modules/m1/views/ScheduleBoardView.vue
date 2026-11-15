<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addEmergencyCase,
  checkScheduleConflicts,
  createSchedule,
  listRooms,
  listScheduleChanges,
  listSchedules,
  stopSchedule,
  updateSchedule,
  type EmergencyAddRequest,
  type OperatingRoom,
  type OperatingRoomType,
  type Schedule,
  type ScheduleChange,
  type ScheduleConflict,
  type UpdateScheduleRequest,
} from '../api/m1'
import { listAnesthesiaRecords } from '../../m2/api/m2'
import { ApiError } from '../../../api/http'
import { formatLocalDate } from '../utils/date'
import { useAuthStore } from '../../../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const date = ref(formatLocalDate())
const rooms = ref<OperatingRoom[]>([])
const schedules = ref<Schedule[]>([])
const loading = ref(false)
const submitting = ref(false)
const error = ref<ApiError | null>(null)
const conflicts = ref<ScheduleConflict[]>([])
const createVisible = ref(false)
const adjustVisible = ref(false)
const adjusting = ref<Schedule | null>(null)

const stopVisible = ref(false)
const stopping = ref<Schedule | null>(null)
const stopForm = ref({ reasonCode: 'PATIENT_CONDITION', reason: '' })

const emergencyVisible = ref(false)
const emergencyForm = ref({
  applicationId: '',
  roomId: '',
  plannedStart: '',
  plannedEnd: '',
  reasonCode: 'EMERGENCY_GREEN_CHANNEL',
  preemptScheduleId: '',
  sequenceNo: 1,
})

const historyVisible = ref(false)
const historyLoading = ref(false)
const historyItems = ref<ScheduleChange[]>([])
const historySchedule = ref<Schedule | null>(null)

interface ScheduleForm {
  applicationId: string
  roomId: string
  plannedStart: string
  plannedEnd: string
  sequenceNo: number
  reason: string
  reasonCode: string
}

const form = ref<ScheduleForm>({
  applicationId: '',
  roomId: '',
  plannedStart: '',
  plannedEnd: '',
  sequenceNo: 1,
  reason: '',
  reasonCode: '',
})

const canWrite = computed(() => auth.hasPermission('m1:schedule:write'))
const canReadRecord = computed(() => auth.hasPermission('m2:record:read'))
const canApprove = computed(() => auth.hasPermission('m1:schedule:approve'))
/** 台次卡片「流程入口」按各环节读权限逐项显示；缺权限直接不渲染，避免 403 跳转。 */
const canPreopRead = computed(() => auth.hasPermission('m2:preop:read'))
const canConsentRead = computed(() => auth.hasPermission('m2:consent:read'))
const canThreeWayRead = computed(() => auth.hasPermission('m2:threeway:read'))
const canPacuRead = computed(() => auth.hasPermission('m2:pacu:read'))
const canFollowupRead = computed(() => auth.hasPermission('m2:followup:read'))
const hasFlowEntries = computed(
  () =>
    canPreopRead.value ||
    canConsentRead.value ||
    canThreeWayRead.value ||
    canReadRecord.value ||
    canPacuRead.value ||
    canFollowupRead.value,
)
/** 复苏/随访需先按台次换 recordId，请求期间禁用入口避免重复点击。 */
const flowLoading = ref(false)

/** 停台原因码白名单（与契约 0.5.0/服务端一致）。 */
const stopReasonOptions = [
  { value: 'PATIENT_CONDITION', label: '患者情况变化' },
  { value: 'SURGEON_UNAVAILABLE', label: '术者不可用' },
  { value: 'ROOM_EQUIPMENT_FAILURE', label: '术间/设备故障' },
  { value: 'ANESTHESIA_RISK', label: '麻醉风险' },
  { value: 'SCHEDULE_CHANGE', label: '排程调整' },
  { value: 'OTHER', label: '其他（需说明）' },
]

/** 改台原因码白名单。 */
const modifyReasonOptions = [
  { value: 'SCHEDULE_ADJUST', label: '排程调整' },
  { value: 'PATIENT_CONDITION', label: '患者情况变化' },
  { value: 'SURGEON_UNAVAILABLE', label: '术者不可用' },
  { value: 'ROOM_EQUIPMENT_FAILURE', label: '术间/设备故障' },
  { value: 'ANESTHESIA_RISK', label: '麻醉风险' },
  { value: 'OTHER', label: '其他（需说明）' },
]

/** 急诊加台原因码白名单。 */
const emergencyReasonOptions = [
  { value: 'EMERGENCY_GREEN_CHANNEL', label: '急诊绿色通道' },
  { value: 'EMERGENCY_PREEMPT', label: '急诊抢占择期台次' },
  { value: 'OTHER', label: '其他（需说明）' },
]

const roomTypeLabels: Record<OperatingRoomType, string> = {
  GENERAL: '普通',
  HYBRID: '杂交',
  INTERVENTION: '介入',
}

const statusLabels: Record<Schedule['status'], string> = {
  PLANNED: '已排程',
  PUBLISHED: '已发布',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  DELAYED: '延迟',
}

const changeTypeLabels: Record<string, string> = {
  STOP: '停台',
  MODIFY: '改台',
  EMERGENCY: '急诊加台',
}

const approvalLabels: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
}

function roomTypeLabel(type: OperatingRoomType | undefined): string {
  return type ? (roomTypeLabels[type] ?? type) : '—'
}

function statusLabel(status: Schedule['status']): string {
  return statusLabels[status] ?? status
}

function changeTypeLabel(type: string): string {
  return changeTypeLabels[type] ?? type
}

function approvalLabel(status: string): string {
  return approvalLabels[status] ?? status
}

function reasonCodeLabel(code: string): string {
  const options = [...stopReasonOptions, ...modifyReasonOptions, ...emergencyReasonOptions]
  return options.find((option) => option.value === code)?.label ?? code
}

/** 冲突类型文案（x-extensible-enum：未知值回退显示原始编码）。 */
const conflictTypeLabels: Record<string, string> = {
  ROOM_OVERLAP: '术间时段重叠',
  STAFF_CONFLICT: '术者冲突',
  PATIENT_CONFLICT: '患者台次冲突',
  ROOM_TYPE_MISMATCH: '术间类型不匹配',
  TIME_WINDOW_INVALID: '时间窗非法',
  APPLICATION_ALREADY_SCHEDULED: '申请已有生效台次',
  VERSION_CONFLICT: '版本已过期',
  STATE_CONFLICT: '状态不允许该操作',
}

function conflictTypeLabel(type: string): string {
  return conflictTypeLabels[type] ?? type
}

function statusType(status: Schedule['status']): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'CANCELLED') return 'danger'
  if (status === 'IN_PROGRESS' || status === 'DELAYED') return 'warning'
  if (status === 'COMPLETED' || status === 'PUBLISHED') return 'success'
  return 'info'
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(parsed)
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(parsed)
}

const columns = computed(() =>
  rooms.value.map((room) => ({
    room,
    items: schedules.value
      .filter((item) => item.roomId === room.roomId)
      .sort((left, right) => (left.plannedStart ?? '').localeCompare(right.plannedStart ?? '')),
  })),
)

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '排程操作失败',
  })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * 409 响应优先从 `conflicts[]` 解析（保留 conflictingScheduleId），仅在缺失时回退 details。
 * 兼容旧服务端只返回 details 的情形。
 */
function conflictsFromError(apiError: ApiError): ScheduleConflict[] {
  const rawConflicts = isRecord(apiError.rawBody) ? apiError.rawBody.conflicts : undefined
  if (Array.isArray(rawConflicts)) {
    return rawConflicts.filter(isRecord).map((item) => ({
      conflictType: typeof item.conflictType === 'string' ? item.conflictType : 'UNKNOWN',
      severity: item.severity === 'WARNING' ? 'WARNING' : 'BLOCKING',
      message: typeof item.message === 'string' ? item.message : '排程冲突',
      conflictingScheduleId:
        typeof item.conflictingScheduleId === 'string' ? item.conflictingScheduleId : null,
    }))
  }
  return apiError.details.map((detail) => ({
    conflictType: detail.field,
    severity: 'BLOCKING' as const,
    message: detail.reason,
    conflictingScheduleId: null,
  }))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [roomList, schedulePage] = await Promise.all([listRooms(), listSchedules(date.value)])
    rooms.value = roomList.items
    schedules.value = schedulePage.items
  } catch (caught) {
    error.value = toApiError(caught)
    rooms.value = []
    schedules.value = []
  } finally {
    loading.value = false
  }
}

function defaultWindow(day: string): { start: string; end: string } {
  return {
    start: `${day}T08:00:00+08:00`,
    end: `${day}T10:00:00+08:00`,
  }
}

function openCreate(): void {
  const window = defaultWindow(date.value)
  form.value = {
    applicationId: '',
    roomId: rooms.value[0]?.roomId ?? '',
    plannedStart: window.start,
    plannedEnd: window.end,
    sequenceNo: 1,
    reason: '',
    reasonCode: '',
  }
  conflicts.value = []
  error.value = null
  createVisible.value = true
}

function openAdjust(schedule: Schedule): void {
  adjusting.value = schedule
  form.value = {
    applicationId: schedule.applicationId,
    roomId: schedule.roomId,
    plannedStart: schedule.plannedStart,
    plannedEnd: schedule.plannedEnd,
    sequenceNo: schedule.sequenceNo,
    reason: '',
    // Q-1：改台原因码必填，不预填默认码，必须由操作人选择。
    reasonCode: '',
  }
  conflicts.value = []
  error.value = null
  adjustVisible.value = true
}

function openStop(schedule: Schedule): void {
  stopping.value = schedule
  stopForm.value = { reasonCode: 'PATIENT_CONDITION', reason: '' }
  conflicts.value = []
  error.value = null
  stopVisible.value = true
}

function openEmergency(): void {
  const window = defaultWindow(date.value)
  emergencyForm.value = {
    applicationId: '',
    roomId: rooms.value[0]?.roomId ?? '',
    plannedStart: window.start,
    plannedEnd: window.end,
    reasonCode: 'EMERGENCY_GREEN_CHANNEL',
    preemptScheduleId: '',
    sequenceNo: 1,
  }
  conflicts.value = []
  error.value = null
  emergencyVisible.value = true
}

async function precheck(): Promise<boolean> {
  conflicts.value = []
  if (!form.value.roomId || !form.value.plannedStart || !form.value.plannedEnd) {
    ElMessage.warning('请先选择术间与计划时段')
    return false
  }
  try {
    const result = await checkScheduleConflicts({
      roomId: form.value.roomId,
      applicationId: form.value.applicationId || null,
      plannedStart: form.value.plannedStart,
      plannedEnd: form.value.plannedEnd,
      excludeScheduleId: adjusting.value?.scheduleId ?? null,
    })
    conflicts.value = result.conflicts
    if (!result.hasConflict) ElMessage.success('未发现排程冲突')
    return !result.hasConflict
  } catch (caught) {
    error.value = toApiError(caught)
    return false
  }
}

async function submitCreate(): Promise<void> {
  submitting.value = true
  conflicts.value = []
  error.value = null
  try {
    await createSchedule({
      applicationId: form.value.applicationId,
      roomId: form.value.roomId,
      plannedStart: form.value.plannedStart,
      plannedEnd: form.value.plannedEnd,
      sequenceNo: form.value.sequenceNo,
      priority: 'ELECTIVE',
    })
    ElMessage.success('台次已创建')
    createVisible.value = false
    await load()
  } catch (caught) {
    const apiError = toApiError(caught)
    error.value = apiError
    if (apiError.status === 409) conflicts.value = conflictsFromError(apiError)
  } finally {
    submitting.value = false
  }
}

async function submitAdjust(): Promise<void> {
  const current = adjusting.value
  if (!current) return
  if (!form.value.reasonCode) {
    ElMessage.warning('请选择改台原因码（停台改台必选原因码）')
    return
  }
  submitting.value = true
  conflicts.value = []
  error.value = null
  try {
    const payload: UpdateScheduleRequest = {
      roomId: form.value.roomId,
      plannedStart: form.value.plannedStart,
      plannedEnd: form.value.plannedEnd,
      sequenceNo: form.value.sequenceNo,
      version: current.version,
      reason: form.value.reason || null,
      reasonCode: form.value.reasonCode,
    }
    await updateSchedule(current.scheduleId, payload)
    ElMessage.success('台次已调整（已写入改台变更记录）')
    adjustVisible.value = false
    await load()
  } catch (caught) {
    const apiError = toApiError(caught)
    error.value = apiError
    if (apiError.status === 409) conflicts.value = conflictsFromError(apiError)
  } finally {
    submitting.value = false
  }
}

async function submitStop(): Promise<void> {
  const current = stopping.value
  if (!current) return
  submitting.value = true
  conflicts.value = []
  error.value = null
  try {
    await stopSchedule(current.scheduleId, {
      reasonCode: stopForm.value.reasonCode,
      reason: stopForm.value.reason || null,
      version: current.version,
    })
    ElMessage.success('台次已停台（已写入变更记录与通知）')
    stopVisible.value = false
    await load()
  } catch (caught) {
    const apiError = toApiError(caught)
    error.value = apiError
    if (apiError.status === 409) conflicts.value = conflictsFromError(apiError)
    if (apiError.status === 403) {
      ElMessage.error('术中停台需审批权限（m1:schedule:approve）')
    }
  } finally {
    submitting.value = false
  }
}

async function submitEmergency(preemptScheduleId?: string): Promise<void> {
  submitting.value = true
  conflicts.value = []
  error.value = null
  const payload: EmergencyAddRequest = {
    applicationId: emergencyForm.value.applicationId,
    roomId: emergencyForm.value.roomId,
    reasonCode: emergencyForm.value.reasonCode,
    reason: '急诊绿色通道加台',
    expectedStart: emergencyForm.value.plannedStart,
    expectedEnd: emergencyForm.value.plannedEnd,
    sequenceNo: emergencyForm.value.sequenceNo,
    preemptScheduleId: preemptScheduleId ?? (emergencyForm.value.preemptScheduleId || null),
  }
  try {
    const result = await addEmergencyCase(payload)
    ElMessage.success(
      result.approval?.status === 'APPROVED' ? '急诊加台已批准并落台' : '急诊加台已受理',
    )
    emergencyVisible.value = false
    await load()
  } catch (caught) {
    const apiError = toApiError(caught)
    error.value = apiError
    if (apiError.status === 409) {
      conflicts.value = conflictsFromError(apiError)
      ElMessage.warning('存在冲突：可抢占提示中的择期台次后重试')
    }
    if (apiError.status === 403) {
      ElMessage.error('急诊加台需审批权限（m1:schedule:approve）')
    }
  } finally {
    submitting.value = false
  }
}

async function preemptCandidate(conflict: ScheduleConflict): Promise<void> {
  if (!conflict.conflictingScheduleId) return
  const target = schedules.value.find((item) => item.scheduleId === conflict.conflictingScheduleId)
  const targetText = target
    ? `${target.roomNo} 第 ${target.sequenceNo} 台（${target.scheduleId}）`
    : conflict.conflictingScheduleId
  try {
    await ElMessageBox.confirm(
      `将把择期台次 ${targetText} 置为停台并留痕，然后加入急诊台次。确认抢占？`,
      '确认抢占择期台次',
      {
        type: 'warning',
        confirmButtonText: '确认抢占',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true,
      },
    )
  } catch {
    // 取消/关闭：不提交。
    return
  }
  emergencyForm.value.preemptScheduleId = conflict.conflictingScheduleId
  await submitEmergency(conflict.conflictingScheduleId)
}

/** 打开该台次的麻醉记录单（无记录时页面内可创建；权限不足时页面提示 403）。 */
function openRecord(schedule: Schedule): void {
  void router.push(`/m2/records/${schedule.scheduleId}`)
}

/** 术前访视/知情同意/三方核查均以申请标识为路由参数。 */
function openApplicationStep(schedule: Schedule, step: 'preop' | 'consent' | 'three-way'): void {
  void router.push(`/m2/${step}/${schedule.applicationId}`)
}

/**
 * 复苏/随访以麻醉记录单标识为路由参数：先按台次查询记录单，取第一条。
 * 无记录时提示先创建记录单，不跳转；查询失败展示错误并保留卡片。
 */
async function openRecordStep(schedule: Schedule, step: 'pacu' | 'followup'): Promise<void> {
  if (flowLoading.value) return
  flowLoading.value = true
  error.value = null
  try {
    const result = await listAnesthesiaRecords(schedule.scheduleId)
    const record = result.items[0]
    if (!record) {
      ElMessage.warning('请先创建麻醉记录单')
      return
    }
    const target = step === 'pacu' ? 'pacu' : 'followups'
    void router.push(`/m2/${target}/${record.recordId}`)
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    flowLoading.value = false
  }
}

type FlowCommand = 'preop' | 'consent' | 'threeway' | 'record' | 'pacu' | 'followup'

/** 台次卡片「流程入口」统一分发；command 与下拉项一一对应。 */
function handleFlowCommand(command: string, schedule: Schedule): void {
  switch (command as FlowCommand) {
    case 'preop':
      openApplicationStep(schedule, 'preop')
      break
    case 'consent':
      openApplicationStep(schedule, 'consent')
      break
    case 'threeway':
      openApplicationStep(schedule, 'three-way')
      break
    case 'record':
      openRecord(schedule)
      break
    case 'pacu':
      void openRecordStep(schedule, 'pacu')
      break
    case 'followup':
      void openRecordStep(schedule, 'followup')
      break
  }
}

async function openHistory(schedule: Schedule): Promise<void> {
  historySchedule.value = schedule
  historyItems.value = []
  historyVisible.value = true
  historyLoading.value = true
  error.value = null
  try {
    const result = await listScheduleChanges(schedule.scheduleId)
    historyItems.value = result.items
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    historyLoading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="board">
    <header class="board__header">
      <div>
        <h1 class="board__title">排程工作台</h1>
        <p class="board__subtitle">
          按日期与术间查看台次；创建/调整/停台/急诊加台均留痕。冲突不会保存，列表受数据范围约束。
        </p>
      </div>
      <div class="board__actions">
        <el-date-picker
          v-model="date"
          type="date"
          value-format="YYYY-MM-DD"
          data-testid="schedule-date"
          @change="load"
        />
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="canApprove"
          type="danger"
          plain
          data-testid="emergency-button"
          @click="openEmergency"
        >
          急诊加台
        </el-button>
        <el-button
          v-if="canWrite"
          type="primary"
          data-testid="create-schedule-button"
          @click="openCreate"
        >
          新建台次
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="board__alert"
      type="error"
      :closable="false"
      show-icon
      title="操作失败"
    >
      <p>{{ error.message }}</p>
      <p class="board__error-meta">
        错误码：{{ error.code }}
        <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
      </p>
    </el-alert>

    <el-alert
      v-if="conflicts.length > 0"
      class="board__alert"
      type="warning"
      :closable="false"
      show-icon
      title="检测到排程冲突，未保存"
      data-testid="conflict-alert"
    >
      <ul class="board__conflicts">
        <li
          v-for="(conflict, index) in conflicts"
          :key="`${conflict.conflictType}-${index}`"
          class="board__conflict"
          data-testid="conflict-item"
        >
          <el-tag type="danger" effect="plain">{{ conflict.conflictType }}</el-tag>
          <span>{{ conflictTypeLabel(conflict.conflictType) }}</span>
          <span>{{ conflict.message }}</span>
          <span v-if="conflict.conflictingScheduleId" class="board__mono">
            冲突台次：{{ conflict.conflictingScheduleId }}
          </span>
        </li>
      </ul>
    </el-alert>

    <div v-loading="loading" class="board__columns">
      <article
        v-for="column in columns"
        :key="column.room.roomId"
        class="board__column"
        data-testid="room-column"
      >
        <header class="board__column-header">
          <strong>{{ column.room.roomNo }}</strong>
          <span>{{ column.room.roomName }}</span>
          <el-tag size="small" effect="plain">
            {{ roomTypeLabel(column.room.roomType) }}
          </el-tag>
        </header>
        <p v-if="column.items.length === 0" class="board__empty">当日暂无台次</p>
        <ul v-else class="board__list">
          <li
            v-for="schedule in column.items"
            :key="schedule.scheduleId"
            class="board__item"
            data-testid="schedule-row"
          >
            <div class="board__item-main">
              <span class="board__time">
                {{ formatTime(schedule.plannedStart) }}–{{ formatTime(schedule.plannedEnd) }}
              </span>
              <el-tag size="small" :type="statusType(schedule.status)" effect="plain">
                {{ statusLabel(schedule.status) }}
              </el-tag>
              <el-tag
                v-if="schedule.priority === 'EMERGENCY'"
                size="small"
                type="danger"
                effect="dark"
              >
                急诊
              </el-tag>
            </div>
            <div class="board__item-meta">
              <span>第 {{ schedule.sequenceNo }} 台</span>
              <span>{{ schedule.patientName }}</span>
              <span class="board__mono">{{ schedule.patientNo }}</span>
            </div>
            <div class="board__item-actions">
              <el-dropdown
                v-if="hasFlowEntries"
                trigger="click"
                :disabled="flowLoading"
                data-testid="flow-menu"
                @command="(command: string) => handleFlowCommand(command, schedule)"
              >
                <el-button size="small" text type="primary" data-testid="flow-menu-trigger">
                  流程入口
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="canPreopRead" command="preop" data-testid="flow-preop">
                      术前访视
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canConsentRead"
                      command="consent"
                      data-testid="flow-consent"
                    >
                      知情同意
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canThreeWayRead"
                      command="threeway"
                      data-testid="flow-threeway"
                    >
                      三方核查
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canReadRecord"
                      command="record"
                      data-testid="flow-record"
                    >
                      麻醉记录
                    </el-dropdown-item>
                    <el-dropdown-item v-if="canPacuRead" command="pacu" data-testid="flow-pacu">
                      复苏记录（PACU）
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canFollowupRead"
                      command="followup"
                      data-testid="flow-followup"
                    >
                      术后随访
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button
                v-if="canReadRecord"
                size="small"
                text
                type="primary"
                data-testid="record-link"
                @click="openRecord(schedule)"
              >
                麻醉记录
              </el-button>
              <el-button
                size="small"
                text
                data-testid="history-button"
                @click="openHistory(schedule)"
              >
                变更历史
              </el-button>
              <template
                v-if="
                  canWrite &&
                  (schedule.status === 'PLANNED' ||
                    schedule.status === 'PUBLISHED' ||
                    schedule.status === 'DELAYED')
                "
              >
                <el-button
                  size="small"
                  text
                  data-testid="adjust-button"
                  @click="openAdjust(schedule)"
                >
                  调整
                </el-button>
                <el-button
                  size="small"
                  text
                  type="danger"
                  data-testid="cancel-button"
                  @click="openStop(schedule)"
                >
                  停台
                </el-button>
              </template>
              <el-button
                v-else-if="canWrite && schedule.status === 'IN_PROGRESS'"
                size="small"
                text
                type="danger"
                data-testid="stop-in-progress-button"
                @click="openStop(schedule)"
              >
                术中停台（{{ canApprove ? '审批' : '需审批权限' }}）
              </el-button>
            </div>
          </li>
        </ul>
      </article>
    </div>

    <el-dialog v-model="createVisible" title="新建台次" width="520px">
      <el-form label-width="96px">
        <el-form-item label="申请标识">
          <el-input
            v-model="form.applicationId"
            data-testid="application-input"
            placeholder="手术申请 ID（UUID）"
          />
        </el-form-item>
        <el-form-item label="术间">
          <el-select v-model="form.roomId" data-testid="room-select" placeholder="选择术间">
            <el-option
              v-for="room in rooms"
              :key="room.roomId"
              :label="`${room.roomNo} ${room.roomName}`"
              :value="room.roomId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="计划开始">
          <el-date-picker
            v-model="form.plannedStart"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            data-testid="start-input"
          />
        </el-form-item>
        <el-form-item label="计划结束">
          <el-date-picker
            v-model="form.plannedEnd"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            data-testid="end-input"
          />
        </el-form-item>
        <el-form-item label="台次序号">
          <el-input-number v-model="form.sequenceNo" :min="1" data-testid="sequence-input" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="precheck-button" @click="precheck">冲突预检</el-button>
        <el-button
          :loading="submitting"
          type="primary"
          data-testid="submit-button"
          @click="submitCreate"
        >
          提交
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="adjustVisible" title="调整台次（改台）" width="520px">
      <el-form label-width="96px">
        <el-form-item label="术间">
          <el-select v-model="form.roomId" data-testid="adjust-room-select">
            <el-option
              v-for="room in rooms"
              :key="room.roomId"
              :label="`${room.roomNo} ${room.roomName}`"
              :value="room.roomId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="计划开始">
          <el-date-picker
            v-model="form.plannedStart"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
          />
        </el-form-item>
        <el-form-item label="计划结束">
          <el-date-picker
            v-model="form.plannedEnd"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
          />
        </el-form-item>
        <el-form-item label="台次序号">
          <el-input-number v-model="form.sequenceNo" :min="1" />
        </el-form-item>
        <el-form-item label="改台原因码">
          <el-select
            v-model="form.reasonCode"
            data-testid="modify-reason-select"
            placeholder="必选：改台原因码"
          >
            <el-option
              v-for="option in modifyReasonOptions"
              :key="option.value"
              :label="`${option.label}（${option.value}）`"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="form.reason" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
          :loading="submitting"
          type="primary"
          data-testid="adjust-submit"
          @click="submitAdjust"
        >
          保存调整
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stopVisible" title="停台" width="520px">
      <el-alert
        v-if="stopping?.status === 'IN_PROGRESS'"
        class="board__alert"
        type="warning"
        :closable="false"
        show-icon
        title="术中停台需审批"
        data-testid="stop-approval-alert"
      >
        该台次已开始，提交后将记录审批人（需 m1:schedule:approve）。
        <template v-if="!canApprove">
          <br />
          当前账号缺少 m1:schedule:approve，提交将被服务端拒绝（403）。
        </template>
      </el-alert>
      <el-form label-width="96px">
        <el-form-item label="原因码">
          <el-select v-model="stopForm.reasonCode" data-testid="stop-reason-select">
            <el-option
              v-for="option in stopReasonOptions"
              :key="option.value"
              :label="`${option.label}（${option.value}）`"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="原因说明">
          <el-input
            v-model="stopForm.reason"
            maxlength="500"
            data-testid="stop-reason-input"
            placeholder="可选（不含患者标识）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
          :loading="submitting"
          type="danger"
          data-testid="stop-submit"
          @click="submitStop"
        >
          确认停台
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="emergencyVisible" title="急诊加台（需审批）" width="560px">
      <el-alert class="board__alert" type="info" :closable="false" show-icon title="急诊优先于择期">
        提交后写入急诊审批记录；如与择期台次冲突，可按提示抢占该择期台次（原台次置停台并留痕）。
      </el-alert>
      <el-form label-width="110px">
        <el-form-item label="申请标识">
          <el-input
            v-model="emergencyForm.applicationId"
            data-testid="emergency-application-input"
            placeholder="急诊手术申请 ID（UUID）"
          />
        </el-form-item>
        <el-form-item label="目标术间">
          <el-select v-model="emergencyForm.roomId" data-testid="emergency-room-select">
            <el-option
              v-for="room in rooms"
              :key="room.roomId"
              :label="`${room.roomNo} ${room.roomName}`"
              :value="room.roomId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="急诊开始">
          <el-date-picker
            v-model="emergencyForm.plannedStart"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            data-testid="emergency-start-input"
          />
        </el-form-item>
        <el-form-item label="急诊结束">
          <el-date-picker
            v-model="emergencyForm.plannedEnd"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
          />
        </el-form-item>
        <el-form-item label="台次序号">
          <el-input-number v-model="emergencyForm.sequenceNo" :min="1" />
        </el-form-item>
        <el-form-item label="原因码">
          <el-select v-model="emergencyForm.reasonCode" data-testid="emergency-reason-select">
            <el-option
              v-for="option in emergencyReasonOptions"
              :key="option.value"
              :label="`${option.label}（${option.value}）`"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="抢占台次">
          <el-input
            v-model="emergencyForm.preemptScheduleId"
            data-testid="preempt-input"
            placeholder="可选：冲突择期台次 ID"
          />
        </el-form-item>
      </el-form>
      <div
        v-if="conflicts.length > 0"
        class="board__emergency-conflicts"
        data-testid="emergency-conflicts"
      >
        <p class="board__subtitle">
          当前时段存在冲突：未指定抢占将返回 409；可抢占下列择期台次（原台次将停台并留痕）。
        </p>
        <ul class="board__conflicts">
          <li
            v-for="(conflict, index) in conflicts"
            :key="`emergency-${conflict.conflictType}-${index}`"
            class="board__conflict"
          >
            <el-tag type="danger" effect="plain">{{ conflict.conflictType }}</el-tag>
            <span>{{ conflictTypeLabel(conflict.conflictType) }}</span>
            <span>{{ conflict.message }}</span>
            <span v-if="conflict.conflictingScheduleId" class="board__mono">
              冲突台次：{{ conflict.conflictingScheduleId }}
            </span>
            <el-button
              v-if="conflict.conflictingScheduleId"
              size="small"
              type="danger"
              text
              data-testid="emergency-preempt-button"
              @click="preemptCandidate(conflict)"
            >
              抢占该择期台次（需审批）
            </el-button>
          </li>
        </ul>
      </div>
      <template #footer>
        <el-button
          :loading="submitting"
          type="danger"
          data-testid="emergency-submit"
          @click="submitEmergency()"
        >
          提交急诊加台
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="historyVisible"
      title="台次变更历史（停台/改台/急诊审批）"
      size="520px"
      data-testid="history-drawer"
    >
      <p v-if="historySchedule" class="board__subtitle">
        {{ historySchedule.roomNo }} 第 {{ historySchedule.sequenceNo }} 台 ·
        {{ historySchedule.patientName }}
      </p>
      <div v-loading="historyLoading">
        <p v-if="!historyLoading && historyItems.length === 0" class="board__empty">暂无变更记录</p>
        <el-timeline v-else>
          <el-timeline-item
            v-for="change in historyItems"
            :key="change.changeId"
            :timestamp="formatDateTime(change.requestedAt)"
            data-testid="history-item"
          >
            <div class="board__history-item">
              <el-tag
                :type="change.changeType === 'EMERGENCY' ? 'danger' : 'warning'"
                size="small"
                effect="plain"
              >
                {{ changeTypeLabel(change.changeType) }}
              </el-tag>
              <el-tag
                :type="change.status === 'APPROVED' ? 'success' : 'info'"
                size="small"
                effect="plain"
              >
                {{ approvalLabel(change.status) }}
              </el-tag>
              <span class="board__mono">{{ change.reasonCode }}</span>
              <span>{{ reasonCodeLabel(change.reasonCode) }}</span>
            </div>
            <p v-if="change.reason" class="board__history-reason">{{ change.reason }}</p>
            <p class="board__history-meta">
              申请/执行：{{ change.requestedBy ?? '—' }}
              <template v-if="change.approvedBy"> · 审批：{{ change.approvedBy }}</template>
              <template v-if="change.approvedAt">
                · {{ formatDateTime(change.approvedAt) }}
              </template>
            </p>
            <p v-if="change.preemptScheduleId" class="board__history-meta board__mono">
              抢占关系：{{ change.preemptScheduleId }}
            </p>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.board {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.board__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.board__title {
  margin: 0 0 4px;
  font-size: 22px;
}

.board__subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.board__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.board__error-meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.board__conflicts {
  margin: 4px 0 0;
  padding-left: 18px;
}

.board__conflict {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.board__columns {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  min-height: 160px;
}

.board__column {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-bg-color);
}

.board__column-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
}

.board__empty {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.board__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.board__item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
}

.board__item-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.board__time {
  font-weight: 600;
}

.board__item-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.board__mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.board__item-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  margin-top: 4px;
}

.board__emergency-conflicts {
  margin-top: 8px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.board__history-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.board__history-reason {
  margin: 4px 0 0;
  font-size: 13px;
}

.board__history-meta {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
