import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PreopAssessmentView from '../views/PreopAssessmentView.vue'
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
const ASSESSMENT_ID = '16000000-0000-4000-8000-000000000001'

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

const scoreInput = {
  rcri: {
    highRiskSurgery: true,
    ischemicHeartDisease: false,
    congestiveHeartFailure: false,
    cerebrovascularDisease: false,
    insulinDependentDiabetes: false,
    renalImpairment: false,
  },
  apfel: {
    female: true,
    ponvOrMotionSicknessHistory: true,
    nonSmoker: true,
    postoperativeOpioid: true,
  },
  stopBang: {
    snoring: true,
    tiredness: true,
    observedApnea: false,
    hypertension: true,
    bmiOver35: false,
    ageOver50: true,
    neckCircumferenceOver40: false,
    male: false,
  },
  dasiActivities: [
    'SELF_CARE',
    'WALK_INDOORS',
    'WALK_BLOCK',
    'CLIMB_STAIRS',
    'LIGHT_HOUSEHOLD_WORK',
    'MODERATE_HOUSEHOLD_WORK',
  ],
}

function assessment(status: 'DRAFT' | 'FINALIZED', version: number) {
  return {
    assessmentId: ASSESSMENT_ID,
    applicationId: APPLICATION_ID,
    patientId: patientSnapshot.patientId,
    patientSnapshot,
    historySummary: '演示病史（高血压，规律服药；合成数据）',
    asaClass: 'II',
    airwayAssessment: {
      mallampati: 'II',
      mouthOpeningCm: 4.5,
      cervicalSpineMobility: 'NORMAL',
      notes: '演示气道评估（合成数据）',
    },
    scoreInput,
    scores: {
      rcriScore: 1,
      rcriRisk: 'MODERATE',
      apfelScore: 4,
      apfelRisk: 'HIGH',
      stopBangScore: 4,
      stopBangRisk: 'MODERATE',
      dasiScore: 18.95,
      functionalCapacityLow: false,
      riskLevel: 'HIGH',
      calculatedAt: '2026-11-28T09:10:00+08:00',
      calculatorVersion: '1.0.0',
    },
    plan: {
      planType: 'GENERAL',
      airwayPlan: '气管插管（合成示例）',
      notes: '演示麻醉计划（合成数据）',
    },
    status,
    finalizedAt: status === 'FINALIZED' ? '2026-11-28T10:00:00+08:00' : null,
    finalizedBy: status === 'FINALIZED' ? '1000000000000000001' : null,
    createdAt: '2026-11-28T09:10:00+08:00',
    createdBy: '1000000000000000002',
    updatedAt: '2026-11-28T09:10:00+08:00',
    updatedBy: '1000000000000000002',
    version,
  }
}

function listBody(items: unknown[]) {
  return {
    application: {
      applicationId: APPLICATION_ID,
      patientId: patientSnapshot.patientId,
      patientSnapshot,
      surgeryName: '演示腹腔镜胆囊切除术（合成）',
      departmentName: '演示手术科室（合成）',
      status: 'SCHEDULED',
    },
    items,
  }
}

function mountView() {
  return mount(PreopAssessmentView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
}

describe('PreopAssessmentView 术前访视', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser(syntheticUser({ permissions: ['m2:preop:read', 'm2:preop:write'] }))
    await router.push(`/m2/preop/${APPLICATION_ID}`)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetAppState()
  })

  it('加载申请与访视单，展示实时分数、风险等级与服务端结果', async () => {
    const queue = createFetchQueue([jsonResponse(listBody([assessment('DRAFT', 1)]))])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    expect(queue.calls[0]?.url).toBe(
      `/api/v1/anesthesia/preop-assessments?applicationId=${APPLICATION_ID}`,
    )
    expect(wrapper.find('[data-testid="application-context"]').text()).toContain('演示患者')
    expect(wrapper.find('[data-testid="assessment-status"]').text()).toBe('草稿')
    expect(wrapper.find('[data-testid="preview-rcri"]').text()).toBe('1')
    expect(wrapper.find('[data-testid="preview-apfel"]').text()).toBe('4')
    expect(wrapper.find('[data-testid="preview-stopbang"]').text()).toBe('4')
    expect(wrapper.find('[data-testid="preview-dasi"]').text()).toBe('18.95')
    expect(wrapper.find('[data-testid="risk-level"]').text()).toContain('高危')
    expect(wrapper.find('[data-testid="server-scores"]').text()).toContain('RCRI 1')
    expect(wrapper.find('[data-testid="save-button"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="finalize-button"]').exists()).toBe(true)
  })

  it('最终化二次确认后调用服务端并锁定，隐藏最终化按钮', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const queue = createFetchQueue([
      jsonResponse(listBody([assessment('DRAFT', 1)])),
      jsonResponse(assessment('FINALIZED', 2)),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="finalize-button"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(queue.calls[1]?.url).toBe(
      `/api/v1/anesthesia/preop-assessments/${ASSESSMENT_ID}/finalize`,
    )
    expect(queue.calls[1]?.init?.method).toBe('POST')
    expect(JSON.parse(String(queue.calls[1]?.init?.body))).toMatchObject({ version: 1 })
    expect(wrapper.find('[data-testid="assessment-status"]').text()).toBe('已最终化')
    expect(wrapper.find('[data-testid="finalize-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="save-button"]').attributes('disabled')).toBeDefined()
  })

  it('保存失败后重新保存成功时清除错误条', async () => {
    const queue = createFetchQueue([
      jsonResponse(listBody([assessment('DRAFT', 1)])),
      errorResponse(409, 'STATE_CONFLICT', '访视单已被修改或已最终化', 'trace-409'),
      jsonResponse(assessment('DRAFT', 2)),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="save-button"]').trigger('click')
    await flushPromises()

    expect(queue.calls[1]?.url).toBe(`/api/v1/anesthesia/preop-assessments/${ASSESSMENT_ID}`)
    expect(queue.calls[1]?.init?.method).toBe('PUT')
    expect(wrapper.find('[data-testid="preop-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="preop-error"]').text()).toContain('STATE_CONFLICT')

    await wrapper.find('[data-testid="save-button"]').trigger('click')
    await flushPromises()

    expect(queue.calls[2]?.init?.method).toBe('PUT')
    expect(wrapper.find('[data-testid="preop-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="assessment-status"]').text()).toBe('草稿')
  })

  it('无 m2:preop:read 时展示 403 权限态且不渲染表单', async () => {
    const queue = createFetchQueue([
      errorResponse(403, 'AUTH_FORBIDDEN', '无权访问该资源或数据范围不足'),
    ])
    configureHttp({ fetchImpl: queue.fetchImpl })

    const wrapper = mountView()
    await flushPromises()

    const alert = wrapper.find('[data-testid="preop-error"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('无权访问术前访视')
    expect(alert.text()).toContain('m2:preop:read')
    expect(wrapper.find('[data-testid="save-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="scores-panel"]').exists()).toBe(false)
  })
})
