<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  authorizationLevelLabels,
  checkStaffAuthorization,
  getStaffAuthorizations,
  listStaff,
  updateStaffAuthorizations,
  type AuthorizationCheckResult,
  type Staff,
  type StaffAuthorizationHistory,
  type UpdateStaffAuthorizationRequest,
} from '../api/staff'

/**
 * 分级授权（M4-M13-01，契约 staff.yaml 0.1.0）。
 *
 * - 授权历史 append-only：每次授予/撤销写入新版本（version 递增），撤销也写入新版本；
 * - 级别 R1–R5 的业务含义与默认映射【待确认】：范围以显式字段（术式/年龄段/ASA/有效期）为权威，
 *   系统不内置臆造映射；如院方配置级别策略，服务端会拒绝超限范围；
 * - 授权校验只做规则判定，不替代临床决策（AGENTS H-06）。
 */

const auth = useAuthStore()

const staff = ref<Staff[]>([])
const selectedStaffId = ref('')
const history = ref<StaffAuthorizationHistory | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const checkResult = ref<AuthorizationCheckResult | null>(null)

type CheckDimension = 'SURGERY_TYPE' | 'AGE' | 'ASA'

function emptyForm() {
  return {
    level: 'R3' as 'R1' | 'R2' | 'R3' | 'R4' | 'R5',
    scopesText: '',
    ageMin: 18,
    ageMax: 90,
    asaMax: 'ASA3' as 'ASA1' | 'ASA2' | 'ASA3' | 'ASA4' | 'ASA5',
    validFrom: '',
    validTo: '',
    changeReason: '',
    approverId: '',
    secondApproverId: '',
    secondApproverReason: '',
  }
}

const form = reactive(emptyForm())

const checkForm = reactive({
  surgeryTypeId: 'GENERAL',
  age: 60 as number | null,
  asa: 'ASA2' as 'ASA1' | 'ASA2' | 'ASA3' | 'ASA4' | 'ASA5',
  notApplicable: [] as CheckDimension[],
})

