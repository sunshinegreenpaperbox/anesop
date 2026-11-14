<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  dimensionLabels,
  exportFileName,
  exportReportCsv,
  getMultiReport,
  getWorkloadReport,
  metricLabels,
  validateRange,
  MAX_RANGE_DAYS,
  type MultiReport,
  type MultiRow,
  type ReportDimension,
  type ReportMetricCode,
  type ReportRange,
  type WorkloadBucket,
  type WorkloadReport,
} from '../api/report'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'

const auth = useAuthStore()

/** 本地自然日 YYYY-MM-DD（不引其他模块内部工具，避免跨模块引用）。 */
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

const dateRange = ref<[string, string]>([daysAgo(29), localDate()])
const dimension = ref<ReportDimension>('DEPT')
const multiDimensions = ref<ReportDimension[]>(['DEPT', 'SURGERY'])
const multiMetrics = ref<ReportMetricCode[]>(['caseCount', 'anesthesiaMinutes'])
const deptId = ref('')
const departmentOptions = ref<{ value: string; label: string }[]>([])
const workload = ref<WorkloadReport | null>(null)
const multi = ref<MultiReport | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const exporting = ref<'workload' | 'multi' | null>(null)
const page = ref(1)
const size = 20

const canRead = computed(() => auth.hasPermission('m4:report:read'))
const canExport = computed(() => auth.hasPermission('m4:report:export'))
const dimensions = computed(() =>
  (Object.keys(dimensionLabels) as ReportDimension[]).map((value) => ({
    value,
    label: dimensionLabels[value],
  })),
)
const metricOptions = computed(() =>
  (Object.keys(metricLabels) as ReportMetricCode[]).map((value) => ({
    value,
    label: metricLabels[value],
  })),
)
const departments = computed(() => departmentOptions.value)
const multiColumnLabel = computed(() =>
  multiDimensions.value.map((value) => dimensionLabels[value]).join(' + '),
)
const isEmpty = computed(
  () =>
    !loading.value &&
    !error.value &&
    (workload.value?.caseCount ?? 0) === 0 &&
    (multi.value?.total ?? 0) === 0,
)
const multiRows = computed(() => multi.value?.rows ?? [])
const buckets = computed(() => workload.value?.buckets ?? [])

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '报表加载失败',
  })
}

function currentRange(): ReportRange {
  return {
    from: dateRange.value[0],
    to: dateRange.value[1],
    deptId: deptId.value || undefined,
  }
}

function surgeryNames(bucket: WorkloadBucket): string {
  const items = bucket.surgeryDistribution ?? []
  return items.length > 0 ? items.map((item) => item.surgeryName).join('、') : '—'
}

function numberText(value: number | undefined): string {
  return (value ?? 0).toLocaleString('zh-CN')
}

