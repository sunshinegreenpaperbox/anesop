<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  admitPacuRecord,
  dischargePacuRecord,
  getPacuDischarge,
  getPacuRecord,
  listPacuAssessments,
  listPacuRecords,
  pacuAirwayLabels,
  pacuConsciousnessLabels,
  pacuDestinationLabels,
  pacuStatusLabels,
  recordPacuAssessment,
  type CreatePacuRecordRequest,
  type PacuAssessment,
  type PacuDischarge,
  type PacuRecord,
  type RecordPacuAssessmentRequest,
} from '../api/m2'

/**
 * PACU 复苏记录与转出（M3-M10-01，契约 anesthesia.yaml 0.9.0）。
 *
 * 入室 → Aldrete 评分表（实时总分，提交后以服务端计算为准）→ 时间序列 → 转出
 * （去向 + SBAR + 达标确认）。转出阈值由服务端配置（默认 9，【待确认】），
 * 前端只做展示与二次确认，不用自己的总分判断能否转出。
 */

const route = useRoute()
const auth = useAuthStore()

const recordId = computed(() => {
  const value = route.params.recordId
  const raw = Array.isArray(value) ? value[0] : value
  return raw ?? ''
})

const canRead = computed(() => auth.hasPermission('m2:pacu:read'))
const canWrite = computed(() => auth.hasPermission('m2:pacu:write'))

const loading = ref(false)
const busy = ref(false)
const error = ref<ApiError | null>(null)
const pacuRecords = ref<PacuRecord[]>([])
const selected = ref<PacuRecord | null>(null)
const assessments = ref<PacuAssessment[]>([])
const discharge = ref<PacuDischarge | null>(null)
const unmetConditions = ref<{ field: string; reason: string }[]>([])

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

/** 最近一条未转出记录（否则取最新一条已转出记录）。 */
const activeRecord = computed(
  () => pacuRecords.value.find((item) => item.status !== 'DISCHARGED') ?? null,
)

const aldreteItems = [
  {
    key: 'activity',
    label: '活动能力',
    options: [
      { value: 0, label: '不能活动肢体（自主或遵嘱）' },
      { value: 1, label: '可活动两个肢体（自主或遵嘱）' },
      { value: 2, label: '可活动四个肢体（自主或遵嘱）' },
    ],
  },
  {
    key: 'respiration',
    label: '呼吸',
    options: [
      { value: 0, label: '呼吸暂停' },
      { value: 1, label: '呼吸困难或呼吸受限' },
      { value: 2, label: '能深呼吸并有效咳嗽' },
    ],
  },
  {
    key: 'circulation',
    label: '循环',
    options: [
      { value: 0, label: '血压偏离术前 >50%' },
      { value: 1, label: '偏离 20%–50%' },
      { value: 2, label: '偏离 <20%' },
    ],
  },
  {
    key: 'consciousness',
    label: '意识',
    options: [
      { value: 0, label: '无反应' },
      { value: 1, label: '呼之可醒' },
      { value: 2, label: '完全清醒' },
    ],
  },
  {
    key: 'oxygenation',
    label: '氧合',
    options: [
      { value: 0, label: '吸氧下 SpO₂ <90%' },
      { value: 1, label: '需吸氧维持 SpO₂ >90%' },
      { value: 2, label: '空气下 SpO₂ >92%' },
    ],
  },
] as const

type ScoreKey = (typeof aldreteItems)[number]['key']

const admissionForm = reactive({
  admittedAt: nowWithOffset(),
  // B-1：入室关键体征不预填任何“演示值”，必须由医护人员逐项录入；空值不提交。
  systolicBp: null as number | null,
  diastolicBp: null as number | null,
  heartRate: null as number | null,
  spo2: null as number | null,
  respiratoryRate: null as number | null,
  temperature: null as number | null,
  consciousness: null as PacuConsciousnessValue | null,
  painScore: null as number | null,
  nausea: false,
  airway: null as PacuAirwayValue | null,
})
const admissionVisible = ref(false)
const admissionLoading = ref(false)

type PacuConsciousnessValue = NonNullable<CreatePacuRecordRequest['consciousness']>
type PacuAirwayValue = NonNullable<CreatePacuRecordRequest['airway']>

const scoreForm = reactive<AldreteForm>({
  assessedAt: nowWithOffset(),
  activity: null,
  respiration: null,
  circulation: null,
  consciousness: null,
  oxygenation: null,
  notes: '',
})

