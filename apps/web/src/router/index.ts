import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '../api/session'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'
import { routes } from './routes'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/** 只接受站内绝对路径，阻止 `//evil.com` 与登录页自身造成开放重定向/循环。 */
function safeRedirect(value: unknown): string | null {
  if (typeof value !== 'string') return null
  if (!value.startsWith('/') || value.startsWith('//')) return null
  if (value.startsWith('/login')) return null
  return value
}

router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)
  const hasToken = Boolean(getAccessToken())

  // 公开页：登录页；已登录用户访问登录页时拉取 /me 后回首页。
  if (to.meta.public) {
    if (to.name === 'login' && hasToken) {
      try {
        await auth.fetchCurrentUser()
      } catch {
        // 令牌失效：http 层已清会话，停留在登录页由用户重新登录。
        return true
      }
      return safeRedirect(to.query.redirect) ?? { name: 'home' }
    }
    return true
  }

  // 无 token：一律回登录页，并记录来源用于登录后跳转。
  if (!hasToken) {
    return {
      name: 'login',
      query: to.fullPath === '/' ? {} : { redirect: to.fullPath },
    }
  }

  // 有 token：拉取 /v1/auth/me（缓存到 Pinia）；401 由 http 层刷新一次。
  try {
    await auth.fetchCurrentUser()
  } catch {
    auth.clear()
    return {
      name: 'login',
      query: { redirect: to.fullPath, reason: 'session-expired' },
    }
  }

  // 强制改密：服务端只放行 me/logout/password，前端同步收敛到改密页。
  if (
    auth.mustChangePassword &&
    to.name !== 'm0-password' &&
    to.name !== 'forbidden' &&
    to.name !== 'not-found' &&
    to.name !== 'catch-all'
  ) {
    return { name: 'm0-password', query: { forced: '1' } }
  }

  // 权限点声明在 meta.permissions（多个为 OR）；无权限不进入页面，避免先渲染再报 403。
  if (to.meta.permissions && to.meta.permissions.length > 0) {
    if (!auth.hasAnyPermission(to.meta.permissions)) {
      return { name: 'forbidden', query: { from: to.fullPath } }
    }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · ANESOP 麻醉信息平台` : 'ANESOP 麻醉信息平台'
})

export default router
