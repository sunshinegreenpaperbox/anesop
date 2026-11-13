<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelSchedule,
  checkScheduleConflicts,
  createSchedule,
  listRooms,
  listSchedules,
  updateSchedule,
  type OperatingRoom,
  type OperatingRoomType,
  type Schedule,
  type ScheduleConflict,
  type UpdateScheduleRequest,
} from '../api/m1'
import { ApiError } from '../../../api/http'
import { formatLocalDate } from '../utils/date'
import { useAuthStore } from '../../../stores/auth'

const auth = useAuthStore()

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

interface ScheduleForm {
  applicationId: string
  roomId: string
  plannedStart: string
  plannedEnd: string
  sequenceNo: number
  reason: string
}

const form = ref<ScheduleForm>({
  applicationId: '',
  roomId: '',
  plannedStart: '',
  plannedEnd: '',
  sequenceNo: 1,
  reason: '',
})

const canWrite = computed(() => auth.hasPermission('m1:schedule:write'))

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

function roomTypeLabel(type: OperatingRoomType | undefined): string {
  return type ? (roomTypeLabels[type] ?? type) : '—'
}

function statusLabel(status: Schedule['status']): string {
  return statusLabels[status] ?? status
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
  const conflicts = isRecord(apiError.rawBody) ? apiError.rawBody.conflicts : undefined
  if (Array.isArray(conflicts)) {
    return conflicts.filter(isRecord).map((item) => ({
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
  }
  conflicts.value = []
  error.value = null
  adjustVisible.value = true
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
    }
    await updateSchedule(current.scheduleId, payload)
    ElMessage.success('台次已调整')
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

async function handleCancel(schedule: Schedule): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消 ${schedule.roomNo} 第 ${schedule.sequenceNo} 台？取消后保留审计记录。`,
      '取消台次',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  try {
    await cancelSchedule(schedule.scheduleId, {
      reasonCode: 'SCHEDULE_CHANGE',
      reason: '排程工作台取消（演示）',
      version: schedule.version,
    })
    ElMessage.success('台次已取消')
    await load()
  } catch (caught) {
    const apiError = toApiError(caught)
    error.value = apiError
    if (apiError.status === 409) conflicts.value = conflictsFromError(apiError)
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
          按日期与术间查看台次；创建/调整前执行冲突检测，冲突不会保存。列表受数据范围约束。
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
            </div>
            <div class="board__item-meta">
              <span>第 {{ schedule.sequenceNo }} 台</span>
              <span>{{ schedule.patientName }}</span>
              <span class="board__mono">{{ schedule.patientNo }}</span>
            </div>
            <div
              v-if="canWrite && (schedule.status === 'PLANNED' || schedule.status === 'PUBLISHED')"
              class="board__item-actions"
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
                @click="handleCancel(schedule)"
              >
                取消
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

    <el-dialog v-model="adjustVisible" title="调整台次" width="520px">
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
  justify-content: flex-end;
  margin-top: 4px;
}
</style>
