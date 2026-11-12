import type { components } from '@anesop/contracts/auth'
import { http } from './http'

export type LoginRequest = components['schemas']['LoginRequest']
export type TokenResponse = components['schemas']['TokenResponse']
export type CurrentUser = components['schemas']['CurrentUser']
export type ChangePasswordRequest = components['schemas']['ChangePasswordRequest']
export type SessionPage = components['schemas']['SessionPage']
export type Session = components['schemas']['Session']

/**
 * 认证接口（auth.yaml 0.3.0）。路径不带 `/api` 前缀，由 http 层拼 baseUrl：
 * - 开发：Vite proxy `/api` → http://localhost:8080（去掉 /api 前缀）；
 * - 生产：Nginx `/api` → API 服务。
 */
export function login(payload: LoginRequest): Promise<TokenResponse> {
  return http.post<TokenResponse>('/v1/auth/login', payload, { auth: false })
}

export function refresh(refreshToken: string): Promise<TokenResponse> {
  return http.post<TokenResponse>('/v1/auth/refresh', { refreshToken }, { auth: false })
}

export function logout(): Promise<void> {
  return http.post<void>('/v1/auth/logout')
}

export function me(): Promise<CurrentUser> {
  return http.get<CurrentUser>('/v1/auth/me')
}

export function listSessions(page = 1, size = 20): Promise<SessionPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return http.get<SessionPage>(`/v1/auth/sessions?${query.toString()}`)
}

export function changePassword(payload: ChangePasswordRequest): Promise<void> {
  return http.post<void>('/v1/auth/password', payload)
}
