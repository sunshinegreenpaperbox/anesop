import { expect, test, type Page } from '@playwright/test'

/**
 * E2E-05 麻精药品双人核对页面流程（M8-QA-01）：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖：账号 A（麻醉医师/管理员）添加麻精用药 → 未选核对人/同人提交被前端拦截并提示 →
 * 从 `/v1/auth/users` 候选下拉选择第二核对人（账号 B）→ 提交成功、给药人与核对人同时留痕 →
 * 账号 B 重新登录打开同一记录单可见该用药与双人核对信息（只读审计视角，两名用户身份均出现）。
 * 服务端“存在/在职/非本人/持 m2:record:write”规则与失败审计由
 * `AnesthesiaRecordApiIntegrationTest` 覆盖；本用例只验证页面流程与提示，不宣称替代后端校验。
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
  username: 'synth_admin',
  displayName: '演示麻醉医师（合成）',
  roles: ['SYS_ADMIN'],
  permissions: [
    'm0:user:read',
    'm2:record:read',
    'm2:record:write',
    'm2:alert:read',
    'm2:rescue:write',
  ],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const SECOND_CHECKER: SyntheticUser = {
  userId: '10000000-0000-4000-8000-000000000002',
  username: 'synth_checker',
  displayName: '演示核对护士（合成）',
  roles: ['SYS_NURSE_ANESTHETIST'],
  permissions: ['m2:record:read', 'm2:record:write'],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const SCHEDULE_ID = '90000000-0000-4000-8000-000000000001'
const RECORD_ID = '12000000-0000-4000-8000-000000000001'
const DRUG_ID = '14000000-0000-4000-8000-000000000002'
const DRUG_NAME = '瑞芬太尼（演示）'
const ADMINISTERED_AT = '2026-12-01T09:10:00+08:00'

const usersPage = {
  items: [
    {
      userId: ANESTHESIOLOGIST.userId,
      username: ANESTHESIOLOGIST.username,
      displayName: ANESTHESIOLOGIST.displayName,
      status: 'ACTIVE',
      phoneMasked: null,
      roles: ANESTHESIOLOGIST.roles,
      mustChangePassword: false,
      createdAt: '2026-11-13T09:00:00+08:00',
    },
    {
      userId: SECOND_CHECKER.userId,
      username: SECOND_CHECKER.username,
      displayName: SECOND_CHECKER.displayName,
      status: 'ACTIVE',
      phoneMasked: null,
      roles: SECOND_CHECKER.roles,
      mustChangePassword: false,
      createdAt: '2026-11-13T09:00:00+08:00',
    },
    {
      userId: '10000000-0000-4000-8000-000000000009',
      username: 'synth_disabled',
      displayName: '演示停用用户（合成）',
      status: 'DISABLED',
      phoneMasked: null,
      roles: ['SYS_ANESTHESIOLOGIST'],
      mustChangePassword: false,
      createdAt: '2026-11-13T09:00:00+08:00',
    },
  ],
  meta: { page: 1, size: 200, total: 3, totalPages: 1 },
}

type RecordBody = {
  recordId: string
  scheduleId: string
  patientId: string
  patientNo: string
  patientName: string
  status: 'DRAFT' | 'IN_PROGRESS'
  anesthesiaType: string
  startedAt: string | null
  endedAt: string | null
  events: unknown[]
  vitalSigns: unknown[]
  threeWayCheckCompleted: boolean
  createdAt: string
  createdBy: string
  updatedAt: string
  updatedBy: string
  version: number
}

type DrugBody = {
  drugId: string
  recordId: string
  drugName: string
  dose: number
  unit: string
  route: string
  administeredAt: string
  narcotic: boolean
  secondCheckerId: string | null
  secondCheckerName: string | null
  note: string | null
  createdAt: string
  createdBy: string
}

function newRecord(): RecordBody {
  return {
    recordId: RECORD_ID,
    scheduleId: SCHEDULE_ID,
    patientId: '50000000-0000-4000-8000-000000000001',
    patientNo: 'SYNTH-P-0001',
    patientName: '演示患者',
    status: 'IN_PROGRESS',
    anesthesiaType: 'GENERAL',
    startedAt: '2026-12-01T08:40:00+08:00',
    endedAt: null,
    events: [],
    vitalSigns: [],
    threeWayCheckCompleted: true,
    createdAt: '2026-12-01T08:35:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
    updatedAt: '2026-12-01T09:12:00+08:00',
    updatedBy: ANESTHESIOLOGIST.userId,
    version: 4,
  }
}

function dualCheckedDrug(): DrugBody {
  return {
    drugId: DRUG_ID,
    recordId: RECORD_ID,
    drugName: DRUG_NAME,
    dose: 10,
    unit: 'mg',
    route: 'IV',
    administeredAt: ADMINISTERED_AT,
    narcotic: true,
    secondCheckerId: SECOND_CHECKER.userId,
    secondCheckerName: SECOND_CHECKER.displayName,
    note: null,
    createdAt: '2026-12-01T09:11:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
  }
}

const dictionaryDrug = {
  drugId: '2a000000-0000-4000-8000-000000000003',
  code: 'DRUG-REM-001',
  name: DRUG_NAME,
  genericName: '瑞芬太尼注射液（演示）',
  specification: '1mg/1ml',
  unit: 'mg',
  dosageForm: 'INJECTION',
  category: 'OPIOID',
  narcotic: true,
  highRisk: false,
  aliases: [],
  status: 'ACTIVE',
  createdAt: '2026-11-14T08:00:00+08:00',
  createdBy: ANESTHESIOLOGIST.userId,
  updatedAt: '2026-11-14T08:00:00+08:00',
  updatedBy: ANESTHESIOLOGIST.userId,
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

/** 记录单 + 明细的通用拦截；drugs 由调用方提供可变数组（POST 后刷新可见）。 */
async function routeRecord(page: Page, drugs: DrugBody[]): Promise<void> {
  const record = newRecord()
  await page.route('**/api/v1/anesthesia/records**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (method === 'GET' && path.endsWith('/records')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [record] }),
      })
      return
    }
    if (method === 'GET' && path.endsWith('/events')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      })
      return
    }
    if (path.endsWith('/drugs')) {
      if (method === 'POST') {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        const drug: DrugBody = {
          ...dualCheckedDrug(),
          drugName: String(body.drugName),
          dose: Number(body.dose),
          unit: String(body.unit),
          route: String(body.route),
          administeredAt: String(body.administeredAt),
          narcotic: Boolean(body.narcotic),
          secondCheckerId: body.secondCheckerId ? String(body.secondCheckerId) : null,
          secondCheckerName: body.secondCheckerName ? String(body.secondCheckerName) : null,
          note: body.note ? String(body.note) : null,
        }
        drugs.push(drug)
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(drug),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: drugs }),
      })
      return
    }
    if (path.endsWith('/fluids')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [] }),
      })
      return
    }
    if (path.endsWith('/io-summary')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          recordId: RECORD_ID,
          intakeMl: 0,
          outputMl: 0,
          balanceMl: 0,
          fluidCount: 0,
          computedAt: '2026-12-01T10:30:00+08:00',
        }),
      })
      return
    }
    if (path.endsWith('/effective')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          recordId: RECORD_ID,
          locked: false,
          lockedAt: null,
          lockedBy: null,
          status: 'IN_PROGRESS',
          currentRevision: 0,
          original: { anesthesiaType: 'GENERAL', startedAt: record.startedAt, endedAt: null },
          effective: { anesthesiaType: 'GENERAL', startedAt: record.startedAt, endedAt: null },
          corrections: [],
          signatureRef: null,
          signatureHash: null,
          caStatus: null,
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [] }),
    })
  })
}

