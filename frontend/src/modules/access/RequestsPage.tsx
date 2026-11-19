import {
  Button,
  Collapse,
  Descriptions,
  Empty,
  Modal,
  Result,
  Tabs,
  Form,
  Input,
  Space,
  Switch,
  Table,
  Timeline,
  Typography,
} from 'antd';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../../shared/api';
import type { Preview } from '../iam/types';
import { displayTime, ReadFailure, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { RecordPagination } from '../../shared/RecordTable';
import {
  accessPath,
  accessList,
  AccessToolbar,
  AccessDetailDrawer,
  useAccessList,
  AccessEditor,
  AccessGrid,
  AccessSelect,
  AccessStatus,
  personLabel,
  saveAccess,
  statusOptions,
} from './AccessControls';
import type { AccessFilter } from './AccessControls';
import { ResponsibilityEditor } from './ConfigurationPages';
import type {
  AccessRequest,
  RequestActivity,
  RequestItem,
  Responsibility,
  SourceOption,
} from './types';

/**
 * 员工申请与审批人员收件箱分别沿用原型的承载方式。
 */
export function RequestsPage({ mine = false }: { mine?: boolean }) {
  return mine ? <MyRequestsPage /> : <ApprovalInbox />;
}

/**
 * 本人申请清单以事项为主，编号作为同一列的次要信息。
 */
function MyRequestsPage() {
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const [create, setCreate] = useState(params.get('apply') === 'new');
  const requestId = params.get('detail');
  useEffect(() => {
    // 申请快捷入口只消费一次，返回列表不重新打开表单。
    if (params.has('apply')) {
      const next = new URLSearchParams(params);
      next.delete('apply');
      setParams(next, { replace: true });
    }
  }, [params, setParams]);

  /**
   * 详情与编辑返回当前已应用条件及页码。
   */
  function show(id?: string, supplement = false) {
    const next = new URLSearchParams(params);
    next.delete('apply');
    if (supplement) next.set('supplement', 'edit');
    else next.delete('supplement');
    if (id) next.set('detail', id);
    else next.delete('detail');
    setParams(next, { replace: true });
  }

  return (
    <>
      <AccessGrid<AccessRequest>
        path="/my-requests"
        rowKey="id"
        filters={[
          { name: 'keyword', placeholder: '申请事项' },
          { name: 'requestId', placeholder: '申请编号' },
          {
            name: 'state',
            placeholder: '全部状态',
            options: statusOptions([
              'DRAFT',
              'PENDING',
              'ROUTING',
              'NEEDS_SUPPLEMENT',
              'APPROVED',
              'EFFECTIVE',
              'REJECTED',
              'WITHDRAWN',
              'ENDED',
            ]),
          },
        ]}
        empty="暂无申请"
        action={
          allowed(session, 'my-requests.apply') && (
            <Button type="primary" onClick={() => setCreate(true)}>
              申请额外数据访问
            </Button>
          )
        }
        columns={[
          {
            title: '申请事项',
            width: 280,
            render: (_, row) => (
              <div className="access-cell-summary">
                <Button
                  type="link"
                  className="record-link"
                  title={row.purpose}
                  onClick={() => show(row.id)}
                >
                  {row.purpose}
                </Button>
                <small>{row.id}</small>
              </div>
            ),
          },
          {
            title: '申请数据源',
            width: 195,
            ellipsis: true,
            render: (_, row) =>
              row.items.map((item) => item.source?.name ?? '待核定').join('、') || '—',
          },
          { title: '提交时间', width: 154, render: (_, row) => displayTime(row.submittedAt) },
          {
            title: '状态',
            width: 105,
            render: (_, row) => <AccessStatus value={requestState(row)} />,
          },
          {
            title: '操作',
            className: 'record-actions-cell',
            width: 116,
            fixed: 'right',
            render: (_, row) => (
              <Button
                type="link"
                onClick={() => {
                  // 需补充时直接进入表单，保留单据作为返回位置。
                  show(
                    row.id,
                    row.items.some((item) => item.state === 'NEEDS_SUPPLEMENT'),
                  );
                }}
              >
                {row.items.some((item) => item.state === 'NEEDS_SUPPLEMENT')
                  ? '补充重提'
                  : '查看详情'}
              </Button>
            ),
          },
        ]}
      />
      {create && <ApplicationEditor close={() => setCreate(false)} done={show} />}
      {requestId && (
        <RequestDrawer
          key={requestId}
          id={requestId}
          startSupplement={params.get('supplement') === 'edit'}
          close={() => show()}
        />
      )}
    </>
  );
}

/**
 * 在本人视角汇总并保留子项间实际状态差异。
 */
function requestState(request: AccessRequest) {
  const values = new Set(request.items.map((item) => item.state));
  if (values.size === 1) {
    // 单一结果直接沿用后端状态。
    return request.items[0].state;
  }
  if (values.has('NEEDS_SUPPLEMENT')) {
    // 优先显示员工需要处理的下一步。
    return 'NEEDS_SUPPLEMENT';
  }
  return values.has('EFFECTIVE') ? 'PARTIALLY_EFFECTIVE' : 'IN_PROGRESS';
}

/**
 * 收件箱左侧是当前分组队列，右侧显示选中申请及固定办理动作。
 */
function ApprovalInbox() {
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const [lastDecision, setLastDecision] = useState<{ id: string; person: string; label: string }>();
  const [showResult, setShowResult] = useState(false);
  const canReview = allowed(session, 'access-requests.review');
  const canRoute = allowed(session, 'access-requests.route');
  const queues = [
    ...(canReview ? [{ value: 'PENDING', label: '待我审批' }] : []),
    ...(canRoute ? [{ value: 'ROUTING', label: '待分流' }] : []),
    ...(canReview ? [{ value: 'HANDLED', label: '我已审批' }] : []),
  ];
  const current = params.get('queue');
  const queue =
    queues.find(({ value }) => value === current)?.value ?? queues[0]?.value ?? 'PENDING';
  const filters: AccessFilter[] = [
    { name: 'applicant', placeholder: '申请人姓名或登录名' },
    { name: 'requestId', placeholder: '申请编号' },
    { name: 'sourceId', placeholder: '数据源', kind: 'source' },
    ...(queue === 'HANDLED'
      ? [
          {
            name: 'state',
            placeholder: '全部状态',
            options: statusOptions([
              'APPROVED',
              'EFFECTIVE',
              'NEEDS_SUPPLEMENT',
              'REJECTED',
              'ENDED',
            ]),
          },
        ]
      : []),
    { name: 'dateFrom', placeholder: '提交日期范围', date: true },
    { name: 'dateTo', placeholder: '提交日期范围', date: true },
  ];
  const { query, read, search, pagination, filtered } = useAccessList<AccessRequest>(
    '/requests',
    filters,
    { queue },
    undefined,
    queues.length > 0,
  );
  const counts = useQueries({
    queries: queues.map(({ value }) => ({
      // 只统计当前身份可用组；条件与该组列表一致。
      queryKey: ['access', 'queue-count', value, query],
      queryFn: () => {
        const conditions: Record<string, string> = { ...query, pageNum: '1', pageSize: '1' };
        if (value !== 'HANDLED') {
          // 结果状态只作用于已办理分组。
          delete conditions.state;
        }
        return accessList<AccessRequest>('/requests', conditions, { queue: value });
      },
      enabled: value !== queue,
    })),
  });
  const entries = read.error ? [] : (read.data?.items ?? []);
  const requested = params.get('selected') ?? params.get('detail');
  const selected = entries.find((item) => item.id === requested) ?? entries[0];

  /**
   * 标签与队列选择各自保持查询上下文。
   */
  function select(key: 'queue' | 'selected', value: string) {
    const next = new URLSearchParams(params);
    next.set(key, value);
    next.delete('detail');
    if (key === 'queue') {
      // 分组间保留通用筛选，结果状态只适用于已办理。
      next.delete('pageNum');
      next.delete('selected');
      next.delete('state');
    }
    setParams(next, { replace: true });
  }

  if (queues.length === 0) {
    // 没有可办队列时不展示单据或总数。
    return <Result status="403" title="当前没有审批或核定分派权限" />;
  }

  return (
    <section className="record-page approval-page">
      <AccessToolbar key={queue} filters={filters} query={query} search={search} />
      <section className="panel records-panel approval-panel">
        <header className="approval-panel-header">
          <Tabs
            activeKey={queue}
            onChange={(value) => select('queue', value)}
            items={queues.map(({ value, label }, index) => {
              const total = value === queue ? read.data?.total : counts[index]?.data?.total;
              return { key: value, label: total === undefined ? label : `${label}（${total}）` };
            })}
          />
          {lastDecision && (
            <Space size={8} className="approval-feedback">
              <Typography.Text ellipsis>{lastDecision.person}</Typography.Text>
              <span>{lastDecision.label}</span>
              <Button type="link" onClick={() => setShowResult(true)}>
                查看结果
              </Button>
            </Space>
          )}
        </header>
        {read.error ? (
          <div className="approval-unavailable">
            <ReadFailure error={read.error} retry={() => void read.refetch()} />
          </div>
        ) : (
          <div className="approval-workspace" aria-busy={read.isFetching}>
            <nav className="approval-queue" aria-label="访问申请队列">
              {entries.map((entry) => (
                <button
                  type="button"
                  key={entry.id}
                  className="approval-entry"
                  aria-current={entry.id === selected?.id ? 'true' : undefined}
                  onClick={() => select('selected', entry.id)}
                >
                  <span className="approval-entry-identity">
                    <strong title={personLabel(entry.applicant)}>
                      {entry.applicant.displayName}
                    </strong>
                    <AccessStatus value={requestState(entry)} />
                  </span>
                  <span className="approval-entry-title" title={entry.purpose}>
                    {entry.purpose}
                  </span>
                  <span className="approval-entry-meta">
                    <small>{entry.id}</small>
                    <time dateTime={entry.submittedAt ?? undefined}>
                      {displayTime(entry.submittedAt)}
                    </time>
                  </span>
                </button>
              ))}
              {!entries.length && (
                <p className="approval-queue-empty">
                  {read.isPending ? '正在加载' : filtered ? '无匹配项' : '暂无申请'}
                </p>
              )}
            </nav>
            {selected ? (
              <ApprovalDocument
                key={selected.id}
                row={selected}
                queue={queue}
                onDecided={(label) => {
                  // 办理后从服务器回读当前队列，保留最近一笔结果入口。
                  setLastDecision({
                    id: selected.id,
                    person: selected.applicant.displayName,
                    label,
                  });
                }}
              />
            ) : (
              <div className="approval-document-empty">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    read.isPending
                      ? '正在加载单据'
                      : filtered
                        ? '没有匹配的申请'
                        : '当前分组暂无申请'
                  }
                />
              </div>
            )}
          </div>
        )}
        <RecordPagination {...pagination} busy={read.isFetching} error={Boolean(read.error)} />
      </section>
      {showResult && lastDecision && (
        <RequestDrawer id={lastDecision.id} historyOnly close={() => setShowResult(false)} />
      )}
    </section>
  );
}

