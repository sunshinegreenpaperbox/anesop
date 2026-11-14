<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  adverseStatusLabels,
  closeAdverseRca,
  createAdverseEvent,
  createAdverseRca,
  eventTypeLabels,
  listAdverseEvents,
  severityLabels,
  type AdverseEvent,
  type AdverseEventStatus,
  type QcRange,
} from '../api/qc'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'

const auth = useAuthStore()

function localDate(value: Date = new Date()): string {
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${day}`
}

function daysAgo(days: number): string {
  const value = new Date()
  value.setDate(value.getDate() - days)
  return localDate(value)
}

/** 默认整改期限：今天 +14 天（本地日期），避免用户漏填且便于端到端操作。 */
function daysAhead(days: number): string {
  const value = new Date()
  value.setDate(value.getDate() + days)
  return localDate(value)
}

const dateRange = ref<[string, string]>([daysAgo(89), localDate()])
const statusFilter = ref<AdverseEventStatus | ''>('')
const deptFilter = ref('')
const items = ref<AdverseEvent[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const submitting = ref(false)
const error = ref<ApiError | null>(null)
const departmentOptions = ref<{ value: string; label: string }[]>([])

const canRead = computed(() => auth.hasPermission('m4:qc:read'))
const canWrite = computed(() => auth.hasPermission('m4:qc:write'))
const canReview = computed(() => auth.hasPermission('m4:qc:review'))
const isEmpty = computed(() => !loading.value && !error.value && items.value.length === 0)

const statusOptions = computed(() =>
  (Object.keys(adverseStatusLabels) as AdverseEventStatus[]).map((value) => ({
    value,
    label: adverseStatusLabels[value],
  })),
)
const typeOptions = computed(() =>
  Object.entries(eventTypeLabels).map(([value, label]) => ({ value, label })),
)
const severityOptions = computed(() =>
  Object.entries(severityLabels).map(([value, label]) => ({ value, label })),
)

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '不良事件操作失败',
  })
}

function currentRange(): QcRange {
  return {
    from: dateRange.value[0],
    to: dateRange.value[1],
    deptId: deptFilter.value || undefined,
  }
}

function rememberDepartments(rows: AdverseEvent[]): void {
  const seen = new Set(departmentOptions.value.map((item) => item.value))
  const merged = [...departmentOptions.value]
  for (const row of rows) {
    if (row.deptId && row.deptName && !seen.has(row.deptId)) {
      seen.add(row.deptId)
      merged.push({ value: row.deptId, label: row.deptName })
    }
  }
  departmentOptions.value = merged.sort((left, right) =>
    left.label.localeCompare(right.label, 'zh-CN'),
  )
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await listAdverseEvents({
      ...currentRange(),
      status: statusFilter.value || undefined,
      page: page.value,
      size,
    })
    items.value = result.items ?? []
    total.value = result.meta?.total ?? 0
    rememberDepartments(items.value)
  } catch (caught) {
    error.value = toApiError(caught)
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------
// 登记
// ------------------------------------------------------------------
const registerVisible = ref(false)
const registerForm = reactive({
  eventType: 'MEDICATION',
  severity: 'LEVEL_II',
  occurredAt: '',
  deptId: '',
  description: '',
  anonymous: false,
})

function openRegister(): void {
  registerForm.eventType = 'MEDICATION'
  registerForm.severity = 'LEVEL_II'
  registerForm.occurredAt = `${localDate()}T10:00:00+08:00`
  registerForm.deptId = deptFilter.value || items.value[0]?.deptId || ''
  registerForm.description = ''
  registerForm.anonymous = false
  registerVisible.value = true
}

async function submitRegister(): Promise<void> {
  if (!registerForm.deptId) {
    ElMessage.warning('请选择或填写科室标识')
    return
  }
  if (!registerForm.description.trim()) {
    ElMessage.warning('请填写已脱敏的事件描述')
    return
  }
  submitting.value = true
  try {
    await createAdverseEvent({
      eventType: registerForm.eventType,
      severity: registerForm.severity,
      occurredAt: registerForm.occurredAt,
      deptId: registerForm.deptId,
      description: registerForm.description.trim(),
      anonymous: registerForm.anonymous,
    })
    registerVisible.value = false
    ElMessage.success('不良事件已登记（REPORTED）')
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    submitting.value = false
  }
}

// ------------------------------------------------------------------
// RCA 提交 / 复核 / 闭环
// ------------------------------------------------------------------
const rcaVisible = ref(false)
const rcaTarget = ref<AdverseEvent | null>(null)
const rcaForm = reactive({
  rootCause: '',
  correctiveAction: '',
  responsibleName: '',
  dueDate: '',
  timeline: [] as { occurredAt: string; description: string }[],
})

function openRca(row: AdverseEvent): void {
  rcaTarget.value = row
  rcaForm.rootCause = ''
  rcaForm.correctiveAction = ''
  rcaForm.responsibleName = ''
  rcaForm.dueDate = daysAhead(14)
  rcaForm.timeline = [
    {
      occurredAt: row.occurredAt ?? `${localDate()}T10:00:00+08:00`,
      description: row.description,
    },
  ]
  rcaVisible.value = true
}

function addTimeline(): void {
  rcaForm.timeline.push({
    occurredAt: rcaTarget.value?.occurredAt ?? `${localDate()}T10:00:00+08:00`,
    description: '',
  })
}

function removeTimeline(index: number): void {
  if (rcaForm.timeline.length <= 1) return
  rcaForm.timeline.splice(index, 1)
}

async function submitRca(): Promise<void> {
  const target = rcaTarget.value
  if (!target) return
  const timeline = rcaForm.timeline
    .filter((item) => item.description.trim())
    .map((item) => ({ occurredAt: item.occurredAt, description: item.description.trim() }))
  if (timeline.length === 0 || !rcaForm.rootCause.trim() || !rcaForm.correctiveAction.trim()) {
    ElMessage.warning('请填写时间线、根因与整改措施')
    return
  }
  if (!rcaForm.responsibleName.trim() || !rcaForm.dueDate) {
    ElMessage.warning('请填写责任人与整改期限')
    return
  }
  submitting.value = true
  try {
    await createAdverseRca(target.id, {
      timeline,
      rootCause: rcaForm.rootCause.trim(),
      correctiveAction: rcaForm.correctiveAction.trim(),
      responsibleName: rcaForm.responsibleName.trim(),
      dueDate: rcaForm.dueDate,
    })
    rcaVisible.value = false
    ElMessage.success('RCA 已提交（整改中前需复核）')
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    submitting.value = false
  }
}

const reviewVisible = ref(false)
const reviewTarget = ref<AdverseEvent | null>(null)
const reviewForm = reactive({ reviewerName: '', conclusion: '' })

function openReview(row: AdverseEvent): void {
  reviewTarget.value = row
  reviewForm.reviewerName = ''
  reviewForm.conclusion = ''
  reviewVisible.value = true
}

async function submitReview(): Promise<void> {
  const target = reviewTarget.value
  if (!target || !target.rcaId) return
  if (!reviewForm.reviewerName.trim() || !reviewForm.conclusion.trim()) {
    ElMessage.warning('请填写复核人与复核结论')
    return
  }
  submitting.value = true
  try {
    await closeAdverseRca(target.rcaId, {
      reviewerName: reviewForm.reviewerName.trim(),
      conclusion: reviewForm.conclusion.trim(),
      rectificationCompleted: false,
    })
    reviewVisible.value = false
    ElMessage.success('RCA 已复核，事件转整改中（RECTIFYING）')
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    submitting.value = false
  }
}

const closeVisible = ref(false)
const closeTarget = ref<AdverseEvent | null>(null)
const closeForm = reactive({ reviewerName: '', conclusion: '', completed: false })

function openClose(row: AdverseEvent): void {
  closeTarget.value = row
  closeForm.reviewerName = ''
  closeForm.conclusion = ''
  closeForm.completed = false
  closeVisible.value = true
}

async function submitClose(): Promise<void> {
  const target = closeTarget.value
  if (!target || !target.rcaId) return
  if (!closeForm.completed) {
    ElMessage.warning('请确认整改已完成')
    return
  }
  if (!closeForm.reviewerName.trim() || !closeForm.conclusion.trim()) {
    ElMessage.warning('请填写复核人与闭环结论')
    return
  }
  submitting.value = true
  try {
    await closeAdverseRca(target.rcaId, {
      reviewerName: closeForm.reviewerName.trim(),
      conclusion: closeForm.conclusion.trim(),
      rectificationCompleted: true,
    })
    closeVisible.value = false
    ElMessage.success('事件已闭环（CLOSED）')
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    submitting.value = false
  }
}

async function changePage(next: number): Promise<void> {
  page.value = next
  await load()
}

onMounted(async () => {
  if (!canRead.value) return
  await load()
})
</script>

<template>
  <section class="qc" data-testid="qc-adverse-page">
    <header>
      <h1 class="qc__title">不良事件登记与 RCA 闭环</h1>
      <p class="qc__hint">
        状态机 REPORTED→RCA→RECTIFYING→CLOSED（只允许逐级前进，越级/重复闭环 409）；主体与 RCA
        append-only；复核人不得为上报人/责任人。描述须已脱敏（不含姓名/住院号/身份证号/手机号）。
      </p>
    </header>

    <el-alert
      v-if="!canRead"
      data-testid="forbidden-alert"
      type="warning"
      :closable="false"
      title="无不良事件权限"
      description="需要权限点 m4:qc:read；请联系管理员开通。"
    />

    <template v-else>
      <el-card shadow="never">
        <div class="qc__filters">
          <el-date-picker
            v-model="dateRange"
            data-testid="date-range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="起始日期"
            end-placeholder="结束日期"
            :clearable="false"
          />
          <el-select
            v-model="statusFilter"
            data-testid="status-filter"
            placeholder="全部状态"
            clearable
            class="qc__select"
          >
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-select
            v-model="deptFilter"
            data-testid="dept-filter"
            placeholder="全部科室"
            clearable
            filterable
            allow-create
            default-first-option
            class="qc__select"
          >
            <el-option label="全部科室" value="" />
            <el-option
              v-for="item in departmentOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-button data-testid="query-button" type="primary" :loading="loading" @click="load">
            查询
          </el-button>
          <el-button
            v-if="canWrite"
            data-testid="register-button"
            type="primary"
            plain
            @click="openRegister"
          >
            登记不良事件
          </el-button>
        </div>
      </el-card>

      <el-alert
        v-if="error"
        data-testid="error-alert"
        type="error"
        :closable="false"
        :title="error.message"
        :description="`错误码 ${error.code}${error.traceId ? ` · traceId ${error.traceId}` : ''}`"
      />

      <el-empty v-if="isEmpty" data-testid="empty-state" description="所选条件下暂无不良事件" />

      <el-card v-else-if="!error" shadow="never">
        <el-table data-testid="adverse-table" :data="items" size="small" empty-text="暂无不良事件">
          <el-table-column prop="eventNo" label="事件号" width="200" />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ eventTypeLabels[row.eventType] }}</template>
          </el-table-column>
          <el-table-column label="等级" width="80">
            <template #default="{ row }">{{ severityLabels[row.severity] }}</template>
          </el-table-column>
          <el-table-column label="发生时间" width="150">
            <template #default="{ row }">{{ (row.occurredAt ?? '').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column prop="deptName" label="科室" min-width="130" />
          <el-table-column prop="reporterName" label="上报人" width="130" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag
                :data-testid="`status-${row.eventNo}`"
                size="small"
                :type="row.status === 'CLOSED' ? 'success' : 'warning'"
              >
                {{ adverseStatusLabels[row.status as AdverseEventStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="脱敏描述" min-width="220" />
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button
                v-if="canWrite && row.status === 'REPORTED'"
                :data-testid="`rca-${row.eventNo}`"
                link
                type="primary"
                @click="openRca(row)"
              >
                提交 RCA
              </el-button>
              <el-button
                v-if="canReview && row.status === 'RCA' && row.rcaId"
                :data-testid="`review-${row.eventNo}`"
                link
                type="primary"
                @click="openReview(row)"
              >
                复核
              </el-button>
              <el-button
                v-if="canReview && row.status === 'RECTIFYING' && row.rcaId"
                :data-testid="`close-${row.eventNo}`"
                link
                type="success"
                @click="openClose(row)"
              >
                整改闭环
              </el-button>
              <span v-if="row.status === 'CLOSED'">已闭环</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="total > size"
          data-testid="pagination"
          class="qc__pagination"
          layout="prev, pager, next"
          :current-page="page"
          :page-size="size"
          :total="total"
          @current-change="changePage"
        />
      </el-card>
    </template>

    <el-dialog v-model="registerVisible" title="登记不良事件" width="620px">
      <el-form label-width="110px" data-testid="register-dialog">
        <el-form-item label="事件类型">
          <el-select v-model="registerForm.eventType" data-testid="event-type" class="qc__full">
            <el-option
              v-for="item in typeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="事件等级">
          <el-select v-model="registerForm.severity" data-testid="severity" class="qc__full">
            <el-option
              v-for="item in severityOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发生时间">
          <el-date-picker
            v-model="registerForm.occurredAt"
            data-testid="occurred-at"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            class="qc__full"
          />
        </el-form-item>
        <el-form-item label="科室">
          <el-select
            v-model="registerForm.deptId"
            data-testid="dept-select"
            filterable
            allow-create
            default-first-option
            placeholder="科室标识（UUID）"
            class="qc__full"
          >
            <el-option
              v-for="item in departmentOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="脱敏描述">
          <el-input
            v-model="registerForm.description"
            data-testid="description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="已脱敏描述（不得含姓名/住院号/身份证号/手机号）"
          />
        </el-form-item>
        <el-form-item label="匿名上报">
          <el-switch v-model="registerForm.anonymous" data-testid="anonymous" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button
          data-testid="register-submit"
          type="primary"
          :loading="submitting"
          @click="submitRegister"
        >
          提交
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rcaVisible" title="提交 RCA" width="680px">
      <el-form label-width="110px" data-testid="rca-dialog">
        <el-form-item label="事件时间线">
          <div class="qc__timeline">
            <div v-for="(item, index) in rcaForm.timeline" :key="index" class="qc__timeline-row">
              <el-input
                :data-testid="`timeline-desc-${index}`"
                v-model="item.description"
                placeholder="该节点脱敏描述"
              />
              <el-button link type="danger" @click="removeTimeline(index)">删除</el-button>
            </div>
            <el-button data-testid="add-timeline" link type="primary" @click="addTimeline">
              添加节点
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="根因">
          <el-input
            v-model="rcaForm.rootCause"
            data-testid="root-cause"
            type="textarea"
            :rows="2"
            maxlength="500"
          />
        </el-form-item>
        <el-form-item label="整改措施">
          <el-input
            v-model="rcaForm.correctiveAction"
            data-testid="corrective-action"
            type="textarea"
            :rows="2"
            maxlength="500"
          />
        </el-form-item>
        <el-form-item label="责任人">
          <el-input v-model="rcaForm.responsibleName" data-testid="responsible" maxlength="64" />
        </el-form-item>
        <el-form-item label="整改期限">
          <el-date-picker
            v-model="rcaForm.dueDate"
            data-testid="due-date"
            type="date"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rcaVisible = false">取消</el-button>
        <el-button data-testid="rca-submit" type="primary" :loading="submitting" @click="submitRca">
          提交
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="RCA 复核（转整改中）" width="560px">
      <el-form label-width="110px" data-testid="review-dialog">
        <el-form-item label="复核人">
          <el-input v-model="reviewForm.reviewerName" data-testid="reviewer" maxlength="64" />
        </el-form-item>
        <el-form-item label="复核结论">
          <el-input
            v-model="reviewForm.conclusion"
            data-testid="conclusion"
            type="textarea"
            :rows="2"
            maxlength="300"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button
          data-testid="review-submit"
          type="primary"
          :loading="submitting"
          @click="submitReview"
        >
          复核通过
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closeVisible" title="整改闭环" width="560px">
      <el-form label-width="110px" data-testid="close-dialog">
        <el-form-item label="复核人">
          <el-input v-model="closeForm.reviewerName" data-testid="close-reviewer" maxlength="64" />
        </el-form-item>
        <el-form-item label="闭环结论">
          <el-input
            v-model="closeForm.conclusion"
            data-testid="close-conclusion"
            type="textarea"
            :rows="2"
            maxlength="300"
          />
        </el-form-item>
        <el-form-item label="整改完成">
          <el-checkbox v-model="closeForm.completed" data-testid="rectification-completed">
            确认整改已完成
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeVisible = false">取消</el-button>
        <el-button
          data-testid="close-submit"
          type="success"
          :loading="submitting"
          @click="submitClose"
        >
          确认闭环
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.qc {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.qc__title {
  margin: 0 0 6px;
  font-size: 22px;
}

.qc__hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  max-width: 980px;
}

.qc__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.qc__select {
  width: 200px;
}

.qc__full {
  width: 100%;
}

.qc__timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.qc__timeline-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.qc__pagination {
  margin-top: 10px;
  justify-content: flex-end;
}
</style>
