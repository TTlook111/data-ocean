/** 队列的基础事项与当前责任。 */
export type Issue = {
  id: string;
  sourceId: string;
  sourceName: string;
  origin: string;
  status: string;
  assigneeId: string | null;
  assigneeName: string | null;
  createdAt?: string;
  updatedAt: string;
  summary?: string;
  referenceCount?: number;
  nextAction?: string;
};

/** 业务自由文本可能受限，操作人与责任关系仍可核对。 */
export type Activity = {
  version: number;
  action:
    | 'ASSIGNED'
    | 'REVIEWED'
    | 'RESULT_SUBMITTED'
    | 'VERIFIED'
    | 'RECHECK_FAILED'
    | 'CLOSED'
    | 'TASK_ADDED'
    | 'TASK_ASSIGNED'
    | 'TASK_REVIEWED'
    | 'TASK_NO_CHANGE'
    | 'TASK_CANCELLED'
    | 'TASK_RESTORED'
    | 'SOURCE_FOLLOW_UP'
    | 'SOURCE_WAITING'
    | 'TECHNICAL_FOLLOW_UP'
    | 'TECHNICAL_WAITING'
    | 'SUPPLEMENT_ASKED'
    | 'SUPPLEMENT_ADDED'
    | 'SUPPLEMENT_ENDED'
    | 'MERGED'
    | 'MEMBER_ADDED'
    | 'UNMERGED'
    | 'MEMBER_REMOVED'
    | 'REOPENED';
  actor: string;
  assignee: string | null;
  direction: string | null;
  note: string | null;
  rootCause: string | null;
  conclusion?: string | null;
  occurredAt: string;
  /** 任务操作的事项内编号，主事项操作为空。 */
  taskId?: number | null;
  /** 合并或纠正所涉及的另一个事项。 */
  relatedIssueId?: string | null;
  /** 此事件所属处理轮次。 */
  completionRound?: number;
  /** 本轮结案后的结构化结果，旧记录可能没有可回查的编码。 */
  closureCode?: keyof typeof closeReasons | 'RESOLVED' | null;
};

/**
 * 技术与源数据跟进都记录已做工作和下一步。
 */
export const followUpActions = new Set<Activity['action']>([
  'SOURCE_FOLLOW_UP',
  'SOURCE_WAITING',
  'TECHNICAL_FOLLOW_UP',
  'TECHNICAL_WAITING',
]);

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
  /** 当前独立任务，未拆分的事项沿用原处理区。 */
  tasks?: FeedbackTask[];
  /** 原问题补充和主从记录仍保留各自归属。 */
  lifecycle?: FeedbackLifecycle;
};

/**
 * 原问关联的补充请求或已有说明。
 */
export type FeedbackSupplementEntry = {
  /** 补充记录编号。 */
  id: string;
  /** 原事项编号，只在后台可见。 */
  issueId?: string | null;
  /** 要补齐的业务信息，受限或到期时为空。 */
  prompt: string | null;
  /** 实际回复，受限或到期时为空。 */
  answer: string | null;
  /** 补充请求或主动说明的创建时间。 */
  requestedAt: string;
  /** 已回复时间，待回复时为空。 */
  answeredAt: string | null;
  /** 结束等待的处置类型，实际回复的记录为空。 */
  disposition?: FeedbackSupplementDisposition | null;
  /** 处置依据，只在获授权的后台详情可见。 */
  dispositionReason?: string | null;
  /** 负责人结束等待的时间。 */
  disposedAt?: string | null;
};

/**
 * 负责人对仍待回复的补充请求作出的处理结果。
 */
export type FeedbackSupplementDisposition = 'WITHDRAWN' | 'UNAVAILABLE';

/**
 * 实际回复或人工处置均会结束这条请求的等待。
 */
export function isSupplementPending(entry: FeedbackSupplementEntry) {
  return !entry.answeredAt && !entry.disposedAt;
}

/**
 * 后台可见的关系、补充及轮次状态。
 */
