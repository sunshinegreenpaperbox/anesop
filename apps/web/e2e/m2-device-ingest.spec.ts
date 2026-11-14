import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * E2E-02 采集断网补传（M3-QA-01）：API 拦截 + SYNTHETIC 数据，不连接真实后端。
 *
 * 场景：网关断网期间本地缓冲 2 帧 → 恢复后按同一 batchKey 重放 → 平台整批判重（不重复行）
 * → 设备健康可见 → 新 batchKey 补 1 帧 → 记录页体征条数 2 → 3（不是 4/6）。
 *
 * 说明（与 E2E-04 同口径）：本用例是 **UI + 平台 API 契约层** 的端到端回归，ingest 的
 * 批/行级幂等由拦截层按契约语义模拟；真实 HTTP + PostgreSQL 的幂等、质量标记、403 审计
 * 由 `DeviceAcquisitionApiIntegrationTest` 与 `ClinicalRecordAcceptanceTest` 覆盖。
 * 真实全栈补传（启动 Spring Boot + 嵌入式 PG）仍由后续 `scripts/e2e-full.sh` 承接。
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
  permissions: [
    'm2:record:read',
    'm2:record:write',
    'm2:record:lock',
    'm2:record:revise',
    'm2:alert:read',
    'm2:device:read',
    'm2:device:write',
  ],
  dataScopes: [{ type: 'ALL', scopeIds: [] }],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const SURGEON: SyntheticUser = {
  userId: '1000000000000000003',
  username: 'synth_surgeon',
  displayName: '演示术者（合成）',
  roles: ['SYS_SURGEON'],
  permissions: [],
  dataScopes: [],
  siteIds: ['3000000000000000001'],
  mustChangePassword: false,
}

const SCHEDULE_ID = '90000000-0000-4000-8000-000000000002'
const RECORD_ID = '12000000-0000-4000-8000-000000000002'
const ROOM_ID = 'f2000000-0000-4000-8000-0000000000c1'
const GATEWAY_ID = 'GW-E2E-02'
const DEVICE_ID = 'SIM-E2E-02'
const GATEWAY_TOKEN = 'synth-e2e-02-gateway-token'

interface Frame {
  seq: number
  measuredAt: string
  hr: number
  spo2: number
  quality: number
  flags: string[]
}

interface PlatformState {
  batches: Set<string>
  rows: Map<string, Frame>
  duplicateFrames: number
}

function newState(): PlatformState {
  return { batches: new Set(), rows: new Map(), duplicateFrames: 0 }
}

function frame(seq: number, measuredAt: string, hr: number, quality: number): Frame {
  return { seq, measuredAt, hr, spo2: 98, quality, flags: quality === 0 ? [] : ['hr:RANGE'] }
}

function fulfillJson(route: Route, status: number, body: unknown) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'X-Trace-Id': 'SYNTH-E2E-02' },
    body: JSON.stringify(body),
  })
}

function recordBody(state: PlatformState) {
  const vitalSigns = Array.from(state.rows.values()).map((item) => ({
    seq: item.seq,
    measuredAt: item.measuredAt,
    hr: item.hr,
    spo2: item.spo2,
    quality: item.quality,
    qualityFlags: item.flags,
  }))
  return {
    recordId: RECORD_ID,
    scheduleId: SCHEDULE_ID,
    patientId: '50000000-0000-4000-8000-000000000002',
    patientNo: 'SYNTH-P-0002',
    patientName: '演示患者乙（合成）',
    status: 'IN_PROGRESS',
    anesthesiaType: 'GENERAL',
    startedAt: '2026-12-02T08:40:00+08:00',
    endedAt: null,
    events: [],
    vitalSigns,
    threeWayCheckCompleted: true,
    lockedAt: null,
    lockedBy: null,
    currentRevision: 0,
    signatureRef: null,
    signatureHash: null,
    caStatus: null,
    createdAt: '2026-12-02T08:35:00+08:00',
    createdBy: ANESTHESIOLOGIST.userId,
    updatedAt: '2026-12-02T08:40:00+08:00',
    updatedBy: ANESTHESIOLOGIST.userId,
    version: 2,
  }
}

