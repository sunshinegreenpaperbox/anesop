import { expect, test, type Page } from '@playwright/test'

/**
 * 质控不良事件闭环页（M4-M17-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：上报（REPORTED）→ 提交 RCA（RCA）→ 复核（RECTIFYING）→ 整改闭环（CLOSED），
 * 并断言每次状态前移真实进入请求体（阶段 1 rectificationCompleted=false、阶段 2 =true）。
 * 指标手算/状态机 409/append-only/导出脱敏由 `M4QcApiIntegrationTest` 与 `QcServiceImplTest` 覆盖。
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

const QC_USER: SyntheticUser = {
  userId: '1000000000000000011',
  username: 'synth_qc_manager',
  displayName: '演示质控专员（合成）',
  roles: ['SYS_QC_MANAGER'],
  permissions: ['m4:qc:read', 'm4:qc:write', 'm4:qc:review', 'm4:qc:export'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const DEPT_ANES = 'f1000000-0000-4000-8000-000000000002'
const EVENT_ID = '7d000000-0000-4000-8000-000000000001'
const EVENT_NO = 'AE-20260112-0000a1'
const RCA_ID = '7e000000-0000-4000-8000-000000000001'

interface EventState {
  status: 'REPORTED' | 'RCA' | 'RECTIFYING' | 'CLOSED'
}

function eventBody(state: EventState) {
  return {
    id: EVENT_ID,
    eventNo: EVENT_NO,
    eventType: 'MEDICATION',
    severity: 'LEVEL_II',
    occurredAt: '2026-01-12T10:30:00+08:00',
    deptId: DEPT_ANES,
    deptName: '麻醉科（合成）',
    scheduleId: null,
    description: '术中一过性血压下降（合成，已脱敏）',
    reporterName: 'synth_qc_manager',
    anonymous: false,
    status: state.status,
    rcaId: state.status === 'REPORTED' ? null : RCA_ID,
    version: state.status === 'REPORTED' ? 1 : 2,
    createdAt: '2026-01-12T03:05:00Z',
  }
}

function rcaBody(state: EventState, rectificationCompleted: boolean) {
  const closed = state.status === 'CLOSED'
  return {
    id: RCA_ID,
    eventId: EVENT_ID,
    eventNo: EVENT_NO,
    timeline: [
      {
        occurredAt: '2026-01-12T10:30:00+08:00',
        description: '诱导后 3 分钟血压下降（合成）',
      },
    ],
    rootCause: '诱导期容量评估不足（合成）',
    correctiveAction: '修订容量评估清单（合成）',
    responsibleName: '演示责任人（合成）',
    dueDate: '2026-02-15',
    status: closed ? 'CLOSED' : 'OPEN',
    reviewerName: state.status === 'RCA' ? null : '演示复核人（合成）',
    reviewedAt: state.status === 'RCA' ? null : '2026-01-15T02:00:00Z',
    closedAt: closed ? '2026-01-16T02:00:00Z' : null,
    createdAt: '2026-01-13T02:00:00Z',
    eventStatus: state.status,
    rectificationCompleted,
  }
}

/** 输入框定位：兼容 data-testid 落在原生 input/textarea 或 Element Plus 包装元素上。 */
function field(page: Page, testId: string) {
  return page
    .locator(
      `[data-testid="${testId}"]:is(input, textarea), ` +
        `[data-testid="${testId}"] input, [data-testid="${testId}"] textarea`,
    )
    .first()
}

async function seedSession(page: Page): Promise<void> {
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(QC_USER) }),
  )
  await page.addInitScript((value) => {
    window.sessionStorage.setItem('anesop.auth.accessToken', 'SYNTH-ACCESS-TOKEN')
    window.sessionStorage.setItem('anesop.auth.refreshToken', 'SYNTH-REFRESH-TOKEN')
    window.sessionStorage.setItem('anesop.auth.currentUser', JSON.stringify(value))
    window.sessionStorage.setItem('anesop.auth.expiresAt', String(Date.now() + 900_000))
  }, QC_USER)
}

test.describe('M4-M17-01 不良事件 RCA 闭环', () => {
  test('上报 → RCA → 复核 → 整改闭环（状态逐级前进）', async ({ page }) => {
    await seedSession(page)
    const state: EventState = { status: 'REPORTED' }
    const listStatuses: string[] = []
    const closeBodies: boolean[] = []

    await page.route(/\/api\/v1\/qc\/adverse-events\?/, (route) => {
      listStatuses.push(state.status)
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [eventBody(state)],
          meta: { page: 1, size: 20, total: 1, totalPages: 1 },
        }),
      })
    })
    await page.route(/\/api\/v1\/qc\/adverse-events\/[^/]+\/rca$/, (route) => {
      state.status = 'RCA'
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(rcaBody(state, false)),
      })
    })
    await page.route(/\/api\/v1\/qc\/rca\/[^/]+\/close$/, (route) => {
      const body = route.request().postDataJSON() as { rectificationCompleted?: boolean }
      const completed = body.rectificationCompleted === true
      closeBodies.push(completed)
      state.status = completed ? 'CLOSED' : 'RECTIFYING'
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(rcaBody(state, completed)),
      })
    })

    await page.goto('/m4/qc/adverse-events')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('不良事件登记与 RCA 闭环')
    await expect(page.getByTestId(`status-${EVENT_NO}`)).toHaveText('已上报')

    // 1) 提交 RCA → 根因分析中
    await page.getByTestId(`rca-${EVENT_NO}`).click()
    await expect(page.getByTestId('rca-dialog')).toBeVisible()
    await field(page, 'root-cause').fill('诱导期容量评估不足（合成）')
    await field(page, 'corrective-action').fill('修订容量评估清单并纳入培训（合成）')
    await field(page, 'responsible').fill('演示责任人（合成）')
    await field(page, 'timeline-desc-0').fill('诱导后 3 分钟血压下降（合成）')
    await page.getByTestId('rca-submit').click()
    await expect(page.getByTestId(`status-${EVENT_NO}`)).toHaveText('根因分析中')

    // 2) 复核 → 整改中（阶段 1，rectificationCompleted=false）
    await page.getByTestId(`review-${EVENT_NO}`).click()
    await expect(page.getByTestId('review-dialog')).toBeVisible()
    await field(page, 'reviewer').fill('演示复核人（合成）')
    await field(page, 'conclusion').fill('根因与整改措施认可（合成）')
    await page.getByTestId('review-submit').click()
    await expect(page.getByTestId(`status-${EVENT_NO}`)).toHaveText('整改中')

    // 3) 整改闭环 → 已闭环（阶段 2，rectificationCompleted=true）
    await page.getByTestId(`close-${EVENT_NO}`).click()
    await expect(page.getByTestId('close-dialog')).toBeVisible()
    await page.getByTestId('rectification-completed').click()
    await field(page, 'close-reviewer').fill('演示复核人（合成）')
    await field(page, 'close-conclusion').fill('整改完成（合成）')
    await page.getByTestId('close-submit').click()
    await expect(page.getByTestId(`status-${EVENT_NO}`)).toHaveText('已闭环')

    // 状态机逐级前进，且两次闭环请求体区分阶段。
    expect(listStatuses).toEqual(
      expect.arrayContaining(['REPORTED', 'RCA', 'RECTIFYING', 'CLOSED']),
    )
    expect(closeBodies).toEqual([false, true])
    console.log(
      `[M4-M17-01][e2e] role=SYS_QC_MANAGER listStatuses=${listStatuses.join(
        '>',
      )} closeStages=${closeBodies.join(',')}`,
    )
  })
})
