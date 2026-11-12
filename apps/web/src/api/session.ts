import type { components } from '@anesop/contracts/auth'

/** 契约 CurrentUser 快照（apps/web 只读，不手写重复类型）。 */
export type CurrentUser = components['schemas']['CurrentUser']
export type TokenResponse = components['schemas']['TokenResponse']

/**
 * 会话存储策略（S0-08）：
 * - accessToken：内存优先 + sessionStorage 备份（刷新页面保留登录，关闭标签页即失效）；
 * - refreshToken：仅 sessionStorage（不落 localStorage，降低持久化泄露面）；
 * - currentUser：sessionStorage，作为首屏缓存，路由守卫仍会用 /v1/auth/me 校验。
 *
 * XSS 风险：sessionStorage 对同源脚本可读。当前为 S0 骨架的权衡（无后端 Cookie 会话），
 * M1 迁移到 httpOnly + Secure + SameSite Cookie 后删除 refreshToken 的 Web Storage 写入。
 * 详见 docs/ui/README.md「会话存储与 M1 安全待办」。
 */
const ACCESS_KEY = 'anesop.auth.accessToken'
const REFRESH_KEY = 'anesop.auth.refreshToken'
const USER_KEY = 'anesop.auth.currentUser'
const EXPIRES_KEY = 'anesop.auth.expiresAt'

let accessToken: string | null = null
let refreshToken: string | null = null
let currentUser: CurrentUser | null = null
let expiresAt: number | null = null
let loaded = false

function storage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    // 隐私模式/沙箱禁用 Web Storage 时退化为纯内存会话。
    return null
  }
}

function read(key: string): string | null {
  return storage()?.getItem(key) ?? null
}

function write(key: string, value: string): void {
  storage()?.setItem(key, value)
}

function remove(key: string): void {
  storage()?.removeItem(key)
}

function parseUser(raw: string | null): CurrentUser | null {
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const candidate = parsed as Partial<CurrentUser>
    if (typeof candidate.userId !== 'string' || typeof candidate.username !== 'string') return null
    return candidate as CurrentUser
  } catch {
    return null
  }
}

/** 从 sessionStorage 恢复内存态；进程内只执行一次，后续以内存为准。 */
export function loadSession(): { user: CurrentUser | null; expiresAt: number | null } {
  if (!loaded) {
    accessToken = read(ACCESS_KEY)
    refreshToken = read(REFRESH_KEY)
    currentUser = parseUser(read(USER_KEY))
    const rawExpires = read(EXPIRES_KEY)
    const parsedExpires = rawExpires ? Number.parseInt(rawExpires, 10) : Number.NaN
    expiresAt = Number.isFinite(parsedExpires) ? parsedExpires : null
    loaded = true
  }
  return { user: currentUser, expiresAt }
}

export function getAccessToken(): string | null {
  if (!loaded) loadSession()
  return accessToken
}

export function getRefreshToken(): string | null {
  if (!loaded) loadSession()
  return refreshToken
}

export function getCurrentUser(): CurrentUser | null {
  if (!loaded) loadSession()
  return currentUser
}

export function getSessionExpiresAt(): number | null {
  if (!loaded) loadSession()
  return expiresAt
}

/** 保存令牌响应；expiresIn 单位为秒（契约 TokenResponse.expiresIn）。 */
export function setTokens(
  tokens: Pick<TokenResponse, 'accessToken' | 'refreshToken' | 'expiresIn'>,
): void {
  accessToken = tokens.accessToken
  refreshToken = tokens.refreshToken
  expiresAt = Date.now() + tokens.expiresIn * 1000
  loaded = true
  write(ACCESS_KEY, accessToken)
  write(REFRESH_KEY, refreshToken)
  write(EXPIRES_KEY, String(expiresAt))
}

export function setCurrentUser(user: CurrentUser | null): void {
  currentUser = user
  loaded = true
  if (user) {
    write(USER_KEY, JSON.stringify(user))
  } else {
    remove(USER_KEY)
  }
}

/** 清理全部会话痕迹；登出、刷新失败、401 不可恢复时调用。 */
export function clearSession(): void {
  accessToken = null
  refreshToken = null
  currentUser = null
  expiresAt = null
  loaded = true
  remove(ACCESS_KEY)
  remove(REFRESH_KEY)
  remove(USER_KEY)
  remove(EXPIRES_KEY)
}

/** 仅测试使用：恢复未加载状态，强制下次从 sessionStorage 读取。 */
export function resetSessionCacheForTests(): void {
  accessToken = null
  refreshToken = null
  currentUser = null
  expiresAt = null
  loaded = false
}
