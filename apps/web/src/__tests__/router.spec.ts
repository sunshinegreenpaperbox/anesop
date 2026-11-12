import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { configureHttp } from '../api/http'
import { getAccessToken, setTokens } from '../api/session'
import router from '../router'
import { resetAppState, createFetchQueue, jsonResponse, syntheticUser } from './testUtils'

describe('路由守卫', () => {
  beforeEach(async () => {
    resetAppState()
    await router.push('/login')
  })

  afterEach(() => {
    resetAppState()
  })

  it('无 token 访问受保护页面时跳转登录页并保留来源', async () => {
    await router.push('/home')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/home')
    expect(router.currentRoute.value.meta.public).toBe(true)
  })

  it('有 token 时拉取 /v1/auth/me 并放行首页', async () => {
    const user = syntheticUser()
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 })
    const { fetchImpl, calls } = createFetchQueue([jsonResponse(user)])
    configureHttp({ fetchImpl })

    await router.push('/home')

    expect(router.currentRoute.value.name).toBe('home')
    expect(calls).toHaveLength(1)
    expect(calls[0]?.url).toBe('/api/v1/auth/me')
    expect(calls[0]?.init?.headers).toMatchObject({ Authorization: 'Bearer access-1' })
  })

  it('权限点不足时跳转 403 且不进入目标页', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 })
    const user = syntheticUser({
      roles: ['SYS_ANESTHESIOLOGIST'],
      permissions: ['anesthesia:record:read'],
    })
    const { fetchImpl } = createFetchQueue([jsonResponse(user)])
    configureHttp({ fetchImpl })

    await router.push('/m0/sessions')

    expect(router.currentRoute.value.name).toBe('forbidden')
    expect(router.currentRoute.value.query.from).toBe('/m0/sessions')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('具备 m0:auth:read 时允许进入会话管理', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 })
    const user = syntheticUser({ permissions: ['m0:auth:read'] })
    const { fetchImpl } = createFetchQueue([jsonResponse(user)])
    configureHttp({ fetchImpl })

    await router.push('/m0/sessions')

    expect(router.currentRoute.value.name).toBe('m0-sessions')
    expect(router.currentRoute.value.path).toBe('/m0/sessions')
  })

  it('已登录时未知路径保留 URL 并渲染 404', async () => {
    const user = syntheticUser()
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 })
    const { fetchImpl } = createFetchQueue([jsonResponse(user)])
    configureHttp({ fetchImpl })

    await router.push('/no-such-page')

    expect(router.currentRoute.value.name).toBe('catch-all')
    expect(router.currentRoute.value.path).toBe('/no-such-page')
    expect(router.currentRoute.value.meta.title).toBe('页面不存在')
  })

  it('mustChangePassword=true 时收敛到改密页', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 })
    const user = syntheticUser({ mustChangePassword: true })
    const { fetchImpl } = createFetchQueue([jsonResponse(user)])
    configureHttp({ fetchImpl })

    await router.push('/home')

    expect(router.currentRoute.value.name).toBe('m0-password')
    expect(router.currentRoute.value.query.forced).toBe('1')
    expect(getAccessToken()).toBe('access-1')
  })
})
