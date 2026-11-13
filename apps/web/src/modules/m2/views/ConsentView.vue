<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  createConsent,
  declineConsent,
  listConsentTemplates,
  listConsents,
  revokeConsent,
  signConsent,
  type AnesthesiaApplicationSummary,
  type ConsentRecord,
  type ConsentSignerRelation,
  type ConsentSignMethod,
  type ConsentTemplate,
} from '../api/m2'

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  WAITING_SIGN: '待签署',
  SIGNED: '已签署',
  DECLINED: '已拒绝',
  REVOKED: '已撤销',
}
const STATUS_TAG_TYPES: Record<string, 'info' | 'warning' | 'success' | 'danger'> = {
  DRAFT: 'info',
  WAITING_SIGN: 'warning',
  SIGNED: 'success',
  DECLINED: 'danger',
  REVOKED: 'info',
}
const RELATION_OPTIONS: { value: ConsentSignerRelation; label: string }[] = [
  { value: 'SELF', label: '患者本人' },
  { value: 'SPOUSE', label: '配偶' },
  { value: 'PARENT', label: '父母' },
  { value: 'CHILD', label: '子女' },
  { value: 'LEGAL_GUARDIAN', label: '法定代理人' },
  { value: 'OTHER', label: '其他' },
]
const METHOD_OPTIONS: { value: ConsentSignMethod; label: string }[] = [
  { value: 'PAPER', label: '纸质签署' },
  { value: 'ELECTRONIC', label: '电子签署（CA 占位）' },
]

const route = useRoute()
const auth = useAuthStore()

const applicationId = computed(() => {
  const value = route.params.applicationId
  const raw = Array.isArray(value) ? value[0] : value
  return raw ?? ''
})

const application = ref<AnesthesiaApplicationSummary | null>(null)
const templates = ref<ConsentTemplate[]>([])
const records = ref<ConsentRecord[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)

const form = reactive({
  templateId: '',
  signerName: '',
  signerRelation: 'SELF' as ConsentSignerRelation,
  signMethod: 'ELECTRONIC' as ConsentSignMethod,
})

const canWrite = computed(() => auth.hasPermission('m2:consent:write'))
const busy = computed(() => loading.value || saving.value)
const selectedTemplate = computed<ConsentTemplate | null>(
  () => templates.value.find((template) => template.templateId === form.templateId) ?? null,
)
const latestRecord = computed<ConsentRecord | null>(() => records.value[0] ?? null)
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权访问知情同意（需要 m2:consent:read）'
  if (error.value.status === 404) return '手术申请不存在或超出数据范围'
  return '知情同意操作失败'
})

function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status
}

function relationLabel(value: string | null | undefined): string {
  return RELATION_OPTIONS.find((option) => option.value === value)?.label ?? value ?? ''
}

function methodLabel(value: string | null | undefined): string {
  return METHOD_OPTIONS.find((option) => option.value === value)?.label ?? value ?? ''
}

