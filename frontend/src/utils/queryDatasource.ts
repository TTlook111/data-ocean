/** 解析问数页 URL 中的数据源 ID，只接受正整数的单值参数。 */
export function parseDatasourceId(value: unknown): number | undefined {
  if (Array.isArray(value) || value == null) return undefined
  const raw = String(value).trim()
  if (!/^[1-9]\d*$/.test(raw)) return undefined
  const id = Number(raw)
  return Number.isSafeInteger(id) ? id : undefined
}
