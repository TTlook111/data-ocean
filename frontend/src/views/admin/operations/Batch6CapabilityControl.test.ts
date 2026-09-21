// @vitest-environment happy-dom
/**
 * 批次 6 前端能力控制测试（组织基础 + 运营与平台 + 字段治理）
 *
 * 能力结论只来自 IAM-SIMPLE-1 摘要，不能再读旧 permissions 数组。
 * 无能力时不发必然 403 的请求；只有查看权时写按钮不可用。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'

const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))

const api = vi.hoisted(() => ({
  getIamS1Capabilities: vi.fn(),
  getAiConfig: vi.fn(),
  createAiProvider: vi.fn(),
  listUsers: vi.fn(),
  listDepartments: vi.fn(),
  createUser: vi.fn(),
  listPendingReviews: vi.fn(),
  approveFeedback: vi.fn(),
  pageConfidence: vi.fn(),
}))

vi.mock('../../../api/iamS1', () => ({ getIamS1Capabilities: api.getIamS1Capabilities }))
vi.mock('../../../api/admin/system', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/admin/system')>()
  return {
    ...actual,
    getAiConfig: api.getAiConfig,
    createAiProvider: api.createAiProvider,
  }
})
vi.mock('../../../api/admin/user', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/admin/user')>()
  return {
    ...actual,
    listUsers: api.listUsers,
    listDepartments: api.listDepartments,
    createUser: api.createUser,
  }
})
vi.mock('../../../api/admin/field', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/admin/field')>()
  return {
    ...actual,
    listPendingReviews: api.listPendingReviews,
    approveFeedback: api.approveFeedback,
    pageConfidence: api.pageConfidence,
  }
})
vi.mock('../../../api/admin/datasource', () => ({
  listSimpleDatasources: vi.fn().mockResolvedValue({ code: 200, message: 'ok', data: [] }),
}))
vi.mock('../../../api/admin/metadata', () => ({
  listSnapshots: vi.fn().mockResolvedValue({ code: 200, message: 'ok', data: { records: [], total: 0 } }),
}))
vi.mock('../../../api/admin/knowledge', () => ({
  listKnowledgeDocs: vi.fn().mockResolvedValue({ code: 200, message: 'ok', data: { records: [], total: 0 } }),
}))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routeState,
    useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  }
})

import AiConfig from '../system/AiConfig.vue'
import UserList from '../user/UserList.vue'
import FeedbackReview from '../field/FeedbackReview.vue'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAdminContextStore } from '../../../stores/adminContext'

function capability(options: {
  global?: string[]
  onDatasource?: string[]
  systemAdmin?: boolean
}): IamS1CapabilitySnapshot {
  const codes = options.onDatasource ?? []
  return {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 7,
    systemAdmin: options.systemAdmin ?? false,
    globalFunctions: options.global ?? [],
    datasourceCapabilities: [
      { datasourceId: 5, datasourceName: '销售库', functionCodes: codes, functionNames: codes },
    ],
    queryUse: false,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
}

function respond(snapshot: IamS1CapabilitySnapshot) {
  api.getIamS1Capabilities.mockResolvedValue({ code: 200, message: 'ok', data: snapshot })
}

function mountPage(component: unknown, attach = false) {
  return mount(component as never, {
    ...(attach ? { attachTo: document.body } : {}),
    global: {
      plugins: [ElementPlus],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

async function settle() {
  for (let round = 0; round < 5; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

describe('批次 6 前端能力控制', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    routeState.query = {}
    Object.values(api).forEach((fn) => fn.mockReset())
    api.listUsers.mockResolvedValue({ code: 200, message: 'ok', data: { records: [], total: 0 } })
    api.listDepartments.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    api.getAiConfig.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { providers: [], activeChat: null, activeEmbedding: null },
    })
    api.listPendingReviews.mockResolvedValue({ code: 200, message: 'ok', data: { records: [], total: 0 } })
    api.pageConfidence.mockResolvedValue({ code: 200, message: 'ok', data: { records: [], total: 0 } })
  })

  it('AI 配置无查看权时不请求配置接口，且不出现添加供应商按钮可点状态', async () => {
    respond(capability({ global: [] }))
    await useIamS1Store().load(true)
    const wrapper = mountPage(AiConfig)
    await settle()
    expect(api.getAiConfig).not.toHaveBeenCalled()
    const add = wrapper.findAll('button').find((item) => item.text().includes('添加供应商'))
    expect(add?.attributes('disabled')).toBeDefined()
  })

  it('AI 配置只读时仍读取配置，但不能添加供应商', async () => {
    respond(capability({ global: ['system:ai-config:view'] }))
    await useIamS1Store().load(true)
    const wrapper = mountPage(AiConfig)
    await settle()
    expect(api.getAiConfig).toHaveBeenCalled()
    const add = wrapper.findAll('button').find((item) => item.text().includes('添加供应商'))
    expect(add?.attributes('disabled')).toBeDefined()
  })

  it('用户列表无查看权时不请求用户接口，且不展示旧角色多选', async () => {
    respond(capability({ global: [] }))
    await useIamS1Store().load(true)
    const wrapper = mountPage(UserList)
    await settle()
    expect(api.listUsers).not.toHaveBeenCalled()
    expect(api.listDepartments).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('organization:user:view')
    expect(wrapper.text()).not.toContain('请选择角色')
  })

  it('用户列表有维护权时新建用户表单不再提交旧 roleIds', async () => {
    respond(capability({ global: ['organization:user:view', 'organization:user:manage'] }))
    await useIamS1Store().load(true)
    const wrapper = mountPage(UserList, true)
    await settle()
    expect(api.listUsers).toHaveBeenCalled()
    const add = wrapper.findAll('button').find((item) => item.text().includes('新增用户'))
    expect(add?.attributes('disabled')).toBeUndefined()
    await add!.trigger('click')
    await settle()
    expect(document.body.textContent || '').toContain('组织、角色与负责源')
    expect(document.body.innerHTML).not.toContain('prop="roleIds"')
    wrapper.unmount()
  })

  it('反馈审核无字段治理查看权时不请求待审列表', async () => {
    respond(capability({ onDatasource: [] }))
    await useIamS1Store().load(true)
    useAdminContextStore().datasourceId = 5
    const wrapper = mountPage(FeedbackReview)
    await settle()
    expect(api.listPendingReviews).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('审核确认')
  })
})
