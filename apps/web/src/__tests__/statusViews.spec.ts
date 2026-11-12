import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import ForbiddenView from '../views/ForbiddenView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import router from '../router'
import { pinia } from '../stores'
import { resetAppState, seedAuthenticatedUser } from './testUtils'

describe('状态页', () => {
  beforeEach(async () => {
    resetAppState()
    seedAuthenticatedUser()
    await router.push('/home')
  })

  afterEach(() => {
    resetAppState()
  })

  it('403 页展示权限说明、目标页面与当前账号', async () => {
    await router.push('/403?from=/m0/sessions')
    const wrapper = mount(ForbiddenView, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('无权访问该页面')
    expect(wrapper.text()).toContain('权限点')
    expect(wrapper.text()).toContain('/m0/sessions')
    expect(wrapper.text()).toContain('demo_admin')
  })

  it('404 页展示请求路径并可返回首页', async () => {
    await router.push('/404')
    const wrapper = mount(NotFoundView, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('页面不存在')
    expect(wrapper.text()).toContain('/404')
    expect(wrapper.text()).toContain('返回首页')
  })
})
