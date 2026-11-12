import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it } from 'vitest'
import HomeView from '../views/HomeView.vue'
import { useAuthStore } from '../stores/auth'
import { pinia } from '../stores'
import { resetAppState, syntheticUser } from './testUtils'

describe('HomeView 首页', () => {
  beforeEach(() => {
    resetAppState()
  })

  it('展示欢迎语、当前角色、权限概览与系统状态，不再使用占位文案', () => {
    const auth = useAuthStore(pinia)
    auth.user = syntheticUser({
      displayName: '演示管理员（合成）',
      roles: ['SYS_ADMIN'],
      permissions: ['m0:auth:read', 'm0:auth:write', 'anesthesia:record:read'],
    })

    const wrapper = mount(HomeView, { global: { plugins: [ElementPlus] } })

    expect(wrapper.get('h1').text()).toBe('欢迎回来，演示管理员（合成）')
    expect(wrapper.text()).toContain('信息科管理员（R9）')
    expect(wrapper.text()).toContain('M0 公共支撑')
    expect(wrapper.text()).toContain('anesthesia:record:read')
    expect(wrapper.text()).toContain('/api')
    expect(wrapper.text()).not.toContain('工作台占位页')
  })

  it('无权限点时给出空状态提示', () => {
    const auth = useAuthStore(pinia)
    auth.user = syntheticUser({ roles: [], permissions: [], dataScopes: [] })

    const wrapper = mount(HomeView, { global: { plugins: [ElementPlus] } })

    expect(wrapper.text()).toContain('尚未分配角色')
    expect(wrapper.text()).toContain('当前账号没有可用权限点')
    expect(wrapper.text()).toContain('未配置数据范围')
  })
})
