import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, resetHttpForTests } from '../api/http'
import { downloadFile, parseFileName } from '../modules/m0/api/platform'
import { resetSessionCacheForTests, setTokens } from '../api/session'

describe('platform API 客户端', () => {
  afterEach(() => {
    resetSessionCacheForTests()
    resetHttpForTests()
    vi.unstubAllGlobals()
  })

  it('解析 RFC 5987 filename*（中文文件名）', () => {
    expect(parseFileName("attachment; filename*=UTF-8''%E7%AB%99%E5%8F%B0.csv")).toBe('站台.csv')
    expect(parseFileName('attachment; filename="a.csv"')).toBeNull()
    expect(parseFileName("attachment; filename*=UTF-8''%E0%A4%A")).toBeNull()
  })

  it('下载文件携带 Bearer 并返回 Blob/摘要头', async () => {
    setTokens({ accessToken: 'token-dl', refreshToken: 'refresh-dl', expiresIn: 900 })
    const calls: { url: string; init?: RequestInit }[] = []
    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init })
      return new Response(new Blob(['room,shift\n']), {
        status: 200,
        headers: {
          'Content-Disposition': "attachment; filename*=UTF-8''%E6%A8%A1%E6%9D%BF.csv",
          'X-Content-Sha256': 'b'.repeat(64),
        },
      })
    }
    vi.stubGlobal('fetch', fetchImpl)

    const file = await downloadFile('8b000000-0000-4000-8000-000000000001')

    expect(calls[0]?.url).toMatch(/\/api\/v1\/files\/8b000000-0000-4000-8000-000000000001$/)
    expect((calls[0]?.init?.headers as Record<string, string>).Authorization).toBe(
      'Bearer token-dl',
    )
    expect(file.fileName).toBe('模板.csv')
    expect(file.sha256).toBe('b'.repeat(64))
    expect(file.blob.size).toBeGreaterThan(0)
  })

  it('下载 403 时抛契约 ApiError（不吞掉错误码）', async () => {
    setTokens({ accessToken: 'token-dl', refreshToken: 'refresh-dl', expiresIn: 900 })
    vi.stubGlobal(
      'fetch',
      async () =>
        new Response(
          JSON.stringify({ code: 'AUTH_FORBIDDEN', message: '文件不可见', traceId: 't1' }),
          {
            status: 403,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    )

    await expect(downloadFile('x')).rejects.toMatchObject({
      name: 'ApiError',
      status: 403,
      code: 'AUTH_FORBIDDEN',
      traceId: 't1',
    } satisfies Partial<ApiError>)
  })
})
