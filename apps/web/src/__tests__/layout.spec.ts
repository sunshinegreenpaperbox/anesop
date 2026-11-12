import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import MainLayout from '../layout/MainLayout.vue'
import router from '../router'
import { pinia } from '../stores'
import { resetAppState, seedAuthenticatedUser, syntheticUser } from './testUtils'

const originalWidth = window.innerWidth

function setViewportWidth(width: number): void {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
}

describe('主布局', () => {
  beforeEach(async () => {
    resetAppState()
    setViewportWidth(1440)
    await router.push('/login')
  })

  afterEach(() => {
    resetAppState()
    setViewportWidth(originalWidth)
  })

  it('渲染顶栏、侧边菜单、面包屑与内容区，并可收起侧栏', async () => {
    seedAuthenticatedUser()
    await router.push('/home')
    const wrapper = mount(MainLayout, {
      global: { plugins: [pinia, router, ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('ANESOP 麻醉信息平台')
    expect(wrapper.text()).toContain('演示管理员（合成）')
    expect(wrapper.text()).toContain('信息科管理员（R9）')
    expect(wrapper.text()).toContain('首页')
    expect(wrapper.text()).toContain('会话管理')
    expect(wrapper.get('h1').text()).toContain('欢迎回来')
    expect(wrapper.find('.app-breadcrumb').exists()).toBe(true)

    const toggle = wrapper.get('[aria-label="收起侧边菜单"]')
    await toggle.trigger('click')
    expect(wrapper.classes()).toContain('is-collapsed')
  })

  it('无 m0:auth:read 的账号看不到会话管理菜单', async () => {
    seedAuthenticatedUser(
      syntheticUser({ roles: ['SYS_ANESTHESIOLOGIST'], permissions: ['anesthesia:record:read'] }),
    )
    await router.push('/home')
    const wrapper = mount(MainLayout, {
      global: { plugins: [pinia, router, ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('首页')
    expect(wrapper.text()).not.toContain('会话管理')
  })

  it('窄屏（<1200）自动收起侧栏', async () => {
    setViewportWidth(1100)
    seedAuthenticatedUser()
    await router.push('/home')
    const wrapper = mount(MainLayout, {
      global: { plugins: [pinia, router, ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.classes()).toContain('is-collapsed')
    expect(wrapper.get('[aria-label="展开侧边菜单"]')).toBeTruthy()
  })
})
