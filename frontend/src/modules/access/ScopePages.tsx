import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tabs,
  Typography,
} from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { allowed, useSession } from '../../shared/session';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import type { Preview } from '../iam/types';
import {
  accessPath,
  accessList,
  AccessDetailDrawer,
  AccessEditor,
  AccessGrid,
  AccessPerson,
  AccessSelect,
  AccessStatus,
  AccessToolbar,
  personLabel,
  previewAccess,
  saveAccess,
  statusOptions,
} from './AccessControls';
import { RequestDrawer } from './RequestsPage';
import type {
  AccessGrant,
  AccessScope,
  DepartmentAccess,
  DepartmentGrant,
  GrantDirectoryEntry,
} from './types';

/**
 * 申请授权和部门来源按原型放在同一张授权表，筛选和总数使用统一接口。
 */
export function GrantsPage() {
  const session = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [selected, setSelected] = useState<GrantDirectoryEntry>();
  const [revoking, setRevoking] = useState<AccessGrant>();
  const [requestId, setRequestId] = useState<string>();
  const fromRequest = params.get('requestId');
  const fromGrant = params.get('grantId');
  const auditReturn = params.get('auditReturn');
  const canRevoke = allowed(session, 'grants.revoke');
  return (
    <>
      <AccessGrid<GrantDirectoryEntry>
        path="/grant-directory"
        rowKey="id"
        empty="暂无授权记录"
        action={
          auditReturn && allowed(session, 'audit.view') ? (
            <Button
              onClick={() => {
                // 目标固定为审计页，查询串恢复原条件和打开的记录。
                void navigate({ pathname: '/iam/audit', search: auditReturn }, { replace: true });
              }}
            >
              返回审计记录
            </Button>
          ) : undefined
        }
        filters={[
          { name: 'applicant', placeholder: '被授权人', ariaLabel: '被授权人姓名或登录名' },
          { name: 'sourceId', placeholder: '数据源', kind: 'source' },
          {
            name: fromGrant ? 'grantId' : fromRequest ? 'requestId' : 'keyword',
            placeholder: fromGrant ? '授权来源编号' : '来源名称或编号',
            inMore: true,
          },
          { name: 'handler', placeholder: '批准人姓名或登录名', inMore: true },
          {
            name: 'state',
            placeholder: '全部状态',
            options: statusOptions(['EFFECTIVE', 'SUSPENDED', 'ENDED']),
          },
          { name: 'dateFrom', placeholder: '生效日期', date: true, inMore: true },
          { name: 'dateTo', placeholder: '生效日期', date: true, inMore: true },
        ]}
        columns={[
          {
            title: '被授权人',
            width: 128,
            render: (_, entry) => (
              <AccessPerson person={(entry.request ?? entry.department).grantee} />
            ),
          },
          {
            title: '数据源与规则',
            width: 172,
            render: (_, entry) => {
              // 历史行明确展示当前来源的名称及批准时的规则名称。
              const item = entry.request ?? entry.department;
              const names = item.rules.map((rule) => rule.name).join('、');
              return (
                <div className="access-cell-summary">
                  <Button
                    type="link"
                    className="record-link"
                    title={item.source.name}
                    onClick={() => setSelected(entry)}
                  >
                    {item.source.name}
                  </Button>
                  <small title={names}>{names || '—'}</small>
                </div>
              );
            },
          },
          {
            title: '授权来源',
            width: 128,
            render: (_, entry) => (
              <div className="access-cell-summary">
                <span>{entry.request ? '申请授权' : '部门初始访问'}</span>
                <small title={entry.department?.departmentName}>
                  {entry.request?.requestId ?? entry.department?.departmentName}
                </small>
              </div>
            ),
          },
          {
            title: '批准人',
            width: 102,
            render: (_, entry) => <AccessPerson person={entry.request?.approver} />,
          },
          {
            title: '状态',
            width: 96,
            render: (_, entry) => (
              <AccessStatus value={(entry.request ?? entry.department).state} />
            ),
          },
          {
            title: '生效时间',
            width: 151,
            render: (_, entry) => displayTime((entry.request ?? entry.department).startedAt),
          },
          {
            title: '操作',
            className: 'record-actions-cell',
            width: 140,
            fixed: 'right',
            render: (_, entry) => (
              <Space size={8} className="row-actions">
                <Button type="link" onClick={() => setSelected(entry)}>
                  详情
                </Button>
                {canRevoke && entry.request && entry.request.state !== 'ENDED' && (
                  <Button type="link" danger onClick={() => setRevoking(entry.request!)}>
                    撤销
                  </Button>
                )}
              </Space>
            ),
          },
        ]}
      />
      {selected && !requestId && !revoking && (
        <GrantDirectoryDrawer
          entry={selected}
          close={() => setSelected(undefined)}
          canRevoke={canRevoke}
          openRequest={setRequestId}
          revoke={setRevoking}
        />
      )}
      {requestId && <RequestDrawer readOnly id={requestId} close={() => setRequestId(undefined)} />}
      {revoking && (
        <RevokeEditor
          row={revoking}
          close={() => setRevoking(undefined)}
          onSaved={() => {
            setSelected(undefined);
            setRevoking(undefined);
          }}
        />
      )}
    </>
  );
}

