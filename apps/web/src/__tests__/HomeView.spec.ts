import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import HomeView from '../views/HomeView.vue'

describe('HomeView', () => {
  it('渲染平台标题与工作台说明', () => {
    const wrapper = mount(HomeView)

    expect(wrapper.get('h1').text()).toBe('ANESOP 麻醉信息平台')
    expect(wrapper.get('p').text()).toBe('围术期全流程管理 · 工作台占位页')
    expect(wrapper.get('main').classes()).toContain('home')
  })
})
