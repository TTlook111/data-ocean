import type { RouteLocationRaw } from 'vue-router'
import { ADMIN_WORKSPACES, type AdminContextMode } from '../router/adminNavigation'

interface ReadinessActionResult {
  to: RouteLocationRaw
  known: boolean
}

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
 * 后端 `blockReasons.actionPath` 的旧路径到新前端的集中映射。
 *
 * 每个目标按自己的 `contextMode` 声明接受哪些上下文参数——这是本文件唯一的转换入口，
 * 各页面不得再自行拼装（《实施任务清单》§4）。
 */
const PATH_TARGETS: Record<string, (context: AdminContextSource) => RouteLocationRaw> = {
  '/admin/datasources': ({ datasourceId }) => (
    datasourceId
      ? { path: '/admin/data-sources', query: { focus: datasourceId } }
      : { path: '/admin/data-sources' }
  ),
  // /admin/releases 的 contextMode 是 datasource，不接受 snapshotId。
  '/admin/metadata/lifecycle': (context) => ({
    path: '/admin/releases',
    query: buildContextQuery('datasource', context),
  }),
  // /admin/governance/issues 的 contextMode 是 datasource-snapshot，接受 snapshotId——
  // 治理阻塞问题按已发布快照统计，不带 snapshotId 会落到错误范围。
  '/admin/governance/issues': (context) => ({
    path: '/admin/governance/issues',
    query: buildContextQuery('datasource-snapshot', context),
  }),
  '/admin/knowledge/review': (context) => ({
    path: '/admin/semantics/knowledge',
    query: { tab: 'review', ...buildContextQuery('datasource', context) },
  }),
  '/admin/permission/access': (context) => ({
    path: '/admin/access',
    query: { tab: 'grants', ...buildContextQuery('datasource', context) },
  }),
}

/**
 * 取某个业务域的首个工作区，作为「未映射 actionPath」的安全落点
 * （《实施任务清单》§4「提供返回对应业务域首页的安全入口」）。
 * 该域没有二级工作区时（如工作台）回落到工作台本身。
 */
export function findDomainHome(domainKey: string): { path: string; label: string } {
  const workspace = ADMIN_WORKSPACES.find((item) => item.domainKey === domainKey)
  return workspace
    ? { path: workspace.path, label: workspace.label }
    : { path: '/admin/workbench', label: '工作台' }
}

/**
 * 把 readiness 的 `actionPath` 解析为新前端路由。
 *
 * **调用方必须先判断 `known`。** `known` 为 false 表示该路径未映射，此时 `to` 只是
 * 占位值，不得直接导航——按《实施任务清单》§4，未知路径不得猜测，应显示操作文案
 * 并提供安全入口（用 {@link findDomainHome}），同时已记录日志供后续补充映射。
 */
export function resolveReadinessActionPath(
  actionPath?: string,
  context: AdminContextSource = {},
): ReadinessActionResult {
  if (!actionPath) {
    return { to: '/admin/workbench', known: false }
  }

  const [pathname] = actionPath.split('?')
  const resolver = PATH_TARGETS[pathname]
  if (!resolver) {
    console.warn('[DataOcean] 未映射的 readiness actionPath:', actionPath)
    return { to: '/admin/workbench', known: false }
  }

  return { to: resolver(context), known: true }
}
