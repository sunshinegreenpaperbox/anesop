import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import App from '../App.vue'
import router from '../router'
import { pinia } from '../stores'
import { resetAppState } from './testUtils'

describe('App 壳', () => {
  beforeEach(async () => {
    resetAppState()
    await router.push('/login')
    await router.isReady()
  })

  afterEach(() => {
    resetAppState()
  })

  it('根路径在未登录时经守卫重定向到登录页并渲染登录表单', async () => {
    await router.push('/')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')

    const wrapper = mount(App, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('h2').text()).toBe('账号登录')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(document.title).toContain('登录')
  })
})
