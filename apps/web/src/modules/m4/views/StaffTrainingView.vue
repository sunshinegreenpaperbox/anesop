<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  createStaffTraining,
  listExpiringStaffTrainings,
  listStaff,
  listStaffTrainings,
  trainingStatusLabels,
  type CreateStaffTrainingRequest,
  type ExpiringTrainingList,
  type Staff,
  type StaffTraining,
} from '../api/staff'

/**
 * 培训与到期提醒（M4-M13-01，契约 staff.yaml 0.1.0）。
 *
 * - 记录课程/完成时间/到期时间/证书号；同一人员同一课程编码唯一；
 * - 到期提醒默认 30 天（方案书“证书到期前 30 天提醒”），含已过期记录便于清零；
 * - 登记需要 m4:training:manage；查询需要 m4:staff:read。
 */

const auth = useAuthStore()

const staff = ref<Staff[]>([])
const selectedStaffId = ref('')
const trainings = ref<StaffTraining[]>([])
const expiring = ref<ExpiringTrainingList | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const dialogVisible = ref(false)
const days = ref(30)

const form = reactive({
  courseCode: '',
  courseName: '',
  completedOn: '',
  expiresOn: '',
  certificateNo: '',
})

const canManage = computed(() => auth.hasPermission('m4:training:manage'))
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权登记培训（m4:training:manage）或超出数据范围'
  if (error.value.status === 409) return '同一人员已登记该课程编码'
  if (error.value.status === 404) return '人员档案不存在或超出数据范围'
  if (error.value.status === 422) return '人员已离职/停用'
  if (error.value.status === 400) return '培训字段校验失败（见下发明细）'
  return '培训操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function loadStaff(): Promise<void> {
  try {
    const list = await listStaff({ size: 200 })
    staff.value = list.items
    if (!selectedStaffId.value && list.items.length > 0) {
      selectedStaffId.value = list.items[0].staffId
    }
  } catch (cause) {
    handleError(cause)
  }
}

