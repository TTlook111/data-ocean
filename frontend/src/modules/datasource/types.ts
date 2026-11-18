import type { Page } from '../iam/types';

/**
 * 列表中可披露的数据源概要。
 */
export interface SourceSummary {
  /**
   * 稳定源编号。
   */
  id: string;
  /**
   * 数据源名称。
   */
  name: string;
  /**
   * 数据库类型。
   */
  databaseType?: string;
  /**
   * 真实识别版本。
   */
  detectedVersion?: string;
  /**
   * 技术联系人。
   */
  technicalContact?: string;
  /**
   * 配置状态。
   */
  lifecycle: string;
  /**
   * 最近采集状态。
   */
  latestTaskState?: string;
  /**
   * 最近采集结束时间。
   */
  latestTaskEndedAt?: string;
  /**
   * 最近成功采集时间。
   */
  lastSuccessfulAt?: string;
  /**
   * 当前写入版本。
   */
  version: number;
}

/**
 * 数据库配置，不包含秘密。
 */
export interface SourceConfiguration {
  /**
   * 源名称。
   */
  name: string;
  /**
   * 用途说明。
   */
  purpose?: string;
  /**
   * 技术联系人。
   */
  technicalContactId?: string;
  /**
   * 业务联系人。
   */
  businessContactId?: string;
  /**
   * 源库类型。
   */
  databaseType: string;
  /**
   * 连接地址。
   */
  host?: string;
  /**
   * 连接端口。
   */
  port?: number;
  /**
   * 数据库名。
   */
  databaseName?: string;
  /**
   * 专用只读账号。
   */
  account?: string;
  /**
   * PostgreSQL 范围。
   */
  schemas?: string[];
}

/**
 * 来源采集运行及调度参数。
 */
export interface RuntimePolicy {
  /**
   * 最大连接数。
   */
  maxConnections: number;
  /**
   * 采集并行上限。
   */
  collectionConcurrency: number;
  /**
   * 瞬时网络重试上限。
   */
  retryLimit: number;
  /**
   * 任务执行秒数。
   */
  taskTimeoutSeconds: number;
  /**
   * 单次查询秒数。
   */
  queryTimeoutSeconds: number;
  /**
   * 业务查询结果上限。
   */
  rowLimit: number;
  /**
   * 定时周期小时数。
   */
  syncHours: number;
  /**
   * 允许窗口开始时刻。
   */
  windowStart: string;
  /**
   * 允许窗口结束时刻。
   */
  windowEnd: string;
}

/**
 * 从实际生效计划推算的状态。
 */
export interface ScheduleStatus {
  /**
   * 当前计划状态。
   */
  state: string;
  /**
   * 是否暂停未来定时采集。
   */
  paused: boolean;
  /**
   * 配置时区。
   */
  timeZone: string;
  /**
   * 下次采集时刻。
   */
  nextScheduledAt?: string;
  /**
   * 本段陈旧开始时刻。
   */
  staleSince?: string;
}

/**
 * 来源管理可读的当前配置。
 */
export interface SourceDetail {
  /**
   * 复用摘要。
   */
  source: SourceSummary;
  /**
   * 当前生效的非秘密配置。
   */
  configuration: SourceConfiguration;
  /**
   * 当前运行参数。
   */
  runtimePolicy: RuntimePolicy;
  /**
   * 是否已有受控凭据。
   */
  credentialConfigured: boolean;
  /**
   * 固定配置修订。
   */
  configurationRevision: number;
  /**
   * 当前成功快照。
   */
  currentSnapshotId?: string;
  /**
   * 来源实际活动任务。
   */
  activeTaskId?: string;
  /**
   * 待停止或切换的替换。
   */
  pendingCredentialChangeId?: string;
  /**
   * 当前计划状态。
   */
  schedule: ScheduleStatus;
}

/**
 * 采集与目录共用的源选择项。
 */
export interface SourceOption {
  /**
   * 来源编号。
   */
  id: string;
  /**
   * 来源名称。
   */
  name: string;
  /**
   * 生命周期。
   */
  lifecycle: string;
  /**
   * 操作核对时的版本。
   */
  version: number;
}

/**
 * 真实源库逐项测试结果。
 */
export interface ConnectionCheck {
  /**
   * 测试编号。
   */
  checkId: string;
  /**
   * 实际库版本。
   */
  detectedVersion?: string;
  /**
   * 可选 Schema。
   */
  availableSchemas: string[];
  /**
   * 本次已测试 Schema。
   */
  verifiedSchemas: string[];
  /**
   * 分项结果。
   */
  items: CheckItem[];
  /**
   * 具备启用条件。
   */
  canEnable: boolean;
}

/**
 * 连接测试单项能力。
 */
export interface CheckItem {
  /**
   * 分项编号。
   */
  code: string;
  /**
   * 分项名称。
   */
  label: string;
  /**
   * 真实结论。
   */
  status: string;
  /**
   * 可读原因。
   */
  message: string;
}

/**
 * 实际采集任务摘要和状态内容。
 */
