<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  DEFAULT_TREND_HOURS,
  REFRESH_INTERVAL_MS,
  asaClassLabels,
  dataQualityLabels,
  dataScopeLabels,
  formatClock,
  formatDelay,
  formatHour,
  formatNumber,
  formatRate,
  getDashboardTrends,
  getResourceDashboard,
  getRiskDashboard,
  resourceGapLabels,
  resourceGapSeverityLabels,
  riskLevelLabels,
  roomStatusLabels,
  shiftLabels,
  type DataQuality,
  type ResourceDashboard,
  type ResourceGapSeverity,
  type RiskDashboard,
  type TrendDashboard,
} from '../api/dashboard'

/**
 * 风险与资源大屏（M5-M18-02，契约 dashboard.yaml 0.2.0）。
 *
 * 数据：风险分层（ASA/术前评分风险等级/未确认严重告警/超时台次）、资源态势（术间利用率与
 * 状态、设备在线率、当班人员、缺口提示）与按小时趋势。**全部为聚合计数与比率，无患者标识**。
 *
 * 刷新：与实时看板一致的 10s 前端轮询（后端无状态、无 SSE），支持暂停/继续/立即刷新；
 * 轮询失败保留上次快照并叠加错误提示，避免大屏闪断。布局为暗色大屏网格（1440p/1080p 可读）。
 */
const auth = useAuthStore()

const risk = ref<RiskDashboard | null>(null)
const resources = ref<ResourceDashboard | null>(null)
const trends = ref<TrendDashboard | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const paused = ref(false)
let timer: number | null = null

const canRead = computed(() => auth.hasPermission('m5:dashboard:read'))
const hasData = computed(
  () =>
    (risk.value?.totalCaseCount ?? 0) > 0 ||
    (resources.value?.roomUtilization.roomCount ?? 0) > 0 ||
    (resources.value?.staffOnDuty.total ?? 0) > 0 ||
    (trends.value?.points.length ?? 0) > 0,
)
const isEmpty = computed(() => !loading.value && !error.value && !hasData.value)
const scopeLabel = computed(() => (risk.value ? dataScopeLabels[risk.value.dataScope] : '—'))
const quality = computed<DataQuality>(() => risk.value?.dataQuality ?? 'NO_DATA')
const qualityLabel = computed(() => dataQualityLabels[quality.value])
const qualityTagType = computed(() =>
  quality.value === 'OK' ? 'success' : quality.value === 'DEGRADED' ? 'warning' : 'info',
)
const refreshedText = computed(() => formatClock(risk.value?.updatedAt))
const delayText = computed(() => formatDelay(risk.value?.dataDelaySeconds))

/** 风险 KPI 卡（高风险/未评估/严重告警/超时台次）。 */
const riskCards = computed(() => {
  const current = risk.value
  return [
    {
      key: 'highRiskCaseCount',
      label: '高危台次',
      value: formatNumber(current?.highRiskCaseCount),
      hint: 'ASA ≥ III 或术前评分高危',
      tone: 'danger',
    },
    {
      key: 'severeAlertCount',
      label: '未确认严重告警',
      value: formatNumber(current?.severeAlertCount),
      hint: 'CRITICAL 且未确认',
      tone: 'warning',
    },
    {
      key: 'overdueCaseCount',
      label: '超时台次',
      value: formatNumber(current?.overdueCaseCount),
      hint: `延迟开始 ${formatNumber(current?.delayedStartCount)} · 超时未结束 ${formatNumber(current?.overrunCaseCount)}`,
      tone: 'warning',
    },
    {
      key: 'unassessedCaseCount',
      label: '未评估台次',
      value: formatNumber(current?.unassessedCaseCount),
      hint: `已评估 ${formatNumber(current?.assessedCaseCount)} / 台次 ${formatNumber(current?.totalCaseCount)}`,
      tone: 'info',
    },
  ]
})

const asaItems = computed(() =>
  (risk.value?.asaClassCounts ?? []).map((item) => ({
    key: item.asaClass,
    label: asaClassLabels[item.asaClass],
    count: item.count,
  })),
)
const riskLevelItems = computed(() =>
  (risk.value?.riskLevelCounts ?? []).map((item) => ({
    key: item.riskLevel,
    label: riskLevelLabels[item.riskLevel],
    count: item.count,
  })),
)
const asaTotal = computed(() => asaItems.value.reduce((sum, item) => sum + item.count, 0))
const riskLevelTotal = computed(() =>
  riskLevelItems.value.reduce((sum, item) => sum + item.count, 0),
)

