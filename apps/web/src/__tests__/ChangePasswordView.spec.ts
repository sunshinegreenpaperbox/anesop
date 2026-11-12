import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChangePasswordView from '../modules/m0/views/ChangePasswordView.vue'
import { configureHttp } from '../api/http'
import { getAccessToken } from '../api/session'
import router from '../router'
import { pinia } from '../stores'
import { createFetchQueue, resetAppState, seedAuthenticatedUser, syntheticUser } from './testUtils'

function mountView() {
  return mount(ChangePasswordView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function fill(
  wrapper: ReturnType<typeof mountView>,
  oldPassword: string,
  newPassword: string,
  confirmPassword = newPassword,
): Promise<void> {
  await wrapper.get('input[autocomplete="current-password"]').setValue(oldPassword)
  const newInputs = wrapper.findAll('input[autocomplete="new-password"]')
  await newInputs[0]!.setValue(newPassword)
  await newInputs[1]!.setValue(confirmPassword)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('ChangePasswordView 改密页', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ mustChangePassword: true }))
    await router.push('/m0/password?forced=1')
  })

  afterEach(() => {
    resetAppState()
  })

  it('首次登录展示强制改密提示，提交成功后清会话并要求重新登录', async () => {
    const queue = createFetchQueue([new Response(null, { status: 204 })])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    expect(wrapper.text()).toContain('首次登录必须修改')

    await fill(wrapper, 'Anesop@2026', 'Anesop@2027!x')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(router.currentRoute.value.query.reason).toBe('password-changed')
    expect(queue.calls[0]?.url).toBe('/api/v1/auth/password')
    expect(JSON.parse(String(queue.calls[0]?.init?.body))).toEqual({
      oldPassword: 'Anesop@2026',
      newPassword: 'Anesop@2027!x',
    })
    expect(getAccessToken()).toBeNull()
  })

  it('新口令不满足强度时不发请求', async () => {
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await fill(wrapper, 'Anesop@2026', 'short')

    expect(queue.calls).toHaveLength(0)
    await vi.waitFor(() => expect(wrapper.text()).toContain('新口令长度需为 12–128 个字符'))
  })

  it('两次新口令不一致时校验拦截', async () => {
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await fill(wrapper, 'Anesop@2026', 'Anesop@2027!x', 'Anesop@2027!y')

    expect(queue.calls).toHaveLength(0)
    await vi.waitFor(() => expect(wrapper.text()).toContain('两次输入的新口令不一致'))
  })
})