/**
 * 单据右侧保留原型的申请资料、核定与审批及固定动作区。
 */
function ApprovalDocument({
  row,
  queue,
  onDecided,
}: {
  row: AccessRequest;
  queue: string;
  onDecided: (label: string) => void;
}) {
  const session = useSession();
  const [partId, setPartId] = useState<string>();
  const [action, setAction] = useState<{ action: string; item?: RequestItem }>();
  const [configure, setConfigure] = useState(false);
  const item = row.items.find((entry) => entry.id === partId) ?? row.items[0];
  const live = queue !== 'HANDLED';
  const canRoute =
    live &&
    allowed(session, 'access-requests.route') &&
    (item.state === 'ROUTING' ||
      (item.state === 'PENDING' && item.assignee?.id === session.userId));
  const canPlan = live && row.planAvailable;
  const canReview =
    live &&
    item.state === 'PENDING' &&
    item.assignee?.id === session.userId &&
    row.applicant.id !== session.userId;
  return (
    <article className="approval-document" aria-label={`申请 ${row.id} 的办理单据`}>
      <div className="approval-document-body">
        {/* 单据状态、事项和身份围成完整摘要。 */}
        <header className="approval-document-heading">
          <Space size={8}>
            <AccessStatus value={item.state} />
            <small>
              {row.id} · 修订 {row.version}
            </small>
          </Space>
          <h2>{item.application?.purpose ?? row.purpose}</h2>
          <dl className="approval-metadata">
            <div>
              <dt>申请人</dt>
              <dd>{personLabel(row.applicant)}</dd>
            </div>
            {row.departmentName && (
              <div>
                <dt>所属部门</dt>
                <dd>{row.departmentName}</dd>
              </div>
            )}
            <div>
              <dt>提交时间</dt>
              <dd>{displayTime(row.submittedAt)}</dd>
            </div>
            {row.joint && (
              <div>
                <dt>办理方式</dt>
                <dd>联合办理</dd>
              </div>
            )}
          </dl>
        </header>
        <section className="approval-section" aria-label="申请资料">
          <dl className="approval-request-fields">
            <div>
              <dt>需要的数据</dt>
              <dd>{item.application?.dataNeeded ?? row.dataNeeded}</dd>
            </div>
            <div>
              <dt>已知数据源</dt>
              <dd>
                {row.items
                  .map((part) => part.source?.name)
                  .filter(Boolean)
                  .join('、') || '由负责人核定'}
              </dd>
            </div>
            {(item.application?.supplement ?? row.supplement) && (
              <div>
                <dt>补充说明</dt>
                <dd>{item.application?.supplement ?? row.supplement}</dd>
              </div>
            )}
          </dl>
        </section>
        <section className="approval-section">
          <div className="approval-decision-heading">
            <h3>{live ? '核定与审批' : '审批记录'}</h3>
            <Space wrap>
              {canPlan && (
                <Button type="link" onClick={() => setAction({ action: 'PLAN' })}>
                  拆分与联合设置
                </Button>
              )}
              {canRoute && item.state === 'PENDING' && (
                <Button type="link" onClick={() => setAction({ action: 'ROUTE', item })}>
                  重新核定数据源
                </Button>
              )}
            </Space>
          </div>
          {row.items.length > 1 && (
            <Tabs
              activeKey={item.id}
              onChange={setPartId}
              items={row.items.map((part) => ({
                key: part.id,
                label: part.source?.name ?? '待核定数据源',
              }))}
            />
          )}
          <div className="approval-part-meta">
            <AccessStatus value={item.state} />
            {item.assignee && <span>审批负责人 · {personLabel(item.assignee)}</span>}
            {item.handler && (
              <span>
                {personLabel(item.handler)} · {displayTime(item.handledAt)}
              </span>
            )}
          </div>
          <dl className="approval-scope">
            <div>
              <dt>数据源</dt>
              <dd>{item.source?.name ?? '待核定'}</dd>
            </div>
            <div>
              <dt>输出限制规则</dt>
              <dd>
                {item.rules.length ? item.rules.map((rule) => rule.name).join('、') : '待选择'}
              </dd>
            </div>
          </dl>
          {item.reason && <p className="approval-reason">{item.reason}</p>}
        </section>
      </div>
      {live && (
        <footer className="approval-actions">
          {row.items.length > 1 && (
            <span title={item.source?.name}>{item.source?.name ?? '待核定数据源'}</span>
          )}
          <Space wrap>
            {canRoute &&
              item.state === 'ROUTING' &&
              item.source &&
              allowed(session, 'sources.approval-config') && (
                <Button onClick={() => setConfigure(true)}>配置审批责任</Button>
              )}
            {canRoute && item.state === 'ROUTING' && (
              <Button type="primary" onClick={() => setAction({ action: 'ROUTE', item })}>
                核定数据源
              </Button>
            )}
            {canReview && allowed(session, 'access-requests.reject') && (
              <Button danger onClick={() => setAction({ action: 'REJECT', item })}>
                驳回
              </Button>
            )}
            {canReview && allowed(session, 'access-requests.supplement') && (
              <Button onClick={() => setAction({ action: 'SUPPLEMENT', item })}>要求补充</Button>
            )}
            {canReview && allowed(session, 'access-requests.review') && (
              <Button type="primary" onClick={() => setAction({ action: 'APPROVE', item })}>
                核定并批准
              </Button>
            )}
          </Space>
        </footer>
      )}
      {configure && item.source && (
        <AssignmentConfiguration source={item.source} close={() => setConfigure(false)} />
      )}
      {action && (
        <DecisionEditor
          row={row}
          {...action}
          close={() => setAction(undefined)}
          done={() => {
            // 历史结果仅在实际产生办理决定时提供回看。
            if (decisions[action.action]) onDecided(decisions[action.action].label);
          }}
        />
      )}
    </article>
  );
}

