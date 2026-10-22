import type { RouteLocationRaw } from 'vue-router'
import {
  ADMIN_WORKSPACES,
  findWorkspace,
  findWorkspaceByPath,
  type AdminContextMode,
  type AdminViewCapability,
  type AdminWorkspace,
} from '../router/adminNavigation'

/**
 * 导航可见性判断所需的最小能力摘要接口。
 *
 * 只依赖读取方法，便于在纯函数测试中替换 store，也避免导航工具直接绑定 Pinia。
 */
export interface AdminCapabilityLookup {
  loaded: boolean
  errorMessage?: string | null
  hasGlobal(code: string): boolean
  canOnDatasource(code: string, datasourceId?: number): boolean
  datasourcesWithFunction(code: string): number[]
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
 *
 * 导航传入选定后的可见工作区，避免一级入口落到被能力隐藏的首项。
 */
export function findDomainHome(
  domainKey: string,
  workspaces: AdminWorkspace[] = ADMIN_WORKSPACES,
): { path: string; label: string } {
  const workspace = workspaces.find((item) => item.domainKey === domainKey)
  return workspace
    ? { path: workspace.path, label: workspace.label }
    : { path: '/admin/workbench', label: '工作台' }
}

function capabilitySatisfied(
  capability: AdminViewCapability,
  lookup: AdminCapabilityLookup,
  selectedDatasourceId?: number,
): boolean {
  const globalHit = capability.anyOf.some((code) => lookup.hasGlobal(code))
  const datasourceHit = capability.anyOf.some((code) => (
    selectedDatasourceId !== undefined
      ? lookup.canOnDatasource(code, selectedDatasourceId)
      : lookup.datasourcesWithFunction(code).length > 0
  ))
  switch (capability.scope) {
    case 'global':
      return globalHit
    case 'datasource':
      return datasourceHit
    case 'global-or-datasource':
      return globalHit || datasourceHit
    default:
      return true
  }
}

/**
 * 工作区是否应出现在左侧导航。
 *
 * - 未声明查看能力：始终可见（这些页面目前只受后台准入控制）。
 * - 能力摘要未加载或读取失败：fail-open，保持可见；后端仍是安全边界。
 */
export function isWorkspaceVisible(
  workspace: AdminWorkspace,
  capabilities: AdminCapabilityLookup,
  selectedDatasourceId?: number,
): boolean {
  if (!workspace.viewCapability) return true
  if (!capabilities.loaded || capabilities.errorMessage) return true
  return capabilitySatisfied(workspace.viewCapability, capabilities, selectedDatasourceId)
}

export function visibleAdminWorkspaces(
  workspaces: AdminWorkspace[],
  capabilities: AdminCapabilityLookup,
  selectedDatasourceId?: number,
): AdminWorkspace[] {
  return workspaces.filter((workspace) => isWorkspaceVisible(workspace, capabilities, selectedDatasourceId))
}

export interface AdminBreadcrumbItem {
  key: string
  label: string
  to?: RouteLocationRaw
  current?: boolean
}

export interface AdminBreadcrumbInput {
  title?: string
  workspaceKey?: string
  breadcrumbParent?: string
  query?: Record<string, unknown>
  workspaces?: AdminWorkspace[]
}

function queryNumber(value: unknown): number | undefined {
  const raw = Array.isArray(value) ? value[0] : value
  const parsed = Number(raw)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined
}

/**
 * 由路由 meta、工作区元数据和白名单上下文生成后台面包屑。
 *
 * 父级只来自 `breadcrumbParent` 或当前工作区，不按路径前缀猜测；上下文只保留
 * datasourceId/snapshotId，并交给 buildContextQuery 按目标 contextMode 过滤。
 */
export function buildAdminBreadcrumbs(input: AdminBreadcrumbInput): AdminBreadcrumbItem[] {
  const workspaces = input.workspaces ?? ADMIN_WORKSPACES
  const title = String(input.title || 'DataOcean 后台')
  const query: AdminContextSource = {
    datasourceId: queryNumber(input.query?.datasourceId),
    snapshotId: queryNumber(input.query?.snapshotId),
  }
  const items: AdminBreadcrumbItem[] = [
    { key: 'home', label: 'DataOcean', to: { path: '/admin/workbench' } },
  ]

  const appendParent = (workspace: AdminWorkspace | undefined) => {
    if (!workspace || workspace.label === title) return
    items.push({
      key: `workspace-${workspace.key}`,
      label: workspace.label,
      to: { path: workspace.path, query: buildContextQuery(workspace.contextMode, query) },
    })
  }

  if (input.breadcrumbParent) {
    appendParent(findWorkspaceByPath(input.breadcrumbParent, workspaces))
  } else {
    appendParent(findWorkspace(input.workspaceKey, workspaces))
  }

  items.push({ key: 'current', label: title, current: true })
  return items
}

export interface AdminWorkspaceGroup {
  key: string
  label?: string
  items: AdminWorkspace[]
}

/**
 * 按 groupKey 分组，保持原数组顺序；相邻的无分组工作区合并为一个无标题组。
 */
export function groupAdminWorkspaces(workspaces: AdminWorkspace[]): AdminWorkspaceGroup[] {
  const groups: AdminWorkspaceGroup[] = []
  for (const workspace of workspaces) {
    const key = workspace.groupKey || '__ungrouped__'
    const last = groups[groups.length - 1]
    if (last && last.key === key) {
      last.items.push(workspace)
      continue
    }
    groups.push({ key, label: workspace.groupLabel, items: [workspace] })
  }
  return groups
}
