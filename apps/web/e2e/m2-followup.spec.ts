import { expect, test, type Page } from '@playwright/test'

/**
 * 术后随访与镇痛（M3-M11-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：空态 → 录入 H48 随访（NRS/PONV/并发症/说明/反馈/随访人）→ 列表与详情展示
 * （脱敏提示）→ 追加镇痛记录（PCIA + bolus/background/lockout + 调整说明）→ 镇痛列表。
 * 真实 409（记录单未结束）、400（评分/镇痛参数越界）、append-only 55000、权限 403/404 与
 * 审计脱敏由 `FollowupPainApiIntegrationTest` 覆盖。
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
  permissions: ['m2:record:read', 'm2:record:write', 'm2:followup:read', 'm2:followup:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const FOLLOWUP_ID = '1c000000-0000-4000-8000-000000000001'
const PAIN_ID = '1d000000-0000-4000-8000-000000000001'
const PATIENT_ID = '50000000-0000-4000-8000-000000000001'

async function seedSession(page: Page, user: SyntheticUser): Promise<void> {
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(user) }),
  )
  await page.addInitScript((value) => {
    window.sessionStorage.setItem('anesop.auth.accessToken', 'SYNTH-ACCESS-TOKEN')
    window.sessionStorage.setItem('anesop.auth.refreshToken', 'SYNTH-REFRESH-TOKEN')
    window.sessionStorage.setItem('anesop.auth.currentUser', JSON.stringify(value))
    window.sessionStorage.setItem('anesop.auth.expiresAt', String(Date.now() + 900_000))
  }, user)
}

test.describe('M3 术后随访与镇痛', () => {
  test('录入 24 小时随访并追加 PCIA 镇痛记录', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)

    const followups: Record<string, unknown>[] = []
    const painRecords: Record<string, unknown>[] = []

    await page.route('**/api/v1/anesthesia/followups**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname
      const method = request.method()
      const json = (body: unknown, status = 200) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

      if (method === 'GET' && path.endsWith('/followups')) {
        return json({ items: followups })
      }
      if (method === 'POST' && path.endsWith('/followups')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        const created = {
          followupId: FOLLOWUP_ID,
          recordId: RECORD_ID,
          patientId: PATIENT_ID,
          followupType: body.followupType,
          assessedAt: body.assessedAt,
          nrsScore: body.nrsScore,
          ponvScore: body.ponvScore,
          complications: body.complications,
          complicationNotes: body.complicationNotes ?? null,
          patientFeedback: body.patientFeedback ?? null,
          followedUpBy: body.followedUpBy,
          createdAt: '2026-12-03T09:00:00+08:00',
          createdBy: ANESTHESIOLOGIST.userId,
          version: 1,
        }
        followups.unshift(created)
        return json(created, 201)
      }
      if (method === 'GET' && path.endsWith('/pain-management')) {
        return json({ items: painRecords })
      }
      if (method === 'POST' && path.endsWith('/pain-management')) {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        const created = {
          painManagementId: PAIN_ID,
          followupId: FOLLOWUP_ID,
          recordId: RECORD_ID,
          modality: body.modality,
          drug: body.drug,
          bolusDose: body.bolusDose,
          backgroundDose: body.backgroundDose,
          lockoutMinutes: body.lockoutMinutes,
          effect: body.effect,
          adjustments: body.adjustments ?? [],
          createdAt: '2026-12-03T10:00:00+08:00',
          createdBy: ANESTHESIOLOGIST.userId,
        }
        painRecords.push(created)
        return json(created, 201)
      }
      return json(
        {
          code: 'RESOURCE_NOT_FOUND',
          message: '资源不存在或不可见',
          traceId: 'trace-e2e',
          details: [],
        },
        404,
      )
    })

    await page.goto(`/m2/followups/${RECORD_ID}`)
    await expect(page.getByTestId('followup-empty')).toBeVisible()
    await expect(page.getByTestId('followup-mask-hint')).toHaveCount(0)

    // 录入随访：无评分/随访人时不提交（先点一次确认空态仍在）。
    await page.getByTestId('followup-create-open').click()
    await expect(page.getByTestId('followup-required-hint')).toContainText('麻醉记录单结束后录入')
    await page.getByTestId('followup-submit').click()
    await expect(page.getByTestId('followup-empty')).toBeVisible()

    await page.getByTestId('followup-type').click()
    await page.getByRole('option', { name: '术后 48 小时', exact: true }).click()
    await page.getByTestId('followup-nrs').locator('input').fill('4')
    await page.getByTestId('followup-ponv').locator('input').fill('2')
    await page.getByText('恶心/呕吐（PONV）', { exact: true }).click()
    await page.getByTestId('followup-notes').fill('演示：轻度恶心（合成）')
    await page.getByTestId('followup-feedback').fill('演示：疼痛可耐受（合成）')
    await page.getByTestId('followup-by').fill('演示随访医师（合成）')
    await page.getByTestId('followup-submit').click()

    await expect(page.getByTestId('followup-list')).toContainText('术后 48 小时')
    await expect(page.getByTestId('followup-list')).toContainText('恶心/呕吐（PONV）')
    await expect(page.getByTestId('followup-mask-hint')).toContainText('服务端脱敏后落库')

    // 追加镇痛：PCIA + bolus/background/lockout + 调整说明。
    await page.getByTestId('pain-create-open').click()
    await page.getByTestId('pain-drug').fill('演示舒芬太尼方案（合成数据）')
    await page.getByTestId('pain-bolus').locator('input').fill('2')
    await page.getByTestId('pain-background').locator('input').fill('1')
    await page.getByTestId('pain-lockout').locator('input').fill('15')
    await page.getByTestId('pain-adjustment-note').fill('演示：追加 bolus 1 次（合成）')
    await page.getByTestId('pain-submit').click()

    await expect(page.getByTestId('pain-list')).toContainText('PCIA（自控静脉）')
    await expect(page.getByTestId('pain-list')).toContainText('演示舒芬太尼方案（合成数据）')
    await expect(page.getByTestId('pain-list')).toContainText('追加 bolus 1 次')
  })
})
