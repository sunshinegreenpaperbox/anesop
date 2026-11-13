import { expect, test, type Page } from '@playwright/test'

/**
 * M2-M2-02 停台与急诊加台 E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：
 * - 停台：打开停台对话框 → 选择原因码/填写说明 → POST `/schedules/{id}/stop`（带 version）
 *   → 刷新后状态为已取消。
 * - 急诊加台：提交遇 409 + conflicts[] → 提示可抢占择期台次 → 点击抢占后以
 *   `preemptScheduleId` 重试 → 201，刷新后急诊台次带“急诊”标识。
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

const APPROVER: SyntheticUser = {
  userId: '1000000000000000001',
  username: 'synth_or_manager',
  displayName: '演示手术室管理员（合成）',
  roles: ['SYS_OR_MANAGER'],
  permissions: ['m1:schedule:read', 'm1:schedule:write', 'm1:schedule:approve'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const WRITER: SyntheticUser = {
  userId: '1000000000000000002',
  username: 'synth_or_writer',
  displayName: '演示排程员（合成，无审批权）',
  roles: ['SYS_OR_MANAGER'],
  permissions: ['m1:schedule:read', 'm1:schedule:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const ROOM = {
  roomId: 'f2000000-0000-4000-8000-0000000000c1',
  roomNo: 'OR-01',
  roomName: '演示 1 号手术间（合成）',
  roomType: 'GENERAL',
  siteId: 'f1000000-0000-4000-8000-000000000001',
  status: 'IDLE',
  isolationRequired: false,
  equipmentTags: [],
  createdAt: '2026-11-13T08:00:00+08:00',
  createdBy: null,
  updatedAt: '2026-11-13T08:00:00+08:00',
  updatedBy: null,
  version: 1,
}

const ELECTIVE = {
  scheduleId: '80000000-0000-4000-8000-000000000001',
  applicationId: '60000000-0000-4000-8000-000000000001',
  patientId: '50000000-0000-4000-8000-000000000001',
  patientNo: 'SYNTH-HIS-P-0001',
  patientName: '演示患者',
  roomId: ROOM.roomId,
  roomNo: 'OR-01',
  sequenceNo: 1,
  plannedStart: '2026-12-03T08:00:00+08:00',
  plannedEnd: '2026-12-03T10:00:00+08:00',
  actualStart: null,
  actualEnd: null,
  status: 'PLANNED',
  priority: 'ELECTIVE',
  changeReason: null,
  createdAt: '2026-11-13T08:00:00+08:00',
  createdBy: '1000000000000000001',
  updatedAt: '2026-11-13T08:00:00+08:00',
  updatedBy: '1000000000000000001',
  version: 1,
}

const EMERGENCY = {
  ...ELECTIVE,
  scheduleId: '80000000-0000-4000-8000-000000000002',
  applicationId: '60000000-0000-4000-8000-000000000002',
  patientNo: 'SYNTH-HIS-P-0002',
  sequenceNo: 2,
  status: 'PLANNED',
  priority: 'EMERGENCY',
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

function pageBody(items: unknown[]) {
  return { items, meta: { page: 1, size: 50, total: items.length, totalPages: 1 } }
}

test.describe('M2 停台与急诊加台', () => {
  test('停台：选择原因码并提交，刷新后显示已取消', async ({ page }) => {
    await seedSession(page, APPROVER)
    await page.route('**/api/v1/scheduling/rooms', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [ROOM] }),
      }),
    )

    let listCalls = 0
    const stopBodies: Array<Record<string, unknown>> = []
    await page.route(/\/api\/v1\/scheduling\/schedules(\?.*)?$/, (route) => {
      if (route.request().method() !== 'GET') return route.fallback()
      listCalls += 1
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          pageBody([
            listCalls === 1
              ? ELECTIVE
              : {
                  ...ELECTIVE,
                  status: 'CANCELLED',
                  changeReason: '演示停台说明（合成数据）',
                  version: 2,
                },
          ]),
        ),
      })
    })
    await page.route('**/api/v1/scheduling/schedules/*/stop', async (route) => {
      stopBodies.push(JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>)
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ...ELECTIVE,
          status: 'CANCELLED',
          changeReason: '演示停台说明（合成数据）',
          version: 2,
        }),
      })
    })

    await page.goto('/m1/schedule')
    await expect(page.getByText('SYNTH-HIS-P-0001').first()).toBeVisible()
    await page.getByTestId('cancel-button').click()
    await expect(page.getByTestId('stop-reason-select')).toBeVisible()
    await page.getByTestId('stop-reason-input').fill('演示停台说明（合成数据）')
    await page.getByTestId('stop-submit').click()

    await expect.poll(() => stopBodies.length).toBe(1)
    expect(stopBodies[0]?.reasonCode).toBe('PATIENT_CONDITION')
    expect(stopBodies[0]?.reason).toBe('演示停台说明（合成数据）')
    expect(stopBodies[0]?.version).toBe(1)
    await expect(page.getByText('已取消')).toBeVisible()
    console.log(
      `[M2-M2-02][E2E-stop] reasonCode=${String(stopBodies[0]?.reasonCode)} status=CANCELLED`,
    )
  })

  test('急诊加台：409 冲突提示可抢占择期台次，抢占后 201', async ({ page }) => {
    await seedSession(page, APPROVER)
    await page.route('**/api/v1/scheduling/rooms', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [ROOM] }),
      }),
    )

    let listCalls = 0
    const emergencyBodies: Array<Record<string, unknown>> = []
    await page.route(/\/api\/v1\/scheduling\/schedules(\?.*)?$/, (route) => {
      if (route.request().method() !== 'GET') return route.fallback()
      listCalls += 1
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          pageBody(
            listCalls === 1
              ? [ELECTIVE]
              : [
                  {
                    ...ELECTIVE,
                    status: 'CANCELLED',
                    changeReason: 'EMERGENCY_PREEMPT',
                    version: 2,
                  },
                  EMERGENCY,
                ],
          ),
        ),
      })
    })
    await page.route('**/api/v1/scheduling/emergency-cases', async (route) => {
      const body = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      emergencyBodies.push(body)
      if (!body.preemptScheduleId) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SCHEDULE_CONFLICT',
            message: '排程冲突，操作未生效',
            traceId: 'SYNTH-TRACE-E2E-EMERGENCY',
            details: [{ field: 'ROOM_OVERLAP', reason: '与术间 OR-01 第 1 台时段重叠' }],
            conflicts: [
              {
                conflictType: 'ROOM_OVERLAP',
                severity: 'BLOCKING',
                message: '与术间 OR-01 第 1 台时段重叠',
                conflictingScheduleId: ELECTIVE.scheduleId,
              },
            ],
          }),
        })
      }
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          accepted: true,
          scheduleId: EMERGENCY.scheduleId,
          approvalRequired: true,
          conflicts: [],
          approval: {
            changeId: '90000000-0000-4000-8000-000000000002',
            changeType: 'EMERGENCY',
            status: 'APPROVED',
            requestedBy: APPROVER.userId,
            requestedAt: '2026-12-03T07:55:00+08:00',
            approvedBy: APPROVER.userId,
            approvedAt: '2026-12-03T07:55:00+08:00',
            preemptScheduleId: ELECTIVE.scheduleId,
          },
        }),
      })
    })

    await page.goto('/m1/schedule')
    await page.getByTestId('emergency-button').click()
    await page
      .getByTestId('emergency-application-input')
      .fill('60000000-0000-4000-8000-000000000002')
    await page.getByTestId('emergency-submit').click()

    await expect(page.getByTestId('conflict-alert')).toBeVisible()
    await expect(page.getByTestId('conflict-alert')).toContainText('ROOM_OVERLAP')
    await expect(page.getByTestId('emergency-conflicts')).toBeVisible()
    await expect(page.getByTestId('emergency-preempt-button')).toBeVisible()
    await page.getByTestId('emergency-preempt-button').click()
    // S-6：抢占需二次确认，文案包含被抢占台次信息。
    const confirmDialog = page.locator('.el-message-box')
    await expect(confirmDialog).toBeVisible()
    await expect(confirmDialog).toContainText(ELECTIVE.scheduleId)
    await page.getByRole('button', { name: '确认抢占' }).click()

    await expect.poll(() => emergencyBodies.length).toBe(2)
    expect(emergencyBodies[0]?.reasonCode).toBe('EMERGENCY_GREEN_CHANNEL')
    expect(emergencyBodies[1]?.preemptScheduleId).toBe(ELECTIVE.scheduleId)
    await expect(page.getByText('已取消').first()).toBeVisible()
    await expect(page.getByText('急诊').first()).toBeVisible()
    console.log(
      `[M2-M2-02][E2E-emergency] first=409 conflict second=201 preempt=${String(
        emergencyBodies[1]?.preemptScheduleId,
      )}`,
    )
  })

  test('术中停台无审批权限：提交返回 403 并保留台次', async ({ page }) => {
    await seedSession(page, WRITER)
    await page.route('**/api/v1/scheduling/rooms', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [ROOM] }),
      }),
    )
    await page.route(/\/api\/v1\/scheduling\/schedules(\?.*)?$/, (route) => {
      if (route.request().method() !== 'GET') return route.fallback()
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          pageBody([
            {
              ...ELECTIVE,
              status: 'IN_PROGRESS',
              actualStart: '2026-12-03T08:05:00+08:00',
            },
          ]),
        ),
      })
    })

    const stopBodies: Array<Record<string, unknown>> = []
    await page.route('**/api/v1/scheduling/schedules/*/stop', async (route) => {
      stopBodies.push(JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>)
      return route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'AUTH_FORBIDDEN',
          message: '无权访问该资源或数据范围不足',
          traceId: 'SYNTH-TRACE-E2E-STOP-403',
          details: [],
        }),
      })
    })

    await page.goto('/m1/schedule')
    await expect(page.getByText('进行中').first()).toBeVisible()
    await page.getByTestId('stop-in-progress-button').click()
    await expect(page.getByTestId('stop-approval-alert')).toContainText('m1:schedule:approve')
    await page.getByTestId('stop-submit').click()

    await expect.poll(() => stopBodies.length).toBe(1)
    expect(stopBodies[0]?.reasonCode).toBe('PATIENT_CONDITION')
    expect(stopBodies[0]?.version).toBe(1)
    await expect(page.getByText('操作失败')).toBeVisible()
    await expect(page.getByText('AUTH_FORBIDDEN')).toBeVisible()
    // 台次仍为进行中，未被误停。
    await expect(page.getByText('进行中').first()).toBeVisible()
    console.log(
      `[M2-M2-02][E2E-stop-403] reasonCode=${String(
        stopBodies[0]?.reasonCode,
      )} status=403 schedule=IN_PROGRESS`,
    )
  })
})
