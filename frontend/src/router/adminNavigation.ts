import type { Component } from 'vue'

export type AdminContextMode = 'none' | 'datasource' | 'datasource-snapshot' | 'locked-resource'

export interface AdminRouteMeta {
  title: string
  domainKey: string
  workspaceKey: string
  contextMode: AdminContextMode
  breadcrumbParent?: string
}

export interface AdminDomain {
  key: string
  label: string
  path: string
  icon: Component
}

export interface AdminWorkspace {
  key: string
  domainKey: string
  label: string
  path: string
  contextMode: AdminContextMode
}

export const ADMIN_DOMAIN_KEYS = {
  workbench: 'workbench',
  dataEntry: 'data-entry',
  dataAssets: 'data-assets',
  governance: 'governance',
  semantics: 'semantics',
  access: 'access',
  operations: 'operations',
} as const

export const ADMIN_WORKSPACES: AdminWorkspace[] = [
  { key: 'data-sources', domainKey: ADMIN_DOMAIN_KEYS.dataEntry, label: '数据源', path: '/admin/data-sources', contextMode: 'none' },
  { key: 'collections', domainKey: ADMIN_DOMAIN_KEYS.dataEntry, label: '采集任务', path: '/admin/collections', contextMode: 'datasource' },
  { key: 'assets', domainKey: ADMIN_DOMAIN_KEYS.dataAssets, label: '资产目录', path: '/admin/assets', contextMode: 'datasource' },
  { key: 'releases', domainKey: ADMIN_DOMAIN_KEYS.dataAssets, label: '版本发布', path: '/admin/releases', contextMode: 'datasource' },
  { key: 'governance', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '治理总览', path: '/admin/governance', contextMode: 'datasource-snapshot' },
  { key: 'governance-issues', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '问题中心', path: '/admin/governance/issues', contextMode: 'datasource-snapshot' },
  { key: 'governance-rules', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '规则与状态', path: '/admin/governance/rules', contextMode: 'datasource-snapshot' },
  { key: 'governance-fields', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '字段治理', path: '/admin/governance/fields', contextMode: 'datasource-snapshot' },
  { key: 'glossaries', domainKey: ADMIN_DOMAIN_KEYS.semantics, label: '业务术语', path: '/admin/semantics/glossaries', contextMode: 'none' },
  { key: 'knowledge', domainKey: ADMIN_DOMAIN_KEYS.semantics, label: '语义知识', path: '/admin/semantics/knowledge', contextMode: 'datasource' },
  { key: 'prompts', domainKey: ADMIN_DOMAIN_KEYS.semantics, label: 'Prompt 策略', path: '/admin/semantics/prompts', contextMode: 'none' },
  { key: 'access', domainKey: ADMIN_DOMAIN_KEYS.access, label: '授权管理', path: '/admin/access', contextMode: 'datasource' },
  { key: 'access-approvals', domainKey: ADMIN_DOMAIN_KEYS.access, label: '访问审批', path: '/admin/access/approvals', contextMode: 'none' },
  { key: 'organization', domainKey: ADMIN_DOMAIN_KEYS.access, label: '组织与角色', path: '/admin/access/organization', contextMode: 'none' },
  { key: 'queries', domainKey: ADMIN_DOMAIN_KEYS.operations, label: '查询分析', path: '/admin/operations/queries', contextMode: 'none' },
  { key: 'lineage', domainKey: ADMIN_DOMAIN_KEYS.operations, label: '数据血缘', path: '/admin/operations/lineage', contextMode: 'datasource' },
  { key: 'runtime', domainKey: ADMIN_DOMAIN_KEYS.operations, label: '运行监控', path: '/admin/platform/runtime', contextMode: 'none' },
  { key: 'operation-logs', domainKey: ADMIN_DOMAIN_KEYS.operations, label: '操作日志', path: '/admin/platform/operation-logs', contextMode: 'none' },
  { key: 'ai', domainKey: ADMIN_DOMAIN_KEYS.operations, label: 'AI 配置', path: '/admin/platform/ai', contextMode: 'none' },
]

export function findWorkspace(key?: string) {
  return ADMIN_WORKSPACES.find((item) => item.key === key)
}
