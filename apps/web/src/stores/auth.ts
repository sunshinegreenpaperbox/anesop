import { defineStore } from 'pinia'
import * as authApi from '../api/auth'
import { ApiError } from '../api/http'
import {
  clearSession,
  getAccessToken,
  loadSession,
  setCurrentUser,
  setTokens,
  type CurrentUser,
} from '../api/session'

export type AuthStatus = 'idle' | 'loading' | 'ready' | 'error'

function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: error instanceof Error ? error.message : '未知错误',
  })
}

/**
 * 认证会话状态（S0-08）。
 *
 * user 快照来自契约 CurrentUser；令牌本体由 src/api/session.ts 管理（内存 + sessionStorage），
 * 本 store 不重复保存 token，避免两处状态不一致。路由守卫用 getAccessToken() 判断“有没有令牌”，
 * 用 user 判断“是否已拉取 /v1/auth/me”。
 */
export const useAuthStore = defineStore('auth', {
  state: () => {
    const stored = loadSession()
    return {
      user: stored.user as CurrentUser | null,
      status: 'idle' as AuthStatus,
      lastError: null as ApiError | null,
      /**
       * 本次前端启动是否已用 /v1/auth/me 校验过会话。
       * user 快照可能来自 sessionStorage，不能替代服务端校验：守卫在首屏导航时
       * 即使有快照也会拉一次 /me；成功后同一次启动内复用缓存。
       */
      initialized: false,
    }
  },

  getters: {
    isAuthenticated: (state): boolean => state.user !== null,
    roles: (state): string[] => state.user?.roles ?? [],
    permissions: (state): string[] => state.user?.permissions ?? [],
    dataScopes: (state) => state.user?.dataScopes ?? [],
    mustChangePassword: (state): boolean => state.user?.mustChangePassword === true,
    displayName: (state): string => state.user?.displayName ?? state.user?.username ?? '',
    username: (state): string => state.user?.username ?? '',
  },

  actions: {
    /** 登录成功即写入令牌与用户快照；失败原样抛出 ApiError 供登录页展示 code/message/traceId。 */
    async login(username: string, password: string): Promise<CurrentUser> {
      this.status = 'loading'
      this.lastError = null
      try {
        const response = await authApi.login({ username, password, authType: 'LOCAL' })
        setTokens(response)
        this.user = response.user
        setCurrentUser(response.user)
        this.status = 'ready'
        this.initialized = true
        return response.user
      } catch (error) {
        this.lastError = toApiError(error)
        this.status = 'error'
        throw this.lastError
      }
    },

    /**
     * 拉取当前用户（缓存到 Pinia）。force=true 时忽略缓存重新拉取。
     * 401 由 http 层尝试刷新；仍失败时清本地会话，守卫转登录。
     */
    async fetchCurrentUser(force = false): Promise<CurrentUser | null> {
      if (!getAccessToken()) {
        this.clear()
        return null
      }
      if (this.initialized && this.user && !force) return this.user

      this.status = 'loading'
      try {
        const user = await authApi.me()
        this.user = user
        setCurrentUser(user)
        this.status = 'ready'
        this.lastError = null
        this.initialized = true
        return user
      } catch (error) {
        this.lastError = toApiError(error)
        this.status = 'error'
        if (this.lastError.status === 0 || this.lastError.status === 401) this.clear()
        throw this.lastError
      }
    },

    hasPermission(code: string): boolean {
      return this.permissions.includes(code)
    },

    /** 路由 meta.permissions 语义：声明多个时满足任一即可（OR）。 */
    hasAnyPermission(codes?: readonly string[]): boolean {
      if (!codes || codes.length === 0) return true
      return codes.some((code) => this.permissions.includes(code))
    },

    hasAllPermissions(codes?: readonly string[]): boolean {
      if (!codes || codes.length === 0) return true
      return codes.every((code) => this.permissions.includes(code))
    },

    async logout(): Promise<void> {
      try {
        await authApi.logout()
      } catch {
        // 登出接口不可用时仍要清本地会话：本地退出优先，服务端令牌按过期自然失效。
      } finally {
        this.clear()
      }
    },

    /** 清空内存与 sessionStorage 会话，不调用后端。 */
    clear(): void {
      clearSession()
      this.user = null
      this.status = 'idle'
      this.lastError = null
      this.initialized = false
    },
  },
})
