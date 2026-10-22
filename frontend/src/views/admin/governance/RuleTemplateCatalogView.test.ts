// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({ listQualityRuleTemplates: vi.fn() }))
vi.mock('../../../api/admin/governance', () => ({
  listQualityRuleTemplates: api.listQualityRuleTemplates,
}))

import RuleTemplateCatalogView from './RuleTemplateCatalogView.vue'

function templates() {
  return [
    {
      code: 'DATA_STALE_TABLE',
      name: '表数据长期未更新',
      dimension: 'TIMELINESS',
      targetType: 'TABLE',
      checkType: 'DATA',
      description: '使用已注册的新鲜度检查器。',
      allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE'],
      parameters: [{ key: 'staleDays', label: '过期天数', type: 'INTEGER', minimum: 1, maximum: 3650, defaultValue: 30 }],
    },
    {
      code: 'COMP_TABLE_COMMENT_MISSING',
      name: '表注释缺失',
      dimension: 'COMPLETENESS',
      targetType: 'TABLE',
      checkType: 'SCHEMA',
      description: '检查表是否包含业务说明。',
      allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE'],
      parameters: [],
    },
  ]
}

function mountPage() {
  return mount(RuleTemplateCatalogView, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        TaskPageHeader: { props: ['title'], template: '<header>{{ title }}</header>' },
      },
    },
  })
}

async function settle() {
  for (let round = 0; round < 4; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

beforeEach(() => {
  api.listQualityRuleTemplates.mockReset()
  api.listQualityRuleTemplates.mockResolvedValue({ data: templates() })
})

describe('检查类型只读目录', () => {
  it('展示已注册模板的维度、目标、范围和参数', async () => {
    const wrapper = mountPage()
    await settle()

    const text = wrapper.text()
    expect(text).toContain('检查类型')
    expect(text).toContain('表数据长期未更新')
    expect(text).toContain('DATA_STALE_TABLE')
    expect(text).toContain('时效性')
    expect(text).toContain('业务数据（只读抽样）')
    expect(text).toContain('过期天数（整数 1–3650，默认30）')
    expect(text).toContain('表注释缺失')
    expect(text).toContain('无参数')
  })

  it('不提供新建、编辑或删除操作', async () => {
    const wrapper = mountPage()
    await settle()

    const labels = wrapper.findAll('button').map((button) => button.text())
    expect(labels.some((label) => /新建|编辑|删除/.test(label))).toBe(false)
    expect(wrapper.text()).toContain('检查类型不是可自由新建的字典项')
  })

  it('读取失败显示错误并可重试', async () => {
    api.listQualityRuleTemplates.mockRejectedValue(new Error('模板服务不可用'))
    const wrapper = mountPage()
    await settle()

    expect(wrapper.text()).toContain('模板服务不可用')
    api.listQualityRuleTemplates.mockResolvedValue({ data: templates() })
    await wrapper.find('.error-retry').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('表数据长期未更新')
  })
})
