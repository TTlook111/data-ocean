// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminContextStore } from './adminContext'
import { useIamS1Store } from './iamS1'

const api = vi.hoisted(() => ({ listSimpleDatasources: vi.fn(), listSnapshots: vi.fn(), listKnowledgeDocs: vi.fn() }))
vi.mock('../api/iamS1', () => ({ getIamS1Capabilities: vi.fn() }))
vi.mock('../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots, getSnapshotDetail: vi.fn() }))
vi.mock('../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))

function role(functions: string[]) {
  const iam = useIamS1Store()
  iam.snapshot = { protocolVersion: 'IAM-SIMPLE-1', userId: 7, systemAdmin: false,
    globalFunctions: functions,
    datasourceCapabilities: [{ datasourceId: 2, datasourceName: '第二合成源', functionCodes: functions, functionNames: functions }],
    queryUse: false, viewSql: false, export: false, permissionRevision: 1 }
  iam.loaded = true
}
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); vi.resetAllMocks()
  api.listSimpleDatasources.mockRejectedValue(new Error('没有 datasource:view'))
  api.listSnapshots.mockRejectedValue(new Error('没有 metadata:view'))
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [{ id: 6, datasourceId: 2, title: '本源草稿', status: 'DRAFT' }], total: 1 } })
})

describe('后台范围按当前 S1 管理能力恢复', () => {
  it('仅知识维护者从旧源1偏好恢复到合法源2，不借用数据源/元数据查看权', async () => {
    localStorage.setItem('dataocean_admin_context', JSON.stringify({ datasourceId: 1, snapshotId: 11, knowledgeDocId: 1 }))
    role(['knowledge:view', 'knowledge:manage'])
    const context = useAdminContextStore()
    await expect(context.initialize()).resolves.toBeUndefined()
    expect(context.datasourceId).toBe(2)
    expect(context.datasources.map(item => ({ id: item.id, name: item.name }))).toEqual([{ id: 2, name: '第二合成源' }])
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    expect(api.listSnapshots).not.toHaveBeenCalled()
    expect(api.listKnowledgeDocs).toHaveBeenCalledWith({ datasourceId: 2, page: 1, pageSize: 50 })
  })

  it('当前账号仍合法的源和文档偏好保留，不清空所有偏好', async () => {
    localStorage.setItem('dataocean_admin_context', JSON.stringify({ datasourceId: 2, knowledgeDocId: 6 }))
    role(['knowledge:view', 'knowledge:approve'])
    const context = useAdminContextStore()
    await context.initialize()
    expect(context.datasourceId).toBe(2)
    expect(context.knowledgeDocId).toBe(6)
    expect(context.initialized).toBe(true)
    expect(api.listSnapshots).not.toHaveBeenCalled()
  })

  it('元数据角色不因没有知识查看权而无法初始化', async () => {
    role(['metadata:view'])
    api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 22, datasourceId: 2, snapshotVersion: 2, status: 'PUBLISHED' }], total: 1 } })
    const context = useAdminContextStore()
    await context.initialize()
    expect(context.datasourceId).toBe(2)
    expect(context.snapshotId).toBe(22)
    expect(api.listKnowledgeDocs).not.toHaveBeenCalled()
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
  })
})