/**
 * 详情从当前来源进入，联合授权展示整组实际批准记录。
 */
function GrantDirectoryDrawer({
  entry,
  close,
  canRevoke,
  openRequest,
  revoke,
}: {
  entry: GrantDirectoryEntry;
  close: () => void;
  canRevoke: boolean;
  openRequest: (id: string) => void;
  revoke: (row: AccessGrant) => void;
}) {
  const session = useSession();
  const item = entry.request ?? entry.department;
  const request = entry.request;
  const department = entry.department;
  const joint = useQuery({
    queryKey: ['access', 'grant-joint-details', request?.requestId],
    queryFn: () =>
      accessList<AccessGrant>('/grants', { requestId: request!.requestId, pageSize: '100' }),
    enabled: Boolean(request?.joint),
  });
  const scope = request?.joint ? joint.data?.items : [item];
  return (
    <AccessDetailDrawer
      size={650}
      title="授权详情"
      close={close}
      actions={
        <>
          {request && <Button onClick={() => openRequest(request.requestId)}>查看申请</Button>}
          {department && allowed(session, 'departments.view') && (
            <Button href={`/iam/departments?department=${department.departmentId}&tab=template`}>
              查看部门配置
            </Button>
          )}
          {canRevoke && request && request.state !== 'ENDED' && (
            <Button type="primary" danger onClick={() => revoke(request)}>
              撤销授权
            </Button>
          )}
        </>
      }
    >
      <div className="access-detail-heading">
        <h2>{request?.joint ? '联合授权' : item.source.name}</h2>
        <AccessStatus value={item.state} />
      </div>
      <Descriptions
        column={2}
        items={[
          {
            key: 'person',
            label: request ? '申请人（被授权人）' : '被授权人',
            children: personLabel(item.grantee),
          },
          { key: 'type', label: '来源类型', children: request ? '申请授权' : '部门初始访问' },
          { key: 'start', label: '生效时间', children: displayTime(item.startedAt) },
          {
            key: 'source',
            label: request ? '申请编号' : '来源部门',
            children: request?.requestId ?? department?.departmentName,
          },
          ...(request?.joint ? [{ key: 'joint', label: '办理方式', children: '联合办理' }] : []),
        ]}
      />
      <h3 className="access-detail-section">数据源与输出限制规则</h3>
      <ReadState
        loading={Boolean(request?.joint) && joint.isPending}
        error={joint.error}
        retry={() => void joint.refetch()}
      >
        <Table
          size="small"
          rowKey={(row) => row.source.id}
          dataSource={scope ?? []}
          pagination={false}
          columns={[
            { title: '数据源', width: 180, render: (_, row) => row.source.name },
            {
              title: '输出限制规则',
              render: (_, row) => row.rules.map((rule) => rule.name).join('、'),
            },
          ]}
        />
      </ReadState>
      {request && (
        <>
          <h3 className="access-detail-section">批准记录</h3>
          <Descriptions
            column={2}
            items={(request.joint ? (joint.data?.items ?? []) : [request]).flatMap((row) => [
              {
                key: `${row.id}:approver`,
                label: request.joint ? `${row.source.name}批准人` : '批准人',
                children: personLabel(row.approver),
              },
              { key: `${row.id}:time`, label: '批准时间', children: displayTime(row.approvedAt) },
            ])}
          />
        </>
      )}
      {item.endedAt && (
        <>
          <h3 className="access-detail-section">{request ? '撤销记录' : '来源结束'}</h3>
          <Descriptions
            column={1}
            items={[
              { key: 'time', label: '结束时间', children: displayTime(item.endedAt) },
              ...(request?.endReason
                ? [{ key: 'reason', label: '处理原因', children: request.endReason }]
                : []),
            ]}
          />
        </>
      )}
    </AccessDetailDrawer>
  );
}

