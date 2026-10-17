// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TermEditor from './TermEditor.vue'
const api = vi.hoisted(() => ({ saveTermDraft: vi.fn(), getTermWorkspace: vi.fn() }))
vi.mock('../../api/admin/glossary', () => api)
const wrappers: Array<ReturnType<typeof mount>> = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.resetAllMocks() })
function editor() {
  const wrapper = mount(TermEditor, { props: { groupId: 1, sources: [] }, global: {
    plugins: [ElementPlus], stubs: { teleport: true, TermFieldPicker: true },
  } })
  wrappers.push(wrapper)
  return wrapper
}
async function fill(wrapper: ReturnType<typeof editor>) {
  await (wrapper.vm as unknown as { open: () => Promise<void> }).open()
  await flushPromises()
  await wrapper.find('input[placeholder="例如：客户编号、有效订单"]').setValue('客户编号')
  await wrapper.find('textarea').setValue('客户的唯一标识')
}
function button(wrapper: ReturnType<typeof editor>, text: string) {
  return wrapper.findAll('button').find(b => b.text() === text)!
}
describe('业务术语聚合保存', () => {
  it('未绑定字段仍可保存草稿，但不能提交审核', async () => {
    api.saveTermDraft.mockResolvedValue({ data: { id: 9, revision: 1 } })
    const wrapper = editor(); await fill(wrapper)
    await button(wrapper, '保存并提交审核').trigger('click'); await flushPromises()
    expect(api.saveTermDraft).not.toHaveBeenCalled()
    await button(wrapper, '保存草稿').trigger('click'); await flushPromises()
    expect(api.saveTermDraft).toHaveBeenCalledWith(1, undefined, expect.objectContaining({ name: '客户编号', entityIds: [], submit: false }))
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })
  it('定义和选择的映射通过一个请求提交，失败后保留输入', async () => {
    api.saveTermDraft.mockRejectedValue(new Error('字段来源已变化'))
    const wrapper = editor(); await fill(wrapper)
    wrapper.findComponent({ name: 'TermFieldPicker' }).vm.$emit('update:modelValue', [{ entityId: 20, datasourceName: '客户库', tableName: 'customers', columnName: 'id' }])
    await button(wrapper, '保存并提交审核').trigger('click'); await flushPromises()
    expect(api.saveTermDraft).toHaveBeenCalledWith(1, undefined, expect.objectContaining({ description: '客户的唯一标识', entityIds: [20], submit: true }))
    expect(wrapper.text()).toContain('字段来源已变化')
    expect((wrapper.find('input[placeholder="例如：客户编号、有效订单"]').element as HTMLInputElement).value).toBe('客户编号')
    expect(wrapper.emitted('saved')).toBeUndefined()
  })
})
