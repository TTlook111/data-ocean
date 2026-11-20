import { RecordTable } from '../../shared/RecordTable';
import { MoreFilters } from '../../shared/MoreFilters';
import { ActionMenuButton } from '../../shared/ActionMenuButton';
import { App, Avatar, Button, Descriptions, Drawer, Form, Input, Select, Space } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useEffect, useRef, useState } from 'react';
import { api, queryString } from '../../shared/api';
import {
  DetailFooter,
  displayTime,
  readErrorMessage,
  ReadState,
  Status,
  statuses,
} from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { PersonEditor, RoleAssignmentDialog, UserActionDialog } from './UserDialogs';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Department, Page, Person } from './types';
import { AccessScopes } from '../access/ScopePages';

/**
 * 人员列表保留已应用条件与页码，详情不替换原列表上下文。
 */
export function UsersPage() {
  const session = useSession();

  const { message } = App.useApp();

  const [params, setParams] = useSearchParams();

  const [form] = Form.useForm();
  const [moreOpen, setMoreOpen] = useState(false);

  const [detailId, setDetailId] = useState<string | null>(null);

  const [editing, setEditing] = useState<Person | 'new' | null>(null);

  const [assignment, setAssignment] = useState<Person | null>(null);

  // 详情分配角色后回到原按钮，抽屉保持挂载以保留阅读位置。
  const assignmentTrigger = useRef<HTMLElement | null>(null);

  const [action, setAction] = useState<{ person: Person; name: string } | null>(null);

  const query = params.toString();

  const displayName = params.get('displayName') ?? undefined;
  const username = params.get('username') ?? undefined;
  const departmentId = params.get('departmentId') ?? undefined;
  const displayStatus = params.get('displayStatus') ?? undefined;

  useEffect(() => {
    // 浏览器回退恢复已应用条件；单纯翻页不覆盖尚未提交的查询输入。
    form.setFieldsValue({ displayName, username, departmentId, displayStatus });
  }, [form, displayName, username, departmentId, displayStatus]);

  const records = useQuery({
    queryKey: ['users', query],
    queryFn: () => api<Page<Person>>(`/iam/users?${query}`),
  });

  const departmentTree = useQuery({
    queryKey: ['department-tree'],
    queryFn: () => api<Department[]>('/iam/departments/tree'),
    enabled: allowed(session, 'departments.view'),
  });

  /**
   * 查询字段按姓名、登录名、部门、状态的表头顺序应用。
   */
  function search(values: Record<string, string>) {
    setMoreOpen(false);
    setParams(
      queryString({
        ...values,
        departmentId: allowed(session, 'departments.view')
          ? form.getFieldValue('departmentId')
          : undefined,
        pageNum: 1,
        pageSize: params.get('pageSize') ?? 10,
      }),
    );
    form.focusField('displayName');
  }

  /**
   * 清除已应用条件并恢复首个输入焦点。
   */
  function reset() {
    setMoreOpen(false);
    form.resetFields();
    form.setFieldsValue({
      displayName: undefined,
      username: undefined,
      departmentId: undefined,
      displayStatus: undefined,
    });
    setParams({ pageNum: '1', pageSize: params.get('pageSize') ?? '10' });
    form.focusField('displayName');
  }

  /**
   * 每次编辑先读取最新详情，不把列表摘要当完整表单。
   */
  async function edit(person: Person) {
    try {
      setEditing(await api<Person>(`/iam/users/${person.id}`));
    } catch (failure) {
      // 详情读取失败保留列表上下文，避免入口无响应或打开不完整表单。
      message.error(
        readErrorMessage(failure instanceof Error ? failure : new Error('读取人员失败')),
      );
    }
  }

  /**
   * 行与详情共用动作资格，实际执行仍由服务端检查。
   */
  function rowActions(person: Person, inDetail = false) {
    const active = person.displayStatus !== 'ARCHIVED';

    const extras = [
      { key: 'transfer', label: '调整归属', code: 'users.transfer', visible: active },
      { key: 'password-reset', label: '重置密码', code: 'users.password', visible: active },
      {
        key: 'disable',
        label: '停用人员',
        code: 'users.disable',
        visible: active && person.displayStatus !== 'DISABLED',
        danger: true,
      },
      {
        key: 'enable',
        label: '复核并恢复',
        code: 'users.enable',
        visible: person.displayStatus === 'DISABLED',
      },
      {
        key: 'archive',
        label: '归档人员',
        code: 'users.archive',
        visible: person.displayStatus === 'DISABLED',
        danger: true,
      },
      {
        key: 'protected-roles',
        label: '受保护角色分配',
        code: 'roles.protected',
        visible: active && allowed(session, 'users.assign'),
      },
      {
        key: 'recover',
        label: '受保护权限恢复',
        code: 'users.recover',
        visible: active && person.displayStatus !== 'DISABLED',
      },
    ].filter((item) => item.visible && allowed(session, item.code));

    const more =
      extras.length === 1 ? (
        <Button
          type={inDetail ? 'default' : 'link'}
          danger={extras[0].danger}
          onClick={() => {
            // 详情办理先返回列表上下文，不叠加两层对象编辑浮层。
            if (inDetail) setDetailId(null);
            setAction({ person, name: extras[0].key });
          }}
        >
          {extras[0].label}
        </Button>
      ) : extras.length > 1 ? (
        <ActionMenuButton
          type={inDetail ? 'default' : 'link'}
          placement={inDetail ? 'topLeft' : 'bottomLeft'}
          menu={{
            items: extras,
            onClick: ({ key }) => {
              // 低频动作保持同一对象及当前版本，离开回看后再填写提案。
              if (inDetail) setDetailId(null);
              setAction({ person, name: key });
            },
          }}
        >
          更多
        </ActionMenuButton>
      ) : null;

    // 查看型详情沿用原型的左侧低频菜单和右侧常用操作。
    if (inDetail) {
      return (
        <div className="detail-footer">
          <span>{more}</span>
          <Space>
            {active && allowed(session, 'users.assign') && (
              <Button
                data-person-action="assign"
                onClick={(event) => {
                  // 只暂时隐藏详情；保存、取消或 Esc 后均恢复原人员与阅读上下文。
                  assignmentTrigger.current = event.currentTarget;
                  setAssignment(person);
                }}
              >
                分配角色
              </Button>
            )}
            {active && allowed(session, 'users.edit') ? (
              <Button
                type="primary"
                onClick={() => {
                  // 编辑前再次读取当前版本，避免将历史详情作为新提案。
                  setDetailId(null);
                  void edit(person);
                }}
              >
                编辑人员
              </Button>
            ) : (
              <Button onClick={() => setDetailId(null)}>关闭</Button>
            )}
          </Space>
        </div>
      );
    }

    return (
      <Space size={8} className="row-actions">
        {active && allowed(session, 'users.edit') && (
          <Button type="link" onClick={() => edit(person)}>
            编辑
          </Button>
        )}
        {active && allowed(session, 'users.assign') && (
          <Button type="link" onClick={() => setAssignment(person)}>
            分配角色
          </Button>
        )}
        {more}
      </Space>
    );
  }

  /**
   * 展开树用于过滤选择，显示完整部门名称而不引入层级授权。
   */
  function flatten(nodes: Department[]): Department[] {
    return nodes.flatMap((node) => [node, ...flatten(node.children ?? [])]);
  }

  // 查询区、结果表格和分页共享工作区，状态反馈不隐藏表头。
  return (
    <>
      <section className="panel query-panel compact-query" aria-label="人员查询">
        <Form
          name="users-query"
          form={form}
          layout="inline"
          onFinish={search}
          initialValues={Object.fromEntries(params)}
        >
          <Form.Item name="displayName">
            <Input aria-label="姓名" placeholder="姓名" prefix={<SearchOutlined />} allowClear />
          </Form.Item>
          <Form.Item name="username">
            <Input aria-label="登录名" placeholder="登录名" allowClear />
          </Form.Item>
          {/* 常用查询保持一行，低频字段仍可按原规则提交。 */}
          <Form.Item name="displayStatus" className="status-query">
            <Select
              aria-label="状态"
              placeholder="全部状态"
              allowClear
              options={Object.entries(statuses).map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
          <div className="query-tail-actions">
            <Space>
              {allowed(session, 'departments.view') && (
                <MoreFilters
                  open={moreOpen}
                  setOpen={setMoreOpen}
                  count={Number(params.has('departmentId'))}
                >
                  <Form.Item name="departmentId">
                    <Select
                      aria-label="所属部门"
                      placeholder="所属部门"
                      allowClear
                      showSearch
                      optionFilterProp="label"
                      options={flatten(departmentTree.data ?? []).map((item) => ({
                        label: item.name,
                        value: item.id,
                      }))}
                    />
                  </Form.Item>
                </MoreFilters>
              )}
              <Button htmlType="submit">查询</Button>
              <Button onClick={reset}>重置</Button>
            </Space>
          </div>
        </Form>
        {allowed(session, 'users.create') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
            新增人员
          </Button>
        )}
      </section>
      <section className="panel records-panel" aria-label="人员列表">
        <RecordTable<Person>
          error={records.error}
          retry={records.refetch}
          filtered={['displayName', 'username', 'departmentId', 'displayStatus'].some((key) =>
            params.has(key),
          )}
          onReset={reset}
          rowKey="id"
          loading={records.isFetching}
          dataSource={records.data?.items}
          scroll={{ x: 1040, y: '100%' }}
          pagination={{
            current: records.data?.pageNum ?? Number(params.get('pageNum') ?? 1),
            pageSize: records.data?.pageSize ?? Number(params.get('pageSize') ?? 10),
            total: records.data?.total,
            showTotal: (total) => `共 ${total} 条`,
            showSizeChanger: true,
            onChange: (page, size) =>
              setParams((previous) => {
                const next = new URLSearchParams(previous);
                next.set('pageNum', String(page));
                next.set('pageSize', String(size));
                return next;
              }),
          }}
          columns={[
            {
              title: '人员',
              width: 210,
              render: (_, person) => (
                <div>
                  <Button
                    type="link"
                    className="cell-title"
                    title={person.displayName}
                    onClick={() => setDetailId(person.id)}
                  >
                    {person.displayName}
                  </Button>
                  <span className="table-secondary" title={person.username}>
                    {person.username}
                  </span>
                </div>
              ),
            },
            {
              title: '部门',
              width: 200,
              render: (_, person) => (
                <div>
                  <span className="cell-text" title={person.primaryDepartment?.name}>
                    {person.primaryDepartment?.name ?? '—'}
                  </span>
                  {person.secondaryDepartments.length > 0 && (
                    <span
                      className="table-secondary"
                      title={person.secondaryDepartments.map((item) => item.name).join('、')}
                    >
                      兼任：{person.secondaryDepartments.map((item) => item.name).join('、')}
                    </span>
                  )}
                </div>
              ),
            },
            {
              title: '个人角色',
              width: 210,
              ellipsis: true,
              render: (_, person) => person.roles.map((item) => item.name).join('、') || '未分配',
            },
            {
              title: '状态',
              dataIndex: 'displayStatus',
              width: 120,
              render: (value) => <Status value={value} />,
            },
            { title: '更新时间', dataIndex: 'updatedAt', width: 185, render: displayTime },
            {
              title: '操作',
              className: 'record-actions-cell',
              width: 240,
              fixed: 'right',
              render: (_, person) => rowActions(person),
            },
          ]}
        />
      </section>
      {detailId && (
        <PersonDetailsDrawer
          id={detailId}
          open={!assignment}
          close={() => setDetailId(null)}
          actions={rowActions}
          afterOpenChange={(open) => {
            // 恢复抽屉完成后再交回焦点，避免被弹窗的退出动画抢走。
            if (open && assignmentTrigger.current) {
              requestAnimationFrame(() => {
                // 保存刷新可能替换底栏节点，等抽屉原生聚焦结束后定位当前分配入口。
                const target = assignmentTrigger.current?.isConnected
                  ? assignmentTrigger.current
                  : document.querySelector<HTMLElement>(
                      '.ant-drawer-open [data-person-action="assign"]',
                    );
                target?.focus({ preventScroll: true });
                assignmentTrigger.current = null;
              });
            }
          }}
        />
      )}
      {editing && (
        <PersonEditor
          key={editing === 'new' ? 'new' : editing.id}
          person={editing === 'new' ? null : editing}
          close={() => setEditing(null)}
          created={(id) => {
            // 创建对象不能被旧筛选遮住，回到首屏并直接打开其真实详情。
            setParams({ pageNum: '1', pageSize: params.get('pageSize') ?? '10' });
            setDetailId(id);
          }}
        />
      )}
      {assignment && (
        <RoleAssignmentDialog
          key={`${assignment.id}:${assignment.version}`}
          person={assignment}
          close={() => setAssignment(null)}
        />
      )}
      {action && (
        <UserActionDialog
          key={`${action.person.id}:${action.name}:${action.person.version}`}
          person={action.person}
          action={action.name}
          close={() => setAction(null)}
        />
      )}
    </>
  );
}