/**
 * 独立或联合审批生成的来源，管理和本人页面均可回看。
 */
function PersonalGrants({ mine = false }: { mine?: boolean }) {
  const session = useSession();
  const [selected, setSelected] = useState<AccessGrant>();
  const [revoking, setRevoking] = useState<AccessGrant>();
  const [requestId, setRequestId] = useState<string>();
  return (
    <>
      <AccessGrid<AccessGrant>
        path={mine ? '/my-grants' : '/grants'}
        rowKey="id"
        empty="暂无个人授权来源"
        filters={
          mine
            ? []
            : [
                { name: 'sourceId', placeholder: '数据源', kind: 'source' },
                { name: 'applicant', placeholder: '被授权人' },
                { name: 'handler', placeholder: '批准人' },
                { name: 'requestId', placeholder: '申请编号' },
                { name: 'dateFrom', placeholder: '生效日期起', date: true },
                { name: 'dateTo', placeholder: '生效日期止', date: true },
                {
                  name: 'state',
                  placeholder: '来源状态',
                  options: statusOptions(['EFFECTIVE', 'SUSPENDED', 'ENDED']),
                },
              ]
        }
        columns={[
          {
            title: '被授权人',
            width: 170,
            render: (_, row) => (
              <Button
                type="link"
                className="record-link"
                title={personLabel(row.grantee)}
                onClick={() => setSelected(row)}
              >
                <AccessPerson person={row.grantee} />
              </Button>
            ),
          },
          {
            title: '数据源 / 规则',
            width: 255,
            render: (_, row) => (
              <div className="access-stack">
                <strong>{row.source.name}</strong>
                <Typography.Text type="secondary" ellipsis>
                  {row.rules.map((rule) => rule.name).join('、')}
                </Typography.Text>
              </div>
            ),
          },
          { title: '来源', width: 145, render: (_, row) => `访问申请 ${row.requestId}` },
          {
            title: '批准人',
            width: 145,
            render: (_, row) => <AccessPerson person={row.approver} />,
          },
          { title: '状态', width: 105, render: (_, row) => <AccessStatus value={row.state} /> },
          { title: '生效时间', width: 160, render: (_, row) => displayTime(row.startedAt) },
          {
            title: '操作',
            className: 'record-actions-cell',
            width: 90,
            fixed: 'right',
            render: (_, row) =>
              !mine && row.state !== 'ENDED' && allowed(session, 'grants.revoke') ? (
                <Button type="link" danger onClick={() => setRevoking(row)}>
                  撤销
                </Button>
              ) : (
                '—'
              ),
          },
        ]}
      />
      {selected && !requestId && (
        <AccessDetailDrawer
          title={selected.source.name}
          size={640}
          close={() => setSelected(undefined)}
          actions={
            (!mine || allowed(session, 'my-requests.view')) && (
              <Button onClick={() => setRequestId(selected.requestId)}>查看申请</Button>
            )
          }
        >
          <Descriptions
            column={2}
            items={[
              {
                key: 'grantee',
                label: '被授权人 / 申请人',
                children: personLabel(selected.grantee),
              },
              { key: 'state', label: '状态', children: <AccessStatus value={selected.state} /> },
              { key: 'approver', label: '批准人', children: personLabel(selected.approver) },
              { key: 'time', label: '批准时间', children: displayTime(selected.approvedAt) },
              {
                key: 'rules',
                label: '批准时规则',
                children: selected.rules.map((rule) => rule.name).join('、'),
                span: 2,
              },
              ...(selected.endedAt
                ? [
                    {
                      key: 'ended',
                      label: '结束时间',
                      children: displayTime(selected.endedAt),
                      span: 2,
                    },
                    { key: 'reason', label: '结束原因', children: selected.endReason, span: 2 },
                  ]
                : []),
            ]}
          />
        </AccessDetailDrawer>
      )}
      {requestId && <RequestDrawer readOnly id={requestId} close={() => setRequestId(undefined)} />}
      {revoking && <RevokeEditor row={revoking} close={() => setRevoking(undefined)} />}
    </>
  );
}

