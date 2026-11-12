import { afterEach, describe, expect, it, vi } from 'vitest'
import { me } from '../api/auth'
import { ApiError, configureHttp } from '../api/http'
import { getAccessToken, getRefreshToken, setTokens } from '../api/session'
import {
  createFetchQueue,
  errorResponse,
  jsonResponse,
  resetAppState,
  syntheticUser,
  tokenResponse,
} from './testUtils'

function headersOf(call: { init?: RequestInit }): Record<string, string> {
  return (call.init?.headers ?? {}) as Record<string, string>
}

describe('http 请求封装：401 刷新重放', () => {
  afterEach(() => {
    resetAppState()
  })

  it('401 后用 refresh token 单飞刷新一次并重放原请求', async () => {
    resetAppState()
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh', expiresIn: 900 })
    const user = syntheticUser()
    const onSessionExpired = vi.fn()
    const queue = createFetchQueue([
      errorResponse(401, 'AUTH_UNAUTHORIZED', '未认证或令牌已失效', 'trace-401'),
      jsonResponse(tokenResponse(user, 'new-access', 'new-refresh')),
      jsonResponse(user),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl, onSessionExpired })

    const result = await me()

    expect(result.userId).toBe(user.userId)
    expect(queue.calls).toHaveLength(3)
    expect(queue.calls[0]?.url).toBe('/api/v1/auth/me')
    expect(headersOf(queue.calls[0]!).Authorization).toBe('Bearer old-access')

    expect(queue.calls[1]?.url).toBe('/api/v1/auth/refresh')
    expect(headersOf(queue.calls[1]!).Authorization).toBeUndefined()
    expect(JSON.parse(String(queue.calls[1]?.init?.body))).toEqual({ refreshToken: 'old-refresh' })

    expect(queue.calls[2]?.url).toBe('/api/v1/auth/me')
    expect(headersOf(queue.calls[2]!).Authorization).toBe('Bearer new-access')
    expect(headersOf(queue.calls[2]!)['X-Trace-Id']).toBeTruthy()

    expect(getAccessToken()).toBe('new-access')
    expect(getRefreshToken()).toBe('new-refresh')
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('刷新失败时清空会话、通知跳登录并抛契约错误', async () => {
    resetAppState()
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh', expiresIn: 900 })
    const onSessionExpired = vi.fn()
    const queue = createFetchQueue([
      errorResponse(401, 'AUTH_UNAUTHORIZED', '未认证或令牌已失效', 'trace-401'),
      errorResponse(401, 'AUTH_UNAUTHORIZED', '刷新令牌已失效', 'trace-refresh'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl, onSessionExpired })

    await expect(me()).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_UNAUTHORIZED',
      traceId: 'trace-401',
    })

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(window.sessionStorage.getItem('anesop.auth.refreshToken')).toBeNull()
    expect(onSessionExpired).toHaveBeenCalledWith('refresh_failed')
  })

  it('并发 401 只触发一次 refresh，其余请求共享刷新结果', async () => {
    resetAppState()
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh', expiresIn: 900 })
    const user = syntheticUser()
    const refreshResponse = new Promise<Response>((resolve) => {
      setTimeout(() => resolve(jsonResponse(tokenResponse(user, 'new-access', 'new-refresh'))), 10)
    })
    const queue = createFetchQueue([
      errorResponse(401, 'AUTH_UNAUTHORIZED', '未认证'),
      errorResponse(401, 'AUTH_UNAUTHORIZED', '未认证'),
      refreshResponse,
      jsonResponse(user),
      jsonResponse(user),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const [first, second] = await Promise.all([me(), me()])

    expect(first.userId).toBe(user.userId)
    expect(second.userId).toBe(user.userId)
    const refreshCalls = queue.calls.filter((call) => call.url === '/api/v1/auth/refresh')
    expect(refreshCalls).toHaveLength(1)
    const replayCalls = queue.calls.filter((call) => call.url === '/api/v1/auth/me')
    expect(replayCalls).toHaveLength(4)
    expect(
      replayCalls.slice(2).every((call) => headersOf(call).Authorization === 'Bearer new-access'),
    ).toBe(true)
  })

  it('网络异常映射为 NETWORK_ERROR，不误判为认证失败', async () => {
    resetAppState()
    const failingFetch = (async () => {
      throw new TypeError('Failed to fetch')
    }) as typeof fetch
    configureHttp({ fetchImpl: failingFetch })

    const error = await me().catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ kind: 'network', code: 'NETWORK_ERROR', status: 0 })
  })
})
