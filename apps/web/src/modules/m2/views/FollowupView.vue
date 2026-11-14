<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  createFollowup,
  followupComplicationLabels,
  followupTypeLabels,
  listFollowups,
  listPainManagement,
  painManagementEffectLabels,
  painManagementModalityLabels,
  recordPainManagement,
  type CreatePostopFollowupRequest,
  type FollowupComplication,
  type FollowupType,
  type PainManagement,
  type PainManagementEffect,
  type PainManagementModality,
  type PostopFollowup,
  type RecordPainManagementRequest,
} from '../api/m2'

/**
 * 术后随访与镇痛（M3-M11-01，契约 anesthesia.yaml 0.10.0）。
 *
 * 从麻醉记录单进入：24/48 小时随访（NRS 0–10、PONV 0–3、并发症多选 + 说明、患者反馈、
 * 随访人）→ 镇痛记录（PCIA/PCEA/口服/静脉/神经阻滞，bolus/background/lockout、效果、
 * 调整记录）。随访与镇痛均 append-only：更正只能新增记录。
 *
 * 安全提示：并发症说明/患者反馈/调整说明会由服务端脱敏后落库，但页面必须提示不得录入
 * 姓名/住院号/身份证号/手机号等直接标识。并发症字典、PONV 分级、随访窗口与 PCA 参数默认值
 * 均为演示口径【待确认】，前端不自行判定临床阈值。
 */

const route = useRoute()
const auth = useAuthStore()

const recordId = computed(() => {
  const value = route.params.recordId
  const raw = Array.isArray(value) ? value[0] : value
  return raw ?? ''
})

const canRead = computed(() => auth.hasPermission('m2:followup:read'))
const canWrite = computed(() => auth.hasPermission('m2:followup:write'))

const loading = ref(false)
const busy = ref(false)
const error = ref<ApiError | null>(null)
const followups = ref<PostopFollowup[]>([])
const selectedId = ref<string | null>(null)
const painRecords = ref<PainManagement[]>([])

const complicationOptions = Object.keys(followupComplicationLabels)
const modalityOptions = Object.keys(painManagementModalityLabels)
const effectOptions = Object.keys(painManagementEffectLabels)

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

interface FollowupFormState {
  followupType: FollowupType
  assessedAt: string
  nrsScore: number | null
  ponvScore: number | null
  complications: FollowupComplication[]
  complicationNotes: string
  patientFeedback: string
  followedUpBy: string
}

const followupForm = reactive<FollowupFormState>({
  followupType: 'H24',
  assessedAt: nowWithOffset(),
  nrsScore: null,
  ponvScore: null,
  complications: [],
  complicationNotes: '',
  patientFeedback: '',
  followedUpBy: '',
})
const followupDialogVisible = ref(false)
const followupSubmitting = ref(false)

interface PainFormState {
  modality: PainManagementModality
  drug: string
  bolusDose: number | null
  backgroundDose: number | null
  lockoutMinutes: number | null
  effect: PainManagementEffect
  adjustmentNote: string
}

const painForm = reactive<PainFormState>({
  modality: 'PCIA',
  drug: '',
  bolusDose: null,
  backgroundDose: null,
  lockoutMinutes: null,
  effect: 'NOT_ASSESSED',
  adjustmentNote: '',
})
const painDialogVisible = ref(false)
const painSubmitting = ref(false)

