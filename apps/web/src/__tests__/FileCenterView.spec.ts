import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import FileCenterView from '../modules/m0/views/FileCenterView.vue'
import { configureHttp } from '../api/http'
import { pinia } from '../stores'
import {
  createFetchQueue,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from './testUtils'

const FILE_PAGE = {
  items: [
    {
      fileId: '8b000000-0000-4000-8000-000000000001',
      fileName: 'file-8b000000-0000-4000-8000-000000000001.csv',
      contentType: 'text/csv',
      sizeBytes: 43,
      sha256: 'a'.repeat(64),
      businessType: 'TEMPLATE',
      businessId: null,
      uploaderId: 'e0000000-0000-4000-8000-000000000001',
      scanStatus: 'NOT_SCANNED',
      createdAt: '2026-11-15T18:30:00+08:00',
    },
  ],
  meta: { page: 1, size: 20, total: 1, totalPages: 1 },
}

describe('FileCenterView 文件管理', () => {
  beforeEach(() => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m0:file:read', 'm0:file:write'] }))
  })

  afterEach(() => {
    resetAppState()
  })

  it('加载“我的文件”并只展示服务端生成文件名', async () => {
    const queue = createFetchQueue([jsonResponse(FILE_PAGE)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(FileCenterView, { global: { plugins: [pinia, ElementPlus] } })
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/files?page=1&size=20')
    expect(wrapper.find('[data-testid="my-files-table"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('file-8b000000-0000-4000-8000-000000000001.csv')
    expect(wrapper.text()).toContain('TEMPLATE')
  })

  it('空列表展示空态且不报错', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [], meta: { page: 1, size: 20, total: 0, totalPages: 0 } }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(FileCenterView, { global: { plugins: [pinia, ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('暂无上传记录')
    expect(wrapper.find('.files__error').exists()).toBe(false)
  })
})
