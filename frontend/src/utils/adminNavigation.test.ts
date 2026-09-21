import { describe, expect, it } from 'vitest'
import { ADMIN_DOMAIN_KEYS, ADMIN_WORKSPACES } from '../router/adminNavigation'
import { buildContextQuery, findDomainHome, resolveReadinessAction } from './adminNavigation'

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
    expect(ADMIN_WORKSPACES.find((item) => item.key === 'organization')?.label)
      .toBe('组织与角色（旧）')
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