const roomStatusItems = computed(() => {
  const counts = resources.value?.roomStatusCounts
  return [
    { key: 'inSurgery', label: roomStatusLabels.IN_SURGERY, value: counts?.inSurgery ?? 0 },
    { key: 'idle', label: roomStatusLabels.IDLE, value: counts?.idle ?? 0 },
    { key: 'preparing', label: roomStatusLabels.PREPARING, value: counts?.preparing ?? 0 },
    { key: 'cleaning', label: roomStatusLabels.CLEANING, value: counts?.cleaning ?? 0 },
    { key: 'recovery', label: roomStatusLabels.RECOVERY, value: counts?.recovery ?? 0 },
    {
      key: 'outOfService',
      label: roomStatusLabels.OUT_OF_SERVICE,
      value: counts?.outOfService ?? 0,
    },
  ]
})

const staffText = computed(() =>
  (resources.value?.staffOnDuty.byShift ?? [])
    .map((item) => `${shiftLabels[item.shift]} ${item.count}`)
    .join(' · '),
)

const gaps = computed(() => resources.value?.resourceGaps ?? [])
const trendPoints = computed(() => trends.value?.points ?? [])

function gapType(severity: ResourceGapSeverity): 'warning' | 'info' {
  return severity === 'WARN' ? 'warning' : 'info'
}

function gapTitle(code: keyof typeof resourceGapLabels): string {
  return resourceGapLabels[code]
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '风险与资源大屏加载失败',
  })
}

async function load(): Promise<void> {
  if (!canRead.value || loading.value) return
  loading.value = true
  try {
    const [nextRisk, nextResources, nextTrends] = await Promise.all([
      getRiskDashboard(),
      getResourceDashboard(),
      getDashboardTrends(DEFAULT_TREND_HOURS),
    ])
    risk.value = nextRisk
    resources.value = nextResources
    trends.value = nextTrends
    error.value = null
  } catch (cause) {
    error.value = toApiError(cause)
  } finally {
    loading.value = false
  }
}

