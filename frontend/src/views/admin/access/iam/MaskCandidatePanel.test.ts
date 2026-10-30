// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({
  listMaskCandidates: vi.fn(),
  confirmMaskCandidate: vi.fn(),
  rejectMaskCandidate: vi.fn(),
}))
const iam = vi.hoisted(() => ({ loaded: true, errorMessage: '', snapshot: {}, canOnDatasource: vi.fn() }))

vi.mock('../../../../api/admin/catalog', () => ({
  listMaskCandidates: api.listMaskCandidates,
  confirmMaskCandidate: api.confirmMaskCandidate,
  rejectMaskCandidate: api.rejectMaskCandidate,
}))
vi.mock('../../../../stores/auth', () => ({ useAuthStore: () => ({ token: 'fixture' }) }))
vi.mock('../../../../stores/iamS1', () => ({ useIamS1Store: () => iam }))

import MaskCandidatePanel from './MaskCandidatePanel.vue'

function candidate() {
  return {
    entityId: 21,
    name: 'phone',
    displayName: '手机号',
    fqn: 'ds.db.customer.phone',
    pendingMask: { mask_strategy: 'PHONE', tag_fqn: 'PII' },
  }
}

async function settle() {
  for (let round = 0; round < 5; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset())
  iam.canOnDatasource.mockReset().mockReturnValue(true)
  api.listMaskCandidates.mockResolvedValue({ data: [candidate()] })
})

describe('MaskCandidatePanel', () => {
  it('在字段保护中加载并确认脱敏候选', async () => {
    const wrapper = mount(MaskCandidatePanel, {
      props: { datasourceId: 5 },
      global: { plugins: [ElementPlus] },
    })
    await settle()

    expect(api.listMaskCandidates).toHaveBeenCalledWith(5)
    expect(wrapper.text()).toContain('手机号')
    expect(wrapper.text()).toContain('PHONE')
    expect(wrapper.text()).toContain('确认生效')
  })

  it('没有查看字段保护能力时说明原因且不发请求', async () => {
    iam.canOnDatasource.mockReturnValue(false)
    const wrapper = mount(MaskCandidatePanel, {
      props: { datasourceId: 5 },
      global: { plugins: [ElementPlus] },
    })
    await settle()

    expect(api.listMaskCandidates).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('security:mask:view')
  })

  it('未选择数据源时提示先选择数据源', async () => {
    const wrapper = mount(MaskCandidatePanel, {
      props: { datasourceId: undefined },
      global: { plugins: [ElementPlus] },
    })
    await settle()

    expect(api.listMaskCandidates).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('先选择数据源')
  })
})
