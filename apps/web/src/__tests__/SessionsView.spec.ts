import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import SessionsView from '../modules/m0/views/SessionsView.vue'
import { configureHttp } from '../api/http'
import router from '../router'
import { pinia } from '../stores'
import {
  createFetchQueue,
  errorResponse,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from './testUtils'

const session = {
  sessionId: '4000000000000000001',
  userId: '1000000000000000001',
  clientIp: '10.0.0.10',
  userAgent: 'SYNTHETIC-BROWSER/1.0',
  loginAt: '2026-11-12T08:00:00+08:00',
  expiresAt: '2026-11-19T08:00:00+08:00',
  status: 'ACTIVE' as const,
  createdAt: '2026-11-12T08:00:00+08:00',
  createdBy: '1000000000000000001',
  updatedAt: '2026-11-12T08:00:00+08:00',
  updatedBy: '1000000000000000001',
  version: 1,
}

function sessionPage() {
  return {
    items: [session],
    meta: { page: 1, size: 10, total: 1, totalPages: 1 },
  }
}

describe('SessionsView 会话管理', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m0:auth:read'] }))
    await router.push('/m0/sessions')
  })

  afterEach(() => {
    resetAppState()
  })

  it('加载会话列表并展示客户端与状态', async () => {
    const queue = createFetchQueue([jsonResponse(sessionPage())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(SessionsView, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/auth/sessions?page=1&size=10')
    expect(wrapper.text()).toContain('10.0.0.10')
    expect(wrapper.text()).toContain('SYNTHETIC-BROWSER/1.0')
    expect(wrapper.text()).toContain('有效')
  })

  it('接口失败时展示契约错误码并允许重试', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足', 'trace-403'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(SessionsView, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('无权访问该资源或数据范围不足')
    expect(wrapper.text()).toContain('AUTH_FORBIDDEN')
    expect(wrapper.text()).toContain('trace-403')
  })
})