/**
 * 待分流时在同一单据上下文配置当前数据源责任。
 */
function AssignmentConfiguration({ source, close }: { source: SourceOption; close: () => void }) {
  const read = useQuery({
    queryKey: ['access', 'responsibility-by-source', source.id],
    queryFn: () =>
      accessList<Responsibility>('/responsibilities', { keyword: source.name, pageSize: '100' }),
  });
  const row = read.data?.items.find((entry) => entry.source.id === source.id);
  if (row) {
    // 使用数据源管理的原责任编辑器。
    return <ResponsibilityEditor row={row} close={close} />;
  }
  return (
    <Modal open centered title="配置审批责任" footer={null} onCancel={close}>
      <ReadState loading={read.isPending} error={read.error} retry={() => void read.refetch()}>
        <p>当前数据源不可配置审批责任。</p>
      </ReadState>
    </Modal>
  );
}

/**
 * 本人创建、保存草稿或按经办人要求补充。
 */
function ApplicationEditor({
  row,
  item,
  close,
  done,
}: {
  row?: AccessRequest;
  item?: RequestItem;
  close: () => void;
  done?: (id: string) => void;
}) {
  const [form] = Form.useForm<{
    purpose: string;
    dataNeeded: string;
    sourceIds?: string[];
    supplement?: string;
    draft?: boolean;
  }>();
  const [selected, setSelected] = useState(
    (row?.items ?? []).flatMap((item) =>
      item.source ? [{ value: item.source.id, label: item.source.name }] : [],
    ),
  );
  const supplementing = Boolean(row?.submittedAt);
  return (
    <AccessEditor
      title={supplementing ? '补充重提' : '申请数据访问'}
      successMessage={supplementing ? '补充资料已提交' : '访问申请已提交'}
      context={supplementing && item?.reason ? <span>补充要求 · {item.reason}</span> : undefined}
      form={form}
      initialValues={{
        purpose: item?.application?.purpose ?? row?.purpose,
        dataNeeded: item?.application?.dataNeeded ?? row?.dataNeeded,
        sourceIds: selected.map((option) => option.value),
        supplement: item?.application?.supplement ?? row?.supplement,
        draft: false,
      }}
      close={close}
      extra={
        !supplementing && (
          <Button
            onClick={() => {
              // 草稿和正式申请复用同一组输入与再次核对。
              form.setFieldValue('draft', true);
              form.submit();
            }}
          >
            保存草稿
          </Button>
        )
      }
      prepare={() => form.setFieldValue('draft', false)}
      preview={(values) =>
        api<Preview>(
          `${accessPath}/my-requests/submission-preview${row ? `?id=${encodeURIComponent(row.id)}` : ''}`,
          {
            method: 'POST',
            body: { ...values, expectedVersion: row?.version, draft: values.draft ?? false },
          },
        )
      }
      confirm={(values, verified) => (
        <Descriptions
          column={1}
          items={[
            { key: 'purpose', label: '业务用途', children: values.purpose },
            { key: 'data', label: '需要的数据', children: values.dataNeeded },
            ...(supplementing && values.supplement
              ? [{ key: 'supplement', label: '补充说明', children: values.supplement }]
              : []),
            ...(verified?.changes ?? []).map((change, index) => ({
              key: `${change.field}:${index}`,
              label: change.label,
              children: change.after,
            })),
          ]}
        />
      )}
      save={async (values, command) => {
        // 保存命令返回真实单据编号供回看。
        const result = await saveAccess(
          row ? `/my-requests/${row.id}` : '/my-requests',
          row ? 'PUT' : 'POST',
          { ...values, expectedVersion: row?.version, draft: values.draft ?? false },
          'my-requests.apply',
          command,
        );
        if (result.targetId) done?.(result.targetId);
      }}
    >
      <Form.Item name="draft" hidden>
        <Input />
      </Form.Item>
      <Form.Item name="purpose" label="业务用途" rules={[{ required: true, whitespace: true }]}>
        <Input autoFocus maxLength={500} placeholder="要完成的业务工作" />
      </Form.Item>
      <Form.Item
        name="dataNeeded"
        label="需要的数据"
        rules={[{ required: true, whitespace: true }]}
      >
        <Input.TextArea rows={4} maxLength={2000} />
      </Form.Item>
      {supplementing ? (
        <Form.Item name="supplement" label="补充说明">
          <Input.TextArea rows={3} maxLength={1000} />
        </Form.Item>
      ) : (
        <Form.Item name="sourceIds" label="已知数据源">
          <AccessSelect
            kind="source"
            multiple
            label="可选填已知数据源"
            selected={selected}
            remember={(option) =>
              setSelected((before) =>
                before.some((item) => item.value === option.value) ? before : [...before, option],
              )
            }
          />
        </Form.Item>
      )}
    </AccessEditor>
  );
}

