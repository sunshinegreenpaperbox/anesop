import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FileUploadPanel from '../modules/m0/components/FileUploadPanel.vue'
import { configureHttp } from '../api/http'
import { pinia } from '../stores'
import {
  createFetchQueue,
  errorResponse,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from './testUtils'

const meta = {
  fileId: '8b000000-0000-4000-8000-000000000001',
  fileName: '科室排班模板（合成）.csv',
  contentType: 'text/csv' as const,
  sizeBytes: 12,
  sha256: 'a'.repeat(64),
  businessType: 'TEMPLATE' as const,
  businessId: null,
  uploaderId: 'e0000000-0000-4000-8000-000000000001',
  scanStatus: 'NOT_SCANNED' as const,
  createdAt: '2026-11-15T18:30:00+08:00',
}

function fileOf(content: string, name = '模板.csv', type = 'text/csv'): File {
  return new File([content], name, { type })
}

async function selectFile(wrapper: ReturnType<typeof mount>, file: File): Promise<void> {
  const input = wrapper.find('[data-testid="file-input"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}

describe('FileUploadPanel 文件上传组件', () => {
  beforeEach(() => {
    resetAppState()
    seedAuthenticatedUser(
      syntheticUser({ roles: ['SYS_ADMIN'], permissions: ['m0:file:read', 'm0:file:write'] }),
    )
  })

  afterEach(() => {
    resetAppState()
  })

  it('超过 20MB 的文件在客户端即拒绝，不发起请求', async () => {
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(FileUploadPanel, { global: { plugins: [pinia, ElementPlus] } })
    const oversized = fileOf('x')
    Object.defineProperty(oversized, 'size', { value: 20 * 1024 * 1024 + 1 })
    await selectFile(wrapper, oversized)
    await flushPromises()

    expect(wrapper.text()).toContain('文件大小超出上限')
    expect(queue.calls).toHaveLength(0)
  })

  it('类型不在白名单时拒绝上传', async () => {
    const queue = createFetchQueue([])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(FileUploadPanel, { global: { plugins: [pinia, ElementPlus] } })
    await selectFile(wrapper, fileOf('MZ', '影像.dcm', 'application/dicom'))
    await flushPromises()

    expect(wrapper.text()).toContain('文件类型不在允许范围')
    expect(queue.calls).toHaveLength(0)
  })

  it('合法 CSV 上传后调用 POST 并回显摘要', async () => {
    const queue = createFetchQueue([jsonResponse(meta, 201)])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(FileUploadPanel, {
      props: { businessType: 'TEMPLATE' },
      global: { plugins: [pinia, ElementPlus] },
    })
    await selectFile(wrapper, fileOf('room,shift\n'))
    await flushPromises()

    const submit = wrapper.find('[data-testid="file-upload-submit"]')
    await submit.trigger('click')
    // FileReader 读 Base64 是宏任务：用 waitFor 等请求发出，不依赖固定次数 flush。
    await vi.waitFor(() => expect(queue.calls.length).toBe(1))
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/files')
    expect(queue.calls[0]?.init?.method).toBe('POST')
    const body = JSON.parse(String(queue.calls[0]?.init?.body))
    expect(body.fileName).toBe('模板.csv')
    expect(body.contentType).toBe('text/csv')
    expect(body.businessType).toBe('TEMPLATE')
    expect(wrapper.find('[data-testid="file-upload-result"]').exists()).toBe(true)
    expect(wrapper.emitted('uploaded')?.[0]).toEqual([meta])
  })

  it('服务端 422 时展示契约错误文案', async () => {
    const queue = createFetchQueue([
      errorResponse(422, 'BUSINESS_RULE_VIOLATION', '文件大小超出上限（20MB）', 'trace-file-422'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mount(FileUploadPanel, { global: { plugins: [pinia, ElementPlus] } })
    await selectFile(wrapper, fileOf('x'))
    await flushPromises()
    await wrapper.find('[data-testid="file-upload-submit"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('文件大小超出上限（20MB）'))

    expect(wrapper.text()).toContain('文件大小超出上限（20MB）')
    expect(wrapper.find('[data-testid="file-upload-result"]').exists()).toBe(false)
  })
})
