import { Button, Descriptions, Form, Input, Modal, Space, Tabs, Typography } from 'antd';
import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { DetailDialog } from '../../shared/DetailDialog';
import { allowed, useSession } from '../../shared/session';
import { displayTime } from '../../shared/Records';
import { DatasourcePage } from '../datasource/DatasourcePage';
import {
  AccessEditor,
  AccessGrid,
  AccessPerson,
  AccessSelect,
  AccessStatus,
  personLabel,
  previewAccess,
  saveAccess,
  statusOptions,
} from './AccessControls';
import type { OutputRule, Responsibility } from './types';

/**
 * 数据源接入与审批责任使用同一管理入口的两个页签。
 */
export function DatasourceManagementPage() {
  const [params, setParams] = useSearchParams();
  const active = params.get('section') === 'responsibilities' ? 'responsibilities' : 'overview';
  const history = useRef(new Map<string, string>());
  const tabs = (
    <Tabs
      className="access-grid-tabs"
      activeKey={active}
      items={[
        { key: 'overview', label: '数据源概况' },
        { key: 'responsibilities', label: '审批责任' },
      ]}
      onChange={(key) => {
        // 每个页签恢复自己上次已应用的筛选条件及页码。
        history.current.set(active, params.toString());
        const next = new URLSearchParams(history.current.get(key));
        if (key === 'responsibilities') next.set('section', key);
        else next.delete('section');
        setParams(next, { replace: true });
      }}
    />
  );
  return active === 'overview' ? (
    <DatasourcePage header={tabs} />
  ) : (
    <ResponsibilitiesPage header={tabs} />
  );
}

/**
 * 管理员在同一源清单内配置两位具名责任人。
 */
function ResponsibilitiesPage({ header }: { header: ReactNode }) {
  const session = useSession();
  const [editing, setEditing] = useState<Responsibility>();
  const [detail, setDetail] = useState<Responsibility>();
  return (
    <>
      {/* 审批责任查询与当前配置一览。 */}
      <AccessGrid<Responsibility>
        path="/responsibilities"
        header={header}
        rowKey={(row) => row.source.id}
        filters={[
          { name: 'keyword', placeholder: '数据源名称' },
          { name: 'applicant', placeholder: '主审批人姓名或登录名' },
          { name: 'handler', placeholder: '替代负责人姓名或登录名' },
          {
            name: 'state',
            placeholder: '全部状态',
            options: statusOptions(['UNCONFIGURED', 'READY', 'REVIEW']),
          },
        ]}
        empty="暂无可配置审批责任的数据源"
        columns={[
          {
            title: '数据源',
            width: 210,
            render: (_, row) => (
              <Button
                type="link"
                className="record-link"
                title={row.source.name}
                onClick={() => setDetail(row)}
              >
                {row.source.name}
              </Button>
            ),
          },
          {
            title: '主审批人',
            width: 160,
            render: (_, row) => (row.primary ? <AccessPerson person={row.primary} /> : '未指定'),
          },
          {
            title: '替代负责人',
            width: 160,
            render: (_, row) =>
              row.alternate ? <AccessPerson person={row.alternate} /> : '未指定',
          },
          { title: '责任状态', width: 120, render: (_, row) => <AccessStatus value={row.state} /> },
          { title: '更新时间', width: 160, render: (_, row) => displayTime(row.updatedAt) },
          ...(allowed(session, 'sources.approval-config')
            ? [
                {
                  title: '操作',
                  className: 'record-actions-cell',
                  width: 116,
                  fixed: 'right' as const,
                  render: (_: unknown, row: Responsibility) => (
                    <Button type="link" onClick={() => setEditing(row)}>
                      配置责任
                    </Button>
                  ),
                },
              ]
            : []),
        ]}
      />
      {detail && (
        <DetailDialog
          title="数据源审批责任"
          width={520}
          close={() => setDetail(undefined)}
          actions={
            allowed(session, 'sources.approval-config') && (
              <Button
                type="primary"
                onClick={() => {
                  // 同一条记录转到责任编辑，保留一个办理界面。
                  setDetail(undefined);
                  setEditing(detail);
                }}
              >
                配置审批责任
              </Button>
            )
          }
        >
          <Descriptions
            className="responsibility-detail"
            colon={false}
            column={1}
            items={[
              { key: 'source', label: '数据源', children: detail.source.name },
              { key: 'state', label: '责任状态', children: <AccessStatus value={detail.state} /> },
              { key: 'primary', label: '主审批人', children: personLabel(detail.primary) },
              { key: 'alternate', label: '替代负责人', children: personLabel(detail.alternate) },
              { key: 'updated', label: '更新时间', children: displayTime(detail.updatedAt) },
            ]}
          />
        </DetailDialog>
      )}
      {editing && <ResponsibilityEditor row={editing} close={() => setEditing(undefined)} />}
    </>
  );
}

