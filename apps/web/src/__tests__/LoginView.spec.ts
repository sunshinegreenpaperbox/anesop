import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from '../modules/m0/views/LoginView.vue'
import { configureHttp } from '../api/http'
import { getAccessToken, getCurrentUser } from '../api/session'
import { pinia } from '../stores'
import router from '../router'
import {
  createFetchQueue,
  errorResponse,
  jsonResponse,
  resetAppState,
  syntheticUser,
  tokenResponse,
} from './testUtils'

function mountLogin() {
  return mount(LoginView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
}

async function fillAndSubmit(
  wrapper: ReturnType<typeof mountLogin>,
  username = 'demo_admin',
  password = 'Anesop@2026',
): Promise<void> {
  await wrapper.get('input[autocomplete="username"]').setValue(username)
  await wrapper.get('input[autocomplete="current-password"]').setValue(password)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('LoginView 登录页', () => {
  beforeEach(async () => {
    resetAppState()
    await router.push('/login')
  })

  afterEach(() => {
    resetAppState()
  })

  it('提交成功后保存会话并跳转首页', async () => {
    const user = syntheticUser()
    const queue = createFetchQueue([
      jsonResponse(tokenResponse(user, 'access-login', 'refresh-login')),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountLogin()
    await fillAndSubmit(wrapper)

    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/home'))
    expect(queue.calls[0]?.url).toBe('/api/v1/auth/login')
    expect(JSON.parse(String(queue.calls[0]?.init?.body))).toMatchObject({
      username: 'demo_admin',
      authType: 'LOCAL',
    })
    expect(getAccessToken()).toBe('access-login')
    expect(getCurrentUser()?.userId).toBe(user.userId)
  })

  it('登录失败展示契约 code/message/traceId，停留在登录页', async () => {
    const queue = createFetchQueue([
      errorResponse(401, 'AUTH_UNAUTHORIZED', '账号或口令不正确', 'trace-login-fail'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountLogin()
    await fillAndSubmit(wrapper, 'demo_admin', 'wrong-password')

    await vi.waitFor(() => expect(wrapper.find('.login__error-message').exists()).toBe(true))
    expect(wrapper.get('.login__error-message').text()).toBe('账号或口令不正确')
    expect(wrapper.text()).toContain('AUTH_UNAUTHORIZED')
    expect(wrapper.text()).toContain('trace-login-fail')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(getAccessToken()).toBeNull()
  })

  it('空账号空口令时前端校验拦截，不发起请求', async () => {
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountLogin()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(queue.calls).toHaveLength(0)
    await vi.waitFor(() => expect(wrapper.text()).toContain('请输入登录账号'))
    await vi.waitFor(() => expect(wrapper.text()).toContain('请输入口令'))
  })

  it('mustChangePassword=true 时登录后跳转改密页', async () => {
    const user = syntheticUser({ mustChangePassword: true })
    const queue = createFetchQueue([jsonResponse(tokenResponse(user))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountLogin()
    await fillAndSubmit(wrapper)

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('m0-password'))
    expect(router.currentRoute.value.query.forced).toBe('1')
  })
})
