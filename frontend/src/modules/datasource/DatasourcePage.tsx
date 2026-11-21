import {
  Alert,
  App,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
} from 'antd';
import {
  DatabaseOutlined,
  DownOutlined,
  PlusOutlined,
  SearchOutlined,
  UpOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { displayTime, readErrorMessage, ReadState } from '../../shared/Records';
import { ActionMenuButton } from '../../shared/ActionMenuButton';
import { RecordTable } from '../../shared/RecordTable';
import { MoreFilters } from '../../shared/MoreFilters';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
import { useFormClose } from '../../shared/useFormClose';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page, Preview } from '../iam/types';
import { DatasourceStatus, datasourceLabel, datasourceOptions } from './Presentation';
import { CredentialDialog } from './CredentialDialog';
import type {
  ConnectionCheck,
  RuntimePolicy,
  SourceConfiguration,
  SourceDetail,
  SourceSummary,
} from './types';

/**
 * 新建与编辑复用同一组数据连接及运行参数。
 */
interface EditorValues {
  /**
   * 非秘密连接参数，可用于核对。
   */
  configuration: SourceConfiguration;
  /**
   * 元数据读取与定时设置。
   */
  runtimePolicy: RuntimePolicy;
  /**
   * 当前输入凭据，仅在表单会话内使用。
   */
  password?: string;
}

/**
 * 联系人查询返回的可选人员引用。
 */
interface ContactOption {
  /**
   * 选项人员标识。
   */
  id: string;
  /**
   * 登录名。
   */
  username: string;
  /**
   * 人员显示姓名。
   */
  displayName: string;
}

/**
 * 运行表单沿用已确认的部署默认值和界面顺序。
 */
const runtimeDefaults: RuntimePolicy = {
  maxConnections: 4,
  collectionConcurrency: 2,
  retryLimit: 2,
  taskTimeoutSeconds: 600,
  queryTimeoutSeconds: 30,
  rowLimit: 1000,
  syncHours: 24,
  windowStart: '02:00',
  windowEnd: '05:00',
};

/**
 * 运行设置使用与服务端相同的字段、单位和输入范围。
 */
const numericLimits = [
  ['maxConnections', '最大连接数', 1, 20],
  ['collectionConcurrency', '采集并发', 1, 8],
  ['retryLimit', '重试上限', 0, 5],
  ['taskTimeoutSeconds', '采集任务时限（秒）', 30, 3600],
  ['queryTimeoutSeconds', '查询时限（秒）', 1, 300],
  ['rowLimit', '结果行数上限', 1, 10000],
  ['syncHours', '采集周期（小时）', 1, 720],
] as const;

/**
 * 区分业务筛选与分页参数，清空筛选时保留每页条数。
 */
const sourceFilters = ['name', 'databaseType', 'technicalContactKeyword', 'lifecycle'];

/**
 * 数据源概况保留正式列表、条件与详情入口。
 */
