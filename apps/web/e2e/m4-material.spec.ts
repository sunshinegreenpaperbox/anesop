import { expect, test, type Page } from '@playwright/test'

/**
 * 耗材/UDI/SPD（M4-M14-01）E2E：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 覆盖主链路：UDI 逐件登记入库 → 绑定麻醉记录使用 → material→UDI→record 追溯链；
 * 并验证耗材出入库页的批次库存/流水与 SPD 模拟状态卡可渲染。
 * 真实 409（数量不足/重复使用）、422（效期/记录不可见）、403/DataScope、审计与降级
 * 由 `M4MaterialApiIntegrationTest` 与单测覆盖。
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

const NURSE: SyntheticUser = {
  userId: '10000000-0000-4000-8000-000000000004',
  username: 'synth_nurse',
  displayName: '演示麻醉护士（合成）',
  roles: ['SYS_NURSE_ANESTHETIST'],
  permissions: ['m4:material:read', 'm4:udi:manage'],
  dataScopes: [{ type: 'DEPT', scopeIds: ['f1000000-0000-4000-8000-000000000002'] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const DEPT = 'f1000000-0000-4000-8000-000000000002'
const MATERIAL_ID = '4a000000-0000-4000-8000-000000000001'
const RECORD_ID = '7a000000-0000-4000-8000-000000000001'
const SCHEDULE_ID = '6a000000-0000-4000-8000-000000000001'
const UDI_DI = '06937000000001'
const UDI_PI = 'SN-E2E-0001'
const UDI = `${UDI_DI}^${UDI_PI}`

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

function materialRecord() {
  return {
    materialId: MATERIAL_ID,
    code: 'MAT-CVC-001',
    name: '中心静脉导管（演示）',
    specification: '7F 三腔',
    unit: '套',
    category: 'CATHETER',
    udiDi: UDI_DI,
    highValue: true,
    expiryManaged: true,
    status: 'ACTIVE',
    createdAt: '2026-11-14T08:00:00+08:00',
    createdBy: NURSE.userId,
    updatedAt: '2026-11-14T08:00:00+08:00',
    updatedBy: NURSE.userId,
    version: 1,
  }
}

test.describe('M4 耗材/UDI/SPD', () => {
  test('UDI 登记入库 → 绑定记录使用 → 追溯链', async ({ page }) => {
    await seedSession(page, NURSE)

    const instance: Record<string, unknown> = {
      udi: UDI,
      udiDi: UDI_DI,
      udiPi: UDI_PI,
      serialNumber: UDI_PI,
      materialId: MATERIAL_ID,
      materialCode: 'MAT-CVC-001',
      materialName: '中心静脉导管（演示）',
      batchNo: 'BATCH-E2E-0001',
      expiryDate: '2027-12-31',
      departmentId: DEPT,
      status: 'IN_STOCK',
      createdAt: '2026-11-14T08:00:00+08:00',
      createdBy: NURSE.userId,
      version: 1,
    }
    const movements: Record<string, unknown>[] = [
      {
        movementId: '4c000000-0000-4000-8000-000000000001',
        movementType: 'IN',
        materialId: MATERIAL_ID,
        materialCode: 'MAT-CVC-001',
        materialName: '中心静脉导管（演示）',
        batchNo: 'BATCH-E2E-0001',
        expiryDate: '2027-12-31',
        udi: UDI,
        quantity: 1,
        unit: '套',
        departmentId: DEPT,
        counterparty: '中心库房（E2E）',
        balanceAfter: 1,
        operatorId: NURSE.userId,
        operatorName: NURSE.username,
        occurredAt: '2026-11-14T08:00:00+08:00',
        createdAt: '2026-11-14T08:00:00+08:00',
      },
    ]
    let recordLink: Record<string, string> | null = null

    const json = (route: import('@playwright/test').Route, body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

    await page.route('**/api/v1/materials**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname

      if (path.endsWith('/materials/departments')) {
        return json(route, {
          items: [{ departmentId: DEPT, code: 'DEPT-ANES', name: '麻醉科（演示）' }],
        })
      }
      if (path.endsWith('/materials/udi-instances') && request.method() === 'POST') {
        const body = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
        instance.batchNo = body.batchNo
        instance.udiPi = body.udiPi
        instance.serialNumber = body.serialNumber ?? body.udiPi
        instance.udi = `${body.udiDi}^${body.udiPi}`
        return json(route, instance, 201)
      }
      if (path.includes('/materials/udi-instances/') && path.endsWith('/use')) {
        instance.status = 'USED'
        instance.recordId = RECORD_ID
        instance.scheduleId = SCHEDULE_ID
        instance.usedAt = '2026-11-14T09:00:00+08:00'
        instance.usedBy = NURSE.userId
        instance.version = 2
        recordLink = { recordId: RECORD_ID, scheduleId: SCHEDULE_ID }
        movements.push({
          movementId: '4c000000-0000-4000-8000-000000000002',
          movementType: 'OUT',
          materialId: MATERIAL_ID,
          materialCode: 'MAT-CVC-001',
          materialName: '中心静脉导管（演示）',
          batchNo: 'BATCH-E2E-0001',
          udi: UDI,
          quantity: 1,
          unit: '套',
          departmentId: DEPT,
          counterparty: '术中使用（UDI 扫录）',
          balanceAfter: 0,
          operatorId: NURSE.userId,
          operatorName: NURSE.username,
          occurredAt: '2026-11-14T09:00:00+08:00',
          createdAt: '2026-11-14T09:00:00+08:00',
        })
        return json(route, instance)
      }
      if (path.includes('/materials/udi-instances/') && request.method() === 'GET') {
        return json(route, {
          instance,
          material: {
            materialId: MATERIAL_ID,
            code: 'MAT-CVC-001',
            name: '中心静脉导管（演示）',
            specification: '7F 三腔',
            unit: '套',
            highValue: true,
            expiryManaged: true,
            status: 'ACTIVE',
          },
          movements,
          ...(recordLink ? { record: recordLink } : {}),
        })
      }
      if (path.endsWith('/materials/stock-movements') && request.method() === 'GET') {
        return json(route, { items: movements })
      }
      if (path.endsWith('/materials/stock') && request.method() === 'GET') {
        return json(route, {
          items: [
            {
              stockId: '4b000000-0000-4000-8000-000000000001',
              materialId: MATERIAL_ID,
              materialCode: 'MAT-CVC-001',
              materialName: '中心静脉导管（演示）',
              batchNo: 'BATCH-E2E-0001',
              departmentId: DEPT,
              quantity: recordLink ? 0 : 1,
              unit: '套',
              expiryDate: '2027-12-31',
              updatedAt: '2026-11-14T08:00:00+08:00',
            },
          ],
        })
      }
      if (path.endsWith('/materials/spd-adapter/status')) {
        return json(route, {
          adapterCode: 'EXT-03',
          name: 'SPD 供应链适配器（模拟）',
          mode: 'SIMULATED',
          available: true,
          degraded: false,
          lastSyncedAt: '2026-11-14T08:00:00+08:00',
          pendingSyncCount: 0,
          message: '未接入真实 SPD 系统（EXT-03），当前为模拟同步',
        })
      }
      if (path.endsWith('/materials') && request.method() === 'GET') {
        return json(route, {
          items: [materialRecord()],
          page: 1,
          size: 200,
          total: 1,
          totalPages: 1,
        })
      }
      return json(
        route,
        { code: 'RESOURCE_NOT_FOUND', message: '资源不存在', traceId: 'e2e', details: [] },
        404,
      )
    })

    // 1) UDI 逐件登记（入库）。
    await page.goto('/m4/materials/trace')
    await expect(page.getByTestId('udi-empty')).toBeVisible()
    await page.getByTestId('udi-register-di').fill(UDI_DI)
    await page.getByTestId('udi-register-pi').fill(UDI_PI)
    await page.getByTestId('udi-register-batch').fill('BATCH-E2E-0001')
    await page.getByTestId('udi-register-source').fill('中心库房（E2E）')
    await page.getByTestId('udi-register-submit').click()
    await expect(page.getByTestId('udi-register-result')).toContainText('已登记')
    await expect(page.getByTestId('udi-status')).toContainText('在库')
    await expect(page.getByTestId('udi-movements')).toContainText('入库')

    // 2) 绑定麻醉记录使用（一物一码）。
    await page.getByTestId('udi-use-record').fill(RECORD_ID)
    await page.getByTestId('udi-use-schedule').fill(SCHEDULE_ID)
    await page.getByTestId('udi-use-submit').click()
    await expect(page.getByTestId('udi-use-result')).toContainText('已使用并绑定记录')
    await expect(page.getByTestId('udi-status')).toContainText('已使用')

    // 3) 追溯链：material → UDI → record，含出库流水。
    await expect(page.getByTestId('udi-material')).toContainText('中心静脉导管（演示）')
    await expect(page.getByTestId('udi-record')).toContainText(RECORD_ID)
    await expect(page.getByTestId('udi-movements')).toContainText('出库')

    // 4) 出入库页：批次库存与 SPD 模拟状态卡。
    await page.goto('/m4/materials/stock')
    await expect(page.getByTestId('spd-status')).toContainText('模拟同步正常')
    await expect(page.getByTestId('stock-table')).toContainText('BATCH-E2E-0001')
    await expect(page.getByTestId('movement-table')).toContainText('出库')
  })
})
