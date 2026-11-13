<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  listThreeWayChecks,
  recordThreeWayCheck,
  type AnesthesiaApplicationSummary,
  type ApplicationThreeWayCheck,
  type RecordApplicationThreeWayCheckRequest,
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

const route = useRoute()
const auth = useAuthStore()

const applicationId = computed(() => {
  const value = route.params.applicationId
  const raw = Array.isArray(value) ? value[0] : value
  return raw ?? ''
})

const application = ref<AnesthesiaApplicationSummary | null>(null)
const checks = ref<ApplicationThreeWayCheck[]>([])
const completed = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const timeOutPrefilled = ref(false)

const form = reactive({
  timeOutAt: nowWithOffset(),
  signOutEnabled: false,
  signOutAt: nowWithOffset(),
  specimenChecked: false,
  implantChecked: false,
  issuesText: '',
})

const canWrite = computed(() => auth.hasPermission('m2:threeway:write'))
const busy = computed(() => loading.value || saving.value)
const latest = computed<ApplicationThreeWayCheck | null>(() => checks.value[0] ?? null)
const nextVersion = computed(() => (latest.value?.version ?? 0) + 1)
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权访问三方核查（需要 m2:threeway:read）'
  if (error.value.status === 404) return '手术申请不存在或超出数据范围'
  return '三方核查操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  if (cause instanceof ApiError) {
    ElMessage.error(cause.message)
  }
}

