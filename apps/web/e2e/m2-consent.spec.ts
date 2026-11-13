import { expect, test, type Page } from '@playwright/test'

/**
 * 知情同意与三方核查（M2-M5-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：模板展示 → 创建待签署 → 二次确认签署（CA 占位 SIMULATED）→ 撤销入口；
 * 三方核查录入 Time-out + Sign-out → 已完成；真实 403 + 审计由
 * `ConsentThreeWayApiIntegrationTest` 覆盖。
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
  permissions: ['m2:consent:read', 'm2:consent:write', 'm2:threeway:read', 'm2:threeway:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const APPLICATION_ID = '60000000-0000-4000-8000-000000000001'
const CONSENT_ID = '17000000-0000-4000-8000-000000000001'
const TEMPLATE_ID = 'b1000000-0000-4000-8000-000000000002'

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

const application = {
  applicationId: APPLICATION_ID,
  patientId: patientSnapshot.patientId,
  patientSnapshot,
  surgeryName: '演示腹腔镜胆囊切除术（合成）',
  departmentName: '演示手术科室（合成）',
  status: 'SCHEDULED',
}

const template = {
  templateId: TEMPLATE_ID,
  templateCode: 'ANES_GENERAL',
  version: 2,
  title: '麻醉知情同意书（演示模板 v2）',
  contentText: '【SYNTHETIC 演示模板】患者已了解麻醉风险并同意实施。',
  effectiveAt: '2026-11-01T00:00:00+08:00',
  createdAt: '2026-11-13T00:00:00+08:00',
  createdBy: null,
}

function consentRecord(status: 'WAITING_SIGN' | 'SIGNED', version: number) {
  const signed = status === 'SIGNED'
  return {
    consentId: CONSENT_ID,
    applicationId: APPLICATION_ID,
    patientId: patientSnapshot.patientId,
    templateId: TEMPLATE_ID,
    templateCode: 'ANES_GENERAL',
    templateVersion: 2,
    templateTitle: template.title,
    status,
    signerName: '演示患者',
    signerRelation: 'SELF',
    signMethod: 'ELECTRONIC',
    signedAt: signed ? '2026-12-01T08:30:00+08:00' : null,
    signedBy: signed ? ANESTHESIOLOGIST.userId : null,
    signatureRef: signed ? `SIM-CA-${CONSENT_ID}` : null,
    signatureHash: signed ? 'a'.repeat(64) : null,
    caStatus: signed ? 'SIMULATED' : 'PENDING',
    declineReason: null,
    declinedAt: null,
    revokedAt: null,
    revokedBy: null,
    revokeReason: null,
    createdAt: '2026-12-01T08:20:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
    updatedAt: '2026-12-01T08:20:00+08:00',
    updatedBy: ANESTHESIOLOGIST.userId,
    version,
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

test.describe('M2 知情同意与三方核查', () => {
  test('创建同意→二次确认签署→CA 占位标识', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    let current = null as ReturnType<typeof consentRecord> | null

    await page.route('**/api/v1/anesthesia/consent-templates**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [template] }),
      }),
    )
    await page.route('**/api/v1/anesthesia/consents**', async (route) => {
      const request = route.request()
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ application, items: current ? [current] : [] }),
        })
        return
      }
      if (request.url().endsWith('/sign')) {
        current = consentRecord('SIGNED', 2)
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(current),
        })
        return
      }
      current = consentRecord('WAITING_SIGN', 1)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(current),
      })
    })

    await page.goto(`/m2/consent/${APPLICATION_ID}`)

    await expect(page.getByTestId('application-context')).toContainText('演示患者')
    await expect(page.getByTestId('template-content')).toContainText('SYNTHETIC')
    await page.getByTestId('signer-name').fill('演示患者')
    await page.getByTestId('create-consent').click()

    await expect(page.getByTestId('consent-status')).toHaveText('待签署')
    await page.getByTestId('sign-button').click()
    await expect(page.locator('.el-message-box__message')).toContainText('CA 不可用（EXT-05）')
    await page.getByRole('button', { name: '确认签署' }).click()

    await expect(page.getByTestId('consent-status')).toHaveText('已签署')
    await expect(page.getByTestId('ca-status')).toContainText('SIMULATED')
    await expect(page.getByTestId('revoke-button')).toBeVisible()
  })

  test('三方核查 Time-out + Sign-out 标记完成', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    let completed = false
    let version = 0

    await page.route('**/api/v1/anesthesia/applications/*/three-way-checks', async (route) => {
      const request = route.request()
      if (request.method() === 'GET') {
        const items = []
        if (version >= 1) {
          items.push({
            checkId: '18000000-0000-4000-8000-000000000001',
            applicationId: APPLICATION_ID,
            timeOutAt: '2026-12-01T08:50:00+08:00',
            timeOutBy: ANESTHESIOLOGIST.userId,
            signOutAt: completed ? '2026-12-01T10:55:00+08:00' : null,
            signOutBy: completed ? ANESTHESIOLOGIST.userId : null,
            specimenChecked: true,
            implantChecked: true,
            issues: [],
            createdAt: '2026-12-01T08:52:00+08:00',
            createdBy: ANESTHESIOLOGIST.userId,
            updatedAt: '2026-12-01T08:52:00+08:00',
            updatedBy: ANESTHESIOLOGIST.userId,
            version,
          })
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ application, completed, items }),
        })
        return
      }
      const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
      version += 1
      completed = typeof body.signOutAt === 'string'
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          checkId: `18000000-0000-4000-8000-00000000000${version}`,
          applicationId: APPLICATION_ID,
          timeOutAt: body.timeOutAt,
          timeOutBy: ANESTHESIOLOGIST.userId,
          signOutAt: body.signOutAt ?? null,
          signOutBy: body.signOutAt ? ANESTHESIOLOGIST.userId : null,
          specimenChecked: body.specimenChecked,
          implantChecked: body.implantChecked,
          issues: body.issues ?? [],
          createdAt: '2026-12-01T08:52:00+08:00',
          createdBy: ANESTHESIOLOGIST.userId,
          updatedAt: '2026-12-01T08:52:00+08:00',
          updatedBy: ANESTHESIOLOGIST.userId,
          version,
        }),
      })
    })

    await page.goto(`/m2/three-way/${APPLICATION_ID}`)
    await expect(page.getByTestId('threeway-completed')).toHaveText('未完成')

    await page.getByTestId('specimen-checked').click()
    await page.getByTestId('implant-checked').click()
    await page.getByTestId('signout-enabled').click()
    await page.getByTestId('record-check').click()
    await page.getByRole('button', { name: '确认提交' }).click()

    await expect(page.getByTestId('threeway-completed')).toHaveText('已完成')
    await expect(page.getByTestId('threeway-versions')).toContainText('v1')
    await expect(page.getByTestId('threeway-versions')).toContainText('含 Sign-out')
  })
})
