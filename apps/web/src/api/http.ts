import type { components } from '@anesop/contracts/auth'
import { clearSession, getAccessToken, getRefreshToken, setTokens } from './session'

export type ErrorDetail = components['schemas']['ErrorDetail']
type TokenResponse = components['schemas']['TokenResponse']

export type ApiErrorKind = 'http' | 'network' | 'timeout' | 'aborted' | 'parse'

/**
 * 统一 API 错误：直接承载契约 Error 的 code/message/traceId/details，
 * 页面只展示 message 与 traceId，分支逻辑使用 code。
 */
export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status: number
  readonly code: string
  readonly traceId: string
  readonly details: ErrorDetail[]

  constructor(params: {
    kind?: ApiErrorKind
    status?: number
    code: string
    message: string
    traceId?: string
    details?: ErrorDetail[]
  }) {
    super(params.message)
    this.name = 'ApiError'
    this.kind = params.kind ?? 'http'
    this.status = params.status ?? 0
    this.code = params.code
    this.traceId = params.traceId ?? ''
    this.details = params.details ?? []
  }

  /** 已认证但无权限，或数据范围不足。 */
  get isForbidden(): boolean {
    return this.status === 403
  }

  /** 未认证/令牌失效。 */
  get isUnauthorized(): boolean {
    return this.status === 401
  }
}

export interface HttpConfig {
  /** 默认 `/api`：Vite dev 代理到 localhost:8080，生产由 Nginx 反代到 API。 */
  baseUrl: string
  timeoutMs: number
  fetchImpl: typeof fetch
  /** 刷新失败或刷新后仍 401 时通知应用层（清会话 + 跳登录）。 */
  onSessionExpired?: (reason: 'refresh_failed' | 'unauthorized_after_refresh') => void
}

function readBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL
  if (typeof configured === 'string' && configured.trim().length > 0) {
    return configured.trim().replace(/\/$/, '')
  }
  return '/api'
}

const DEFAULT_CONFIG: HttpConfig = {
  baseUrl: readBaseUrl(),
  timeoutMs: 15_000,
  fetchImpl: (...args) => fetch(...args),
}

let config: HttpConfig = { ...DEFAULT_CONFIG }

export function configureHttp(overrides: Partial<HttpConfig>): void {
  config = { ...config, ...overrides }
}

export function getApiBaseUrl(): string {
  return config.baseUrl
}

/** 测试钩子：恢复默认配置，避免用例之间互相污染。 */
export function resetHttpForTests(): void {
  config = { ...DEFAULT_CONFIG }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
  /** 是否附加 Bearer 与触发 401 刷新；登录/刷新显式传 false。 */
  auth?: boolean
  timeoutMs?: number
  signal?: AbortSignal
  /** 内部标记：401 后已重放一次，禁止再次刷新，避免死循环。 */
  retried?: boolean
  /** 调用方显式指定的链路追踪标识；缺省时自动生成。 */
  traceId?: string
}

interface RequestResult<T> {
  data: T
  status: number
  traceId: string
}

