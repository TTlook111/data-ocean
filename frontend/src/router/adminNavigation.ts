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

export type AdminCapabilityScope = 'global' | 'datasource' | 'global-or-datasource'

export interface AdminViewCapability {
  /** 任一功能码满足即可（OR）。 */
  anyOf: string[]
  scope: AdminCapabilityScope
}

export interface AdminWorkspace {
  key: string
  domainKey: string
  label: string
  path: string
  contextMode: AdminContextMode
  /** 查看该工作区所需的 IAM 能力；未配置表示始终可见（后端仍是最终边界）。 */
  viewCapability?: AdminViewCapability
  /** 域内分组键；同一域内相邻且相同的键渲染为一个分组。 */
  groupKey?: string
  groupLabel?: string
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
  { key: 'assets', domainKey: ADMIN_DOMAIN_KEYS.dataAssets, label: '数据对象目录', path: '/admin/assets', contextMode: 'datasource' },
  { key: 'lineage', domainKey: ADMIN_DOMAIN_KEYS.dataAssets, label: '数据血缘', path: '/admin/assets/lineage', contextMode: 'datasource', viewCapability: { anyOf: ['lineage:view'], scope: 'datasource' } },
  { key: 'releases', domainKey: ADMIN_DOMAIN_KEYS.dataAssets, label: '元数据版本', path: '/admin/releases', contextMode: 'datasource', viewCapability: { anyOf: ['metadata:release:view'], scope: 'datasource' } },
  { key: 'governance', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '治理总览', path: '/admin/governance', contextMode: 'datasource-snapshot', viewCapability: { anyOf: ['governance:view'], scope: 'datasource' }, groupKey: 'governance-outcome', groupLabel: '结果与问题' },
  { key: 'governance-issues', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '问题中心', path: '/admin/governance/issues', contextMode: 'datasource-snapshot', viewCapability: { anyOf: ['governance:issue:view'], scope: 'datasource' }, groupKey: 'governance-outcome', groupLabel: '结果与问题' },
  { key: 'governance-rules', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '质量规则', path: '/admin/governance/rules', contextMode: 'datasource-snapshot', viewCapability: { anyOf: ['governance:rule:view'], scope: 'global-or-datasource' }, groupKey: 'governance-rules', groupLabel: '规则与状态' },
  { key: 'governance-status', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '治理状态维护', path: '/admin/governance/status', contextMode: 'datasource-snapshot', viewCapability: { anyOf: ['metadata:view'], scope: 'datasource' }, groupKey: 'governance-rules', groupLabel: '规则与状态' },
  { key: 'governance-fields', domainKey: ADMIN_DOMAIN_KEYS.governance, label: '字段治理', path: '/admin/governance/fields', contextMode: 'datasource-snapshot', viewCapability: { anyOf: ['security:mask:view', 'governance:field:view'], scope: 'datasource' }, groupKey: 'governance-fields', groupLabel: '字段治理' },
  { key: 'glossaries', domainKey: ADMIN_DOMAIN_KEYS.semantics, label: '业务术语', path: '/admin/semantics/glossaries', contextMode: 'none', viewCapability: { anyOf: ['glossary:view'], scope: 'global' } },
  { key: 'knowledge', domainKey: ADMIN_DOMAIN_KEYS.semantics, label: '语义知识', path: '/admin/semantics/knowledge', contextMode: 'datasource', viewCapability: { anyOf: ['knowledge:view'], scope: 'datasource' } },
  { key: 'prompts', domainKey: ADMIN_DOMAIN_KEYS.semantics, label: 'Prompt 策略', path: '/admin/semantics/prompts', contextMode: 'none', viewCapability: { anyOf: ['prompt:view'], scope: 'global' } },
  { key: 'iam-access', domainKey: ADMIN_DOMAIN_KEYS.access, label: '授权配置', path: '/admin/access/iam', contextMode: 'none', viewCapability: { anyOf: ['security:permission:view', 'security:mask:view', 'security:effective:view'], scope: 'global' } },
  { key: 'iam-approvals', domainKey: ADMIN_DOMAIN_KEYS.access, label: '访问申请与审批', path: '/admin/access/iam-approvals', contextMode: 'none', viewCapability: { anyOf: ['security:approval:view'], scope: 'global' } },
  { key: 'iam-organization', domainKey: ADMIN_DOMAIN_KEYS.access, label: '组织、角色与负责源', path: '/admin/access/iam-organization', contextMode: 'none', viewCapability: { anyOf: ['organization:user:view', 'organization:role:view', 'organization:department:view', 'organization:permission:view'], scope: 'global' } },
  { key: 'queries', domainKey: ADMIN_DOMAIN_KEYS.operations, label: '查询分析', path: '/admin/operations/queries', contextMode: 'none' },
  { key: 'runtime', domainKey: ADMIN_DOMAIN_KEYS.operations, label: '运行监控', path: '/admin/platform/runtime', contextMode: 'none', viewCapability: { anyOf: ['system:runtime:view'], scope: 'global' } },
  { key: 'operation-logs', domainKey: ADMIN_DOMAIN_KEYS.operations, label: '操作日志', path: '/admin/platform/operation-logs', contextMode: 'none', viewCapability: { anyOf: ['operation-log:view'], scope: 'global' } },
  { key: 'ai', domainKey: ADMIN_DOMAIN_KEYS.operations, label: 'AI 配置', path: '/admin/platform/ai', contextMode: 'none', viewCapability: { anyOf: ['system:ai-config:view'], scope: 'global' } },
]

export function findWorkspace(key?: string) {
  return ADMIN_WORKSPACES.find((item) => item.key === key)
}
