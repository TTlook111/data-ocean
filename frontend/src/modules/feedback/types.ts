/** 队列的基础事项与当前责任。 */
export type Issue = {
  id: string;
  sourceId: string;
  sourceName: string;
  origin: string;
  status: string;
  assigneeId: string | null;
  assigneeName: string | null;
  updatedAt: string;
};

/** 业务自由文本可能受限，操作人与责任关系仍可核对。 */
export type Activity = {
  version: number;
  action: 'ASSIGNED' | 'REVIEWED' | 'RESULT_SUBMITTED' | 'VERIFIED' | 'RECHECK_FAILED' | 'CLOSED';
  actor: string;
  assignee: string | null;
  direction: string | null;
  note: string | null;
  rootCause: string | null;
  conclusion?: string | null;
  occurredAt: string;
};

/** 详情由服务端决定可见证据和本次可执行操作。 */
export type IssueDetail = {
  issue: Issue;
  version: number;
  direction: string;
  canAssign: boolean;
  canReview: boolean;
  evidenceRestricted: boolean;
  question: {
    answerId: string;
    question: string | null;
    supplement: string | null;
    executedAt: string | null;
    evidence: { title: string; revision: number; fragment: number } | null;
    scope: string | null;
    sql: string | null;
    incomplete: boolean;
  } | null;
  reports: { id: string; reason: string | null; occurredAt: string }[];
  /** 未交付的原问仍有独立诊断证据。 */
  incidents?: QueryIncident[];
  latestReview: Activity | null;
  activities: Activity[];
  resolution?: Resolution;
};

/** 问数失败的安全定位信息，后台无需原文权限也能分派。 */
export type QueryIncident = {
  requestId: string;
  state: string;
  phase: string;
  diagnostic: string;
  executionState: string;
  failedAt: string;
};

/** 评价与问数自动来源按实际成因独立呈现。 */
export const originLabels: Record<string, string> = {
  USER_DISLIKE: '问数点踩',
  QUERY_KNOWLEDGE_GAP: '问数知识缺口',
  QUERY_TECHNICAL_FAILURE: '技术失败',
};

/** 从处理结果到回告，各状态由服务端独立核验。 */
export type Resolution = {
  canSubmitResult: boolean;
  canVerify: boolean;
  canResolve: boolean;
  canCloseOther: boolean;
  result: {
    version: number;
    impact: string;
    summary: string | null;
    checkMethod: string | null;
    reference: string | null;
    regression: string | null;
    submittedAt: string;
  } | null;
  verification: {
    version: number;
    decision: 'PASSED' | 'FAILED';
    valid: boolean;
    testScope: string | null;
    expected: string | null;
    actual: string | null;
    dataBasis: string | null;
    verifiedAt: string;
  } | null;
  notice: { state: string; sentAt: string | null; readAt: string | null } | null;
};

/** 产品内通知与业务正文独立，授权失效时省略原问链接。 */
export type FeedbackNotice = {
  id: string;
  message: string;
  sourceId: string | null;
  requestId: string | null;
  sentAt: string;
  readAt: string | null;
};

/** 初步方向沿用原型，待核实也是有效的分派选择。 */
export const directions = {
  UNDETERMINED: '待核实',
  KNOWLEDGE: '业务知识',
  TECHNICAL: '技术问题',
  SOURCE_DATA: '源数据',
  DEFINITION: '口径解释',
  ACCESS: '权限限制',
} as const;

/** 只映射事实状态，避免把负责人变更误作处理完成。 */
export const statusLabels: Record<string, string> = {
  PENDING_ASSIGNMENT: '待分派',
  IN_PROGRESS: '处理中',
  PENDING_VERIFICATION: '待验证',
  RESOLVED: '已解决',
  CLOSED_OTHER: '其他结案',
};