interface AldreteForm {
  assessedAt: string
  activity: number | null
  respiration: number | null
  circulation: number | null
  consciousness: number | null
  oxygenation: number | null
  notes: string
}

/** 五项全部显式选择后才可提交（0 分也是有效选择，未选为 null）。 */
const selectedScores = computed<Record<ScoreKey, number> | null>(() => {
  const { activity, respiration, circulation, consciousness, oxygenation } = scoreForm
  if (
    activity === null ||
    respiration === null ||
    circulation === null ||
    consciousness === null ||
    oxygenation === null
  ) {
    return null
  }
  return { activity, respiration, circulation, consciousness, oxygenation }
})

const localTotal = computed<number | null>(() => {
  const scores = selectedScores.value
  if (!scores) return null
  return (
    scores.activity +
    scores.respiration +
    scores.circulation +
    scores.consciousness +
    scores.oxygenation
  )
})

const scoreComplete = computed(() => selectedScores.value !== null)

const threshold = computed(() => selected.value?.aldreteThreshold ?? 9)
const scoreMeetsThreshold = computed(
  () => localTotal.value !== null && localTotal.value >= threshold.value,
)

const dischargeForm = reactive({
  destination: 'WARD' as NonNullable<PacuDischargeRequestDestination>,
  situation: '',
  background: '',
  assessment: '',
  recommendation: '',
  criteriaConfirmed: false,
})
type PacuDischargeRequestDestination = 'WARD' | 'ICU' | 'HDU' | 'DAY_SURGERY' | 'HOME'
const dischargeVisible = ref(false)
const dischargeLoading = ref(false)

const active = computed(() => selected.value ?? activeRecord.value)

/** S-1：转出对话框只展示服务端最近一次评估，绝不使用本地未提交分值。 */
const serverLatestTotal = computed(() => active.value?.latestAldreteTotal ?? null)
const serverLatestAssessmentAt = computed(() => active.value?.latestAssessmentAt ?? null)
const serverMeetsThreshold = computed(
  () => serverLatestTotal.value !== null && serverLatestTotal.value >= threshold.value,
)

function describeError(cause: unknown): ApiError | null {
  return cause instanceof ApiError ? cause : null
}

function handleError(cause: unknown): void {
  const apiError = describeError(cause)
  error.value = apiError
  if (apiError) {
    ElMessage.error(`${apiError.code}：${apiError.message}`)
  } else {
    ElMessage.error('请求失败，请稍后重试')
  }
}

async function loadSelected(pacuId: string): Promise<void> {
  const [detail, timeline, dischargeResult] = await Promise.allSettled([
    getPacuRecord(pacuId),
    listPacuAssessments(pacuId),
    getPacuDischarge(pacuId),
  ])
  if (detail.status === 'fulfilled') selected.value = detail.value
  if (timeline.status === 'fulfilled') assessments.value = timeline.value.items
  discharge.value = dischargeResult.status === 'fulfilled' ? dischargeResult.value : null
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    if (!canRead.value) return
    const list = await listPacuRecords(recordId.value)
    pacuRecords.value = list.items
    const current = activeRecord.value ?? list.items[0] ?? null
    if (current) {
      await loadSelected(current.pacuId)
    } else {
      selected.value = null
      assessments.value = []
      discharge.value = null
    }
  } catch (cause) {
    pacuRecords.value = []
    selected.value = null
    handleError(cause)
  } finally {
    loading.value = false
  }
}

function resetAdmissionForm(): void {
  admissionForm.admittedAt = nowWithOffset()
  admissionForm.systolicBp = null
  admissionForm.diastolicBp = null
  admissionForm.heartRate = null
  admissionForm.spo2 = null
  admissionForm.respiratoryRate = null
  admissionForm.temperature = null
  admissionForm.consciousness = null
  admissionForm.painScore = null
  admissionForm.nausea = false
  admissionForm.airway = null
}

function openAdmission(): void {
  unmetConditions.value = []
  resetAdmissionForm()
  admissionVisible.value = true
}

function isPresent(value: number | null | undefined): value is number {
  return value !== null && value !== undefined && !Number.isNaN(value)
}

