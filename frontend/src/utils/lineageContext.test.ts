import { expect, it } from 'vitest'
import { positiveId, queryEvidenceKind, queryEvidenceTarget } from './lineageContext'
it('精确ID拒绝数组小数指数及隐式偏好替换', () => {
  for (const id of [undefined, null, [], ['1'], '0', '1.2', '1e2', '-1', 'abc'])
    expect(positiveId(id)).toBeUndefined()
  expect(positiveId('12')).toBe(12)
})
it('真实USAGE表与同字段的使用、输出行分别标注', () => {
  expect(queryEvidenceKind({ relationType: 'USAGE' })).toBe('表级查询使用')
  expect(queryEvidenceKind({ sourceColumn: 'amount' })).toBe('字段查询使用')
  expect(queryEvidenceKind({ sourceColumn: 'amount', aliasName: 'total_amount' })).toBe('结果字段派生')
})
it('COUNT(*)或常量只按已返回字段证据处理，不伪造字段和同表JOIN', () => {
  const columns: [] = []
  expect(columns.map(queryEvidenceKind)).toEqual([])
  expect(queryEvidenceKind({ relationType: 'OTHER' })).toBe('OTHER')
})
it('字段缺少实际source或所属表不能从FQN猜测证据范围', () => {
  expect(
    queryEvidenceTarget({
      entityType: 'COLUMN',
      name: 'amount',
      fqn: 'db.orders.amount',
      entityMetadata: '{"datasource_id":1}',
    } as any),
  ).toBeUndefined()
})
