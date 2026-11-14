<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  categoryLabels,
  exportFileName,
  exportQcCsv,
  getIndicatorResults,
  getIndicators,
  validateRange,
  MAX_RANGE_DAYS,
  type QcExportInput,
  type QcIndicator,
  type QcIndicatorCategory,
  type QcIndicatorResult,
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

const dateRange = ref<[string, string]>([daysAgo(29), localDate()])
const indicators = ref<QcIndicator[]>([])
const results = ref<QcIndicatorResult[]>([])
const loading = ref(false)
const exporting = ref(false)
const error = ref<ApiError | null>(null)

const canRead = computed(() => auth.hasPermission('m4:qc:read'))
const canExport = computed(() => auth.hasPermission('m4:qc:export'))
const targetMetCount = computed(
  () => results.value.filter((item) => item.targetMet === true).length,
)
const isEmpty = computed(() => !loading.value && !error.value && results.value.length === 0)

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '质控指标加载失败',
  })
}

function currentRange(): QcRange {
  return { from: dateRange.value[0], to: dateRange.value[1] }
}

function categoryText(code: string): string {
  return categoryLabels[code as QcIndicatorCategory] ?? code
}

function numberText(value: number | undefined): string {
  return (value ?? 0).toLocaleString('zh-CN')
}

function valueText(item: QcIndicatorResult): string {
  const suffix = item.unit === 'PERCENT' ? '%' : '‰'
  return `${(item.value ?? 0).toFixed(2)}${suffix}`
}

function thresholdText(item: QcIndicatorResult): string {
  const operator = item.thresholdOperator === 'LTE' ? '≤' : '≥'
  return `${operator} ${item.thresholdValue ?? 0}`
}

async function loadAll(): Promise<void> {
  const invalid = validateRange(dateRange.value?.[0] ?? '', dateRange.value?.[1] ?? '')
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  loading.value = true
  error.value = null
  try {
    const [definitionList, resultList] = await Promise.all([
      getIndicators(),
      getIndicatorResults(currentRange()),
    ])
    indicators.value = definitionList.items ?? []
    results.value = resultList.items ?? []
  } catch (caught) {
    error.value = toApiError(caught)
    indicators.value = []
    results.value = []
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

async function exportIndicators(): Promise<void> {
  const invalid = validateRange(dateRange.value?.[0] ?? '', dateRange.value?.[1] ?? '')
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  exporting.value = true
  try {
    const input: QcExportInput = { type: 'indicator', range: currentRange() }
    downloadBlob(await exportQcCsv(input), exportFileName(input))
    ElMessage.success('质控指标 CSV 已导出（脱敏）')
  } catch (caught) {
    error.value = toApiError(caught)
    ElMessage.error(error.value.message)
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  if (!canRead.value) return
  await loadAll()
})
</script>

<template>
  <section class="qc" data-testid="qc-indicator-page">
    <header class="qc__header">
      <div>
        <h1 class="qc__title">质控指标看板</h1>
        <p class="qc__hint">
          口径 qc-1.0.0 · 时区 Asia/Shanghai · 指标按 code+版本 版本化；分母为 0 时不判达标。
          指标清单与阈值【待确认】（合成参考指标 provisional=true）；结构合规口径
          inTargetStatistics=false，不计入达标统计；跨度上限 {{ MAX_RANGE_DAYS }} 天。
        </p>
      </div>
    </header>

    <el-alert
      v-if="!canRead"
      data-testid="forbidden-alert"
      type="warning"
      :closable="false"
      title="无质控指标权限"
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
          <el-button data-testid="query-button" type="primary" :loading="loading" @click="loadAll">
            查询
          </el-button>
          <el-button
            v-if="canExport"
            data-testid="export-indicator"
            :loading="exporting"
            @click="exportIndicators"
          >
            导出 CSV
          </el-button>
          <el-tag data-testid="target-met-count" size="small" type="info">
            达标 {{ targetMetCount }} / {{ results.length }}
          </el-tag>
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

      <el-empty v-if="isEmpty" data-testid="empty-state" description="所选周期暂无指标结果" />

      <template v-else-if="!error">
        <el-card shadow="never">
          <template #header>
            <div class="qc__card-header">
              <span>指标结果（分子 / 分母 / 值 / 达标）</span>
              <el-tag size="small" type="info">{{ results.length }} 项</el-tag>
            </div>
          </template>
          <el-table
            data-testid="result-table"
            :data="results"
            size="small"
            empty-text="暂无指标结果"
          >
            <el-table-column prop="code" label="指标编码" width="180" />
            <el-table-column prop="name" label="指标名称" min-width="170" />
            <el-table-column label="类别" width="80">
              <template #default="{ row }">{{ categoryText(row.category) }}</template>
            </el-table-column>
            <el-table-column label="版本" width="80">
              <template #default="{ row }">{{ row.indicatorVersion }}</template>
            </el-table-column>
            <el-table-column label="分子" width="80">
              <template #default="{ row }">{{ numberText(row.numerator) }}</template>
            </el-table-column>
            <el-table-column label="分母" width="80">
              <template #default="{ row }">{{ numberText(row.denominator) }}</template>
            </el-table-column>
            <el-table-column label="指标值" width="110">
              <template #default="{ row }">{{ valueText(row) }}</template>
            </el-table-column>
            <el-table-column label="阈值" width="90">
              <template #default="{ row }">{{ thresholdText(row) }}</template>
            </el-table-column>
            <el-table-column label="达标" width="110">
              <template #default="{ row }">
                <el-tag
                  v-if="row.inTargetStatistics === false"
                  :data-testid="`target-${row.code}`"
                  size="small"
                  type="info"
                >
                  不计入达标
                </el-tag>
                <el-tag
                  v-else
                  :data-testid="`target-${row.code}`"
                  size="small"
                  :type="row.targetMet ? 'success' : 'danger'"
                >
                  {{ row.targetMet ? '达标' : '未达标' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="口径" min-width="220">
              <template #default="{ row }">{{ row.definition }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never">
          <template #header>
            <div class="qc__card-header">
              <span>指标定义（版本化）</span>
              <el-tag size="small" type="info">{{ indicators.length }} 条</el-tag>
            </div>
          </template>
          <el-table
            data-testid="indicator-table"
            :data="indicators"
            size="small"
            empty-text="暂无指标定义"
          >
            <el-table-column prop="code" label="编码" width="180" />
            <el-table-column prop="name" label="名称" min-width="150" />
            <el-table-column label="类别" width="80">
              <template #default="{ row }">{{ categoryText(row.category) }}</template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="110" />
            <el-table-column label="阈值" width="90">
              <template #default="{ row }">
                {{ row.thresholdOperator === 'LTE' ? '≤' : '≥' }} {{ row.thresholdValue }}
              </template>
            </el-table-column>
            <el-table-column label="待确认" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.provisional ? 'warning' : 'success'">
                  {{ row.provisional ? '待确认' : '已确认' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="dataSource" label="数据来源" min-width="280" />
          </el-table>
        </el-card>
      </template>
    </template>
  </section>
</template>

<style scoped>
.qc {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.qc__header {
  display: flex;
  justify-content: space-between;
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

.qc__card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
</style>