export interface CollectionTask {
  /**
   * 任务编号。
   */
  taskId: string;
  /**
   * 展示编号。
   */
  taskNo: string;
  /**
   * 所属来源。
   */
  sourceId: string;
  /**
   * 发起方式。
   */
  triggerKind: string;
  /**
   * 已冻结的发起人。
   */
  actor: { name: string };
  /**
   * 核对采集范围。
   */
  scopeLabel: string;
  /**
   * 当次只读账号。
   */
  account: string;
  /**
   * 接收时刻。
   */
  createdAt: string;
  /**
   * 实际开始时间。
   */
  startedAt?: string;
  /**
   * 实际结束时间。
   */
  endedAt?: string;
  /**
   * 重试对应原任务。
   */
  parentTaskId?: string;
  /**
   * 固定配置修订。
   */
  configRevision: number;
  /**
   * 任务状态。
   */
  state: string;
  /**
   * 源执行状态。
   */
  executionState: string;
  /**
   * 核对状态版本。
   */
  version: number;
  /**
   * 取消及核实进度。
   */
  stopProgress?: { message: string };
  /**
   * 状态对应的结果。
   */
  content?: TaskContent;
}

/**
 * 采集中或已结束的可披露结果。
 */
export interface TaskContent {
  /**
   * 实际读取阶段。
   */
  phase?: string;
  /**
   * 进度数据。
   */
  counts?: Counts;
  /**
   * 本次成功快照。
   */
  snapshotId?: string;
  /**
   * 成功读取对象数。
   */
  collectedObjectCount?: number;
  /**
   * 目录分析结论。
   */
  analysis?: string;
  /**
   * 实际差异数。
   */
  changeCount?: number;
  /**
   * 真实失败原因。
   */
  failureMessage?: string;
  /**
   * 原取消原因。
   */
  reason?: string;
}

/**
 * 前端进度区显示的可用统计。
 */
export interface Counts {
  /**
   * 完整枚举前为空。
   */
  totalCount?: number;
  /**
   * 已发现数。
   */
  discoveredCount: number;
  /**
   * 尝试读取数。
   */
  attemptedCount: number;
}

/**
 * 目录位于某次成功快照的实际元数据对象。
 */
export interface CatalogRecord {
  /**
   * 稳定对象身份。
   */
  objectId?: string;
  /**
   * 实际观察编号。
   */
  observationId: string;
  /**
   * 真实限定名。
   */
  qualifiedName: string;
  /**
   * 对象类别。
   */
  objectType: string;
  /**
   * 来源说明原文。
   */
  sourceComment?: string;
  /**
   * 目录关注项。
   */
  catalogStates: string[];
  /**
   * 本次快照。
   */
  snapshotId: string;
  /**
   * 采集时间。
   */
  collectedAt: string;
}

/**
 * 目录实际分页与成功快照。
 */
export interface CatalogPage {
  /**
   * 对象分页结果。
   */
  page: Page<CatalogRecord>;
  /**
   * 当前成功快照。
   */
  currentSnapshotId?: string;
  /**
   * 目录投影版本。
   */
  catalogVersion: number;
}

/**
 * 真实字段结构。
 */
export interface CatalogField {
  /**
   * 字段名。
   */
  name: string;
  /**
   * 真实源类型。
   */
  type: string;
  /**
   * 是否可空。
   */
  nullable: boolean;
  /**
   * 默认表达式。
   */
  defaultValue?: string;
  /**
   * 数据库是否生成。
   */
  generated: boolean;
  /**
   * 来源字段注释。
   */
  comment?: string;
  /**
   * 字段次序。
   */
  ordinal: number;
}

/**
 * 键和索引的实际成员。
 */
export interface CatalogKey {
  /**
   * 来源名称。
   */
  name?: string;
  /**
   * 在此键中的字段。
   */
  field?: string;
  /**
   * 成员顺序。
   */
  ordinal: number;
  /**
   * 外键来源。
   */
  referencedObject?: string;
  /**
   * 外键字段。
   */
  referencedField?: string;
}

/**
 * 完整对象阅读页。
 */
export interface CatalogDetail {
  /**
   * 实际对象。
   */
  object: CatalogRecord;
  /**
   * 固定修订标识。
   */
  objectRevision?: string;
  /**
   * 字段定义。
   */
  columns: CatalogField[];
  /**
   * 来源主键。
   */
  primaryKeys: CatalogKey[];
  /**
   * 来源外键。
   */
  foreignKeys: CatalogKey[];
  /**
   * 适用物理索引。
   */
  indexes: CatalogKey[];
  /**
   * 分区父表。
   */
  parent?: string;
}

/**
 * 来源发现和人工核实的事实记录。
 */
export interface StructureChange {
  /**
   * 变化编号。
   */
  changeId: string;
  /**
   * 实际来源任务。
   */
  sourceTaskId: string;
  /**
   * 变化对象。
   */
  target: string;
  /**
   * 变化分类。
   */
  changeType: string;
  /**
   * 实际变化项。
   */
  attributeKind: string;
  /**
   * 发现时间。
   */
  discoveredAt: string;
  /**
   * 事实状态。
   */
  factState: string;
  /**
   * 处理阶段。
   */
  stage: string;
  /**
   * 变化版本。
   */
  version: number;
  /**
   * 原始证据。
   */
  before?: unknown;
  /**
   * 当前证据。
   */
  after?: unknown;
  /**
   * 已有核实结果。
   */
  latestReview?: { decision: string; evidenceText: string };
}

/**
 * 凭据替换独立回执。
 */
export interface CredentialChange {
  /**
   * 替换编号。
   */
  changeId: string;
  /**
   * 替换状态。
   */
  state: string;
  /**
   * 源侧停止状态。
   */
  executionState: string;
  /**
   * 真实结果原因。
   */
  message?: string;
  /**
   * 原在途任务。
   */
  blockingTaskId?: string;
}
