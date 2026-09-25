// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import router from '../router'
import { ADMIN_DOMAIN_KEYS, ADMIN_WORKSPACES } from '../router/adminNavigation'
import { buildContextQuery, findDomainHome, resolveReadinessAction } from './adminNavigation'

/** 解析结果落在 catch-all 上即视为不可达（router/index.ts 的 name: 'not-found'） */
function isResolvable(path: string): boolean {
  return router.resolve(path).name !== 'not-found'
}

describe('admin navigation metadata', () => {
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
