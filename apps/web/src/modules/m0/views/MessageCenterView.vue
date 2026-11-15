<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useMessageStore } from '../../../stores/message'
import {
  listMessages,
  markAllMessagesRead,
  markMessageRead,
  type Message,
  type MessagePage,
} from '../api/platform'

/**
 * 消息中心（M8-M0-04，契约 platform.yaml 0.2.0）。
 *
 * - 仅显示当前用户消息（后端强制 recipient_user_id = 当前用户；他人消息 404）；
 * - 未读角标 unreadCount 来自服务端（不受 unreadOnly/分页影响）；
 * - 标记已读幂等；全部已读只影响本人未读；
 * - 消息为模板编码 + 参数白名单，页面只展示键值，不含 PHI。
 */

const messageStore = useMessageStore()
const messages = ref<Message[]>([])
const meta = ref<MessagePage['meta']>({ page: 1, size: 20, total: 0, totalPages: 0 })
const unreadCount = ref(0)
const unreadOnly = ref(false)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const marking = ref(false)
const error = ref<ApiError | null>(null)

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

const badgeValue = computed(() => (unreadCount.value > 99 ? '99+' : String(unreadCount.value)))

function formatTime(value: string | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : timeFormatter.format(date)
}

function formatParams(message: Message): string {
  const entries = Object.entries(message.params ?? {})
  if (entries.length === 0) return '—'
  return entries.map(([key, value]) => `${key}=${value}`).join(' · ')
}

function toApiError(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: cause instanceof Error ? cause.message : '加载消息失败',
  })
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await listMessages(page.value, size.value, unreadOnly.value)
    messages.value = data.items
    meta.value = data.meta
    unreadCount.value = data.unreadCount
    messageStore.setUnreadCount(data.unreadCount)
  } catch (cause) {
    error.value = toApiError(cause)
    messages.value = []
    ElMessage.error(error.value.message)
  } finally {
    loading.value = false
  }
}

async function markRead(message: Message): Promise<void> {
  if (message.read) return
  try {
    const updated = await markMessageRead(message.messageId)
    message.read = updated.read
    message.readAt = updated.readAt
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    messageStore.setUnreadCount(unreadCount.value)
    if (unreadOnly.value) {
      messages.value = messages.value.filter((item) => item.messageId !== message.messageId)
      meta.value = { ...meta.value, total: Math.max(0, meta.value.total - 1) }
    }
    ElMessage.success('已标记为已读')
  } catch (cause) {
    error.value = toApiError(cause)
    ElMessage.error(error.value.message)
  }
}

async function readAll(): Promise<void> {
  marking.value = true
  try {
    const result = await markAllMessagesRead()
    unreadCount.value = 0
    messageStore.setUnreadCount(0)
    ElMessage.success(`已标记 ${result.updatedCount} 条消息为已读`)
    await load()
  } catch (cause) {
    error.value = toApiError(cause)
    ElMessage.error(error.value.message)
  } finally {
    marking.value = false
  }
}

function toggleUnreadOnly(value: boolean): void {
  unreadOnly.value = value
  page.value = 1
  void load()
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
  <section class="messages">
    <header class="messages__header">
      <div>
        <h1 class="messages__title">
          消息中心
          <el-badge
            v-if="unreadCount > 0"
            :value="badgeValue"
            class="messages__badge"
            :data-testid="'unread-badge'"
          />
        </h1>
        <p class="messages__subtitle">
          仅显示当前账号的站内消息（权限点 <code>m0:message:read</code>）。消息由领域事件/系统
          广播写入，参数为模板白名单字段，不含患者标识。
        </p>
      </div>
      <div class="messages__actions">
        <el-switch
          :model-value="unreadOnly"
          active-text="只看未读"
          @update:model-value="
            (value: string | number | boolean) => toggleUnreadOnly(value === true)
          "
        />
        <el-button :disabled="unreadCount === 0" :loading="marking" @click="readAll">
          全部已读
        </el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="messages__alert"
      type="error"
      :closable="false"
      show-icon
      title="消息操作失败"
    >
      <p class="messages__error-message">{{ error.message }}</p>
      <p class="messages__error-meta">
        错误码：{{ error.code }}
        <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
      </p>
    </el-alert>

    <el-card class="messages__card" shadow="never">
      <el-table v-loading="loading" :data="messages" stripe class="messages__table">
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.read ? 'info' : 'warning'" effect="plain" size="small">
              {{ row.read ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="templateCode" label="模板" min-width="160" />
        <el-table-column label="参数" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ formatParams(row) }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.read" link type="primary" @click="markRead(row)">
              标记已读
            </el-button>
            <span v-else class="messages__read-at">{{ formatTime(row.readAt) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <p class="messages__empty">暂无消息。</p>
        </template>
      </el-table>

      <div class="messages__pager">
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
.messages {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-4);
}

.messages__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--anesop-space-3);
}

.messages__title {
  display: flex;
  align-items: center;
  gap: var(--anesop-space-2);
  margin: 0;
  font-size: var(--anesop-font-size-xl);
  font-weight: var(--anesop-font-weight-semibold);
}

.messages__badge {
  line-height: 1;
}

.messages__subtitle {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.messages__subtitle code {
  padding: 0 var(--anesop-space-1);
  font-family: var(--anesop-font-family-mono);
  background: var(--anesop-color-primary-soft);
  border-radius: var(--anesop-radius-sm);
}

.messages__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--anesop-space-2);
}

.messages__alert {
  margin-bottom: var(--anesop-space-2);
}

.messages__error-message {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.messages__error-meta {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}

.messages__card {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.messages__read-at {
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
}

.messages__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--anesop-space-4);
}

.messages__empty {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}
</style>
