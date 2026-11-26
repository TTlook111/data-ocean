import type { Person } from '../iam/types';

/**
 * 审批与来源仅使用现有人员模型中的公开身份字段。
 */
export type PersonOption = Pick<Person, 'id' | 'displayName' | 'username'>;

/**
 * 页面可选的最小数据源身份。
 */
export interface SourceOption {
  /** 稳定数据源编号。 */
  id: string;
  /** 数据源名称。 */
  name: string;
}

/**
 * 当前规则或审批时的规则快照。
 */
export interface OutputRule {
  /** 规则的稳定编号。 */
  id: string;
  /** 规则的名称。 */
  name: string;
  /** 实际限制全文。 */
  content: string;
  /** 当前或历史修订。 */
  version: number;
  /** 当前规则的更新时间。 */
  updatedAt?: string | null;
}

/**
 * 一个数据源当前配置的审批责任。
 */
export interface Responsibility {
  /** 责任所属源。 */
  source: SourceOption;
  /** 可为空的主审批人。 */
  primary: PersonOption | null;
  /** 可为空的替代负责人。 */
  alternate: PersonOption | null;
  /** 配置修订，未配置为零。 */
  version: number;
  /** 责任可用状态。 */
  state: string;
  /** 上次实际配置时间。 */
  updatedAt: string | null;
}

/**
 * 单据中的真实办理子项。
 */
export interface RequestItem {
  /** 子项编号。 */
  id: string;
  /** 已核定数据源。 */
  source: SourceOption | null;
  /** 当前子项状态。 */
  state: string;
  /** 当前应办理人员。 */
  assignee: PersonOption | null;
  /** 实际经办人。 */
  handler: PersonOption | null;
  /** 实际办理时间。 */
  handledAt: string | null;
  /** 分派异常或实际办理原因。 */
  reason: string | null;
  /** 此子项批准时保存的规则。 */
  rules: OutputRule[];
  /** 部分重提后仅属于此子项的资料。 */
  application?: {
    /** 实际申请用途。 */
    purpose: string;
    /** 本次需要的数据。 */
    dataNeeded: string;
    /** 对办理要求的补充说明。 */
    supplement: string;
  } | null;
}

/**
 * 来自本人或已限定办理范围的申请单。
 */
export interface AccessRequest {
  /** 单据编号。 */
  id: string;
  /** 用于提交核对的整单版本。 */
  version: number;
  /** 本人身份及授权目标。 */
  applicant: PersonOption;
  /** 申请人当前直属主部门的名称。 */
  departmentName?: string | null;
  /** 业务用途。 */
  purpose: string;
  /** 数据需求。 */
  dataNeeded: string;
  /** 最近一次补充说明。 */
  supplement: string;
  /** 全部子项是否联合生效。 */
  joint: boolean;
  /** 首次提交时间，草稿为空。 */
  submittedAt: string | null;
  /** 当前可见的真实办理子项。 */
  items: RequestItem[];
  /** 当前整单可调整审批结构。 */
  planAvailable?: boolean;
  /** 可调整时提供完整范围，独立于已筛选的子项。 */
  planSources?: SourceOption[];
}

/**
 * 申请审批实际形成的个人来源。
 */
export interface AccessGrant {
  /** 个人来源编号。 */
  id: string;
  /** 实际被授权人。 */
  grantee: PersonOption;
  /** 实际批准人。 */
  approver: PersonOption;
  /** 个人来源绑定的数据源。 */
  source: SourceOption;
  /** 原申请编号。 */
  requestId: string;
  /** 最新整单修订号。 */
  version: number;
  /** 是否整组结束来源。 */
  joint: boolean;
  /** 当前来源状态。 */
  state: string;
  /** 实际批准时刻。 */
  approvedAt: string;
  /** 实际生效时刻。 */
  startedAt: string;
  /** 结束时刻。 */
  endedAt: string | null;
  /** 当前可回看的结束原因。 */
  endReason: string | null;
  /** 批准时冻结的规则。 */
  rules: OutputRule[];
}

/**
 * 实时访问决策聚合以及每笔来源。
 */
export interface AccessScope {
  /** 真实可用的数据源。 */
  source: SourceOption;
  /** 按正文去重后的当前规则。 */
  rules: OutputRule[];
  /** 分开回看的有效来源。 */
  origins: {
    /** 对应的配置或申请来源。 */
    id: string;
    /** 部门或申请来源类型。 */
    kind: string;
    /** 原申请或部门编号。 */
    originId: string;
    /** 原来源名称。 */
    originName: string;
    /** 本笔来源引用的当前规则。 */
    rules: OutputRule[];
  }[];
}

/**
 * 同步真实部门版本和当前初始访问配置。
 */
export interface DepartmentAccess {
  /** 部门编号。 */
  id: string;
  /** 部门名称。 */
  name: string;
  /** 当前影响核对的版本。 */
  version: number;
  /** 恢复后是否待复核。 */
  reviewRequired: boolean;
  /** 当前直属成员影响数。 */
  memberCount: number;
  /** 部门选择的数据源和规则。 */
  sources: AccessScope[];
}

/**
 * 部门配置和直属归属相交后形成的一笔历史来源。
 */
export interface DepartmentGrant {
  /** 部门配置与人员关系的稳定组合编号。 */
  id: string;
  /** 实际所属部门。 */
  departmentId: string;
  /** 来源部门名称。 */
  departmentName: string;
  /** 被授权账号。 */
  grantee: PersonOption;
  /** 授予的数据源。 */
  source: SourceOption;
  /** 当前来源状态。 */
  state: string;
  /** 同时具备配置和成员关系的开始时间。 */
  startedAt: string;
  /** 已结束来源的确切结束时间。 */
  endedAt: string | null;
  /** 该笔来源的适用规则。 */
  rules: OutputRule[];
}

/**
 * 两类真实授权在同一管理列表分页，详情使用各自来源结构。
 */
export type GrantDirectoryEntry = {
  /** 带来源类型的稳定列表编号。 */
  id: string;
} & (
  | { /** 实际申请授权。 */ request: AccessGrant; /** 该行没有部门配置。 */ department: null }
  | {
      /** 该行没有个人申请。 */ request: null;
      /** 真实部门派生来源。 */ department: DepartmentGrant;
    }
);

/**
 * 本人申请历史中的实际处理动作。
 */
export interface RequestActivity {
  /** 历史提交版本。 */
  version: number;
  /** 实际办理动作。 */
  action: string;
  /** 原实际办理人。 */
  actor: PersonOption;
  /** 提交实际时刻。 */
  createdAt: string;
}