function parseScopes(): string[] {
  return form.scopesText
    .split(/[，,]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

const dualRequired = computed(
  () =>
    form.level === 'R1' ||
    form.level === 'R2' ||
    parseScopes().includes('*') ||
    form.asaMax === 'ASA4' ||
    form.asaMax === 'ASA5',
)

const revokeDualRequired = computed(() => {
  const latest = current.value
  if (!latest) return false
  return (
    latest.level === 'R1' ||
    latest.level === 'R2' ||
    latest.surgeryScopes.includes('*') ||
    latest.asaMax === 'ASA4' ||
    latest.asaMax === 'ASA5'
  )
})

function resetForm(): void {
  Object.assign(form, emptyForm())
}

const canWrite = computed(() => auth.hasPermission('m4:staff:write'))
const current = computed(() => history.value?.current ?? null)
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403)
    return '无权维护分级授权（m4:staff:write）、自授/自审被拒或审批人无审批权限'
  if (error.value.status === 409) return '授权已被他人变更（版本过期）或已撤销，请刷新后重试'
  if (error.value.status === 404) return '人员档案不存在或超出数据范围'
  if (error.value.status === 422) return '业务规则拒绝（人员离职或范围超出级别策略）'
  if (error.value.status === 400) return '授权范围/审批人字段校验失败（见下发明细）'
  return '分级授权操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function loadStaff(): Promise<void> {
  loading.value = true
  try {
    const list = await listStaff({ size: 200 })
    staff.value = list.items
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

async function loadHistory(): Promise<void> {
  // S-7：切换人员先重置表单，避免上一个人的范围/审批人泄漏到当前人员。
  resetForm()
  checkResult.value = null
  if (!selectedStaffId.value) {
    history.value = null
    return
  }
  error.value = null
  try {
    history.value = await getStaffAuthorizations(selectedStaffId.value)
    const latest = history.value.current
    if (latest) {
      form.level = latest.level
      form.scopesText = latest.surgeryScopes.join('，')
      form.ageMin = latest.ageMin
      form.ageMax = latest.ageMax
      form.asaMax = latest.asaMax
      form.validFrom = latest.validFrom
      form.validTo = latest.validTo
    }
  } catch (cause) {
    handleError(cause)
  }
}

async function grant(): Promise<void> {
  if (!selectedStaffId.value) {
    ElMessage.warning('请先选择人员')
    return
  }
  const scopes = parseScopes()
  if (!form.approverId.trim()) {
    ElMessage.warning('请填写第一审批人用户标识（不能是操作者本人）')
    return
  }
  if (dualRequired.value && !form.secondApproverId.trim()) {
    ElMessage.warning('该级别/范围需双人审批，请填写第二审批人')
    return
  }
  if (dualRequired.value && form.secondApproverId.trim() === form.approverId.trim()) {
    ElMessage.warning('第二审批人必须与第一审批人不同')
    return
  }
  saving.value = true
  error.value = null
  try {
    const payload: UpdateStaffAuthorizationRequest = {
      action: 'GRANT',
      version: current.value?.version ?? 0,
      level: form.level,
      surgeryScopes: scopes,
      ageMin: form.ageMin,
      ageMax: form.ageMax,
      asaMax: form.asaMax,
      validFrom: form.validFrom || undefined,
      validTo: form.validTo || undefined,
      approvedBy: form.approverId.trim(),
      secondApproverId: form.secondApproverId.trim() || undefined,
      secondApproverReason: form.secondApproverReason.trim() || undefined,
      changeReason: form.changeReason.trim() || undefined,
    }
    history.value = await updateStaffAuthorizations(selectedStaffId.value, payload)
    checkResult.value = null
    ElMessage.success('授权已写入新版本（历史保留）')
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function revoke(): Promise<void> {
  if (!selectedStaffId.value || !current.value) {
    ElMessage.warning('当前无授权可撤销')
    return
  }
  if (!form.changeReason.trim()) {
    ElMessage.warning('撤销授权必须填写原因')
    return
  }
  if (!form.approverId.trim()) {
    ElMessage.warning('请填写第一审批人用户标识（不能是操作者本人）')
    return
  }
  if (revokeDualRequired.value && !form.secondApproverId.trim()) {
    ElMessage.warning('该级别/范围需双人审批，撤销也必须填写第二审批人')
    return
  }
  if (revokeDualRequired.value && form.secondApproverId.trim() === form.approverId.trim()) {
    ElMessage.warning('第二审批人必须与第一审批人不同')
    return
  }
  saving.value = true
  error.value = null
  try {
    history.value = await updateStaffAuthorizations(selectedStaffId.value, {
      action: 'REVOKE',
      version: current.value.version,
      approvedBy: form.approverId.trim(),
      secondApproverId: form.secondApproverId.trim() || undefined,
      secondApproverReason: form.secondApproverReason.trim() || undefined,
      changeReason: form.changeReason.trim(),
    })
    checkResult.value = null
    ElMessage.success('授权已撤销（新版本 REVOKED，历史保留）')
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function runCheck(): Promise<void> {
  if (!selectedStaffId.value) {
    ElMessage.warning('请先选择人员')
    return
  }
  error.value = null
  try {
    checkResult.value = await checkStaffAuthorization({
      staffId: selectedStaffId.value,
      surgeryTypeId: checkForm.surgeryTypeId.trim() || undefined,
      age: checkForm.age === null ? undefined : checkForm.age,
      asa: checkForm.asa,
      notApplicable: checkForm.notApplicable.length ? [...checkForm.notApplicable] : undefined,
    })
  } catch (cause) {
    handleError(cause)
  }
}

onMounted(loadStaff)
</script>

<template>
  <section class="staff-authorization">
    <header class="page-header">
      <div>
        <h1>分级授权</h1>
        <p class="hint">
          授权历史只增不改：每次授予/撤销写入新版本；范围以术式/年龄段/ASA/有效期为准（R1–R5
          业务含义【待确认】）。授权须由具备 m4:staff:authorize 的他人审批；R1/R2、范围 * 或 ASA≥4
          须双人复核。
        </p>
      </div>
    </header>

    <el-alert
      v-if="error"
      type="error"
      :title="errorTitle"
      :description="`${error.message}${error.traceId ? `（traceId: ${error.traceId}）` : ''}`"
      show-icon
      :closable="false"
      data-testid="auth-error"
    />

    <div class="toolbar">
      <el-select
        v-model="selectedStaffId"
        data-testid="auth-staff-select"
        placeholder="选择人员"
        filterable
        style="width: 280px"
        @change="loadHistory"
      >
        <el-option
          v-for="item in staff"
          :key="item.staffId"
          :label="`${item.staffNo} ${item.name}`"
          :value="item.staffId"
        />
      </el-select>
      <el-button data-testid="auth-refresh" @click="loadHistory">刷新授权</el-button>
    </div>

    <el-card v-if="history" shadow="never" data-testid="auth-current">
      <template #header>当前授权</template>
      <p v-if="!current" data-testid="auth-empty">当前无分级授权</p>
      <el-descriptions v-else :column="4" border size="small">
        <el-descriptions-item label="级别">{{ current.level }}</el-descriptions-item>
        <el-descriptions-item label="范围">{{
          current.surgeryScopes.join('，')
        }}</el-descriptions-item>
        <el-descriptions-item label="年龄段">
          {{ current.ageMin }}–{{ current.ageMax }} 岁
        </el-descriptions-item>
        <el-descriptions-item label="ASA 上限">{{ current.asaMax }}</el-descriptions-item>
        <el-descriptions-item label="生效">{{ current.validFrom }}</el-descriptions-item>
        <el-descriptions-item label="失效">{{ current.validTo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ current.status }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ current.version }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="canWrite" shadow="never">
      <template #header>授予 / 复评（写入新版本）</template>
      <el-form label-width="100px" data-testid="auth-form">
        <el-form-item label="级别">
          <el-select v-model="form.level" data-testid="auth-level" style="width: 100%">
            <el-option
              v-for="(label, value) in authorizationLevelLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="术式范围">
          <el-input
            v-model="form.scopesText"
            data-testid="auth-scopes"
            placeholder="* 或术式编码，逗号分隔"
          />
        </el-form-item>
        <el-form-item label="年龄段">
          <div class="range">
            <el-input-number v-model="form.ageMin" :min="0" :max="120" data-testid="auth-age-min" />
            <span>至</span>
            <el-input-number v-model="form.ageMax" :min="1" :max="120" data-testid="auth-age-max" />
          </div>
        </el-form-item>
        <el-form-item label="ASA 上限">
          <el-select v-model="form.asaMax" data-testid="auth-asa" style="width: 100%">
            <el-option
              v-for="asa in ['ASA1', 'ASA2', 'ASA3', 'ASA4', 'ASA5']"
              :key="asa"
              :label="asa"
              :value="asa"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-input
            v-model="form.validFrom"
            data-testid="auth-valid-from"
            placeholder="YYYY-MM-DD"
          />
        </el-form-item>
        <el-form-item label="失效日期">
          <el-input v-model="form.validTo" data-testid="auth-valid-to" placeholder="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="第一审批人">
          <el-input
            v-model="form.approverId"
            data-testid="auth-approver"
            placeholder="审批人用户 ID（须具备 m4:staff:authorize，不能是操作者/被授权人）"
          />
        </el-form-item>
        <el-form-item v-if="dualRequired" label="第二审批人">
          <el-input
            v-model="form.secondApproverId"
            data-testid="auth-second-approver"
            placeholder="R1/R2、范围 * 或 ASA≥4 必须双人审批，且不能与第一审批人相同"
          />
        </el-form-item>
        <el-form-item v-if="dualRequired" label="复核意见">
          <el-input
            v-model="form.secondApproverReason"
            data-testid="auth-second-reason"
            placeholder="第二审批人复核意见（可选）"
          />
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input
            v-model="form.changeReason"
            data-testid="auth-reason"
            placeholder="撤销时必填"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="dualRequired"
        type="warning"
        :closable="false"
        show-icon
        title="双人审批：R1/R2、术式范围含 * 或 ASA≥4 时必须由两名不同审批人复核"
        data-testid="auth-dual-hint"
      />
      <div class="actions">
        <el-button type="primary" :loading="saving" data-testid="auth-grant" @click="grant">
          保存授权（新版本）
        </el-button>
        <el-button type="danger" :loading="saving" data-testid="auth-revoke" @click="revoke">
          撤销授权
        </el-button>
      </div>
    </el-card>

    <el-card v-if="history" shadow="never">
      <template #header>历史版本（append-only，{{ history.history.length }} 条）</template>
      <el-table :data="history.history" data-testid="auth-history" border size="small">
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="level" label="级别" width="70" />
        <el-table-column label="范围" min-width="140">
          <template #default="{ row }">{{ row.surgeryScopes.join('，') }}</template>
        </el-table-column>
        <el-table-column label="年龄段" width="110">
          <template #default="{ row }">{{ row.ageMin }}–{{ row.ageMax }}</template>
        </el-table-column>
        <el-table-column prop="asaMax" label="ASA" width="80" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="validTo" label="失效" width="110" />
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>授权校验（排程/术中集成点，仅规则判定）</template>
      <div class="toolbar">
        <el-input
          v-model="checkForm.surgeryTypeId"
          data-testid="auth-check-surgery"
          placeholder="术式编码"
          style="width: 160px"
        />
        <el-input-number v-model="checkForm.age" :min="0" :max="120" data-testid="auth-check-age" />
        <el-select v-model="checkForm.asa" data-testid="auth-check-asa" style="width: 110px">
          <el-option
            v-for="asa in ['ASA1', 'ASA2', 'ASA3', 'ASA4', 'ASA5']"
            :key="asa"
            :label="asa"
            :value="asa"
          />
        </el-select>
        <el-select
          v-model="checkForm.notApplicable"
          data-testid="auth-check-not-applicable"
          multiple
          collapse-tags
          placeholder="不适用维度（缺字段必选）"
          style="width: 220px"
        >
          <el-option label="术式不适用" value="SURGERY_TYPE" />
          <el-option label="年龄不适用" value="AGE" />
          <el-option label="ASA 不适用" value="ASA" />
        </el-select>
        <el-button data-testid="auth-check-run" @click="runCheck">校验</el-button>
      </div>
      <el-alert
        v-if="checkResult"
        data-testid="auth-check-result"
        :type="checkResult.allowed ? 'success' : 'error'"
        :title="checkResult.allowed ? '授权通过（仍须医师临床判断）' : '授权不通过'"
        :closable="false"
        show-icon
      />
      <ul
        v-if="checkResult && checkResult.reasons.length"
        data-testid="auth-check-reasons"
        class="reasons"
      >
        <li v-for="reason in checkResult.reasons" :key="reason.code">
          {{ reason.code }}：{{ reason.message }}
        </li>
      </ul>
    </el-card>
  </section>
</template>

<style scoped>
.staff-authorization {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.range {
  display: flex;
  align-items: center;
  gap: 8px;
}
.reasons {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--el-color-danger);
  font-size: 13px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0;
}
</style>
