<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createPreopAssessment,
  finalizePreopAssessment,
  listPreopAssessments,
  updatePreopAssessment,
  type PreopApplicationSummary,
  type PreopAsaClass,
  type PreopAssessment,
  type PreopCervicalSpine,
  type PreopDasiActivity,
  type PreopMallampati,
  type PreopPlanType,
} from '../api/m2'
import {
  APFEL_ITEMS,
  DASI_ITEMS,
  RCRI_ITEMS,
  STOP_BANG_ITEMS,
  previewScores,
  type ApfelKey,
  type RcriKey,
  type RiskLevel,
  type StopBangKey,
} from '../utils/preopScores'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const ASA_OPTIONS: PreopAsaClass[] = ['I', 'II', 'III', 'IV', 'V', 'VI']
const MALLAMPATI_OPTIONS: PreopMallampati[] = ['I', 'II', 'III', 'IV']
const CERVICAL_OPTIONS: { value: PreopCervicalSpine; label: string }[] = [
  { value: 'NORMAL', label: '正常' },
  { value: 'LIMITED', label: '受限' },
  { value: 'FIXED', label: '固定' },
  { value: 'UNASSESSED', label: '未评估' },
]
const PLAN_TYPE_OPTIONS: { value: PreopPlanType; label: string }[] = [
  { value: 'GENERAL', label: '全身麻醉' },
  { value: 'REGIONAL', label: '区域阻滞' },
  { value: 'COMBINED', label: '全身 + 区域复合' },
  { value: 'MONITORED_ANESTHESIA_CARE', label: 'MAC 监护麻醉' },
  { value: 'SEDATION', label: '镇静' },
]

interface PreopFormState {
  historySummary: string
  asaClass: PreopAsaClass | ''
  mallampati: PreopMallampati | ''
  mouthOpeningCm: number | null
  cervicalSpineMobility: PreopCervicalSpine
  airwayNotes: string
  rcri: Record<RcriKey, boolean>
  apfel: Record<ApfelKey, boolean>
  stopBang: Record<StopBangKey, boolean>
  dasiActivities: PreopDasiActivity[]
  planType: PreopPlanType | ''
  airwayPlan: string
  planNotes: string
}

function emptyForm(): PreopFormState {
  return {
    historySummary: '',
    asaClass: '',
    mallampati: '',
    mouthOpeningCm: null,
    cervicalSpineMobility: 'NORMAL',
    airwayNotes: '',
    rcri: Object.fromEntries(RCRI_ITEMS.map((item) => [item.key, false])) as Record<
      RcriKey,
      boolean
    >,
    apfel: Object.fromEntries(APFEL_ITEMS.map((item) => [item.key, false])) as Record<
      ApfelKey,
      boolean
    >,
    stopBang: Object.fromEntries(STOP_BANG_ITEMS.map((item) => [item.key, false])) as Record<
      StopBangKey,
      boolean
    >,
    dasiActivities: [],
    planType: '',
    airwayPlan: '',
    planNotes: '',
  }
}

const form = reactive<PreopFormState>(emptyForm())
const application = ref<PreopApplicationSummary | null>(null)
const assessments = ref<PreopAssessment[]>([])
const assessment = ref<PreopAssessment | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const validationError = ref('')

const applicationId = computed(() => {
  const value = route.params.applicationId
  return Array.isArray(value) ? (value[0] ?? '') : (value ?? '')
})

const canWrite = computed(() => auth.hasPermission('m2:preop:write'))
const isFinalized = computed(() => assessment.value?.status === 'FINALIZED')
const draftEditable = computed(() => canWrite.value && !isFinalized.value)
const preview = computed(() => previewScores(form))

const statusLabel = computed(() => {
  if (!assessment.value) return '新建访视'
  return assessment.value.status === 'FINALIZED' ? '已最终化' : '草稿'
})

const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.isForbidden) return '无权访问术前访视'
  if (error.value.status === 404) return '手术申请不存在或不可见'
  return '加载术前访视失败'
})

const errorText = computed(() => {
  if (!error.value) return ''
  if (error.value.isForbidden) {
    return '当前账号缺少 m2:preop:read 权限，或该手术申请超出数据范围。'
  }
  return `${error.value.message}（${error.value.code} / ${error.value.traceId}）`
})

const riskLabels: Record<RiskLevel, string> = {
  LOW: '低危',
  MODERATE: '中危',
  HIGH: '高危',
}

const riskTypes: Record<RiskLevel, 'success' | 'warning' | 'danger'> = {
  LOW: 'success',
  MODERATE: 'warning',
  HIGH: 'danger',
}

function riskLabel(risk: RiskLevel): string {
  return riskLabels[risk] ?? risk
}

function riskType(risk: RiskLevel): 'success' | 'warning' | 'danger' {
  return riskTypes[risk]
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '加载术前访视失败',
  })
}

