import { RecordTable } from '../../shared/RecordTable';
import { Button, DatePicker, Descriptions, Drawer, Form, Input, Select, Space, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useEffect, useRef, useState } from 'react';
import dayjs from 'dayjs';
import { api, queryString } from '../../shared/api';
import { DetailFooter, displayTime, ReadState } from '../../shared/Records';
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
}

/**
 * 审计只读查询与详情回看，操作入口不提供修改或删除。
 */
export function AuditPage() {
  const [params, setParams] = useSearchParams();

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

  const [selected, setSelected] = useState<string | null>(null);

  const detailTrigger = useRef<HTMLElement | null>(null);

  /**
   * 审计抽屉保持挂载，关闭后显式恢复到原查看按钮。
   */
  function closeDetail() {
    setSelected(null);
    requestAnimationFrame(() => detailTrigger.current?.focus({ preventScroll: true }));
  }

  const records = useQuery({
    queryKey: ['audit', params.toString()],
    queryFn: () => api<Page<AuditRecord>>(`/iam/audit-records?${params}`),
  });

  const detail = useQuery({
    queryKey: ['audit-detail', selected],
    queryFn: () => api<AuditRecord>(`/iam/audit-records/${selected}`),
    enabled: selected !== null,
  });

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
            <Input aria-label="操作者姓名或登录名" placeholder="操作者姓名或登录名" allowClear />
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
          loading={records.isFetching}
          scroll={{ x: 1100, y: '100%' }}
          pagination={{
            current: records.data?.pageNum ?? 1,
            pageSize: records.data?.pageSize ?? 10,
            total: records.data?.total,
            showSizeChanger: true,
            showQuickJumper: true,
            onChange: (pageNum, pageSize) =>
              setParams((previous) => {
                const next = new URLSearchParams(previous);
                next.set('pageNum', String(pageNum));
                next.set('pageSize', String(pageSize));
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
            { title: '目标对象', dataIndex: 'targetName', width: 230, ellipsis: true },
            {
              title: '结果',
              width: 100,
              render: (_, record) => (
                <Tag
                  color={
                    record.outcome === 'SUCCESS'
                      ? 'success'
                      : record.outcome === 'FAILED'
                        ? 'error'
                        : 'warning'
                  }
                >
                  {{ SUCCESS: '成功', REJECTED: '拒绝', FAILED: '失败' }[record.outcome]}
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
                  onClick={(event) => {
                    // 保留本条记录的查看入口，关闭后不丢失键盘位置。
                    detailTrigger.current = event.currentTarget;
                    setSelected(record.id);
                  }}
                >
                  查看
                </Button>
              ),
            },
          ]}
        />
      </section>
      <Drawer
        open={selected !== null}
        onClose={closeDetail}
        title="审计记录"
        size="min(600px, calc(100vw - 80px))"
        closable={{ placement: 'end' }}
        className="record-details-drawer"
        footer={<DetailFooter close={closeDetail} />}
      >
        <ReadState error={detail.error} retry={detail.refetch} loading={detail.isPending}>
          <Descriptions
            column={2}
            layout="vertical"
            colon={false}
            items={[
              { key: 'id', label: '审计编号', children: detail.data?.id },
              { key: 'time', label: '发生时间', children: displayTime(detail.data?.occurredAt) },
              { key: 'actor', label: '操作者', children: detail.data?.actor.displayName ?? '系统' },
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
                children:
                  detail.data &&
                  ({ SUCCESS: '成功', REJECTED: '拒绝', FAILED: '失败' }[detail.data.outcome] ??
                    detail.data.outcome),
              },
              { key: 'target-id', label: '目标编号', children: detail.data?.targetId },
              { key: 'reason', label: '原因', children: detail.data?.reason || '—', span: 2 },
              {
                key: 'command',
                label: '命令编号',
                children: detail.data?.commandId ?? '—',
                span: 2,
              },
            ]}
          />
          <RecordTable<Change>
            className="full-text-table"
            rowKey="field"
            dataSource={detail.data?.changes}
            pagination={false}
            columns={[
              { title: '字段', dataIndex: 'label', width: 130 },
              { title: '之前', dataIndex: 'before', render: (value) => value || '—' },
              { title: '之后', dataIndex: 'after', render: (value) => value || '—' },
            ]}
          />
        </ReadState>
      </Drawer>
    </>
  );
}
