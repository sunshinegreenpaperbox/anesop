<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  closeRectification,
  createChartReview,
  createRectification,
  gradeOf,
  itemLabels,
  listChartReviews,
  reviewStatusLabels,
  scorePercent,
  type ChartReview,
  type ChartReviewItemCode,
  type ChartReviewStatus,
  type QcRange,
  type Rectification,
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
const statusFilter = ref<ChartReviewStatus | ''>('')
const deptFilter = ref('')
const items = ref<ChartReview[]>([])
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
  (Object.keys(reviewStatusLabels) as ChartReviewStatus[]).map((value) => ({
    value,
    label: reviewStatusLabels[value],
  })),
)

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '病历抽查操作失败',
  })
}

function currentRange(): QcRange {
  return {
    from: dateRange.value[0],
    to: dateRange.value[1],
    deptId: deptFilter.value || undefined,
  }
}

function rememberDepartments(rows: ChartReview[]): void {
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
    const result = await listChartReviews({
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
// 创建抽查
// ------------------------------------------------------------------
interface ScoreRow {
  itemCode: ChartReviewItemCode
  itemName: string
  score: number
  maxScore: number
  defectNote: string
}

function defaultScores(): ScoreRow[] {
  return (Object.keys(itemLabels) as ChartReviewItemCode[]).map((code) => ({
    itemCode: code,
    itemName: itemLabels[code],
    score: 25,
    maxScore: 25,
    defectNote: '',
  }))
}

const createVisible = ref(false)
const createForm = reactive({
  deptId: '',
  reviewedAt: '',
  scores: defaultScores(),
})

const previewTotal = computed(() =>
  createForm.scores.reduce((sum, item) => sum + (Number(item.score) || 0), 0),
)
const previewMax = computed(() =>
  createForm.scores.reduce((sum, item) => sum + (Number(item.maxScore) || 0), 0),
)
const previewPercent = computed(() => scorePercent(previewTotal.value, previewMax.value))
const previewGrade = computed(() => gradeOf(previewTotal.value, previewMax.value))

function openCreate(): void {
  createForm.deptId = deptFilter.value || items.value[0]?.deptId || ''
  createForm.reviewedAt = `${localDate()}T10:00:00+08:00`
  createForm.scores = defaultScores()
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!createForm.deptId) {
    ElMessage.warning('请选择或填写科室标识')
    return
  }
  for (const item of createForm.scores) {
    const score = Number(item.score)
    const maxScore = Number(item.maxScore)
    if (!Number.isFinite(score) || !Number.isFinite(maxScore) || score < 0 || score > maxScore) {
      ElMessage.warning(`评分项「${item.itemName}」得分必须在 0–满分之间`)
      return
    }
    if (score < maxScore && !item.defectNote.trim()) {
      ElMessage.warning(`评分项「${item.itemName}」存在缺陷，必须填写缺陷说明`)
      return
    }
  }
  submitting.value = true
  try {
    await createChartReview({
      deptId: createForm.deptId,
      reviewedAt: createForm.reviewedAt,
      items: createForm.scores.map((item) => ({
        itemCode: item.itemCode,
        itemName: item.itemName,
        score: Number(item.score),
        maxScore: Number(item.maxScore),
        defectNote: item.defectNote.trim() || undefined,
      })),
    })
    createVisible.value = false
    ElMessage.success('病历抽查已创建（OPEN）')
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    submitting.value = false
  }
}

// ------------------------------------------------------------------
// 整改与闭环
// ------------------------------------------------------------------
const rectifyVisible = ref(false)
const rectifyTarget = ref<ChartReview | null>(null)
const rectifyForm = reactive({ correctiveAction: '', responsibleName: '', dueDate: '' })

function openRectify(row: ChartReview): void {
  rectifyTarget.value = row
  rectifyForm.correctiveAction = ''
  rectifyForm.responsibleName = ''
  rectifyForm.dueDate = daysAhead(14)
  rectifyVisible.value = true
}

async function submitRectify(): Promise<void> {
  const target = rectifyTarget.value
  if (!target) return
  if (!rectifyForm.correctiveAction.trim() || !rectifyForm.responsibleName.trim()) {
    ElMessage.warning('请填写整改措施与责任人')
    return
  }
  if (!rectifyForm.dueDate) {
    ElMessage.warning('请选择整改期限')
    return
  }
  submitting.value = true
  try {
    const created: Rectification = await createRectification(target.id, {
      correctiveAction: rectifyForm.correctiveAction.trim(),
      responsibleName: rectifyForm.responsibleName.trim(),
      dueDate: rectifyForm.dueDate,
    })
    // 保留整改标识，便于在闭环时直接使用（列表接口也会返回 rectificationId）。
    const current = items.value.find((item) => item.id === target.id)
    if (current) current.rectificationId = created.id
    rectifyVisible.value = false
    ElMessage.success('整改已提交（RECTIFYING）')
    await load()
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    submitting.value = false
  }
}

const closeVisible = ref(false)
const closeTarget = ref<ChartReview | null>(null)
const closeForm = reactive({ reviewerName: '', conclusion: '' })

function openClose(row: ChartReview): void {
  closeTarget.value = row
  closeForm.reviewerName = ''
  closeForm.conclusion = ''
  closeVisible.value = true
}

async function submitClose(): Promise<void> {
  const target = closeTarget.value
  if (!target || !target.rectificationId) {
    ElMessage.warning('未找到整改记录，无法闭环')
    return
  }
  if (!closeForm.reviewerName.trim() || !closeForm.conclusion.trim()) {
    ElMessage.warning('请填写复核人与闭环结论')
    return
  }
  submitting.value = true
  try {
    await closeRectification(target.rectificationId, {
      reviewerName: closeForm.reviewerName.trim(),
      conclusion: closeForm.conclusion.trim(),
    })
    closeVisible.value = false
    ElMessage.success('抽查已闭环（CLOSED）')
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
  <section class="qc" data-testid="qc-review-page">
    <header>
      <h1 class="qc__title">病历质量抽查与整改</h1>
      <p class="qc__hint">
        评分项：完整性/时效性/签名/逻辑一致性；总分与等级由服务端计算（合格线 90%【待确认】）。
        存在缺陷项必须整改，闭环须独立复核人；整改内容 append-only。
      </p>
    </header>

    <el-alert
      v-if="!canRead"
      data-testid="forbidden-alert"
      type="warning"
      :closable="false"
      title="无病历抽查权限"
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
            data-testid="create-button"
            type="primary"
            plain
            @click="openCreate"
          >
            新建抽查
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

      <el-empty v-if="isEmpty" data-testid="empty-state" description="所选条件下暂无抽查记录" />

      <el-card v-else-if="!error" shadow="never">
        <el-table data-testid="review-table" :data="items" size="small" empty-text="暂无抽查记录">
          <el-table-column prop="reviewNo" label="抽查号" width="200" />
          <el-table-column label="抽查时间" width="150">
            <template #default="{ row }">{{ (row.reviewedAt ?? '').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column prop="deptName" label="科室" min-width="130" />
          <el-table-column label="总分" width="110">
            <template #default="{ row }">{{ row.totalScore }} / {{ row.maxScore }}</template>
          </el-table-column>
          <el-table-column label="得分率" width="90">
            <template #default="{ row }">
              {{ scorePercent(row.totalScore, row.maxScore).toFixed(2) }}%
            </template>
          </el-table-column>
          <el-table-column prop="grade" label="等级" width="70" />
          <el-table-column prop="defectCount" label="缺陷" width="70" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag
                :data-testid="`review-status-${row.reviewNo}`"
                size="small"
                :type="row.status === 'CLOSED' ? 'success' : 'warning'"
              >
                {{ reviewStatusLabels[row.status as ChartReviewStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button
                v-if="canWrite && row.status === 'OPEN'"
                :data-testid="`rectify-${row.reviewNo}`"
                link
                type="primary"
                @click="openRectify(row)"
              >
                提交整改
              </el-button>
              <el-button
                v-if="canReview && row.status === 'RECTIFYING' && row.rectificationId"
                :data-testid="`close-${row.reviewNo}`"
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

    <el-dialog v-model="createVisible" title="新建病历质量抽查" width="720px">
      <el-form label-width="110px" data-testid="create-dialog">
        <el-form-item label="科室">
          <el-select
            v-model="createForm.deptId"
            data-testid="review-dept"
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
        <el-form-item label="抽查时间">
          <el-date-picker
            v-model="createForm.reviewedAt"
            data-testid="reviewed-at"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
          />
        </el-form-item>
        <el-form-item label="评分项">
          <div class="qc__scores">
            <div v-for="(item, index) in createForm.scores" :key="item.itemCode" class="qc__score">
              <span class="qc__score-name">{{ item.itemName }}</span>
              <el-input-number
                v-model="item.score"
                :data-testid="`score-${index}`"
                :min="0"
                :max="item.maxScore"
                size="small"
              />
              <span class="qc__score-sep">/</span>
              <el-input-number
                v-model="item.maxScore"
                :data-testid="`max-${index}`"
                :min="1"
                :max="100"
                size="small"
              />
              <el-input
                v-model="item.defectNote"
                :data-testid="`defect-${index}`"
                size="small"
                placeholder="缺陷说明（得分<满分时必填）"
                maxlength="200"
              />
            </div>
          </div>
        </el-form-item>
        <el-form-item label="预览">
          <span data-testid="score-preview">
            总分 {{ previewTotal }} / {{ previewMax }} · {{ previewPercent.toFixed(2) }}% · 等级
            {{ previewGrade }}
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button
          data-testid="create-submit"
          type="primary"
          :loading="submitting"
          @click="submitCreate"
        >
          提交
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rectifyVisible" title="提交整改" width="560px">
      <el-form label-width="110px" data-testid="rectify-dialog">
        <el-form-item label="整改措施">
          <el-input
            v-model="rectifyForm.correctiveAction"
            data-testid="rectify-action"
            type="textarea"
            :rows="3"
            maxlength="500"
          />
        </el-form-item>
        <el-form-item label="责任人">
          <el-input
            v-model="rectifyForm.responsibleName"
            data-testid="rectify-responsible"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="整改期限">
          <el-date-picker
            v-model="rectifyForm.dueDate"
            data-testid="rectify-due-date"
            type="date"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rectifyVisible = false">取消</el-button>
        <el-button
          data-testid="rectify-submit"
          type="primary"
          :loading="submitting"
          @click="submitRectify"
        >
          提交
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closeVisible" title="整改闭环" width="520px">
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

.qc__scores {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.qc__score {
  display: grid;
  grid-template-columns: 90px 120px 12px 120px 1fr;
  gap: 8px;
  align-items: center;
}

.qc__score-name {
  font-size: 13px;
}

.qc__score-sep {
  text-align: center;
}

.qc__pagination {
  margin-top: 10px;
  justify-content: flex-end;
}
</style>
