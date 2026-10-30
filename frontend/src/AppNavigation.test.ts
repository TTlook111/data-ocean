// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'

afterEach(() => {
  vi.unstubAllGlobals()
})
it('减少动态效果时，连续跨工作区导航仍呈现最终页面', async () => {
  vi.stubGlobal('matchMedia', () => ({
    matches: true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/query', component: defineComponent({ template: '<main><h1>当前问数页面</h1></main>' }) },
      { path: '/profile', component: defineComponent({ template: '<main><h1>本人资料页面</h1></main>' }) },
      { path: '/', redirect: '/query' },
    ],
  })
  await router.push('/query')
  await router.isReady()
  const w = mount(App, { global: { plugins: [router], stubs: { transition: false } } })
  await flushPromises()
  for (let i = 0; i < 3; i++) {
    await router.push('/profile')
    await router.push('/')
    await nextTick()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/query')
    expect(w.find('main').exists()).toBe(true)
    expect(w.text()).toContain('当前问数页面')
  }
  w.unmount()
})
