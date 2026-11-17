/**
 * 所有外部标识保留字符串，避免 JavaScript 长整型精度丢失。
 */
export interface Versioned {
  /**
   * 稳定资源标识。
   */
  id: string;
  /**
   * 当前聚合修订，提交时用于并发检查。
   */
  version: number;
}

/**
 * 服务端分页结果，查询范围与总数由数据库统一计算。
 */
export interface Page<T> {
  /**
   * 当前有效页的记录。
   */
  items: T[];
  /**
   * 同一筛选范围的总数。
   */
  total: number;
  /**
   * 服务端校正后的页码，从 1 开始。
   */
  pageNum: number;
  /**
   * 实际每页条数。
   */
  pageSize: number;
}

/**
 * 浏览器仅保存当前显示身份，授权始终由服务端重核。
 */
export interface Session {
  /**
   * 当前登录人员。
   */
  userId: string;
  /**
   * 稳定登录名。
   */
  username: string;
  /**
   * 当前姓名。
   */
  displayName: string;
  /**
   * 人员资料修订。
   */
  version: number;
  /**
   * 是否只能进行首次或重置后改密。
   */
  mustChangePassword: boolean;
  /**
   * 当前功能使用代码，受限身份时为空。
   */
  permissionCodes: string[];
  /**
   * 当前配置安全修订，仅作界面刷新依据。
   */
  securityVersion: string;
}

/**
 * 部门引用用于主兼任展示及人员选择。
 */
export interface DepartmentRef {
  /**
   * 部门稳定标识。
   */
  id: string;
  /**
   * 业务编号。
   */
  code: string;
  /**
   * 部门名称。
   */
  name: string;
  /**
   * 正常、停用或归档状态。
   */
  lifecycle: string;
}

/**
 * 角色引用保留实际版本与受保护标记。
 */
export interface RoleRef extends Versioned {
  /**
   * 角色名称，不作为权限依据。
   */
  name: string;
  /**
   * 当前生命周期。
   */
  lifecycle: string;
  /**
   * 是否包含受保护使用或授予项。
   */
  containsProtected: boolean;
}

/**
 * 列表和详情共用当前或归档时的人员归属。
 */
export interface Person extends Versioned {
  /**
   * 创建后不可修改的登录名。
   */
  username: string;
  /**
   * 人员姓名。
   */
  displayName: string;
  /**
   * 主部门，归档时为历史快照。
   */
  primaryDepartment: DepartmentRef | null;
  /**
   * 兼任部门，不带层级授权继承。
   */
  secondaryDepartments: DepartmentRef[];
  /**
   * 当前分配或归档快照角色。
   */
  roles: RoleRef[];
  /**
   * 包含首次待登录和待改密的展示状态。
   */
  displayStatus: string;
  /**
   * 本人联系手机号。
   */
  phone?: string | null;
  /**
   * 本人联系邮箱。
   */
  email?: string | null;
  /**
   * 管理备注，仅人员管理详情使用。
   */
  managementNote?: string | null;
  /**
   * 当前记录更新时间。
   */
  updatedAt?: string;
  /**
   * 成员列表中命中的主兼任关系。
   */
  matchingMemberships?: Membership[];
}

/**
 * 部门成员行的实际命中关系。
 */
export interface Membership {
  /**
   * 用于结束指定兼任的关系标识。
   */
  id: string;
  /**
   * 关系所属部门。
   */
  department: DepartmentRef;
  /**
   * 是否为当前主部门关系。
   */
  primary: boolean;
  /**
   * 契约中的主部门标记。
   */
  isPrimary?: boolean;
}

/**
 * 角色详情复用列表字段，功能配置另按真实接口读取。
 */
export interface Role extends RoleRef {
  /**
   * 可空角色说明。
   */
  description: string | null;
  /**
   * 当前分配人员数量。
   */
  memberCount: number;
  /**
   * 实际功能集合，详情返回。
   */
  permissionCodes: string[];
  /**
   * 当前角色是否可被分配。
   */
  selectable?: boolean;
  /**
   * 是否为目标人员原已选角色。
   */
  selected?: boolean;
  /**
   * 最后一次配置更新时间。
   */
  updatedAt: string;
}

/**
 * 部门树与详情采用同一身份，统计只在详情返回。
 */
