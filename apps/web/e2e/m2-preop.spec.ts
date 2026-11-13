import { expect, test, type Page } from '@playwright/test'

/**
 * 术前访视与评估（M2-M4-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：从申请进入访视页 → 展示实时分数/风险等级/服务端结果 → 二次确认最终化 →
 * 状态锁定（最终化按钮消失）；无数据范围时展示 403 权限态。
 * 真实后端 403 + 审计由 `PreopAssessmentApiIntegrationTest` 覆盖。
 */

interface SyntheticUser {
  userId: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
  dataScopes: { type: string; scopeIds: string[] }[]
  siteIds: string[]
  mustChangePassword: boolean
}

const ANESTHESIOLOGIST: SyntheticUser = {
  userId: '1000000000000000002',
  username: 'synth_anesthesiologist',
  displayName: '演示麻醉医师（合成）',
  roles: ['SYS_ANESTHESIOLOGIST'],
  permissions: ['m2:preop:read', 'm2:preop:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const APPLICATION_ID = '60000000-0000-4000-8000-000000000001'
const ASSESSMENT_ID = '16000000-0000-4000-8000-000000000001'

const patientSnapshot = {
  patientId: '50000000-0000-4000-8000-000000000001',
  hisPatientNo: 'SYNTH-HIS-P-0001',
  patientName: '演示患者',
  gender: 'FEMALE',
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
    finalizedBy: status === 'FINALIZED' ? ANESTHESIOLOGIST.userId : null,
    createdAt: '2026-11-28T09:10:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
    updatedAt: '2026-11-28T09:10:00+08:00',
    updatedBy: ANESTHESIOLOGIST.userId,
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

async function seedSession(page: Page, user: SyntheticUser): Promise<void> {
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(user),
    }),
  )
  await page.addInitScript((value) => {
    window.sessionStorage.setItem('anesop.auth.accessToken', 'SYNTH-ACCESS-TOKEN')
    window.sessionStorage.setItem('anesop.auth.refreshToken', 'SYNTH-REFRESH-TOKEN')
    window.sessionStorage.setItem('anesop.auth.currentUser', JSON.stringify(value))
    window.sessionStorage.setItem('anesop.auth.expiresAt', String(Date.now() + 900_000))
  }, user)
}

test.describe('M2 术前访视与评估', () => {
  test('展示评分→二次确认最终化→锁定', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    const finalizeBodies: Array<Record<string, unknown>> = []

    await page.route('**/api/v1/anesthesia/preop-assessments**', async (route) => {
      const request = route.request()
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(listBody([assessment('DRAFT', 1)])),
        })
        return
      }
      if (request.url().endsWith('/finalize')) {
        finalizeBodies.push(JSON.parse(request.postData() ?? '{}') as Record<string, unknown>)
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(assessment('FINALIZED', 2)),
        })
        return
      }
      await route.fulfill({ status: 500, body: '{}' })
    })

    await page.goto(`/m2/preop/${APPLICATION_ID}`)

    await expect(page.getByTestId('application-context')).toContainText('演示患者')
    await expect(page.getByTestId('preview-rcri')).toHaveText('1')
    await expect(page.getByTestId('preview-apfel')).toHaveText('4')
    await expect(page.getByTestId('preview-stopbang')).toHaveText('4')
    await expect(page.getByTestId('preview-dasi')).toHaveText('18.95')
    await expect(page.getByTestId('risk-level')).toContainText('高危')
    await expect(page.getByTestId('assessment-status')).toHaveText('草稿')
    await expect(page.getByTestId('server-scores')).toContainText('RCRI 1')

    await page.getByTestId('finalize-button').click()
    await expect(page.getByText('最终化后访视单不可修改')).toBeVisible()
    await page.getByRole('button', { name: '确认最终化' }).click()

    await expect(page.getByTestId('assessment-status')).toHaveText('已最终化')
    await expect(page.getByTestId('finalize-button')).toHaveCount(0)
    await expect(page.getByTestId('save-button')).toBeDisabled()
    expect(finalizeBodies).toEqual([{ version: 1 }])
  })

  test('无数据范围时展示 403 权限态', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    await page.route('**/api/v1/anesthesia/preop-assessments**', (route) =>
      route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'AUTH_FORBIDDEN',
          message: '无权访问该资源或数据范围不足',
          traceId: 'SYNTH-E2E-TRACE',
          details: [],
        }),
      }),
    )

    await page.goto(`/m2/preop/${APPLICATION_ID}`)

    await expect(page.getByTestId('preop-error')).toContainText('无权访问术前访视')
    await expect(page.getByTestId('preop-error')).toContainText('m2:preop:read')
    await expect(page.getByTestId('scores-panel')).toHaveCount(0)
    await expect(page.getByTestId('save-button')).toHaveCount(0)
  })
})
