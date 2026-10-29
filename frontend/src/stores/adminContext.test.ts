// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { getSnapshotDetail, listSnapshots } from '../api/admin/metadata'
import { useAdminContextStore } from './adminContext'

const api = vi.hoisted(() => ({
  listSimpleDatasources: vi.fn(),
  listKnowledgeDocs: vi.fn(),
  listSnapshots: vi.fn(),
  getSnapshotDetail: vi.fn(),
}))
vi.mock('../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))
vi.mock('../api/admin/metadata', () => ({
  listSnapshots: api.listSnapshots,
  getSnapshotDetail: api.getSnapshotDetail,
}))

function snapshotItem(id: number, datasourceId = 1) {
  return {
    id,
    snapshotVersion: id,
    datasourceId,
    datasourceName: 'ds',
    tableCount: 1,
    columnCount: 2,
    status: 'PUBLISHED',
    createdAt: '2026-10-18T00:00:00',
  }
}

function detail(id: number, datasourceId = 1) {
  return {
    snapshot: snapshotItem(id, datasourceId),
    datasourceName: 'ds',
    tables: [],
    columns: [],
  }
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  api.listSimpleDatasources.mockReset()
  api.listKnowledgeDocs.mockReset()
  api.listSnapshots.mockReset()
  api.getSnapshotDetail.mockReset()
})

describe('adminContext.ensureSnapshot', () => {
  it('loads a historical snapshot by id when it is outside the first page', async () => {
    const store = useAdminContextStore()
    store.datasourceId = 1
    api.getSnapshotDetail.mockResolvedValue({ data: detail(99) })

    const item = await store.ensureSnapshot(99)

    expect(item?.id).toBe(99)
    expect(store.snapshots.map((snapshot) => snapshot.id)).toContain(99)
    expect(getSnapshotDetail).toHaveBeenCalledWith(99)
  })

  it('rejects a snapshot owned by another datasource instead of switching scope', async () => {
    const store = useAdminContextStore()
    store.datasourceId = 1
    api.getSnapshotDetail.mockResolvedValue({ data: detail(99, 2) })

    await expect(store.ensureSnapshot(99)).rejects.toThrow('不属于当前数据源')
    expect(store.snapshots).toHaveLength(0)
  })

  it('reuses an already loaded snapshot without an extra request', async () => {
    const store = useAdminContextStore()
    store.datasourceId = 1
    store.snapshots = [snapshotItem(99)]

    const item = await store.ensureSnapshot(99)

    expect(item?.id).toBe(99)
    expect(getSnapshotDetail).not.toHaveBeenCalled()
  })

  it('surfaces a readable error when the snapshot cannot be loaded', async () => {
    const store = useAdminContextStore()
    store.datasourceId = 1
    api.getSnapshotDetail.mockRejectedValue(new Error('无权访问该快照'))

    await expect(store.ensureSnapshot(99)).rejects.toThrow('无权访问该快照')
    expect(listSnapshots).not.toHaveBeenCalled()
  })

  it('discards a historical snapshot response from the previous login session', async () => {
    const store = useAdminContextStore()
    store.datasourceId = 1
    let finish!: (value: unknown) => void
    api.getSnapshotDetail.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const request = store.ensureSnapshot(99)

    store.resetSession()
    finish({ data: detail(99) })

    await expect(request).rejects.toThrow('范围已变化')
    expect(store.snapshots).toEqual([])
  })
})
