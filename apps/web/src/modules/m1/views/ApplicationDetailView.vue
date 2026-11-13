<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelApplication,
  getApplication,
  type ApplicationStatus,
  type ApplicationUrgency,
  type SurgeryApplication,
} from '../api/m1'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const application = ref<SurgeryApplication | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)

const canWrite = computed(() => auth.hasPermission('m1:application:write'))
const canReadPreop = computed(() => auth.hasPermission('m2:preop:read'))

const statusLabels: Record<ApplicationStatus, string> = {
  SUBMITTED: '已提交',
  ACCEPTED: '已受理',
  SCHEDULED: '已排程',
  CANCELLED: '已取消',
  REJECTED: '已驳回',
}

const urgencyLabels: Record<ApplicationUrgency, string> = {
  ELECTIVE: '择期',
  URGENT: '限期',
  EMERGENCY: '急诊',
}

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
  return statusLabels[status] ?? status
}

function statusType(status: ApplicationStatus): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'SUBMITTED') return 'info'
  if (status === 'ACCEPTED' || status === 'SCHEDULED') return 'success'
  if (status === 'CANCELLED') return 'danger'
  return 'warning'
}

function urgencyLabel(urgency: ApplicationUrgency): string {
  return urgencyLabels[urgency] ?? urgency
}

function urgencyType(urgency: ApplicationUrgency): 'info' | 'warning' | 'danger' {
  if (urgency === 'EMERGENCY') return 'danger'
  if (urgency === 'URGENT') return 'warning'
  return 'info'
}

function fromLabel(from: ApplicationStatus | null | undefined): string {
  return from ? statusLabel(from) : '创建'
}

function isTerminal(status: ApplicationStatus): boolean {
  return status === 'CANCELLED' || status === 'REJECTED'
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '加载申请详情失败',
  })
}

async function load(): Promise<void> {
  const id = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
  if (!id) {
    error.value = new ApiError({
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
      message: '申请不存在或不可见',
    })
    return
  }
  loading.value = true
  error.value = null
  try {
    application.value = await getApplication(id)
  } catch (caught) {
    error.value = toApiError(caught)
    application.value = null
  } finally {
    loading.value = false
  }
}

function goBack(): void {
  void router.push({ name: 'm1-applications' })
}

function openPreop(): void {
  if (!application.value) return
  void router.push({
    name: 'm2-preop',
    params: { applicationId: application.value.applicationId },
  })
}

