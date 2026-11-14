<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  REFRESH_INTERVAL_MS,
  caseStatusLabels,
  dataQualityLabels,
  dataScopeLabels,
  formatClock,
  formatDelay,
  formatMinutes,
  formatNumber,
  formatRate,
  getDashboardKpi,
  getRealtimeDashboard,
  localDate,
  roomStatusLabels,
  type DashboardKpi,
  type DataQuality,
  type RealtimeDashboard,
} from '../api/dashboard'

/**
 * 实时态势与 KPI 看板（M5-M18-01，契约 dashboard.yaml 0.1.0）。
 *
 * 刷新机制：**10s 前端轮询**（契约固定；后端无状态、无 SSE）。页面提供“暂停刷新/继续刷新”
 * 与“立即刷新”，避免大屏长时间无人值守时无谓请求；轮询失败保留上次数据并提示错误，
 * 不打断大屏展示。展示只含术间/科室/状态/计数与 KPI 数值，无患者标识（无 PHI）。
 */
const auth = useAuthStore()

const realtime = ref<RealtimeDashboard | null>(null)
const kpi = ref<DashboardKpi | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const paused = ref(false)
let timer: number | null = null

const canRead = computed(() => auth.hasPermission('m5:dashboard:read'))
const isEmpty = computed(
  () =>
    !loading.value &&
    !error.value &&
    (realtime.value?.rooms.length ?? 0) === 0 &&
    (realtime.value?.caseProgress.totalCount ?? 0) === 0 &&
    (kpi.value?.totalCaseCount ?? 0) === 0,
)
const scopeLabel = computed(() =>
  realtime.value ? dataScopeLabels[realtime.value.dataScope] : '—',
)
const quality = computed<DataQuality>(() => realtime.value?.dataQuality ?? 'NO_DATA')
const qualityLabel = computed(() => dataQualityLabels[quality.value])
const qualityTagType = computed(() =>
  quality.value === 'OK' ? 'success' : quality.value === 'DEGRADED' ? 'warning' : 'info',
)
const refreshedText = computed(() => formatClock(realtime.value?.updatedAt))
const delayText = computed(() => formatDelay(realtime.value?.dataDelaySeconds))

const roomStatusCards = computed(() => {
  const counts = realtime.value?.roomStatusCounts
  return [
    { key: 'inSurgery', label: roomStatusLabels.IN_SURGERY, value: counts?.inSurgery ?? 0 },
    { key: 'idle', label: roomStatusLabels.IDLE, value: counts?.idle ?? 0 },
    { key: 'cleaning', label: roomStatusLabels.CLEANING, value: counts?.cleaning ?? 0 },
    {
      key: 'outOfService',
      label: roomStatusLabels.OUT_OF_SERVICE,
      value: counts?.outOfService ?? 0,
    },
    { key: 'preparing', label: roomStatusLabels.PREPARING, value: counts?.preparing ?? 0 },
    { key: 'recovery', label: roomStatusLabels.RECOVERY, value: counts?.recovery ?? 0 },
  ]
})

const caseProgressText = computed(() => {
  const items = realtime.value?.caseProgress.statusCounts ?? []
  return items.map((item) => `${caseStatusLabels[item.status]} ${item.count}`).join(' · ')
})

