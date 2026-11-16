import { http } from '../http'
import type { ApiResult } from '../types'
import type { QualityRulePayload } from './governance'

export type QualityRuleAction = 'CREATE' | 'UPDATE' | 'COPY' | 'ENABLE' | 'DISABLE' | 'DELETE' | 'RESTORE'
export interface QualityRuleOperationRequest {
  operationId: string
  action: QualityRuleAction
  ruleId?: number
  expectedVersion?: number
  configuration?: QualityRulePayload
}
export interface QualityRuleOperationReceipt {
  operationId: string
  action?: QualityRuleAction
  outcome: 'APPLIED' | 'REJECTED' | 'CONFLICT' | 'NOT_FOUND'
  ruleId?: number
  configVersion?: number
  errorCode?: number
  errorMessage?: string
}

// 规则写入只走持久操作接口；配置正文、原版本和操作 ID 在请求创建时一起冻结。
export async function submitQualityRuleOperation(request: QualityRuleOperationRequest) {
  const { data } = await http.post<ApiResult<QualityRuleOperationReceipt>>(
    '/api/admin/quality-rules/operations',
    request,
  )
  return data
}
export async function getQualityRuleOperation(operationId: string) {
  const { data } = await http.get<ApiResult<QualityRuleOperationReceipt>>(
    `/api/admin/quality-rules/operations/${operationId}`,
  )
  return data
}
// NOT_FOUND 不足以证明原请求未在途；服务端结束围栏与原操作使用同一个原子提交边界。
export async function resolveQualityRuleOperation(operationId: string) {
  const { data } = await http.post<ApiResult<QualityRuleOperationReceipt>>(
    `/api/admin/quality-rules/operations/${operationId}/resolve`,
  )
  return data
}
