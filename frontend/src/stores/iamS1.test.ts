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

  it('只有问数功能的用户不算拥有后台能力', async () => {
    // 回归：「普通问数用户」模板的功能码就是 query:use。
    // 若把 globalFunctions.length > 0 当作后台能力，这类用户会被路由守卫放行进 /admin/**。
    mockedGetCapabilities.mockResolvedValue({
      code: 200,
      message: 'success',
      data: snapshot({ globalFunctions: ['query:use'], datasourceCapabilities: [] }),
    })
    const store = useIamS1Store()
    await store.load()

    expect(store.queryUse).toBe(true)
    expect(store.hasAnyAdminCapability).toBe(false)
  })

  it('数据源绑定上只有问数功能时同样不算后台能力', async () => {
    mockedGetCapabilities.mockResolvedValue({
      code: 200,
      message: 'success',
      data: snapshot({
        globalFunctions: ['query:use'],
        datasourceCapabilities: [
          {
            datasourceId: 5,
            datasourceName: '销售库',
            functionCodes: ['query:use', 'query:export'],
            functionNames: ['使用问数', '导出结果'],
          },
        ],
      }),
    })
    const store = useIamS1Store()
    await store.load()

    expect(store.hasAnyAdminCapability).toBe(false)
  })

  it('存在后台功能时才算拥有后台能力', async () => {
    mockedGetCapabilities.mockResolvedValue({ code: 200, message: 'success', data: snapshot() })
    const store = useIamS1Store()
    await store.load()

    expect(store.hasAnyAdminCapability).toBe(true)
  })

  it('数据源下拉只保留“功能与负责源同一绑定”的源', async () => {
    // 服务端按负责源下发数据源，不按功能过滤；页面若直接使用会把
    // “A 角色给的功能”和“B 角色给的负责源”交叉相乘，选中后必然被后端拒绝。
    mockedGetCapabilities.mockResolvedValue({
      code: 200,
      message: 'success',
      data: snapshot({
        datasourceCapabilities: [
          {
            datasourceId: 5,
            datasourceName: '销售库',
            functionCodes: ['security:permission:view'],
            functionNames: ['查看授权配置'],
          },
          {
            datasourceId: 9,
            datasourceName: '财务库',
            functionCodes: ['security:mask:view'],
            functionNames: ['查看字段保护'],
          },
        ],
      }),
    })
    const store = useIamS1Store()
    await store.load()

    expect(store.datasourcesWithFunction('security:permission:view')).toEqual([5])
    expect(store.datasourcesWithFunction('security:mask:view')).toEqual([9])
    expect(store.datasourcesWithFunction('security:approval:review')).toEqual([])
  })

  it('系统管理员在任意功能上拥有全部负责源', async () => {
    mockedGetCapabilities.mockResolvedValue({
      code: 200,
      message: 'success',
      data: snapshot({
        systemAdmin: true,
        globalFunctions: [],
        datasourceCapabilities: [
          { datasourceId: 5, datasourceName: '销售库', functionCodes: [], functionNames: [] },
        ],
      }),
    })
    const store = useIamS1Store()
    await store.load()

    expect(store.datasourcesWithFunction('security:approval:review')).toEqual([5])
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
