// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { RouteLocationRaw } from 'vue-router'
import ReleaseGatePanel from './ReleaseGatePanel.vue'
import type { ReleaseGate } from '../../api/admin/versioning'

function gate(overrides: Partial<ReleaseGate> = {}): ReleaseGate {
  return {
    snapshotId: 9,
    datasourceId: 5,
    snapshotStatus: 'APPROVED',
    approvable: true,
    publishable: true,
    blockingCount: 0,
    warningCount: 0,
    unresolvedSourceHigh: 0,
    unresolvedSemanticHigh: 0,
    validTableCount: 2,
    items: [
      { code: 'SOURCE_HIGH_UNRESOLVED', category: 'SOURCE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的来源高风险问题' },
      { code: 'NO_NORMAL_RECOMMENDED_TABLE', category: 'STRUCTURE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 2, message: '存在 2 张 NORMAL/RECOMMENDED 状态的表' },
      { code: 'SEMANTIC_HIGH_UNRESOLVED', category: 'SEMANTIC', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的语义高风险问题' },
    ],
    ...overrides,
  }
}

interface PanelProps {
  gate: ReleaseGate | null
  loading?: boolean
  error?: string
  compact?: boolean
  issueTarget?: RouteLocationRaw
}

function mountPanel(props: PanelProps) {
  return mount(ReleaseGatePanel, {
    props,
    global: { stubs: { RouterLink: { template: '<a class="router-link-stub"><slot /></a>' } } },
  })
}

describe('ReleaseGatePanel', () => {
  it('显示三项清单和通过汇总', () => {
    const wrapper = mountPanel({ gate: gate() })

    expect(wrapper.text()).toContain('发布门禁')
    expect(wrapper.text()).toContain('门禁已通过')
    expect(wrapper.text()).toContain('没有未解决的来源高风险问题')
    expect(wrapper.text()).toContain('存在 2 张 NORMAL/RECOMMENDED 状态的表')
    expect(wrapper.findAll('.release-gate__item')).toHaveLength(3)
    expect(wrapper.findAll('.release-gate__item--pass')).toHaveLength(3)
  })

  it('来源高风险阻断时显示阻断汇总和问题中心入口', () => {
    const wrapper = mountPanel({
      gate: gate({
        approvable: false,
        publishable: false,
        blockingCount: 1,
        unresolvedSourceHigh: 2,
        items: [
          { code: 'SOURCE_HIGH_UNRESOLVED', category: 'SOURCE', severity: 'HIGH', status: 'FAIL', blocksApproval: true, blocksPublish: true, count: 2, message: '无法发布：存在 2 个未解决的高风险问题' },
          { code: 'NO_NORMAL_RECOMMENDED_TABLE', category: 'STRUCTURE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 2, message: '存在 2 张 NORMAL/RECOMMENDED 状态的表' },
          { code: 'SEMANTIC_HIGH_UNRESOLVED', category: 'SEMANTIC', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的语义高风险问题' },
        ],
      }),
      issueTarget: { path: '/admin/governance/issues', query: { datasourceId: '5', snapshotId: '9' } },
    })

    expect(wrapper.text()).toContain('存在 1 项阻断，暂不可发布')
    expect(wrapper.text()).toContain('无法发布：存在 2 个未解决的高风险问题')
    expect(wrapper.text()).toContain('去问题中心处理')
    expect(wrapper.find('.release-gate__item--fail').exists()).toBe(true)
  })

  it('语义高风险只显示提示并说明不阻断发布', () => {
    const wrapper = mountPanel({
      gate: gate({
        warningCount: 1,
        unresolvedSemanticHigh: 1,
        items: [
          { code: 'SOURCE_HIGH_UNRESOLVED', category: 'SOURCE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的来源高风险问题' },
          { code: 'NO_NORMAL_RECOMMENDED_TABLE', category: 'STRUCTURE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 2, message: '存在 2 张 NORMAL/RECOMMENDED 状态的表' },
          { code: 'SEMANTIC_HIGH_UNRESOLVED', category: 'SEMANTIC', severity: 'HIGH', status: 'WARN', blocksApproval: false, blocksPublish: false, count: 1, message: '存在 1 个未解决的语义高风险问题，语义问题不阻断发布，请在发布前确认' },
        ],
      }),
    })

    expect(wrapper.text()).toContain('提示（不阻断发布）')
    expect(wrapper.find('.release-gate__item--warn').exists()).toBe(true)
    expect(wrapper.text()).toContain('可以发布，但有 1 项提示需确认')
  })

  it('未批准时说明批准后才能发布', () => {
    const wrapper = mountPanel({ gate: gate({ snapshotStatus: 'ISSUE_FOUND', publishable: false }) })

    expect(wrapper.text()).toContain('批准后才能发布')
  })

  it('读取失败显示重试并发出 retry', async () => {
    const wrapper = mountPanel({ gate: null, error: '发布门禁读取失败' })

    expect(wrapper.text()).toContain('发布门禁读取失败')
    await wrapper.find('.error-retry').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('读取中且无数据时显示加载状态', () => {
    const wrapper = mountPanel({ gate: null, loading: true })
    expect(wrapper.text()).toContain('正在读取发布门禁')
  })
})
