/**
 * 表说明与字段定义只共享编辑行为，业务键各自明确。
 */
export type Definition = {
  /**
   * 表或字段的业务名称。
   */
  businessName?: string;
  /**
   * 表实际承载的业务用途。
   */
  purpose?: string;
  /**
   * 一行代表的业务粒度。
   */
  rowGrain?: string;
  /**
   * 记录识别与去重。
   */
  rowIdentity?: string;
  /**
   * 范围与排除约束。
   */
  dataScope?: string;
  /**
   * 时间在业务中的实际含义。
   */
  timeSemantics?: string;
  /**
   * 数据更新和保留。
   */
  updatePolicy?: string;
  /**
   * 使用注意事项。
   */
  usageNotes?: string;
  /**
   * 已知的业务数据质量问题。
   */
  qualityNotes?: string;
  /**
   * 字段的实际业务含义。
   */
  meaning?: string;
  /**
   * 数据值的解释。
   */
  valueDescription?: string;
  /**
   * 原编码及实际含义，编码保持字符串。
   */
  valueMappings?: { code: string; meaning: string }[];
  /**
   * 金额或数量单位。
   */
  unit?: string;
  /**
   * 业务字段格式。
   */
  format?: string;
  /**
   * 空值、空串及零的区别。
   */
  nullMeaning?: string;
  /**
   * 缺少字符但不是空值的含义。
   */
  emptyMeaning?: string;
  /**
   * 数值为零时的业务含义。
   */
  zeroMeaning?: string;
  /**
   * 可计算业务时区。
   */
  businessTimeZone?: string;
  /**
   * 适用的计算及筛选规则。
   */
  usageRule?: string;
  /**
   * 敏感或仅限特定用途的说明。
   */
  sensitivityNotes?: string;
};

/**
 * 固定的稳定对象或字段目标。
 */
export interface Target {
  /**
   * 对象的稳定编号。
   */
  objectId: string;
  /**
   * 表说明与字段各自保存及确认。
   */
  kind: 'TABLE' | 'COLUMN';
  /**
   * 当前对象或字段的稳定编号。
   */
  targetId: string;
}

/**
 * 当前业务编辑引用的确切采集修订。
 */
export interface Basis {
  /**
   * 实际成功采集快照。
   */
  snapshotId: string;
  /**
   * 当前目标所在的对象结构。
   */
  objectRevisionId: string;
  /**
   * 字段的独立结构修订。
   */
  columnRevisionId?: string | null;
}

/**
 * 资料正文或原件的固定出处。
 */
export interface EvidenceLocation {
  /**
   * 正文使用 BODY，整份原件使用 ATTACHMENT。
   */
  section: string;
  /**
   * 正文起始偏移，单位为 UTF-16 字符。
   */
  start?: number | null;
  /**
   * 正文结束偏移，右端不包含。
   */
  end?: number | null;
}

/**
 * 人工为一项业务内容关联一个确切资料修订。
 */
export interface NoteReference {
  /**
   * 依据支持的业务字段。
   */
  contentPath: keyof Definition;
  /**
   * 企业说明的稳定编号。
   */
  noteId: string;
  /**
   * 实际保存的修订主键。
   */
  revisionId: string;
  /**
   * 整份原件编号，正文不填。
   */
  attachmentId?: string | null;
  /**
   * 可回看的源位置。
   */
  location: EvidenceLocation;
}

/**
 * 实际证据仅在具备当前权限时可定位。
 */
export interface Evidence {
  /**
   * 结构、企业说明或人工填写。
   */
  kind: 'STRUCTURE' | 'NOTE' | 'MANUAL';
  /**
   * 对应的业务字段位置。
   */
  contentPath: keyof Definition;
  /**
   * 当时的资料稳定编号。
   */
  noteId: string | null;
  /**
   * 保存时采用的确切修订主键。
   */
  revisionId: string | null;
  /**
   * 展示和回跳使用的资料版本序号。
   */
  revision: number | null;
  /**
   * 可获准下载的原件。
   */
  attachmentId: string | null;
  /**
   * 原文定位。
   */
  location: EvidenceLocation | null;
  /**
   * 操作者没有具体出处查看资格。
   */
  redacted: boolean;
  /**
   * 业务人工结论。
   */
  manualBasis: string | null;
}

/**
 * 当前可确认的业务版本。
 */
export interface Revision {
  /**
   * 内容修订主键。
   */
  revisionId: string;
  /**
   * 条目内的内容修订序号。
   */
  revision: number;
  /**
   * 已写入的结构化定义。
   */
  content: Definition;
  /**
   * 人工来源或 AI 辅助。
   */
  origin: 'MANUAL' | 'AI_ASSISTED';
  /**
   * 固定使用的资料与结构依据。
   */
  evidenceRefs: Evidence[];
}

