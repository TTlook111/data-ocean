import {
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
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { DetailFooter, displayTime, ReadState } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
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
 * 数据源概况保留正式列表、条件与详情入口。
 */
export function DatasourcePage() {
  const session = useSession();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [params, setParams] = useSearchParams();
  const [form] = Form.useForm();
  const [opened, setOpened] = useState<string | null>(null);
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
    setParams(new URLSearchParams(queryString({ ...values, pageSize: size }).replace(/^\?/, '')));
  }

  return (
    <section className="record-page" aria-label="数据源管理">
      {/* 将查询项按对应列表列顺序排列。 */}
      <Form
        form={form}
        className="record-filters"
        layout="inline"
        initialValues={Object.fromEntries(params)}
        onFinish={search}
      >
        <Form.Item name="name">
          <Input aria-label="数据源名称" placeholder="数据源名称" allowClear />
        </Form.Item>
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
        <Form.Item name="lifecycle">
          <Select
            aria-label="配置状态"
            placeholder="配置状态"
            allowClear
            options={datasourceOptions(['DRAFT', 'ENABLED', 'DISABLED', 'RETIRED'])}
          />
        </Form.Item>
        <Space className="record-query-actions">
          <Button htmlType="submit">查询</Button>
          <Button
            onClick={() => {
              form.resetFields();
              form.setFieldsValue({
                name: undefined,
                databaseType: undefined,
                technicalContactKeyword: undefined,
                lifecycle: undefined,
              });
              setParams({});
            }}
          >
            重置
          </Button>
        </Space>
        {allowed(session, 'sources.create') && (
          <Button type="primary" onClick={() => setEditor('new')}>
            新增数据源
          </Button>
        )}
      </Form>
      <RecordTable<SourceSummary>
        rowKey="id"
        dataSource={list.data?.items}
        loading={list.isPending}
        error={list.error}
        retry={() => void list.refetch()}
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
            width: 190,
            render: (_, item) => (
              <Button type="link" onClick={() => setOpened(item.id)}>
                {item.name}
              </Button>
            ),
          },
          {
            title: '数据库类型',
            width: 130,
            render: (_, item) => (
              <div>
                {datasourceLabel(item.databaseType)}
                <small className="ds-secondary">{item.detectedVersion}</small>
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
                {datasourceLabel(item.latestTaskState)}
                <small className="ds-secondary">{displayTime(item.latestTaskEndedAt)}</small>
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
            render: (_, item) => (
              <Button type="link" onClick={() => setOpened(item.id)}>
                查看详情
              </Button>
            ),
          },
        ]}
      />
      {/* 连接与定时详情保持侧栏内，与原列表条件和页码独立。 */}
      <Drawer
        width={580}
        title={detail.data?.source.name ?? '数据源详情'}
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
              onClose={() => setOpened(null)}
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
 * 详情展示当前连接、计划和当前可用的维护入口。
 */
