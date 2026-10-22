import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../http', () => ({ http }))

import { confirmFieldSemanticBindings, getPendingFieldSemanticBindings } from './fieldSemantic'

describe('字段语义来源批量确认 API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('按明确快照读取待确认来源，不接受客户端数据源回退', async () => {
    const response = { code: 200, data: [{ columnMetaId: 21, expectedVersion: 4, eligible: true }] }
    http.get.mockResolvedValue({ data: response })

    await expect(getPendingFieldSemanticBindings(81)).resolves.toEqual(response)
    expect(http.get).toHaveBeenCalledWith('/api/admin/field-semantics/snapshots/81/bindings/pending-confirmations')
  })

  it('提交每个字段自己的预期版本和依据，保留逐项结果', async () => {
    const items = [
      { columnMetaId: 21, expectedVersion: 4, evidence: '结构与口径逐项一致' },
      { columnMetaId: 22, expectedVersion: 2, evidence: '结构与口径逐项一致' },
    ]
    const response = { code: 200, data: { totalCount: 2, successCount: 1, failedCount: 1 } }
    http.post.mockResolvedValue({ data: response })

    await expect(confirmFieldSemanticBindings(81, items)).resolves.toEqual(response)
    expect(http.post).toHaveBeenCalledWith('/api/admin/field-semantics/snapshots/81/bindings/confirmations', { items })
  })
})