function parseIssues(): string[] {
  return form.issuesText
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await listThreeWayChecks(applicationId.value)
    application.value = result.application
    checks.value = result.items
    completed.value = result.completed
    // S-1：新增版本默认沿用上一版本 Time-out，避免无意改动已完成核查的时序。
    const latestCheck = result.items[0]
    if (!timeOutPrefilled.value && latestCheck?.timeOutAt) {
      form.timeOutAt = latestCheck.timeOutAt
      timeOutPrefilled.value = true
    }
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

function sameInstant(left: string, right: string): boolean {
  const leftMillis = new Date(left).getTime()
  const rightMillis = new Date(right).getTime()
  return Number.isFinite(leftMillis) && Number.isFinite(rightMillis)
    ? leftMillis === rightMillis
    : left === right
}

async function submit(): Promise<void> {
  if (!form.timeOutAt) {
    ElMessage.warning('请填写 Time-out 时间')
    return
  }
  if (form.signOutEnabled && !form.signOutAt) {
    ElMessage.warning('请填写 Sign-out 时间')
    return
  }
  // S-5：标本/植入物必须逐项显式勾选确认，不能以默认 false 直接提交。
  if (!form.specimenChecked || !form.implantChecked) {
    ElMessage.warning('请逐项勾选确认“标本核对一致”与“植入物/耗材核对一致”后再提交')
    return
  }

  const previousTimeOut = latest.value?.timeOutAt
  const timeOutChanged = Boolean(previousTimeOut && !sameInstant(previousTimeOut, form.timeOutAt))
  if (timeOutChanged) {
    try {
      await ElMessageBox.confirm(
        `Time-out 时间与上一版本（${previousTimeOut}）不一致，确认修改并新增版本？`,
        'Time-out 变更确认',
        { confirmButtonText: '确认修改', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }

  const summary = [
    `核查版本 v${nextVersion.value}`,
    `标本核对：一致`,
    `植入物/耗材核对：一致`,
    form.signOutEnabled ? '包含 Sign-out' : '仅 Time-out',
  ].join('；')
  try {
    await ElMessageBox.confirm(`${summary}。提交后 append-only，不可修改，确认提交？`, '二次确认', {
      confirmButtonText: '确认提交',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  const payload: RecordApplicationThreeWayCheckRequest = {
    timeOutAt: form.timeOutAt,
    specimenChecked: form.specimenChecked,
    implantChecked: form.implantChecked,
    issues: parseIssues(),
  }
  if (timeOutChanged) {
    payload.confirmTimeOutChange = true
  }
  if (form.signOutEnabled) {
    payload.signOutAt = form.signOutAt
  }
  saving.value = true
  error.value = null
  try {
    await recordThreeWayCheck(applicationId.value, payload)
    ElMessage.success(`已新增核查版本 v${nextVersion.value}`)
    form.issuesText = ''
    form.signOutEnabled = false
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
  <section class="threeway">
    <header class="threeway__header">
      <div>
        <h1 class="threeway__title">三方核查（Time-out / Sign-out）</h1>
        <p class="threeway__subtitle">
          append-only：每次录入新增版本，历史不可修改；最新版本同时记录 Time-out 与 Sign-out
          才算完成（M3 锁单前置）。
        </p>
      </div>
      <el-button data-testid="threeway-refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="error"
      data-testid="threeway-error"
      class="threeway__error"
      type="error"
      :closable="false"
      show-icon
      :title="errorTitle"
      :description="`${error.code}：${error.message}${error.traceId ? `（traceId：${error.traceId}）` : ''}`"
    />

    <el-skeleton v-if="loading && !application" :rows="3" animated />

    <template v-else-if="application">
      <el-descriptions
        data-testid="application-context"
        class="threeway__context"
        :column="3"
        border
      >
        <el-descriptions-item label="患者">
          {{ application.patientSnapshot.patientName }}（{{
            application.patientSnapshot.hisPatientNo
          }}）
        </el-descriptions-item>
        <el-descriptions-item label="术式">{{ application.surgeryName }}</el-descriptions-item>
        <el-descriptions-item label="申请科室">{{
          application.departmentName
        }}</el-descriptions-item>
        <el-descriptions-item label="申请状态">{{ application.status }}</el-descriptions-item>
        <el-descriptions-item label="核查完成">
          <el-tag data-testid="threeway-completed" :type="completed ? 'success' : 'warning'">
            {{ completed ? '已完成' : '未完成' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="最新版本">{{ latest?.version ?? 0 }}</el-descriptions-item>
      </el-descriptions>

      <section v-if="canWrite" class="threeway__panel">
        <h2 class="threeway__panel-title">录入核查（新增 v{{ nextVersion }}）</h2>
        <el-form label-width="140px" @submit.prevent>
          <el-form-item label="Time-out 时间">
            <el-date-picker
              v-model="form.timeOutAt"
              data-testid="timeout-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              placeholder="切皮前 Time-out 完成时间"
              :disabled="busy"
            />
            <span
              v-if="latest?.timeOutAt"
              data-testid="timeout-prefill-note"
              class="threeway__hint"
            >
              默认沿用上一版本 Time-out（{{ latest.timeOutAt }}）；修改需二次确认
            </span>
          </el-form-item>
          <el-form-item label="标本核对确认">
            <el-checkbox
              v-model="form.specimenChecked"
              data-testid="specimen-checked"
              :disabled="busy"
            >
              已核对标本并确认一致（必选）
            </el-checkbox>
          </el-form-item>
          <el-form-item label="植入物核对确认">
            <el-checkbox
              v-model="form.implantChecked"
              data-testid="implant-checked"
              :disabled="busy"
            >
              已核对植入物/耗材并确认一致（必选）
            </el-checkbox>
          </el-form-item>
          <el-form-item label="已完成 Sign-out">
            <el-switch
              v-model="form.signOutEnabled"
              data-testid="signout-enabled"
              :disabled="busy"
            />
          </el-form-item>
          <el-form-item v-if="form.signOutEnabled" label="Sign-out 时间">
            <el-date-picker
              v-model="form.signOutAt"
              data-testid="signout-at"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              placeholder="出室前 Sign-out 完成时间"
              :disabled="busy"
            />
          </el-form-item>
          <el-form-item label="问题项">
            <el-input
              v-model="form.issuesText"
              data-testid="issues"
              type="textarea"
              :rows="3"
              maxlength="2000"
              placeholder="每行一条问题项（最多 20 条，每条 ≤200 字）；无问题可留空"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              data-testid="record-check"
              :disabled="busy"
              :loading="saving"
              @click="submit"
            >
              录入核查版本
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <section class="threeway__panel">
        <h2 class="threeway__panel-title">核查版本（append-only）</h2>
        <p v-if="checks.length === 0" data-testid="threeway-empty" class="threeway__empty">
          暂无核查记录，请先录入 Time-out。
        </p>
        <ul v-else data-testid="threeway-versions" class="threeway__versions">
          <li v-for="check in checks" :key="check.checkId" class="threeway__version">
            <div class="threeway__version-head">
              <strong>v{{ check.version }}</strong>
              <el-tag :type="check.signOutAt ? 'success' : 'warning'" size="small">
                {{ check.signOutAt ? '含 Sign-out' : '仅 Time-out' }}
              </el-tag>
            </div>
            <dl class="threeway__version-meta">
              <div>
                <dt>Time-out</dt>
                <dd>{{ check.timeOutAt }}</dd>
              </div>
              <div>
                <dt>Sign-out</dt>
                <dd>{{ check.signOutAt ?? '未完成' }}</dd>
              </div>
              <div>
                <dt>标本</dt>
                <dd>{{ check.specimenChecked ? '一致' : '不一致' }}</dd>
              </div>
              <div>
                <dt>植入物</dt>
                <dd>{{ check.implantChecked ? '一致' : '不一致' }}</dd>
              </div>
              <div>
                <dt>问题项</dt>
                <dd>{{ check.issues.length }} 条</dd>
              </div>
            </dl>
            <ul v-if="check.issues.length > 0" class="threeway__issues">
              <li v-for="(issue, index) in check.issues" :key="index">{{ issue }}</li>
            </ul>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.threeway {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1080px;
}

.threeway__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.threeway__title {
  margin: 0;
  font-size: 22px;
}

.threeway__subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
}

.threeway__panel {
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.threeway__panel-title {
  margin: 0 0 12px;
  font-size: 16px;
}

.threeway__hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.threeway__empty {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.threeway__versions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.threeway__version {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.threeway__version-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.threeway__version-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin: 8px 0 0;
  font-size: 13px;
}

.threeway__version-meta div {
  display: flex;
  gap: 6px;
}

.threeway__version-meta dt {
  color: var(--el-text-color-secondary);
}

.threeway__version-meta dd {
  margin: 0;
}

.threeway__issues {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}
</style>
