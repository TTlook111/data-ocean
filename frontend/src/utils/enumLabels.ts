export const snapshotStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  CHECKING: '校验中',
  ISSUE_FOUND: '存在问题',
  APPROVED: '已审核',
  PUBLISHED: '已发布',
  EXPIRED: '已过期',
}

export const snapshotStatusTypes: Record<string, string> = {
  DRAFT: 'info',
  CHECKING: 'warning',
  ISSUE_FOUND: 'danger',
  APPROVED: 'success',
  PUBLISHED: 'success',
  EXPIRED: 'info',
}

export const governanceStatusLabels: Record<string, string> = {
  DISCOVERED: '待治理',
  NORMAL: '正常可用',
  RECOMMENDED: '推荐使用',
  SENSITIVE: '敏感字段',
  DEPRECATED: '已废弃',
  BLOCKED: '禁止使用',
}

export const knowledgeStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  PUBLISHED: '已发布',
  DEPRECATED: '已废弃',
  REJECTED: '已驳回',
}

export const knowledgeStatusTypes: Record<string, string> = {
  DRAFT: 'info',
  PENDING_REVIEW: 'warning',
  APPROVED: 'success',
  PUBLISHED: 'success',
  DEPRECATED: 'danger',
  REJECTED: 'danger',
}

export const generationSourceLabels: Record<string, string> = {
  MANUAL: '人工编辑',
  AI_GENERATED: 'AI 生成',
  ROLLBACK: '版本回滚',
}

export const generationSourceTypes: Record<string, string> = {
  MANUAL: 'info',
  AI_GENERATED: 'success',
  ROLLBACK: 'warning',
}

export const syncStatusLabels: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
  CANCELLED: '已取消',
  CANCELED: '已取消',
}

export const syncStatusTypes: Record<string, string> = {
  PENDING: 'info',
  RUNNING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  TIMEOUT: 'danger',
  CANCELLED: 'info',
  CANCELED: 'info',
}

export const syncTriggerLabels: Record<string, string> = {
  MANUAL: '手动触发',
  SCHEDULED: '定时触发',
  SYSTEM: '系统触发',
}

export const qualityDimensionLabels: Record<string, string> = {
  COMPLETENESS: '完整性',
  ACCURACY: '准确性',
  CONSISTENCY: '一致性',
  TIMELINESS: '时效性',
  TRACEABILITY: '可追溯性',
  VALIDITY: '有效性',
  UNIQUENESS: '唯一性',
}

export const severityLabels: Record<string, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

export const issueStatusLabels: Record<string, string> = {
  OPEN: '待处理',
  CONFIRMED: '已确认',
  RESOLVED: '已解决',
  REJECTED: '已驳回',
  AUTO_CLOSED: '自动关闭',
}

export const issueStatusTypes: Record<string, string> = {
  OPEN: 'warning',
  CONFIRMED: 'primary',
  RESOLVED: 'success',
  REJECTED: 'info',
  AUTO_CLOSED: 'info',
}

export const auditActionLabels: Record<string, string> = {
  PUBLISH: '发布',
  EXPIRE: '过期',
  REVOKE: '撤回',
  STATUS_TRANSITION: '状态变更',
}

export const roleCodeLabels: Record<string, string> = {
  USER: '普通员工',
  ANALYST: '数据分析师',
  DATA_ANALYST: '数据分析师',
  DATA_MANAGER: '数据管理员',
  SECURITY_MANAGER: '安全管理员',
  ADMIN: '超级管理员',
  SUPER_ADMIN: '超级管理员',
}

export function enumLabel(labels: Record<string, string>, value?: string | null, fallback = '未设置') {
  if (!value) return fallback
  return labels[value] || value
}

export function enumType(types: Record<string, string>, value?: string | null, fallback = 'info') {
  if (!value) return fallback
  return types[value] || fallback
}

export const snapshotStatusLabel = (status?: string | null) => enumLabel(snapshotStatusLabels, status)
export const snapshotStatusType = (status?: string | null) => enumType(snapshotStatusTypes, status)
export const governanceStatusLabel = (status?: string | null) => enumLabel(governanceStatusLabels, status)
export const knowledgeStatusLabel = (status?: string | null) => enumLabel(knowledgeStatusLabels, status)
export const knowledgeStatusType = (status?: string | null) => enumType(knowledgeStatusTypes, status)
export const generationSourceLabel = (source?: string | null) => enumLabel(generationSourceLabels, source)
export const generationSourceType = (source?: string | null) => enumType(generationSourceTypes, source)
export const syncStatusLabel = (status?: string | null) => enumLabel(syncStatusLabels, status)
export const syncStatusType = (status?: string | null) => enumType(syncStatusTypes, status)
export const syncTriggerLabel = (trigger?: string | null) => enumLabel(syncTriggerLabels, trigger)
export const qualityDimensionLabel = (dimension?: string | null) => enumLabel(qualityDimensionLabels, dimension)
export const severityLabel = (severity?: string | null) => enumLabel(severityLabels, severity)
export const issueStatusLabel = (status?: string | null) => enumLabel(issueStatusLabels, status)
export const issueStatusType = (status?: string | null) => enumType(issueStatusTypes, status)
export const auditActionLabel = (action?: string | null) => enumLabel(auditActionLabels, action)
export const roleCodeLabel = (role?: string | null) => enumLabel(roleCodeLabels, role)
export const roleCodesLabel = (roles?: string[] | null, fallback = '普通用户') =>
  roles?.length ? roles.map((role) => roleCodeLabel(role)).join(' / ') : fallback
