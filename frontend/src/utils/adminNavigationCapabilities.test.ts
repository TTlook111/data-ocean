// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { ADMIN_DOMAIN_KEYS, ADMIN_WORKSPACES, type AdminWorkspace } from '../router/adminNavigation'
import {
  findDomainHome,
  groupAdminWorkspaces,
  isWorkspaceVisible,
  visibleAdminWorkspaces,
  type AdminCapabilityLookup,
} from './adminNavigation'

interface LookupOptions {
  loaded?: boolean
  error?: string
  global?: string[]
  onDatasource?: Record<number, string[]>
  anyDatasource?: Record<string, number[]>
}

function lookup(options: LookupOptions = {}): AdminCapabilityLookup {
  const {
    loaded = true,
    error = '',
    global = [],
    onDatasource = {},
    anyDatasource = {},
  } = options
  return {
    loaded,
    errorMessage: error,
    hasGlobal: (code) => global.includes(code),
    canOnDatasource: (code, datasourceId) => (
      datasourceId !== undefined && (onDatasource[datasourceId] || []).includes(code)
    ),
    datasourcesWithFunction: (code) => anyDatasource[code] || [],
  }
}

function workspace(key: string): AdminWorkspace {
  const found = ADMIN_WORKSPACES.find((item) => item.key === key)
  if (!found) throw new Error(`missing workspace ${key}`)
  return found
}

describe('导航能力过滤', () => {
  it('未声明查看能力的工作区始终可见', () => {
    expect(isWorkspaceVisible(workspace('assets'), lookup())).toBe(true)
    expect(isWorkspaceVisible(workspace('queries'), lookup())).toBe(true)
  })

  it('global 作用域按 hasGlobal 判断，anyOf 是 OR', () => {
    const access = workspace('iam-access')
    expect(isWorkspaceVisible(access, lookup({ global: ['security:mask:view'] }))).toBe(true)
    expect(isWorkspaceVisible(access, lookup({ global: ['security:permission:view'] }))).toBe(true)
    expect(isWorkspaceVisible(access, lookup({ global: ['lineage:view'] }))).toBe(false)
  })

  it('datasource 作用域已选数据源时使用 canOnDatasource', () => {
    const lineage = workspace('lineage')
    expect(isWorkspaceVisible(lineage, lookup({ onDatasource: { 5: ['lineage:view'] } }), 5)).toBe(true)
    expect(isWorkspaceVisible(lineage, lookup({ onDatasource: { 5: ['lineage:view'] } }), 6)).toBe(false)
  })

  it('datasource 作用域未选数据源时任一负责源具备能力即可见', () => {
    const lineage = workspace('lineage')
    expect(isWorkspaceVisible(lineage, lookup({ anyDatasource: { 'lineage:view': [5] } }))).toBe(true)
    expect(isWorkspaceVisible(lineage, lookup({ anyDatasource: { 'lineage:view': [] } }))).toBe(false)
  })

  it('global-or-datasource 任一来源命中即可见', () => {
    const rules = workspace('governance-rules')
    expect(isWorkspaceVisible(rules, lookup({ global: ['governance:rule:view'] }))).toBe(true)
    expect(isWorkspaceVisible(rules, lookup({ anyDatasource: { 'governance:rule:view': [5] } }))).toBe(true)
    expect(isWorkspaceVisible(rules, lookup())).toBe(false)
  })

  it('能力摘要未加载或读取失败时 fail-open', () => {
    const lineage = workspace('lineage')
    expect(isWorkspaceVisible(lineage, lookup({ loaded: false }))).toBe(true)
    expect(isWorkspaceVisible(lineage, lookup({ error: '读取失败' }))).toBe(true)
  })

  it('过滤后只保留可见工作区', () => {
    const result = visibleAdminWorkspaces(
      ADMIN_WORKSPACES,
      lookup({ global: ['prompt:view'], anyDatasource: { 'lineage:view': [5] } }),
    )
    expect(result.map((item) => item.key)).toContain('assets')
    expect(result.map((item) => item.key)).toContain('lineage')
    expect(result.map((item) => item.key)).toContain('prompts')
    expect(result.map((item) => item.key)).not.toContain('governance')
    expect(result.map((item) => item.key)).not.toContain('ai')
  })

  it('治理域分组保持顺序并带标签', () => {
    const governance = ADMIN_WORKSPACES.filter((item) => item.domainKey === ADMIN_DOMAIN_KEYS.governance)
    const groups = groupAdminWorkspaces(governance)

    expect(groups.map((group) => group.label)).toEqual(['结果与问题', '规则与状态', '字段治理'])
    expect(groups[0].items.map((item) => item.key)).toEqual(['governance', 'governance-issues'])
    expect(groups[1].items.map((item) => item.key)).toEqual(['governance-rules', 'governance-status'])
    expect(groups[2].items.map((item) => item.key)).toEqual(['governance-fields'])
  })

  it('无分组工作区合并为一个无标题组', () => {
    const groups = groupAdminWorkspaces(ADMIN_WORKSPACES.filter((item) => item.domainKey === ADMIN_DOMAIN_KEYS.dataAssets))
    expect(groups).toHaveLength(1)
    expect(groups[0].label).toBeUndefined()
    expect(groups[0].items).toHaveLength(3)
  })

  it('一级入口使用可见列表中的首个工作区', () => {
    const visible = ADMIN_WORKSPACES.filter((item) => ['governance-issues'].includes(item.key))
    expect(findDomainHome(ADMIN_DOMAIN_KEYS.governance, visible)).toEqual({
      path: '/admin/governance/issues',
      label: '问题中心',
    })
  })
})