/**
 * 在同一抽屉回看单据，只显示当前可办理子项的操作。
 */
export function RequestDrawer({
  id,
  close,
  startSupplement = false,
  historyOnly = false,
  readOnly = false,
}: {
  id: string;
  close: () => void;
  startSupplement?: boolean;
  historyOnly?: boolean;
  readOnly?: boolean;
}) {
  const session = useSession();
  const [editing, setEditing] = useState<boolean | string>(startSupplement);
  const [action, setAction] = useState<{ action: string; item?: RequestItem }>();
  const read = useQuery({
    queryKey: ['access', 'request', id, historyOnly],
    queryFn: () =>
      api<AccessRequest>(`${accessPath}/requests/${id}${historyOnly ? '/handled' : ''}`),
  });
  const row = read.data;
  const mine = row?.applicant.id === session.userId;
  const canAct = !historyOnly && !readOnly;
  const canPlan = canAct && row?.planAvailable && !mine;
  const editableItem = row?.items.find(
    (item) =>
      ['DRAFT', 'NEEDS_SUPPLEMENT'].includes(item.state) &&
      (editing === true || editing === item.id),
  );
  return (
    <AccessDetailDrawer
      size={800}
      title={historyOnly ? '审批结果' : (row?.purpose ?? '访问申请')}
      close={close}
      actions={
        <>
          {canPlan && <Button onClick={() => setAction({ action: 'PLAN' })}>拆分与联合设置</Button>}
          {historyOnly &&
            allowed(session, 'grants.view') &&
            row?.items.some((item) => ['EFFECTIVE', 'ENDED'].includes(item.state)) && (
              <Button href={'/iam/grants?requestId=' + encodeURIComponent(row.id)}>
                查看授权来源
              </Button>
            )}
        </>
      }
    >
      <ReadState loading={read.isPending} error={read.error} retry={() => void read.refetch()}>
        {row && (
          <>
            {/* 整体用途与申请身份在当前确切子项上方。 */}
            <Descriptions
              column={2}
              items={[
                { key: 'id', label: '申请编号', children: row.id },
                { key: 'user', label: '申请人', children: personLabel(row.applicant) },
                { key: 'submitted', label: '提交时间', children: displayTime(row.submittedAt) },
                { key: 'joint', label: '办理方式', children: row.joint ? '联合生效' : '独立办理' },
                {
                  key: 'data',
                  label: '需要的数据',
                  children: <span className="detail-text">{row.dataNeeded}</span>,
                  span: 2,
                },
                ...(row.supplement
                  ? [
                      {
                        key: 'supplement',
                        label: '补充说明',
                        children: <span className="detail-text">{row.supplement}</span>,
                        span: 2,
                      },
                    ]
                  : []),
              ]}
            />
            <h3>办理情况</h3>
            <Table<RequestItem>
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={row.items}
              columns={[
                {
                  title: '数据源 / 状态',
                  width: 220,
                  render: (_, item) => (
                    <div className="access-stack">
                      <Typography.Text strong>
                        {item.source?.name ?? '待核定数据源'}
                      </Typography.Text>
                      <AccessStatus value={item.state} />
                    </div>
                  ),
                },
                {
                  title: '负责人 / 办理记录',
                  render: (_, item) => (
                    <div className="access-stack">
                      {item.assignee && <span>{personLabel(item.assignee)}</span>}
                      {item.handler && (
                        <span>
                          {personLabel(item.handler)} · {displayTime(item.handledAt)}
                        </span>
                      )}
                      {item.rules.length > 0 && (
                        <span>{item.rules.map((rule) => rule.name).join('、')}</span>
                      )}
                      {item.reason && (
                        <Typography.Text type="secondary">{item.reason}</Typography.Text>
                      )}
                      {item.application && (
                        <div className="detail-text">
                          <strong>{item.application.purpose}</strong>
                          <p>{item.application.dataNeeded}</p>
                          {item.application.supplement && <p>{item.application.supplement}</p>}
                        </div>
                      )}
                    </div>
                  ),
                },
                ...(canAct
                  ? [
                      {
                        title: '操作',
                        width: 175,
                        render: (_: unknown, item: RequestItem) => (
                          <Space size={4} wrap>
                            {mine &&
                              ['DRAFT', 'NEEDS_SUPPLEMENT'].includes(item.state) &&
                              allowed(session, 'my-requests.apply') && (
                                <Button type="link" onClick={() => setEditing(item.id)}>
                                  {item.state === 'DRAFT' ? '编辑' : '补充重提'}
                                </Button>
                              )}
                            {mine &&
                              [
                                'DRAFT',
                                'PENDING',
                                'ROUTING',
                                'NEEDS_SUPPLEMENT',
                                'APPROVED',
                              ].includes(item.state) &&
                              allowed(session, 'my-requests.withdraw') && (
                                <Button
                                  type="link"
                                  danger
                                  onClick={() => setAction({ action: 'WITHDRAW', item })}
                                >
                                  {item.state === 'DRAFT' ? '删除草稿' : '撤回'}
                                </Button>
                              )}
                            {!mine &&
                              (item.state === 'ROUTING' ||
                                (item.assignee?.id === session.userId &&
                                  item.state === 'PENDING')) &&
                              allowed(session, 'access-requests.route') && (
                                <Button
                                  type="link"
                                  onClick={() => setAction({ action: 'ROUTE', item })}
                                >
                                  核定数据源
                                </Button>
                              )}
                            {!mine &&
                              item.state === 'PENDING' &&
                              item.assignee?.id === session.userId &&
                              Object.entries(decisions).map(
                                ([key, config]) =>
                                  allowed(session, config.permission) && (
                                    <Button
                                      key={key}
                                      type="link"
                                      danger={key === 'REJECT'}
                                      onClick={() => setAction({ action: key, item })}
                                    >
                                      {config.label}
                                    </Button>
                                  ),
                              )}
                          </Space>
                        ),
                      },
                    ]
                  : []),
              ]}
            />
            {mine && row.submittedAt && <RequestHistory id={id} />}
          </>
        )}
      </ReadState>
      {row && editableItem && canAct && mine && allowed(session, 'my-requests.apply') && (
        <ApplicationEditor row={row} item={editableItem} close={() => setEditing(false)} />
      )}
      {row && action && (
        <DecisionEditor
          row={row}
          {...action}
          close={() => setAction(undefined)}
          done={() => {
            // 删除草稿或办结后返回原查询，使队列重新定位。
            if (action.action === 'WITHDRAW' && action.item?.state === 'DRAFT') close();
          }}
        />
      )}
    </AccessDetailDrawer>
  );
}

