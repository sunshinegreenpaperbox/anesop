import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AlertRuleConfigView from '../views/AlertRuleConfigView.vue'
import { configureHttp } from '../../../api/http'
import router from '../../../router'
import { pinia } from '../../../stores'
import {
  createFetchQueue,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from '../../../__tests__/testUtils'

const rule = {
  ruleId: '1b000000-0000-4000-8000-000000000001',
  code: 'SPO2_LOW',
  name: '血氧饱和度过低（演示阈值【待确认】）',
  metric: 'SPO2' as const,
  operator: 'LT' as const,
  thresholdLow: 90,
  thresholdHigh: null,
  durationSeconds: 0,
  severity: 'CRITICAL' as const,
  version: 1,
  enabled: true,
  pendingConfirmation: true,
  createdAt: '2026-12-01T00:00:00+08:00',
  createdBy: '10000000-0000-4000-8000-000000000001',
  updatedAt: '2026-12-01T00:00:00+08:00',
  updatedBy: '10000000-0000-4000-8000-000000000001',
}

const etco2Rule = {
  ...rule,
  ruleId: '1b000000-0000-4000-8000-000000000005',
  code: 'ETCO2_RANGE',
  name: '呼气末二氧化碳超出区间（演示【待确认】）',
  metric: 'ETCO2' as const,
  operator: 'OUT_OF_RANGE' as const,
  thresholdLow: 30,
  thresholdHigh: 50,
  durationSeconds: 30,
  severity: 'WARN' as const,
}

function mountView() {
  return mount(AlertRuleConfigView, { global: { plugins: [pinia, router, ElementPlus] } })
}

describe('AlertRuleConfigView 预警规则配置', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:alert:configure'] }))
    await router.push('/m2/alert-rules')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载规则并展示阈值/版本/启用状态', async () => {
    const queue = createFetchQueue([jsonResponse({ items: [rule, etco2Rule] })])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/anesthesia/alert-rules')
    const text = wrapper.find('[data-testid="rules-table"]').text()
    expect(text).toContain('SPO2_LOW')
    expect(text).toContain('< 90')
    expect(text).toContain('30 – 50')
    expect(text).toContain('v1')
    expect(text).toContain('启用')
    expect(wrapper.find('[data-testid="rule-pending-SPO2_LOW"]').text()).toContain('待确认')
    expect(wrapper.find('[data-testid="rules-no-permission"]').exists()).toBe(false)
  })

  it('配置规则生成新版本并提交 version 与阈值', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [rule] }),
      jsonResponse({
        ...rule,
        ruleId: '1b000000-0000-4000-8000-000000000011',
        thresholdLow: 88,
        version: 2,
      }),
      jsonResponse({ items: [{ ...rule, thresholdLow: 88, version: 2 }] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="rule-edit-SPO2_LOW"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="rule-threshold-low"] input').setValue('88')
    await wrapper.find('[data-testid="rule-duration"] input').setValue('15')
    await wrapper.find('[data-testid="rule-name"]').setValue('血氧过低（院内确认版）')
    await wrapper.find('[data-testid="rule-save"]').trigger('click')
    await flushPromises()

    const putCall = queue.calls[1]
    expect(putCall?.url).toBe(`/api/v1/anesthesia/alert-rules/${rule.ruleId}`)
    expect(putCall?.init?.method).toBe('PUT')
    const body = JSON.parse(String(putCall?.init?.body)) as Record<string, unknown>
    expect(body).toMatchObject({
      name: '血氧过低（院内确认版）',
      thresholdLow: 88,
      durationSeconds: 15,
      severity: 'CRITICAL',
      enabled: true,
      version: 1,
    })
    expect(queue.calls[2]?.url).toBe('/api/v1/anesthesia/alert-rules')
    expect(wrapper.find('[data-testid="rules-table"]').text()).toContain('< 88')
  })

  it('版本过期 409 时展示冲突提示且不静默覆盖', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [rule] }),
      jsonResponse(
        {
          code: 'STATE_CONFLICT',
          message: '预警规则已更新，请刷新后重试',
          traceId: 'trace-rule-409',
          details: [],
        },
        409,
      ),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="rule-edit-SPO2_LOW"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="rule-save"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="rules-error"]').text()).toContain('规则已被他人更新')
    expect(wrapper.find('[data-testid="rules-error"]').text()).toContain('trace-rule-409')
  })

  it('无 configure 权限时只读并提示', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:alert:read'] }))
    const queue = createFetchQueue([jsonResponse({ items: [rule] })])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="rules-no-permission"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-edit-SPO2_LOW"]').exists()).toBe(false)
  })
})