/**
 * 部门派生来源以组织配置及任职区间回看，来源条件由组织配置办理。
 */
function DepartmentGrants({ mine = false }: { mine?: boolean }) {
  const session = useSession();
  const [selected, setSelected] = useState<DepartmentGrant>();
  return (
    <>
      <AccessGrid<DepartmentGrant>
        path={mine ? '/my-department-grants' : '/department-grants'}
        rowKey="id"
        empty="暂无部门访问来源"
        filters={[
          ...(!mine
            ? [
                { name: 'sourceId', placeholder: '数据源', kind: 'source' as const },
                { name: 'applicant', placeholder: '被授权人' },
              ]
            : []),
          { name: 'keyword', placeholder: '来源部门' },
          {
            name: 'state',
            placeholder: '来源状态',
            options: statusOptions(['EFFECTIVE', 'SUSPENDED', 'ENDED']),
          },
        ]}
        columns={[
          {
            title: '数据源',
            width: 180,
            render: (_, row) => (
              <Button type="link" className="record-link" onClick={() => setSelected(row)}>
                {row.source.name}
              </Button>
            ),
          },
          { title: '来源部门', dataIndex: 'departmentName', width: 170 },
          ...(!mine
            ? [
                {
                  title: '被授权人',
                  width: 165,
                  render: (_: unknown, row: DepartmentGrant) => personLabel(row.grantee),
                },
              ]
            : []),
          {
            title: '输出规则',
            width: 210,
            ellipsis: true,
            render: (_, row) => row.rules.map((rule) => rule.name).join('、'),
          },
          { title: '状态', width: 105, render: (_, row) => <AccessStatus value={row.state} /> },
          { title: '生效时间', width: 160, render: (_, row) => displayTime(row.startedAt) },
        ]}
      />
      {selected && (
        <AccessDetailDrawer
          title={selected.source.name}
          size={600}
          close={() => setSelected(undefined)}
        >
          <Descriptions
            column={2}
            items={[
              { key: 'user', label: '被授权人', children: personLabel(selected.grantee) },
              {
                key: 'department',
                label: '来源部门',
                children: allowed(session, 'departments.view') ? (
                  <a href={`/iam/departments?department=${selected.departmentId}&tab=template`}>
                    {selected.departmentName}
                  </a>
                ) : (
                  selected.departmentName
                ),
              },
              {
                key: 'state',
                label: '来源状态',
                children: <AccessStatus value={selected.state} />,
              },
              { key: 'started', label: '生效时间', children: displayTime(selected.startedAt) },
              ...(selected.endedAt
                ? [{ key: 'ended', label: '结束时间', children: displayTime(selected.endedAt) }]
                : []),
              {
                key: 'rules',
                label: '输出限制',
                children: selected.rules.map((rule) => rule.name).join('、'),
                span: 2,
              },
            ]}
          />
        </AccessDetailDrawer>
      )}
    </>
  );
}

/**
 * 本人访问范围先呈现原型的当前数据表，历史记录从单独入口回看。
 */
export function MyAccessPage() {
  const session = useSession();
  const navigate = useNavigate();
  const [tab, setTab] = useState('personal');
  const [history, setHistory] = useState(false);
  if (!history) {
    // 当前范围提供同排查询和申请入口。
    return (
      <AccessScopes
        action={
          <>
            <Button onClick={() => setHistory(true)}>历史来源</Button>
            {allowed(session, 'my-requests.apply') && (
              <Button type="primary" onClick={() => navigate('/my-requests?apply=new')}>
                申请额外数据访问
              </Button>
            )}
          </>
        }
      />
    );
  }
  return (
    <>
      <Tabs
        activeKey={tab}
        onChange={setTab}
        className="access-source-tabs"
        tabBarExtraContent={<Button onClick={() => setHistory(false)}>返回当前访问范围</Button>}
        items={[
          { key: 'personal', label: '申请来源' },
          { key: 'department', label: '部门来源' },
        ]}
      />
      {tab === 'personal' ? <PersonalGrants mine /> : <DepartmentGrants mine />}
    </>
  );
}

