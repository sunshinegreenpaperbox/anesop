<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { changePassword } from '../../../api/auth'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'

interface ChangePasswordForm {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive<ChangePasswordForm>({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const submitting = ref(false)
const error = ref<ApiError | null>(null)

const forced = computed(() => route.query.forced === '1' || auth.mustChangePassword)

/** 契约要求：12–128 字符，大写/小写/数字/符号四类中至少三类。 */
function passwordRuleMessage(value: string): string | null {
  if (value.length < 12 || value.length > 128) return '新口令长度需为 12–128 个字符'
  const categories = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((pattern) =>
    pattern.test(value),
  ).length
  if (categories < 3) return '新口令需包含大写、小写、数字、符号中的至少三类'
  if (value === form.oldPassword) return '新口令不能与旧口令相同'
  if (auth.username && value.toLowerCase().includes(auth.username.toLowerCase())) {
    return '新口令不能包含登录账号'
  }
  return null
}

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前口令', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新口令', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const message = passwordRuleMessage(value)
        callback(message ? new Error(message) : undefined)
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新口令', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        callback(value === form.newPassword ? undefined : new Error('两次输入的新口令不一致'))
      },
      trigger: 'blur',
    },
  ],
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '修改口令失败，请稍后重试',
  })
}

async function onSubmit(): Promise<void> {
  if (submitting.value) return
  const instance = formRef.value
  if (!instance) return
  const valid = await instance.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  error.value = null
  try {
    await changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    ElMessage.success('口令已修改，请使用新口令重新登录')
    // 服务端改密后旧令牌仍带 mustChangePassword 快照，按契约要求重新登录。
    auth.clear()
    await router.replace({ name: 'login', query: { reason: 'password-changed' } })
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    submitting.value = false
  }
}

async function backToHome(): Promise<void> {
  await router.replace('/home')
}
</script>

<template>
  <section class="password">
    <el-card class="password__card" shadow="never">
      <template #header>
        <div class="password__header">
          <h1 class="password__title">修改口令</h1>
          <el-tag v-if="forced" type="warning" effect="plain">首次登录必须修改</el-tag>
        </div>
      </template>

      <el-alert
        v-if="forced"
        class="password__notice"
        type="warning"
        :closable="false"
        show-icon
        title="当前为初始口令"
        description="修改成功前，服务端仅允许访问当前用户、登出与改密接口；修改后将要求重新登录。"
      />

      <el-alert
        v-if="error"
        class="password__notice"
        type="error"
        :closable="false"
        show-icon
        title="修改口令失败"
      >
        <p class="password__error-message">{{ error.message }}</p>
        <p class="password__error-meta">
          错误码：{{ error.code }}
          <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
        </p>
        <ul v-if="error.details.length > 0" class="password__error-details">
          <li v-for="detail in error.details" :key="`${detail.field}-${detail.reason}`">
            {{ detail.field }}：{{ detail.reason }}
          </li>
        </ul>
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="password__form"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="当前口令" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            size="large"
            autocomplete="current-password"
            show-password
            :disabled="submitting"
          />
        </el-form-item>

        <el-form-item label="新口令" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            size="large"
            autocomplete="new-password"
            show-password
            :disabled="submitting"
          />
          <p class="password__hint">12–128 个字符，大写、小写、数字、符号四类中至少三类。</p>
        </el-form-item>

        <el-form-item label="确认新口令" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            size="large"
            autocomplete="new-password"
            show-password
            :disabled="submitting"
          />
        </el-form-item>

        <div class="password__actions">
          <el-button type="primary" size="large" native-type="submit" :loading="submitting">
            {{ submitting ? '正在提交…' : '确认修改' }}
          </el-button>
          <el-button v-if="!forced" size="large" @click="backToHome">返回首页</el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>

<style scoped>
.password {
  display: flex;
  justify-content: center;
}

.password__card {
  width: 100%;
  max-width: 560px;
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.password__card :deep(.el-card__header) {
  padding: var(--anesop-space-3) var(--anesop-space-4);
  border-bottom-color: var(--anesop-color-border-light);
}

.password__card :deep(.el-card__body) {
  padding: var(--anesop-space-4);
}

.password__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--anesop-space-2);
}

.password__title {
  margin: 0;
  font-size: var(--anesop-font-size-lg);
  font-weight: var(--anesop-font-weight-semibold);
}

.password__notice {
  margin-bottom: var(--anesop-space-4);
}

.password__error-message {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.password__error-meta {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}

.password__error-details {
  margin: var(--anesop-space-1) 0 0;
  padding-left: var(--anesop-space-4);
  font-size: var(--anesop-font-size-xs);
}

.password__hint {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
  line-height: var(--anesop-line-height-base);
}

.password__actions {
  display: flex;
  gap: var(--anesop-space-2);
  margin-top: var(--anesop-space-2);
}
</style>
