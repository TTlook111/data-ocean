// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import FieldGovernanceProgress from './FieldGovernanceProgress.vue'
import { getFieldGovernanceProgress } from '../../api/admin/fieldSemantic'
vi.mock('../../api/admin/fieldSemantic', () => ({ getFieldGovernanceProgress: vi.fn() }))

describe('字段治理阶段', () => {
  it('仅显示服务端核验的阶段，不能将待复查显示为已完成', async () => {
    vi.mocked(getFieldGovernanceProgress).mockResolvedValue({ data: {
      columnMetaId: 1, snapshotId: 2, stage: 'AWAITING_RECHECK', nextAction: 'RECHECK', reason: '需要复查',
    } } as never)
    const wrapper = mount(FieldGovernanceProgress, { props: { columnId: 1, snapshotId: 2 } })
    await flushPromises()
    expect(wrapper.text()).toContain('待系统复查')
    expect(wrapper.text()).not.toContain('本轮已完成')
    wrapper.unmount()
  })
  it('切换字段后不采纳旧请求的完成结果', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(getFieldGovernanceProgress).mockReturnValueOnce(new Promise(r => { resolve = r }) as never)
      .mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = mount(FieldGovernanceProgress, { props: { columnId: 1, snapshotId: 2 } })
    await wrapper.setProps({ columnId: 3 })
    await flushPromises()
    resolve({ data: { columnMetaId: 1, snapshotId: 2, stage: 'ROUND_COMPLETED', reason: '旧结果' } })
    await flushPromises()
    expect(wrapper.text()).toContain('读取失败')
    expect(wrapper.text()).not.toContain('本轮已完成')
    wrapper.unmount()
  })
})
