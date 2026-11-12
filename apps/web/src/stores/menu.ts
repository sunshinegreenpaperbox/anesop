import { defineStore } from 'pinia'
import { computed } from 'vue'
import { appMenu, filterMenuByPermission } from '../navigation'
import { useAuthStore } from './auth'

/**
 * 菜单状态：从 navigation.ts 的同一份菜单定义派生，按当前用户权限点过滤。
 * 不做角色硬编码判断——权限点全部来自 /v1/auth/me 快照。
 */
export const useMenuStore = defineStore('menu', () => {
  const auth = useAuthStore()

  const visibleMenus = computed(() =>
    filterMenuByPermission(appMenu, (permissions) => auth.hasAnyPermission(permissions)),
  )

  return { visibleMenus }
})
