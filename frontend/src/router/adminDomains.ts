import {
  BarChart3,
  BookOpen,
  Database,
  FolderKanban,
  LayoutDashboard,
  ShieldCheck,
  SlidersHorizontal,
} from 'lucide-vue-next'
import { ADMIN_DOMAIN_KEYS, type AdminDomain } from './adminNavigation'

/**
 * 后台一级域定义。
 *
 * 顺序即左侧导航顺序；一级入口目标由 ADMIN_WORKSPACES 推导，不在这里写死路径。
 */
export const ADMIN_DOMAINS: AdminDomain[] = [
  { key: ADMIN_DOMAIN_KEYS.workbench, label: '工作台', path: '/admin/workbench', icon: LayoutDashboard },
  { key: ADMIN_DOMAIN_KEYS.dataEntry, label: '数据接入', path: '/admin/data-sources', icon: Database },
  { key: ADMIN_DOMAIN_KEYS.dataAssets, label: '数据资产', path: '/admin/assets', icon: FolderKanban },
  { key: ADMIN_DOMAIN_KEYS.governance, label: '数据治理', path: '/admin/governance', icon: SlidersHorizontal },
  { key: ADMIN_DOMAIN_KEYS.semantics, label: '语义中心', path: '/admin/semantics/glossaries', icon: BookOpen },
  { key: ADMIN_DOMAIN_KEYS.access, label: '权限与组织', path: '/admin/access/iam', icon: ShieldCheck },
  { key: ADMIN_DOMAIN_KEYS.operations, label: '运营与平台', path: '/admin/operations/queries', icon: BarChart3 },
]