function stopPolling(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

function startPolling(): void {
  stopPolling()
  if (!canRead.value) return
  timer = window.setInterval(() => {
    void load()
  }, REFRESH_INTERVAL_MS)
}

async function refreshNow(): Promise<void> {
  await load()
}

async function togglePause(): Promise<void> {
  paused.value = !paused.value
  if (paused.value) {
    stopPolling()
    return
  }
  // 继续刷新时先补一次最新数据，避免大屏展示暂停期间的旧快照。
  await load()
  startPolling()
}

onMounted(async () => {
  if (!canRead.value) return
  await load()
  startPolling()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <section class="risk-screen" data-testid="dashboard-risk-page">
    <header class="screen-header">
      <div>
        <h1>风险与资源大屏</h1>
        <p class="screen-subtitle">
          风险分层 · 术间与设备资源 · 当班人员 · 按小时趋势 · 每
          {{ REFRESH_INTERVAL_MS / 1000 }} 秒自动刷新
        </p>
      </div>
      <div class="screen-meta">
        <el-tag data-testid="data-scope" type="info" effect="plain">范围：{{ scopeLabel }}</el-tag>
        <el-tag data-testid="data-quality" :type="qualityTagType" effect="dark">
          {{ qualityLabel }}
        </el-tag>
        <span class="screen-updated" data-testid="last-updated">
          更新于 {{ refreshedText }} · 延迟 {{ delayText }}
        </span>
        <el-button data-testid="refresh-now" :loading="loading" @click="refreshNow">
          立即刷新
        </el-button>
        <el-button
          data-testid="refresh-toggle"
          :type="paused ? 'primary' : 'default'"
          @click="togglePause"
        >
          {{ paused ? '继续刷新' : '暂停刷新' }}
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="!canRead"
      data-testid="forbidden-alert"
      type="warning"
      :closable="false"
      show-icon
      title="无看板查看权限"
      description="当前账号缺少 m5:dashboard:read 权限，请联系系统管理员开通用看板只读权限。"
    />

    <template v-else>
      <el-alert
        v-if="error"
        data-testid="error-alert"
        type="error"
        :closable="false"
        show-icon
        :title="error.message"
        :description="`错误码：${error.code} · 追踪号：${error.traceId || '—'}`"
      />

      <el-empty
        v-if="isEmpty"
        data-testid="empty-state"
        description="当前数据范围暂无风险与资源数据（可能尚未排程、未访视或未排班）"
      />

      <template v-else-if="risk || resources || trends">
        <section class="screen-section" aria-labelledby="risk-heading">
          <h2 id="risk-heading">风险分层</h2>
          <div class="card-grid">
            <el-card
              v-for="card in riskCards"
              :key="card.key"
              shadow="never"
              class="metric-card"
              :class="`tone-${card.tone}`"
            >
              <div class="metric-label">{{ card.label }}</div>
              <div class="metric-value" :data-testid="`risk-${card.key}`">
                {{ card.value }}
              </div>
              <div class="metric-hint">{{ card.hint }}</div>
            </el-card>
          </div>
          <div class="split">
            <el-card shadow="never" class="panel">
              <template #header>ASA 分级分布</template>
              <div data-testid="asa-class-list">
                <div v-for="item in asaItems" :key="item.key" class="bar-row">
                  <span class="bar-label">{{ item.label }}</span>
                  <el-progress
                    class="bar"
                    :percentage="asaTotal > 0 ? Math.round((item.count / asaTotal) * 100) : 0"
                    :stroke-width="12"
                    :show-text="false"
                  />
                  <span class="bar-value">{{ item.count }}</span>
                </div>
                <p v-if="asaItems.length === 0" class="metric-hint">暂无已评估台次</p>
              </div>
            </el-card>
            <el-card shadow="never" class="panel">
              <template #header>术前评分风险等级</template>
              <div data-testid="risk-level-list">
                <div v-for="item in riskLevelItems" :key="item.key" class="bar-row">
                  <span class="bar-label">{{ item.label }}</span>
                  <el-progress
                    class="bar"
                    :percentage="
                      riskLevelTotal > 0 ? Math.round((item.count / riskLevelTotal) * 100) : 0
                    "
                    :stroke-width="12"
                    :show-text="false"
                  />
                  <span class="bar-value">{{ item.count }}</span>
                </div>
                <p v-if="riskLevelItems.length === 0" class="metric-hint">暂无已评估台次</p>
              </div>
            </el-card>
          </div>
        </section>

        <section class="screen-section" aria-labelledby="resource-heading">
          <h2 id="resource-heading">资源态势</h2>
          <div class="card-grid">
            <el-card shadow="never" class="metric-card">
              <div class="metric-label">术间利用率</div>
              <div class="metric-value" data-testid="resource-utilization">
                {{ formatRate(resources?.roomUtilization.utilizationRate) }}
              </div>
              <div class="metric-hint">
                {{ formatNumber(resources?.roomUtilization.surgeryMinutes) }} /
                {{ formatNumber(resources?.roomUtilization.availableMinutes) }} 分钟 ·
                {{ formatNumber(resources?.roomUtilization.roomCount) }} 间
              </div>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <div class="metric-label">设备在线率</div>
              <div class="metric-value" data-testid="resource-device-online-rate">
                {{ formatRate(resources?.deviceOnlineRate) }}
              </div>
              <div class="metric-hint">
                在线 {{ formatNumber(resources?.devices.online) }} /
                {{ formatNumber(resources?.devices.total) }} · 离线
                {{ formatNumber(resources?.devices.offline) }}
              </div>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <div class="metric-label">当班人员</div>
              <div class="metric-value" data-testid="resource-staff">
                {{ formatNumber(resources?.staffOnDuty.total) }}
              </div>
              <div class="metric-hint">{{ staffText || '今日无排班' }}</div>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <div class="metric-label">术间状态</div>
              <div class="status-inline" data-testid="resource-room-status">
                <span
                  v-for="item in roomStatusItems"
                  :key="item.key"
                  class="status-chip"
                  :data-testid="`resource-room-${item.key}`"
                >
                  {{ item.label }} {{ item.value }}
                </span>
              </div>
            </el-card>
          </div>
          <el-card shadow="never" class="panel">
            <template #header>资源缺口提示</template>
            <div data-testid="resource-gaps" class="gap-list">
              <el-alert
                v-for="gap in gaps"
                :key="gap.code"
                :type="gapType(gap.severity)"
                :closable="false"
                show-icon
                :title="`${gapTitle(gap.code)}（${resourceGapSeverityLabels[gap.severity]}）`"
                :description="gap.message"
              />
              <p v-if="gaps.length === 0" class="metric-hint">当前无资源缺口提示</p>
            </div>
          </el-card>
        </section>

        <section class="screen-section" aria-labelledby="trend-heading">
          <h2 id="trend-heading">按小时趋势（近 {{ (trends?.windowMinutes ?? 0) / 60 }} 小时）</h2>
          <el-card shadow="never" class="panel">
            <el-table :data="trendPoints" data-testid="trend-table" size="large">
              <el-table-column label="时段" min-width="100">
                <template #default="{ row }">{{ formatHour(row.startsAt) }}</template>
              </el-table-column>
              <el-table-column prop="alertCount" label="告警" min-width="90" />
              <el-table-column prop="criticalAlertCount" label="严重告警" min-width="100" />
              <el-table-column prop="caseCount" label="台次" min-width="90" />
              <el-table-column prop="surgeryMinutes" label="手术分钟" min-width="110" />
              <el-table-column label="利用率" min-width="100">
                <template #default="{ row }">{{ formatRate(row.utilizationRate) }}</template>
              </el-table-column>
            </el-table>
            <p v-if="trendPoints.length === 0" class="metric-hint" data-testid="trend-empty">
              暂无趋势数据
            </p>
          </el-card>
        </section>

        <el-card
          v-if="
            (risk?.pendingConfirmations.length ?? 0) > 0 ||
            (resources?.pendingConfirmations.length ?? 0) > 0
          "
          shadow="never"
          class="panel"
        >
          <template #header>口径待确认</template>
          <ul class="pending-list" data-testid="pending-confirmations">
            <li v-for="item in risk?.pendingConfirmations ?? []" :key="item">{{ item }}</li>
            <li v-for="item in resources?.pendingConfirmations ?? []" :key="item">{{ item }}</li>
          </ul>
        </el-card>
      </template>
    </template>
  </section>
</template>

<style scoped>
.risk-screen {
  --screen-panel: rgba(18, 28, 45, 0.92);
  --screen-border: rgba(84, 122, 173, 0.42);

  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 18px 28px;
  border-radius: 12px;
  color: #e6eefc;
  background: radial-gradient(circle at 20% 0%, #16273f 0%, #0b1220 55%, #070c15 100%);
}

.screen-header {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  justify-content: space-between;
}

.screen-header h1 {
  margin: 0;
  font-size: 28px;
  letter-spacing: 0.08em;
  color: #ffffff;
}

.screen-subtitle {
  margin: 6px 0 0;
  color: #8fa6c8;
  font-size: 13px;
}

.screen-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.screen-updated {
  color: #8fa6c8;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.screen-section h2 {
  margin: 0 0 10px;
  font-size: 19px;
  color: #cfe0f7;
  letter-spacing: 0.04em;
}

.card-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.split {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  margin-top: 12px;
}

.metric-card,
.panel {
  margin-top: 8px;
  border-color: var(--screen-border);
  background: var(--screen-panel);
}

.metric-card {
  margin-top: 0;
}

.metric-card :deep(.el-card__body) {
  padding: 14px 16px;
}

.metric-label {
  color: #8fa6c8;
  font-size: 13px;
  letter-spacing: 0.06em;
}

.metric-value {
  margin: 6px 0 4px;
  font-size: 38px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  color: #ffffff;
}

.tone-danger .metric-value {
  color: #ff7a7a;
}

.tone-warning .metric-value {
  color: #ffc266;
}

.tone-info .metric-value {
  color: #7fd3ff;
}

.metric-hint {
  margin: 0;
  color: #8fa6c8;
  font-size: 12px;
  line-height: 1.5;
}

.panel :deep(.el-card__header) {
  color: #cfe0f7;
  border-color: var(--screen-border);
}

.panel :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(30, 48, 76, 0.9);
  --el-table-text-color: #dce8fb;
  --el-table-header-text-color: #9fb8dc;
  --el-table-border-color: var(--screen-border);
}

.panel :deep(.el-table__inner-wrapper::before) {
  background-color: var(--screen-border);
}

.bar-row {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.bar-label {
  width: 96px;
  color: #cfe0f7;
  font-size: 13px;
}

.bar {
  flex: 1;
}

.bar-value {
  width: 46px;
  color: #ffffff;
  font-size: 18px;
  font-weight: 700;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.status-inline {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.status-chip {
  padding: 4px 10px;
  border: 1px solid var(--screen-border);
  border-radius: 999px;
  color: #dce8fb;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.gap-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pending-list {
  margin: 0;
  padding-left: 18px;
  color: #8fa6c8;
  font-size: 13px;
  line-height: 1.7;
}
</style>
