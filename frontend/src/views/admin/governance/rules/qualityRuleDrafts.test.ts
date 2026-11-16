// @vitest-environment happy-dom
import { beforeEach, expect, it } from 'vitest'
import { clearQualityRuleDrafts, useQualityRuleDrafts } from './qualityRuleDrafts'

beforeEach(clearQualityRuleDrafts)

it('关闭后恢复原人工输入，提交规范化不反向改写草稿', () => {
  const draft = useQualityRuleDrafts(
    () => 'session-a',
    () => true,
  )
  draft.form.datasourceId = 5
  draft.activateDraft(undefined, 'DATASOURCE', 5)
  draft.form.ruleName = '  原始输入  '
  draft.form.description = '处理建议\n\n'
  draft.rememberDraft()
  const reopened = useQualityRuleDrafts(
    () => 'session-a',
    () => true,
  )
  reopened.form.datasourceId = 5
  reopened.activateDraft(undefined, 'DATASOURCE', 5)
  expect(reopened.form.ruleName).toBe('  原始输入  ')
  expect(reopened.form.description).toBe('处理建议\n\n')
  expect(reopened.payload().ruleName).toBe('原始输入')
  expect(reopened.form.ruleName).toBe('  原始输入  ')
})

it('源级新建改全局后恢复不会保留旧datasourceId', () => {
  const draft = useQualityRuleDrafts(
    () => 'session-a',
    () => true,
  )
  draft.form.datasourceId = 5
  draft.activateDraft(undefined, 'DATASOURCE', 5)
  draft.form.scopeType = 'GLOBAL'
  draft.form.datasourceId = undefined
  draft.form.ruleName = '全局草稿'
  draft.rememberDraft()
  const reopened = useQualityRuleDrafts(
    () => 'session-a',
    () => true,
  )
  reopened.form.datasourceId = 5
  reopened.activateDraft(undefined, 'DATASOURCE', 5)
  expect(reopened.form.scopeType).toBe('GLOBAL')
  expect(reopened.form.datasourceId).toBeUndefined()
  expect(reopened.draftKey()).toBe('create:5')
})

it('确认撤权只删除无权来源草稿，其他来源保留', () => {
  let sources = [5, 6]
  const allowed = (value: { datasourceId?: number }) => sources.includes(value.datasourceId || 0)
  for (const source of sources) {
    const draft = useQualityRuleDrafts(() => 'session-a', allowed)
    draft.form.datasourceId = source
    draft.activateDraft(undefined, 'DATASOURCE', source)
    draft.form.ruleName = `源${source}草稿`
    draft.rememberDraft()
  }
  sources = [6]
  const reopened = useQualityRuleDrafts(() => 'session-a', allowed)
  reopened.pruneDrafts()
  sources = [5, 6]
  reopened.form.datasourceId = 5
  reopened.activateDraft(undefined, 'DATASOURCE', 5)
  expect(reopened.form.ruleName).toBe('')
  reopened.form.datasourceId = 6
  reopened.activateDraft(undefined, 'DATASOURCE', 6)
  expect(reopened.form.ruleName).toBe('源6草稿')
})
