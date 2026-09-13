import { describe, expect, it } from 'vitest'
import { parseDatasourceId } from './queryDatasource'

describe('parseDatasourceId', () => {
  it('只接受 URL 中的正整数单值', () => {
    expect(parseDatasourceId('12')).toBe(12)
    expect(parseDatasourceId(12)).toBe(12)
    expect(parseDatasourceId('0')).toBeUndefined()
    expect(parseDatasourceId('-1')).toBeUndefined()
    expect(parseDatasourceId('1.2')).toBeUndefined()
    expect(parseDatasourceId(['12', '13'])).toBeUndefined()
    expect(parseDatasourceId('')).toBeUndefined()
  })
})