async function seedSession(page: Page, user: SyntheticUser): Promise<void> {
  await page.addInitScript((value) => {
    window.sessionStorage.setItem('anesop.auth.accessToken', 'SYNTH-ACCESS-TOKEN')
    window.sessionStorage.setItem('anesop.auth.refreshToken', 'SYNTH-REFRESH-TOKEN')
    window.sessionStorage.setItem('anesop.auth.currentUser', JSON.stringify(value))
    window.sessionStorage.setItem('anesop.auth.expiresAt', String(Date.now() + 900_000))
  }, user)
}

/** 平台 API 契约层模拟：devices 登记/ingest/vitals/health + 记录页读取。 */
async function installPlatformRoutes(
  page: Page,
  state: PlatformState,
  currentUser: SyntheticUser,
): Promise<void> {
  // 兜底：未覆盖的 API 返回空列表，避免记录页次级请求落空。
  await page.route('**/api/v1/**', (route) => fulfillJson(route, 200, { items: [] }))

  await page.route('**/api/v1/auth/me', (route) => fulfillJson(route, 200, currentUser))

  await page.route('**/api/v1/devices**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()

    if (method === 'GET' && path.endsWith('/health')) {
      if (!currentUser.permissions.includes('m2:device:read')) {
        return fulfillJson(route, 403, {
          code: 'AUTH_FORBIDDEN',
          message: '需要 m2:device:read 权限',
          traceId: 'SYNTH-E2E-02',
        })
      }
      return fulfillJson(route, 200, {
        generatedAt: new Date().toISOString(),
        offlineTimeoutSeconds: 120,
        status: state.batches.size > 0 ? 'HEALTHY' : 'NO_DATA',
        devices: { total: 1, online: 1, offline: 0, maintenance: 0 },
        ingest: {
          batches: state.batches.size,
          acceptedRows: state.rows.size,
          duplicateRows: 0,
          rejectedRows: 0,
          lastReceivedAt: new Date().toISOString(),
        },
        gateways: [
          {
            gatewayId: GATEWAY_ID,
            status: 'ONLINE',
            devicesTotal: 1,
            devicesOnline: 1,
            devicesOffline: 0,
            devicesMaintenance: 0,
          },
        ],
      })
    }

    if (method === 'GET' && path.endsWith('/vitals')) {
      if (!currentUser.permissions.includes('m2:device:read')) {
        return fulfillJson(route, 403, {
          code: 'AUTH_FORBIDDEN',
          message: '需要 m2:device:read 权限',
          traceId: 'SYNTH-E2E-02',
        })
      }
      const items = Array.from(state.rows.values()).map((item) => ({
        metric: 'HR',
        value: item.hr,
        unit: 'bpm',
        measuredAt: item.measuredAt,
        quality: item.quality,
        qualityFlags: item.flags,
      }))
      return fulfillJson(route, 200, {
        items,
        meta: { page: 1, size: 50, total: state.rows.size, totalPages: 1 },
      })
    }

    if (method === 'GET') {
      if (!currentUser.permissions.includes('m2:device:read')) {
        return fulfillJson(route, 403, {
          code: 'AUTH_FORBIDDEN',
          message: '需要 m2:device:read 权限',
          traceId: 'SYNTH-E2E-02',
        })
      }
      return fulfillJson(route, 200, {
        items: [
          {
            gatewayId: GATEWAY_ID,
            deviceId: DEVICE_ID,
            roomId: ROOM_ID,
            deviceType: 'MONITOR',
            status: 'ONLINE',
            version: 1,
            qualityStats: {
              batches: state.batches.size,
              acceptedRows: state.rows.size,
              duplicateRows: 0,
              rejectedRows: 0,
              suspectRows: 0,
            },
          },
        ],
      })
    }

    if (method === 'POST' && path.endsWith('/ingest')) {
      if (request.headers()['x-gateway-token'] !== GATEWAY_TOKEN) {
        return fulfillJson(route, 401, {
          code: 'AUTH_UNAUTHORIZED',
          message: '网关令牌无效',
          traceId: 'SYNTH-E2E-02',
        })
      }
      const body = JSON.parse(request.postData() ?? '{}') as {
        batchKey?: string
        frames?: Frame[]
      }
      const batchKey = body.batchKey ?? ''
      const frames = body.frames ?? []
      if (state.batches.has(batchKey)) {
        // 断网恢复后的整批重放：按 batchKey 判重，不新增行、不重复计数。
        state.duplicateFrames += frames.length
        return fulfillJson(route, 202, {
          batchKey,
          acceptedRows: 0,
          duplicateRows: frames.length,
          rejectedRows: 0,
          duplicateBatch: true,
          receivedAt: new Date().toISOString(),
          rejections: [],
        })
      }
      state.batches.add(batchKey)
      let acceptedRows = 0
      let duplicateRows = 0
      for (const item of frames) {
        const rowKey = `${item.seq}|${item.measuredAt}`
        if (state.rows.has(rowKey)) {
          duplicateRows += 1
        } else {
          state.rows.set(rowKey, item)
          acceptedRows += 1
        }
      }
      return fulfillJson(route, 202, {
        batchKey,
        acceptedRows,
        duplicateRows,
        rejectedRows: 0,
        duplicateBatch: false,
        receivedAt: new Date().toISOString(),
        rejections: [],
      })
    }

    if (method === 'POST' && path === '/api/v1/devices') {
      if (!currentUser.permissions.includes('m2:device:write')) {
        return fulfillJson(route, 403, {
          code: 'AUTH_FORBIDDEN',
          message: '需要 m2:device:write 权限',
          traceId: 'SYNTH-E2E-02',
        })
      }
      return fulfillJson(route, 201, {
        gatewayId: GATEWAY_ID,
        deviceId: DEVICE_ID,
        roomId: ROOM_ID,
        deviceType: 'MONITOR',
        status: 'ONLINE',
        version: 1,
      })
    }

    return fulfillJson(route, 200, { items: [] })
  })

  await page.route('**/api/v1/anesthesia/records**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const record = recordBody(state)
    if (request.method() === 'GET' && path.endsWith('/records')) {
      return fulfillJson(route, 200, { items: [record] })
    }
    if (request.method() === 'GET' && path.endsWith('/events')) {
      return fulfillJson(route, 200, { items: [] })
    }
    if (request.method() === 'GET' && path.endsWith('/io-summary')) {
      return fulfillJson(route, 200, {
        recordId: RECORD_ID,
        intakeMl: 0,
        outputMl: 0,
        balanceMl: 0,
        fluidCount: 0,
        computedAt: new Date().toISOString(),
      })
    }
    if (request.method() === 'GET' && path.endsWith('/effective')) {
      return fulfillJson(route, 200, {
        recordId: RECORD_ID,
        original: { anesthesiaType: record.anesthesiaType },
        effective: { anesthesiaType: record.anesthesiaType },
        corrections: [],
        currentRevision: 0,
      })
    }
    return fulfillJson(route, 200, { items: [] })
  })
}