/**
 * 两位责任人必须明确不同，核对时显示新旧角色安排。
 */
export function ResponsibilityEditor({ row, close }: { row: Responsibility; close: () => void }) {
  const [form] = Form.useForm<{ primaryUserId: string; alternateUserId: string }>();
  const [people, setPeople] = useState<Record<string, string>>({
    ...(row.primary ? { [row.primary.id]: personLabel(row.primary) } : {}),
    ...(row.alternate ? { [row.alternate.id]: personLabel(row.alternate) } : {}),
  });
  return (
    <AccessEditor
      title="配置审批责任"
      successMessage="审批责任已更新"
      context={
        <div className="responsibility-source">
          <span>数据源</span>
          <strong>{row.source.name}</strong>
        </div>
      }
      form={form}
      initialValues={{ primaryUserId: row.primary?.id, alternateUserId: row.alternate?.id }}
      close={close}
      preview={(values) =>
        previewAccess(`/responsibilities/${row.source.id}`, {
          ...values,
          expectedVersion: row.version,
        })
      }
      confirm={(_values, verified) => (
        <Descriptions
          column={1}
          items={[
            ...(verified?.changes ?? []).map((change) => ({
              key: change.field,
              label: change.label,
              children: `${change.before ?? '—'} → ${change.after}`,
            })),
          ]}
        />
      )}
      save={(values, command, reviewToken) =>
        saveAccess(
          `/responsibilities/${row.source.id}`,
          'PUT',
          { ...values, expectedVersion: row.version, reviewToken },
          'sources.approval-config',
          command,
        )
      }
    >
      {/* 仅展示当前可审批账号。 */}
      <Form.Item
        name="primaryUserId"
        label="主审批人"
        rules={[{ required: true, message: '请选择主审批人' }]}
      >
        <AccessSelect
          kind="approver"
          label="选择主审批人"
          selected={Object.entries(people).map(([value, label]) => ({ value, label }))}
          remember={(option) =>
            setPeople((before) => ({ ...before, [option.value]: option.label }))
          }
        />
      </Form.Item>
      <Form.Item
        name="alternateUserId"
        label="替代负责人"
        dependencies={['primaryUserId']}
        rules={[
          { required: true, message: '请选择替代负责人' },
          {
            validator: (_, value?: string) =>
              value && value === form.getFieldValue('primaryUserId')
                ? Promise.reject(new Error('请选择不同的替代负责人'))
                : Promise.resolve(),
          },
        ]}
      >
        <AccessSelect
          kind="approver"
          label="选择替代负责人"
          selected={Object.entries(people).map(([value, label]) => ({ value, label }))}
          remember={(option) =>
            setPeople((before) => ({ ...before, [option.value]: option.label }))
          }
        />
      </Form.Item>
    </AccessEditor>
  );
}

/**
 * 输出限制按规则维护，列表省略可在详情完整阅读的正文。
 */