function weightText(value: number | undefined): string {
  return (value ?? 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function rowLabel(row: MultiRow): string {
  const parts: string[] = []
  for (const current of multiDimensions.value) {
    if (current === 'DEPT') parts.push(row.deptName ?? '未匹配科室')
    else if (current === 'STAFF') {
      parts.push(row.staffNo ? `${row.staffNo} ${row.staffName ?? ''}`.trim() : '未匹配医师')
    } else if (current === 'SURGERY') parts.push(row.surgeryName ?? row.surgeryCode ?? '未登记术式')
    else if (current === 'ROOM') parts.push(row.roomNo ?? '未匹配术间')
  }
  return parts.join(' · ')
}

/** 科室选项从 DEPT 维度桶派生（不依赖 m1:schedule:read），跨查询累积避免筛选后丢失。 */
function rememberDepartments(items: WorkloadBucket[]): void {
  if (dimension.value !== 'DEPT' || deptId.value) return
  const seen = new Set(departmentOptions.value.map((item) => item.value))
  const merged = [...departmentOptions.value]
  for (const bucket of items) {
    if (bucket.key && bucket.key !== 'UNASSIGNED' && bucket.label && !seen.has(bucket.key)) {
      seen.add(bucket.key)
      merged.push({ value: bucket.key, label: bucket.label })
    }
  }
  departmentOptions.value = merged.sort((left, right) =>
    left.label.localeCompare(right.label, 'zh-CN'),
  )
}

async function loadMulti(): Promise<void> {
  multi.value = await getMultiReport(
    currentRange(),
    multiDimensions.value,
    multiMetrics.value,
    page.value,
    size,
  )
}

async function loadAll(): Promise<void> {
  const invalid = validateRange(dateRange.value?.[0] ?? '', dateRange.value?.[1] ?? '')
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  if (multiDimensions.value.length === 0 || multiMetrics.value.length === 0) {
    ElMessage.warning('请至少选择一个维度与一个指标')
    return
  }
  loading.value = true
  error.value = null
  page.value = 1
  try {
    const [workloadReport, multiReport] = await Promise.all([
      getWorkloadReport(currentRange(), dimension.value),
      getMultiReport(currentRange(), multiDimensions.value, multiMetrics.value, 1, size),
    ])
    workload.value = workloadReport
    multi.value = multiReport
    rememberDepartments(workloadReport.buckets ?? [])
  } catch (caught) {
    error.value = toApiError(caught)
    workload.value = null
    multi.value = null
  } finally {
    loading.value = false
  }
}

async function changePage(next: number): Promise<void> {
  page.value = next
  try {
    await loadMulti()
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  }
}

function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    anchor.rel = 'noopener'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

async function exportWorkload(): Promise<void> {
  const invalid = validateRange(dateRange.value?.[0] ?? '', dateRange.value?.[1] ?? '')
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  exporting.value = 'workload'
  try {
    const input = { report: 'workload' as const, range: currentRange(), dimension: dimension.value }
    downloadBlob(await exportReportCsv(input), exportFileName(input))
    ElMessage.success('工作量 CSV 已导出')
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    exporting.value = null
  }
}

async function exportMulti(): Promise<void> {
  const invalid = validateRange(dateRange.value?.[0] ?? '', dateRange.value?.[1] ?? '')
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  if (multiDimensions.value.length === 0 || multiMetrics.value.length === 0) {
    ElMessage.warning('请至少选择一个维度与一个指标')
    return
  }
  exporting.value = 'multi'
  try {
    const input = {
      report: 'multi' as const,
      range: currentRange(),
      dimensions: multiDimensions.value,
      metrics: multiMetrics.value,
    }
    downloadBlob(await exportReportCsv(input), exportFileName(input))
    ElMessage.success('多维报表 CSV 已导出')
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    exporting.value = null
  }
}

onMounted(async () => {
  if (!canRead.value) return
  await loadAll()
})
</script>

<template>
  <section class="report" data-testid="report-page">
    <header class="report__header">
      <div>
        <h1 class="report__title">工作量与多维报表</h1>
        <p class="report__hint">
          口径 report-1.0.0 · 时区 Asia/Shanghai · 只读聚合（台次/麻醉时长/加权台次/术式分布）。
          跨度上限 {{ MAX_RANGE_DAYS }} 天；数据范围按科室/术间/本人裁剪；绩效权重与难度系数由院方
          另定【待确认】，系统只出数。
        </p>
      </div>
    </header>

    <el-alert
      v-if="!canRead"
      data-testid="forbidden-alert"
      type="warning"
      :closable="false"
      title="无报表权限"
      description="需要权限点 m4:report:read；请联系管理员开通。"
    />

    <template v-else>
      <el-card class="report__filters" shadow="never">
        <div class="report__filter-row">
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
          <el-select v-model="dimension" data-testid="dimension-select" class="report__dimension">
            <el-option
              v-for="item in dimensions"
              :key="item.value"
              :label="`工作量维度：${item.label}`"
              :value="item.value"
            />
          </el-select>
          <el-select
            v-model="multiDimensions"
            data-testid="multi-dimensions"
            multiple
            collapse-tags
            class="report__multi"
            placeholder="多维维度"
          >
            <el-option
              v-for="item in dimensions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-select
            v-model="multiMetrics"
            data-testid="multi-metrics"
            multiple
            collapse-tags
            class="report__multi"
            placeholder="指标"
          >
            <el-option
              v-for="item in metricOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-select
            v-model="deptId"
            data-testid="dept-filter"
            placeholder="全部科室"
            clearable
            class="report__dept"
          >
            <el-option label="全部科室" value="" />
            <el-option
              v-for="department in departments"
              :key="department.value"
              :label="department.label"
              :value="department.value"
            />
          </el-select>
          <el-button data-testid="query-button" type="primary" :loading="loading" @click="loadAll">
            查询
          </el-button>
          <template v-if="canExport">
            <el-button
              data-testid="export-workload"
              :loading="exporting === 'workload'"
              @click="exportWorkload"
            >
              导出工作量 CSV
            </el-button>
            <el-button
              data-testid="export-multi"
              :loading="exporting === 'multi'"
              @click="exportMulti"
            >
              导出多维 CSV
            </el-button>
          </template>
        </div>
      </el-card>

      <el-alert
        v-if="error"
        data-testid="error-alert"
        class="report__alert"
        type="error"
        :closable="false"
        :title="error.message"
        :description="`错误码 ${error.code}${error.traceId ? ` · traceId ${error.traceId}` : ''}`"
      />

      <el-empty
        v-if="isEmpty"
        data-testid="empty-state"
        description="所选日期范围与筛选条件暂无台次数据"
      />

      <div v-else-if="!error" v-loading="loading" class="report__content">
        <el-card shadow="never">
          <template #header>
            <div class="report__card-header">
              <span>工作量汇总（{{ dimensionLabels[dimension] }}维度）</span>
              <el-tag size="small" type="info">{{ buckets.length }} 个分组</el-tag>
            </div>
          </template>
          <div class="report__metrics">
            <div>
              <div class="report__metric">{{ numberText(workload?.caseCount) }}</div>
              <div class="report__metric-label">台次</div>
            </div>
            <div>
              <div class="report__metric">{{ numberText(workload?.anesthesiaMinutes) }}</div>
              <div class="report__metric-label">麻醉时长（分钟）</div>
            </div>
            <div>
              <div class="report__metric">{{ weightText(workload?.weightedCaseCount) }}</div>
              <div class="report__metric-label">加权台次</div>
            </div>
            <div>
              <div class="report__metric">{{ numberText(workload?.unrecordedCaseCount) }}</div>
              <div class="report__metric-label">无时长台次</div>
            </div>
          </div>
          <p class="report__formula">{{ workload?.formula }}</p>
          <el-table
            data-testid="workload-table"
            :data="buckets"
            size="small"
            empty-text="暂无维度数据"
          >
            <el-table-column prop="label" :label="dimensionLabels[dimension]" min-width="180" />
            <el-table-column prop="caseCount" label="台次" width="90" />
            <el-table-column prop="anesthesiaMinutes" label="麻醉时长" width="110" />
            <el-table-column label="加权台次" width="110">
              <template #default="{ row }">{{ weightText(row.weightedCaseCount) }}</template>
            </el-table-column>
            <el-table-column prop="unrecordedCaseCount" label="无时长台次" width="110" />
            <el-table-column label="术式分布" min-width="220">
              <template #default="{ row }">
                {{ surgeryNames(row) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never">
          <template #header>
            <div class="report__card-header">
              <span>多维报表（{{ multiColumnLabel }}）</span>
              <el-tag size="small" type="info">{{ numberText(multi?.total) }} 个分组</el-tag>
            </div>
          </template>
          <el-table
            data-testid="multi-table"
            :data="multiRows"
            size="small"
            empty-text="暂无分组数据"
          >
            <el-table-column label="维度组合" min-width="220">
              <template #default="{ row }">{{ rowLabel(row) }}</template>
            </el-table-column>
            <el-table-column
              v-if="multiMetrics.includes('caseCount')"
              prop="caseCount"
              label="台次"
              width="90"
            />
            <el-table-column
              v-if="multiMetrics.includes('anesthesiaMinutes')"
              prop="anesthesiaMinutes"
              label="麻醉时长"
              width="110"
            />
            <el-table-column
              v-if="multiMetrics.includes('weightedCaseCount')"
              label="加权台次"
              width="110"
            >
              <template #default="{ row }">{{ weightText(row.weightedCaseCount) }}</template>
            </el-table-column>
            <el-table-column prop="unrecordedCaseCount" label="无时长台次" width="110" />
          </el-table>
          <p data-testid="multi-total" class="report__total">
            合计：{{ numberText(multi?.totals?.caseCount) }} 台次 ·
            {{ numberText(multi?.totals?.anesthesiaMinutes) }} 分钟 · 加权
            {{ weightText(multi?.totals?.weightedCaseCount) }} · 分组
            {{ numberText(multi?.totals?.groupCount) }}
          </p>
          <el-pagination
            v-if="(multi?.totalPages ?? 0) > 1"
            data-testid="multi-pagination"
            class="report__pagination"
            layout="prev, pager, next"
            :current-page="page"
            :page-size="size"
            :total="multi?.total ?? 0"
            @current-change="changePage"
          />
        </el-card>
      </div>
    </template>
  </section>
</template>

<style scoped>
.report {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.report__header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.report__title {
  margin: 0 0 6px;
  font-size: 22px;
}

.report__hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  max-width: 980px;
}

.report__filters {
  border: 1px solid var(--el-border-color-lighter);
}

.report__filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.report__dimension {
  width: 200px;
}

.report__multi {
  width: 220px;
}

.report__dept {
  width: 190px;
}

.report__alert {
  margin-top: 4px;
}

.report__content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.report__card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.report__metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 8px;
}

.report__metric {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.3;
}

.report__metric-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.report__formula {
  margin: 0 0 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.report__total {
  margin: 10px 0 0;
  color: var(--el-text-color-regular);
}

.report__pagination {
  margin-top: 10px;
  justify-content: flex-end;
}
</style>