async function handleCancel(): Promise<void> {
  const current = application.value
  if (!current) return
  try {
    await ElMessageBox.confirm(
      `确认取消申请 ${current.hisApplicationNo}？取消后进入终态，不可恢复。`,
      '取消手术申请',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  try {
    await cancelApplication(current.applicationId, {
      reasonCode: 'PATIENT_CONDITION',
      reason: '详情页取消（演示）',
      version: current.version,
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

watch(
  () => route.params.id,
  () => {
    void load()
  },
)
</script>

<template>
  <section class="detail">
    <header class="detail__header">
      <div>
        <h1 class="detail__title">手术申请详情</h1>
        <p class="detail__subtitle">
          申请状态流转与脱敏患者摘要；证件号与手机号不展示，住院号仅展示脱敏值。
        </p>
      </div>
      <div class="detail__actions">
        <el-button data-testid="back-button" @click="goBack">返回列表</el-button>
        <el-button
          v-if="application && canReadPreop"
          type="primary"
          data-testid="preop-entry"
          @click="openPreop"
        >
          术前访视
        </el-button>
        <el-button
          v-if="application && canWrite && !isTerminal(application.status)"
          type="danger"
          data-testid="cancel-button"
          @click="handleCancel"
        >
          取消申请
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="detail__alert"
      type="error"
      :closable="false"
      show-icon
      title="加载或操作失败"
    >
      <p class="detail__error-message">{{ error.message }}</p>
      <p class="detail__error-meta">
        错误码：{{ error.code }}
        <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
      </p>
    </el-alert>

    <el-card v-loading="loading" class="detail__card" shadow="never">
      <template v-if="application">
        <div class="detail__summary">
          <div>
            <p class="detail__label">HIS 申请号</p>
            <p class="detail__value detail__value--mono">{{ application.hisApplicationNo }}</p>
          </div>
          <div class="detail__badges">
            <el-tag :type="statusType(application.status)" effect="plain">
              {{ statusLabel(application.status) }}
            </el-tag>
            <el-tag :type="urgencyType(application.urgency)" effect="plain">
              {{ urgencyLabel(application.urgency) }}
            </el-tag>
            <el-tag effect="plain" type="info">{{
              application.source === 'HIS' ? 'HIS 导入' : '手工创建'
            }}</el-tag>
          </div>
        </div>

        <el-descriptions class="detail__descriptions" :column="2" border>
          <el-descriptions-item label="患者姓名">{{
            application.patient.name
          }}</el-descriptions-item>
          <el-descriptions-item label="性别 / 年龄">
            {{
              application.patient.gender === 'FEMALE'
                ? '女'
                : application.patient.gender === 'MALE'
                  ? '男'
                  : '未知'
            }}
            / {{ application.patient.age }} 岁
          </el-descriptions-item>
          <el-descriptions-item label="住院号（脱敏）">
            {{ application.patient.inpatientNoMasked }}
          </el-descriptions-item>
          <el-descriptions-item label="科室 / 病区 / 床号">
            {{ application.patient.departmentName }} / {{ application.patient.ward || '—' }} /
            {{ application.patient.bedNo || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="拟施术式">{{
            application.surgeryName
          }}</el-descriptions-item>
          <el-descriptions-item label="术式编码">{{
            application.surgeryCode || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="预计时长">
            {{ application.plannedDurationMin }} 分钟
          </el-descriptions-item>
          <el-descriptions-item label="术者">{{
            application.surgeonName || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="术前诊断" :span="2">
            {{ application.preopDiagnosis || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">
            {{ formatTime(application.submittedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="最后更新">
            {{ formatTime(application.updatedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{
            application.remark || '—'
          }}</el-descriptions-item>
        </el-descriptions>

        <h2 class="detail__section-title">状态历史</h2>
        <el-timeline class="detail__timeline">
          <el-timeline-item
            v-for="(entry, index) in application.statusHistory"
            :key="`${entry.occurredAt}-${index}`"
            :timestamp="formatTime(entry.occurredAt)"
            placement="top"
          >
            <p class="detail__history-title">
              {{ fromLabel(entry.fromStatus) }} →
              {{ statusLabel(entry.toStatus) }}
            </p>
            <p class="detail__history-reason">{{ entry.reason || '—' }}</p>
          </el-timeline-item>
          <el-timeline-item
            v-if="application.statusHistory.length === 0"
            timestamp="—"
            placement="top"
          >
            <p class="detail__history-reason">暂无状态历史。</p>
          </el-timeline-item>
        </el-timeline>
      </template>
      <p v-else-if="!loading && !error" class="detail__empty">申请不存在或不可见。</p>
    </el-card>
  </section>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-4);
}

.detail__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--anesop-space-3);
}

.detail__title {
  margin: 0;
  font-size: var(--anesop-font-size-xl);
  font-weight: var(--anesop-font-weight-semibold);
}

.detail__subtitle {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.detail__actions {
  display: flex;
  gap: var(--anesop-space-2);
}

.detail__alert {
  margin-bottom: var(--anesop-space-2);
}

.detail__error-message {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.detail__error-meta {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}

.detail__card {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.detail__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--anesop-space-3);
  margin-bottom: var(--anesop-space-4);
}

.detail__badges {
  display: flex;
  gap: var(--anesop-space-2);
}

.detail__label {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.detail__value {
  margin: var(--anesop-space-1) 0 0;
  font-size: var(--anesop-font-size-lg);
  font-weight: var(--anesop-font-weight-semibold);
}

.detail__value--mono {
  font-family: var(--anesop-font-family-mono);
}

.detail__section-title {
  margin: var(--anesop-space-5) 0 var(--anesop-space-3);
  font-size: var(--anesop-font-size-lg);
  font-weight: var(--anesop-font-weight-semibold);
}

.detail__history-title {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.detail__history-reason {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
}

.detail__empty {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}
</style>
