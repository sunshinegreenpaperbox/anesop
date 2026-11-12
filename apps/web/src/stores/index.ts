import { createPinia } from 'pinia'

/**
 * 单一 Pinia 实例：main.ts 安装它，路由守卫与测试也复用它，
 * 避免守卫里 `useAuthStore()` 在无 activePinia 时抛错。
 */
export const pinia = createPinia()

export default pinia
