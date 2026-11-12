import type { components } from '@anesop/contracts/auth'
import { resetHttpForTests } from '../api/http'
import { resetSessionCacheForTests, setCurrentUser, setTokens } from '../api/session'
import { useAuthStore } from '../stores/auth'
import { pinia } from '../stores'

export type CurrentUser = components['schemas']['CurrentUser']
export type TokenResponse = components['schemas']['TokenResponse']

/** SYNTHETIC 用户：不含真实姓名/工号/手机号，仅用于测试。 */
export function syntheticUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    userId: '1000000000000000001',
    username: 'demo_admin',
    displayName: '演示管理员（合成）',
    roles: ['SYS_ADMIN'],
    permissions: ['m0:auth:read', 'm0:auth:write'],
    dataScopes: [{ type: 'ALL', scopeIds: [] }],
    siteIds: ['3000000000000000001'],
    mustChangePassword: false,
    ...overrides,
  }
}

export function tokenResponse(
  user: CurrentUser,
  accessToken = 'access-token-1',
  refreshToken = 'refresh-token-1',
): TokenResponse {
  return { accessToken, refreshToken, tokenType: 'Bearer', expiresIn: 900, user }
}

export function jsonResponse(body: unknown, status = 200, traceId = 'trace-test-0001'): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'X-Trace-Id': traceId },
  })
}

export function errorResponse(
  status: number,
  code: string,
  message: string,
  traceId = 'trace-test-0001',
): Response {
  return jsonResponse({ code, message, traceId, details: [] }, status, traceId)
}

interface FetchCall {
  url: string
  init?: RequestInit
}

type FetchResult = Response | Promise<Response> | (() => Response | Promise<Response>)

/**
 * 按顺序返回预置 Response 的 fetch 桩；记录调用参数供断言。
 * 队列耗尽即抛错，避免“多打了一次请求”被静默忽略。
 */
export function createFetchQueue(responses: FetchResult[]): {
  fetchImpl: typeof fetch
  calls: FetchCall[]
} {
  const calls: FetchCall[] = []
  let index = 0
  const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    calls.push({ url: String(input), init })
    const next = responses[index]
    index += 1
    if (!next) throw new Error(`unexpected fetch #${index}: ${String(input)}`)
    return typeof next === 'function' ? await next() : await next
  }
  return { fetchImpl: fetchImpl as typeof fetch, calls }
}

/** 每个用例前重置 HTTP 配置、sessionStorage 与 Pinia 中的认证状态。 */
export function resetAppState(): void {
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  resetHttpForTests()
  const auth = useAuthStore(pinia)
  auth.$reset()
}

/** 已登录且已加载用户快照的状态（用于组件级测试，不触发网络）。 */
export function seedAuthenticatedUser(user: CurrentUser = syntheticUser()): void {
  setTokens({
    accessToken: 'test-access-token',
    refreshToken: 'test-refresh-token',
    expiresIn: 900,
  })
  setCurrentUser(user)
  const auth = useAuthStore(pinia)
  auth.user = user
  auth.status = 'ready'
  // 组件级测试直接以“本启动已通过 /me 校验”的状态进入，避免为了布局挂载而 mock 网络。
  auth.initialized = true
}
