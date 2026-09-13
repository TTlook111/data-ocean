import { describe, expect, it } from 'vitest'
import { parseStoredQueryResult } from './queryResult'

describe('parseStoredQueryResult', () => {
  it('恢复正式协议中的推荐追问和任务 ID', () => {
    const result = parseStoredQueryResult({
      taskId: 'task-1',
      metadata: JSON.stringify({ status: 'COMPLETED', suggestedQuestions: ['查看趋势', '  按地区拆分  '] }),
    })

    expect(result?.taskId).toBe('task-1')
    expect(result?.suggestedQuestions).toEqual(['查看趋势', '按地区拆分'])
  })

  it('兼容已落库的 snake_case 推荐追问', () => {
    const result = parseStoredQueryResult({
      taskId: undefined,
      metadata: JSON.stringify({ status: 'COMPLETED', suggested_questions: ['继续分析'] }),
    })

    expect(result?.suggestedQuestions).toEqual(['继续分析'])
  })

  it('忽略损坏或非对象元数据', () => {
    expect(parseStoredQueryResult({ taskId: 'task-1', metadata: '{bad' })).toBeUndefined()
    expect(parseStoredQueryResult({ taskId: 'task-1', metadata: '[]' })).toBeUndefined()
  })
})