const kpiCards = computed(() => {
  const current = kpi.value
  return [
    {
      key: 'caseCount',
      label: '当日台次',
      value: formatNumber(current?.caseCount),
      hint: `总台次（含取消）${formatNumber(current?.totalCaseCount)}`,
    },
    {
      key: 'startedCount',
      label: '已开始',
      value: formatNumber(current?.startedCount),
      hint: `已完成 ${formatNumber(current?.completedCount)}`,
    },
    {
      key: 'onTimeRate',
      label: '准点率',
      value: formatRate(current?.onTimeRate),
      hint: `${formatNumber(current?.onTimeCount)}/${formatNumber(current?.onTimeEvaluatedCount)} 首台（阈值 ${current?.onTimeThresholdMinutes ?? 15} 分钟）`,
    },
    {
      key: 'cancellationRate',
      label: '取消率',
      value: formatRate(current?.cancellationRate),
      hint: `取消 ${formatNumber(current?.cancelledCount)} 台`,
    },
    {
      key: 'utilizationRate',
      label: '利用率',
      value: formatRate(current?.utilizationRate),
      hint: `${formatNumber(current?.totalSurgeryMinutes)}/${formatNumber(current?.availableMinutes)} 分钟`,
    },
    {
      key: 'alertCount',
      label: '告警数',
      value: formatNumber(current?.alertCount),
      hint: `严重 ${formatNumber(current?.criticalAlertCount)}`,
    },
    {
      key: 'averageAnesthesiaMinutes',
      label: '平均麻醉时长',
      value: formatMinutes(current?.averageAnesthesiaMinutes),
      hint: `有记录 ${formatNumber(current?.recordedCaseCount)} 台 / 无时长 ${formatNumber(current?.unrecordedCaseCount)} 台`,
    },
    {
      key: 'completedCount',
      label: '已完成',
      value: formatNumber(current?.completedCount),
      hint: '记录单结束或台次已完成',
    },
  ]
})

/** 术间状态中文标签（模板内不做类型断言，保持模板简洁）。 */
function roomStatusText(status: string): string {
  return roomStatusLabels[status as keyof typeof roomStatusLabels] ?? status
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '看板加载失败',
  })
}

