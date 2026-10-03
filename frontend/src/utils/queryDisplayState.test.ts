import { describe, expect, it } from 'vitest'
import { finalProtectionLabel, resolveQueryDisplayState } from './queryDisplayState'

const protectedRows = [{ region: 'North', amount: 50 }]

describe('resolveQueryDisplayState', () => {
  it.each([
    ['FINAL_PROTECTED', '已按当前权限校验'],
    ['FINAL_MASKED', '已按当前权限校验并脱敏'],
  ])('labels final protection status %s only from the server value', (status, label) => {
    expect(finalProtectionLabel(status)).toBe(label)
    expect(finalProtectionLabel('NO_RESULT')).toBeUndefined()
  })

  it('keeps clarification separate from successful empty results even with stale rows', () => {
    const state = resolveQueryDisplayState({
      result: { taskId: 'clarify-1', status: 'CLARIFICATION_REQUIRED', data: protectedRows },
    })

    expect(state.kind).toBe('clarification')
    expect(state.title).toBe('需要补充查询条件')
  })

  it.each(['FAILED', 'CANCELLED', 'TIMEOUT'])('does not expose residual rows as success for %s', (status) => {
    const state = resolveQueryDisplayState({
      result: { taskId: `task-${status}`, status, data: protectedRows },
    })

    expect(state.kind).toBe(status.toLowerCase())
    expect(state.kind).not.toBe('success-data')
    expect(state.kind).not.toBe('success-empty')
  })

  it('keeps processing distinct from successful no-data', () => {
    expect(resolveQueryDisplayState({ result: { taskId: 'processing-1', status: 'PROCESSING' } }).kind).toBe('processing')
    expect(resolveQueryDisplayState({ result: { taskId: 'empty-1', status: 'COMPLETED', data: [] } }).kind).toBe('success-empty')
  })

  it('only offers recovery waiting when local polling ended but the server still reports PROCESSING', () => {
    const localWait = resolveQueryDisplayState({
      result: { taskId: 'still-running', status: 'PROCESSING' },
      localWaitTimedOut: true,
    })
    const serverTimeout = resolveQueryDisplayState({ result: { taskId: 'ended', status: 'TIMEOUT' } })

    expect(localWait).toMatchObject({ kind: 'processing', canResume: true })
    expect(serverTimeout.kind).toBe('timeout')
    expect(serverTimeout).not.toHaveProperty('canResume')
  })

  it('does not leave an older completed result visible while a new query is submitting', () => {
    const state = resolveQueryDisplayState({
      result: { taskId: 'older-completed', status: 'COMPLETED', data: protectedRows },
      isSubmitting: true,
    })

    expect(state.kind).toBe('processing')
  })

  it('shows selection errors before any previous result can be used', () => {
    const state = resolveQueryDisplayState({
      result: null,
      selectionError: '任务读取失败',
    })

    expect(state).toMatchObject({ kind: 'selection-error', description: '任务读取失败' })
  })
})
