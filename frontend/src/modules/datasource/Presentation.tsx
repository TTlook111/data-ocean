import { Status } from '../../shared/Records';

/**
 * 数据源页面使用的业务状态名称。
 */
const labels: Record<string, string> = {
  DRAFT: '草稿',
  ENABLED: '已启用',
  DISABLED: '已停用',
  RETIRED: '已退役',
  QUEUED: '排队中',
  RUNNING: '采集中',
  SUCCEEDED: '采集成功',
  FAILED: '采集失败',
  CANCELLED: '已取消',
  PASS: '通过',
  FAIL: '失败',
  UNVERIFIED: '未验证',
  NOT_APPLICABLE: '不适用',
  WAITING_FIRST_SUCCESS: '等待首次成功',
  READY: '待定时执行',
  WAITING_WINDOW: '等待采集时段',
  BLOCKED: '等待当前操作',
  PAUSED: '已暂停',
  INACTIVE: '未启用',
  PENDING_STOP: '等待停止',
  APPLIED: '已切换',
  COLLECTED: '已采集',
  IDENTITY_UNCERTAIN: '身份待核实',
  PENDING_REVIEW: '待核实',
  PENDING_GOVERNANCE: '待治理',
  COMPLETED: '已完成',
  CONFIRMED: '已确认',
  EXCLUDED: '已排除',
  PENDING: '待核实',
  INITIAL_BASELINE: '已建立初始目录',
  NO_CHANGE: '未发现结构变化',
  CHANGES_FOUND: '发现结构变化',
  NEW_SCOPE_BASELINE: '已建立新范围目录',
  MYSQL: 'MySQL',
  POSTGRESQL: 'PostgreSQL',
  TABLE: '普通表',
  VIEW: '视图',
  MATERIALIZED_VIEW: '物化视图',
  PARTITION: '分区表',
  SYSTEM: '系统对象',
  TEMPORARY: '临时表',
  FOREIGN: '外部表',
  MANUAL: '手动',
  RETRY: '重新采集',
  SCHEDULED: '定时',
  OBJECT_ADDED: '新增对象',
  OBJECT_REMOVED: '移除对象',
  OBJECT_RENAMED: '对象更名',
  OBJECT_REBUILT: '对象重建',
  ACCESS_LOST: '访问丢失',
  COLUMN_ADDED: '新增字段',
  COLUMN_REMOVED: '移除字段',
  ATTRIBUTE_CHANGED: '结构属性变化',
  COMMENT: '注释',
  EXISTENCE: '存在性',
  IDENTITY: '对象身份',
  TYPE: '字段类型',
  DEFAULT: '默认值',
  GENERATED: '生成策略',
  NULLABILITY: '可空',
  ORDINAL: '字段顺序',
  CONSTRAINT: '键、关系与索引',
  PARENT: '分区父表',
  CONNECTING: '正在连接源库',
  ENUMERATING: '正在读取对象清单',
  READING: '正在读取对象结构',
  COMPARING: '正在核对结构',
  VERIFYING_STOP: '正在核实停止',
};

/**
 * 将服务端状态或类别换成中文标签。
 */
export function datasourceLabel(value?: string | null) {
  return value ? (labels[value] ?? value) : '—';
}

/**
 * 复用后台状态控件，数据源语义使用自己的标签。
 */
export function DatasourceStatus({ value }: { value: string }) {
  return <Status value={value} label={datasourceLabel(value)} />;
}

/**
 * 生成沿用同一命名的过滤选择项。
 */
export function datasourceOptions(values: string[]) {
  return values.map((value) => ({ value, label: datasourceLabel(value) }));
}
