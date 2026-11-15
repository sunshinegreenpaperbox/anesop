import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import MessageCenterView from '../modules/m0/views/MessageCenterView.vue'
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

const message = {
  messageId: '9a000000-0000-4000-8000-000000000001',
  type: 'SCHEDULE' as const,
  templateCode: 'SCHEDULE_CHANGED',
  title: '台次时间已调整',
  params: { roomNo: 'OR-01', count: '1' },
  read: false,
  createdAt: '2026-11-15T18:20:00+08:00',
  readAt: null,
}

function page(overrides: Record<string, unknown> = {}) {
  return {
    items: [message],
    meta: { page: 1, size: 20, total: 1, totalPages: 1 },
    unreadCount: 1,
    ...overrides,
  }
}

function mountView() {
  return mount(MessageCenterView, { global: { plugins: [pinia, router, ElementPlus] } })
}

describe('MessageCenterView 消息中心', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ roles: ['SYS_ANESTHESIOLOGIST'], permissions: ['m0:message:read'] }),
    )
    await router.push('/m0/messages')
  })

  afterEach(() => {
    resetAppState()
  })

  it('加载当前用户消息并展示未读角标与参数', async () => {
    const queue = createFetchQueue([jsonResponse(page())])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/messages?page=1&size=20&unreadOnly=false')
    expect(wrapper.text()).toContain('台次时间已调整')
    expect(wrapper.text()).toContain('SCHEDULE_CHANGED')
    expect(wrapper.text()).toContain('roomNo=OR-01')
    expect(wrapper.find('[data-testid="unread-badge"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('未读')
  })

  it('标记单条已读调用 POST 并减少未读数', async () => {
    const queue = createFetchQueue([
      jsonResponse(page()),
      jsonResponse({ ...message, read: true, readAt: '2026-11-15T18:25:00+08:00' }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    const markButton = wrapper.findAll('button').find((button) => button.text() === '标记已读')
    await markButton?.trigger('click')
    await flushPromises()

    expect(queue.calls[1]?.url).toBe('/api/v1/messages/9a000000-0000-4000-8000-000000000001/read')
    expect(queue.calls[1]?.init?.method).toBe('POST')
    expect(wrapper.text()).toContain('已读')
    expect(wrapper.find('[data-testid="unread-badge"]').exists()).toBe(false)
  })

  it('全部已读调用 read-all 后刷新列表', async () => {
    const queue = createFetchQueue([
      jsonResponse(page()),
      jsonResponse({ updatedCount: 1 }),
      jsonResponse(page({ unreadCount: 0, items: [{ ...message, read: true }] })),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    const readAllButton = wrapper.findAll('button').find((button) => button.text() === '全部已读')
    await readAllButton?.trigger('click')
    await flushPromises()

    expect(queue.calls[1]?.url).toBe('/api/v1/messages/read-all')
    expect(queue.calls[2]?.url).toBe('/api/v1/messages?page=1&size=20&unreadOnly=false')
    expect(wrapper.text()).toContain('已读')
  })

  it('只看未读切换后按 unreadOnly=true 重新查询', async () => {
    const queue = createFetchQueue([
      jsonResponse(page()),
      jsonResponse(page({ items: [], meta: { page: 1, size: 20, total: 0, totalPages: 0 } })),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.el-switch').trigger('click')
    await flushPromises()

    expect(queue.calls[1]?.url).toBe('/api/v1/messages?page=1&size=20&unreadOnly=true')
  })

  it('403 时展示契约错误码', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源', 'trace-msg-403'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('无权访问该资源')
    expect(wrapper.text()).toContain('AUTH_FORBIDDEN')
  })
})
