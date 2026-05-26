import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface SyncTaskItem {
  id: number
  datasourceName: string
  triggerType: string
  status: string
  progressTotal?: number
  progressCurrent?: number
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
}

export interface SnapshotItem {
  id: number
  snapshotVersion: number
  datasourceId: number
  datasourceName: string
  tableCount: number
  columnCount: number
  qualityScore?: number
  status: string
  createdAt: string
}

export interface SnapshotDetail {
  snapshot: SnapshotItem
  tables: TableMetaItem[]
  columns: ColumnMetaItem[]
}

export interface TableMetaItem {
  id: number
  tableName: string
  tableComment?: string
  tableType: string
  engine?: string
  rowCountEstimate?: number
  governanceStatus: string
}

export interface ColumnMetaItem {
  id: number
  tableMetaId: number
  tableName: string
  columnName: string
  columnComment?: string
  dataType: string
  isNullable: number
  isPrimaryKey: number
  ordinalPosition: number
  nullRate?: number
  governanceStatus: string
}

export interface SchemaDiffResult {
  addedTables: string[]
  removedTables: string[]
  addedColumns: ColumnChange[]
  removedColumns: ColumnChange[]
  modifiedColumns: ColumnChange[]
}

export interface ColumnChange {
  tableName: string
  columnName: string
  oldType?: string
  newType?: string
  oldComment?: string
  newComment?: string
}

export interface SyncTriggerPayload {
  datasourceId: number
  includeStatistics?: boolean
}

export async function triggerSync(payload: SyncTriggerPayload) {
  const { data } = await http.post<ApiResult<null>>('/api/admin/metadata/sync', payload)
  return data
}

export async function listSyncTasks(params: { datasourceId?: number; page?: number; size?: number }) {
  const { data } = await http.get<ApiResult<PageResult<SyncTaskItem>>>('/api/admin/metadata/sync-tasks', { params })
  return data
}

export async function listSnapshots(params: { datasourceId?: number; page?: number; size?: number }) {
  const { data } = await http.get<ApiResult<PageResult<SnapshotItem>>>('/api/admin/metadata/snapshots', { params })
  return data
}

export async function getSnapshotDetail(id: number) {
  const { data } = await http.get<ApiResult<SnapshotDetail>>(`/api/admin/metadata/snapshots/${id}`)
  return data
}

export async function diffSnapshots(oldId: number, newId: number) {
  const { data } = await http.get<ApiResult<SchemaDiffResult>>('/api/admin/metadata/snapshots/diff', {
    params: { oldId, newId }
  })
  return data
}