export function DatasourcePage({ header }: { header?: React.ReactNode }) {
  const session = useSession();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [params, setParams] = useSearchParams();
  const [form] = Form.useForm();
  const [moreOpen, setMoreOpen] = useState(false);
  const [opened, setOpened] = useState<string | null>(params.get('detail'));
  const [editor, setEditor] = useState<SourceDetail | 'new' | null>(null);
  const [replace, setReplace] = useState<SourceDetail | null>(null);
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const page = Number(params.get('pageNum') ?? 1);
  const size = Number(params.get('pageSize') ?? 10);
  const list = useQuery({
    queryKey: ['datasources', params.toString()],
    queryFn: () =>
      api<Page<SourceSummary>>('/datasources?' + queryString(Object.fromEntries(params))),
  });
  const detail = useQuery({
    queryKey: ['datasource', opened],
    queryFn: () => api<SourceDetail>('/datasources/' + opened),
    enabled: Boolean(opened),
    refetchInterval: (query) => (query.state.data?.pendingCredentialChangeId ? 1500 : false),
  });

  /**
   * 启用成功后为有目录权限的用户打开空目录和首次采集入口。
   */
  function afterEnable(sourceId: string) {
    setEditor(null);
    setOpened(null);
    if (allowed(session, 'catalog.view')) {
      navigate('/catalog?source=' + sourceId);
    }
  }

  /**
   * 按实际来源版本预览暂停或恢复的影响。
   */
  async function schedule(source: SourceDetail, reason: string) {
    const id = source.source.id;
    const paused = source.schedule.paused;
    const action = paused ? 'RESUME_SCHEDULE' : 'PAUSE_SCHEDULE';
    const body = { action, expectedVersion: source.source.version, proposal: { reason } };
    try {
      // 核对与提交使用同一数据，取消核对仅返回详情。
      const preview = await api<Preview>('/datasources/' + id + '/impact-preview', {
        method: 'POST',
        body,
      });
      setReview({
        title: paused ? '恢复定时采集' : '暂停定时采集',
        preview,
        path: '/datasources/' + id + '/schedule/state',
        method: 'POST',
        operation: 'sources.pause',
        body,
        confirmText: paused ? '确认恢复' : '确认暂停',
        done: () => setReview(null),
      });
    } catch (error) {
      message.error((error as Error).message);
    }
  }

  /**
   * 提交服务器分页；条件变化回到第一页。
   */
  function search(values: Record<string, string | undefined>) {
    setMoreOpen(false);
    setParams(
      new URLSearchParams(
        queryString({
          ...values,
          databaseType: form.getFieldValue('databaseType'),
          technicalContactKeyword: form.getFieldValue('technicalContactKeyword'),
          pageSize: size,
        }),
      ),
    );
    form.focusField('name');
  }

  /**
   * 工具栏和无匹配状态共用恢复入口，将焦点交回名称搜索框。
   */
  function reset() {
    setMoreOpen(false);
    form.setFieldsValue(Object.fromEntries(sourceFilters.map((key) => [key, undefined])));
    setParams({ pageSize: String(size) });
    form.focusField('name');
  }

  return (
    <section className="record-page" aria-label="数据源管理">
      {/* 将查询项按对应列表列顺序排列。 */}
      <div
        className="panel query-panel ds-query-panel compact-query registry-query-panel"
        aria-label="数据源查询"
      >
        <Form
          name="datasource-query"
          className="registry-query"
          form={form}
          layout="inline"
          initialValues={Object.fromEntries(params)}
          onFinish={search}
        >
          <Form.Item name="name">
            <Input
              aria-label="数据源名称"
              placeholder="数据源名称"
              prefix={<SearchOutlined />}
              allowClear
            />
          </Form.Item>
          {/* 常用查询保持一行，低频字段仍可按原规则提交。 */}
          <Form.Item name="lifecycle" className="status-query">
            <Select
              aria-label="配置状态"
              placeholder="配置状态"
              allowClear
              options={datasourceOptions(['DRAFT', 'ENABLED', 'DISABLED', 'RETIRED'])}
            />
          </Form.Item>
          <div className="registry-query-actions">
            <Space>
              <MoreFilters
                open={moreOpen}
                setOpen={setMoreOpen}
                count={
                  Number(params.has('databaseType')) + Number(params.has('technicalContactKeyword'))
                }
              >
                <Form.Item name="databaseType">
                  <Select
                    aria-label="数据库类型"
                    placeholder="数据库类型"
                    allowClear
                    options={datasourceOptions(['MYSQL', 'POSTGRESQL'])}
                  />
                </Form.Item>
                <Form.Item name="technicalContactKeyword">
                  <Input aria-label="技术联系人" placeholder="技术联系人姓名或登录名" allowClear />
                </Form.Item>
              </MoreFilters>
              <Button htmlType="submit">查询</Button>
              <Button onClick={reset}>重置</Button>
            </Space>
            {/* 新增为整张列表的主操作，位于查询面板右侧。 */}
            {allowed(session, 'sources.create') && (
              <Button
                type="primary"
                icon={<PlusOutlined aria-hidden="true" />}
                onClick={() => setEditor('new')}
              >
                新增数据源
              </Button>
            )}
          </div>
        </Form>
      </div>
      {/* 表格及分页共用一个完整结果面板。 */}
      <section className="panel records-panel" aria-label="数据源列表">
        {header}
        <RecordTable<SourceSummary>
          rowKey="id"
          dataSource={list.data?.items}
          loading={list.isPending}
          error={list.error}
          retry={() => void list.refetch()}
          filtered={sourceFilters.some((key) => params.has(key))}
          onReset={reset}
          emptyDescription="尚未添加数据源"
          pagination={{
            current: list.data?.pageNum ?? page,
            pageSize: size,
            total: list.data?.total ?? 0,
            onChange: (next, pageSize) =>
              setParams({
                ...Object.fromEntries(params),
                pageNum: String(next),
                pageSize: String(pageSize),
              }),
          }}
          columns={[
            {
              title: '数据源',
              width: 210,
              render: (_, item) => (
                <div className="ds-row-identity">
                  <span className="ds-identity-icon" aria-hidden="true">
                    <DatabaseOutlined />
                  </span>
                  <Button
                    type="link"
                    className="cell-title"
                    title={item.name}
                    onClick={() => setOpened(item.id)}
                  >
                    {item.name}
                  </Button>
                </div>
              ),
            },
            {
              title: '数据库类型',
              width: 130,
              render: (_, item) => (
                <div>
                  {datasourceLabel(item.databaseType)}
                  {item.detectedVersion && (
                    <small className="table-secondary" title={item.detectedVersion}>
                      {item.detectedVersion}
                    </small>
                  )}
                </div>
              ),
            },
            {
              title: '技术联系人',
              dataIndex: 'technicalContact',
              width: 120,
              render: (value) => value ?? '—',
            },
            {
              title: '配置状态',
              width: 110,
              render: (_, item) => <DatasourceStatus value={item.lifecycle} />,
            },
            {
              title: '最近结构采集',
              width: 170,
              render: (_, item) => (
                <>
                  {item.latestTaskState ? (
                    <DatasourceStatus value={item.latestTaskState} />
                  ) : (
                    <span className="muted">尚无采集记录</span>
                  )}
                  {item.latestTaskEndedAt && (
                    <small className="table-secondary">{displayTime(item.latestTaskEndedAt)}</small>
                  )}
                </>
              ),
            },
            {
              title: '最近采集成功',
              width: 160,
              render: (_, item) => displayTime(item.lastSuccessfulAt),
            },
            {
              title: '操作',
              width: 126,
              fixed: 'right',
              className: 'record-actions-cell',
              render: (_, item) => (
                <Button type="link" onClick={() => setOpened(item.id)}>
                  查看详情
                </Button>
              ),
            },
          ]}
        />
      </section>
      {/* 连接与定时详情保持侧栏内，与原列表条件和页码独立。 */}
      <Drawer
        size="min(580px, calc(100vw - 80px))"
        className="record-details-drawer ds-source-drawer"
        closable={{ placement: 'end' }}
        title={
          <span className="cell-text" title={detail.data?.source.name}>
            {detail.data?.source.name ?? '数据源详情'}
          </span>
        }
        open={Boolean(opened)}
        onClose={() => setOpened(null)}
        destroyOnHidden
      >
        <ReadState
          error={detail.error}
          loading={detail.isPending}
          retry={() => void detail.refetch()}
        >
          {detail.data && (
            <SourceInformation
              detail={detail.data}
              edit={() => setEditor(detail.data ?? null)}
              replace={() => setReplace(detail.data ?? null)}
              schedule={schedule}
            />
          )}
        </ReadState>
      </Drawer>
      {editor && (
        <SourceEditor
          source={editor === 'new' ? undefined : editor}
          close={() => setEditor(null)}
          enabled={afterEnable}
          saved={() => {
            setEditor(null);
            void list.refetch();
            void detail.refetch();
          }}
        />
      )}
      {replace && (
        <CredentialDialog
          source={replace}
          close={() => {
            setReplace(null);
            void detail.refetch();
          }}
        />
      )}
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </section>
  );
}

