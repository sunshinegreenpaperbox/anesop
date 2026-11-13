<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  exportSchedulingAnalytics,
  getSchedulingCancellations,
  getSchedulingOnTime,
  getSchedulingUtilization,
  type AnalyticsMetric,
  type AnalyticsRange,
  type CancellationReport,
  type OnTimeReport,
  type UtilizationReport,
  type UtilizationRoom,
} from '../api/m1'
import { ApiError } from '../../../api/http'
import { formatLocalDate } from '../utils/date'
import { useAuthStore } from '../../../stores/auth'

const auth = useAuthStore()

function daysAgo(days: number): string {
  const value = new Date()
  value.setDate(value.getDate() - days)
  return formatLocalDate(value)
}

const dateRange = ref<[string, string]>([daysAgo(6), formatLocalDate()])
const deptId = ref('')
const departmentOptions = ref<{ value: string; label: string }[]>([])
const utilization = ref<UtilizationReport | null>(null)
const onTime = ref<OnTimeReport | null>(null)
const cancellations = ref<CancellationReport | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const exporting = ref<AnalyticsMetric | null>(null)

const canRead = computed(() => auth.hasPermission('m1:analytics:read'))
const departments = computed(() => departmentOptions.value)
// Q-1：无实际开台数据（evaluatedCaseCount=0）时展示“暂无数据”，不把缺数当 0.0%。
const onTimeAvailable = computed(() => (onTime.value?.summary.evaluatedCaseCount ?? 0) > 0)
const onTimeMetricText = computed(() =>
  onTimeAvailable.value ? percentText(onTime.value?.summary.onTimeRate) : '暂无数据',
)
const onTimeExcludedDays = computed(() => onTime.value?.summary.excludedDays ?? 0)
const rangeValid = computed(
  () =>
    Array.isArray(dateRange.value) && dateRange.value.length === 2 && Boolean(dateRange.value[0]),
)
const isEmpty = computed(
  () =>
    !loading.value &&
    !error.value &&
    utilization.value?.summary.caseCount === 0 &&
    onTime.value?.summary.firstCaseCount === 0 &&
    cancellations.value?.summary.totalCases === 0,
)

const metricLabels: Record<AnalyticsMetric, string> = {
  utilization: '术间利用率',
  'on-time': '准点开台率',
  cancellations: '取消率',
}

const reasonLabels: Record<string, string> = {
  PATIENT_CONDITION: '患者情况变化',
  SURGEON_UNAVAILABLE: '术者不可用',
  ROOM_EQUIPMENT_FAILURE: '术间/设备故障',
  ANESTHESIA_RISK: '麻醉风险',
  SCHEDULE_CHANGE: '排程调整',
  OTHER: '其他',
  EMERGENCY_PREEMPT: '急诊抢占',
  UNSPECIFIED: '未记录原因',
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '排程分析加载失败',
  })
}

function currentRange(): AnalyticsRange {
  return {
    from: dateRange.value[0],
    to: dateRange.value[1],
    deptId: deptId.value || undefined,
  }
}

function percent(value: number | undefined): number {
  return Math.min(100, Math.max(0, Math.round((value ?? 0) * 1000) / 10))
}

function percentText(value: number | undefined): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}

function reasonLabel(code: string): string {
  return reasonLabels[code] ?? code
}

/**
 * S-1：科室选项从分析响应 rooms[].deptId/deptName 派生，不依赖需 m1:schedule:read 的
 * /v1/scheduling/rooms（SYS_QC_MANAGER 无该权限且异常会被吞）。已见科室跨查询累积，
 * 避免筛选后选项被当前响应裁掉。
 */
function rememberDepartments(rooms: UtilizationRoom[]): void {
  const seen = new Set(departmentOptions.value.map((item) => item.value))
  const merged = [...departmentOptions.value]
  for (const room of rooms) {
    if (room.deptId && room.deptName && !seen.has(room.deptId)) {
      seen.add(room.deptId)
      merged.push({ value: room.deptId, label: room.deptName })
    }
  }
  departmentOptions.value = merged.sort((left, right) =>
    left.label.localeCompare(right.label, 'zh-CN'),
  )
}