function SourceInformation({
  detail,
  onClose,
  edit,
  replace,
  schedule,
}: {
  detail: SourceDetail;
  onClose: () => void;
  edit: () => void;
  replace: () => void;
  schedule: (source: SourceDetail, reason: string) => Promise<void>;
}) {
  const session = useSession();
  const navigate = useNavigate();
  const [showReason, setShowReason] = useState(false);
  const [reason, setReason] = useState('');
  const { configuration, schedule: plan, source } = detail;
  return (
    <>
      <Descriptions
        column={2}
        size="small"
        items={[
          {
            key: 'lifecycle',
            label: '配置状态',
            children: <DatasourceStatus value={source.lifecycle} />,
          },
          { key: 'database', label: '数据库', children: configuration.databaseName ?? '—' },
          {
            key: 'address',
            label: '连接地址',
            children: configuration.host ? configuration.host + ':' + configuration.port : '—',
          },
          { key: 'account', label: '数据库只读账号', children: configuration.account ?? '—' },
          {
            key: 'schema',
            label: 'Schema 范围',
            children: configuration.schemas?.join('、') || '—',
          },
          { key: 'version', label: '配置修订', children: detail.configurationRevision },
          { key: 'plan', label: '定时采集', children: <DatasourceStatus value={plan.state} /> },
          { key: 'next', label: '下次采集', children: displayTime(plan.nextScheduledAt) },
          { key: 'timezone', label: '时区', children: plan.timeZone },
          { key: 'stale', label: '陈旧开始时间', children: displayTime(plan.staleSince) },
          { key: 'purpose', label: '用途说明', children: configuration.purpose ?? '—', span: 2 },
        ]}
      />
      {/* 来源动作以实际当前状态和权限决定，始终能回到原列表。 */}
      <DetailFooter close={onClose}>
        {allowed(session, 'catalog.view') && (
          <Button onClick={() => navigate('/catalog?source=' + source.id)}>查看元数据目录</Button>
        )}
        {allowed(session, 'sources.pause') && source.lifecycle === 'ENABLED' && (
          <Button onClick={() => setShowReason(true)}>
            {plan.paused ? '恢复定时' : '暂停定时'}
          </Button>
        )}
        {allowed(session, 'sources.edit') && source.lifecycle === 'ENABLED' && (
          <Button onClick={replace}>更换凭据</Button>
        )}
        {allowed(session, 'sources.edit') && source.lifecycle !== 'RETIRED' && (
          <Button type="primary" onClick={edit}>
            编辑连接
          </Button>
        )}
      </DetailFooter>
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
    </>
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
  const session = useSession();
  const { message } = App.useApp();
  const [form] = Form.useForm<EditorValues>();
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const [check, setCheck] = useState<ConnectionCheck | null>(null);
  const [testing, setTesting] = useState(false);
  const [showLimits, setShowLimits] = useState(false);
  const [contactKeyword, setContactKeyword] = useState('');
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
  const id = source?.source.id;
  const path = '/datasources' + (id ? '/' + id : '');
  const options =
    contacts.data?.items.map((person) => ({
      value: person.id,
      label: person.displayName + '（' + person.username + '）',
    })) ?? [];

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
    setTesting(true);
    try {
      const input = values();
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
      message.error((error as Error).message);
    } finally {
      setTesting(false);
    }
  }

  /**
   * 已核对内容与有权提交的动作保持一致。
   */
  async function prepare(mode: 'draft' | 'save' | 'enable') {
    try {
      await form.validateFields([['configuration', 'name']]);
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
          preview,
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
      if (error instanceof Error) message.error(error.message);
    }
  }

  return (
    <>
      <Modal
        centered
        open={!review}
        title={source ? '编辑连接' : '新增数据源'}
        width={760}
        onCancel={close}
        footer={
          <Space wrap>
            <Button onClick={close}>取消</Button>
            <Button onClick={() => void prepare(id ? 'save' : 'draft')}>
              {id ? '核对并保存' : '保存草稿'}
            </Button>
            {canTest && (
              <Button loading={testing} onClick={() => void test()}>
                {check ? '重新测试' : '测试连接'}
              </Button>
            )}
            {canEnable && !immutable && (
              <Button
                type="primary"
                disabled={!check?.canEnable}
                onClick={() => void prepare('enable')}
              >
                核对并启用
              </Button>
            )}
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
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
          <h3>基本信息</h3>
          <Form.Item
            name={['configuration', 'name']}
            label="数据源名称"
            rules={[{ required: true, message: '请输入数据源名称' }]}
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
            <Input.TextArea rows={2} maxLength={1000} />
          </Form.Item>
          <h3>数据库连接</h3>
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
            <Form.Item label="数据库版本">
              <span>
                {check?.detectedVersion ?? source?.source.detectedVersion ?? '测试后识别'}
              </span>
            </Form.Item>
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
          <Button type="link" onClick={() => setShowLimits(!showLimits)} aria-expanded={showLimits}>
            运行与定时采集
          </Button>
          {/* 高级参数折叠保留已填写配置及真实后端限额。 */}
          <div className="ds-form-grid" hidden={!showLimits}>
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
          {check && (
            <section aria-label="分项连接测试">
              <h3>分项连接测试</h3>
              {check.items.map((item) => (
                <div className="ds-check-row" key={item.code}>
                  <span>{item.label}</span>
                  <DatasourceStatus value={item.status} />
                  <span>{item.message}</span>
                </div>
              ))}
            </section>
          )}
        </Form>
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}
