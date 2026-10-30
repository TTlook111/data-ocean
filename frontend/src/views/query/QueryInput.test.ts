// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import QueryInput from './QueryInput.vue'

function createInput(isQuerying = false) {
  return mount(QueryInput, {
    props: {
      question: '统计订单',
      isQuerying,
      selectedId: 1,
      selectedDatasourceName: '当前数据源',
      canAsk: true,
      readinessLoading: false,
    },
  })
}

describe('QueryInput keyboard behavior', () => {
  it('sends on Enter but preserves Shift+Enter and IME composition', async () => {
    const wrapper = createInput()
    const input = wrapper.get('textarea')
    const composing = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, isComposing: true })
    const shiftEnter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, shiftKey: true })
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    input.element.dispatchEvent(composing)
    input.element.dispatchEvent(shiftEnter)
    input.element.dispatchEvent(enter)

    expect(wrapper.emitted('send')).toHaveLength(1)
    expect(composing.defaultPrevented).toBe(false)
    expect(shiftEnter.defaultPrevented).toBe(false)
    expect(enter.defaultPrevented).toBe(true)
  })

  it('does not send or insert a newline while a task is processing', async () => {
    const wrapper = createInput(true)
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    wrapper.get('textarea').element.dispatchEvent(event)

    expect(wrapper.emitted('send')).toBeUndefined()
    expect(event.defaultPrevented).toBe(true)
  })
})