const selected = computed(
  () => followups.value.find((item) => item.followupId === selectedId.value) ?? null,
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

async function loadPain(followupId: string): Promise<void> {
  try {
    const result = await listPainManagement(followupId)
    painRecords.value = result.items
  } catch (cause) {
    painRecords.value = []
    handleError(cause)
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    if (!canRead.value) return
    const list = await listFollowups(recordId.value)
    followups.value = list.items
    const current =
      list.items.find((item) => item.followupId === selectedId.value) ?? list.items[0] ?? null
    selectedId.value = current?.followupId ?? null
    if (current) {
      await loadPain(current.followupId)
    } else {
      painRecords.value = []
    }
  } catch (cause) {
    followups.value = []
    selectedId.value = null
    painRecords.value = []
    handleError(cause)
  } finally {
    loading.value = false
  }
}

async function selectFollowup(followupId: string): Promise<void> {
  selectedId.value = followupId
  await loadPain(followupId)
}

function onRowClick(row: PostopFollowup): void {
  void selectFollowup(row.followupId)
}

function adjustmentText(row: PainManagement): string {
  return row.adjustments.length ? row.adjustments.map((item) => item.description).join('；') : '—'
}

function resetFollowupForm(): void {
  followupForm.followupType = 'H24'
  followupForm.assessedAt = nowWithOffset()
  followupForm.nrsScore = null
  followupForm.ponvScore = null
  followupForm.complications = []
  followupForm.complicationNotes = ''
  followupForm.patientFeedback = ''
  followupForm.followedUpBy = ''
}

function openFollowupDialog(): void {
  resetFollowupForm()
  followupDialogVisible.value = true
}

async function submitFollowup(): Promise<void> {
  if (!followupForm.assessedAt) {
    ElMessage.warning('请填写随访时间')
    return
  }
  if (followupForm.nrsScore === null || followupForm.ponvScore === null) {
    ElMessage.warning('请填写 NRS 疼痛评分（0–10）与 PONV 评分（0–3）')
    return
  }
  if (!followupForm.followedUpBy.trim()) {
    ElMessage.warning('请填写随访人')
    return
  }
  followupSubmitting.value = true
  try {
    const payload: CreatePostopFollowupRequest = {
      anesthesiaRecordId: recordId.value,
      followupType: followupForm.followupType,
      assessedAt: followupForm.assessedAt,
      nrsScore: followupForm.nrsScore,
      ponvScore: followupForm.ponvScore,
      complications: followupForm.complications,
      complicationNotes: followupForm.complicationNotes.trim()
        ? followupForm.complicationNotes.trim()
        : undefined,
      patientFeedback: followupForm.patientFeedback.trim()
        ? followupForm.patientFeedback.trim()
        : undefined,
      followedUpBy: followupForm.followedUpBy.trim(),
    }
    const created = await createFollowup(payload)
    followupDialogVisible.value = false
    selectedId.value = created.followupId
    ElMessage.success('已录入术后随访（append-only）')
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    followupSubmitting.value = false
  }
}

function openPainDialog(): void {
  painForm.modality = 'PCIA'
  painForm.drug = ''
  painForm.bolusDose = null
  painForm.backgroundDose = null
  painForm.lockoutMinutes = null
  painForm.effect = 'NOT_ASSESSED'
  painForm.adjustmentNote = ''
  painDialogVisible.value = true
}

async function submitPain(): Promise<void> {
  const current = selected.value
  if (!current) return
  if (painForm.bolusDose === null || painForm.bolusDose <= 0) {
    ElMessage.warning('bolus 剂量必须大于 0（单位随药物口径，【待确认】）')
    return
  }
  if (painForm.backgroundDose === null || painForm.backgroundDose < 0) {
    ElMessage.warning('background 剂量不能为空且不得小于 0')
    return
  }
  if (painForm.lockoutMinutes === null || painForm.lockoutMinutes <= 0) {
    ElMessage.warning('lockout 时间必须大于 0 分钟')
    return
  }
  if (!painForm.drug.trim()) {
    ElMessage.warning('请填写药物/方案')
    return
  }
  painSubmitting.value = true
  try {
    const payload: RecordPainManagementRequest = {
      modality: painForm.modality,
      drug: painForm.drug.trim(),
      bolusDose: painForm.bolusDose,
      backgroundDose: painForm.backgroundDose,
      lockoutMinutes: painForm.lockoutMinutes,
      effect: painForm.effect,
      adjustments: painForm.adjustmentNote.trim()
        ? [{ adjustedAt: nowWithOffset(), description: painForm.adjustmentNote.trim() }]
        : [],
    }
    await recordPainManagement(current.followupId, payload)
    painDialogVisible.value = false
    ElMessage.success('已追加镇痛记录（append-only）')
    await loadPain(current.followupId)
  } catch (cause) {
    handleError(cause)
  } finally {
    painSubmitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="followup">
    <header class="followup__header">
      <div>
        <h1 class="followup__title">术后随访与镇痛</h1>
        <p class="followup__subtitle">
          麻醉记录单 <code>{{ recordId }}</code
          >；24/48 小时随访与镇痛记录 append-only（更正只能新增），评分/并发症字典与 PCA
          默认值均为【待确认】演示口径。
        </p>
      </div>
      <div class="followup__actions">
        <el-button data-testid="followup-refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="canWrite"
          data-testid="followup-create-open"
          type="primary"
          :disabled="busy"
          @click="openFollowupDialog"
        >
          录入随访
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      data-testid="followup-error"
      class="followup__error"
      type="error"
      :closable="false"
      show-icon
      :title="error.code"
      :description="`${error.message}${error.traceId ? `（traceId：${error.traceId}）` : ''}`"
    />

    <el-empty
      v-if="!loading && followups.length === 0"
      data-testid="followup-empty"
      description="该麻醉记录单尚无术后随访；麻醉结束后可录入 24/48 小时随访。"
    />

    <template v-if="followups.length > 0">
      <el-card shadow="never">
        <template #header>
          <div class="followup__card-header">
            <span>随访记录（append-only）</span>
            <span class="followup__hint">同一记录单同类型可多次随访，时间序列只增</span>
          </div>
        </template>
        <el-table
          :data="followups"
          data-testid="followup-list"
          highlight-current-row
          @row-click="onRowClick"
        >
          <el-table-column label="类型" width="150">
            <template #default="{ row }">
              {{ followupTypeLabels[row.followupType] ?? row.followupType }}
            </template>
          </el-table-column>
          <el-table-column prop="assessedAt" label="随访时间" width="220" />
          <el-table-column prop="nrsScore" label="NRS（0–10）" width="120" />
          <el-table-column prop="ponvScore" label="PONV（0–3）" width="120" />
          <el-table-column label="并发症" min-width="200">
            <template #default="{ row }">
              {{
                row.complications.length
                  ? row.complications
                      .map((code: string) => followupComplicationLabels[code] ?? code)
                      .join('、')
                  : '无'
              }}
            </template>
          </el-table-column>
          <el-table-column prop="followedUpBy" label="随访人" width="180" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :data-testid="`followup-select-${row.followupId}`"
                @click.stop="selectFollowup(row.followupId)"
              >
                查看
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="selected" shadow="never">
        <template #header>
          <div class="followup__card-header">
            <span>
              随访详情 ·
              {{ followupTypeLabels[selected.followupType] ?? selected.followupType }}
            </span>
            <el-tag type="info">NRS {{ selected.nrsScore }} / PONV {{ selected.ponvScore }}</el-tag>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="随访时间">{{ selected.assessedAt }}</el-descriptions-item>
          <el-descriptions-item label="随访人">{{ selected.followedUpBy }}</el-descriptions-item>
          <el-descriptions-item label="并发症">
            {{
              selected.complications.length
                ? selected.complications
                    .map((code: string) => followupComplicationLabels[code] ?? code)
                    .join('、')
                : '无'
            }}
          </el-descriptions-item>
          <el-descriptions-item label="录入时间">{{ selected.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="并发症说明" :span="2">
            {{ selected.complicationNotes ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="患者反馈" :span="2">
            {{ selected.patientFeedback ?? '—' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert
          data-testid="followup-mask-hint"
          class="followup__mask"
          type="warning"
          :closable="false"
          show-icon
          title="说明与反馈由服务端脱敏后落库；请勿录入姓名、住院号、身份证号、手机号等直接标识"
        />
      </el-card>

      <el-card v-if="selected" shadow="never">
        <template #header>
          <div class="followup__card-header">
            <span>镇痛记录（append-only）</span>
            <el-button
              v-if="canWrite"
              data-testid="pain-create-open"
              type="primary"
              size="small"
              :disabled="busy"
              @click="openPainDialog"
            >
              追加镇痛记录
            </el-button>
          </div>
        </template>
        <el-table :data="painRecords" data-testid="pain-list" empty-text="尚无镇痛记录">
          <el-table-column label="方式" width="180">
            <template #default="{ row }">
              {{ painManagementModalityLabels[row.modality] ?? row.modality }}
            </template>
          </el-table-column>
          <el-table-column prop="drug" label="药物/方案" min-width="180" />
          <el-table-column prop="bolusDose" label="bolus" width="90" />
          <el-table-column prop="backgroundDose" label="background" width="110" />
          <el-table-column prop="lockoutMinutes" label="lockout（min）" width="130" />
          <el-table-column label="效果" width="110">
            <template #default="{ row }">
              {{ painManagementEffectLabels[row.effect] ?? row.effect }}
            </template>
          </el-table-column>
          <el-table-column label="调整记录" min-width="220">
            <template #default="{ row }">
              {{ adjustmentText(row) }}
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="录入时间" width="220" />
        </el-table>
        <p class="followup__hint">
          bolus &gt; 0、background ≥ 0、lockout &gt; 0 分钟由服务端校验；PCA 参数默认值与剂量单位
          【待确认】，系统不推荐处方、不替代医师判断。
        </p>
      </el-card>
    </template>

    <el-dialog v-model="followupDialogVisible" title="录入术后随访" width="680px">
      <el-alert
        data-testid="followup-required-hint"
        type="info"
        :closable="false"
        show-icon
        title="随访只能在麻醉记录单结束后录入；NRS/PONV、随访人为必填。自由文本请勿填写直接标识（服务端脱敏）。"
      />
      <el-form label-width="130px" class="followup__form">
        <el-form-item label="随访类型">
          <el-select v-model="followupForm.followupType" data-testid="followup-type">
            <el-option
              v-for="type in ['H24', 'H48']"
              :key="type"
              :label="followupTypeLabels[type]"
              :value="type"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="随访时间">
          <el-date-picker
            v-model="followupForm.assessedAt"
            data-testid="followup-assessed-at"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
          />
        </el-form-item>
        <el-form-item label="NRS 疼痛评分">
          <el-input-number
            v-model="followupForm.nrsScore"
            data-testid="followup-nrs"
            :min="0"
            :max="10"
          />
          <span class="followup__hint">0 无痛 – 10 剧痛（量表口径【待确认】）</span>
        </el-form-item>
        <el-form-item label="PONV 评分">
          <el-input-number
            v-model="followupForm.ponvScore"
            data-testid="followup-ponv"
            :min="0"
            :max="3"
          />
          <span class="followup__hint">0 无 – 3 严重呕吐（演示分级【待确认】）</span>
        </el-form-item>
        <el-form-item label="并发症">
          <el-checkbox-group
            v-model="followupForm.complications"
            data-testid="followup-complications"
          >
            <el-checkbox
              v-for="code in complicationOptions"
              :key="code"
              :value="code"
              :label="followupComplicationLabels[code]"
            />
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="并发症说明">
          <el-input
            v-model="followupForm.complicationNotes"
            data-testid="followup-notes"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="请勿填写姓名/住院号/身份证号/手机号（服务端脱敏）"
          />
        </el-form-item>
        <el-form-item label="患者反馈">
          <el-input
            v-model="followupForm.patientFeedback"
            data-testid="followup-feedback"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="请勿填写姓名/住院号/身份证号/手机号（服务端脱敏）"
          />
        </el-form-item>
        <el-form-item label="随访人">
          <el-input
            v-model="followupForm.followedUpBy"
            data-testid="followup-by"
            maxlength="64"
            placeholder="演示随访护士（合成）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="followupDialogVisible = false">取消</el-button>
        <el-button
          data-testid="followup-submit"
          type="primary"
          :loading="followupSubmitting"
          @click="submitFollowup"
        >
          提交随访
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="painDialogVisible" title="追加镇痛记录" width="640px">
      <el-form label-width="130px" class="followup__form">
        <el-form-item label="镇痛方式">
          <el-select v-model="painForm.modality" data-testid="pain-modality">
            <el-option
              v-for="code in modalityOptions"
              :key="code"
              :label="painManagementModalityLabels[code]"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="药物/方案">
          <el-input v-model="painForm.drug" data-testid="pain-drug" maxlength="128" />
        </el-form-item>
        <el-form-item label="bolus 剂量">
          <el-input-number
            v-model="painForm.bolusDose"
            data-testid="pain-bolus"
            :min="0"
            :step="0.5"
            :precision="2"
          />
          <span class="followup__hint">必须 &gt; 0；单位随药物口径【待确认】</span>
        </el-form-item>
        <el-form-item label="background 剂量">
          <el-input-number
            v-model="painForm.backgroundDose"
            data-testid="pain-background"
            :min="0"
            :step="0.5"
            :precision="2"
          />
          <span class="followup__hint">≥ 0</span>
        </el-form-item>
        <el-form-item label="lockout（分钟）">
          <el-input-number v-model="painForm.lockoutMinutes" data-testid="pain-lockout" :min="1" />
          <span class="followup__hint">必须 &gt; 0；默认值未内置【待确认】</span>
        </el-form-item>
        <el-form-item label="镇痛效果">
          <el-select v-model="painForm.effect" data-testid="pain-effect">
            <el-option
              v-for="code in effectOptions"
              :key="code"
              :label="painManagementEffectLabels[code]"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调整说明">
          <el-input
            v-model="painForm.adjustmentNote"
            data-testid="pain-adjustment-note"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="可选；调整只能新增镇痛记录，不得改写历史"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="painDialogVisible = false">取消</el-button>
        <el-button
          data-testid="pain-submit"
          type="primary"
          :loading="painSubmitting"
          @click="submitPain"
        >
          提交镇痛记录
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.followup {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.followup__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.followup__title {
  margin: 0;
  font-size: 20px;
}

.followup__subtitle {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.followup__actions {
  display: flex;
  gap: 8px;
}

.followup__card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.followup__hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.followup__mask {
  margin-top: 12px;
}

.followup__form {
  margin-top: 12px;
}
</style>