function hydrateForm(source: PreopAssessment): void {
  form.historySummary = source.historySummary
  form.asaClass = source.asaClass
  form.mallampati = source.airwayAssessment.mallampati
  form.mouthOpeningCm = source.airwayAssessment.mouthOpeningCm
  form.cervicalSpineMobility = source.airwayAssessment.cervicalSpineMobility
  form.airwayNotes = source.airwayAssessment.notes ?? ''
  for (const item of RCRI_ITEMS) form.rcri[item.key] = source.scoreInput.rcri[item.key]
  for (const item of APFEL_ITEMS) form.apfel[item.key] = source.scoreInput.apfel[item.key]
  for (const item of STOP_BANG_ITEMS) {
    form.stopBang[item.key] = source.scoreInput.stopBang[item.key]
  }
  form.dasiActivities = [...source.scoreInput.dasiActivities]
  form.planType = source.plan.planType
  form.airwayPlan = source.plan.airwayPlan ?? ''
  form.planNotes = source.plan.notes ?? ''
}

function resetForm(): void {
  Object.assign(form, emptyForm())
  validationError.value = ''
}

async function load(): Promise<void> {
  if (!applicationId.value) {
    error.value = new ApiError({
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
      message: '缺少手术申请标识',
    })
    return
  }
  loading.value = true
  error.value = null
  try {
    const list = await listPreopAssessments(applicationId.value)
    application.value = list.application
    assessments.value = list.items
    if (list.items.length > 0 && list.items[0]) {
      assessment.value = list.items[0]
      hydrateForm(list.items[0])
    } else {
      assessment.value = null
      resetForm()
    }
  } catch (caught) {
    error.value = toApiError(caught)
    application.value = null
    assessment.value = null
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  if (!form.historySummary.trim()) {
    validationError.value = '请填写病史要点'
    return false
  }
  if (!form.asaClass) {
    validationError.value = '请选择 ASA 分级'
    return false
  }
  if (!form.mallampati) {
    validationError.value = '请选择 Mallampati 分级'
    return false
  }
  if (form.mouthOpeningCm === null || form.mouthOpeningCm < 0 || form.mouthOpeningCm > 10) {
    validationError.value = '张口度必须在 0–10 cm 之间'
    return false
  }
  if (!form.planType) {
    validationError.value = '请选择麻醉计划类型'
    return false
  }
  validationError.value = ''
  return true
}

function buildPayload() {
  return {
    historySummary: form.historySummary.trim(),
    asaClass: form.asaClass as PreopAsaClass,
    airwayAssessment: {
      mallampati: form.mallampati as PreopMallampati,
      mouthOpeningCm: form.mouthOpeningCm as number,
      cervicalSpineMobility: form.cervicalSpineMobility,
      notes: form.airwayNotes.trim() || null,
    },
    scoreInput: {
      rcri: { ...form.rcri },
      apfel: { ...form.apfel },
      stopBang: { ...form.stopBang },
      dasiActivities: [...form.dasiActivities],
    },
    plan: {
      planType: form.planType as PreopPlanType,
      airwayPlan: form.airwayPlan.trim() || null,
      notes: form.planNotes.trim() || null,
    },
  }
}

async function save(): Promise<void> {
  if (!draftEditable.value || !validate()) return
  saving.value = true
  try {
    const payload = buildPayload()
    if (assessment.value && assessment.value.status === 'DRAFT') {
      assessment.value = await updatePreopAssessment(assessment.value.assessmentId, {
        version: assessment.value.version,
        ...payload,
      })
    } else {
      if (!application.value) return
      const created = await createPreopAssessment({
        applicationId: applicationId.value,
        patientSnapshot: application.value.patientSnapshot,
        ...payload,
      })
      assessment.value = created
      assessments.value = [created, ...assessments.value]
    }
    // 成功后清除上一次失败的错误条，避免“已保存”与旧错误同时出现。
    error.value = null
    ElMessage.success('访视单已保存；评分与风险等级以服务端结果为准')
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    saving.value = false
  }
}

async function finalizeAssessment(): Promise<void> {
  if (!assessment.value || assessment.value.status !== 'DRAFT' || !canWrite.value) return
  try {
    await ElMessageBox.confirm(
      '最终化后访视单不可修改，只能为同一申请新建版本；确认最终化？',
      '最终化术前访视',
      { type: 'warning', confirmButtonText: '确认最终化', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  saving.value = true
  try {
    assessment.value = await finalizePreopAssessment(assessment.value.assessmentId, {
      version: assessment.value.version,
    })
    const index = assessments.value.findIndex(
      (item) => item.assessmentId === assessment.value?.assessmentId,
    )
    if (index >= 0 && assessment.value) assessments.value[index] = assessment.value
    error.value = null
    ElMessage.success('访视单已最终化')
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    saving.value = false
  }
}

function startNewVersion(): void {
  assessment.value = null
  resetForm()
}

function goBack(): void {
  void router.push({ name: 'm1-application-detail', params: { id: applicationId.value } })
}

onMounted(() => {
  void load()
})

watch(
  () => route.params.applicationId,
  () => {
    void load()
  },
)
</script>

<template>
  <section class="preop">
    <header class="preop__header">
      <div>
        <h1 class="preop__title">术前访视与评估</h1>
        <p class="preop__subtitle">
          病史、气道、ASA 与 RCRI/Apfel/STOP-BANG/DASI
          评分；评分由服务端计算，最终化后只能新建版本。
        </p>
      </div>
      <div class="preop__actions">
        <el-button data-testid="back-button" @click="goBack">返回申请</el-button>
        <el-button
          v-if="assessment"
          data-testid="new-version-button"
          :disabled="!canWrite || !isFinalized"
          @click="startNewVersion"
        >
          新建版本
        </el-button>
        <el-button
          v-if="application"
          type="primary"
          data-testid="save-button"
          :disabled="!draftEditable || saving"
          :loading="saving"
          @click="save"
        >
          保存草稿
        </el-button>
        <el-button
          v-if="assessment && assessment.status === 'DRAFT'"
          type="warning"
          data-testid="finalize-button"
          :disabled="!canWrite || saving"
          @click="finalizeAssessment"
        >
          最终化
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      data-testid="preop-error"
      class="preop__alert"
      type="error"
      :title="errorTitle"
      :description="errorText"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="validationError"
      data-testid="validation-error"
      class="preop__alert"
      type="warning"
      :title="validationError"
      :closable="false"
      show-icon
    />

    <div v-loading="loading">
      <el-card v-if="application" class="preop__card" shadow="never">
        <template #header>
          <div class="preop__card-header">
            <span>申请与患者（脱敏快照）</span>
            <el-tag data-testid="assessment-status" :type="isFinalized ? 'success' : 'info'">
              {{ statusLabel }}
            </el-tag>
          </div>
        </template>
        <div class="preop__grid" data-testid="application-context">
          <div><span class="preop__label">申请标识</span>{{ application.applicationId }}</div>
          <div><span class="preop__label">申请状态</span>{{ application.status }}</div>
          <div><span class="preop__label">拟施术式</span>{{ application.surgeryName }}</div>
          <div>
            <span class="preop__label">患者</span>{{ application.patientSnapshot.patientName }}
          </div>
          <div>
            <span class="preop__label">患者号</span>{{ application.patientSnapshot.hisPatientNo }}
          </div>
          <div>
            <span class="preop__label">科室</span>{{ application.patientSnapshot.departmentName }}
          </div>
        </div>
      </el-card>

      <el-card v-if="application" class="preop__card" shadow="never">
        <template #header>病史与气道评估</template>
        <el-form label-width="140px" :disabled="!draftEditable">
          <el-form-item label="病史要点" required>
            <el-input
              v-model="form.historySummary"
              data-testid="history-input"
              type="textarea"
              :rows="3"
              maxlength="4000"
              show-word-limit
              placeholder="术前病史、合并症与用药要点（合成演示）"
            />
          </el-form-item>
          <div class="preop__row">
            <el-form-item label="Mallampati" required>
              <el-select
                v-model="form.mallampati"
                data-testid="mallampati-select"
                placeholder="选择分级"
              >
                <el-option
                  v-for="value in MALLAMPATI_OPTIONS"
                  :key="value"
                  :label="value"
                  :value="value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="张口度（cm）" required>
              <el-input-number
                v-model="form.mouthOpeningCm"
                data-testid="mouth-opening-input"
                :min="0"
                :max="10"
                :step="0.1"
                :precision="1"
              />
            </el-form-item>
            <el-form-item label="颈椎活动度">
              <el-select v-model="form.cervicalSpineMobility" data-testid="cervical-select">
                <el-option
                  v-for="item in CERVICAL_OPTIONS"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="ASA 分级" required>
              <el-select v-model="form.asaClass" data-testid="asa-select" placeholder="选择 ASA">
                <el-option
                  v-for="value in ASA_OPTIONS"
                  :key="value"
                  :label="value"
                  :value="value"
                />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="气道备注">
            <el-input v-model="form.airwayNotes" data-testid="airway-notes-input" maxlength="500" />
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="application" class="preop__card" shadow="never">
        <template #header>
          <div class="preop__card-header">
            <span>风险评分（实时预览，最终以服务端结果为准）</span>
            <el-tag data-testid="risk-level" :type="riskType(preview.riskLevel)">
              综合 {{ riskLabel(preview.riskLevel) }}
            </el-tag>
          </div>
        </template>
        <div class="preop__scores" data-testid="scores-panel">
          <div>
            <span class="preop__label">RCRI</span>
            <strong data-testid="preview-rcri">{{ preview.rcriScore }}</strong> 分（{{
              riskLabel(preview.rcriRisk)
            }}）
          </div>
          <div>
            <span class="preop__label">Apfel</span>
            <strong data-testid="preview-apfel">{{ preview.apfelScore }}</strong> 分（{{
              riskLabel(preview.apfelRisk)
            }}）
          </div>
          <div>
            <span class="preop__label">STOP-BANG</span>
            <strong data-testid="preview-stopbang">{{ preview.stopBangScore }}</strong> 分（{{
              riskLabel(preview.stopBangRisk)
            }}）
          </div>
          <div>
            <span class="preop__label">DASI</span>
            <strong data-testid="preview-dasi">{{ preview.dasiScore.toFixed(2) }}</strong> 分
            <el-tag v-if="preview.functionalCapacityLow" size="small" type="warning"
              >功能容量偏低</el-tag
            >
          </div>
        </div>

        <el-divider content-position="left">RCRI（6 项）</el-divider>
        <div class="preop__checks" data-testid="rcri-checks">
          <el-checkbox
            v-for="item in RCRI_ITEMS"
            :key="item.key"
            v-model="form.rcri[item.key]"
            :disabled="!draftEditable"
          >
            {{ item.label }}
          </el-checkbox>
        </div>
        <el-divider content-position="left">Apfel（4 项）</el-divider>
        <div class="preop__checks" data-testid="apfel-checks">
          <el-checkbox
            v-for="item in APFEL_ITEMS"
            :key="item.key"
            v-model="form.apfel[item.key]"
            :disabled="!draftEditable"
          >
            {{ item.label }}
          </el-checkbox>
        </div>
        <el-divider content-position="left">STOP-BANG（8 项）</el-divider>
        <div class="preop__checks" data-testid="stopbang-checks">
          <el-checkbox
            v-for="item in STOP_BANG_ITEMS"
            :key="item.key"
            v-model="form.stopBang[item.key]"
            :disabled="!draftEditable"
          >
            {{ item.label }}
          </el-checkbox>
        </div>
        <el-divider content-position="left">DASI（12 项，勾选“能够完成”）</el-divider>
        <el-checkbox-group
          v-model="form.dasiActivities"
          :disabled="!draftEditable"
          data-testid="dasi-checks"
        >
          <el-checkbox v-for="item in DASI_ITEMS" :key="item.code" :value="item.code">
            {{ item.label }}（{{ item.weight }}）
          </el-checkbox>
        </el-checkbox-group>

        <div v-if="assessment" class="preop__server" data-testid="server-scores">
          服务端结果：RCRI {{ assessment.scores.rcriScore }} / Apfel
          {{ assessment.scores.apfelScore }} / STOP-BANG {{ assessment.scores.stopBangScore }} /
          DASI {{ assessment.scores.dasiScore }} / 综合
          {{ riskLabel(assessment.scores.riskLevel) }}（量表版本
          {{ assessment.scores.calculatorVersion }}）
        </div>
      </el-card>

      <el-card v-if="application" class="preop__card" shadow="never">
        <template #header>麻醉计划</template>
        <el-form label-width="140px" :disabled="!draftEditable">
          <el-form-item label="计划类型" required>
            <el-select
              v-model="form.planType"
              data-testid="plan-type-select"
              placeholder="选择麻醉计划"
            >
              <el-option
                v-for="item in PLAN_TYPE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="气道方案">
            <el-input v-model="form.airwayPlan" data-testid="airway-plan-input" maxlength="500" />
          </el-form-item>
          <el-form-item label="计划备注">
            <el-input
              v-model="form.planNotes"
              data-testid="plan-notes-input"
              type="textarea"
              :rows="2"
              maxlength="2000"
            />
          </el-form-item>
        </el-form>
      </el-card>

      <p v-if="!loading && !application && !error" class="preop__empty">
        暂无可显示的术前访视数据。
      </p>
    </div>
  </section>
</template>

<style scoped>
.preop {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.preop__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.preop__title {
  margin: 0 0 4px;
  font-size: 22px;
}

.preop__subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.preop__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.preop__card {
  margin-top: 16px;
}

.preop__card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.preop__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 8px 16px;
  font-size: 14px;
}

.preop__row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.preop__label {
  color: var(--el-text-color-secondary);
  margin-right: 8px;
}

.preop__scores {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 32px;
  margin-bottom: 8px;
}

.preop__checks {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 24px;
}

.preop__server {
  margin-top: 12px;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 13px;
}

.preop__alert {
  margin-top: 12px;
}

.preop__empty {
  margin: 24px 0;
  color: var(--el-text-color-secondary);
}
</style>
