// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import router from '../router'
import { ADMIN_DOMAIN_KEYS, ADMIN_WORKSPACES, findWorkspaceByPath } from '../router/adminNavigation'
import { buildAdminBreadcrumbs, buildContextQuery, findDomainHome, resolveReadinessAction } from './adminNavigation'

/** 解析结果落在 catch-all 上即视为不可达（router/index.ts 的 name: 'not-found'） */
function isResolvable(path: string): boolean {
  return router.resolve(path).name !== 'not-found'
}

describe('admin navigation metadata', () => {
  it('Prompt 策略归入运营与平台，并保留旧链接的模板与页签', () => {
    expect(findWorkspaceByPath('/admin/platform/prompts')).toMatchObject({
      key: 'prompts', domainKey: ADMIN_DOMAIN_KEYS.operations, contextMode: 'none',
      groupKey: 'operations-platform', viewCapability: { anyOf: ['prompt:view'], scope: 'global' },
    })
    expect(findWorkspaceByPath('/admin/semantics/prompts')).toBeUndefined()
    expect(router.resolve('/admin/platform/prompts').meta).toMatchObject({
      domainKey: 'operations', workspaceKey: 'prompts', contextMode: 'none',
    })
    const legacy = router.resolve({ path: '/admin/semantics/prompts', query: { code: 'sql_generation', tab: 'versions' }, hash: '#history' })
    const redirect = legacy.matched[legacy.matched.length - 1]?.redirect
    if (typeof redirect !== 'function') throw new Error('旧 Prompt 链接没有兼容跳转')
    expect(redirect(legacy, router.currentRoute.value)).toEqual({
      path: '/admin/platform/prompts', query: { code: 'sql_generation', tab: 'versions' }, hash: '#history',
    })
  })

  it('覆盖七个一级业务域下的正式工作区', () => {
    expect(new Set(ADMIN_WORKSPACES.map((item) => item.domainKey))).toEqual(new Set([
      ADMIN_DOMAIN_KEYS.dataEntry,
      ADMIN_DOMAIN_KEYS.dataAssets,
      ADMIN_DOMAIN_KEYS.governance,
      ADMIN_DOMAIN_KEYS.semantics,
      ADMIN_DOMAIN_KEYS.access,
      ADMIN_DOMAIN_KEYS.operations,
    ]))
    expect(ADMIN_WORKSPACES.map((item) => item.path)).not.toContain('/admin/datasources')
    expect(ADMIN_WORKSPACES.find((item) => item.key === 'iam-organization')?.label)
      .toBe('组织、角色与负责源')
    expect(ADMIN_WORKSPACES.find((item) => item.key === 'iam-access')?.path)
      .toBe('/admin/access/iam')
    expect(ADMIN_WORKSPACES.find((item) => item.key === 'iam-approvals')?.path)
      .toBe('/admin/access/iam-approvals')
    expect(ADMIN_WORKSPACES.map((item) => item.path)).not.toContain('/admin/access')
    expect(ADMIN_WORKSPACES.map((item) => item.path)).not.toContain('/admin/access/approvals')
    expect(ADMIN_WORKSPACES.find((item) => item.key === 'organization')).toBeUndefined()
    expect(ADMIN_WORKSPACES.map((item) => item.path)).not.toContain('/admin/access/organization')
  })

  it('治理规则只保留一个管理入口，旧检查类型链接回到规则页内说明', () => {
    expect(ADMIN_WORKSPACES.some(item => item.key === 'rule-templates')).toBe(false)
    expect(findWorkspaceByPath('/admin/governance/rules')?.label).toBe('质量规则')
    const legacy = router.resolve({ path: '/admin/governance/rule-templates', query: { datasourceId: '5', snapshotId: '8' } })
    const redirect = legacy.matched[legacy.matched.length - 1]?.redirect
    expect(typeof redirect).toBe('function')
    if (typeof redirect !== 'function') throw new Error('旧链接没有兼容跳转')
    expect(redirect(legacy, router.currentRoute.value)).toEqual({ path: '/admin/governance/rules', query: { datasourceId: '5', snapshotId: '8', showChecks: '1' } })
  })

  it('按目标工作区的 contextMode 白名单继承上下文', () => {
    expect(buildContextQuery('datasource-snapshot', { datasourceId: 7, snapshotId: 11 })).toEqual({ datasourceId: '7', snapshotId: '11' })
    expect(buildContextQuery('datasource', { datasourceId: 7, snapshotId: 11 })).toEqual({ datasourceId: '7' })
    expect(buildContextQuery('none', { datasourceId: 7, snapshotId: 11 })).toEqual({})
  })

  it('使用正式工作区处理 readiness 动作，不依赖旧 URL', () => {
    expect(resolveReadinessAction('BLOCKING_GOVERNANCE_ISSUES', { datasourceId: 7, snapshotId: 11 })).toEqual({
      path: '/admin/governance/issues',
      query: { datasourceId: '7', snapshotId: '11' },
    })
    expect(resolveReadinessAction('CURRENT_USER_NOT_GRANTED', { datasourceId: 7 })).toBeUndefined()
    expect(findDomainHome(ADMIN_DOMAIN_KEYS.workbench)).toEqual({ path: '/admin/workbench', label: '工作台' })
  })
})

