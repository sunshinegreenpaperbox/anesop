<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  appendRecordEvent,
  createAnesthesiaRecord,
  getIntakeOutputSummary,
  listAnesthesiaRecords,
  listDrugAdministrations,
  listFluidRecords,
  listRecordEvents,
  recordDrugAdministration,
  recordFluidRecord,
  recordEventLabels,
  type AnesthesiaRecordDetail,
  type CreateRecordEventRequest,
  type DrugAdministration,
  type FluidDirection,
  type FluidRecord,
  type IntakeOutputSummary,
  type RecordEvent,
  type RecordEventType,
} from '../api/m2'

function nowWithOffset(): string {
  const date = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  const offsetMinutes = -date.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const absolute = Math.abs(offsetMinutes)
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
  )
}

const EVENT_TYPES: RecordEventType[] = [
  'ANESTHESIA_START',
  'INDUCTION',
  'INTUBATION',
  'INCISION',
  'CUSTOM',
  'EXTUBATION',
  'ANESTHESIA_END',
]
const ANESTHESIA_TYPES = [
  { value: 'GENERAL', label: '全身麻醉' },
  { value: 'REGIONAL', label: '区域阻滞' },
  { value: 'COMBINED', label: '联合麻醉' },
  { value: 'LOCAL', label: '局部麻醉' },
  { value: 'SEDATION', label: '镇静/监护麻醉' },
] as const

const route = useRoute()
const auth = useAuthStore()

const scheduleId = computed(() => {
  const value = route.params.scheduleId
  const raw = Array.isArray(value) ? value[0] : value
  return raw ?? ''
})

const record = ref<AnesthesiaRecordDetail | null>(null)
const events = ref<RecordEvent[]>([])
const drugs = ref<DrugAdministration[]>([])
const fluids = ref<FluidRecord[]>([])
const summary = ref<IntakeOutputSummary | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)

const createForm = reactive({
  anesthesiaType: 'GENERAL' as (typeof ANESTHESIA_TYPES)[number]['value'],
})

const eventForm = reactive({
  eventType: 'INDUCTION' as RecordEventType,
  occurredAt: nowWithOffset(),
  note: '',
  // 补录原因：事件时间早于最新事件或早于服务端容差窗口时必填（400 PARAM_INVALID）。
  entryReason: '',
})

const drugForm = reactive({
  drugName: '',
  dose: 10,
  unit: 'mg',
  route: 'IV',
  administeredAt: nowWithOffset(),
  narcotic: false,
  secondCheckerId: '',
  secondCheckerName: '',
  note: '',
})

const fluidForm = reactive({
  direction: 'INTAKE' as FluidDirection,
  fluidType: '',
  amountMl: 500,
  note: '',
})

const canWrite = computed(() => auth.hasPermission('m2:record:write'))
const editable = computed(() => canWrite.value && record.value !== null)
const busy = computed(() => loading.value || saving.value)
const statusLabel = computed(() =>
  record.value?.status === 'IN_PROGRESS'
    ? '进行中'
    : record.value?.status === 'DRAFT'
      ? '草稿'
      : '',
)
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权访问麻醉记录（需要 m2:record:read）'
  if (error.value.status === 404) return '麻醉记录单不存在或超出数据范围'
  return '麻醉记录单操作失败'
})
const eventTypeOptions = computed(() =>
  EVENT_TYPES.map((type) => ({ value: type, label: recordEventLabels[type] ?? type })),
)

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  if (cause instanceof ApiError) {
    ElMessage.error(cause.message)
  } else {
    ElMessage.error('请求失败，请稍后重试')
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const list = await listAnesthesiaRecords(scheduleId.value)
    record.value = list.items[0] ?? null
    if (!record.value) {
      events.value = []
      drugs.value = []
      fluids.value = []
      summary.value = null
      return
    }
  } catch (cause) {
    record.value = null
    handleError(cause)
    return
  } finally {
    loading.value = false
  }
  // 明细加载失败不清空记录单本身（保留患者/状态上下文，错误提示可重试）。
  try {
    await loadRecordDetail(record.value.recordId)
  } catch (cause) {
    handleError(cause)
  }
}

async function loadRecordDetail(recordId: string): Promise<void> {
  const [eventList, drugList, fluidList, ioSummary] = await Promise.all([
    listRecordEvents(recordId),
    listDrugAdministrations(recordId),
    listFluidRecords(recordId),
    getIntakeOutputSummary(recordId),
  ])
  events.value = eventList.items
  drugs.value = drugList.items
  fluids.value = fluidList.items
  summary.value = ioSummary
}

