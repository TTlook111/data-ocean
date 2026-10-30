import type { MetadataEntityItem } from '../api/admin/catalog'
/** Explicit malformed identifiers never fall through to a different saved scope. */
export function positiveId(raw: unknown): number | undefined {
  if (typeof raw !== 'string' && typeof raw !== 'number') return undefined
  if (!/^\d+$/.test(String(raw))) return undefined
  const id = Number(raw)
  return Number.isSafeInteger(id) && id > 0 ? id : undefined
}
export function entitySource(entity: MetadataEntityItem | null | undefined): number | undefined {
  try {
    return positiveId(JSON.parse(entity?.entityMetadata || '{}').datasource_id)
  } catch {
    return undefined
  }
}
export function queryEvidenceTarget(
  entity: MetadataEntityItem,
): { source: number; table: string; column?: string } | undefined {
  const source = entitySource(entity)
  if (!source) return undefined
  let meta: Record<string, unknown> = {}
  try {
    meta = JSON.parse(entity.entityMetadata || '{}')
  } catch {
    /* missing exact table context */
  }
  if (entity.entityType === 'TABLE') return { source, table: entity.name }
  if (entity.entityType !== 'COLUMN' || typeof meta.table_name !== 'string' || !meta.table_name)
    return undefined
  return { source, table: meta.table_name, column: entity.name }
}
/** Java represents a projected output by aliasName; a field use without alias is not an output. */
export function queryEvidenceKind(row: {
  sourceColumn?: string
  aliasName?: string
  relationType?: string
}): string {
  return row.sourceColumn !== undefined
    ? row.aliasName
      ? '结果字段派生'
      : '字段查询使用'
    : row.relationType === 'USAGE'
      ? '表级查询使用'
      : row.relationType || '类型未提供'
}