/**
 * 原办理动作进入历史区，办结人与新分派人分别回看。
 */
function RequestHistory({ id }: { id: string }) {
  const history = useQuery({
    queryKey: ['access', 'request-history', id],
    queryFn: () => api<RequestActivity[]>(`${accessPath}/my-requests/${id}/history`),
  });
  const labels: Record<string, string> = {
    DRAFT: '保存草稿',
    SUBMIT: '提交申请',
    RESUBMIT: '补充重提',
    ROUTE: '核定数据源',
    PLAN: '调整办理方式',
    APPROVE: '批准',
    REJECT: '驳回',
    SUPPLEMENT: '要求补充',
    WITHDRAW: '撤回',
    REVOKE: '撤销授权',
    ACCOUNT_DISABLED: '账号停用后结束访问',
  };
  return (
    <Collapse
      className="access-request-history"
      ghost
      items={[
        {
          key: 'history',
          label: '处理记录',
          children: (
            <ReadState
              loading={history.isPending}
              error={history.error}
              retry={() => void history.refetch()}
            >
              <Timeline
                items={(history.data ?? []).map((entry) => ({
                  key: entry.version,
                  content: (
                    <Space wrap>
                      <strong>{labels[entry.action] ?? entry.action}</strong>
                      <span>{personLabel(entry.actor)}</span>
                      <Typography.Text type="secondary">
                        {displayTime(entry.createdAt)}
                      </Typography.Text>
                    </Space>
                  ),
                }))}
              />
            </ReadState>
          ),
        },
      ]}
    />
  );
}

