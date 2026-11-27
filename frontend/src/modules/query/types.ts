/**
 * 当前可选择的真实数据源。
 */
export type Source = {
  /**
   * 访问历史和提交新问数共同使用的编号。
   */
  id: string;
  /**
   * 当前账号有权回看的来源名称。
   */
  name: string;
  /**
   * 零表示允许提交，其他值为后端的阻断业务码。接口升级期间可能缺省。
   */
  askBlockedCode?: number;
};

/**
 * 本人在当前源的近期轮次摘要。
 */
export type Turn = { id: string; question: string; state: string; createdAt: string };

/**
 * 左侧对话项保留其实际最近轮次入口。
 */
export type Conversation = {
  id: string;
  title: string;
  latestRequestId: string;
  updatedAt: string;
};

/**
 * 解释的实际修订不改变查询状态或结果。
 */
export type Explanation = {
  revision: number;
  status:
    'QUEUED' | 'GENERATING' | 'COMPLETED' | 'FAILED' | 'STOPPED' | 'UNAVAILABLE' | 'NOT_GENERATED';
  /**
   * 后端返回的当前生成限制。
   */
  blockedReason?: string | null;
  text: string | null;
};

/** 限量结果带有服务端实际查询时间序列标识。 */
export type QueryResult = {
  columns: { name: string; label: string }[];
  rows: (string | null)[][];
  truncated: boolean;
  unit: string;
  scope: string;
  dataUpdatedAt: string | null;
  series?: { start: string; end: string } | null;
};

/**
 * 查询实际结果与同版依据。未获权限时服务端省略 SQL。
 */
export interface Snapshot extends Turn {
  /** 持续固定的单一来源。 */
  sourceId: string;
  /** 同一个问题下的缺项补充。 */
  supplement: string | null;
  /** 请求与实际数据库执行的状态分别记录。 */
  executionState: string;
  /** 技术故障时当前账号可见的阶段与原因。 */
  failureDetail?: string | null;
  /** 当前是否在核实停止。 */
  cancelRequested: boolean;
  /** 模型确认的缺项。 */
  clarification: string | null;
  /** 服务端限量结果及实际查询口径。 */
  result: QueryResult | null;
  /** 使用的正式文档与确切版本。 */
  evidence: { title: string; revision: number; revisionId: string; fragment: number } | null;
  /** 可审阅校验后的 SQL 模板。 */
  sql: string | null;
  /** 数据库派发时间与当前进度版本。 */
  executedAt: string | null;
  updatedAt: string;
  /** 本轮所属的同源业务对话。 */
  conversationId: string;
  /** 调度重新接管的真实代次。 */
  runVersion: number;
  /** 已交付结果的当前解释修订。 */
  explanation: Explanation | null;
  /** 已交付且仍获准回看的回答评价身份。 */
  answerRef: { answerId: string; answerRevision: number } | null;
}
