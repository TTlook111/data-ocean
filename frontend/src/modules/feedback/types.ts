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
  action: 'ASSIGNED' | 'REVIEWED';
  actor: string;
  assignee: string | null;
  direction: string | null;
  note: string | null;
  rootCause: string | null;
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
  latestReview: Activity | null;
  activities: Activity[];
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
};
