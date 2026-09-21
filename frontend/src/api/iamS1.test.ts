/**
 * IAM-SIMPLE-1 查询 API 测试
 *
 * 重点验证：
 * - S1 提交只调用 /api/iam-s1/query/ask，并携带协议版本与资源声明（不与旧 /api/query 混用）；
 * - SSE 事件流解析（fetch + ReadableStream，因为 EventSource 无法携带 Authorization 头）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { IAM_S1_DEFAULT_QUERY_USAGES, iamS1Ask, iamS1StreamTask } from './iamS1'
import { http } from './http'

vi.mock('./http', () => ({
  http: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
  },
}))

const mockedPost = vi.mocked(http.post)

afterEach(() => {
  vi.restoreAllMocks()
  mockedPost.mockReset()
})

describe('iamS1Ask', () => {
  it('只调用 IAM-SIMPLE-1 新链路并携带资源声明', async () => {
    mockedPost.mockResolvedValue({
      data: { code: 200, message: 'success', data: { taskId: 't-1', protocolVersion: 'IAM-SIMPLE-1' } },
    })

    const result = await iamS1Ask({
      datasourceId: 5,
      question: '统计最近 30 天订单金额',
      tables: [{ tableName: 'orders', referencedColumns: ['order_id', 'amount'] }],
    })

    expect(mockedPost).toHaveBeenCalledTimes(1)
    const [url, body] = mockedPost.mock.calls[0]
    expect(url).toBe('/api/iam-s1/query/ask')
    expect(url).not.toContain('/api/query/ask')
    expect(body).toMatchObject({
      protocolVersion: 'IAM-SIMPLE-1',
      datasourceId: 5,
      question: '统计最近 30 天订单金额',
      tables: [{ tableName: 'orders', referencedColumns: ['order_id', 'amount'] }],
    })
    expect(result.data.taskId).toBe('t-1')
  })

  it('为每个字段补齐默认 usage，缺失会被后端拒绝', async () => {
    mockedPost.mockResolvedValue({
      data: { code: 200, message: 'success', data: { taskId: 't-2', protocolVersion: 'IAM-SIMPLE-1' } },
    })

    await iamS1Ask({
      datasourceId: 5,
      question: '按地区汇总销售额',
      tables: [{ tableName: 'orders', referencedColumns: ['region', 'amount'] }],
    })

    const body = mockedPost.mock.calls[0][1] as {
      tables: Array<{ columnUsages: Record<string, string[]> }>
    }
    // 与后端 IamS1UsageDefaults.DEFAULT_QUERY_USAGES 对齐；两侧默认集合必须同步修改。
    expect(IAM_S1_DEFAULT_QUERY_USAGES).toEqual(['PROJECTION', 'FILTER', 'JOIN'])
    expect(body.tables[0].columnUsages.region).toEqual([...IAM_S1_DEFAULT_QUERY_USAGES])
    expect(body.tables[0].columnUsages.amount).toEqual([...IAM_S1_DEFAULT_QUERY_USAGES])
  })

  it('调用方显式声明的 usage 不会被默认值覆盖', async () => {
    mockedPost.mockResolvedValue({
      data: { code: 200, message: 'success', data: { taskId: 't-3', protocolVersion: 'IAM-SIMPLE-1' } },
    })

    await iamS1Ask({
      datasourceId: 5,
      question: '按地区汇总销售额',
      tables: [{
        tableName: 'orders',
        referencedColumns: ['region', 'amount'],
        columnUsages: { region: ['PROJECTION', 'GROUP'], amount: ['PROJECTION', 'ORDER'] },
      }],
    })

    const body = mockedPost.mock.calls[0][1] as {
      tables: Array<{ columnUsages: Record<string, string[]> }>
    }
    expect(body.tables[0].columnUsages.region).toEqual(['PROJECTION', 'GROUP'])
    expect(body.tables[0].columnUsages.amount).toEqual(['PROJECTION', 'ORDER'])
  })
})

describe('iamS1StreamTask', () => {
  beforeEach(() => {
    // 运行环境未注入浏览器存储时，为 SSE 认证头提供最小替身
    vi.stubGlobal('localStorage', {
      getItem: () => 'test-token',
      setItem: () => undefined,
      removeItem: () => undefined,
    })
  })

  it('解析 SSE 事件并在流结束时返回', async () => {
    const payload = 'event: progress\ndata: {"progressMessage":"正在生成 SQL"}\n\n' +
      'event: result\ndata: {"status":"COMPLETED"}\n\n'
    const encoder = new TextEncoder()
    let served = false
    const body = {
      getReader() {
        return {
          async read() {
            if (served) return { value: undefined, done: true }
            served = true
            return { value: encoder.encode(payload), done: false }
          },
        }
      },
    }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, body })
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<{ type: string; data: string }> = []
    await iamS1StreamTask('t-1', { onEvent: (event) => events.push(event) })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/iam-s1/query/tasks/t-1/stream')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      headers: { Authorization: 'Bearer test-token', Accept: 'text/event-stream' },
    })
    expect(events).toEqual([
      { type: 'progress', data: '{"progressMessage":"正在生成 SQL"}' },
      { type: 'result', data: '{"status":"COMPLETED"}' },
    ])
  })

  it('事件流不可用时抛出错误，由上层 S1 轮询兜底', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, body: null }))

    await expect(iamS1StreamTask('t-2', { onEvent: () => undefined })).rejects.toThrow('503')
  })
})