/**
 * 来源上的定向复核。
 */
export interface Impact {
  /**
   * 影响的稳定编号。
   */
  impactId: string;
  /**
   * 对象或字段实际办理目标。
   */
  target: Target;
  /**
   * 字段原名或表说明。
   */
  targetName: string;
  /**
   * 新增、复核或移除。
   */
  kind: 'NEW_ITEM' | 'RECHECK' | 'REMOVAL';
  /**
   * 当前办理状态。
   */
  state: 'PENDING' | 'IN_PROGRESS' | 'REVIEWED';
  /**
   * 可披露的 B 批结构或资料定位。
   */
  origin?: {
    /**
     * 结构变化的稳定编号。
     */
    changeId?: string | null;
    /**
     * 企业说明稳定编号。
     */
    noteId?: string | null;
    /**
     * 变化前后的资料内容修订号。
     */
    beforeRevision?: number | null;
    /**
     * 撤下时无后侧修订。
     */
    afterRevision?: number | null;
  } | null;
}

/**
 * 待核清问题和历史结论。
 */
export interface Gap {
  /**
   * 实际业务问题编号。
   */
  gapId: string;
  /**
   * 此问题影响的业务字段。
   */
  contentPath: keyof Definition;
  /**
   * 需要实际回答的问题。
   */
  question: string;
  /**
   * 开放或已解决。
   */
  state: 'OPEN' | 'RESOLVED';
  /**
   * 实际人工填写的结论。
   */
  resolution?: string | null;
}

/**
 * 当前工作台条目快照。
 */
export interface Item {
  /**
   * 本次编辑的稳定目标。
   */
  target: Target;
  /**
   * 为空时条目尚未物化。
   */
  itemId: string | null;
  /**
   * 当前并发版本。
   */
  version: number | null;
  /**
   * 当前可用或最后成功结构。
   */
  structureBasis: Basis;
  /**
   * 空白、草稿或确认。
   */
  state: string;
  /**
   * 有实际来源变化需要复核。
   */
  pendingReview: boolean;
  /**
   * 已持久化的可披露定义。
   */
  currentRevision: Revision | null;
  /**
   * 实际确认摘要，保留旧版本审核。
   */
  latestConfirmation: { confirmedAt: string; id: string; action: string } | null;
  /**
   * 待补充及已核清事项。
   */
  gaps: Gap[];
  /**
   * 同一条目的实际来源记录。
   */
  impacts: Impact[];
  /**
   * 当前禁止使用。
   */
  blocked: boolean;
  /**
   * 是否还能实际编辑。
   */
  availability: string;
  /**
   * 当前条目的原字段名或表说明名称。
   */
  displayName: string;
  /**
   * 固定结构中的技术原生类型。
   */
  nativeType?: string | null;
  /**
   * 固定成功结构中的原注释。
   */
  sourceComment: string | null;
  /**
   * 前端仅据此呈现动作，写入仍由服务器核验。
   */
  actions: {
    /**
     * 可以编辑。
     */
    canEdit: boolean;
    /**
     * 可以读取 AI 建议。
     */
    canSuggest: boolean;
    /** 具有生成权限但当前来源无法传给模型时的原因。 */
    suggestUnavailableReason?: string | null;
    /**
     * 可以确认。
     */
    canConfirm: boolean;
    /**
     * 可以提出具体问题。
     */
    canSupplement: boolean;
    /**
     * 可以执行危险的使用控制。
     */
    canForbid: boolean;
    /**
     * 可以确认已证明的移除。
     */
    canAcknowledgeRemoval: boolean;
  };
}

/**
 * 当前来源表列表的一行。
 */
export interface GovernanceRow {
  /**
   * 原数据源编号。
   */
  sourceId: string;
  /**
   * 待核实时没有稳定对象编号。
   */
  objectId: string | null;
  /**
   * 成功观察的唯一编号。
   */
  observationId: string;
  /**
   * 源端限定名称。
   */
  qualifiedName: string;
  /**
   * 表或视图等来源类型。
   */
  objectType: string;
  /**
   * 采集原注释。
   */
  sourceComment: string | null;
  /**
   * 实际编辑的业务名称。
   */
  businessName: string | null;
  /**
   * 实际编辑的业务用途。
   */
  purpose: string | null;
  /**
   * 当前结构可办性。
   */
  availability: string;
  /**
   * 表说明与字段进度。
   */
  progress: string;
  /**
   * 有效已确认数。
   */
  confirmedCount: number;
  /**
   * 当前适用的全部必要项。
   */
  totalCount: number;
  /**
   * 仍需办理的类别。
   */
  pendingKinds: ('TABLE' | 'COLUMN')[];
  /**
   * 变更后需复核的条目。
   */
  pendingImpacts: Impact[];
}

