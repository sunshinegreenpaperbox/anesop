import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from '../App.vue'
import router from '../router'

describe('App 壳', () => {
  it('根路径经路由重定向后渲染首页并显示平台标题', async () => {
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router] } })

    expect(router.currentRoute.value.path).toBe('/home')
    expect(wrapper.get('h1').text()).toBe('ANESOP 麻醉信息平台')
    expect(wrapper.text()).toContain('围术期全流程管理 · 工作台占位页')
  })
})