async function submitAdmission(): Promise<void> {
  const systolicBp = admissionForm.systolicBp
  const diastolicBp = admissionForm.diastolicBp
  const heartRate = admissionForm.heartRate
  const spo2 = admissionForm.spo2
  const respiratoryRate = admissionForm.respiratoryRate
  const temperature = admissionForm.temperature
  const painScore = admissionForm.painScore
  const consciousness = admissionForm.consciousness
  const airway = admissionForm.airway
  if (
    !isPresent(systolicBp) ||
    !isPresent(diastolicBp) ||
    !isPresent(heartRate) ||
    !isPresent(spo2) ||
    !isPresent(respiratoryRate) ||
    !isPresent(temperature)
  ) {
    ElMessage.warning('请逐项填写入室关键体征（SBP/DBP/HR/SpO₂/RR/TEMP），不预填默认值')
    return
  }
  if (painScore === null || consciousness === null || airway === null) {
    ElMessage.warning('请显式选择入室意识、疼痛评分与气道方式')
    return
  }
  if (
    systolicBp < 40 ||
    systolicBp > 300 ||
    diastolicBp < 20 ||
    diastolicBp > 200 ||
    heartRate < 20 ||
    heartRate > 250 ||
    spo2 < 50 ||
    spo2 > 100 ||
    respiratoryRate < 4 ||
    respiratoryRate > 60 ||
    temperature < 30 ||
    temperature > 45 ||
    painScore < 0 ||
    painScore > 10
  ) {
    ElMessage.warning('入室体征/疼痛评分超出物理范围，请核对后重新填写')
    return
  }
  admissionLoading.value = true
  try {
    const payload: CreatePacuRecordRequest = {
      recordId: recordId.value,
      admittedAt: admissionForm.admittedAt,
      vitalSigns: {
        systolicBp,
        diastolicBp,
        heartRate,
        spo2,
        respiratoryRate,
        temperature,
      },
      consciousness,
      painScore,
      nausea: admissionForm.nausea,
      airway,
    }
    const created = await admitPacuRecord(payload)
    admissionVisible.value = false
    ElMessage.success('已入 PACU')
    await load()
    if (created) selected.value = created
  } catch (cause) {
    handleError(cause)
  } finally {
    admissionLoading.value = false
  }
}

async function submitAssessment(): Promise<void> {
  const pacu = active.value
  if (!pacu) return
  const scores = selectedScores.value
  if (!scores) {
    ElMessage.warning('请逐项选择 Aldrete 五项（0/1/2）后再提交；未选分项不可提交')
    return
  }
  if (!scoreForm.assessedAt) {
    ElMessage.warning('请填写评估时间')
    return
  }
  busy.value = true
  try {
    const payload: RecordPacuAssessmentRequest = {
      assessedAt: scoreForm.assessedAt,
      activity: scores.activity,
      respiration: scores.respiration,
      circulation: scores.circulation,
      consciousness: scores.consciousness,
      oxygenation: scores.oxygenation,
      notes: scoreForm.notes.trim() ? scoreForm.notes.trim() : undefined,
    }
    const saved = await recordPacuAssessment(pacu.pacuId, payload)
    ElMessage.success(`已记录 Aldrete 评估：${saved.aldreteTotal} 分（服务端计算）`)
    scoreForm.assessedAt = nowWithOffset()
    scoreForm.activity = null
    scoreForm.respiration = null
    scoreForm.circulation = null
    scoreForm.consciousness = null
    scoreForm.oxygenation = null
    scoreForm.notes = ''
    await loadSelected(pacu.pacuId)
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    busy.value = false
  }
}

function openDischarge(): void {
  unmetConditions.value = []
  dischargeForm.situation = ''
  dischargeForm.background = ''
  dischargeForm.assessment = ''
  dischargeForm.recommendation = ''
  dischargeForm.criteriaConfirmed = false
  dischargeForm.destination = 'WARD'
  dischargeVisible.value = true
}

