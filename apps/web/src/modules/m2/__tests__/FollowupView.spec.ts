import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FollowupView from '../views/FollowupView.vue'
import { configureHttp } from '../../../api/http'
import router from '../../../router'
import { pinia } from '../../../stores'
import {
  createFetchQueue,
  errorResponse,
  jsonResponse,
  resetAppState,
  seedAuthenticatedUser,
  syntheticUser,
} from '../../../__tests__/testUtils'

enableAutoUnmount(afterEach)

const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const FOLLOWUP_ID = '1c000000-0000-4000-8000-000000000001'
const PAIN_ID = '1d000000-0000-4000-8000-000000000001'
const PATIENT_ID = '50000000-0000-4000-8000-000000000001'
const ACTOR_ID = '10000000-0000-4000-8000-000000000003'

function followup(overrides: Record<string, unknown> = {}) {
  return {
    followupId: FOLLOWUP_ID,
    recordId: RECORD_ID,
    patientId: PATIENT_ID,
    followupType: 'H24',
    assessedAt: '2026-12-02T09:00:00+08:00',
    nrsScore: 3,
    ponvScore: 1,
    complications: ['PONV', 'PAIN_INADEQUATE'],
    complicationNotes: '演示：轻度恶心，镇痛后可缓解（合成）',
    patientFeedback: '演示：疼痛可耐受，希望追加镇痛（合成）',
    followedUpBy: '演示随访护士（合成）',
    createdAt: '2026-12-02T09:05:00+08:00',
    createdBy: ACTOR_ID,
    version: 1,
    ...overrides,
  }
}

function pain(overrides: Record<string, unknown> = {}) {
  return {
    painManagementId: PAIN_ID,
    followupId: FOLLOWUP_ID,
    recordId: RECORD_ID,
    modality: 'PCIA',
    drug: '演示舒芬太尼方案（合成数据）',
    bolusDose: 2,
    backgroundDose: 1,
    lockoutMinutes: 15,
    effect: 'PARTIAL',
    adjustments: [
      {
        adjustedAt: '2026-12-02T10:30:00+08:00',
        description: '演示：追加 bolus 1 次，疼痛缓解（合成）',
      },
    ],
    createdAt: '2026-12-02T09:10:00+08:00',
    createdBy: ACTOR_ID,
    ...overrides,
  }
}

function mountView() {
  return mount(FollowupView, { global: { plugins: [pinia, router, ElementPlus] } })
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await flushPromises()
  }
  await new Promise((resolve) => setTimeout(resolve, 2))
  await flushPromises()
}

function fillFollowup(wrapper: ReturnType<typeof mountView>): void {
  wrapper
    .find('[data-testid="followup-type"]')
    .findComponent({ name: 'ElSelect' })
    .vm.$emit('update:modelValue', 'H48')
  const numbers = wrapper
    .find('[data-testid="followup-nrs"]')
    .findComponent({ name: 'ElInputNumber' })
  numbers.vm.$emit('update:modelValue', 4)
  wrapper
    .find('[data-testid="followup-ponv"]')
    .findComponent({ name: 'ElInputNumber' })
    .vm.$emit('update:modelValue', 2)
  wrapper
    .find('[data-testid="followup-complications"]')
    .findComponent({ name: 'ElCheckboxGroup' })
    .vm.$emit('update:modelValue', ['PONV', 'OTHER'])
  wrapper.find('[data-testid="followup-notes"]').setValue('演示：恶心（合成）')
  wrapper.find('[data-testid="followup-feedback"]').setValue('演示：疼痛缓解（合成）')
  wrapper.find('[data-testid="followup-by"]').setValue('演示随访医师（合成）')
}

