<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../../api/http'
import { safeRedirect } from '../../../router/redirect'
import { useAuthStore } from '../../../stores/auth'

interface LoginForm {
  username: string
  password: string
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive<LoginForm>({ username: '', password: '' })
const loading = ref(false)
const error = ref<ApiError | null>(null)

const rules: FormRules = {
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    { max: 64, message: '登录账号不能超过 64 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入口令', trigger: 'blur' },
    { max: 128, message: '口令不能超过 128 个字符', trigger: 'blur' },
  ],
}

const notice = computed(() => {
  if (route.query.reason === 'session-expired') {
    return { type: 'warning' as const, text: '会话已过期，请重新登录。' }
  }
  if (route.query.reason === 'password-changed') {
    return { type: 'success' as const, text: '口令已修改，请使用新口令重新登录。' }
  }
  return null
})

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '登录失败，请稍后重试',
  })
}

async function onSubmit(): Promise<void> {
  if (loading.value) return
  const instance = formRef.value
  if (!instance) return

  const valid = await instance.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  error.value = null
  try {
    const user = await auth.login(form.username.trim(), form.password)
    if (user.mustChangePassword) {
      ElMessage.warning('首次登录需先修改初始口令')
      await router.replace({ name: 'm0-password', query: { forced: '1' } })
      return
    }
    ElMessage.success('登录成功')
    await router.replace(safeRedirect(route.query.redirect) ?? '/home')
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login">
    <section class="login__brand" aria-label="平台简介">
      <div class="login__brand-inner">
        <p class="login__brand-mark">ANESOP</p>
        <h1 class="login__brand-title">麻醉与围术期<br />全流程信息平台</h1>
        <p class="login__brand-desc">
          覆盖术前访视、术中记录、术后随访与质控统计。一次登录、按角色授权、全程留痕。
        </p>
        <ul class="login__features">
          <li>统一认证与 RBAC 权限点，菜单按权限动态生成</li>
          <li>访问令牌自动刷新，401 单飞续期后重放原请求</li>
          <li>错误统一携带 code / message / traceId，便于排障</li>
        </ul>
        <p class="login__brand-foot">院内单院部署 · 患者数据不出院</p>
      </div>
    </section>

    <section class="login__panel">
      <div class="login__card">
        <header class="login__header">
          <p class="login__eyebrow">ANESOP 麻醉信息平台</p>
          <h2 class="login__title">账号登录</h2>
          <p class="login__subtitle">请使用院内分配的账号登录；首次登录需修改初始口令。</p>
        </header>

        <el-alert
          v-if="notice"
          class="login__notice"
          :type="notice.type"
          :title="notice.text"
          :closable="false"
          show-icon
        />

        <el-alert
          v-if="error"
          class="login__notice"
          type="error"
          :closable="false"
          show-icon
          title="登录失败"
        >
          <p class="login__error-message">{{ error.message }}</p>
          <p class="login__error-meta">
            错误码：{{ error.code }}
            <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
          </p>
          <ul v-if="error.details.length > 0" class="login__error-details">
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
          class="login__form"
          @submit.prevent="onSubmit"
        >
          <el-form-item label="账号" prop="username">
            <el-input
              v-model="form.username"
              type="text"
              size="large"
              placeholder="工号或域账号"
              autocomplete="username"
              :disabled="loading"
              clearable
            />
          </el-form-item>

          <el-form-item label="口令" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              placeholder="请输入口令"
              autocomplete="current-password"
              :disabled="loading"
              show-password
            />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            native-type="submit"
            class="login__submit"
            :loading="loading"
          >
            {{ loading ? '正在登录…' : '登录' }}
          </el-button>
        </el-form>

        <p class="login__hint">
          账号锁定、口令重置与权限申请请联系信息科管理员。登录失败不区分账号不存在与口令错误。
        </p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(420px, 0.9fr);
  min-height: 100vh;
  background: var(--anesop-color-bg-page);
}

.login__brand {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--anesop-space-8);
  color: var(--anesop-color-text-inverse);
  background: var(--anesop-gradient-brand);
}

.login__brand-inner {
  width: 100%;
  max-width: 560px;
}

.login__brand-mark {
  display: inline-flex;
  align-items: center;
  height: 32px;
  margin: 0 0 var(--anesop-space-6);
  padding: 0 var(--anesop-space-3);
  color: var(--anesop-color-brand-800);
  font-size: var(--anesop-font-size-sm);
  font-weight: var(--anesop-font-weight-bold);
  letter-spacing: 0.12em;
  background: var(--anesop-color-brand-50);
  border-radius: var(--anesop-radius-full);
}

.login__brand-title {
  margin: 0;
  font-size: var(--anesop-font-size-3xl);
  font-weight: var(--anesop-font-weight-bold);
  line-height: var(--anesop-line-height-tight);
}

.login__brand-desc {
  max-width: 480px;
  margin: var(--anesop-space-4) 0 0;
  color: var(--anesop-color-brand-50);
  font-size: var(--anesop-font-size-base);
  line-height: var(--anesop-line-height-relaxed);
}

.login__features {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-3);
  margin: var(--anesop-space-6) 0 0;
  padding: 0;
  list-style: none;
}

.login__features li {
  position: relative;
  padding-left: var(--anesop-space-6);
  color: var(--anesop-color-brand-100);
  font-size: var(--anesop-font-size-sm);
}

.login__features li::before {
  position: absolute;
  top: 0.55em;
  left: 0;
  width: var(--anesop-space-2);
  height: var(--anesop-space-2);
  content: '';
  background: var(--anesop-color-brand-200);
  border-radius: var(--anesop-radius-full);
}

.login__brand-foot {
  margin: var(--anesop-space-8) 0 0;
  color: var(--anesop-color-brand-200);
  font-size: var(--anesop-font-size-xs);
}

.login__panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--anesop-space-8);
}

.login__card {
  width: 100%;
  max-width: 420px;
  padding: var(--anesop-space-8);
  background: var(--anesop-color-bg-card);
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-xl);
  box-shadow: var(--anesop-shadow-md);
}

.login__header {
  margin-bottom: var(--anesop-space-4);
}

.login__eyebrow {
  margin: 0 0 var(--anesop-space-1);
  color: var(--anesop-color-primary);
  font-size: var(--anesop-font-size-xs);
  font-weight: var(--anesop-font-weight-semibold);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.login__title {
  margin: 0;
  font-size: var(--anesop-font-size-2xl);
  font-weight: var(--anesop-font-weight-semibold);
  line-height: var(--anesop-line-height-tight);
}

.login__subtitle {
  margin: var(--anesop-space-2) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
  line-height: var(--anesop-line-height-base);
}

.login__notice {
  margin-bottom: var(--anesop-space-4);
}

.login__error-message {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.login__error-meta {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}

.login__error-details {
  margin: var(--anesop-space-1) 0 0;
  padding-left: var(--anesop-space-4);
  font-size: var(--anesop-font-size-xs);
}

.login__form :deep(.el-form-item__label) {
  color: var(--anesop-color-text-regular);
  font-weight: var(--anesop-font-weight-medium);
}

.login__submit {
  width: 100%;
  margin-top: var(--anesop-space-2);
}

.login__hint {
  margin: var(--anesop-space-4) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
  line-height: var(--anesop-line-height-relaxed);
}

@media (max-width: 1024px) {
  .login {
    grid-template-columns: minmax(0, 1fr);
  }

  .login__brand {
    display: none;
  }

  .login__panel {
    padding: var(--anesop-space-4);
  }
}
</style>