async function submitDischarge(): Promise<void> {
  const pacu = active.value
  if (!pacu) return
  unmetConditions.value = []
  dischargeLoading.value = true
  try {
    await dischargePacuRecord(pacu.pacuId, {
      destination: dischargeForm.destination,
      criteriaConfirmed: dischargeForm.criteriaConfirmed,
      handover: {
        situation: dischargeForm.situation,
        background: dischargeForm.background,
        assessment: dischargeForm.assessment,
        recommendation: dischargeForm.recommendation,
      },
    })
    dischargeVisible.value = false
    ElMessage.success('已转出')
    await load()
  } catch (cause) {
    const apiError = describeError(cause)
    if (apiError?.status === 409 && apiError.details.length > 0) {
      unmetConditions.value = apiError.details
    }
    handleError(cause)
  } finally {
    dischargeLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="pacu">
    <header class="pacu__header">
      <div>
        <h1 class="pacu__title">复苏记录（PACU）</h1>
        <p class="pacu__subtitle">
          麻醉记录单 <code>{{ recordId }}</code
          >；Aldrete 总分由服务端计算（append-only 时间序列），转出须达到服务端阈值并填写 SBAR
          交接。
        </p>
      </div>
      <div class="pacu__actions">
        <el-button data-testid="pacu-refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="canWrite && !activeRecord"
          data-testid="pacu-admit-open"
          type="primary"
          :disabled="busy"
          @click="openAdmission"
        >
          入室
        </el-button>
        <el-button
          v-if="canWrite && activeRecord && activeRecord.status !== 'DISCHARGED'"
          data-testid="pacu-discharge-open"
          type="warning"
          :disabled="busy"
          @click="openDischarge"
        >
          转出
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      data-testid="pacu-error"
      class="pacu__error"
      type="error"
      :closable="false"
      show-icon
      :title="error.code"
      :description="`${error.message}${error.traceId ? `（traceId：${error.traceId}）` : ''}`"
    />

    <el-empty
      v-if="!loading && !active"
      data-testid="pacu-empty"
      description="该麻醉记录单尚无复苏记录；记录麻醉开始后可入 PACU。"
    />

    <template v-if="active">
      <el-card shadow="never">
        <template #header>
          <div class="pacu__card-header">
            <span>入室信息</span>
            <el-tag
              data-testid="pacu-status"
              :type="active.status === 'DISCHARGED' ? 'info' : 'success'"
            >
              {{ pacuStatusLabels[active.status] ?? active.status }}
            </el-tag>
          </div>
        </template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="入室时间">{{ active.admittedAt }}</el-descriptions-item>
          <el-descriptions-item label="转出时间">
            {{ active.dischargedAt ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="转出阈值（【待确认】）">
            {{ active.aldreteThreshold }} 分
          </el-descriptions-item>
          <el-descriptions-item label="最近 Aldrete 总分">
            <span data-testid="pacu-latest-total">
              {{ active.latestAldreteTotal ?? '尚未评估' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="意识">
            {{ pacuConsciousnessLabels[active.consciousness] ?? active.consciousness }}
          </el-descriptions-item>
          <el-descriptions-item label="气道">
            {{ pacuAirwayLabels[active.airway] ?? active.airway }}
          </el-descriptions-item>
          <el-descriptions-item label="疼痛（NRS）">{{ active.painScore }}</el-descriptions-item>
          <el-descriptions-item label="恶心/呕吐">{{
            active.nausea ? '是' : '否'
          }}</el-descriptions-item>
          <el-descriptions-item label="入室生命体征">
            <span data-testid="pacu-vitals">
              BP {{ active.vitalSigns.systolicBp ?? '—' }}/{{
                active.vitalSigns.diastolicBp ?? '—'
              }}
              mmHg；HR {{ active.vitalSigns.heartRate ?? '—' }} bpm；SpO₂
              {{ active.vitalSigns.spo2 ?? '—' }}%；RR
              {{ active.vitalSigns.respiratoryRate ?? '—' }} 次/分；T
              {{ active.vitalSigns.temperature ?? '—' }} ℃
            </span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card v-if="canWrite && active.status !== 'DISCHARGED'" shadow="never">
        <template #header>
          <div class="pacu__card-header">
            <span>Aldrete 评分</span>
            <span data-testid="aldrete-total" class="pacu__total">
              当前总分 {{ localTotal ?? '—' }} / 10（阈值 {{ threshold }}）
            </span>
          </div>
        </template>
        <el-form label-width="120px">
          <el-form-item v-for="item in aldreteItems" :key="item.key" :label="item.label">
            <el-radio-group v-model="scoreForm[item.key]" :data-testid="`aldrete-${item.key}`">
              <el-radio v-for="option in item.options" :key="option.value" :value="option.value">
                {{ option.value }} 分 · {{ option.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="评估时间">
            <el-date-picker
              v-model="scoreForm.assessedAt"
              data-testid="aldrete-assessed-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="scoreForm.notes" data-testid="aldrete-notes" maxlength="500" />
          </el-form-item>
          <el-form-item>
            <el-alert
              v-if="!scoreComplete"
              data-testid="aldrete-incomplete"
              type="info"
              :closable="false"
              show-icon
              title="请逐项选择 Aldrete 五项（0/1/2）；未选齐不可提交，0 分也须显式选择"
            />
            <el-alert
              v-else
              data-testid="aldrete-threshold-hint"
              :type="scoreMeetsThreshold ? 'success' : 'warning'"
              :closable="false"
              show-icon
              :title="
                scoreMeetsThreshold
                  ? '当前总分已达到转出阈值（仍以服务端计算与医师判断为准）'
                  : `当前总分低于转出阈值 ${threshold} 分，暂不可转出`
              "
            />
          </el-form-item>
          <el-form-item>
            <el-button
              data-testid="aldrete-submit"
              type="primary"
              :loading="busy"
              :disabled="!scoreComplete || busy"
              @click="submitAssessment"
            >
              记录评估
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="pacu__card-header">
            <span>Aldrete 时间序列（append-only）</span>
            <span class="pacu__hint">更正只能新增评估，不可修改/删除</span>
          </div>
        </template>
        <el-table :data="assessments" data-testid="aldrete-timeline" empty-text="尚未评估">
          <el-table-column prop="assessedAt" label="评估时间" width="220" />
          <el-table-column prop="activity" label="活动" width="80" />
          <el-table-column prop="respiration" label="呼吸" width="80" />
          <el-table-column prop="circulation" label="循环" width="80" />
          <el-table-column prop="consciousness" label="意识" width="80" />
          <el-table-column prop="oxygenation" label="氧合" width="80" />
          <el-table-column prop="aldreteTotal" label="总分" width="90" />
          <el-table-column prop="notes" label="备注" />
        </el-table>
      </el-card>

      <el-card v-if="discharge" shadow="never">
        <template #header>
          <div class="pacu__card-header">
            <span>转出与交接（SBAR）</span>
            <el-tag type="info">
              {{ pacuDestinationLabels[discharge.destination] ?? discharge.destination }}
            </el-tag>
          </div>
        </template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="转出时间">{{ discharge.dischargedAt }}</el-descriptions-item>
          <el-descriptions-item label="转出时 Aldrete">
            {{ discharge.criteria.aldreteTotal }} / 10
          </el-descriptions-item>
          <el-descriptions-item label="阈值">
            {{ discharge.criteria.aldreteThreshold }}（已达标：{{
              discharge.criteria.meetsThreshold ? '是' : '否'
            }}）
          </el-descriptions-item>
        </el-descriptions>
        <el-descriptions :column="1" border class="pacu__sbar">
          <el-descriptions-item label="S 现状">{{
            discharge.handover.situation
          }}</el-descriptions-item>
          <el-descriptions-item label="B 背景">{{
            discharge.handover.background
          }}</el-descriptions-item>
          <el-descriptions-item label="A 评估">{{
            discharge.handover.assessment
          }}</el-descriptions-item>
          <el-descriptions-item label="R 建议">
            {{ discharge.handover.recommendation }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </template>

    <el-dialog v-model="admissionVisible" title="入室（PACU）" width="640px">
      <el-alert
        data-testid="admit-required-hint"
        type="info"
        :closable="false"
        show-icon
        title="入室关键体征/意识/疼痛/气道不预填默认值，请逐项录入；体征缺项不可提交"
      />
      <el-form label-width="120px" class="pacu__admission-form">
        <el-form-item label="入室时间">
          <el-date-picker
            v-model="admissionForm.admittedAt"
            data-testid="admit-admitted-at"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
          />
        </el-form-item>
        <el-form-item label="血压（mmHg）">
          <el-input-number
            v-model="admissionForm.systolicBp"
            data-testid="admit-systolic"
            placeholder="SBP"
            :min="40"
            :max="300"
          />
          <span class="pacu__slash">/</span>
          <el-input-number
            v-model="admissionForm.diastolicBp"
            data-testid="admit-diastolic"
            placeholder="DBP"
            :min="20"
            :max="200"
          />
        </el-form-item>
        <el-form-item label="心率（bpm）">
          <el-input-number
            v-model="admissionForm.heartRate"
            data-testid="admit-heart-rate"
            placeholder="HR"
            :min="20"
            :max="250"
          />
        </el-form-item>
        <el-form-item label="SpO₂（%）">
          <el-input-number
            v-model="admissionForm.spo2"
            data-testid="admit-spo2"
            placeholder="SpO₂"
            :min="50"
            :max="100"
          />
        </el-form-item>
        <el-form-item label="呼吸（次/分）">
          <el-input-number
            v-model="admissionForm.respiratoryRate"
            data-testid="admit-respiratory-rate"
            placeholder="RR"
            :min="4"
            :max="60"
          />
        </el-form-item>
        <el-form-item label="体温（℃）">
          <el-input-number
            v-model="admissionForm.temperature"
            data-testid="admit-temperature"
            placeholder="TEMP"
            :min="30"
            :max="45"
            :step="0.1"
            :precision="1"
          />
        </el-form-item>
        <el-form-item label="意识">
          <el-select
            v-model="admissionForm.consciousness"
            data-testid="admit-consciousness"
            placeholder="请选择"
          >
            <el-option
              v-for="(label, value) in pacuConsciousnessLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="疼痛（NRS）">
          <el-input-number
            v-model="admissionForm.painScore"
            data-testid="admit-pain-score"
            placeholder="NRS"
            :min="0"
            :max="10"
          />
        </el-form-item>
        <el-form-item label="恶心/呕吐">
          <el-switch v-model="admissionForm.nausea" data-testid="admit-nausea" />
        </el-form-item>
        <el-form-item label="气道">
          <el-select v-model="admissionForm.airway" data-testid="admit-airway" placeholder="请选择">
            <el-option
              v-for="(label, value) in pacuAirwayLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="admissionVisible = false">取消</el-button>
        <el-button
          data-testid="admit-submit"
          type="primary"
          :loading="admissionLoading"
          @click="submitAdmission"
        >
          确认入室
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dischargeVisible" title="转出（PACU）" width="720px">
      <el-alert
        v-if="unmetConditions.length > 0"
        data-testid="discharge-unmet"
        type="error"
        :closable="false"
        show-icon
        title="转出条件未满足（服务端判定）"
      >
        <ul class="pacu__unmet">
          <li v-for="item in unmetConditions" :key="item.field">
            <strong>{{ item.field }}</strong
            >：{{ item.reason }}
          </li>
        </ul>
      </el-alert>
      <el-alert
        v-else
        data-testid="discharge-criteria-hint"
        :type="serverMeetsThreshold ? 'success' : 'warning'"
        :closable="false"
        show-icon
        :title="
          serverLatestTotal === null
            ? '服务端尚无 Aldrete 评估记录，无法转出（请先记录评估）'
            : `服务端最近一次 Aldrete ${serverLatestTotal} 分 / 阈值 ${threshold} 分${
                serverLatestAssessmentAt ? `（${serverLatestAssessmentAt}）` : ''
              }，以服务端评估为准`
        "
      />
      <el-form label-width="110px" class="pacu__discharge-form">
        <el-form-item label="转出去向">
          <el-select v-model="dischargeForm.destination" data-testid="discharge-destination">
            <el-option
              v-for="(label, value) in pacuDestinationLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="S 现状">
          <el-input
            v-model="dischargeForm.situation"
            data-testid="sbar-situation"
            maxlength="2000"
          />
        </el-form-item>
        <el-form-item label="B 背景">
          <el-input
            v-model="dischargeForm.background"
            data-testid="sbar-background"
            maxlength="2000"
          />
        </el-form-item>
        <el-form-item label="A 评估">
          <el-input
            v-model="dischargeForm.assessment"
            data-testid="sbar-assessment"
            maxlength="2000"
          />
        </el-form-item>
        <el-form-item label="R 建议">
          <el-input
            v-model="dischargeForm.recommendation"
            data-testid="sbar-recommendation"
            maxlength="2000"
          />
        </el-form-item>
        <el-form-item label="条件确认">
          <el-checkbox v-model="dischargeForm.criteriaConfirmed" data-testid="discharge-confirm">
            我已逐项核对 Aldrete 达标、气道/循环/疼痛与交接事项
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dischargeVisible = false">取消</el-button>
        <el-button
          data-testid="discharge-submit"
          type="primary"
          :loading="dischargeLoading"
          @click="submitDischarge"
        >
          确认转出
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.pacu {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.pacu__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.pacu__title {
  margin: 0;
  font-size: 22px;
}

.pacu__subtitle {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.pacu__actions {
  display: flex;
  gap: 8px;
}

.pacu__card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.pacu__total {
  font-weight: 600;
  color: var(--el-color-primary);
}

.pacu__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pacu__sbar {
  margin-top: 12px;
}

.pacu__slash {
  margin: 0 8px;
}

.pacu__unmet {
  margin: 6px 0 0;
  padding-left: 18px;
}

.pacu__discharge-form {
  margin-top: 12px;
}

.pacu__admission-form {
  margin-top: 12px;
}
</style>