async function load(): Promise<void> {
  if (!canRead.value || loading.value) return
  loading.value = true
  try {
    const [nextRealtime, nextKpi] = await Promise.all([
      getRealtimeDashboard(),
      getDashboardKpi(localDate()),
    ])
    realtime.value = nextRealtime
    kpi.value = nextKpi
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
  <section class="dashboard" data-testid="dashboard-page">
    <header class="dashboard-header">
      <div class="dashboard-title">
        <h1>实时态势与 KPI 看板</h1>
        <p class="dashboard-subtitle">
          术间状态、台次进度、告警与设备在线态势 · 每 {{ REFRESH_INTERVAL_MS / 1000 }} 秒自动刷新
        </p>
      </div>
      <div class="dashboard-meta">
        <el-tag data-testid="data-scope" type="info" effect="plain">范围：{{ scopeLabel }}</el-tag>
        <el-tag data-testid="data-quality" :type="qualityTagType" effect="dark">
          {{ qualityLabel }}
        </el-tag>
        <span class="dashboard-updated" data-testid="last-updated">
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
        description="当前数据范围暂无实时数据（可能尚未排程或尚未接入设备）"
      />

      <!-- 首次加载失败时只展示错误态；轮询失败保留上一次快照并叠加错误提示（大屏不闪断）。 -->
      <template v-else-if="realtime || kpi">
        <section class="dashboard-section" aria-labelledby="room-status-heading">
          <h2 id="room-status-heading">术间状态</h2>
          <div class="status-grid" data-testid="room-status-grid">
            <el-card
              v-for="card in roomStatusCards"
              :key="card.key"
              shadow="never"
              class="status-card"
            >
              <div class="status-card-label">{{ card.label }}</div>
              <div class="status-card-value" :data-testid="`room-status-${card.key}`">
                {{ card.value }}
              </div>
            </el-card>
          </div>
        </section>

        <el-row :gutter="16">
          <el-col :xs="24" :md="8">
            <el-card shadow="never" class="panel">
              <template #header>台次进度</template>
              <div class="panel-body" data-testid="case-progress">
                <div class="metric">
                  <span class="metric-label">进行中台次</span>
                  <span class="metric-value">
                    {{ formatNumber(realtime?.caseProgress.activeCount) }}
                  </span>
                </div>
                <div class="metric">
                  <span class="metric-label">当日台次（含取消）</span>
                  <span class="metric-value">
                    {{ formatNumber(realtime?.caseProgress.totalCount) }}
                  </span>
                </div>
                <p class="metric-hint" data-testid="case-progress-detail">
                  {{ caseProgressText || '暂无台次' }}
                </p>
              </div>
            </el-card>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-card shadow="never" class="panel">
              <template #header>告警态势</template>
              <div class="panel-body" data-testid="alert-summary">
                <div class="metric">
                  <span class="metric-label">未确认告警</span>
                  <span class="metric-value warning">
                    {{ formatNumber(realtime?.alerts.unacknowledgedCount) }}
                  </span>
                </div>
                <div class="metric">
                  <span class="metric-label">严重告警</span>
                  <span class="metric-value danger">
                    {{ formatNumber(realtime?.alerts.criticalCount) }}
                  </span>
                </div>
                <p class="metric-hint">
                  已确认 {{ formatNumber(realtime?.alerts.acknowledgedCount) }} · 已忽略
                  {{ formatNumber(realtime?.alerts.ignoredCount) }}
                </p>
              </div>
            </el-card>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-card shadow="never" class="panel">
              <template #header>设备在线</template>
              <div class="panel-body" data-testid="device-summary">
                <div class="metric">
                  <span class="metric-label">在线 / 总数</span>
                  <span class="metric-value success">
                    {{ formatNumber(realtime?.devices.online) }} /
                    {{ formatNumber(realtime?.devices.total) }}
                  </span>
                </div>
                <div class="metric">
                  <span class="metric-label">离线设备</span>
                  <span class="metric-value danger">
                    {{ formatNumber(realtime?.devices.offline) }}
                  </span>
                </div>
                <el-progress
                  class="device-progress"
                  :percentage="
                    realtime && realtime.devices.total > 0
                      ? Math.round((realtime.devices.online / realtime.devices.total) * 100)
                      : 0
                  "
                  :stroke-width="10"
                  :show-text="false"
                />
                <p class="metric-hint">
                  维护中 {{ formatNumber(realtime?.devices.maintenance) }} 台
                </p>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <section class="dashboard-section" aria-labelledby="kpi-heading">
          <h2 id="kpi-heading">当日 KPI（{{ kpi?.date ?? '—' }}）</h2>
          <div class="kpi-grid" data-testid="kpi-grid">
            <el-card v-for="card in kpiCards" :key="card.key" shadow="never" class="kpi-card">
              <div class="kpi-label">{{ card.label }}</div>
              <div class="kpi-value" :data-testid="`kpi-${card.key}`">{{ card.value }}</div>
              <div class="kpi-hint">{{ card.hint }}</div>
            </el-card>
          </div>
        </section>

        <el-card shadow="never" class="panel">
          <template #header>术间明细</template>
          <el-table :data="realtime?.rooms ?? []" data-testid="room-list" size="large">
            <el-table-column prop="roomNo" label="术间" min-width="120" />
            <el-table-column prop="deptName" label="科室" min-width="160" />
            <el-table-column label="状态" min-width="120">
              <template #default="{ row }">
                <el-tag :type="row.status === 'IN_SURGERY' ? 'danger' : 'info'" effect="plain">
                  {{ roomStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="进行中" min-width="100">
              <template #default="{ row }">{{ row.caseInProgress ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column prop="plannedCaseCount" label="当日台次" min-width="100" />
          </el-table>
        </el-card>

        <el-card v-if="(kpi?.pendingConfirmations.length ?? 0) > 0" shadow="never" class="panel">
          <template #header>口径待确认</template>
          <ul class="pending-list" data-testid="pending-confirmations">
            <li v-for="item in kpi?.pendingConfirmations ?? []" :key="item">{{ item }}</li>
          </ul>
        </el-card>
      </template>
    </template>
  </section>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 8px 4px 24px;
}

.dashboard-header {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  justify-content: space-between;
}

.dashboard-title h1 {
  margin: 0;
  font-size: 26px;
  letter-spacing: 0.04em;
}

.dashboard-subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.dashboard-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.dashboard-updated {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.dashboard-section h2 {
  margin: 0 0 10px;
  font-size: 18px;
}

.status-grid,
.kpi-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.status-card-value {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.status-card-label,
.kpi-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.panel {
  margin-top: 8px;
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.metric {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.metric-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.metric-value {
  font-size: 26px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.metric-value.warning {
  color: var(--el-color-warning);
}

.metric-value.danger {
  color: var(--el-color-danger);
}

.metric-value.success {
  color: var(--el-color-success);
}

.metric-hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.device-progress {
  margin: 4px 0;
}

.kpi-value {
  margin: 6px 0 4px;
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.kpi-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}

.pending-list {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}
</style>
