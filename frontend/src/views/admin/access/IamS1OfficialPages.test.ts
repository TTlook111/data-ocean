import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ADMIN_WORKSPACES } from '../../../router/adminNavigation'

const FORBIDDEN = [
  'listRoles',
  'listPermissions',
  'updateRolePermissions',
  'assignRoleToUser',
]

const OFFICIAL_PAGES = [
  'src/views/admin/access/IamS1OrganizationView.vue',
  'src/views/admin/access/IamS1AccessWorkspaceView.vue',
  'src/views/admin/access/IamS1ApprovalView.vue',
  'src/views/admin/user/UserList.vue',
  'src/views/admin/user/DepartmentTree.vue',
]

function readFrontend(relativePath: string) {
  return readFileSync(resolve(process.cwd(), relativePath), 'utf8')
}

describe('正式 S1 组织入口不得引用旧角色权限 API', () => {
  it('正式导航使用组织、角色与负责源，旧页明确标为切换前入口', () => {
    const official = ADMIN_WORKSPACES.find((item) => item.key === 'iam-organization')
    const legacy = ADMIN_WORKSPACES.find((item) => item.key === 'organization')
    expect(official?.label).toBe('组织、角色与负责源')
    expect(official?.path).toBe('/admin/access/iam-organization')
    expect(legacy?.label).toBe('组织与角色（旧）')
    expect(legacy?.path).toBe('/admin/access/organization')
  })

  it('正式 S1 页面不引用 listRoles/listPermissions/updateRolePermissions/assignRoleToUser', () => {
    for (const page of OFFICIAL_PAGES) {
      const source = readFrontend(page)
      for (const symbol of FORBIDDEN) {
        expect(source, `${page} 不得引用 ${symbol}`).not.toContain(symbol)
      }
    }
  })

  it('正式组织页接入用户和部门，旧组织页不再混入这两块', () => {
    const official = readFrontend('src/views/admin/access/IamS1OrganizationView.vue')
    const legacy = readFrontend('src/views/admin/user/OrganizationView.vue')
    expect(official).toContain("from '../user/UserList.vue'")
    expect(official).toContain("from '../user/DepartmentTree.vue'")
    expect(official).toContain("from '../../../api/iamS1'")
    expect(legacy).not.toContain('UserList')
    expect(legacy).not.toContain('DepartmentTree')
    expect(legacy).toContain('/admin/access/iam-organization')
    expect(legacy).toContain('组织与角色（旧）')
  })
})