export interface Department extends DepartmentRef, Versioned {
  /**
   * 层级子节点。
   */
  children: Department[];
  /**
   * 筛选时是否为直接命中节点。
   */
  matched?: boolean;
  /**
   * 当前上级部门。
   */
  parent: DepartmentRef | null;
  /**
   * 当前负责人，以人员 ID 绑定。
   */
  manager: Pick<Person, 'id' | 'username' | 'displayName'> | null;
  /**
   * 部门说明。
   */
  note: string | null;
  /**
   * 同级排序值。
   */
  sortOrder: number;
  /**
   * 当前直属成员数。
   */
  directMemberCount: number;
  /**
   * 后代部门范围的去重成员数。
   */
  descendantMemberCount: number;
  /**
   * 全部范围的去重成员数。
   */
  totalMemberCount: number;
  /**
   * 保留访问配置是否待后续复核。
   */
  accessReviewRequired: boolean;
  /**
   * 该选项在当前动作中是否可选。
   */
  selectable?: boolean;
}

/**
 * 核对弹窗中的可披露字段差异。
 */
export interface Change {
  /**
   * 字段业务编码。
   */
  field: string;
  /**
   * 面向用户的字段名称。
   */
  label: string;
  /**
   * 变更前的安全摘要。
   */
  before: string | null;
  /**
   * 变更后的安全摘要。
   */
  after: string | null;
}

/**
 * 服务端绑定完整提案的影响核对结果。
 */
export interface Preview {
  /**
   * 本次动作编码。
   */
  action: string;
  /**
   * 服务端签名的核对凭据。
   */
  reviewToken: string;
  /**
   * 目标及实际变化。
   */
  changes: Change[];
  /**
   * 必要危险后果或恢复边界。
   */
  warnings: string[];
  /**
   * 按实际范围计算的受影响对象数量。
   */
  affected: { kind: string; count: number }[];
}

/**
 * 系统权限目录中的实际可配置功能。
 */
export interface Capability {
  /**
   * 功能用途，承接原型中每个操作的解释。
   */
  purpose: string;
  /**
   * 服务端定义的稳定功能代码。
   */
  code: string;
  /**
   * 面向管理员的名称。
   */
  name: string;
  /**
   * 是否必须走受保护配置流程。
   */
  protected: boolean;
  /**
   * 所属页面权，页面本身为空。
   */
  pagePermissionCode: string | null;
}

/**
 * 以页面组织功能选择，不把功能代码前缀当作归属规则。
 */
export interface CapabilityPage {
  /**
   * 页面权代码。
   */
  pageCode: string;
  /**
   * 业务页面名称。
   */
  name: string;
  /**
   * 页面与显式所属的动作。
   */
  operations: Capability[];
}

/**
 * 人员创建和修改共用的表单输入。
 */
export interface PersonForm {
  /**
   * 新人员的登录名。
   */
  username: string;
  /**
   * 人员姓名。
   */
  displayName: string;
  /**
   * 唯一主部门。
   */
  primaryDepartmentId: string;
  /**
   * 兼任部门列表。
   */
  secondaryDepartmentIds: string[];
  /**
   * 初始角色，仅新建时使用。
   */
  roleIds: string[];
  /**
   * 开发或管理员设定的初始密码。
   */
  initialPassword: string;
  /**
   * 联系手机号。
   */
  phone: string | null;
  /**
   * 联系邮箱。
   */
  email: string | null;
  /**
   * 管理备注。
   */
  managementNote: string | null;
}

/**
 * 部门创建、编辑共用完整业务资料。
 */
export interface DepartmentForm {
  /**
   * 部门名称。
   */
  name: string;
  /**
   * 唯一部门编号。
   */
  code: string;
  /**
   * 上级部门，空值代表根部门。
   */
  parentId: string | null;
  /**
   * 负责人，以人员 ID 提交。
   */
  managerUserId: string | null;
  /**
   * 部门说明。
   */
  note: string | null;
  /**
   * 同级排序。
   */
  sortOrder: number;
}

/**
 * 普通与受保护角色配置使用同一业务字段。
 */
export interface RoleForm {
  /**
   * 规范化防重前的显示名称。
   */
  name: string;
  /**
   * 可空角色说明。
   */
  description: string | null;
  /**
   * 完整最终功能集合。
   */
  permissionCodes: string[];
  /**
   * 受保护修改的操作原因。
   */
  reason?: string;
}