/**
 * 页面详情的结构和实际治理上下文。
 */
export interface GovernanceObject {
  /**
   * 当前源显示名称。
   */
  sourceName: string;
  /**
   * 技术限定路径。
   */
  qualifiedName: string;
  /**
   * 来源原注释。
   */
  sourceComment: string | null;
  /**
   * 当前对象可用性。
   */
  availability: string;
  /**
   * 同表完整的技术待核实数。
   */
  pendingFactCount: number;
  /**
   * 建议首个待办理目标。
   */
  nextPendingTarget: Target | null;
  /**
   * 表说明的实际状态。
   */
  tableEntry: Item;
  /**
   * 含已移除字段的待办。
   */
  pendingImpacts: Impact[];
}

/**
 * 字段导航中的技术信息和治理状态。
 */
export interface ColumnRow {
  /**
   * 固定字段目标。
   */
  target: Target;
  /**
   * 原名称。
   */
  name: string;
  /**
   * 原生类型。
   */
  nativeType: string;
  /**
   * 技术空值约束。
   */
  nullable: boolean;
  /**
   * 原始注释。
   */
  sourceComment: string | null;
  /**
   * 审核状态。
   */
  state: string;
  /**
   * 当前存在复核事项。
   */
  pendingReview: boolean;
}

/**
 * 一次安全写入对应的版本和确认。
 */
export interface WriteResult {
  /**
   * 本次写入后的版本。
   */
  version: number | null;
  /**
   * 本命令关联的实际内容修订。
   */
  currentRevision: Revision | null;
  /**
   * 本命令关联的实际确认。
   */
  confirmationId: string | null;
}

/**
 * B 批资料选择和原文回看所需的最小格式。
 */
export interface BusinessNoteExcerpt {
  /**
   * 确切正文及附件。
   */
  content: {
    /**
     * 不可变主键。
     */
    revisionId: string;
    /**
     * 当版标题。
     */
    title: string;
    /**
     * 当次实际正文。
     */
    body: string;
    /**
     * 当版用于人工引用的原件。
     */
    attachments: { id: string; name: string }[];
    /**
     * 返回历史仍准确定位修订。
     */
    revision: number;
  };
}

/**
 * 生成结果提交前采用确切内容差异。
 */
export interface Suggestion {
  /**
   * 当前条目的原始建议。
   */
  suggestionId: string;
  /**
   * 由真实调用形成的候选内容。
   */
  content: Definition;
}

/**
 * AI 任务接收或读回的持久状态。
 */
export interface Generation {
  /**
   * 后端任务编号。
   */
  requestId: string;
  /**
   * 真实任务状态。
   */
  state: string;
  /**
   * 失败的安全原因。
   */
  failureCode?: string | null;
  /**
   * 当前条目对应的已存结果。
   */
  targets?: GenerationTarget[];
}

/**
 * 批内独立执行项也用于单项任务回读。
 */
export interface GenerationTarget {
  /**
   * 本项独立执行编号，用于回看建议。
   */
  requestId: string;
  /**
   * 本项处理的稳定条目。
   */
  target: Target;
  /**
   * 关联或指标候选发现，普通建议没有此值。
   */
  discoveryKind?: 'RELATION' | 'METRIC' | null;
  /**
   * 单项实际状态，可独立成功或失败。
   */
  state: string;
  /**
   * 当前能够获准回看的原始建议。
   */
  suggestion?: Suggestion | null;
  /**
   * 单项失败原因编码。
   */
  failureCode?: string | null;
  /**
   * 当前有源访问权限时可见的实际辅助读取摘要。
   */
  samples?:
    | {
        /** 真实尝试顺序。 */
        attempt: number;
        /** 查询结果的稳定状态。 */
        status: string;
        /** 源库实际返回行数。 */
        returnedRows: number;
        /** 有诊断权时回看的固定只读语句。 */
        statement?: string | null;
      }[]
    | null;
}

/**
 * 人工读取的固定版本历史。
 */
export interface History {
  /**
   * 原记录编号。
   */
  id: string;
  /**
   * 修订或确认类型。
   */
  kind: string;
  /**
   * 时间。
   */
  occurredAt: string;
  /**
   * 原修订号。
   */
  revision: number | null;
  /**
   * 原动作。
   */
  action: string | null;
  /**
   * 指定修订时才含可披露详情。
   */
  detail?: Revision | null;
}