export function createTraceId(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  return `trace-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function buildUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const base = config.baseUrl
  return `${base}${path.startsWith('/') ? '' : '/'}${path}`
}

function normalizeErrorBody(body: unknown): {
  code: string
  message: string
  traceId: string
  details: ErrorDetail[]
} | null {
  if (typeof body !== 'object' || body === null) return null
  const candidate = body as Record<string, unknown>
  if (typeof candidate.code !== 'string' || typeof candidate.message !== 'string') return null
  const details = Array.isArray(candidate.details)
    ? candidate.details.filter(
        (item): item is ErrorDetail =>
          typeof item === 'object' &&
          item !== null &&
          typeof (item as ErrorDetail).field === 'string' &&
          typeof (item as ErrorDetail).reason === 'string',
      )
    : []
  return {
    code: candidate.code,
    message: candidate.message,
    traceId: typeof candidate.traceId === 'string' ? candidate.traceId : '',
    details,
  }
}

async function parseResponseBody(response: Response): Promise<unknown> {
  if (response.status === 204 || response.status === 205) return null
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

let refreshInFlight: Promise<boolean> | null = null

/**
 * 单飞刷新：并发多个 401 只发一次 /v1/auth/refresh，其余等待同一结果。
 * 刷新失败返回 false，由调用方统一清会话并跳登录。
 */
function refreshAccessToken(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = performRefresh().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

async function performRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return false

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), config.timeoutMs)
  try {
    const response = await config.fetchImpl(buildUrl('/v1/auth/refresh'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Trace-Id': createTraceId() },
      body: JSON.stringify({ refreshToken }),
      signal: controller.signal,
      credentials: 'same-origin',
    })
    if (!response.ok) return false
    const body = (await parseResponseBody(response)) as TokenResponse | null
    if (
      !body ||
      typeof body.accessToken !== 'string' ||
      typeof body.refreshToken !== 'string' ||
      typeof body.expiresIn !== 'number'
    ) {
      return false
    }
    setTokens(body)
    return true
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

function expireSession(reason: 'refresh_failed' | 'unauthorized_after_refresh'): void {
  clearSession()
  config.onSessionExpired?.(reason)
}

function createTimeoutSignal(
  timeoutMs: number,
  external?: AbortSignal,
): {
  signal: AbortSignal
  cleanup: () => void
  didTimeout: () => boolean
} {
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  const onAbort = () => controller.abort()
  if (external) {
    if (external.aborted) controller.abort()
    else external.addEventListener('abort', onAbort, { once: true })
  }
  return {
    signal: controller.signal,
    didTimeout: () => timedOut,
    cleanup: () => {
      clearTimeout(timer)
      external?.removeEventListener('abort', onAbort)
    },
  }
}

async function execute<T>(path: string, options: RequestOptions): Promise<RequestResult<T>> {
  const method = options.method ?? 'GET'
  const auth = options.auth !== false
  const traceId = options.traceId ?? createTraceId()
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Trace-Id': traceId,
    ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    ...options.headers,
  }

  if (auth) {
    const token = getAccessToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  const timeout = createTimeoutSignal(options.timeoutMs ?? config.timeoutMs, options.signal)
  let response: Response
  try {
    response = await config.fetchImpl(buildUrl(path), {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: timeout.signal,
      credentials: 'same-origin',
    })
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (timeout.didTimeout()) {
      throw new ApiError({
        kind: 'timeout',
        code: 'NETWORK_TIMEOUT',
        message: '请求超时，请检查网络后重试',
        traceId,
      })
    }
    if (timeout.signal.aborted) {
      throw new ApiError({
        kind: 'aborted',
        code: 'REQUEST_ABORTED',
        message: '请求已取消',
        traceId,
      })
    }
    throw new ApiError({
      kind: 'network',
      code: 'NETWORK_ERROR',
      message: '无法连接服务器，请检查网络或稍后重试',
      traceId,
    })
  } finally {
    timeout.cleanup()
  }

  const responseTraceId = response.headers.get('X-Trace-Id') ?? traceId
  let body: unknown = null
  try {
    body = await parseResponseBody(response)
  } catch {
    if (response.ok) {
      throw new ApiError({
        kind: 'parse',
        status: response.status,
        code: 'RESPONSE_PARSE_ERROR',
        message: '响应解析失败，请稍后重试',
        traceId: responseTraceId,
      })
    }
  }

  if (response.status === 401 && auth) {
    if (!options.retried && (await refreshAccessToken())) {
      return execute<T>(path, { ...options, retried: true, traceId })
    }
    expireSession(options.retried ? 'unauthorized_after_refresh' : 'refresh_failed')
  }

  if (!response.ok) {
    const normalized = normalizeErrorBody(body)
    throw new ApiError({
      status: response.status,
      code: normalized?.code ?? `HTTP_${response.status}`,
      message: normalized?.message ?? `请求失败（HTTP ${response.status}）`,
      traceId: normalized?.traceId || responseTraceId,
      details: normalized?.details ?? [],
    })
  }

  return { data: body as T, status: response.status, traceId: responseTraceId }
}

/** 通用请求入口：自动 Bearer、X-Trace-Id、超时、契约错误解析、401 单飞刷新重放。 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const result = await execute<T>(path, options)
  return result.data
}

export const http = {
  get: <T>(path: string, options: RequestOptions = {}) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: RequestOptions = {}) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options: RequestOptions = {}) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options: RequestOptions = {}) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options: RequestOptions = {}) =>
    request<T>(path, { ...options, method: 'DELETE' }),
}