async function loadTrainings(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await listStaffTrainings({
      staffId: selectedStaffId.value || undefined,
      limit: 200,
    })
    trainings.value = result.items
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

async function loadExpiring(): Promise<void> {
  error.value = null
  try {
    expiring.value = await listExpiringStaffTrainings({ days: days.value })
  } catch (cause) {
    handleError(cause)
  }
}

function openCreate(): void {
  form.courseCode = ''
  form.courseName = ''
  form.completedOn = ''
  form.expiresOn = ''
  form.certificateNo = ''
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (
    !selectedStaffId.value ||
    !form.courseCode.trim() ||
    !form.courseName.trim() ||
    !form.completedOn
  ) {
    ElMessage.warning('人员、课程编码、课程名称与完成日期不能为空')
    return
  }
  saving.value = true
  error.value = null
  try {
    const payload: CreateStaffTrainingRequest = {
      staffId: selectedStaffId.value,
      courseCode: form.courseCode.trim(),
      courseName: form.courseName.trim(),
      completedOn: form.completedOn,
      expiresOn: form.expiresOn.trim() || undefined,
      certificateNo: form.certificateNo.trim() || undefined,
    }
    await createStaffTraining(payload)
    ElMessage.success('培训记录已登记')
    dialogVisible.value = false
    await Promise.all([loadTrainings(), loadExpiring()])
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function daysLabel(row: StaffTraining): string {
  if (row.daysUntilExpiry === undefined || row.daysUntilExpiry === null) return '—'
  return row.daysUntilExpiry < 0
    ? `已过期 ${Math.abs(row.daysUntilExpiry)} 天`
    : `${row.daysUntilExpiry} 天`
}

onMounted(async () => {
  await loadStaff()
  await Promise.all([loadTrainings(), loadExpiring()])
})
</script>

<template>
  <section class="staff-training">
    <header class="page-header">
      <div>
        <h1>培训与到期提醒</h1>
        <p class="hint">
          培训记录与证书到期提醒（默认 30 天窗口，含已过期）；同一人员同一课程编码只能登记一次。
        </p>
      </div>
      <div class="actions">
        <el-select
          v-model="selectedStaffId"
          data-testid="trn-staff-select"
          filterable
          style="width: 220px"
          @change="loadTrainings"
        >
          <el-option
            v-for="item in staff"
            :key="item.staffId"
            :label="`${item.staffNo} ${item.name}`"
            :value="item.staffId"
          />
        </el-select>
        <el-button data-testid="trn-refresh" @click="loadTrainings">查询</el-button>
        <el-button v-if="canManage" type="primary" data-testid="trn-create" @click="openCreate">
          登记培训
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
      data-testid="trn-error"
    />

    <el-table
      v-loading="loading"
      :data="trainings"
      data-testid="trn-table"
      border
      style="width: 100%"
    >
      <el-table-column prop="courseCode" label="课程编码" width="170" />
      <el-table-column prop="courseName" label="课程名称" min-width="200" />
      <el-table-column prop="completedOn" label="完成日期" width="120" />
      <el-table-column prop="expiresOn" label="到期日期" width="120">
        <template #default="{ row }">{{ row.expiresOn ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag
            :type="
              row.status === 'EXPIRED'
                ? 'danger'
                : row.status === 'EXPIRING'
                  ? 'warning'
                  : 'success'
            "
            size="small"
          >
            {{ trainingStatusLabels[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="距到期" width="130">
        <template #default="{ row }">{{ daysLabel(row) }}</template>
      </el-table-column>
      <template #empty>
        <span data-testid="trn-empty">暂无培训记录</span>
      </template>
    </el-table>

    <el-card shadow="never">
      <template #header>到期提醒（含已过期，便于清零）</template>
      <div class="actions">
        <el-input-number v-model="days" :min="1" :max="365" data-testid="trn-expiring-days" />
        <span>天内到期</span>
        <el-button data-testid="trn-expiring-run" @click="loadExpiring">查询提醒</el-button>
        <span v-if="expiring" class="hint" data-testid="trn-expiring-asof">
          基准日 {{ expiring.asOf }}，窗口 {{ expiring.days }} 天，共 {{ expiring.items.length }} 条
        </span>
      </div>
      <el-table
        :data="expiring?.items ?? []"
        data-testid="trn-expiring-table"
        border
        size="small"
        style="margin-top: 8px"
      >
        <el-table-column prop="staffNo" label="工号" width="140" />
        <el-table-column prop="courseName" label="课程" min-width="200" />
        <el-table-column prop="expiresOn" label="到期日期" width="120" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{
            trainingStatusLabels[row.status] ?? row.status
          }}</template>
        </el-table-column>
        <el-table-column label="距到期" width="130">
          <template #default="{ row }">{{ daysLabel(row) }}</template>
        </el-table-column>
        <template #empty>
          <span data-testid="trn-expiring-empty">窗口内无到期培训</span>
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="登记培训记录" width="520px">
      <el-form label-width="100px" data-testid="trn-form">
        <el-form-item label="课程编码">
          <el-input v-model="form.courseCode" data-testid="trn-course-code" />
        </el-form-item>
        <el-form-item label="课程名称">
          <el-input v-model="form.courseName" data-testid="trn-course-name" />
        </el-form-item>
        <el-form-item label="完成日期">
          <el-input
            v-model="form.completedOn"
            data-testid="trn-completed"
            placeholder="YYYY-MM-DD"
          />
        </el-form-item>
        <el-form-item label="到期日期">
          <el-input
            v-model="form.expiresOn"
            data-testid="trn-expires"
            placeholder="YYYY-MM-DD（可空）"
          />
        </el-form-item>
        <el-form-item label="证书编号">
          <el-input v-model="form.certificateNo" data-testid="trn-cert" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="trn-submit" @click="submit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.staff-training {
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
}
</style>
