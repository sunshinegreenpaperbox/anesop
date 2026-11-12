import { describe, expect, it } from 'vitest'
import router from '../router'

describe('router', () => {
  it('将 / 重定向到 /home', async () => {
    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/home')
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('未匹配路由兜底到 /home', async () => {
    await router.push('/no-such-page')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/home')
  })
})
