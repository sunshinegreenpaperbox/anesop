import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConsentView from '../views/ConsentView.vue'
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

const APPLICATION_ID = '60000000-0000-4000-8000-000000000001'
const CONSENT_ID = '17000000-0000-4000-8000-000000000001'

const patientSnapshot = {
  patientId: '50000000-0000-4000-8000-000000000001',
  hisPatientNo: 'SYNTH-HIS-P-0001',
  patientName: '演示患者',
  gender: 'FEMALE' as const,
  birthDate: '1972-05-18',
  age: 54,
  departmentName: '演示手术科室（合成）',
  ward: '演示病区（合成）',
  bedNo: '01',
}

const application = {
  applicationId: APPLICATION_ID,
  patientId: patientSnapshot.patientId,
  patientSnapshot,
  surgeryName: '演示腹腔镜胆囊切除术（合成）',
  departmentName: '演示手术科室（合成）',
  status: 'SCHEDULED',
}

const templates = {
  items: [
    {
      templateId: 'b1000000-0000-4000-8000-000000000002',
      templateCode: 'ANES_GENERAL',
      version: 2,
      title: '麻醉知情同意书（演示模板 v2）',
      contentText: '【SYNTHETIC 演示模板】患者已了解麻醉风险并同意实施。',
      effectiveAt: '2026-11-01T00:00:00+08:00',
      createdAt: '2026-11-13T00:00:00+08:00',
      createdBy: null,
    },
  ],
}

function record(status: 'WAITING_SIGN' | 'SIGNED' | 'DECLINED' | 'REVOKED', version: number) {
  const signed = status === 'SIGNED' || status === 'REVOKED'
  return {
    consentId: CONSENT_ID,
    applicationId: APPLICATION_ID,
    patientId: patientSnapshot.patientId,
    templateId: 'b1000000-0000-4000-8000-000000000002',
    templateCode: 'ANES_GENERAL',
    templateVersion: 2,
    templateTitle: '麻醉知情同意书（演示模板 v2）',
    status,
    signerName: '演示患者',
    signerRelation: 'SELF',
    signMethod: 'ELECTRONIC',
    signedAt: signed ? '2026-12-01T08:30:00+08:00' : null,
    signedBy: signed ? 'e0000000-0000-4000-8000-000000000002' : null,
    signatureRef: signed ? `SIM-CA-${CONSENT_ID}` : null,
    signatureHash: signed ? 'a'.repeat(64) : null,
    caStatus: signed ? 'SIMULATED' : 'PENDING',
    declineReason: status === 'DECLINED' ? '演示原因：患者要求暂缓手术（合成数据）' : null,
    declinedAt: status === 'DECLINED' ? '2026-12-01T08:25:00+08:00' : null,
    revokedAt: status === 'REVOKED' ? '2026-12-01T09:00:00+08:00' : null,
    revokedBy: status === 'REVOKED' ? 'e0000000-0000-4000-8000-000000000002' : null,
    revokeReason: status === 'REVOKED' ? '演示原因：患者术前改变意愿（合成数据）' : null,
    createdAt: '2026-12-01T08:20:00+08:00',
    createdBy: 'e0000000-0000-4000-8000-000000000002',
    updatedAt: '2026-12-01T08:20:00+08:00',
    updatedBy: 'e0000000-0000-4000-8000-000000000002',
    version,
  }
}

function listBody(items: unknown[]) {
  return { application, items }
}