describe('后台面包屑', () => {
  it('工作台显示首页和当前页', () => {
    expect(buildAdminBreadcrumbs({ title: '工作台', workspaceKey: 'workbench' })).toEqual([
      { key: 'home', label: 'DataOcean', to: { path: '/admin/workbench' } },
      { key: 'current', label: '工作台', current: true },
    ])
  })

  it('工作区首页不重复渲染同名工作区', () => {
    const items = buildAdminBreadcrumbs({ title: '问题中心', workspaceKey: 'governance-issues' })
    expect(items).toHaveLength(2)
    expect(items[1]).toMatchObject({ label: '问题中心', current: true })
  })

  it('详情页的父级是工作区链接，并按父级 contextMode 过滤上下文', () => {
    const items = buildAdminBreadcrumbs({
      title: '对象详情',
      workspaceKey: 'assets',
      breadcrumbParent: '/admin/assets',
      query: { datasourceId: '7', snapshotId: '11', tab: 'lineage' },
    })

    expect(items).toHaveLength(3)
    expect(items[1]).toEqual({
      key: 'workspace-assets',
      label: '数据对象目录',
      to: { path: '/admin/assets', query: { datasourceId: '7' } },
    })
    expect(items[2]).toMatchObject({ label: '对象详情', current: true })
  })

  it('父级 contextMode 为 none 时不携带任何查询参数', () => {
    const items = buildAdminBreadcrumbs({
      title: '数据源详情',
      workspaceKey: 'data-sources',
      breadcrumbParent: '/admin/data-sources',
      query: { datasourceId: '7', snapshotId: '11' },
    })
    expect(items[1].to).toEqual({ path: '/admin/data-sources', query: {} })
  })

  it('数组形式的数据源和快照 query 会归一化', () => {
    const items = buildAdminBreadcrumbs({
      title: '快照详情',
      workspaceKey: 'releases',
      breadcrumbParent: '/admin/releases',
      query: { datasourceId: ['7', '8'], snapshotId: ['11'] },
    })
    expect(items[1].to).toEqual({ path: '/admin/releases', query: { datasourceId: '7' } })
  })

  it('未知父级不生成坏链接', () => {
    const items = buildAdminBreadcrumbs({
      title: '未知页面',
      workspaceKey: 'missing',
      breadcrumbParent: '/admin/not-a-workspace',
    })
    expect(items).toHaveLength(2)
    expect(items[1]).toMatchObject({ label: '未知页面', current: true })
  })

  it('所有 locked-resource 路由声明了可解析且属于工作区的父级', () => {
    const lockedRoutes = router.getRoutes()
      .filter((item) => item.meta?.contextMode === 'locked-resource')
    expect(lockedRoutes.length).toBeGreaterThan(0)
    for (const item of lockedRoutes) {
      const parent = String(item.meta?.breadcrumbParent || '')
      expect(parent, `${String(item.name)} 缺少 breadcrumbParent`).not.toBe('')
      expect(isResolvable(parent), `${String(item.name)} 的父级 ${parent} 不可解析`).toBe(true)
      expect(findWorkspaceByPath(parent), `${String(item.name)} 的父级 ${parent} 不是工作区`).toBeTruthy()
      expect(parent).not.toBe(item.path)
    }
  })
})

/**
 * 契约：导航上能点的每一个目标都必须解析到真实路由。
 *
 * 缺口背景（2026-09-25）：一级导航「权限与组织」曾指向裸域路径 /admin/access，
 * 而路由表里只有 /admin/access/iam 等子路径 → 点击落到 NotFound。
 * 当时没有任何测试覆盖这项不变量，所以一直没被发现。
 */
describe('导航目标可解析性', () => {
  it('每个一级业务域的首页都解析到真实路由', () => {
    for (const domainKey of Object.values(ADMIN_DOMAIN_KEYS)) {
      const home = findDomainHome(domainKey)
      expect(isResolvable(home.path), `一级域 ${domainKey} 的首页 ${home.path} 解析不到路由`).toBe(true)
    }
  })

  it('每个二级工作区路径都解析到真实路由', () => {
    expect(ADMIN_WORKSPACES.length).toBeGreaterThan(0)
    for (const workspace of ADMIN_WORKSPACES) {
      expect(isResolvable(workspace.path), `工作区 ${workspace.key} 的 ${workspace.path} 解析不到路由`).toBe(true)
    }
  })

  it('readiness 动作落在真实路由上，且不再指向已移除的裸域路径', () => {
    // 整体相等断言同时覆盖 path 与 query：query 里不得再出现 tab
    // （旧页面读 ?tab=，新页面 IamS1AccessWorkspaceView 不读）
    expect(resolveReadinessAction('QUERY_PERMISSION_NOT_CONFIGURED', { datasourceId: 7 })).toEqual({
      path: '/admin/access/iam',
      query: { datasourceId: '7' },
    })

    // 旧目标不存在于路由表（解析为 catch-all）。这条断言把回归钉死：
    // 任何入口再写回 /admin/access 都会被它挡下。
    expect(isResolvable('/admin/access')).toBe(false)
    expect(isResolvable('/admin/access/iam')).toBe(true)
  })
})
