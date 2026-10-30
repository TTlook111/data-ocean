// @vitest-environment happy-dom
import { it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, ref, nextTick } from 'vue'
import ErrorState from './ErrorState.vue'
it('无读取处理函数的错误不提供假重试或重发写操作', () => {
  const w = mount(ErrorState, { props: { message: '写入结果未知，请核对' } })
  expect(w.text()).toContain('结果未知')
  expect(w.find('button').exists()).toBe(false)
  w.unmount()
})
it('有读取处理函数时重试一次，并可明确隐藏', async () => {
  const read = vi.fn()
  const w = mount(ErrorState, { props: { message: '读取失败', onRetry: read } })
  await w.find('button').trigger('click')
  expect(read).toHaveBeenCalledOnce()
  await w.setProps({ showRetry: false })
  expect(w.find('button').exists()).toBe(false)
  w.unmount()
})

it('父组件动态增删handler没有残留假按钮', async () => {
  const enabled = ref(false),
    read = vi.fn()
  const w = mount({
    setup: () => () => h(ErrorState, { message: '读取失败', ...(enabled.value ? { onRetry: read } : {}) }),
  })
  expect(w.find('button').exists()).toBe(false)
  enabled.value = true
  await nextTick()
  await w.find('button').trigger('click')
  expect(read).toHaveBeenCalledOnce()
  enabled.value = false
  await nextTick()
  expect(w.find('button').exists()).toBe(false)
  w.unmount()
})
