import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listMessages } from '../modules/m0/api/platform'
import { useAuthStore } from './auth'

/**
 * 未读消息角标（S-6）：侧边菜单与消息中心共享同一份未读数。
 *
 * - 只有持 `m0:message:read` 的账号才请求 `/v1/messages`（避免无权限账号每页 403）；
 * - `unreadCount` 来自服务端 `MessagePage.unreadCount`（不受分页/未读过滤影响）；
 * - 请求失败按 0 处理并保留上次值，不阻塞导航；消息内容不进入本 store。
 */
export const useMessageStore = defineStore('message', () => {
  const auth = useAuthStore()
  const unreadCount = ref(0)
  const loading = ref(false)

  async function refresh(): Promise<void> {
    if (!auth.hasPermission('m0:message:read')) {
      unreadCount.value = 0
      return
    }
    loading.value = true
    try {
      const page = await listMessages(1, 1, false)
      unreadCount.value = page.unreadCount
    } catch {
      // 角标是增强信息：失败不弹错、不阻塞导航；保留上次值。
    } finally {
      loading.value = false
    }
  }

  function setUnreadCount(value: number): void {
    unreadCount.value = Math.max(0, value)
  }

  function clear(): void {
    unreadCount.value = 0
  }

  return { unreadCount, loading, refresh, setUnreadCount, clear }
})
