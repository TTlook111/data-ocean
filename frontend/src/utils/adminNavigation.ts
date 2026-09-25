import type { RouteLocationRaw } from 'vue-router'
import { ADMIN_WORKSPACES, type AdminContextMode } from '../router/adminNavigation'

/**
 * 可跨工作区继承的后台上下文。
 *
 * 只包含数据源与快照，不包含 tab、page、筛选项等页面本地状态
 * （见《实施任务清单》§3：前者是可继承的上下文，后者是页面自己的筛选参数）。
 */
export interface AdminContextSource {
  datasourceId?: number
  snapshotId?: number
}

/**
 * 按目标页面的上下文模式构造要继承的查询参数。
 *
 * 用白名单逐个构造，而不是复制来源 query 再删减——否则 tab、page、筛选项会泄漏到
 * 不认识它们的页面，命中《开发指导》§18「不显示不生效的数据源或快照选择器」。
 *
 * 裁剪必须无条件执行：从 `locked-resource` 详情页（如 `/admin/releases/snapshots/:id`）
 * 点走时，来源 query 里会带着该页面的 snapshotId。
 */
export function buildContextQuery(
  contextMode: AdminContextMode | undefined,
  source: AdminContextSource,
): Record<string, string> {
  const query: Record<string, string> = {}
  const acceptsDatasource = contextMode === 'datasource' || contextMode === 'datasource-snapshot'
  if (acceptsDatasource && source.datasourceId) {
    query.datasourceId = String(source.datasourceId)
  }
  if (contextMode === 'datasource-snapshot' && source.snapshotId) {
    query.snapshotId = String(source.snapshotId)
  }
  return query
}

/** 按目标路径查它在 `ADMIN_WORKSPACES` 中声明的上下文模式。 */
export function findWorkspaceContextMode(path: string): AdminContextMode | undefined {
  return ADMIN_WORKSPACES.find((item) => item.path === path)?.contextMode
}

/**
 * 将 readiness 状态码落到当前正式工作区。
 *
 * 后端历史 actionPath 属于旧后台 URL，正式前端不再依赖或兼容它；动作目标由状态码
 * 表达的业务阶段决定，并按目标工作区的 contextMode 构造上下文参数。
 */
export function resolveReadinessAction(
  code: string | undefined,
  context: AdminContextSource = {},
): RouteLocationRaw | undefined {
  switch (code) {
    case 'DATASOURCE_DISABLED':
    case 'CONNECTION_NOT_HEALTHY':
      return context.datasourceId
        ? { path: `/admin/data-sources/${context.datasourceId}` }
        : { path: '/admin/data-sources' }
    case 'SNAPSHOT_NOT_PUBLISHED':
      return { path: '/admin/releases', query: buildContextQuery('datasource', context) }
    case 'BLOCKING_GOVERNANCE_ISSUES':
      return { path: '/admin/governance/issues', query: buildContextQuery('datasource-snapshot', context) }
    case 'KNOWLEDGE_NOT_PUBLISHED':
      return {
        path: '/admin/semantics/knowledge',
        query: { tab: 'review', ...buildContextQuery('datasource', context) },
      }
    case 'QUERY_PERMISSION_NOT_CONFIGURED':
      // 目标改为 IAM-SIMPLE-1 的授权配置页。旧页面读 ?tab=，新页面不读
      // （IamS1AccessWorkspaceView 直接默认落在 grants 页签），因此不再传 tab。
      // datasourceId 仍带上，该页会用它预选数据源。
      return {
        path: '/admin/access/iam',
        query: buildContextQuery('datasource', context),
      }
    default:
      return undefined
  }
}

/**
 * 取某个业务域的首个工作区，作为未知 readiness 状态的安全落点。
 * 该域没有二级工作区时（如工作台）回落到工作台本身。
 */
export function findDomainHome(domainKey: string): { path: string; label: string } {
  const workspace = ADMIN_WORKSPACES.find((item) => item.domainKey === domainKey)
  return workspace
    ? { path: workspace.path, label: workspace.label }
    : { path: '/admin/workbench', label: '工作台' }
}
