import { RecordTable } from '../../shared/RecordTable';
import { Button, DatePicker, Descriptions, Drawer, Form, Input, Select, Space, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router';
import { useEffect, useRef, useState } from 'react';
import dayjs from 'dayjs';
import { api, queryString } from '../../shared/api';
import { DetailFooter, displayChangeValue, displayTime, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { accessList, accessPath, accessState, AccessStatus } from '../access/AccessControls';
import { RequestDrawer } from '../access/RequestsPage';
import type { AccessRequest, GrantDirectoryEntry } from '../access/types';
import type { Change, Page } from './types';

/**
 * 审计操作以业务名称展示和选择，内部代码只用于接口匹配。
 */
const actionLabels: Record<string, string> = {
  'iam.bootstrap': '初始化首管理员',
  'users.create': '新增人员',
  'users.edit': '编辑人员',
  'users.transfer': '调整人员归属',
  'users.assign': '分配个人角色',
  'users.disable': '停用人员',
  'users.enable': '复核并恢复人员',
  'users.archive': '归档人员',
  'users.password': '重置密码',
  'users.recover': '恢复受保护权限',
  'departments.create': '新增部门',
  'departments.edit': '编辑部门',
  'departments.add-member': '加入兼任成员',
  'departments.remove-member': '移出兼任成员',
  'departments.transfer-members': '迁移部门成员或下级',
  'departments.disable': '停用部门',
  'departments.enable': '复核并恢复部门',
  'departments.archive': '归档部门',
  'roles.create': '新增角色',
  'roles.edit': '编辑角色',
  'roles.disable': '停用角色',
  'roles.enable': '启用角色',
  'roles.archive': '归档角色',
  'roles.protected': '调整受保护配置',
  'account.contact': '修改本人联系方式',
  'account.password': '修改本人密码',
  'auth.password.initial': '设置首次登录密码',
  'sources.create': '新增数据源',
  'sources.edit': '维护数据源配置',
  'sources.enable': '启用或恢复数据源',
  'sources.disable': '停用数据源',
  'sources.retire': '退役数据源',
  'sources.pause': '暂停或恢复定时采集',
  'sources.credential.applied': '切换数据源凭据',
  'collections.collect': '发起采集',
  'collections.schedule': '触发定时采集',
  'collections.finish': '记录采集结果',
  'collections.retry': '重试采集',
  'collections.cancel': '取消采集',
  'collections.review': '核实结构变化',
  'business-notes.submit': '新增企业业务说明',
  'business-notes.replace': '修订企业业务说明',
  'business-notes.withdraw': '撤下企业业务说明',
  'business-notes.restore': '恢复企业业务说明',
  'business-notes.delete': '删除企业业务说明',
  'sources.approval-config': '配置审批责任',
  'output-rules.create': '新增输出限制规则',
  'output-rules.edit': '编辑输出限制规则',
  'departments.access': '配置部门初始访问',
  'departments.end-access': '结束部门初始访问',
  'my-requests.apply': '提交或修改访问申请',
  'my-requests.withdraw': '撤回访问申请',
  'access-requests.route': '核定或调整申请范围',
  'access-requests.review': '批准访问申请',
  'access-requests.supplement': '要求补充申请',
  'access-requests.reject': '驳回访问申请',
  'grants.revoke': '撤销授权来源',
};

/**
 * 申请办理详情解释真实决定，区分共用权限编码的核定和组合调整。
 */
const decisionLabels: Record<string, string> = {
  ROUTE: '核定数据源',
  PLAN: '调整办理范围',
  APPROVE: '批准',
  SUPPLEMENT: '要求补充',
  REJECT: '驳回',
  WITHDRAW: '撤回',
};

/**
 * 审计使用操作发生时的身份快照，后续人员改名不改写历史。
 */
interface AuditRecord {
  /**
   * 可用于受保护恢复核对的审计标识。
   */
  id: string;
  /**
   * 当时的操作主体快照。
   */
  actor: { kind: string; displayName: string | null; username: string | null };
  /**
   * 实际发生的业务动作。
   */
  actionCode: string;
  /**
   * 区分申请、个人授权及其他审计目标。
   */
  targetType: string;
  /**
   * 操作目标的历史名称。
   */
  targetName: string | null;
  /**
   * 操作目标稳定标识。
   */
  targetId: string;
  /**
   * 成功、拒绝或失败。
   */
  outcome: string;
  /**
   * 成功批准时保存的实际生效结果。
   */
  approvalEffect?: 'EFFECTIVE' | 'APPROVED' | null;
  /**
   * 实际业务发生时刻。
   */
  occurredAt: string;
  /**
   * 操作时记录的原因。
   */
  reason?: string | null;
  /**
   * 当前记录对应的最小差异。
   */
  changes?: Change[];
  /**
   * 幂等提交的原命令标识。
   */
  commandId?: string | null;
  /**
   * 审计发生时提交的权限版本。
   */
  securityVersion?: string | null;
}

/**
 * 批准后展示当时的生效结果，其他动作沿用审计结果。
 */
function auditOutcome(record: AuditRecord) {
  return record.approvalEffect
    ? accessState[record.approvalEffect]
    : ({ SUCCESS: '成功', REJECTED: '拒绝', FAILED: '失败' }[record.outcome] ?? record.outcome);
}

/**
 * 审计只读查询与详情回看，操作入口不提供修改或删除。
 */
export function AuditPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const session = useSession();

  const [form] = Form.useForm();

  const actor = params.get('actor') ?? undefined;
  const actionCode = params.get('actionCode') ?? undefined;
  const target = params.get('target') ?? undefined;
  const outcome = params.get('outcome') ?? undefined;
  const fromDate = params.get('fromDate');
  const toDate = params.get('toDate');

  useEffect(() => {
    // 审计回退时恢复组合条件和日期，不能只恢复结果却清空查询控件。
    form.setFieldsValue({
      actor,
      actionCode,
      target,
      outcome,
      period: fromDate && toDate ? [dayjs(fromDate), dayjs(toDate)] : undefined,
    });
  }, [form, actor, actionCode, target, outcome, fromDate, toDate]);

  const selected = params.get('detail');
  const listParams = new URLSearchParams(params);
  listParams.delete('detail');
  const [showRequest, setShowRequest] = useState(false);
  const requestTrigger = useRef<HTMLButtonElement>(null);
  const returningFromRequest = useRef(false);

  /**
   * 详情地址保留列表条件，跨页返回仍能打开原记录。
   */
  function selectDetail(id?: string) {
    const next = new URLSearchParams(listParams);
    // 关闭抽屉时只移除详情标识。
    if (id) next.set('detail', id);
    setParams(next, { replace: true });
  }

  /**
   * 关闭审计抽屉后恢复到原列表按钮。
   */
  function closeDetail() {
    selectDetail();
    requestAnimationFrame(() =>
      document.getElementById(`audit-record-${selected}`)?.focus({ preventScroll: true }),
    );
  }

  const records = useQuery({
    queryKey: ['audit', listParams.toString()],
    queryFn: () => api<Page<AuditRecord>>(`/iam/audit-records?${listParams}`),
  });

  const detail = useQuery({
    queryKey: ['audit-detail', selected],
    queryFn: () => api<AuditRecord>(`/iam/audit-records/${selected}`),
    enabled: selected !== null,
  });

  const row = detail.isSuccess ? detail.data : undefined;
  const requestId = row?.targetType === 'access_request' ? row.targetId : undefined;
  const grantId = row?.targetType === 'access_grant' ? row.targetId : undefined;
  const canViewOwn = allowed(session, 'my-requests.view');
  const canViewHandled =
    allowed(session, 'access-requests.view') && allowed(session, 'access-requests.review');
  const canViewGrants = allowed(session, 'grants.view');

  // 本人或实际经办人通过独立的申请读取校验。
  const ownRequest = useQuery({
    queryKey: ['access', 'audit-own-request', requestId, session.userId],
    queryFn: () => api<AccessRequest>(`${accessPath}/requests/${requestId}`),
    enabled: Boolean(requestId) && canViewOwn,
    retry: false,
  });
  const ownsRequest =
    canViewOwn && ownRequest.isSuccess && ownRequest.data.applicant.id === session.userId;
  const handledRequest = useQuery({
    queryKey: ['access', 'audit-handled-request', requestId, session.userId],
    queryFn: () => api<AccessRequest>(`${accessPath}/requests/${requestId}/handled`),
    enabled: Boolean(requestId) && !ownsRequest && canViewHandled,
    retry: false,
  });
  const canViewRequest = ownsRequest || (canViewHandled && handledRequest.isSuccess);

  // 授权目录独立核权，并确认当前关联下已有实际来源。
  const grantFilter: Record<string, string> | undefined = grantId
    ? { grantId }
    : requestId
      ? { requestId }
      : undefined;
  const grants = useQuery({
    queryKey: ['access', 'audit-grants', grantFilter, session.userId],
    queryFn: () =>
      accessList<GrantDirectoryEntry>('/grant-directory', { ...grantFilter, pageSize: '1' }),
    enabled: Boolean(grantFilter) && canViewGrants,
    retry: false,
  });
  const changes = (row?.changes ?? []).map((change, index) => ({
    ...change,
    // 同一审批可有多条子项差异，按审计快照的顺序标识。
    rowKey: `${change.field}-${index}`,
  }));

  /**
   * 时间范围按上海业务自然日提交，服务端转换为 UTC 边界。
   */
  function search(values: {
    actor?: string;
    actionCode?: string;
    target?: string;
    outcome?: string;
    period?: { format: (pattern: string) => string }[];
  }) {
    setParams(
      queryString({
        actor: values.actor,
        actionCode: values.actionCode,
        target: values.target,
        outcome: values.outcome,
        fromDate: values.period?.[0]?.format('YYYY-MM-DD'),
        toDate: values.period?.[1]?.format('YYYY-MM-DD'),
        timeZone: values.period ? 'Asia/Shanghai' : undefined,
        pageNum: 1,
        pageSize: params.get('pageSize') ?? 10,
      }),
    );
    form.focusField('actor');
  }

  /**
   * 日期与其他条件一并清空，保留每页条数并返回首个查询输入。
   */
  function reset() {
    form.setFieldsValue({
      actor: undefined,
      actionCode: undefined,
      target: undefined,
      outcome: undefined,
      period: undefined,
    });
    setParams({ pageNum: '1', pageSize: params.get('pageSize') ?? '10' });
    form.focusField('actor');
  }

  // 筛选按操作者、动作、目标、结果和发生时间的表头顺序排列。
  return (
    <>
      <section className="panel query-panel" aria-label="审计查询">
        <Form
          name="audit-query"
          form={form}
          layout="inline"
          onFinish={search}
          initialValues={{
            ...Object.fromEntries(params),
            period:
              params.get('fromDate') && params.get('toDate')
                ? [dayjs(params.get('fromDate')), dayjs(params.get('toDate'))]
                : undefined,
          }}
        >
          <Form.Item name="actor">
            <Input
              aria-label="操作者姓名或登录名"
              placeholder="操作者姓名或登录名"
              prefix={<SearchOutlined />}
              allowClear
            />
          </Form.Item>
          <Form.Item name="actionCode">
            <Select
              aria-label="操作类型"
              placeholder="操作类型"
              showSearch
              optionFilterProp="label"
              allowClear
              options={Object.entries(actionLabels).map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
          <Form.Item name="target">
            <Input aria-label="目标名称或编号" placeholder="目标名称或编号" allowClear />
          </Form.Item>
          <Form.Item name="outcome" className="status-query">
            <Select
              aria-label="操作结果"
              placeholder="全部结果"
              allowClear
              options={[
                { value: 'SUCCESS', label: '成功' },
                { value: 'REJECTED', label: '拒绝' },
                { value: 'FAILED', label: '失败' },
              ]}
            />
          </Form.Item>
          <div className="query-tail">
            <label className="sr-only" htmlFor="audit-start-date">
              操作开始日期
            </label>
            <label className="sr-only" htmlFor="audit-end-date">
              操作结束日期
            </label>
            <Form.Item name="period">
              <DatePicker.RangePicker
                id={{ start: 'audit-start-date', end: 'audit-end-date' }}
                format="YYYY-MM-DD"
                placeholder={['开始日期', '结束日期']}
              />
            </Form.Item>
            <Space>
              <Button htmlType="submit">查询</Button>
              <Button onClick={reset}>重置</Button>
            </Space>
          </div>
        </Form>
      </section>
      <section className="panel records-panel" aria-label="权限审计">
        <RecordTable<AuditRecord>
          error={records.error}
          retry={records.refetch}
          filtered={['actor', 'actionCode', 'target', 'outcome', 'fromDate', 'toDate'].some((key) =>
            params.has(key),
          )}
          onReset={reset}
          rowKey="id"
          dataSource={records.data?.items}
          loading={
            // 窗口获焦会回读原查询，已有审计结果继续可操作。
            records.isPending
          }
          scroll={{ x: 1100, y: '100%' }}
          pagination={{
            current: records.data?.pageNum ?? 1,
            pageSize: records.data?.pageSize ?? 10,
            total: records.data?.total,
            showSizeChanger: true,
            onChange: (pageNum, pageSize) =>
              setParams((previous) => {
                const next = new URLSearchParams(previous);
                next.set('pageNum', String(pageNum));
                next.set('pageSize', String(pageSize));
                next.delete('detail');
                return next;
              }),
          }}
          columns={[
            {
              title: '操作者',
              width: 190,
              render: (_, record) => (
                <div>
                  <span className="cell-text" title={record.actor.displayName ?? '系统'}>
                    {record.actor.displayName ?? '系统'}
                  </span>
                  <div
                    className="table-secondary"
                    title={record.actor.username ?? record.actor.kind}
                  >
                    {record.actor.username ?? record.actor.kind}
                  </div>
                </div>
              ),
            },
            {
              title: '操作类型',
              dataIndex: 'actionCode',
              width: 200,
              ellipsis: true,
              render: (value) => actionLabels[value] ?? '其他操作',
            },
            {
              title: '目标对象',
              dataIndex: 'targetName',
              width: 230,
              ellipsis: true,
              render: (value) => value || '—',
            },
            {
              title: '结果',
              width: 150,
              render: (_, record) =>
                record.approvalEffect ? (
                  // 批准待生效与已生效沿用授权页面的状态样式。
                  <AccessStatus value={record.approvalEffect} />
                ) : (
                  <Tag
                    color={
                      record.outcome === 'SUCCESS'
                        ? 'success'
                        : record.outcome === 'FAILED'
                          ? 'error'
                          : 'warning'
                    }
                  >
                    {auditOutcome(record)}
                  </Tag>
                ),
            },
            { title: '发生时间', dataIndex: 'occurredAt', width: 190, render: displayTime },
            {
              title: '操作',
              className: 'record-actions-cell',
              width: 100,
              fixed: 'right',
              render: (_, record) => (
                <Button
                  type="link"
                  id={`audit-record-${record.id}`}
                  onClick={() => selectDetail(record.id)}
                >
                  查看
                </Button>
              ),
            },
          ]}
        />
      </section>
      {/* 关联申请与审计详情交替打开，收起后移除浮层以恢复列表点击。 */}
      {showRequest && requestId && canViewRequest ? (
        <RequestDrawer
          id={requestId}
          historyOnly={!ownsRequest}
          readOnly
          close={() => {
            // 重开审计抽屉后回到原关联动作。
            returningFromRequest.current = true;
            setShowRequest(false);
          }}
        />
      ) : (
        <Drawer
          open={selected !== null}
          destroyOnHidden
          onClose={closeDetail}
          title="审计记录"
          size="min(600px, calc(100vw - 80px))"
          closable={{ placement: 'end' }}
          className="record-details-drawer"
          afterOpenChange={(open) => {
            // 申请回看退出后，等审计抽屉完成打开再恢复焦点。
            if (open && returningFromRequest.current) {
              returningFromRequest.current = false;
              requestTrigger.current?.focus();
            }
          }}
          footer={
            <DetailFooter close={closeDetail}>
              {requestId && canViewRequest && (
                <Button ref={requestTrigger} onClick={() => setShowRequest(true)}>
                  查看关联申请
                </Button>
              )}
              {canViewGrants && grantFilter && grants.isSuccess && grants.data.total > 0 && (
                <Button
                  onClick={() => {
                    // 带入确切来源，返回时恢复审计的筛选、页码及详情。
                    void navigate(
                      `/iam/grants?${queryString({ ...grantFilter, auditReturn: params.toString() })}`,
                    );
                  }}
                >
                  查看授权来源
                </Button>
              )}
            </DetailFooter>
          }
        >
          <ReadState error={detail.error} retry={detail.refetch} loading={detail.isPending}>
            <Descriptions
              column={2}
              layout="vertical"
              colon={false}
              items={[
                { key: 'id', label: '审计编号', children: detail.data?.id },
                { key: 'time', label: '发生时间', children: displayTime(detail.data?.occurredAt) },
                {
                  key: 'actor',
                  label: '操作者',
                  children: detail.data?.actor.displayName ?? '系统',
                },
                { key: 'username', label: '登录名', children: detail.data?.actor.username ?? '—' },
                {
                  key: 'action',
                  label: '操作类型',
                  children: detail.data && (actionLabels[detail.data.actionCode] ?? '其他操作'),
                },
                { key: 'target', label: '目标', children: detail.data?.targetName },
                {
                  key: 'outcome',
                  label: '结果',
                  children: detail.data && auditOutcome(detail.data),
                },
                { key: 'target-id', label: '目标编号', children: detail.data?.targetId },
                {
                  key: 'security-version',
                  label: '权限版本',
                  children: detail.data?.securityVersion ?? '—',
                },
                { key: 'reason', label: '原因', children: detail.data?.reason || '—', span: 2 },
                {
                  key: 'command',
                  label: '命令编号',
                  children: detail.data?.commandId ?? '—',
                  span: 2,
                },
              ]}
            />
            <RecordTable<Change & { rowKey: string }>
              className="full-text-table"
              rowKey="rowKey"
              dataSource={changes}
              pagination={false}
              columns={[
                { title: '字段', dataIndex: 'label', width: 130 },
                {
                  title: '之前',
                  dataIndex: 'before',
                  render: (value, change) => displayChangeValue(value, change.field, change.label),
                },
                {
                  title: '之后',
                  dataIndex: 'after',
                  render: (value, change) => {
                    // 仅转换确定的办理编码，源名称和一般说明沿用历史原文。
                    if (typeof value === 'string') {
                      if (change.field === 'item') return accessState[value] ?? value;
                      if (change.field === 'decision') return decisionLabels[value] ?? value;
                    }
                    return displayChangeValue(value, change.field, change.label);
                  },
                },
              ]}
            />
          </ReadState>
        </Drawer>
      )}
    </>
  );
}