/** 从页面发起真实 fetch（经 Playwright route 拦截到平台契约层）。 */
async function apiCall(
  page: Page,
  path: string,
  init: { method: string; body?: unknown; headers?: Record<string, string> },
): Promise<{ status: number; body: Record<string, unknown> }> {
  return page.evaluate(
    async ({ path: target, init: options }) => {
      const response = await fetch(target, {
        method: options.method,
        headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      })
      return { status: response.status, body: (await response.json()) as Record<string, unknown> }
    },
    { path, init },
  )
}

test.describe('E2E-02 采集断网补传', () => {
  test('同批次重放不重复，新批次补齐后记录页可见', async ({ page }) => {
    const state = newState()
    await seedSession(page, ANESTHESIOLOGIST)
    await installPlatformRoutes(page, state, ANESTHESIOLOGIST)
    // 先进入应用同源页面，页面内 fetch 才可使用相对路径。
    await page.goto('/home')

    // 管理员登记设备到术间（真实路径：POST /v1/devices）。
    const registered = await apiCall(page, '/api/v1/devices', {
      method: 'POST',
      body: { gatewayId: GATEWAY_ID, deviceId: DEVICE_ID, roomId: ROOM_ID, deviceType: 'MONITOR' },
      headers: { Authorization: 'Bearer SYNTH-ACCESS-TOKEN' },
    })
    expect(registered.status).toBe(201)

    // 无网关令牌的补传被拒绝（401），不产生数据。
    const unauthorized = await apiCall(page, `/api/v1/devices/${GATEWAY_ID}/${DEVICE_ID}/ingest`, {
      method: 'POST',
      body: { batchKey: 'E2E02-NO-TOKEN', frames: [frame(1, new Date().toISOString(), 70, 0)] },
    })
    expect(unauthorized.status).toBe(401)
    expect(unauthorized.body.code).toBe('AUTH_UNAUTHORIZED')
    expect(state.rows.size).toBe(0)

    // 断网缓冲 2 帧，恢复后第一次上传。
    const batchKey = `E2E02-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    const measuredAt = new Date().toISOString()
    const buffered = {
      batchKey,
      frames: [frame(1, measuredAt, 76, 0), frame(2, measuredAt, 132, 1)],
    }
    const first = await apiCall(page, `/api/v1/devices/${GATEWAY_ID}/${DEVICE_ID}/ingest`, {
      method: 'POST',
      body: buffered,
      headers: { 'X-Gateway-Token': GATEWAY_TOKEN },
    })
    expect(first.status).toBe(202)
    expect(first.body.acceptedRows).toBe(2)
    expect(first.body.duplicateBatch).toBe(false)

    // 补传重放：同一 batchKey 再来一次 → 整批判重，不重复行。
    const replay = await apiCall(page, `/api/v1/devices/${GATEWAY_ID}/${DEVICE_ID}/ingest`, {
      method: 'POST',
      body: buffered,
      headers: { 'X-Gateway-Token': GATEWAY_TOKEN },
    })
    expect(replay.status).toBe(202)
    expect(replay.body.duplicateBatch).toBe(true)
    expect(replay.body.acceptedRows).toBe(0)
    expect(replay.body.duplicateRows).toBe(2)
    expect(state.rows.size).toBe(2)

    const health = await apiCall(page, '/api/v1/devices/health', {
      method: 'GET',
      headers: { Authorization: 'Bearer SYNTH-ACCESS-TOKEN' },
    })
    expect(health.status).toBe(200)
    expect((health.body.ingest as Record<string, number>).batches).toBe(1)
    expect((health.body.ingest as Record<string, number>).acceptedRows).toBe(2)

    // 记录页：体征来自平台映射，重放后应为 2 条而不是 4 条。
    await page.goto(`/m2/records/${SCHEDULE_ID}`)
    await expect(page.getByTestId('vital-placeholder')).toHaveText('2 条')
    await expect(page.getByTestId('vital-placeholder')).not.toContainText('4 条')

    // 网关补齐断网期间的第 3 帧（新 batchKey）→ 记录页 3 条，旧帧不重复。
    const late = await apiCall(page, `/api/v1/devices/${GATEWAY_ID}/${DEVICE_ID}/ingest`, {
      method: 'POST',
      body: {
        batchKey: `E2E02-LATE-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`,
        frames: [frame(3, new Date().toISOString(), 78, 0)],
      },
      headers: { 'X-Gateway-Token': GATEWAY_TOKEN },
    })
    expect(late.status).toBe(202)
    expect(late.body.acceptedRows).toBe(1)
    expect(state.rows.size).toBe(3)

    await page.reload()
    await expect(page.getByTestId('vital-placeholder')).toHaveText('3 条')
    await expect(page.getByTestId('vital-placeholder')).not.toContainText('6 条')
    expect(state.duplicateFrames).toBe(2)
  })

  test('无设备/记录权限返回 403 且记录页被守卫拦截', async ({ page }) => {
    const state = newState()
    await seedSession(page, SURGEON)
    await installPlatformRoutes(page, state, SURGEON)
    await page.goto('/home')

    const devices = await apiCall(page, '/api/v1/devices', {
      method: 'GET',
      headers: { Authorization: 'Bearer SYNTH-ACCESS-TOKEN' },
    })
    expect(devices.status).toBe(403)
    expect(devices.body.code).toBe('AUTH_FORBIDDEN')

    // 低权限直接访问记录页：路由守卫拦到 /403，不渲染患者上下文。
    await page.goto(`/m2/records/${SCHEDULE_ID}`)
    await expect(page).toHaveURL(/\/403(\?|$)/)
    await expect(page.getByTestId('vital-placeholder')).toHaveCount(0)
    await expect(page.getByText('演示患者乙（合成）')).toHaveCount(0)
  })
})
