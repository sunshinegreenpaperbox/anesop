<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { type Session, type SessionPage, listSessions } from '../../../api/auth'
import { ApiError } from '../../../api/http'

const sessions = ref<Session[]>([])
const meta = ref<SessionPage['meta']>({ page: 1, size: 10, total: 0, totalPages: 0 })
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const error = ref<ApiError | null>(null)

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

function formatTime(value: string | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : timeFormatter.format(date)
}

function statusType(status: Session['status']): 'success' | 'info' | 'danger' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'REVOKED') return 'danger'
  return 'info'
}

function statusText(status: Session['status']): string {
  if (status === 'ACTIVE') return '有效'
  if (status === 'REVOKED') return '已撤销'
  return '已过期'
}

function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: value instanceof Error ? value.message : '加载会话失败',
  })
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await listSessions(page.value, size.value)
    sessions.value = data.items
    meta.value = data.meta
  } catch (caught) {
    error.value = toApiError(caught)
    sessions.value = []
  } finally {
    loading.value = false
  }
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="sessions">
    <header class="sessions__header">
      <div>
        <h1 class="sessions__title">会话管理</h1>
        <p class="sessions__subtitle">
          查看当前账号的登录会话（需要权限点
          <code>m0:auth:read</code>）。强制下线接口尚未在契约登记，留 M1。
        </p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="error"
      class="sessions__alert"
      type="error"
      :closable="false"
      show-icon
      title="加载会话失败"
    >
      <p class="sessions__error-message">{{ error.message }}</p>
      <p class="sessions__error-meta">
        错误码：{{ error.code }}
        <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
      </p>
    </el-alert>

    <el-card class="sessions__card" shadow="never">
      <el-table v-loading="loading" :data="sessions" stripe class="sessions__table">
        <el-table-column prop="sessionId" label="会话 ID" min-width="200">
          <template #default="{ row }">
            <span :title="row.sessionId" class="sessions__mono">{{ row.sessionId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="clientIp" label="客户端 IP" min-width="120" />
        <el-table-column prop="userAgent" label="客户端" min-width="180" show-overflow-tooltip />
        <el-table-column label="登录时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.loginAt) }}</template>
        </el-table-column>
        <el-table-column label="过期时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <p class="sessions__empty">暂无会话记录。</p>
        </template>
      </el-table>

      <div class="sessions__pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="meta.total"
          :page-size="size"
          :current-page="page"
          background
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.sessions {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-4);
}

.sessions__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--anesop-space-3);
}

.sessions__title {
  margin: 0;
  font-size: var(--anesop-font-size-xl);
  font-weight: var(--anesop-font-weight-semibold);
}

.sessions__subtitle {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.sessions__subtitle code {
  padding: 0 var(--anesop-space-1);
  font-family: var(--anesop-font-family-mono);
  background: var(--anesop-color-primary-soft);
  border-radius: var(--anesop-radius-sm);
}

.sessions__alert {
  margin-bottom: var(--anesop-space-2);
}

.sessions__error-message {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.sessions__error-meta {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}

.sessions__card {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.sessions__card :deep(.el-card__body) {
  padding: var(--anesop-space-4);
}

.sessions__mono {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.sessions__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--anesop-space-4);
}

.sessions__empty {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}
</style>