async function loadAll(): Promise<void> {
  if (!rangeValid.value) {
    ElMessage.warning('请选择完整的日期范围')
    return
  }
  loading.value = true
  error.value = null
  const range = currentRange()
  try {
    const [utilizationReport, onTimeReport, cancellationReport] = await Promise.all([
      getSchedulingUtilization(range),
      getSchedulingOnTime(range),
      getSchedulingCancellations(range),
    ])
    utilization.value = utilizationReport
    onTime.value = onTimeReport
    cancellations.value = cancellationReport
    rememberDepartments(utilizationReport.rooms ?? [])
  } catch (caught) {
    error.value = toApiError(caught)
    utilization.value = null
    onTime.value = null
    cancellations.value = null
  } finally {
    loading.value = false
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

async function exportMetric(metric: AnalyticsMetric): Promise<void> {
  if (!rangeValid.value) {
    ElMessage.warning('请选择完整的日期范围')
    return
  }
  exporting.value = metric
  try {
    const blob = await exportSchedulingAnalytics(metric, currentRange())
    const range = currentRange()
    downloadBlob(blob, `scheduling-${metric}-${range.from}-${range.to}.csv`)
    ElMessage.success(`${metricLabels[metric]} CSV 已导出`)
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
  <section class="analytics" data-testid="analytics-page">
    <header class="analytics__header">
      <div>
        <h1 class="analytics__title">排程分析</h1>
        <p class="analytics__hint">
          口径 analytics-1.0.0 · 时区 Asia/Shanghai · 只读聚合，数据范围按科室/术间裁剪。
          准点口径以系统内台次记录为准（HIS 未回写实际开台时间，标【待确认】）。
        </p>
      </div>
    </header>

    <el-alert
      v-if="!canRead"
      data-testid="forbidden-alert"
      type="warning"
      :closable="false"
      title="无排程分析权限"
      description="需要权限点 m1:analytics:read；请联系管理员开通。"
    />

    <template v-else>
      <el-card class="analytics__filters" shadow="never">
        <div class="analytics__filter-row">
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
            v-model="deptId"
            data-testid="dept-filter"
            placeholder="全部科室"
            clearable
            class="analytics__dept"
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
          <el-button
            data-testid="export-utilization"
            :loading="exporting === 'utilization'"
            @click="exportMetric('utilization')"
          >
            导出利用率 CSV
          </el-button>
          <el-button
            data-testid="export-on-time"
            :loading="exporting === 'on-time'"
            @click="exportMetric('on-time')"
          >
            导出准点率 CSV
          </el-button>
          <el-button
            data-testid="export-cancellations"
            :loading="exporting === 'cancellations'"
            @click="exportMetric('cancellations')"
          >
            导出取消率 CSV
          </el-button>
        </div>
      </el-card>

      <el-alert
        v-if="error"
        data-testid="error-alert"
        class="analytics__alert"
        type="error"
        :closable="false"
        :title="error.message"
        :description="`错误码 ${error.code}${error.traceId ? ` · traceId ${error.traceId}` : ''}`"
      />

      <el-empty
        v-if="isEmpty"
        data-testid="empty-state"
        description="所选日期范围与科室暂无排程数据"
      />

      <div v-else-if="!error" v-loading="loading" class="analytics__cards">
        <el-card data-testid="utilization-card" class="analytics__card" shadow="never">
          <template #header>
            <div class="analytics__card-header">
              <span>术间利用率</span>
              <el-tag size="small" type="info"> {{ utilization?.rooms.length ?? 0 }} 间 </el-tag>
            </div>
          </template>
          <div class="analytics__metric">
            {{ percentText(utilization?.summary.utilizationRate) }}
          </div>
          <el-progress
            :percentage="percent(utilization?.summary.utilizationRate)"
            :show-text="false"
            :stroke-width="8"
          />
          <p class="analytics__sub">
            {{ utilization?.summary.totalSurgeryMinutes ?? 0 }} /
            {{ utilization?.summary.availableMinutes ?? 0 }} 分钟 ·
            {{ utilization?.summary.caseCount ?? 0 }} 台次
          </p>
          <p class="analytics__formula">
            公式：{{ utilization?.formula ?? '总手术时长 / 可用时长' }} · 每间每日
            {{ utilization?.availableMinutesPerRoomPerDay ?? 600 }} 分钟
          </p>
        </el-card>

        <el-card data-testid="on-time-card" class="analytics__card" shadow="never">
          <template #header>
            <div class="analytics__card-header">
              <span>首台准点开台率</span>
              <el-tag size="small" type="info">
                阈值 ±{{ onTime?.thresholdMinutes ?? 15 }} 分钟
              </el-tag>
            </div>
          </template>
          <div data-testid="on-time-metric" class="analytics__metric">
            {{ onTimeMetricText }}
          </div>
          <el-progress
            v-if="onTimeAvailable"
            :percentage="percent(onTime?.summary.onTimeRate)"
            :show-text="false"
            :stroke-width="8"
            status="success"
          />
          <p v-else class="analytics__sub">
            系统内暂无实际开台数据（依赖 M3/HIS 回写实际开台/状态），不做 0.0% 展示。
          </p>
          <p class="analytics__sub">
            {{ onTime?.summary.onTimeCount ?? 0 }} 准点 /
            {{ onTime?.summary.evaluatedCaseCount ?? 0 }} 已评估首台 · 排除
            {{ onTimeExcludedDays }} 个术间-日
          </p>
          <p class="analytics__formula">
            实际开始来源：{{ onTime?.actualStartSource ?? 'SCHEDULE_UPDATED_AT' }}（【待确认】）
          </p>
        </el-card>

        <el-card data-testid="cancellations-card" class="analytics__card" shadow="never">
          <template #header>
            <div class="analytics__card-header">
              <span>取消率</span>
              <el-tag size="small" type="warning">
                {{ cancellations?.byReason.length ?? 0 }} 类原因
              </el-tag>
            </div>
          </template>
          <div class="analytics__metric">
            {{ percentText(cancellations?.summary.cancellationRate) }}
          </div>
          <el-progress
            :percentage="percent(cancellations?.summary.cancellationRate)"
            :show-text="false"
            :stroke-width="8"
            status="warning"
          />
          <p class="analytics__sub">
            {{ cancellations?.summary.cancelledCases ?? 0 }} 取消 /
            {{ cancellations?.summary.totalCases ?? 0 }} 总台次
          </p>
          <p class="analytics__formula">取消原因按最近 STOP 变更记录分组；无记录归 UNSPECIFIED。</p>
        </el-card>
      </div>

      <div v-if="!error && !isEmpty" v-loading="loading" class="analytics__tables">
        <el-card shadow="never">
          <template #header>各术间利用率</template>
          <el-table
            data-testid="utilization-table"
            :data="utilization?.rooms ?? []"
            size="small"
            empty-text="暂无术间数据"
          >
            <el-table-column prop="roomNo" label="术间" width="100" />
            <el-table-column prop="deptName" label="科室" min-width="140" />
            <el-table-column prop="caseCount" label="台次" width="80" />
            <el-table-column prop="totalSurgeryMinutes" label="手术分钟" width="110" />
            <el-table-column prop="availableMinutes" label="可用分钟" width="110" />
            <el-table-column label="利用率" min-width="160">
              <template #default="{ row }">
                <el-progress
                  :percentage="percent(row.utilizationRate)"
                  :format="() => percentText(row.utilizationRate)"
                />
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never">
          <template #header>各术间首台准点</template>
          <el-table
            data-testid="on-time-table"
            :data="onTime?.rooms ?? []"
            size="small"
            empty-text="暂无可评估首台"
          >
            <el-table-column prop="roomNo" label="术间" width="100" />
            <el-table-column prop="deptName" label="科室" min-width="140" />
            <el-table-column prop="evaluatedCaseCount" label="已评估首台" width="110" />
            <el-table-column prop="excludedDays" label="排除日数" width="90" />
            <el-table-column prop="onTimeCount" label="准点" width="80" />
            <el-table-column prop="delayedCount" label="延误" width="80" />
            <el-table-column label="准点率" min-width="160">
              <template #default="{ row }">
                <el-progress
                  :percentage="percent(row.onTimeRate)"
                  :format="() => percentText(row.onTimeRate)"
                  status="success"
                />
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never">
          <template #header>各术间取消率</template>
          <el-table
            data-testid="cancellation-table"
            :data="cancellations?.byRoom ?? []"
            size="small"
            empty-text="暂无台次"
          >
            <el-table-column prop="roomNo" label="术间" width="100" />
            <el-table-column prop="deptName" label="科室" min-width="140" />
            <el-table-column prop="totalCases" label="总台次" width="90" />
            <el-table-column prop="cancelledCases" label="取消" width="80" />
            <el-table-column label="取消率" min-width="160">
              <template #default="{ row }">
                <el-progress
                  :percentage="percent(row.cancellationRate)"
                  :format="() => percentText(row.cancellationRate)"
                  status="warning"
                />
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never">
          <template #header>取消原因码分组</template>
          <el-table
            data-testid="cancellation-reason-table"
            :data="cancellations?.byReason ?? []"
            size="small"
            empty-text="暂无取消台次"
          >
            <el-table-column label="原因码" min-width="160">
              <template #default="{ row }">
                {{ reasonLabel(row.reasonCode) }}（{{ row.reasonCode }}）
              </template>
            </el-table-column>
            <el-table-column prop="cancelledCases" label="取消台次" width="110" />
            <el-table-column label="占取消比" min-width="160">
              <template #default="{ row }">
                <el-progress
                  :percentage="percent(row.shareOfCancelled)"
                  :format="() => percentText(row.shareOfCancelled)"
                />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>
    </template>
  </section>
</template>

<style scoped>
.analytics {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.analytics__header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.analytics__title {
  margin: 0 0 6px;
  font-size: 22px;
}

.analytics__hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  max-width: 960px;
}

.analytics__filters {
  border: 1px solid var(--el-border-color-lighter);
}

.analytics__filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.analytics__dept {
  width: 200px;
}

.analytics__alert {
  margin-top: 4px;
}

.analytics__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.analytics__card {
  border: 1px solid var(--el-border-color-lighter);
}

.analytics__card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.analytics__metric {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.2;
  margin-bottom: 10px;
}

.analytics__sub {
  margin: 10px 0 4px;
  color: var(--el-text-color-regular);
}

.analytics__formula {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.analytics__tables {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: 16px;
}
</style>
