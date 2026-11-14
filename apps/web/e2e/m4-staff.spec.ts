import { expect, test, type Page } from '@playwright/test'

/**
 * 人员分级授权 → 排班（M4-M13-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：选择人员 → 授予授权（append-only 新版本）→ 授权校验通过 → 新建排班成功 →
 * 同一班次再次排班被 409 拒绝并展示冲突明细。真实 append-only 55000、数据范围 403/404、
 * 审计脱敏与授权校验矩阵由 `M4StaffApiIntegrationTest` 与单测覆盖；本用例只验证页面可完成
 * 业务流程。
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

const ADMIN: SyntheticUser = {
  userId: '10000000-0000-4000-8000-000000000001',
  username: 'synth_staff_admin',
  displayName: '演示管理员（合成）',
  roles: ['SYS_ADMIN'],
  permissions: ['m4:staff:read', 'm4:staff:write', 'm4:schedule:manage', 'm4:training:manage'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const STAFF_ID = '3a000000-0000-4000-8000-000000000001'
const DEPARTMENT_ID = 'f1000000-0000-4000-8000-000000000002'
const ROOM_ID = 'f2000000-0000-4000-8000-000000000001'

function staffRecord() {
  return {
    staffId: STAFF_ID,
    userId: '10000000-0000-4000-8000-000000000012',
    staffNo: 'STAFF-ANES-001',
    name: '演示麻醉医师甲（合成）',
    departmentId: DEPARTMENT_ID,
    departmentName: '麻醉科（合成）',
    title: '主任医师',
    jobGrade: '正高',
    employmentStatus: 'ACTIVE',
    specialties: ['心血管麻醉'],
    qualifications: [],
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: ADMIN.userId,
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: ADMIN.userId,
    version: 1,
  }
}

function authorization(version: number) {
  return {
    authorizationId: `5a000000-0000-4000-8000-00000000000${version}`,
    staffId: STAFF_ID,
    version,
    level: 'R3',
    surgeryScopes: ['GENERAL'],
    ageMin: 18,
    ageMax: 90,
    asaMax: 'ASA3',
    validFrom: '2026-11-01',
    validTo: '2027-10-31',
    status: 'ACTIVE',
    approvedBy: ADMIN.userId,
    approvedAt: '2026-11-14T08:00:00+08:00',
    changeReason: '年度授权复评（SYNTHETIC）',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: ADMIN.userId,
  }
}

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

test.describe('M4 人员分级授权与排班', () => {
  test('授予授权 → 授权校验通过 → 排班创建与冲突提示', async ({ page }) => {
    await seedSession(page, ADMIN)

    let currentAuthorization = authorization(1)
    let scheduleCreated = false
    const schedules: Record<string, unknown>[] = []

    // 通用人员路由（具体子路径在其后注册，Playwright 后注册优先）。
    await page.route('**/api/v1/staff**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const json = (body: unknown, status = 200) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
      if (request.method() === 'GET' && url.pathname.endsWith('/staff')) {
        return json({
          items: [staffRecord()],
          page: 1,
          size: 200,
          total: 1,
          totalPages: 1,
        })
      }
      return json(
        { code: 'RESOURCE_NOT_FOUND', message: '资源不存在', traceId: 'trace-e2e', details: [] },
        404,
      )
    })

    await page.route('**/api/v1/staff/departments', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [{ departmentId: DEPARTMENT_ID, code: 'DEPT-ANES', name: '麻醉科（合成）' }],
        }),
      }),
    )

    await page.route('**/api/v1/staff/*/authorizations', async (route) => {
      const request = route.request()
      if (request.method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            staffId: STAFF_ID,
            current: currentAuthorization,
            history: [currentAuthorization],
          }),
        })
      }
      const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
      currentAuthorization = {
        ...authorization(Number(body.version) + 1),
        level: String(body.level ?? 'R3'),
        surgeryScopes: (body.surgeryScopes as string[]) ?? ['GENERAL'],
        ageMin: Number(body.ageMin ?? 18),
        ageMax: Number(body.ageMax ?? 90),
        asaMax: String(body.asaMax ?? 'ASA3'),
        validFrom: String(body.validFrom ?? '2026-11-01'),
        validTo: String(body.validTo ?? '2027-10-31'),
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          staffId: STAFF_ID,
          current: currentAuthorization,
          history: [currentAuthorization],
        }),
      })
    })

    await page.route('**/api/v1/staff/authorization-checks', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          allowed: true,
          reasons: [],
          level: 'R3',
          authorizationId: currentAuthorization.authorizationId,
          validUntil: '2027-10-31',
          checkedAt: '2026-11-14T10:00:00+08:00',
        }),
      }),
    )

    await page.route('**/api/v1/staff/schedules**', async (route) => {
      const request = route.request()
      const json = (body: unknown, status = 200) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
      if (request.method() === 'POST') {
        if (scheduleCreated) {
          return json(
            {
              code: 'STATE_CONFLICT',
              message: '状态或版本冲突，操作未生效',
              traceId: 'trace-e2e-conflict',
              details: [
                {
                  field: 'staffId',
                  reason:
                    '该人员 2026-11-20 白班已有排班（排班标识 6a000000-0000-4000-8000-000000000009）',
                },
              ],
            },
            409,
          )
        }
        scheduleCreated = true
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        const created = {
          scheduleId: '6a000000-0000-4000-8000-000000000009',
          staffId: body.staffId,
          staffNo: 'STAFF-ANES-001',
          scheduleDate: body.scheduleDate,
          shift: body.shift,
          roomId: body.roomId,
          departmentId: body.departmentId,
          status: 'PLANNED',
          note: body.note,
          createdAt: '2026-11-14T08:00:00+08:00',
          createdBy: ADMIN.userId,
          updatedAt: '2026-11-14T08:00:00+08:00',
          updatedBy: ADMIN.userId,
          version: 1,
        }
        schedules.push(created)
        return json(created, 201)
      }
      return json({ items: schedules })
    })

    // 1) 分级授权：选择人员 → 授予/复评写入新版本。
    await page.goto('/m4/staff/authorizations')
    await page.getByTestId('auth-staff-select').click()
    await page.getByRole('option', { name: /STAFF-ANES-001/ }).click()
    await expect(page.getByTestId('auth-current')).toContainText('R3')
    await page.getByTestId('auth-scopes').fill('GENERAL')
    await page.getByTestId('auth-approver').fill('10000000-0000-4000-8000-000000000009')
    await page.getByTestId('auth-valid-from').fill('2026-11-01')
    await page.getByTestId('auth-valid-to').fill('2027-10-31')
    await page.getByTestId('auth-reason').fill('E2E 授权复评（SYNTHETIC）')
    await page.getByTestId('auth-grant').click()
    await expect(page.getByTestId('auth-history')).toContainText('2')
    await expect(page.getByTestId('auth-current')).toContainText('2027-10-31')

    // 2) 授权校验：规则判定通过（页面提示不替代临床决策）。
    await page.getByTestId('auth-check-run').click()
    await expect(page.getByTestId('auth-check-result')).toContainText('授权通过')
    await expect(page.getByTestId('auth-check-result')).toContainText('临床判断')

    // 3) 排班：新建白班成功。
    await page.goto('/m4/staff/schedules')
    await page.getByTestId('sched-create').click()
    await page.getByTestId('sched-date').fill('2026-11-20')
    await page.getByTestId('sched-room').fill(ROOM_ID)
    await page.getByTestId('sched-submit').click()
    await expect(page.getByTestId('sched-table')).toContainText('2026-11-20')
    await expect(page.getByTestId('sched-table')).toContainText('白班')

    // 4) 同一人员同一日期同一班次再次排班 → 409 + 冲突明细。
    await page.getByTestId('sched-create').click()
    await page.getByTestId('sched-date').fill('2026-11-20')
    await page.getByTestId('sched-submit').click()
    await expect(page.getByTestId('sched-error')).toContainText('冲突')
    await expect(page.getByTestId('sched-conflicts')).toContainText('staffId')
    await expect(page.getByTestId('sched-conflicts')).toContainText('已有排班')
  })
})