/**
 * 与各决定明确绑定的权限和交互名称。
 */
const decisions: Record<string, { label: string; permission: string }> = {
  APPROVE: { label: '批准', permission: 'access-requests.review' },
  SUPPLEMENT: { label: '要求补充', permission: 'access-requests.supplement' },
  REJECT: { label: '驳回', permission: 'access-requests.reject' },
};

/**
 * 在同一操作上下文中核对源、关联规则及处理结果。
 */
function DecisionEditor({
  row,
  item,
  action,
  close,
  done,
}: {
  row: AccessRequest;
  item?: RequestItem;
  action: string;
  close: () => void;
  done: () => void;
}) {
  const [form] = Form.useForm<{
    sourceId?: string;
    sourceIds?: string[];
    joint?: boolean;
    ruleIds?: string[];
    reason?: string;
  }>();
  const [known, setKnown] = useState<
    Record<string, { value: string; label: string; content?: string }>
  >({});
  const decision = decisions[action];
  const title =
    decision?.label ??
    (action === 'PLAN'
      ? '调整办理方式'
      : action === 'ROUTE'
        ? '核定数据源'
        : item?.state === 'DRAFT'
          ? '删除草稿'
          : '撤回申请');
  const operation =
    decision?.permission ??
    (action === 'WITHDRAW' ? 'my-requests.withdraw' : 'access-requests.route');
  const routing = ['PLAN', 'ROUTE'].includes(action);
  const currentSources =
    action === 'PLAN'
      ? (row.planSources ?? [])
      : row.items.flatMap((entry) => (entry.source ? [entry.source] : []));
  const chosenSource = Form.useWatch('sourceId', form);
  const assignment = useQuery({
    queryKey: ['access', 'routing-preview', row.id, row.version, item?.id, chosenSource],
    enabled: action === 'ROUTE' && Boolean(item?.id && chosenSource),
    queryFn: () =>
      api<Preview>(`${accessPath}/requests/${row.id}/action-preview`, {
        method: 'POST',
        body: proposal({ sourceId: chosenSource }),
      }),
  });
  const contact = assignment.data?.changes.find((change) => change.field === 'assignee');
  const reasonRequired = ['REJECT', 'SUPPLEMENT', 'PLAN'].includes(action);

  /**
   * 记住引用标题和规则正文，供核对步骤回看。
   */
  function remember(option: { value: string; label: string; content?: string }) {
    setKnown((before) => ({ ...before, [option.value]: option }));
  }

  /**
   * 预览和最终动作共享当前完整提案。
   */
  function proposal(values: object) {
    return { ...values, itemId: item?.id, action, expectedVersion: row.version };
  }

  return (
    <AccessEditor
      title={title}
      successMessage={title + '已提交'}
      reviewTitle={action === 'ROUTE' ? '核对分派' : undefined}
      context={
        <Space>
          <span>{row.purpose}</span>
          {item?.source && <Typography.Text type="secondary">{item.source.name}</Typography.Text>}
        </Space>
      }
      form={form}
      initialValues={{
        joint: row.joint,
        sourceIds: currentSources.map((entry) => entry.id),
      }}
      close={close}
      done={done}
      danger={action === 'REJECT' || action === 'WITHDRAW'}
      preview={(values) =>
        api<Preview>(`${accessPath}/requests/${row.id}/action-preview`, {
          method: 'POST',
          body: proposal(values),
        })
      }
      confirm={(values, verified) => (
        <Descriptions
          column={1}
          items={[
            ...(verified?.changes ?? []).map((change, index) => ({
              key: `${change.field}:${index}`,
              label: change.field === 'rule' ? '输出限制规则' : change.label,
              children:
                change.field === 'rule' ? (
                  <div className="detail-text">
                    <strong>{change.label}</strong>
                    <div>{change.after}</div>
                  </div>
                ) : change.before ? (
                  `${change.before} → ${change.after}`
                ) : (
                  change.after
                ),
            })),
            ...(values.reason ? [{ key: 'reason', label: '说明', children: values.reason }] : []),
          ]}
        />
      )}
      save={(values, command, reviewToken) =>
        saveAccess(
          `/requests/${row.id}/actions`,
          'POST',
          { ...proposal(values), reviewToken },
          operation,
          command,
        )
      }
    >
      {routing && (
        <Form.Item
          name={action === 'PLAN' ? 'sourceIds' : 'sourceId'}
          label="核定数据源"
          rules={[{ required: true }]}
        >
          <AccessSelect
            kind="source"
            label="选择数据源"
            multiple={action === 'PLAN'}
            selected={[
              ...currentSources.map((entry) => ({ value: entry.id, label: entry.name })),
              ...Object.values(known),
            ]}
            remember={remember}
          />
        </Form.Item>
      )}
      {action === 'ROUTE' && chosenSource && (
        <p className="muted" role="status">
          {assignment.error
            ? assignment.error.message
            : assignment.isPending
              ? '正在核对审批负责人'
              : contact
                ? `审批负责人 · ${contact.after}`
                : ''}
        </p>
      )}
      {action === 'PLAN' && (
        <Form.Item name="joint" label="联合生效" valuePropName="checked">
          <Switch aria-label="联合生效" />
        </Form.Item>
      )}
      {action === 'APPROVE' && (
        <Form.Item
          name="ruleIds"
          label="输出限制规则"
          rules={[{ required: true, message: '请选择至少一条输出限制规则' }]}
        >
          <AccessSelect
            kind="rule"
            label="选择输出限制规则"
            multiple
            placement="topLeft"
            selected={Object.values(known)}
            remember={remember}
          />
        </Form.Item>
      )}
      {(reasonRequired || action === 'APPROVE') && (
        <Form.Item
          name="reason"
          label="办理说明"
          rules={[{ required: reasonRequired, whitespace: reasonRequired }]}
        >
          <Input.TextArea rows={3} maxLength={1000} />
        </Form.Item>
      )}
      {action === 'WITHDRAW' && (
        <Typography.Text>
          {item?.state === 'DRAFT'
            ? '删除后将从草稿中移除。'
            : row.joint
              ? '撤回这份尚未生效的联合申请。'
              : '撤回当前子项。'}
        </Typography.Text>
      )}
    </AccessEditor>
  );
}