export function OutputRulesPage() {
  const session = useSession();
  const [editing, setEditing] = useState<OutputRule | 'new'>();
  const [detail, setDetail] = useState<OutputRule>();
  return (
    <>
      <AccessGrid<OutputRule>
        path="/rules"
        rowKey="id"
        filters={[
          { name: 'ruleName', placeholder: '规则名称' },
          { name: 'ruleContent', placeholder: '返回限制内容' },
        ]}
        empty="暂无输出限制规则"
        action={
          allowed(session, 'output-rules.create') && (
            <Button type="primary" onClick={() => setEditing('new')}>
              新增规则
            </Button>
          )
        }
        columns={[
          {
            title: '规则名称',
            width: 260,
            render: (_, row) => (
              <Button type="link" className="record-link" onClick={() => setDetail(row)}>
                {row.name}
              </Button>
            ),
          },
          { title: '返回限制内容', dataIndex: 'content', width: 360, ellipsis: true },
          { title: '更新时间', width: 160, render: (_, row) => displayTime(row.updatedAt) },
          ...(allowed(session, 'output-rules.edit')
            ? [
                {
                  title: '操作',
                  className: 'record-actions-cell',
                  width: 88,
                  fixed: 'right' as const,
                  render: (_: unknown, row: OutputRule) => (
                    <Button type="link" onClick={() => setEditing(row)}>
                      编辑
                    </Button>
                  ),
                },
              ]
            : []),
        ]}
      />
      {detail && (
        <Modal
          centered
          open
          title={detail.name}
          footer={null}
          onCancel={() => setDetail(undefined)}
        >
          <p className="detail-text">{detail.content}</p>
        </Modal>
      )}
      {editing && (
        <RuleEditor
          row={editing === 'new' ? undefined : editing}
          close={() => setEditing(undefined)}
        />
      )}
    </>
  );
}

/**
 * 核对修改后的限制内容及共享变更范围。
 */
function RuleEditor({ row, close }: { row?: OutputRule; close: () => void }) {
  const [form] = Form.useForm<{ name: string; content: string }>();
  const [params, setParams] = useSearchParams();
  const operation = row ? 'output-rules.edit' : 'output-rules.create';
  return (
    <AccessEditor
      title={row ? '编辑规则' : '新增规则'}
      successMessage={row ? '输出限制规则已更新' : '输出限制规则已新增'}
      form={form}
      initialValues={row ? { name: row.name, content: row.content } : undefined}
      close={close}
      preview={
        row
          ? (values) =>
              previewAccess(`/rules/${row.id}`, { ...values, expectedVersion: row.version })
          : undefined
      }
      confirm={(values) => (
        <Space orientation="vertical">
          {/* 新规则全文以及改动前后的明确比较。 */}
          <Typography.Text strong>{values.name}</Typography.Text>
          {row && row.content !== values.content && (
            <Typography.Text type="secondary" className="detail-text">
              {row.content}
            </Typography.Text>
          )}
          <Typography.Paragraph className="detail-text">{values.content}</Typography.Paragraph>
        </Space>
      )}
      save={(values, command, reviewToken) =>
        saveAccess(
          row ? `/rules/${row.id}` : '/rules',
          row ? 'PUT' : 'POST',
          { ...values, expectedVersion: row?.version, reviewToken },
          operation,
          command,
        )
      }
      done={() => {
        // 新规则插入第一页，清空可能遮挡结果的筛选。
        if (!row && params.size) setParams({}, { replace: true });
      }}
    >
      <Form.Item name="name" label="规则名称" rules={[{ required: true, whitespace: true }]}>
        <Input maxLength={80} autoFocus />
      </Form.Item>
      <Form.Item name="content" label="限制内容" rules={[{ required: true, whitespace: true }]}>
        <Input.TextArea maxLength={2000} rows={5} />
      </Form.Item>
    </AccessEditor>
  );
}
