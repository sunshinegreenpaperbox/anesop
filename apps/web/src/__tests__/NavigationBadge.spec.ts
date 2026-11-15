import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import MainLayout from '../layout/MainLayout.vue'
import { configureHttp } from '../api/http'
import router from '../router'
import { pinia } from '../stores'
import {
  createFetchQueue,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from './testUtils'

const MESSAGE_PAGE = {
  items: [],
  meta: { page: 1, size: 1, total: 3, totalPages: 3 },
  unreadCount: 3,
}

const originalWidth = window.innerWidth

describe('导航未读消息角标', () => {
  beforeEach(async () => {
    resetAppState()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 })
    await router.push('/login')
  })

  afterEach(() => {
    resetAppState()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: originalWidth })
  })

  it('持 m0:message:read 时请求未读数并在菜单显示角标', async () => {
    const queue = createFetchQueue([jsonResponse(MESSAGE_PAGE)])
    configureHttp({ fetchImpl: queue.fetchImpl })
    seedAuthenticatedUser(syntheticUser({ permissions: ['m0:message:read', 'm0:auth:read'] }))
    await router.push('/home')

    const wrapper = mount(MainLayout, {
      global: { plugins: [pinia, router, ElementPlus] },
    })
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/messages?page=1&size=1&unreadOnly=false')
    expect(wrapper.find('[data-testid="nav-unread-badge"]').text()).toContain('3')
  })

  it('无 m0:message:read 时不请求消息接口也不显示角标', async () => {
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })
    seedAuthenticatedUser(syntheticUser({ permissions: ['m0:auth:read'] }))
    await router.push('/home')

    const wrapper = mount(MainLayout, {
      global: { plugins: [pinia, router, ElementPlus] },
    })
    await flushPromises()

    expect(queue.calls).toHaveLength(0)
    expect(wrapper.find('[data-testid="nav-unread-badge"]').exists()).toBe(false)
  })
})
