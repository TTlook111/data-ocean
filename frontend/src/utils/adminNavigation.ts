import type { RouteLocationRaw } from 'vue-router'

interface ReadinessActionResult {
  to: RouteLocationRaw
  known: boolean
}

const PATH_TARGETS: Record<string, (datasourceId?: number) => RouteLocationRaw> = {
  '/admin/datasources': (datasourceId) => datasourceId
    ? { path: '/admin/data-sources', query: { focus: datasourceId } }
    : '/admin/data-sources',
  '/admin/metadata/lifecycle': (datasourceId) => datasourceId
    ? { path: '/admin/releases', query: { datasourceId } }
    : '/admin/releases',
  '/admin/governance/issues': (datasourceId) => datasourceId
    ? { path: '/admin/governance/issues', query: { datasourceId } }
    : '/admin/governance/issues',
  '/admin/knowledge/review': (datasourceId) => datasourceId
    ? { path: '/admin/semantics/knowledge', query: { tab: 'review', datasourceId } }
    : { path: '/admin/semantics/knowledge', query: { tab: 'review' } },
  '/admin/permission/access': (datasourceId) => datasourceId
    ? { path: '/admin/access', query: { tab: 'grants', datasourceId } }
    : { path: '/admin/access', query: { tab: 'grants' } },
}

export function resolveReadinessActionPath(actionPath?: string, datasourceId?: number): ReadinessActionResult {
  if (!actionPath) {
    return { to: '/admin/workbench', known: false }
  }

  const [pathname] = actionPath.split('?')
  const resolver = PATH_TARGETS[pathname]
  if (!resolver) {
    console.warn('[DataOcean] 未映射的 readiness actionPath:', actionPath)
    return { to: '/admin/workbench', known: false }
  }

  return { to: resolver(datasourceId), known: true }
}