function mountView() {
  return mount(ConsentView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
}

describe('ConsentView 知情同意', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:consent:read', 'm2:consent:write'] }))
    await router.push(`/m2/consent/${APPLICATION_ID}`)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载申请、模板与同意记录，展示待签署状态与 CA 占位标识', async () => {
    const queue = createFetchQueue([
      jsonResponse(templates),
      jsonResponse(listBody([record('SIGNED', 2)])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe('/api/v1/anesthesia/consent-templates')
    expect(queue.calls[1]?.url).toBe(`/api/v1/anesthesia/consents?applicationId=${APPLICATION_ID}`)
    expect(wrapper.find('[data-testid="application-context"]').text()).toContain('演示患者')
    expect(wrapper.find('[data-testid="consent-status"]').text()).toBe('已签署')
    expect(wrapper.find('[data-testid="ca-status"]').text()).toContain('SIMULATED')
    expect(wrapper.find('[data-testid="template-content"]').text()).toContain('SYNTHETIC')
    expect(wrapper.find('[data-testid="sign-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="revoke-button"]').exists()).toBe(true)
  })

  it('创建同意记录并提交模板版本与签署人信息', async () => {
    const queue = createFetchQueue([
      jsonResponse(templates),
      jsonResponse(listBody([])),
      jsonResponse(record('WAITING_SIGN', 1)),
      jsonResponse(templates),
      jsonResponse(listBody([record('WAITING_SIGN', 1)])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="signer-name"]').setValue('演示患者')
    await wrapper.find('[data-testid="create-consent"]').trigger('click')
    await flushPromises()

    expect(queue.calls[2]?.url).toBe('/api/v1/anesthesia/consents')
    expect(queue.calls[2]?.init?.method).toBe('POST')
    expect(JSON.parse(String(queue.calls[2]?.init?.body))).toMatchObject({
      applicationId: APPLICATION_ID,
      templateId: 'b1000000-0000-4000-8000-000000000002',
      templateVersion: 2,
      signerName: '演示患者',
      signerRelation: 'SELF',
      signMethod: 'ELECTRONIC',
    })
    expect(wrapper.find('[data-testid="consent-status"]').text()).toBe('待签署')
    expect(wrapper.find('[data-testid="sign-button"]').exists()).toBe(true)
  })

  it('签署二次确认后调用服务端，显示 SIMULATED 占位签名', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const queue = createFetchQueue([
      jsonResponse(templates),
      jsonResponse(listBody([record('WAITING_SIGN', 1)])),
      jsonResponse(record('SIGNED', 2)),
      jsonResponse(templates),
      jsonResponse(listBody([record('SIGNED', 2)])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="sign-button"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(queue.calls[2]?.url).toBe(`/api/v1/anesthesia/consents/${CONSENT_ID}/sign`)
    expect(JSON.parse(String(queue.calls[2]?.init?.body))).toEqual({ version: 1 })
    expect(wrapper.find('[data-testid="consent-status"]').text()).toBe('已签署')
    expect(wrapper.find('[data-testid="ca-status"]').text()).toContain('EXT-05')
    expect(wrapper.find('[data-testid="revoke-button"]').exists()).toBe(true)
  })

  it('撤销已签署同意时要求填写原因并提交 version', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const prompt = vi
      .spyOn(ElMessageBox, 'prompt')
      .mockResolvedValue({ value: '演示原因：患者术前改变意愿（合成数据）' } as never)
    const queue = createFetchQueue([
      jsonResponse(templates),
      jsonResponse(listBody([record('SIGNED', 2)])),
      jsonResponse(record('REVOKED', 3)),
      jsonResponse(templates),
      jsonResponse(listBody([record('REVOKED', 3)])),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="revoke-button"]').trigger('click')
    await flushPromises()

    expect(prompt).toHaveBeenCalledTimes(1)
    expect(queue.calls[2]?.url).toBe(`/api/v1/anesthesia/consents/${CONSENT_ID}/revoke`)
    expect(JSON.parse(String(queue.calls[2]?.init?.body))).toEqual({
      version: 2,
      reason: '演示原因：患者术前改变意愿（合成数据）',
    })
    expect(wrapper.find('[data-testid="consent-status"]').text()).toBe('已撤销')
  })

  it('无 m2:consent:read 时展示 403 权限态且不渲染表单', async () => {
    const forbidden = () => errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足')
    const queue = createFetchQueue([forbidden(), forbidden()])
    configureHttp({ fetchImpl: queue.fetchImpl })

    seedAuthenticatedUser(syntheticUser({ permissions: [] }))
    const wrapper = mountView()
    await flushPromises()

    const alert = wrapper.find('[data-testid="consent-error"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('无权访问知情同意')
    expect(alert.text()).toContain('m2:consent:read')
    expect(wrapper.find('[data-testid="create-consent"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="consent-records"]').exists()).toBe(false)
  })
})