/**
 * 受支持的三个数据源状态变更。
 */
type SourceLifecycleAction = 'DISABLE_SOURCE' | 'RESTORE_SOURCE' | 'RETIRE_SOURCE';

/**
 * 恢复需有实际当前连接证据，停用和退役通过同一服务端核对。
 */
function SourceLifecycleDialog({
  detail,
  action,
  close,
}: {
  detail: SourceDetail;
  action: SourceLifecycleAction;
  close: () => void;
}) {
  const [form] = Form.useForm<{ reason: string }>();
  const [test, setTest] = useState<ConnectionCheck>();
  const [review, setReview] = useState<ReviewSubmission>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const restore = action === 'RESTORE_SOURCE';
  const title = restore ? '恢复数据源' : action === 'DISABLE_SOURCE' ? '停用数据源' : '退役数据源';
  const path = '/datasources/' + detail.source.id;

  /**
   * 按保存的范围和凭据测试，确认步骤不读取秘密。
   */
  async function testConnection() {
    setBusy(true);
    setError(undefined);
    setTest(undefined);
    try {
      // 恢复复用正式分项测试和当前源修订。
      setTest(
        await api<ConnectionCheck>(path + '/connection-checks', {
          method: 'POST',
          body: {
            configuration: detail.configuration,
            runtimePolicy: detail.runtimePolicy,
            expectedVersion: detail.source.version,
          },
        }),
      );
    } catch (failure) {
      // 保留源状态及原因输入，只在连接通过后允许恢复。
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  /**
   * 获取系统记录的影响、停止状态和同一提案凭据。
   */
  async function preview({ reason }: { reason: string }) {
    setBusy(true);
    setError(undefined);
    const body = {
      action,
      expectedVersion: detail.source.version,
      proposal: { reason: reason.trim(), checkId: test?.checkId },
    };
    try {
      // 生命周期变更由公共影响核对组件确认。
      const result = await api<Preview>(path + '/impact-preview', { method: 'POST', body });
      setReview({
        title: '核对' + title,
        preview: result,
        path: path + '/lifecycle',
        method: 'POST',
        operation: restore
          ? 'sources.enable'
          : action === 'RETIRE_SOURCE'
            ? 'sources.retire'
            : 'sources.disable',
        body,
        danger: !restore,
        confirmText: '确认' + title,
        done: close,
      });
    } catch (failure) {
      // 核对失败继续保留原操作上下文。
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (review) {
    // 返回修改时保留已填写的原因与连接检查。
    return <ReviewDialog submission={review} close={() => setReview(undefined)} />;
  }
  return (
    <Modal
      open
      centered
      title={title}
      onCancel={close}
      onOk={form.submit}
      okText="核对影响"
      confirmLoading={busy}
      okButtonProps={{ disabled: restore && !test?.canEnable }}
      mask={{ closable: false }}
    >
      <p>{detail.source.name}</p>
      {error && <Alert type="error" showIcon title={error} />}
      {restore && (
        <Space orientation="vertical">
          <Button loading={busy} onClick={() => void testConnection()}>
            测试当前连接
          </Button>
          {test && (
            <Alert
              type={test.canEnable ? 'success' : 'error'}
              showIcon
              title={test.canEnable ? '当前连接和必要读取检查通过' : '当前连接不满足恢复条件'}
              description={test.items
                .filter((item) => item.status === 'FAILED')
                .map((item) => item.label)
                .join('、')}
            />
          )}
        </Space>
      )}
      <Form form={form} layout="vertical" onFinish={(values) => void preview(values)}>
        <Form.Item name="reason" label="操作原因" rules={[{ required: true, whitespace: true }]}>
          <Input.TextArea maxLength={500} rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/**
 * 详情展示当前连接、计划和当前可用的维护入口。
 */
function SourceInformation({
  detail,
  edit,
  replace,
  schedule,
}: {
  detail: SourceDetail;
  edit: () => void;
  replace: () => void;
  schedule: (source: SourceDetail, reason: string) => Promise<void>;
}) {
  const session = useSession();
  const navigate = useNavigate();
  const [showReason, setShowReason] = useState(false);
  const [reason, setReason] = useState('');
  const [lifecycle, setLifecycle] = useState<SourceLifecycleAction>();
  const { configuration, schedule: plan, source } = detail;
  const canOpenCatalog = allowed(session, 'catalog.view');
  const canEdit = allowed(session, 'sources.edit') && source.lifecycle !== 'RETIRED';
  // 只有当前启用源提供定时管理和凭据替换，按实际权限收进底栏。
  const maintenance = [
    ...(source.lifecycle === 'ENABLED' && allowed(session, 'sources.pause')
      ? [
          {
            key: 'schedule',
            label: plan.paused ? '恢复定时' : '暂停定时',
            onClick: () => setShowReason(true),
          },
        ]
      : []),
    ...(source.lifecycle === 'ENABLED' && canEdit
      ? [{ key: 'credential', label: '更换凭据', onClick: replace }]
      : []),
    ...(source.lifecycle === 'ENABLED' && allowed(session, 'sources.disable')
      ? [
          {
            key: 'disable',
            label: '停用数据源',
            danger: true,
            onClick: () => setLifecycle('DISABLE_SOURCE'),
          },
        ]
      : []),
    ...(source.lifecycle === 'DISABLED' &&
    allowed(session, 'sources.enable') &&
    allowed(session, 'sources.test')
      ? [{ key: 'restore', label: '恢复数据源', onClick: () => setLifecycle('RESTORE_SOURCE') }]
      : []),
    ...(source.lifecycle === 'DISABLED' && allowed(session, 'sources.retire')
      ? [
          {
            key: 'retire',
            label: '退役数据源',
            danger: true,
            onClick: () => setLifecycle('RETIRE_SOURCE'),
          },
        ]
      : []),
  ];
  return (
    <div className="ds-source-detail">
      {lifecycle && (
        <SourceLifecycleDialog
          detail={detail}
          action={lifecycle}
          close={() => setLifecycle(undefined)}
        />
      )}
      {/* 查看当前连接和调度事实，长内容单独滚动。 */}
      <div className="ds-source-detail-body">
        <Descriptions
          title="数据库连接"
          column={2}
          size="small"
          items={[
            {
              key: 'lifecycle',
              label: '配置状态',
              children: <DatasourceStatus value={source.lifecycle} />,
            },
            { key: 'version', label: '配置修订', children: detail.configurationRevision },
            {
              key: 'database',
              label: '数据库',
              children: configuration.databaseName ?? '—',
              span: 2,
            },
            {
              key: 'address',
              label: '连接地址',
              children: configuration.host ? configuration.host + ':' + configuration.port : '—',
              span: 2,
            },
            {
              key: 'account',
              label: '数据库只读账号',
              children: configuration.account ?? '—',
              span: 2,
            },
            ...(configuration.databaseType === 'POSTGRESQL'
              ? [
                  {
                    key: 'schema',
                    label: 'Schema 范围',
                    children: configuration.schemas?.join('、') || '—',
                    span: 2,
                  },
                ]
              : []),
            ...(configuration.purpose
              ? [{ key: 'purpose', label: '用途说明', children: configuration.purpose, span: 2 }]
              : []),
          ]}
        />
        <Descriptions
          className="ds-detail-block"
          title="定时采集"
          column={2}
          size="small"
          items={[
            { key: 'plan', label: '状态', children: <DatasourceStatus value={plan.state} /> },
            { key: 'next', label: '下次采集', children: displayTime(plan.nextScheduledAt) },
            { key: 'timezone', label: '时区', children: plan.timeZone },
            ...(plan.staleSince
              ? [{ key: 'stale', label: '陈旧开始时间', children: displayTime(plan.staleSince) }]
              : []),
          ]}
        />
      </div>
      {/* 页脚左侧是低频维护入口，右侧保留当前有权执行的常用动作。 */}
      {(canOpenCatalog || canEdit || maintenance.length > 0) && (
        <div className="detail-footer ds-source-detail-footer">
          <span>
            {maintenance.length === 1 && (
              <Button onClick={maintenance[0].onClick}>{maintenance[0].label}</Button>
            )}
            {maintenance.length > 1 && (
              <ActionMenuButton placement="topLeft" menu={{ items: maintenance }}>
                更多
              </ActionMenuButton>
            )}
          </span>
          <Space wrap>
            {canOpenCatalog && (
              <Button onClick={() => navigate('/catalog?source=' + source.id)}>
                查看元数据目录
              </Button>
            )}
            {canEdit && (
              <Button type="primary" onClick={edit}>
                编辑连接
              </Button>
            )}
          </Space>
        </div>
      )}
      {showReason && (
        <Modal
          centered
          open
          title={plan.paused ? '恢复定时采集' : '暂停定时采集'}
          okText="核对"
          okButtonProps={{ disabled: !plan.paused && !reason.trim() }}
          onCancel={() => setShowReason(false)}
          onOk={() => {
            setShowReason(false);
            void schedule(detail, reason);
          }}
        >
          <Input.TextArea
            aria-label="原因"
            placeholder={plan.paused ? '恢复说明' : '暂停原因'}
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </Modal>
      )}
    </div>
  );
}

/**
 * 新增、编辑、分项测试与核对均以已确认连接表单为基准。
 */
function SourceEditor({
  source,
  close,
  enabled,
  saved,
}: {
  source?: SourceDetail;
  close: () => void;
  enabled: (id: string) => void;
  saved: () => void;
}) {
  useReturnFocus();
  const session = useSession();
  const [form] = Form.useForm<EditorValues>();
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const [check, setCheck] = useState<ConnectionCheck | null>(null);
  const [testing, setTesting] = useState(false);
  const [preparing, setPreparing] = useState<'draft' | 'save' | 'enable' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showLimits, setShowLimits] = useState(false);
  const [contactKeyword, setContactKeyword] = useState('');
  const testResult = useRef<HTMLElement>(null);
  const busy = testing || Boolean(preparing);
  const requestClose = useFormClose(form, close, busy);
  const selectedType = Form.useWatch(['configuration', 'databaseType'], form) ?? 'MYSQL';
  const contacts = useQuery({
    queryKey: ['source-contact-options', contactKeyword],
    queryFn: () =>
      api<Page<ContactOption>>(
        '/datasources/contact-options?' + queryString({ keyword: contactKeyword, pageSize: 60 }),
      ),
  });
  const immutable = source?.source.lifecycle === 'ENABLED';
  const canTest = allowed(session, 'sources.test');
  const canEnable = allowed(session, 'sources.enable');
  const enableReady = canEnable && !immutable && Boolean(check?.canEnable);
  const id = source?.source.id;
  const path = '/datasources' + (id ? '/' + id : '');
  const options =
    contacts.data?.items.map((person) => ({
      value: person.id,
      label: person.displayName + '（' + person.username + '）',
    })) ?? [];

  useEffect(() => {
    // 测试完成后将分项结果带入弹窗可见区域，保留底部提交操作。
    if (check) testResult.current?.scrollIntoView({ block: 'start' });
  }, [check]);

  /**
   * 从表单取得明确的请求数据，保留数据库密码原样。
   */
  function values() {
    const entered = form.getFieldsValue(true);
    return {
      ...entered,
      configuration: { ...entered.configuration, schemas: entered.configuration.schemas ?? [] },
    };
  }

  /**
   * 分项连接测试只提交实际数据库配置，结果回显位置保留在表单。
   */
  async function test() {
    setError(null);
    setCheck(null);
    const input = values();
    const required: {
      name: ['configuration', keyof SourceConfiguration] | ['password'];
      label: string;
    }[] = [
      { name: ['configuration', 'host'], label: '连接地址' },
      { name: ['configuration', 'port'], label: '端口' },
      { name: ['configuration', 'databaseName'], label: '数据库' },
      { name: ['configuration', 'account'], label: '数据库只读账号' },
    ];
    if (!source?.credentialConfigured) {
      // 已保存的凭据允许沿用，新增连接则必须先输入密码。
      required.push({ name: ['password'], label: '数据库密码' });
    }
    const missing = required.filter(({ name }) => {
      // 数据库密码原样校验，普通连接字段忽略首尾空白。
      const value = form.getFieldValue(name);
      return name[0] === 'password' ? !value : !String(value ?? '').trim();
    });
    if (missing.length) {
      // 只在测试时要求完整连接，保存草稿仍允许配置不完整。
      form.setFields(missing.map(({ name, label }) => ({ name, errors: ['请输入' + label] })));
      form.scrollToField(missing[0].name, { focus: true, block: 'nearest' });
      return;
    }
    setTesting(true);
    try {
      const next = await api<ConnectionCheck>(path + '/connection-checks', {
        method: 'POST',
        command: crypto.randomUUID(),
        body: { ...input, expectedVersion: source?.source.version },
      });
      setCheck(next);
      if (
        input.configuration.databaseType === 'POSTGRESQL' &&
        !input.configuration.schemas.length &&
        next.canEnable
      ) {
        // 提供发现的 Schema 给管理员核定，默认沿用返回范围。
        form.setFieldValue(['configuration', 'schemas'], next.availableSchemas);
      }
    } catch (error) {
      // 保留输入，让失败反馈随连接表单回看。
      setError(readErrorMessage(error as Error));
    } finally {
      setTesting(false);
    }
  }

  /**
   * 已核对内容与有权提交的动作保持一致。
   */
  async function prepare(mode: 'draft' | 'save' | 'enable') {
    setError(null);
    try {
      // 草稿只要求名称，连接完整性由分项测试与服务端核对。
      await form.validateFields([['configuration', 'name']]);
    } catch {
      // 校验失败时回到字段，避免在底栏提示含义重复的全局错误。
      form.scrollToField(['configuration', 'name'], { focus: true, block: 'nearest' });
      return;
    }
    setPreparing(mode);
    try {
      const input = values();
      const { password, configuration, runtimePolicy } = input;
      const proposal = {
        configuration,
        runtimePolicy,
        credentialProvided: Boolean(password),
        checkId: check?.canEnable ? check.checkId : undefined,
      };
      const version = source?.source.version;
      if (!id) {
        // 新源按已核对的生命周期保存，启用后跳到该源目录。
        const next = { ...proposal, targetLifecycle: mode === 'enable' ? 'ENABLED' : 'DRAFT' };
        const preview = await api<Preview>('/datasources/impact-preview', {
          method: 'POST',
          body: { proposal: next },
        });
        setReview({
          title: mode === 'enable' ? '核对连接启用' : '核对连接草稿',
          preview: {
            ...preview,
            // 仅转换核对页的生命周期文本，签名及提交参数沿用服务端原值。
            changes: preview.changes.map((change) =>
              change.field === 'lifecycle'
                ? { ...change, after: datasourceLabel(change.after) }
                : change,
            ),
          },
          path: '/datasources',
          method: 'POST',
          operation: 'sources.create',
          body: { proposal: next, password },
          confirmText: mode === 'enable' ? '确认启用' : '确认保存草稿',
          done: (target) => {
            if (mode === 'enable') enabled(target);
            else saved();
          },
        });
      } else if (mode === 'enable') {
        // 草稿启用使用已保存配置，编辑阶段的字段先保存再启用。
        const body = { expectedVersion: version, checkId: check?.checkId };
        const preview = await api<Preview>(path + '/impact-preview', {
          method: 'POST',
          body: { action: 'ENABLE', expectedVersion: version, proposal: { checkId: body.checkId } },
        });
        setReview({
          title: '核对连接启用',
          preview,
          path: path + '/enable',
          method: 'POST',
          operation: 'sources.enable',
          body,
          confirmText: '确认启用',
          done: () => enabled(id),
        });
      } else {
        // 实际保存新版连接，原密码只有明确输入时才变更。
        const preview = await api<Preview>(path + '/impact-preview', {
          method: 'POST',
          body: { action: 'UPDATE_CONFIG', expectedVersion: version, proposal },
        });
        setReview({
          title: '核对连接配置',
          preview,
          path: path + '/configuration',
          method: 'PUT',
          operation: 'sources.edit',
          body: { ...proposal, password, expectedVersion: version },
          done: saved,
        });
      }
    } catch (error) {
      // 核对失败保留原表单和连接测试结果。
      setError(readErrorMessage(error as Error));
    } finally {
      setPreparing(null);
    }
  }

  return (
    <>
      <Modal
        centered
        open={!review}
        title={source ? '编辑连接' : '新增数据源'}
        width={848}
        className="ds-editor"
        onCancel={requestClose}
        closable={!busy}
        keyboard={!busy}
        footer={
          <>
            {/* 连接和核对异常留在操作前，以便在表单滚动时仍能阅读。 */}
            {error && <Alert className="ds-editor-error" type="error" title={error} showIcon />}
            <div className="ds-editor-actions">
              <Button onClick={requestClose} disabled={busy}>
                取消
              </Button>
              <Space wrap>
                <Button
                  type={immutable || !canTest ? 'primary' : 'default'}
                  loading={preparing === 'draft' || preparing === 'save'}
                  disabled={testing || preparing === 'enable'}
                  onClick={() => void prepare(id ? 'save' : 'draft')}
                >
                  {id ? '核对并保存' : '保存草稿'}
                </Button>
                {canTest && (
                  <Button
                    type={immutable || enableReady ? 'default' : 'primary'}
                    loading={testing}
                    disabled={Boolean(preparing)}
                    onClick={() => void test()}
                  >
                    {check ? '重新测试' : '测试连接'}
                  </Button>
                )}
                {canEnable && !immutable && (
                  <Button
                    type="primary"
                    loading={preparing === 'enable'}
                    disabled={
                      !enableReady || testing || preparing === 'draft' || preparing === 'save'
                    }
                    onClick={() => void prepare('enable')}
                  >
                    核对并启用
                  </Button>
                )}
              </Space>
            </div>
          </>
        }
      >
        <Form
          form={form}
          name="datasource-editor"
          layout="vertical"
          disabled={busy}
          className="ds-connection-form"
          initialValues={{
            configuration: source?.configuration ?? {
              databaseType: 'MYSQL',
              port: 3306,
              schemas: [],
            },
            runtimePolicy: source?.runtimePolicy ?? runtimeDefaults,
          }}
          onValuesChange={(patch) => {
            // 操作反馈随着用户继续修正表单清除，连接依据变化使旧结论失效。
            setError(null);
            if (
              patch.password !== undefined ||
              patch.runtimePolicy ||
              Object.keys(patch.configuration ?? {}).some(
                (key) =>
                  ![
                    'name',
                    'purpose',
                    'schemas',
                    'technicalContactId',
                    'businessContactId',
                  ].includes(key),
              )
            )
              setCheck(null);
          }}
        >
          {/* 基本信息和实际数据库参数分组，避免配置和运行结果混在一起。 */}
          <section className="ds-editor-section" aria-labelledby="ds-basic-title">
            <h3 id="ds-basic-title">基本信息</h3>
            <Form.Item
              name={['configuration', 'name']}
              label="数据源名称"
              rules={[{ required: true, whitespace: true, message: '请输入数据源名称' }]}
            >
              <Input maxLength={80} />
            </Form.Item>
            <div className="ds-form-grid">
              <Form.Item name={['configuration', 'technicalContactId']} label="技术联系人">
                <Select
                  allowClear
                  showSearch
                  filterOption={false}
                  onSearch={setContactKeyword}
                  options={options}
                />
              </Form.Item>
              <Form.Item name={['configuration', 'businessContactId']} label="业务联系人">
                <Select
                  allowClear
                  showSearch
                  filterOption={false}
                  onSearch={setContactKeyword}
                  options={options}
                />
              </Form.Item>
            </div>
            <Form.Item name={['configuration', 'purpose']} label="用途说明">
              <Input.TextArea autoSize={{ minRows: 1, maxRows: 3 }} maxLength={1000} />
            </Form.Item>
          </section>
          {/* 地址、账号和范围与基本信息分组，已识别版本紧邻数据库类型。 */}
          <section className="ds-editor-section" aria-labelledby="ds-connection-title">
            <h3 id="ds-connection-title">数据库连接</h3>
            <div className="ds-form-grid">
              <Form.Item name={['configuration', 'databaseType']} label="数据库类型">
                <Select
                  disabled={immutable}
                  options={datasourceOptions(['MYSQL', 'POSTGRESQL'])}
                  onChange={(type) => {
                    form.setFieldValue(['configuration', 'port'], type === 'MYSQL' ? 3306 : 5432);
                    form.setFieldValue(['configuration', 'schemas'], []);
                  }}
                />
              </Form.Item>
              <div className="ds-version">
                <span className="ds-version-label">数据库版本</span>
                <span className="ds-version-value">
                  {check?.detectedVersion ?? source?.source.detectedVersion ?? '测试后识别'}
                </span>
              </div>
              <Form.Item name={['configuration', 'host']} label="连接地址">
                <Input disabled={immutable} maxLength={253} />
              </Form.Item>
              <Form.Item name={['configuration', 'port']} label="端口">
                <InputNumber disabled={immutable} min={1} max={65535} precision={0} />
              </Form.Item>
              <Form.Item name={['configuration', 'databaseName']} label="数据库">
                <Input disabled={immutable} maxLength={160} />
              </Form.Item>
              <Form.Item name={['configuration', 'account']} label="数据库只读账号">
                <Input disabled={immutable} maxLength={160} />
              </Form.Item>
            </div>
            {selectedType === 'POSTGRESQL' && (
              <Form.Item name={['configuration', 'schemas']} label="Schema 范围">
                <Select
                  mode="multiple"
                  disabled={!check?.availableSchemas.length}
                  placeholder="测试连接后选择"
                  options={(check?.availableSchemas ?? source?.configuration.schemas ?? []).map(
                    (value) => ({ value, label: value }),
                  )}
                />
              </Form.Item>
            )}
            {!immutable && (
              <Form.Item name="password" label="数据库密码">
                <Input.Password
                  autoComplete="new-password"
                  maxLength={1024}
                  placeholder={source?.credentialConfigured ? '留空沿用已设置密码' : undefined}
                />
              </Form.Item>
            )}
          </section>
          {/* 运行设置收起时保留字段值，仅在用户需要时展开。 */}
          <section className="ds-editor-section ds-advanced-section">
            <Button
              type="text"
              className="ds-advanced-toggle"
              icon={showLimits ? <UpOutlined /> : <DownOutlined />}
              onClick={() => setShowLimits(!showLimits)}
              aria-expanded={showLimits}
              aria-controls="ds-runtime-fields"
            >
              运行与定时采集
            </Button>
            {/* 高级参数折叠保留已填写配置及真实后端限额。 */}
            <div id="ds-runtime-fields" className="ds-form-grid" hidden={!showLimits}>
              {numericLimits.map(([key, label, min, max]) => (
                <Form.Item key={key} name={['runtimePolicy', key]} label={label}>
                  <InputNumber min={min} max={max} precision={0} />
                </Form.Item>
              ))}
              <Form.Item name={['runtimePolicy', 'windowStart']} label="采集时段开始">
                <Input type="time" />
              </Form.Item>
              <Form.Item name={['runtimePolicy', 'windowEnd']} label="采集时段结束">
                <Input type="time" />
              </Form.Item>
            </div>
          </section>
          {check && (
            <section
              className="ds-editor-section"
              ref={testResult}
              aria-label="分项连接测试"
              aria-live="polite"
            >
              <h3>分项连接测试</h3>
              {check.items.map((item) => (
                <div className="ds-check-row" key={item.code}>
                  <span>{item.code === 'BUSINESS_READ' ? '只读安全' : item.label}</span>
                  <DatasourceStatus value={item.status} />
                  <span>
                    {item.code === 'BUSINESS_READ' && item.status === 'PASS'
                      ? '已核对专用只读权限'
                      : item.message}
                  </span>
                </div>
              ))}
              {/* 业务行的核验结论由接口独立提供，不借用只读安全的通过状态。 */}
              <div className="ds-check-row">
                <span>业务行读取</span>
                <DatasourceStatus value={check.businessReadStatus} />
              </div>
            </section>
          )}
        </Form>
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}
