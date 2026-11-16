import { describe, expect, it } from 'vitest'
import { datasourcePreparationFacts, canOpenDatasourceWorkspace } from './datasourcePreparation'
import type { DatasourceReadiness } from '../api/admin/datasource'

const readiness: DatasourceReadiness = { datasourceId: 1, datasourceName: '合成源', stage: 'ASKABLE', stageLabel: '可询问', progress: 100,
  connectionReady: true, metadataReady: true, governanceReady: true, knowledgeReady: false, permissionReady: true, askable: true, blockReasons: [], publishedSnapshotId: 11, snapshotVersion: 3, ragStatus: 'NONE' }
describe('数据源事实与任务入口', () => {
  it('可问数与无活动索引并存，授权配置不冒充当前账号查询权', () => {
    const facts = datasourcePreparationFacts(readiness)
    expect(facts.find(item => item.key === 'query')).toMatchObject({ ready: true, state: '后台判定可问数' })
    expect(facts.find(item => item.key === 'index')).toMatchObject({ ready: false, state: '尚无活动索引' })
    expect(facts.find(item => item.key === 'query')?.evidence).toContain('不代表当前查看账号')
  })
  it('无发布范围时不把治理布尔值当成已完成；发布与索引来源分别显示', () => {
    const facts = datasourcePreparationFacts({ ...readiness, metadataReady: false, publishedSnapshotId: undefined, latestCollectedSnapshotId: 12, latestCollectedSnapshotVersion: 4, knowledgeReady: true, ragStale: true })
    expect(facts.find(item => item.key === 'governance')).toMatchObject({ ready: false, state: '尚无发布范围' })
    expect(facts.find(item => item.key === 'collection')?.evidence).toBe('最近采集 v4')
    expect(facts.find(item => item.key === 'index')?.state).toBe('活动索引来源落后')
  })
  it('任务链接不借用别源能力，能力读取失败不猜测入口', () => {
    const iam = { loaded: true, errorMessage: null as string | null, hasGlobal: () => true, canOnDatasource: (_code: string, id?: number) => id === 2 }
    expect(canOpenDatasourceWorkspace('/admin/semantics/knowledge', iam, 1)).toBe(false)
    expect(canOpenDatasourceWorkspace('/admin/semantics/knowledge', iam, 2)).toBe(true)
    expect(canOpenDatasourceWorkspace('/admin/not-real', iam, 2)).toBe(false)
    iam.errorMessage = '失败'
    expect(canOpenDatasourceWorkspace('/admin/semantics/knowledge', iam, 2)).toBe(false)
  })
})
