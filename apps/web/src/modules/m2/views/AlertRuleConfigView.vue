<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  alertMetricLabels,
  alertSeverityLabels,
  listAlertRules,
  updateAlertRule,
  type AlertRule,
  type UpdateAlertRuleRequest,
} from '../api/m2'

/**
 * 预警规则配置（仅 m2:alert:configure，V15 仅授予 SYS_ADMIN）。
 *
 * 规则不可原地修改：保存 = 生成 version+1 新版本并停用旧版本（历史保留可追溯）。
 * 阈值口径【待确认】：LT/GT/OUT_OF_RANGE 的阈值由临床顾问确认后录入，
 * 页面显式标注，不把演示默认值当成临床决策依据。阈值/持续时长变更会置
 * pendingConfirmation=true（临床待确认），名称可随新版本更新。
 */

const auth = useAuthStore()

const rules = ref<AlertRule[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const dialogVisible = ref(false)

const form = reactive({
  ruleId: '',
  code: '',
  name: '',
  operator: 'LT' as string,
  thresholdLow: 0,
  thresholdHigh: 0,
  durationSeconds: 0,
  severity: 'WARN' as string,
  enabled: true,
  version: 1,
})

const canConfigure = computed(() => auth.hasPermission('m2:alert:configure'))
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权配置预警规则（需要 m2:alert:configure）'
  if (error.value.status === 409) return '规则已被他人更新，请刷新后重试'
  return '预警规则操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const list = await listAlertRules()
    rules.value = list.items
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

function openEdit(rule: AlertRule): void {
  form.ruleId = rule.ruleId
  form.code = rule.code
  form.name = rule.name
  form.operator = rule.operator
  form.thresholdLow = rule.thresholdLow ?? 0
  form.thresholdHigh = rule.thresholdHigh ?? 0
  form.durationSeconds = rule.durationSeconds
  form.severity = rule.severity
  form.enabled = rule.enabled
  form.version = rule.version
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (form.durationSeconds < 0 || form.durationSeconds > 3600) {
    ElMessage.warning('持续时长必须在 0–3600 秒之间')
    return
  }
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('规则名称不能为空')
    return
  }
  if (form.operator === 'LT' && (form.thresholdLow < 0 || form.thresholdLow > 300)) {
    // 具体指标范围由服务端校验（400）；前端仅做粗粒度提示。
    ElMessage.warning('阈值明显越界，请按指标范围填写（服务端会再次校验）')
    return
  }
  const payload: UpdateAlertRuleRequest = {
    name,
    durationSeconds: form.durationSeconds,
    severity: form.severity as UpdateAlertRuleRequest['severity'],
    enabled: form.enabled,
    version: form.version,
    ...(form.operator === 'LT'
      ? { thresholdLow: form.thresholdLow }
      : form.operator === 'GT'
        ? { thresholdHigh: form.thresholdHigh }
        : { thresholdLow: form.thresholdLow, thresholdHigh: form.thresholdHigh }),
  }
  saving.value = true
  try {
    await updateAlertRule(form.ruleId, payload)
    ElMessage.success('规则已保存为新版本（旧版本停用并保留）')
    dialogVisible.value = false
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function thresholdText(rule: AlertRule): string {
  if (rule.operator === 'LT') return `< ${rule.thresholdLow ?? '—'}`
  if (rule.operator === 'GT') return `> ${rule.thresholdHigh ?? '—'}`
  return `${rule.thresholdLow ?? '—'} – ${rule.thresholdHigh ?? '—'}`
}

onMounted(load)
</script>

<template>
  <section class="rules">
    <header class="rules__header">
      <div>
        <h1 class="rules__title">术中预警规则</h1>
        <p class="rules__subtitle">
          规则版本化：保存即生成新版本并停用旧版本，历史版本保留供审计追溯。
          默认阈值均为【待确认】演示口径，必须经临床顾问确认后由管理员录入；
          阈值/持续时长变更会标记“待确认”（临床复核由 M5 权限细化承接）；告警只做提示，
          不替代临床判断。
        </p>
      </div>
      <el-button data-testid="rules-refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="error"
      data-testid="rules-error"
      type="error"
      :closable="false"
      show-icon
      :title="errorTitle"
      :description="`${error.code}：${error.message}${error.traceId ? `（traceId：${error.traceId}）` : ''}`"
    />

    <p v-if="!canConfigure" data-testid="rules-no-permission" class="rules__hint">
      当前角色无 m2:alert:configure，仅可查看规则（本页面面向信息科管理员）。
    </p>

    <el-table v-loading="loading" :data="rules" data-testid="rules-table" size="small" border>
      <el-table-column label="规则编码" prop="code" width="150" />
      <el-table-column label="名称" prop="name" />
      <el-table-column label="指标" width="150">
        <template #default="{ row }">{{ alertMetricLabels[row.metric] ?? row.metric }}</template>
      </el-table-column>
      <el-table-column label="算子" prop="operator" width="130" />
      <el-table-column label="阈值" width="140">
        <template #default="{ row }">{{ thresholdText(row) }}</template>
      </el-table-column>
      <el-table-column label="持续（秒）" prop="durationSeconds" width="100" />
      <el-table-column label="严重度" width="110">
        <template #default="{ row }">
          {{ alertSeverityLabels[row.severity] ?? row.severity }}
        </template>
      </el-table-column>
      <el-table-column label="版本" width="80">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="临床确认" width="120">
        <template #default="{ row }">
          <el-tag
            :type="row.pendingConfirmation ? 'warning' : 'success'"
            size="small"
            :data-testid="`rule-pending-${row.code}`"
          >
            {{ row.pendingConfirmation ? '待确认' : '已确认' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button
            v-if="canConfigure"
            link
            type="primary"
            :data-testid="`rule-edit-${row.code}`"
            @click="openEdit(row)"
          >
            配置
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="配置预警规则（生成新版本）" width="520px">
      <el-form label-width="110px" @submit.prevent>
        <el-form-item label="规则">
          <span data-testid="rule-dialog-code">{{ form.code }}（当前 v{{ form.version }}）</span>
        </el-form-item>
        <el-form-item label="规则名称">
          <el-input v-model="form.name" data-testid="rule-name" maxlength="200" />
        </el-form-item>
        <el-form-item v-if="form.operator !== 'GT'" label="下限阈值">
          <el-input-number v-model="form.thresholdLow" data-testid="rule-threshold-low" :step="1" />
        </el-form-item>
        <el-form-item v-if="form.operator !== 'LT'" label="上限阈值">
          <el-input-number
            v-model="form.thresholdHigh"
            data-testid="rule-threshold-high"
            :step="1"
          />
        </el-form-item>
        <el-form-item label="持续（秒）">
          <el-input-number
            v-model="form.durationSeconds"
            data-testid="rule-duration"
            :min="0"
            :max="3600"
          />
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="form.severity" data-testid="rule-severity">
            <el-option label="提示" value="INFO" />
            <el-option label="警告" value="WARN" />
            <el-option label="严重" value="CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" data-testid="rule-enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="rule-cancel" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" data-testid="rule-save" :loading="saving" @click="submit">
          保存为新版本
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.rules {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1120px;
}

.rules__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.rules__title {
  margin: 0 0 6px;
  font-size: 22px;
}

.rules__subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

.rules__hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
