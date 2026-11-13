import { expect, test, type Page } from '@playwright/test'

/**
 * E2E-01 排程冲突（M2-M2-01）：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 场景：工作台加载日排程 → 新建台次提交 → 服务端 409 + conflicts[] → 页面展示冲突类型与
 * 说明 → 改选空闲术间再次提交 → 成功刷新。断言请求体、冲突提示与最终台次数量。
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

const WRITE_USER: SyntheticUser = {
  userId: '1000000000000000001',
  username: 'synth_or_manager',
  displayName: '演示手术室管理员（合成）',
  roles: ['SYS_OR_MANAGER'],
  permissions: ['m1:schedule:read', 'm1:schedule:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const READ_ONLY_USER: SyntheticUser = {
  ...WRITE_USER,
  userId: '1000000000000000002',
  username: 'synth_surgeon',
  displayName: '演示外科医师（合成）',
  roles: ['SYS_SURGEON'],
  permissions: ['m1:schedule:read'],
}

const ROOM_ONE = {
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

const ROOM_TWO = {
  ...ROOM_ONE,
  roomId: 'f2000000-0000-4000-8000-0000000000c2',
  roomNo: 'OR-02',
  roomName: '演示 2 号杂交手术间（合成）',
  roomType: 'HYBRID',
}

const EXISTING = {
  scheduleId: '80000000-0000-4000-8000-000000000001',
  applicationId: '60000000-0000-4000-8000-000000000001',
  patientId: '50000000-0000-4000-8000-000000000001',
  patientNo: 'SYNTH-HIS-P-0001',
  patientName: '演示患者',
  roomId: ROOM_ONE.roomId,
  roomNo: 'OR-01',
  sequenceNo: 1,
  plannedStart: '2026-12-02T08:00:00+08:00',
  plannedEnd: '2026-12-02T10:00:00+08:00',
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

test.describe('M2 排程工作台（E2E-01 冲突）', () => {
  test('制造冲突→提示→改术间解决', async ({ page }) => {
    await seedSession(page, WRITE_USER)
    await page.route('**/api/v1/scheduling/rooms', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [ROOM_ONE, ROOM_TWO] }),
      }),
    )

    let listCalls = 0
    const createBodies: Array<Record<string, unknown>> = []
    await page.route(/\/api\/v1\/scheduling\/schedules(\?.*)?$/, async (route) => {
      if (route.request().method() === 'GET') {
        listCalls += 1
        const items =
          listCalls === 1
            ? [EXISTING]
            : [
                EXISTING,
                {
                  ...EXISTING,
                  scheduleId: '80000000-0000-4000-8000-000000000002',
                  roomId: ROOM_TWO.roomId,
                  roomNo: 'OR-02',
                  sequenceNo: 2,
                },
              ]
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageBody(items)),
        })
      }
      const body = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      createBodies.push(body)
      if (createBodies.length === 1) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SCHEDULE_CONFLICT',
            message: '排程冲突，操作未生效',
            traceId: 'SYNTH-TRACE-CONFLICT',
            details: [{ field: 'ROOM_OVERLAP', reason: '与术间 OR-01 第 1 台时段重叠' }],
            conflicts: [
              {
                conflictType: 'ROOM_OVERLAP',
                severity: 'BLOCKING',
                message: '与术间 OR-01 第 1 台时段重叠',
                conflictingScheduleId: EXISTING.scheduleId,
              },
            ],
          }),
        })
      }
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          ...EXISTING,
          scheduleId: '80000000-0000-4000-8000-000000000002',
          roomId: ROOM_TWO.roomId,
          roomNo: 'OR-02',
          sequenceNo: 2,
        }),
      })
    })

    await page.goto('/m1/schedule')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('排程工作台')
    await expect(page.getByText('SYNTH-HIS-P-0001').first()).toBeVisible()
    await expect(page.getByTestId('room-column')).toHaveCount(2)

    await page.getByTestId('create-schedule-button').click()
    await page.getByTestId('application-input').fill('SYNTH-APP-9001')
    await page.getByTestId('submit-button').click()

    await expect(page.getByTestId('conflict-alert')).toBeVisible()
    await expect(page.getByTestId('conflict-alert')).toContainText('ROOM_OVERLAP')
    await expect(page.getByTestId('conflict-alert')).toContainText('与术间 OR-01 第 1 台时段重叠')
    expect(createBodies[0]?.roomId).toBe(ROOM_ONE.roomId)

    await page.getByTestId('room-select').click()
    await page.locator('.el-select-dropdown__item').filter({ hasText: 'OR-02' }).click()
    await page.getByTestId('submit-button').click()

    await expect(page.getByTestId('conflict-alert')).toHaveCount(0)
    await expect(page.getByTestId('schedule-row')).toHaveCount(2)
    await expect(page.getByText('OR-02', { exact: true })).toBeVisible()
    expect(createBodies).toHaveLength(2)
    expect(createBodies[1]?.roomId).toBe(ROOM_TWO.roomId)
    console.log(
      `[E2E-01] first=409/ROOM_OVERLAP second=201 room=${String(createBodies[1]?.roomId)} rows=2`,
    )
  })

  test('只读权限不显示新建/调整/取消按钮', async ({ page }) => {
    await seedSession(page, READ_ONLY_USER)
    await page.route('**/api/v1/scheduling/rooms', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [ROOM_ONE, ROOM_TWO] }),
      }),
    )
    await page.route(/\/api\/v1\/scheduling\/schedules(\?.*)?$/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageBody([EXISTING])),
      }),
    )

    await page.goto('/m1/schedule')

    await expect(page.getByText('SYNTH-HIS-P-0001')).toBeVisible()
    await expect(page.getByTestId('create-schedule-button')).toHaveCount(0)
    await expect(page.getByTestId('adjust-button')).toHaveCount(0)
    await expect(page.getByTestId('cancel-button')).toHaveCount(0)
  })
})
