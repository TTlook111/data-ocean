import type { ConversationMessageItem, QueryTaskResult } from '../api/query'

function asSuggestedQuestions(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim())
    .filter(Boolean)
}

/**
 * 恢复会话消息中的查询结果元数据。
 * 正式协议使用 camelCase；兼容已经落库的 snake_case 结果，避免刷新历史会话时丢失推荐追问。
 */
export function parseStoredQueryResult(message: Pick<ConversationMessageItem, 'metadata' | 'taskId'>): QueryTaskResult | undefined {
  if (!message.metadata) return undefined
  try {
    const parsed: unknown = JSON.parse(message.metadata)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return undefined
    const raw = parsed as Record<string, unknown>
    const result = { ...raw } as unknown as QueryTaskResult
    const suggestions = raw.suggestedQuestions ?? raw.suggested_questions
    result.suggestedQuestions = asSuggestedQuestions(suggestions)
    if (message.taskId && !result.taskId) result.taskId = message.taskId
    return result
  } catch {
    return undefined
  }
}
