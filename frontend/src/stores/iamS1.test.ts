/**
 * IAM-SIMPLE-1 能力摘要 Store 测试
 *
 * 重点验证：
 * - 能力结论只来自 Java 返回的 S1 能力摘要，不回退旧 permissions / roles；
 * - “功能 + 负责源同一绑定”的判定与后端一致（不能把角色的功能与另一角色的源相乘）；
 * - 读取失败时保持“无能力”，不静默放行。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useIamS1Store } from './iamS1'
import * as api from '../api/iamS1'

vi.mock('../api/iamS1', () => ({
  getIamS1Capabilities: vi.fn(),
}))

const mockedGetCapabilities = vi.mocked(api.getIamS1Capabilities)

function snapshot(overrides: Partial<api.IamS1CapabilitySnapshot> = {}): api.IamS1CapabilitySnapshot {
  return {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 2,
    systemAdmin: false,
    globalFunctions: ['query:use', 'organization:role:view'],
    datasourceCapabilities: [
      {
        datasourceId: 5,
        datasourceName: '销售库',
        functionCodes: ['security:permission:view', 'security:permission:manage'],
        functionNames: ['查看授权配置', '维护授权配置'],
      },
    ],
    queryUse: true,
    viewSql: false,
    export: false,
    permissionRevision: 12,
    ...overrides,
  }
}

describe('useIamS1Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockedGetCapabilities.mockReset()
  })

  it('按 Java 结论返回全局功能与问数能力', async () => {
    mockedGetCapabilities.mockResolvedValue({ code: 200, message: 'success', data: snapshot() })
    const store = useIamS1Store()

    await store.load()

    expect(store.hasGlobal('organization:role:view')).toBe(true)
    expect(store.hasGlobal('security:permission:manage')).toBe(false)
    expect(store.queryUse).toBe(true)
    expect(store.viewSql).toBe(false)
    expect(store.exportResult).toBe(false)
  })

  it('功能与负责源必须落在同一条角色绑定上', async () => {
    mockedGetCapabilities.mockResolvedValue({ code: 200, message: 'success', data: snapshot() })
    const store = useIamS1Store()
    await store.load()

    expect(store.canOnDatasource('security:permission:manage', 5)).toBe(true)
    // 未负责的数据源上，即使全局功能里存在同名能力也必须为 false（不能交叉相乘）
    expect(store.canOnDatasource('security:permission:manage', 9)).toBe(false)
    expect(store.canOnDatasource('security:permission:view')).toBe(false)
  })

  it('系统管理员按受保护规则拥有全部能力', async () => {
    mockedGetCapabilities.mockResolvedValue({
      code: 200,
      message: 'success',
      data: snapshot({ systemAdmin: true, globalFunctions: [], datasourceCapabilities: [] }),
    })
    const store = useIamS1Store()
    await store.load()

    expect(store.hasGlobal('audit:export')).toBe(true)
    expect(store.canOnDatasource('security:mask:manage', 42)).toBe(true)
    expect(store.functionNamesOn(42)).toEqual(['系统管理员：全部后台功能'])
  })

  it('读取失败时保持无能力并记录中文错误，不静默放行', async () => {
    mockedGetCapabilities.mockRejectedValue(new Error('请求失败：服务不可用'))
    const store = useIamS1Store()

    await store.load()

    expect(store.snapshot).toBeNull()
    expect(store.hasAnyAdminCapability).toBe(false)
    expect(store.hasGlobal('query:use')).toBe(false)
    expect(store.errorMessage).toContain('服务不可用')
  })

  it('reset 清除上一位用户的界面能力', async () => {
    mockedGetCapabilities.mockResolvedValue({ code: 200, message: 'success', data: snapshot() })
    const store = useIamS1Store()
    await store.load()
    expect(store.queryUse).toBe(true)

    store.reset()

    expect(store.snapshot).toBeNull()
    expect(store.queryUse).toBe(false)
    expect(store.loaded).toBe(false)
  })
})
