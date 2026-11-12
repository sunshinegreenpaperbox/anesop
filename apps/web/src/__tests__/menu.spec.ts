import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { appMenu, filterMenuByPermission } from '../navigation'
import { routes } from '../router/routes'
import { useAuthStore } from '../stores/auth'
import { useMenuStore } from '../stores/menu'
import { syntheticUser } from './testUtils'

describe('菜单权限过滤', () => {
  it('纯函数按权限声明过滤：未声明权限的菜单始终保留', () => {
    const can = (permissions?: readonly string[]) =>
      !permissions || permissions.includes('m0:auth:read')

    const visible = filterMenuByPermission(appMenu, can)

    expect(visible.map((item) => item.path)).toEqual(['/home', '/m0/sessions', '/m0/password'])
  })

  it('无 m0:auth:read 时隐藏会话管理，保留首页与改密', () => {
    const visible = filterMenuByPermission(appMenu, (permissions) => !permissions)
    expect(visible.map((item) => item.path)).toEqual(['/home', '/m0/password'])
  })

  it('store 按 /v1/auth/me 的权限快照生成菜单，不硬编码角色', () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = syntheticUser({
      roles: ['SYS_ANESTHESIOLOGIST'],
      permissions: ['anesthesia:record:read'],
    })
    const menu = useMenuStore()

    expect(menu.visibleMenus.map((item) => item.path)).toEqual(['/home', '/m0/password'])

    auth.user = syntheticUser({ permissions: ['m0:auth:read'] })
    expect(menu.visibleMenus.map((item) => item.path)).toEqual([
      '/home',
      '/m0/sessions',
      '/m0/password',
    ])
  })

  it('每个菜单项都能在路由表中找到对应路径（防菜单/路由漂移）', () => {
    const routePaths = routes.flatMap((record) => {
      if (record.children && record.children.length > 0) {
        return record.children.map((child) => `/${child.path}`)
      }
      return [record.path]
    })

    for (const item of appMenu) {
      expect(routePaths).toContain(item.path)
    }
  })
})

describe('菜单 store 隔离', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('未登录（无 user）时只剩无权限声明的菜单', () => {
    const menu = useMenuStore()
    expect(menu.visibleMenus.map((item) => item.path)).toEqual(['/home', '/m0/password'])
  })
})
