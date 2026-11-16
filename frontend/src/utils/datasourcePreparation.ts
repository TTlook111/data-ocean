import type { DatasourceReadiness } from '../api/admin/datasource'
import { findWorkspaceByPath } from '../router/adminNavigation'

type TaskCapabilities = {
  loaded: boolean
  errorMessage: string | null
  hasGlobal: (code: string) => boolean
  canOnDatasource: (code: string, datasourceId?: number) => boolean
}

/** A task link keeps this datasource; unlike global navigation it cannot switch to another authorized source. */
export function canOpenDatasourceWorkspace(path: string, iam: TaskCapabilities, datasourceId: number) {
  if (!iam.loaded || iam.errorMessage) return false
  const requirement = findWorkspaceByPath(path)?.viewCapability
  if (!requirement) return false
  return requirement.anyOf.some(code => requirement.scope === 'global'
    ? iam.hasGlobal(code) : iam.canOnDatasource(code, datasourceId))
}

export function datasourcePreparationFacts(r: DatasourceReadiness) {
  return [
    { key: 'connection', title: '连接', state: r.connectionReady ? '健康检查已通过' : '连接待处理', ready: r.connectionReady,
      description: '确认只读数据库连接与启用状态；连接成功不代表已经采集。', evidence: '' },
    { key: 'collection', title: '采集结构', state: r.latestCollectedSnapshotId ? '已有采集快照' : '尚无采集快照', ready: Boolean(r.latestCollectedSnapshotId),
      description: '取得表与字段结构，作为后续业务解释和审核的范围。', evidence: r.latestCollectedSnapshotId ? `最近采集 v${r.latestCollectedSnapshotVersion ?? '—'}` : '' },
    { key: 'governance', title: '治理与核对', state: !r.publishedSnapshotId ? '尚无发布范围' : r.governanceReady ? '当前无阻断问题' : '存在阻断问题', ready: Boolean(r.publishedSnapshotId) && r.governanceReady,
      description: '查看当前发布范围的治理问题；无阻断不等于所有字段含义已审核。', evidence: '' },
    { key: 'publication', title: '审核与发布', state: r.metadataReady ? '元数据已发布' : '元数据待发布', ready: r.metadataReady,
      description: '元数据快照与业务知识分别审核发布，发布不会自动构建索引。',
      evidence: `${r.publishedSnapshotId ? `正式快照 v${r.snapshotVersion ?? '—'}` : '尚无正式快照'} · ${r.publishedKnowledgeDocId ? `最近知识文档 v${r.knowledgeVersion ?? '—'} 已发布（非全部文档状态）` : '尚无已发布知识文档'}` },
    { key: 'index', title: '构建知识索引', state: r.knowledgeReady ? r.ragStale ? '活动索引来源落后' : '已有活动索引' : '尚无活动索引', ready: r.knowledgeReady && !r.ragStale,
      description: '在知识工作区显式构建 RAG，供检索和规划使用；与问数准入分别判断。',
      evidence: r.ragNotice || (r.ragBuildId ? `活动构建 ${r.ragBuildId}` : '暂无服务端索引说明') },
    { key: 'query', title: '授权与问数', state: r.askable ? '后台判定可问数' : '问数条件未满足', ready: r.askable,
      description: '实际提问仍按使用者当前授权、字段范围与执行保护核验。',
      evidence: r.permissionReady ? '已有有效数据授权配置，不代表当前查看账号拥有查询权。' : '尚未配置有效查询授权。' },
  ]
}