/**
 * 人员列表与部门成员复用同一详情，关闭后回到原操作上下文。
 */
export function PersonDetailsDrawer({
  id,
  open = true,
  close,
  actions,
  afterOpenChange,
}: {
  /** 当前回看人员的稳定标识。 */
  id: string;
  /** 分配角色时暂时隐藏抽屉，保留详情与滚动位置。 */
  open?: boolean;
  /** 关闭人员详情并回到原列表。 */
  close: () => void;
  /** 当前账号获准执行的人员动作。 */
  actions?: (person: Person, inDetail?: boolean) => React.ReactNode;
  /** 抽屉重新打开后恢复原动作入口焦点。 */
  afterOpenChange?: (open: boolean) => void;
}) {
  useReturnFocus();

  const detail = useQuery({
    queryKey: ['user', id],
    queryFn: () => api<Person>(`/iam/users/${id}`),
  });

  // 详情只展示服务端当前事实，归档时展示已保存的身份快照。
  return (
    <Drawer
      open={open}
      afterOpenChange={afterOpenChange}
      title="人员详情"
      size="min(600px, calc(100vw - 80px))"
      closable={{ placement: 'end' }}
      className="record-details-drawer"
      onClose={close}
      footer={
        !detail.error && detail.data && actions ? (
          actions(detail.data, true)
        ) : (
          <DetailFooter close={close} />
        )
      }
    >
      <ReadState error={detail.error} retry={detail.refetch} loading={detail.isPending}>
        <div className="detail-heading person-overview">
          <Avatar size={48}>{detail.data?.displayName.slice(0, 1)}</Avatar>
          <h2>{detail.data?.displayName}</h2>
          {detail.data && <Status value={detail.data.displayStatus} />}
        </div>
        <Descriptions
          column={2}
          layout="vertical"
          colon={false}
          items={[
            { key: 'username', label: '登录名', children: detail.data?.username },
            {
              key: 'primary',
              label: '主部门',
              children: detail.data?.primaryDepartment?.name ?? '—',
            },
            {
              key: 'secondary',
              label: '兼任部门',
              children:
                detail.data?.secondaryDepartments.map((item) => item.name).join('、') || '—',
              span: 2,
            },
            { key: 'phone', label: '手机号', children: detail.data?.phone || '—' },
            { key: 'email', label: '邮箱', children: detail.data?.email || '—' },
            {
              key: 'updated',
              label: '更新时间',
              children: displayTime(detail.data?.updatedAt),
              span: 2,
            },
          ]}
        />
        <div className="section-heading">
          <h3>个人角色</h3>
        </div>
        <p>{detail.data?.roles.map((role) => role.name).join('、') || '未分配角色'}</p>
        <div className="section-heading">
          <h3>数据访问来源</h3>
        </div>
        {detail.data && <AccessScopes userId={detail.data.id} />}
        {detail.data?.managementNote && (
          <>
            <div className="section-heading">
              <h3>备注</h3>
            </div>
            <p className="detail-text">{detail.data.managementNote}</p>
          </>
        )}
      </ReadState>
    </Drawer>
  );
}