/** 追加事件会改变记录单状态/开始结束时间，刷新必须同时重取记录单本身。 */
async function refresh(): Promise<void> {
  await load()
}

async function createRecord(): Promise<void> {
  saving.value = true
  error.value = null
  try {
    const created = await createAnesthesiaRecord({
      scheduleId: scheduleId.value,
      anesthesiaType: createForm.anesthesiaType,
    })
    record.value = created
    events.value = []
    drugs.value = []
    fluids.value = []
    summary.value = {
      recordId: created.recordId,
      intakeMl: 0,
      outputMl: 0,
      balanceMl: 0,
      fluidCount: 0,
      computedAt: nowWithOffset(),
    }
    ElMessage.success('麻醉记录单已创建（草稿）')
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitEvent(): Promise<void> {
  if (!record.value) return
  if (!eventForm.occurredAt) {
    ElMessage.warning('请填写事件发生时间')
    return
  }
  const payload: CreateRecordEventRequest = {
    eventType: eventForm.eventType,
    occurredAt: eventForm.occurredAt,
    source: 'MANUAL',
  }
  if (eventForm.note.trim()) {
    payload.note = eventForm.note.trim()
  }
  if (eventForm.entryReason.trim()) {
    payload.entryReason = eventForm.entryReason.trim()
  }
  saving.value = true
  try {
    await appendRecordEvent(record.value.recordId, payload)
    ElMessage.success(
      `已追加事件：${recordEventLabels[eventForm.eventType] ?? eventForm.eventType}`,
    )
    eventForm.note = ''
    eventForm.entryReason = ''
    eventForm.occurredAt = nowWithOffset()
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitDrug(): Promise<void> {
  if (!record.value) return
  if (!drugForm.drugName.trim()) {
    ElMessage.warning('请填写药品名称')
    return
  }
  if (
    drugForm.narcotic &&
    (!drugForm.secondCheckerId.trim() || !drugForm.secondCheckerName.trim())
  ) {
    ElMessage.warning('麻精药品必须双人核对：请填写核对人标识与姓名（不同人）')
    return
  }
  saving.value = true
  try {
    await recordDrugAdministration(record.value.recordId, {
      drugName: drugForm.drugName.trim(),
      dose: drugForm.dose,
      unit: drugForm.unit,
      route: drugForm.route,
      administeredAt: drugForm.administeredAt,
      narcotic: drugForm.narcotic,
      ...(drugForm.narcotic
        ? {
            secondCheckerId: drugForm.secondCheckerId.trim(),
            secondCheckerName: drugForm.secondCheckerName.trim(),
          }
        : {}),
      ...(drugForm.note.trim() ? { note: drugForm.note.trim() } : {}),
    })
    ElMessage.success('用药记录已保存（append-only）')
    drugForm.drugName = ''
    drugForm.note = ''
    drugForm.narcotic = false
    drugForm.secondCheckerId = ''
    drugForm.secondCheckerName = ''
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitFluid(): Promise<void> {
  if (!record.value) return
  if (!fluidForm.fluidType.trim()) {
    ElMessage.warning('请填写液体/出量类型')
    return
  }
  if (!Number.isFinite(fluidForm.amountMl) || fluidForm.amountMl <= 0) {
    ElMessage.warning('量必须大于 0 毫升')
    return
  }
  saving.value = true
  try {
    await recordFluidRecord(record.value.recordId, {
      direction: fluidForm.direction,
      fluidType: fluidForm.fluidType.trim(),
      amountMl: fluidForm.amountMl,
      ...(fluidForm.note.trim() ? { note: fluidForm.note.trim() } : {}),
    })
    ElMessage.success('出入量明细已记录')
    fluidForm.fluidType = ''
    fluidForm.note = ''
    await refresh()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function directionLabel(direction: string): string {
  return direction === 'INTAKE' ? '入量' : '出量'
}

function formatDose(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3)
}

onMounted(load)
</script>

<template>
  <section class="record">
    <header class="record__header">
      <div>
        <h1 class="record__title">麻醉记录单</h1>
        <p class="record__subtitle">
          台次 <code>{{ scheduleId }}</code
          >；一个台次一张记录单，时间轴/用药/出入量 append-only（不可改删），
          记录单未锁定时可改（乐观锁版本）。
        </p>
      </div>
      <el-button data-testid="record-refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="error"
      data-testid="record-error"
      class="record__error"
      type="error"
      :closable="false"
      show-icon
      :title="errorTitle"
      :description="`${error.code}：${error.message}${error.traceId ? `（traceId：${error.traceId}）` : ''}`"
    />

    <el-skeleton v-if="loading && !record" :rows="4" animated />

    <section v-else-if="!record" class="record__empty" data-testid="record-empty">
      <el-empty description="该台次尚未创建麻醉记录单" />
      <div v-if="canWrite" class="record__create">
        <el-select v-model="createForm.anesthesiaType" data-testid="create-anesthesia-type">
          <el-option
            v-for="option in ANESTHESIA_TYPES"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button
          type="primary"
          data-testid="create-record"
          :loading="saving"
          @click="createRecord"
        >
          创建记录单
        </el-button>
      </div>
      <p v-else class="record__hint">当前角色无 m2:record:write，不能创建记录单。</p>
    </section>

    <template v-else>
      <el-descriptions data-testid="record-context" class="record__context" :column="4" border>
        <el-descriptions-item label="患者">
          {{ record.patientName }}（{{ record.patientNo }}）
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag
            data-testid="record-status"
            :type="record.status === 'DRAFT' ? 'info' : 'success'"
          >
            {{ statusLabel }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="麻醉方式">
          {{
            ANESTHESIA_TYPES.find((item) => item.value === record?.anesthesiaType)?.label ??
            record.anesthesiaType
          }}
        </el-descriptions-item>
        <el-descriptions-item label="版本">v{{ record.version }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ record.startedAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ record.endedAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="三方核查">
          <el-tag :type="record.threeWayCheckCompleted ? 'success' : 'warning'">
            {{ record.threeWayCheckCompleted ? '已完成' : '未完成' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="体征">
          <span data-testid="vital-placeholder">
            {{
              (record.vitalSigns?.length ?? 0) === 0
                ? '体征待采集接入（M3-M8-01）'
                : `${record.vitalSigns?.length} 条`
            }}
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 出入量汇总 -->
      <section class="record__panel" data-testid="io-summary">
        <h2 class="record__panel-title">出入量汇总（服务端计算）</h2>
        <div class="record__io">
          <div class="record__io-item">
            <span class="record__io-label">入量</span>
            <strong data-testid="io-intake">{{ summary?.intakeMl ?? 0 }} ml</strong>
          </div>
          <div class="record__io-item">
            <span class="record__io-label">出量</span>
            <strong data-testid="io-output">{{ summary?.outputMl ?? 0 }} ml</strong>
          </div>
          <div class="record__io-item">
            <span class="record__io-label">平衡</span>
            <strong data-testid="io-balance">{{ summary?.balanceMl ?? 0 }} ml</strong>
          </div>
          <div class="record__io-item">
            <span class="record__io-label">明细条数</span>
            <strong>{{ summary?.fluidCount ?? 0 }}</strong>
          </div>
        </div>
      </section>

      <!-- 时间轴 -->
      <section class="record__panel">
        <h2 class="record__panel-title">时间轴事件（append-only）</h2>
        <el-table :data="events" data-testid="event-list" size="small" border>
          <el-table-column label="序号" prop="seq" width="70" />
          <el-table-column label="事件" width="120">
            <template #default="{ row }">
              {{ recordEventLabels[row.eventType] ?? row.eventType }}
            </template>
          </el-table-column>
          <el-table-column label="发生时间" prop="occurredAt" width="220" />
          <el-table-column label="备注" prop="note" />
          <el-table-column label="补录原因" prop="entryReason" width="180" />
          <el-table-column label="录入时间" prop="recordedAt" width="220" />
        </el-table>

        <el-form v-if="editable" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="事件类型">
            <el-select v-model="eventForm.eventType" data-testid="event-type">
              <el-option
                v-for="option in eventTypeOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="发生时间">
            <el-date-picker
              v-model="eventForm.occurredAt"
              data-testid="event-occurred-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="eventForm.note" data-testid="event-note" maxlength="500" />
          </el-form-item>
          <el-form-item label="补录原因">
            <el-input
              v-model="eventForm.entryReason"
              data-testid="event-entry-reason"
              maxlength="200"
              placeholder="事件时间早于最新事件或超出容差窗口时必填"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="append-event"
              :loading="saving"
              :disabled="busy"
              @click="submitEvent"
            >
              追加事件
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <!-- 用药 -->
      <section class="record__panel">
        <h2 class="record__panel-title">用药记录（麻精类双人核对）</h2>
        <el-table :data="drugs" data-testid="drug-list" size="small" border>
          <el-table-column label="药品" prop="drugName" />
          <el-table-column label="剂量" width="120">
            <template #default="{ row }">{{ formatDose(row.dose) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="途径" prop="route" width="100" />
          <el-table-column label="给药时间" prop="administeredAt" width="220" />
          <el-table-column label="麻精" width="80">
            <template #default="{ row }">
              <el-tag :type="row.narcotic ? 'danger' : 'info'" size="small">
                {{ row.narcotic ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="核对人" width="160">
            <template #default="{ row }">{{ row.secondCheckerName ?? '—' }}</template>
          </el-table-column>
        </el-table>

        <el-form v-if="editable" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="药品名称">
            <el-input v-model="drugForm.drugName" data-testid="drug-name" maxlength="200" />
          </el-form-item>
          <el-form-item label="剂量/单位">
            <el-input-number
              v-model="drugForm.dose"
              data-testid="drug-dose"
              :min="0.001"
              :step="1"
            />
            <el-input
              v-model="drugForm.unit"
              data-testid="drug-unit"
              class="record__short"
              maxlength="16"
            />
          </el-form-item>
          <el-form-item label="给药途径">
            <el-input v-model="drugForm.route" data-testid="drug-route" maxlength="32" />
          </el-form-item>
          <el-form-item label="给药时间">
            <el-date-picker
              v-model="drugForm.administeredAt"
              data-testid="drug-administered-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </el-form-item>
          <el-form-item label="麻精药品">
            <el-switch v-model="drugForm.narcotic" data-testid="drug-narcotic" />
            <span class="record__hint">开启后必须双人核对，核对人不得为给药人本人</span>
          </el-form-item>
          <template v-if="drugForm.narcotic">
            <el-form-item label="核对人标识">
              <el-input
                v-model="drugForm.secondCheckerId"
                data-testid="drug-second-checker-id"
                placeholder="核对人用户标识（UUID）"
                maxlength="64"
              />
            </el-form-item>
            <el-form-item label="核对人姓名">
              <el-input
                v-model="drugForm.secondCheckerName"
                data-testid="drug-second-checker-name"
                maxlength="64"
              />
            </el-form-item>
          </template>
          <el-form-item label="备注">
            <el-input v-model="drugForm.note" data-testid="drug-note" maxlength="500" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="record-drug"
              :loading="saving"
              :disabled="busy"
              @click="submitDrug"
            >
              保存用药
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <!-- 输液/出入量 -->
      <section class="record__panel">
        <h2 class="record__panel-title">输液/出入量明细（append-only）</h2>
        <el-table :data="fluids" data-testid="fluid-list" size="small" border>
          <el-table-column label="方向" width="90">
            <template #default="{ row }">{{ directionLabel(row.direction) }}</template>
          </el-table-column>
          <el-table-column label="类型" prop="fluidType" />
          <el-table-column label="量（ml）" prop="amountMl" width="110" />
          <el-table-column label="记录时间" prop="recordedAt" width="220" />
          <el-table-column label="备注" prop="note" />
        </el-table>

        <el-form v-if="editable" class="record__form" label-width="110px" @submit.prevent>
          <el-form-item label="方向">
            <el-select v-model="fluidForm.direction" data-testid="fluid-direction">
              <el-option label="入量" value="INTAKE" />
              <el-option label="出量" value="OUTPUT" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型">
            <el-input v-model="fluidForm.fluidType" data-testid="fluid-type" maxlength="64" />
          </el-form-item>
          <el-form-item label="量（ml）">
            <el-input-number v-model="fluidForm.amountMl" data-testid="fluid-amount" :min="1" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="fluidForm.note" data-testid="fluid-note" maxlength="200" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="record-fluid"
              :loading="saving"
              :disabled="busy"
              @click="submitFluid"
            >
              记录出入量
            </el-button>
          </el-form-item>
        </el-form>
      </section>
    </template>
  </section>
</template>

<style scoped>
.record {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1120px;
}

.record__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.record__title {
  margin: 0 0 6px;
  font-size: 22px;
}

.record__subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

.record__panel {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
  background: var(--el-bg-color);
}

.record__panel-title {
  margin: 0 0 12px;
  font-size: 16px;
}

.record__io {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}

.record__io-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.record__io-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.record__form {
  margin-top: 16px;
}

.record__short {
  width: 120px;
  margin-left: 8px;
}

.record__hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.record__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.record__create {
  display: flex;
  gap: 12px;
}
</style>