async function routeDictionary(page: Page): Promise<void> {
  await page.route('**/api/v1/drugs/search**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [dictionaryDrug] }),
    }),
  )
}

test.describe('E2E-05 麻精药品双人核对', () => {
  test('未选/同人核对被拦截，选择第二核对人后提交成功并留痕', async ({ page }) => {
    await seedSession(page, ANESTHESIOLOGIST)
    await page.route('**/api/v1/auth/users**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(usersPage),
      }),
    )
    await routeDictionary(page)
    const drugs: DrugBody[] = []
    let drugPosts = 0
    let usersRequests = 0
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/auth/users')) usersRequests += 1
      if (request.url().endsWith('/drugs') && request.method() === 'POST') drugPosts += 1
    })
    await routeRecord(page, drugs)

    await page.goto(`/m2/records/${SCHEDULE_ID}`)
    await expect(page.getByTestId('record-context')).toContainText('演示患者')

    // 添加麻精用药：字典联想选中后麻精标志只读显示“是”。
    await page.getByTestId('drug-name').fill(DRUG_NAME)
    await page.locator('.el-autocomplete-suggestion li').first().click()
    await expect(page.getByTestId('drug-narcotic')).toHaveClass(/is-checked/)
    await expect(page.getByTestId('drug-second-checker')).toBeVisible()

    // 未选核对人：阻止提交并提示。
    await page.getByTestId('record-drug').click()
    await expect(page.locator('.el-message').last()).toContainText('双人核对')
    expect(drugPosts).toBe(0)

    // 同人核对：候选下拉选中本人仍被阻止并提示。
    await page.getByTestId('drug-second-checker').click()
    await page
      .getByRole('option', { name: new RegExp(`${ANESTHESIOLOGIST.displayName}.*本人`) })
      .click()
    await page.getByTestId('record-drug').click()
    await expect(page.locator('.el-message').last()).toContainText('不得为给药人本人')
    expect(drugPosts).toBe(0)

    // 选择不同核对人（账号 B）：姓名自动回填，提交成功。
    await page.getByTestId('drug-second-checker').click()
    await page.getByRole('option', { name: new RegExp(SECOND_CHECKER.displayName) }).click()
    await expect(page.getByTestId('drug-second-checker-name')).toHaveValue(
      SECOND_CHECKER.displayName,
    )
    await page.getByTestId('record-drug').click()

    await expect(page.locator('.el-message').last()).toContainText('用药记录已保存')
    expect(drugPosts).toBe(1)
    // 候选来自 /v1/auth/users（仅在职用户进入下拉）。
    expect(usersRequests).toBe(1)

    // 时间轴/用药列表显示双人核对信息：给药人 A 与核对人 B 身份同时可见。
    await expect(page.getByTestId('drug-list')).toContainText(DRUG_NAME)
    await expect(page.getByTestId('drug-list')).toContainText('是')
    await expect(page.getByTestId(`drug-operator-${DRUG_ID}`)).toHaveText(
      ANESTHESIOLOGIST.displayName,
    )
    await expect(page.getByTestId(`drug-checker-${DRUG_ID}`)).toHaveText(SECOND_CHECKER.displayName)
    expect(drugs).toHaveLength(1)
    expect(drugs[0]?.secondCheckerId).toBe(SECOND_CHECKER.userId)
  })

  test('第二核对人重新登录可见该用药与双人核对信息（两名用户身份均出现）', async ({ page }) => {
    await seedSession(page, SECOND_CHECKER)
    await routeDictionary(page)
    const drugs: DrugBody[] = [dualCheckedDrug()]
    let usersRequests = 0
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/auth/users')) usersRequests += 1
    })
    await routeRecord(page, drugs)

    await page.goto(`/m2/records/${SCHEDULE_ID}`)

    // 当前登录人（账号 B）出现在页头；记录单与用药仅查看，不触发写操作。
    await expect(page.locator('.app-header__username')).toHaveText(SECOND_CHECKER.displayName)
    await expect(page.getByTestId('record-context')).toContainText('演示患者')
    await expect(page.getByTestId('drug-list')).toContainText(DRUG_NAME)

    // 双人核对信息：核对人 B 显示名 + 给药人 A 用户标识（B 无 m0:user:read，按审计口径回退标识）。
    await expect(page.getByTestId(`drug-checker-${DRUG_ID}`)).toHaveText(SECOND_CHECKER.displayName)
    await expect(page.getByTestId(`drug-operator-${DRUG_ID}`)).toHaveText(ANESTHESIOLOGIST.userId)
    await expect(page.getByTestId('drug-list')).toContainText(SECOND_CHECKER.displayName)
    await expect(page.getByTestId('drug-list')).toContainText(ANESTHESIOLOGIST.userId)

    // 无 m0:user:read 时不应请求用户管理接口（降级为标识展示，不越权取姓名）。
    expect(usersRequests).toBe(0)
  })
})