describe('FollowupView 术后随访与镇痛（M3-M11-01）', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:followup:read', 'm2:followup:write'] }))
    await router.push(`/m2/followups/${RECORD_ID}`)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载随访与镇痛记录，展示脱敏提示', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [followup()] }),
      jsonResponse({ items: [pain()] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="followup-list"]').text()).toContain('术后 24 小时')
    expect(wrapper.find('[data-testid="followup-list"]').text()).toContain('恶心/呕吐（PONV）')
    expect(wrapper.find('[data-testid="pain-list"]').text()).toContain('PCIA（自控静脉）')
    expect(wrapper.find('[data-testid="pain-list"]').text()).toContain('部分有效')
    expect(wrapper.find('[data-testid="pain-list"]').text()).toContain('追加 bolus 1 次')
    expect(wrapper.find('[data-testid="followup-mask-hint"]').text()).toContain('服务端脱敏后落库')
    expect(wrapper.find('[data-testid="followup-error"]').exists()).toBe(false)
  })

  it('只读权限隐藏录入随访与追加镇痛入口', async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:followup:read'] }))
    await router.push(`/m2/followups/${RECORD_ID}`)
    const queue = createFetchQueue([
      jsonResponse({ items: [followup()] }),
      jsonResponse({ items: [] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()

    expect(wrapper.find('[data-testid="followup-create-open"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="pain-create-open"]').exists()).toBe(false)
  })

  it('录入随访：提交 H48/NRS/PONV/并发症/反馈并重新加载', async () => {
    const created = followup({
      followupId: '1c000000-0000-4000-8000-000000000002',
      followupType: 'H48',
      nrsScore: 4,
      ponvScore: 2,
      complications: ['PONV', 'OTHER'],
      followedUpBy: '演示随访医师（合成）',
    })
    const queue = createFetchQueue([
      jsonResponse({ items: [] }),
      jsonResponse(created, 201),
      jsonResponse({ items: [created] }),
      jsonResponse({ items: [] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    expect(wrapper.find('[data-testid="followup-empty"]').exists()).toBe(true)

    await wrapper.find('[data-testid="followup-create-open"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-testid="followup-required-hint"]').text()).toContain(
      '麻醉记录单结束后录入',
    )
    fillFollowup(wrapper)
    await settle()
    await wrapper.find('[data-testid="followup-submit"]').trigger('click')
    await settle()

    const post = queue.calls.find(
      (call) => call.url.endsWith('/v1/anesthesia/followups') && call.init?.method === 'POST',
    )
    expect(post).toBeDefined()
    expect(JSON.parse(String(post?.init?.body))).toMatchObject({
      anesthesiaRecordId: RECORD_ID,
      followupType: 'H48',
      nrsScore: 4,
      ponvScore: 2,
      complications: ['PONV', 'OTHER'],
      complicationNotes: '演示：恶心（合成）',
      patientFeedback: '演示：疼痛缓解（合成）',
      followedUpBy: '演示随访医师（合成）',
    })
    expect(wrapper.find('[data-testid="followup-list"]').text()).toContain('术后 48 小时')
    expect(wrapper.find('[data-testid="followup-empty"]').exists()).toBe(false)
  })

  it('未填写评分/随访人时不发请求', async () => {
    const queue = createFetchQueue([jsonResponse({ items: [] })])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="followup-create-open"]').trigger('click')
    await settle()
    await wrapper.find('[data-testid="followup-submit"]').trigger('click')
    await settle()

    expect(
      queue.calls.filter((call) => call.init?.method === 'POST' && call.url.endsWith('/followups')),
    ).toHaveLength(0)
    expect(queue.calls).toHaveLength(1)
  })

  it('记录单未结束时 409 展示服务端错误且不关闭对话框', async () => {
    const queue = createFetchQueue([
      jsonResponse({ items: [] }),
      errorResponse(409, 'STATE_CONFLICT', '麻醉记录单尚未结束，不能录入术后随访'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="followup-create-open"]').trigger('click')
    await settle()
    fillFollowup(wrapper)
    await settle()
    await wrapper.find('[data-testid="followup-submit"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-testid="followup-error"]').text()).toContain('STATE_CONFLICT')
    expect(wrapper.find('[data-testid="followup-error"]').text()).toContain('尚未结束')
    expect(wrapper.find('[data-testid="followup-submit"]').exists()).toBe(true)
  })

  it('镇痛参数校验（bolus>0、lockout>0）后提交并刷新列表', async () => {
    const created = pain({ painManagementId: '1d000000-0000-4000-8000-000000000002' })
    const queue = createFetchQueue([
      jsonResponse({ items: [followup()] }),
      jsonResponse({ items: [] }),
      jsonResponse(created, 201),
      jsonResponse({ items: [created] }),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await settle()
    await wrapper.find('[data-testid="pain-create-open"]').trigger('click')
    await settle()

    // 空 bolus 不提交。
    await wrapper.find('[data-testid="pain-submit"]').trigger('click')
    await settle()
    expect(
      queue.calls.filter(
        (call) => call.url.endsWith('/pain-management') && call.init?.method === 'POST',
      ),
    ).toHaveLength(0)

    const numbers = wrapper.findAllComponents({ name: 'ElInputNumber' })
    expect(numbers.length).toBe(3)
    numbers[0]?.vm.$emit('update:modelValue', 2)
    numbers[1]?.vm.$emit('update:modelValue', 1)
    numbers[2]?.vm.$emit('update:modelValue', 15)
    wrapper.find('[data-testid="pain-drug"]').setValue('演示舒芬太尼方案（合成数据）')
    wrapper.find('[data-testid="pain-adjustment-note"]').setValue('演示：追加 bolus（合成）')
    await settle()
    await wrapper.find('[data-testid="pain-submit"]').trigger('click')
    await settle()

    const post = queue.calls.find(
      (call) =>
        call.url.endsWith(`/followups/${FOLLOWUP_ID}/pain-management`) &&
        call.init?.method === 'POST',
    )
    expect(post).toBeDefined()
    expect(JSON.parse(String(post?.init?.body))).toMatchObject({
      modality: 'PCIA',
      drug: '演示舒芬太尼方案（合成数据）',
      bolusDose: 2,
      backgroundDose: 1,
      lockoutMinutes: 15,
      effect: 'NOT_ASSESSED',
    })
    expect(JSON.parse(String(post?.init?.body)).adjustments).toHaveLength(1)
    expect(wrapper.find('[data-testid="pain-list"]').text()).toContain('PCIA（自控静脉）')
  })
})
