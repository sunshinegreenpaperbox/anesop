import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import DictManageView from '../modules/m0/views/DictManageView.vue'
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

const dictType = {
  type: 'ASA',
  name: 'ASA 分级',
  description: '美国麻醉医师协会体格状态分级',
  itemCount: 2,
  updatedAt: '2026-11-15T09:00:00+08:00',
}

const dictItems = {
  type: 'ASA',
  name: 'ASA 分级',
  items: [
    {
      itemId: 'd0000000-0000-4000-8000-000000000001',
      type: 'ASA',
      code: 'I',
      name: 'ASA I 级',
      sort: 10,
      enabled: true,
      remark: '健康患者',
      updatedAt: '2026-11-15T09:00:00+08:00',
    },
    {
      itemId: 'd0000000-0000-4000-8000-000000000002',
      type: 'ASA',
      code: 'II',
      name: 'ASA II 级',
      sort: 20,
      enabled: false,
      remark: null,
      updatedAt: '2026-11-15T09:00:00+08:00',
    },
  ],
}

function mountView() {
  return mount(DictManageView, { global: { plugins: [pinia, router, ElementPlus] } })
}

describe('DictManageView 字典管理', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ roles: ['SYS_ADMIN'], permissions: ['m0:dict:read', 'm0:dict:write'] }),
    )
    await router.push('/m0/dicts')
  })

  afterEach(() => {
    resetAppState()
  })

  it('加载字典类型与启用项并展示编码/名称/状态', async () => {
    const queue = createFetchQueue([jsonResponse({ items: [dictType] }), jsonResponse(dictItems)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(queue.calls.map((call) => call.url)).toEqual(['/api/v1/dicts', '/api/v1/dicts/ASA'])
    expect(wrapper.text()).toContain('字典管理')
    expect(wrapper.text()).toContain('ASA I 级')
    expect(wrapper.text()).toContain('启用')
    expect(wrapper.text()).toContain('健康患者')
  })

  it('管理员可勾选含停用项并按 includeDisabled 重新查询', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [dictType] }),
      jsonResponse(dictItems),
      jsonResponse(dictItems),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.el-checkbox__original').setValue(true)
    await flushPromises()

    expect(queue.calls[2]?.url).toBe('/api/v1/dicts/ASA?includeDisabled=true')
    expect(wrapper.text()).toContain('停用')
  })

  it('非管理员不显示维护入口且不请求停用项', async () => {
    seedAuthenticatedUser(
      syntheticUser({ roles: ['SYS_ANESTHESIOLOGIST'], permissions: ['m0:dict:read'] }),
    )
    const queue = createFetchQueue([jsonResponse({ items: [dictType] }), jsonResponse(dictItems)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).not.toContain('新增字典项')
    expect(wrapper.text()).not.toContain('编辑')
    expect(queue.calls[1]?.url).toBe('/api/v1/dicts/ASA')
  })

  it('403 时展示契约错误码与追踪号', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源', 'trace-dict-403'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('无权访问该资源')
    expect(wrapper.text()).toContain('AUTH_FORBIDDEN')
    expect(wrapper.text()).toContain('trace-dict-403')
  })

  it('编辑字典项保存时调用 PUT 且编码不可修改', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [dictType] }),
      jsonResponse(dictItems),
      jsonResponse({ ...dictItems.items[0], name: 'ASA I 级（修订）' }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    const editButton = wrapper.findAll('button').find((button) => button.text() === '编辑')
    expect(editButton).toBeDefined()
    await editButton?.trigger('click')
    await flushPromises()

    const codeInput = wrapper.find('.el-dialog input')
    expect((codeInput.element as HTMLInputElement).disabled).toBe(true)

    const saveButton = wrapper.findAll('button').find((button) => button.text() === '保存')
    await saveButton?.trigger('click')
    await flushPromises()

    const put = queue.calls[2]
    expect(put?.url).toBe('/api/v1/dicts/ASA/items/d0000000-0000-4000-8000-000000000001')
    expect(put?.init?.method).toBe('PUT')
    expect(JSON.parse(String(put?.init?.body))).toMatchObject({ name: 'ASA I 级', sort: 10 })
  })
})