function tagType(status: string): 'info' | 'warning' | 'success' | 'danger' {
  return STATUS_TAG_TYPES[status] ?? 'info'
}

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  if (cause instanceof ApiError) {
    ElMessage.error(cause.message)
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [templateList, recordList] = await Promise.all([
      listConsentTemplates(),
      listConsents(applicationId.value),
    ])
    templates.value = templateList.items
    application.value = recordList.application
    records.value = recordList.items
    if (!form.templateId) {
      const first = templates.value[0]
      if (first) form.templateId = first.templateId
    }
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

async function submitCreate(): Promise<void> {
  const template = selectedTemplate.value
  if (!template) {
    ElMessage.warning('请选择同意模板与版本')
    return
  }
  if (!form.signerName.trim()) {
    ElMessage.warning('请填写签署人姓名')
    return
  }
  saving.value = true
  error.value = null
  try {
    await createConsent({
      applicationId: applicationId.value,
      templateId: template.templateId,
      templateVersion: template.version,
      signerName: form.signerName.trim(),
      signerRelation: form.signerRelation,
      signMethod: form.signMethod,
    })
    ElMessage.success('已创建知情同意记录（待签署）')
    form.signerName = ''
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitSign(record: ConsentRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认签署《${record.templateTitle}》v${record.templateVersion}？` +
        'CA 不可用（EXT-05），将生成占位签名（SIMULATED），不代表真实 CA 有效签名。',
      '确认签署',
      { confirmButtonText: '确认签署', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  saving.value = true
  error.value = null
  try {
    await signConsent(record.consentId, { version: record.version })
    ElMessage.success('已签署（CA 占位 SIMULATED）')
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function promptReason(title: string, placeholder: string): Promise<string | null> {
  try {
    const result = await ElMessageBox.prompt(placeholder, title, {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputPlaceholder: '请填写原因（≤500 字）',
      inputValidator: (value: string) => (value ?? '').trim().length > 0 || '原因不能为空',
    })
    return result.value.trim()
  } catch {
    return null
  }
}

async function submitDecline(record: ConsentRecord): Promise<void> {
  const reason = await promptReason('拒绝知情同意', '请填写患者/代理人拒绝的原因')
  if (reason === null) return
  saving.value = true
  error.value = null
  try {
    await declineConsent(record.consentId, { version: record.version, reason })
    ElMessage.success('已记录拒绝')
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitRevoke(record: ConsentRecord): Promise<void> {
  try {
    await ElMessageBox.confirm('撤销后不可再签署本记录，需重新知情同意。确认撤销？', '确认撤销', {
      confirmButtonText: '确认撤销',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  const reason = await promptReason('撤销知情同意', '请填写撤销原因')
  if (reason === null) return
  saving.value = true
  error.value = null
  try {
    await revokeConsent(record.consentId, { version: record.version, reason })
    ElMessage.success('已撤销')
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="consent">
    <header class="consent__header">
      <div>
        <h1 class="consent__title">知情同意</h1>
        <p class="consent__subtitle">
          模板版本冻结、签署留痕；CA 不可用（EXT-05），签署为占位签名（SIMULATED）。
        </p>
      </div>
      <el-button data-testid="consent-refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="error"
      data-testid="consent-error"
      class="consent__error"
      type="error"
      :closable="false"
      show-icon
      :title="errorTitle"
      :description="`${error.code}：${error.message}${error.traceId ? `（traceId：${error.traceId}）` : ''}`"
    />

    <el-skeleton v-if="loading && !application" :rows="4" animated />

    <template v-else-if="application">
      <el-descriptions
        data-testid="application-context"
        class="consent__context"
        :column="3"
        border
      >
        <el-descriptions-item label="患者">
          {{ application.patientSnapshot.patientName }}（{{
            application.patientSnapshot.hisPatientNo
          }}）
        </el-descriptions-item>
        <el-descriptions-item label="术式">{{ application.surgeryName }}</el-descriptions-item>
        <el-descriptions-item label="申请科室">
          {{ application.departmentName }}
        </el-descriptions-item>
        <el-descriptions-item label="床号">
          {{ application.patientSnapshot.ward }} / {{ application.patientSnapshot.bedNo }}
        </el-descriptions-item>
        <el-descriptions-item label="申请状态">{{ application.status }}</el-descriptions-item>
        <el-descriptions-item label="当前同意">
          <el-tag
            v-if="latestRecord"
            data-testid="consent-status"
            :type="tagType(latestRecord.status)"
          >
            {{ statusLabel(latestRecord.status) }}
          </el-tag>
          <span v-else data-testid="consent-status">未创建</span>
        </el-descriptions-item>
      </el-descriptions>

      <section
        v-if="latestRecord?.caStatus === 'SIMULATED'"
        data-testid="ca-status"
        class="consent__ca"
      >
        <el-tag type="warning">CA 占位（SIMULATED / EXT-05）</el-tag>
        <span>
          签名标识 {{ latestRecord.signatureRef }}；摘要
          {{ latestRecord.signatureHash?.slice(0, 12) }}…；不代表真实 CA 有效签名。
        </span>
      </section>

      <section v-if="canWrite" class="consent__panel">
        <h2 class="consent__panel-title">新建同意（待签署）</h2>
        <el-form label-width="110px" @submit.prevent>
          <el-form-item label="模板版本">
            <el-select
              v-model="form.templateId"
              data-testid="template-select"
              placeholder="选择模板与版本"
              :disabled="busy"
            >
              <el-option
                v-for="template in templates"
                :key="template.templateId"
                :label="`${template.title}（v${template.version}）`"
                :value="template.templateId"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="selectedTemplate" label="模板正文">
            <pre data-testid="template-content" class="consent__content">{{
              selectedTemplate.contentText
            }}</pre>
          </el-form-item>
          <el-form-item label="签署人">
            <el-input
              v-model="form.signerName"
              data-testid="signer-name"
              maxlength="64"
              placeholder="患者本人或法定代理人姓名"
            />
          </el-form-item>
          <el-form-item label="与患者关系">
            <el-select v-model="form.signerRelation" data-testid="signer-relation" :disabled="busy">
              <el-option
                v-for="option in RELATION_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="签署方式">
            <el-select v-model="form.signMethod" data-testid="signer-method" :disabled="busy">
              <el-option
                v-for="option in METHOD_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="create-consent"
              :disabled="busy"
              :loading="saving"
              @click="submitCreate"
            >
              创建同意记录
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <section class="consent__panel">
        <h2 class="consent__panel-title">同意记录（含历史）</h2>
        <p v-if="records.length === 0" class="consent__empty">暂无同意记录。</p>
        <ul v-else data-testid="consent-records" class="consent__records">
          <li v-for="record in records" :key="record.consentId" class="consent__record">
            <div class="consent__record-head">
              <strong>{{ record.templateTitle }}（v{{ record.templateVersion }}）</strong>
              <el-tag :type="tagType(record.status)">{{ statusLabel(record.status) }}</el-tag>
              <el-tag v-if="record.caStatus === 'SIMULATED'" type="warning" size="small">
                CA 占位
              </el-tag>
            </div>
            <dl class="consent__record-meta">
              <div>
                <dt>签署人</dt>
                <dd>{{ record.signerName }}（{{ relationLabel(record.signerRelation) }}）</dd>
              </div>
              <div>
                <dt>签署方式</dt>
                <dd>{{ methodLabel(record.signMethod) }}</dd>
              </div>
              <div>
                <dt>签署时间</dt>
                <dd>{{ record.signedAt ?? '未签署' }}</dd>
              </div>
              <div v-if="record.declineReason">
                <dt>拒绝原因</dt>
                <dd>{{ record.declineReason }}</dd>
              </div>
              <div v-if="record.revokeReason">
                <dt>撤销原因</dt>
                <dd>{{ record.revokeReason }}</dd>
              </div>
              <div>
                <dt>版本</dt>
                <dd>{{ record.version }}</dd>
              </div>
            </dl>
            <div v-if="canWrite" class="consent__record-actions">
              <el-button
                v-if="record.status === 'WAITING_SIGN'"
                type="primary"
                size="small"
                data-testid="sign-button"
                :disabled="busy"
                @click="submitSign(record)"
              >
                签署
              </el-button>
              <el-button
                v-if="record.status === 'WAITING_SIGN'"
                size="small"
                data-testid="decline-button"
                :disabled="busy"
                @click="submitDecline(record)"
              >
                拒绝
              </el-button>
              <el-button
                v-if="record.status === 'SIGNED'"
                type="danger"
                size="small"
                data-testid="revoke-button"
                :disabled="busy"
                @click="submitRevoke(record)"
              >
                撤销
              </el-button>
            </div>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.consent {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1080px;
}

.consent__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.consent__title {
  margin: 0;
  font-size: 22px;
}

.consent__subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
}

.consent__ca {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.consent__panel {
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.consent__panel-title {
  margin: 0 0 12px;
  font-size: 16px;
}

.consent__content {
  max-height: 220px;
  margin: 0;
  padding: 8px 12px;
  overflow: auto;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.consent__empty {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.consent__records {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.consent__record {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.consent__record-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.consent__record-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin: 8px 0 0;
  font-size: 13px;
}

.consent__record-meta div {
  display: flex;
  gap: 6px;
}

.consent__record-meta dt {
  color: var(--el-text-color-secondary);
}

.consent__record-meta dd {
  margin: 0;
}

.consent__record-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
</style>