export type FeedbackLifecycle = {
  /** 当前归并的主事项。 */
  mainIssueId: string | null;
  /** 当前直接成员的原事项编号。 */
  memberIssueIds: string[];
  /** 每个允许补充的原问和该问题的实际提问人。 */
  supplementTargets: {
    issueId: string;
    requestId: string;
    name: string;
    question: string | null;
  }[];
  /** 当前授权下的补充记录。 */
  supplements: FeedbackSupplementEntry[];
  /** 当前主负责人能否处置组内的待回复请求。 */
  canDisposeSupplement?: boolean;
  /** 当前账号可发起人工合并。 */
  canMerge: boolean;
  /** 当前账号可纠正现有合并。 */
  canUnmerge: boolean;
  /** 当前账号可凭依据重开。 */
  canReopen: boolean;
  /** 沿用主事项的完成轮次。 */
  completionRound: number;
  /** 已形成的各轮次投递和已读状态。 */
  noticeRounds: {
    round: number;
    total: number;
    pending: number;
    delivered: number;
    restricted: number;
    suppressed: number;
    readCount: number;
  }[];
};

/**
 * 同一主事项下分派给独立负责人的治理或技术任务。
 */
export type FeedbackTask = {
  /** 在主事项中保持稳定的任务编号。 */
  id: number;
  /** 任务实际办理方向。 */
  direction: 'KNOWLEDGE' | 'TECHNICAL' | 'SOURCE_DATA';
  /** 当前任务负责人编号。 */
  assigneeId: string;
  /** 当前负责人公开姓名。 */
  assigneeName: string;
  /** 任务范围，按原问证据权限裁剪。 */
  scope: string | null;
  /** 该任务当前办理状态。 */
  status: 'IN_PROGRESS' | 'SUBMITTED' | 'NO_CHANGE' | 'CANCELLED';
  /** 当前用户是否可以记录任务核查。 */
  canReview: boolean;
  /** 当前用户是否可以转派本任务。 */
  canAssign: boolean;
  /** 当前用户是否可以退出或恢复本任务。 */
  canChangeStatus: boolean;
  /** 等待技术修复生效、源系统修正或同步期间继续保留跟进责任。 */
  waitingExternal?: boolean;
  /** 本轮仍有效的进展，正文按原问证据权限裁剪。 */
  progress: {
    /** 已完成的排查、沟通或核对工作。 */
    workDone: string | null;
    /** 继续跟进的安排。 */
    nextStep: string | null;
  } | null;
  /** 本任务当前结果、复验和操作权限。 */
  resolution: Resolution;
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
  canSubmitKnowledge?: boolean;
  /** 当前有权保存源数据跟进或实际修正结果。 */
  canSaveSourceData?: boolean;
  canReplayQuestion?: boolean;
  recheckRequestId?: string | null;
  result: {
    version: number;
    impact: string;
    summary: string | null;
    checkMethod: string | null;
    reference: string | null;
    regression: string | null;
    submittedAt: string;
    knowledgeBindingId?: string | null;
    /** 实际修正源系统的人员或团队，受限时不返回。 */
    sourceRepairer?: string | null;
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
    recheckRequestId?: string | null;
    /** 是否关联实际问数，编号只给对应执行人。 */
    questionReplay?: boolean;
  } | null;
  notice: { state: string; sentAt: string | null; readAt: string | null } | null;
};

/** 产品内通知与业务正文独立，授权失效时省略原问链接。 */
export type FeedbackNotice = {
  id: string;
  message: string;
  sourceId: string | null;
  requestId: string | null;
  /** 同一接收人本轮可访问的全部原问。 */
  requestIds?: string[];
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
/** 其他结案的原因选项和历史展示使用同一含义。 */
export const closeReasons = {
  NOT_REPRODUCED: '未复现',
  DEFINITION_CONFIRMED: '按现有口径确认无误',
  CANCELLED: '取消',
  OTHER: '其他',
};

export const statusLabels: Record<string, string> = {
  PENDING_ASSIGNMENT: '待分派',
  PENDING_SUPPLEMENT: '待补充',
  IN_PROGRESS: '处理中',
  PENDING_VERIFICATION: '待验证',
  RESOLVED: '已解决',
  CLOSED_OTHER: '其他结案',
  MERGED: '已合并',
};
