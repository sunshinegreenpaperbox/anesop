<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  cancelStaffSchedule,
  createStaffSchedule,
  listStaff,
  listStaffSchedules,
  scheduleStatusLabels,
  staffShiftLabels,
  type CreateStaffScheduleRequest,
  type Staff,
  type StaffSchedule,
} from '../api/staff'

/**
 * 排班（M4-M13-01，契约 staff.yaml 0.1.0）。
 *
 * - 班次 DAY/NIGHT/ON_CALL（方案书“日常班/值班/备班”映射；三班制【待确认】）；
 * - 冲突：同一人员同一日期同一班次、同一术间同一日期同一班次 → 409 + details（含冲突排班标识）；
 * - 取消保留记录并释放名额；已取消不可复活；维护需要 m4:schedule:manage。
 */

const auth = useAuthStore()

const schedules = ref<StaffSchedule[]>([])
const staff = ref<Staff[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const conflicts = ref<string[]>([])
const createVisible = ref(false)
const cancelVisible = ref(false)
const cancelTarget = ref<StaffSchedule | null>(null)

const filters = reactive({ staffId: '', from: '', to: '', shift: '' })
const form = reactive({
  staffId: '',
  scheduleDate: '',
  shift: 'DAY' as 'DAY' | 'NIGHT' | 'ON_CALL',
  roomId: '',
  note: '',
})
const cancelForm = reactive({ reason: '' })

const canManage = computed(() => auth.hasPermission('m4:schedule:manage'))
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权管理排班（m4:schedule:manage）或超出数据范围'
  if (error.value.status === 409) return '排班冲突或已被他人修改（见冲突明细）'
  if (error.value.status === 404) return '排班不存在或超出数据范围'
  if (error.value.status === 422) return '人员已离职/停用或科室不可用'
  if (error.value.status === 400) return '排班字段校验失败（见下发明细）'
  return '排班操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  conflicts.value = []
  if (cause instanceof ApiError) {
    for (const detail of cause.details) {
      conflicts.value.push(`${detail.field}：${detail.reason}`)
    }
  }
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await listStaffSchedules({
      staffId: filters.staffId || undefined,
      from: filters.from || undefined,
      to: filters.to || undefined,
      shift: filters.shift || undefined,
      limit: 200,
    })
    schedules.value = result.items
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

async function loadStaff(): Promise<void> {
  try {
    const list = await listStaff({ size: 200 })
    staff.value = list.items
  } catch (cause) {
    handleError(cause)
  }
}

function openCreate(): void {
  form.staffId = filters.staffId || staff.value[0]?.staffId || ''
  form.scheduleDate = ''
  form.shift = 'DAY'
  form.roomId = ''
  form.note = ''
  createVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.staffId || !form.scheduleDate) {
    ElMessage.warning('人员与日期不能为空')
    return
  }
  saving.value = true
  error.value = null
  conflicts.value = []
  try {
    const payload: CreateStaffScheduleRequest = {
      staffId: form.staffId,
      scheduleDate: form.scheduleDate,
      shift: form.shift,
      roomId: form.roomId.trim() || undefined,
      departmentId: staff.value.find((item) => item.staffId === form.staffId)?.departmentId ?? '',
      note: form.note.trim() || undefined,
    }
    await createStaffSchedule(payload)
    ElMessage.success('排班已创建')
    createVisible.value = false
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function openCancel(schedule: StaffSchedule): void {
  cancelTarget.value = schedule
  cancelForm.reason = ''
  cancelVisible.value = true
}

async function confirmCancel(): Promise<void> {
  if (!cancelTarget.value) return
  saving.value = true
  error.value = null
  try {
    await cancelStaffSchedule(cancelTarget.value.scheduleId, {
      reason: cancelForm.reason.trim() || undefined,
      version: cancelTarget.value.version,
    })
    ElMessage.success('排班已取消（记录保留）')
    cancelVisible.value = false
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadStaff()
  await load()
})
</script>

<template>
  <section class="staff-schedule">
    <header class="page-header">
      <div>
        <h1>人员排班</h1>
        <p class="hint">
          同人员同日同班次、同术间同日同班次不可重复；已取消的排班不占用名额（记录保留）。
        </p>
      </div>
      <div class="actions">
        <el-select
          v-model="filters.staffId"
          data-testid="sched-staff-filter"
          clearable
          placeholder="全部人员"
          style="width: 190px"
        >
          <el-option
            v-for="item in staff"
            :key="item.staffId"
            :label="`${item.staffNo} ${item.name}`"
            :value="item.staffId"
          />
        </el-select>
        <el-input
          v-model="filters.from"
          data-testid="sched-from"
          placeholder="起 YYYY-MM-DD"
          style="width: 150px"
        />
        <el-input
          v-model="filters.to"
          data-testid="sched-to"
          placeholder="止 YYYY-MM-DD"
          style="width: 150px"
        />
        <el-select
          v-model="filters.shift"
          data-testid="sched-shift-filter"
          clearable
          placeholder="全部班次"
          style="width: 120px"
        >
          <el-option
            v-for="(label, value) in staffShiftLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-button data-testid="sched-refresh" @click="load">查询</el-button>
        <el-button v-if="canManage" type="primary" data-testid="sched-create" @click="openCreate">
          新建排班
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      type="error"
      :title="errorTitle"
      :description="`${error.message}${error.traceId ? `（traceId: ${error.traceId}）` : ''}`"
      show-icon
      :closable="false"
      data-testid="sched-error"
    />
    <ul v-if="conflicts.length" class="conflicts" data-testid="sched-conflicts">
      <li v-for="item in conflicts" :key="item">{{ item }}</li>
    </ul>

    <el-table
      v-loading="loading"
      :data="schedules"
      data-testid="sched-table"
      border
      style="width: 100%"
    >
      <el-table-column prop="scheduleDate" label="日期" width="120" />
      <el-table-column label="班次" width="90">
        <template #default="{ row }">{{ staffShiftLabels[row.shift] ?? row.shift }}</template>
      </el-table-column>
      <el-table-column prop="staffNo" label="工号" width="140" />
      <el-table-column prop="roomId" label="术间" min-width="150">
        <template #default="{ row }">{{ row.roomId ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PLANNED' ? 'success' : 'info'" size="small">
            {{ scheduleStatusLabels[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            v-if="canManage && row.status === 'PLANNED'"
            link
            type="danger"
            data-testid="sched-cancel"
            @click="openCancel(row)"
          >
            取消
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span data-testid="sched-empty">暂无排班</span>
      </template>
    </el-table>

    <el-dialog v-model="createVisible" title="新建排班" width="520px">
      <el-form label-width="90px" data-testid="sched-form">
        <el-form-item label="人员">
          <el-select
            v-model="form.staffId"
            data-testid="sched-staff"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in staff"
              :key="item.staffId"
              :label="`${item.staffNo} ${item.name}`"
              :value="item.staffId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-input v-model="form.scheduleDate" data-testid="sched-date" placeholder="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="班次">
          <el-select v-model="form.shift" data-testid="sched-shift" style="width: 100%">
            <el-option
              v-for="(label, value) in staffShiftLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="术间">
          <el-input
            v-model="form.roomId"
            data-testid="sched-room"
            placeholder="术间 UUID（可空）"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" data-testid="sched-note" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="sched-submit" @click="submit">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cancelVisible" title="取消排班" width="460px">
      <el-form label-width="90px" data-testid="sched-cancel-form">
        <el-form-item label="原因">
          <el-input v-model="cancelForm.reason" data-testid="sched-cancel-reason" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelVisible = false">返回</el-button>
        <el-button
          type="danger"
          :loading="saving"
          data-testid="sched-cancel-submit"
          @click="confirmCancel"
        >
          确认取消
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.staff-schedule {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0;
}
.conflicts {
  margin: 0;
  padding-left: 18px;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