/**
 * 撤销核对从服务端读取实际剩余来源及联合影响。
 */
function RevokeEditor({
  row,
  close,
  onSaved = close,
}: {
  row: AccessGrant;
  close: () => void;
  onSaved?: () => void;
}) {
  const [form] = Form.useForm<{ reason: string }>();
  const [submission, setSubmission] = useState<ReviewSubmission>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  /**
   * 取真实影响及对应签名后才显示确认按钮。
   */
  async function review({ reason }: { reason: string }) {
    setBusy(true);
    setError(undefined);
    const path = `${accessPath}/grants/${row.id}`;
    const body = { expectedVersion: row.version, reason: reason.trim() };
    try {
      // 正式撤销必须带回该版本和提案的核对凭据。
      const preview = await api<Preview>(`${path}/revoke-preview`, { method: 'POST', body });
      setSubmission({
        path: `${path}/revoke`,
        preview,
        title: '核对撤销授权',
        method: 'POST',
        operation: 'grants.revoke',
        body,
        danger: true,
        confirmText: '确认撤销',
        done: onSaved,
      });
    } catch (failure) {
      // 结束或修订发生变化时保留原输入和服务端原因。
      setError(failure instanceof Error ? failure.message : '暂时无法核对');
    } finally {
      setBusy(false);
    }
  }

  return submission ? (
    <ReviewDialog submission={submission} close={() => setSubmission(undefined)} />
  ) : (
    <Modal
      open
      centered
      title="撤销授权"
      onCancel={close}
      onOk={form.submit}
      okText="核对影响"
      confirmLoading={busy}
    >
      <p>
        {personLabel(row.grantee)} · {row.source.name}
      </p>
      {row.joint && <Alert type="warning" showIcon title="本次将结束同一申请的整组联合来源" />}
      {error && <Alert type="error" showIcon title={error} />}
      <Form layout="vertical" form={form} onFinish={(values) => void review(values)}>
        <Form.Item name="reason" label="撤销原因" rules={[{ required: true, whitespace: true }]}>
          <Input.TextArea rows={4} maxLength={500} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/**
 * 本人和管理员分别从允许的入口读取同一权威访问范围。
 */
export function AccessScopes({ userId, action }: { userId?: string; action?: React.ReactNode }) {
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const [selected, setSelected] = useState<AccessScope>();
  const [requestId, setRequestId] = useState<string>();
  const read = useQuery({
    queryKey: ['access', 'scopes', userId],
    queryFn: () =>
      api<AccessScope[]>(
        userId ? `${accessPath}/users/${userId}/sources` : `${accessPath}/my-sources`,
      ),
  });
  const query = {
    sourceName: userId ? '' : (params.get('sourceName') ?? ''),
    ruleName: userId ? '' : (params.get('ruleName') ?? ''),
  };
  const data = (read.data ?? []).filter(
    (entry) =>
      entry.source.name.toLowerCase().includes(query.sourceName.toLowerCase()) &&
      (!query.ruleName ||
        entry.rules.some((rule) => rule.name.toLowerCase().includes(query.ruleName.toLowerCase()))),
  );
  const size = [10, 20, 50].includes(Number(params.get('pageSize')))
    ? Number(params.get('pageSize'))
    : 10;
  const page = Math.max(
    1,
    Math.min(Number(params.get('pageNum')) || 1, Math.ceil(data.length / size)),
  );
  const current = read.data?.find((row) => row.source.id === selected?.source.id);
  const canViewRequest = userId
    ? allowed(session, 'grants.view')
    : allowed(session, 'my-requests.view');

  /**
   * 搜索两个字段时取交集，并保留本人当前列表上下文。
   */
  function search(values: Record<string, string | undefined>) {
    const next = new URLSearchParams(params);
    for (const name of ['sourceName', 'ruleName']) {
      // 清空查询时从地址移除旧值。
      const value = values[name]?.trim();
      if (value) next.set(name, value);
      else next.delete(name);
    }
    next.delete('pageNum');
    setParams(next, { replace: true });
  }

  return (
    <section className={userId ? undefined : 'record-page'}>
      {!userId && (
        <AccessToolbar
          filters={[
            { name: 'sourceName', placeholder: '数据源名称' },
            { name: 'ruleName', placeholder: '输出限制规则名称', inMore: true },
          ]}
          query={query}
          search={search}
          action={action}
        />
      )}
      <div className={userId ? undefined : 'panel records-panel access-records'}>
        {/* 窗口重新获焦时保留当前来源与可操作入口。 */}
        <RecordTable<AccessScope>
          scroll={userId ? { x: 580 } : undefined}
          rowKey={(row) => row.source.id}
          dataSource={userId ? data : data.slice((page - 1) * size, page * size)}
          loading={read.isPending}
          error={read.error}
          retry={() => void read.refetch()}
          emptyDescription="当前没有有效数据访问来源"
          filtered={Boolean(query.sourceName || query.ruleName)}
          onReset={() => search({})}
          pagination={
            userId
              ? false
              : {
                  current: page,
                  pageSize: size,
                  total: data.length,
                  onChange: (current, pageSize) => {
                    // 改变单页容量从第一页显示。
                    const next = new URLSearchParams(params);
                    next.set('pageNum', String(pageSize === size ? current : 1));
                    next.set('pageSize', String(pageSize));
                    setParams(next, { replace: true });
                  },
                }
          }
          columns={[
            {
              title: '数据源',
              width: userId ? 126 : 170,
              ellipsis: true,
              render: (_, row) => row.source.name,
            },
            {
              title: '当前输出限制',
              width: userId ? 168 : 300,
              ellipsis: true,
              render: (_, row) => row.rules.map((rule) => rule.name).join('、'),
            },
            {
              title: '有效来源',
              width: userId ? 82 : 96,
              align: 'right',
              render: (_, row) => `${row.origins.length} 笔`,
            },
            {
              title: '状态',
              width: userId ? 84 : 96,
              render: () => <AccessStatus value="EFFECTIVE" />,
            },
            {
              title: '操作',
              className: 'record-actions-cell',
              width: 116,
              fixed: userId ? undefined : 'right',
              render: (_, row) => (
                <Button type="link" onClick={() => setSelected(row)}>
                  查看详情
                </Button>
              ),
            },
          ]}
        />
      </div>
      {selected && !requestId && (
        <AccessDetailDrawer title="数据访问详情" size={600} close={() => setSelected(undefined)}>
          <div className="access-detail-heading">
            <h2>{selected.source.name}</h2>
            <AccessStatus value={current ? 'EFFECTIVE' : 'ENDED'} />
          </div>
          <h3 className="access-detail-section">当前输出限制</h3>
          {(current?.rules ?? []).map((rule) => (
            <div key={rule.id}>
              <strong>{rule.name}</strong>
              <p className="detail-text">{rule.content}</p>
            </div>
          ))}
          {current?.origins.length ? <h3 className="access-detail-section">访问来源</h3> : null}
          {(current?.origins ?? []).map((origin) => (
            <div className="access-origin" key={`${origin.kind}:${origin.id}`}>
              <strong>{origin.originName}</strong>
              <span>{origin.rules.map((rule) => rule.name).join('、')}</span>
              {origin.kind === 'REQUEST' && canViewRequest && (
                <Button type="link" onClick={() => setRequestId(origin.originId)}>
                  查看申请
                </Button>
              )}
            </div>
          ))}
        </AccessDetailDrawer>
      )}
      {requestId && <RequestDrawer readOnly id={requestId} close={() => setRequestId(undefined)} />}
    </section>
  );
}

/**
 * 直属部门视图接入真实配置及成员变更后影响。
 */
export function DepartmentAccessPanel({ id }: { id: string }) {
  const session = useSession();
  const [editing, setEditing] = useState(false);
  const read = useQuery({
    queryKey: ['access', 'department', id],
    queryFn: () => api<DepartmentAccess>(`${accessPath}/departments/${id}`),
  });
  const row = read.data;
  return (
    <div className="template-content">
      <ReadState loading={read.isPending} error={read.error} retry={() => void read.refetch()}>
        {row && (
          <>
            <Descriptions
              layout="vertical"
              column={2}
              items={[
                { key: 'members', label: '影响成员', children: `本部门直属 ${row.memberCount} 人` },
                {
                  key: 'state',
                  label: '配置状态',
                  children: row.reviewRequired
                    ? '待复核'
                    : row.sources.length
                      ? '已配置'
                      : '未配置',
                },
              ]}
            />
            {row.sources.map((source) => (
              <div className="access-origin" key={source.source.id}>
                <strong>{source.source.name}</strong>
                <span>{source.rules.map((rule) => rule.name).join('、')}</span>
              </div>
            ))}
            {allowed(session, 'departments.access') && (
              <Button onClick={() => setEditing(true)}>配置初始访问</Button>
            )}
            {editing && (
              <DepartmentAccessEditor
                row={row}
                close={() => setEditing(false)}
                allowClear={allowed(session, 'departments.end-access')}
              />
            )}
          </>
        )}
      </ReadState>
    </div>
  );
}

/**
 * 部门分源选择规则，核对只展示将生效的配置。
 */
function DepartmentAccessEditor({
  row,
  close,
  allowClear,
}: {
  row: DepartmentAccess;
  close: () => void;
  allowClear: boolean;
}) {
  const [form] = Form.useForm<{
    sources: { sourceId: string; ruleIds: string[] }[];
    reason?: string;
  }>();
  const [sources, setSources] = useState(
    row.sources.map(({ source }) => ({ value: source.id, label: source.name })),
  );
  const [rules, setRules] = useState(
    row.sources.flatMap(({ rules }) =>
      rules.map((rule) => ({ value: rule.id, label: rule.name, content: rule.content })),
    ),
  );
  return (
    <AccessEditor
      title="配置初始访问"
      successMessage="部门初始访问已更新"
      context={`${row.name} · 本部门直属 ${row.memberCount} 人`}
      form={form}
      close={close}
      initialValues={{
        sources: row.sources.map((source) => ({
          sourceId: source.source.id,
          ruleIds: source.rules.map((rule) => rule.id),
        })),
      }}
      preview={(values) =>
        previewAccess(`/departments/${row.id}`, { ...values, expectedVersion: row.version })
      }
      confirm={(values, verified) => (
        <div className="access-stack">
          {values.sources.length === 0 && (
            <Alert type="warning" showIcon title="将结束该部门初始访问" />
          )}
          {verified?.changes.map((change, index) => (
            <div key={index}>
              <strong>{change.label}</strong>
              <p>{change.after ?? '结束该数据源的部门来源'}</p>
            </div>
          ))}
        </div>
      )}
      save={(values, command, reviewToken) => {
        // 清空授权采用独立权限，后端再次核对。
        if (!values.sources.length && !allowClear)
          return Promise.reject(new Error('没有结束初始访问的权限'));
        return saveAccess(
          `/departments/${row.id}`,
          'PUT',
          { ...values, expectedVersion: row.version, reviewToken },
          values.sources.length ? 'departments.access' : 'departments.end-access',
          command,
        );
      }}
    >
      <Form.List name="sources">
        {(fields, { add, remove }) => (
          <div className="access-stack">
            {fields.map((field) => (
              <div className="access-selection" key={field.key}>
                <Form.Item
                  name={[field.name, 'sourceId']}
                  label="数据源"
                  rules={[{ required: true }]}
                >
                  <AccessSelect
                    kind="source"
                    label="选择数据源"
                    selected={sources}
                    remember={(option) =>
                      setSources((prev) => [
                        ...prev.filter((item) => item.value !== option.value),
                        option,
                      ])
                    }
                  />
                </Form.Item>
                <Form.Item
                  name={[field.name, 'ruleIds']}
                  label="输出限制规则"
                  rules={[{ required: true }]}
                >
                  <AccessSelect
                    kind="rule"
                    label="选择输出限制规则"
                    multiple
                    selected={rules}
                    remember={(option) =>
                      setRules((prev) => [
                        ...prev.filter((item) => item.value !== option.value),
                        { ...option, content: option.content ?? '' },
                      ])
                    }
                  />
                </Form.Item>
                <Button size="small" type="link" onClick={() => remove(field.name)}>
                  移除数据源
                </Button>
              </div>
            ))}
            <Button onClick={() => add()}>添加数据源</Button>
          </div>
        )}
      </Form.List>
      <Form.Item name="reason" label="变更原因">
        <Input.TextArea rows={2} maxLength={500} />
      </Form.Item>
    </AccessEditor>
  );
}
