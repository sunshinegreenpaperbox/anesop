<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelApplication,
  importApplications,
  listApplications,
  type ApplicationFilters,
  type ApplicationStatus,
  type ApplicationUrgency,
  type SurgeryApplication,
  type SurgeryApplicationPage,
} from '../api/m1'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const applications = ref<SurgeryApplication[]>([])
const meta = ref<SurgeryApplicationPage['meta']>({ page: 1, size: 10, total: 0, totalPages: 0 })
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const importing = ref(false)
const error = ref<ApiError | null>(null)
const filters = ref<ApplicationFilters>({ status: '', urgency: '', departmentName: '' })

const canWrite = computed(() => auth.hasPermission('m1:application:write'))

const statusOptions: Array<{ value: ApplicationStatus; label: string }> = [
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'ACCEPTED', label: '已受理' },
  { value: 'SCHEDULED', label: '已排程' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'REJECTED', label: '已驳回' },
]

const urgencyOptions: Array<{ value: ApplicationUrgency; label: string }> = [
  { value: 'ELECTIVE', label: '择期' },
  { value: 'URGENT', label: '限期' },
  { value: 'EMERGENCY', label: '急诊' },
]

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : timeFormatter.format(date)
}

function statusLabel(status: ApplicationStatus): string {
  return statusOptions.find((item) => item.value === status)?.label ?? status
}

function statusType(status: ApplicationStatus): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'SUBMITTED') return 'info'
  if (status === 'ACCEPTED' || status === 'SCHEDULED') return 'success'
  if (status === 'CANCELLED') return 'danger'
  return 'warning'
}

function urgencyLabel(urgency: ApplicationUrgency): string {
  return urgencyOptions.find((item) => item.value === urgency)?.label ?? urgency
}

function urgencyType(urgency: ApplicationUrgency): 'info' | 'warning' | 'danger' {
  if (urgency === 'EMERGENCY') return 'danger'
  if (urgency === 'URGENT') return 'warning'
  return 'info'
}

function isTerminal(status: ApplicationStatus): boolean {
  return status === 'CANCELLED' || status === 'REJECTED'
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '加载手术申请失败',
  })
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await listApplications(filters.value, page.value, size.value)
    applications.value = data.items
    meta.value = data.meta
  } catch (caught) {
    error.value = toApiError(caught)
    applications.value = []
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function resetFilters(): void {
  filters.value = { status: '', urgency: '', departmentName: '' }
  search()
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

function openDetail(application: SurgeryApplication): void {
  void router.push({ name: 'm1-application-detail', params: { id: application.applicationId } })
}

async function handleImport(): Promise<void> {
  importing.value = true
  error.value = null
  try {
    const result = await importApplications({ count: 4 })
    ElMessage.success(
      `模拟 HIS 导入完成：新增 ${result.importedCount} 条，幂等跳过 ${result.skippedCount} 条`,
    )
    page.value = 1
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    importing.value = false
  }
}

async function handleCancel(application: SurgeryApplication): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消申请 ${application.hisApplicationNo}？取消后进入终态，不可恢复。`,
      '取消手术申请',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  try {
    await cancelApplication(application.applicationId, {
      reasonCode: 'PATIENT_CONDITION',
      reason: '排程工作台取消（演示）',
      version: application.version,
    })
    ElMessage.success('申请已取消')
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="applications">
    <header class="applications__header">
      <div>
        <h1 class="applications__title">手术申请</h1>
        <p class="applications__subtitle">
          HIS
          申请接收与校验（M1）。列表受数据范围约束；住院号只展示脱敏值，页面不展示证件号与手机号。
        </p>
      </div>
      <div class="applications__actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="canWrite"
          type="primary"
          :loading="importing"
          data-testid="import-button"
          @click="handleImport"
        >
          模拟 HIS 导入
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="applications__alert"
      type="error"
      :closable="false"
      show-icon
      title="操作失败"
    >
      <p class="applications__error-message">{{ error.message }}</p>
      <p class="applications__error-meta">
        错误码：{{ error.code }}
        <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
      </p>
    </el-alert>

    <el-card class="applications__card" shadow="never">
      <div class="applications__filters">
        <el-select
          v-model="filters.status"
          class="applications__filter"
          placeholder="全部状态"
          clearable
          data-testid="status-filter"
        >
          <el-option
            v-for="item in statusOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select
          v-model="filters.urgency"
          class="applications__filter"
          placeholder="全部紧急度"
          clearable
          data-testid="urgency-filter"
        >
          <el-option
            v-for="item in urgencyOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-input
          v-model="filters.departmentName"
          class="applications__filter applications__filter--wide"
          placeholder="申请科室（精确匹配）"
          clearable
          data-testid="department-filter"
        />
        <el-button type="primary" data-testid="search-button" @click="search">查询</el-button>
        <el-button data-testid="reset-button" @click="resetFilters">重置</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="applications"
        stripe
        class="applications__table"
        row-key="applicationId"
      >
        <el-table-column prop="hisApplicationNo" label="HIS 申请号" min-width="170" />
        <el-table-column label="患者" min-width="180">
          <template #default="{ row }">
            <div>{{ row.patient.name }}</div>
            <div class="applications__muted">
              {{
                row.patient.gender === 'FEMALE'
                  ? '女'
                  : row.patient.gender === 'MALE'
                    ? '男'
                    : '未知'
              }}
              · {{ row.patient.age }} 岁 · {{ row.patient.inpatientNoMasked }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="patient.departmentName" label="科室" min-width="130" />
        <el-table-column
          prop="surgeryName"
          label="拟施术式"
          min-width="200"
          show-overflow-tooltip
        />
        <el-table-column label="紧急度" width="90">
          <template #default="{ row }">
            <el-tag :type="urgencyType(row.urgency)" effect="plain" size="small">
              {{ urgencyLabel(row.urgency) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="canWrite && !isTerminal(row.status)"
              link
              type="danger"
              data-testid="cancel-button"
              @click="handleCancel(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <p class="applications__empty">
            暂无手术申请。可点击“模拟 HIS 导入”生成 SYNTHETIC 演示数据。
          </p>
        </template>
      </el-table>

      <div class="applications__pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="meta.total"
          :page-size="size"
          :current-page="page"
          background
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.applications {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-4);
}

.applications__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--anesop-space-3);
}

.applications__title {
  margin: 0;
  font-size: var(--anesop-font-size-xl);
  font-weight: var(--anesop-font-weight-semibold);
}

.applications__subtitle {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.applications__actions {
  display: flex;
  gap: var(--anesop-space-2);
}

.applications__alert {
  margin-bottom: var(--anesop-space-2);
}

.applications__error-message {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.applications__error-meta {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}

.applications__card {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.applications__card :deep(.el-card__body) {
  padding: var(--anesop-space-4);
}

.applications__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--anesop-space-2);
  margin-bottom: var(--anesop-space-4);
}

.applications__filter {
  width: 160px;
}

.applications__filter--wide {
  width: 220px;
}

.applications__muted {
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
}

.applications__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--anesop-space-4);
}

.applications__empty {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}
</style>
