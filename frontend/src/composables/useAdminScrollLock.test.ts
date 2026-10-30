// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { useAdminScrollLock } from './useAdminScrollLock'

const Host = defineComponent({
  setup() {
    const locked = ref(false)
    useAdminScrollLock(() => locked.value)
    return { locked }
  },
  template: '<button @click="locked = !locked">{{ locked }}</button>',
})

describe('useAdminScrollLock', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('locks and restores only the admin content scroll container', async () => {
    const content = document.createElement('main')
    content.className = 'admin-shell__content'
    content.style.overflowY = 'auto'
    content.style.overscrollBehavior = 'contain'
    document.body.appendChild(content)
    const wrapper = mount(Host)

    expect(content.style.overflowY).toBe('auto')
    await wrapper.find('button').trigger('click')
    await nextTick()
    expect(content.style.overflowY).toBe('hidden')
    expect(content.style.overscrollBehavior).toBe('contain')

    await wrapper.find('button').trigger('click')
    await nextTick()
    expect(content.style.overflowY).toBe('auto')
    expect(content.style.overscrollBehavior).toBe('contain')
    wrapper.unmount()
  })

  it('restores the container when the owner unmounts while locked', async () => {
    const content = document.createElement('main')
    content.className = 'admin-shell__content'
    content.style.overflowY = 'auto'
    document.body.appendChild(content)
    const wrapper = mount(Host)

    await wrapper.find('button').trigger('click')
    await nextTick()
    expect(content.style.overflowY).toBe('hidden')
    wrapper.unmount()
    expect(content.style.overflowY).toBe('auto')
  })
})
