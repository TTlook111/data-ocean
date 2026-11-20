import { MoreFilters } from '../../shared/MoreFilters';
import { RecordTable } from '../../shared/RecordTable';
import { DepartmentAccessPanel } from '../access/ScopePages';
import { ActionMenuButton } from '../../shared/ActionMenuButton';
import {
  App,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  Segmented,
  Select,
  Space,
  Tabs,
  Tree,
} from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { allowed, useSession } from '../../shared/session';
import { readErrorMessage, ReadState, Status, statuses } from '../../shared/Records';
import type { Department, Membership, Page, Person } from './types';
import { DepartmentAction, DepartmentEditor, flattenDepartments } from './DepartmentDialogs';
import { UserActionDialog } from './UserDialogs';
import { PersonDetailsDrawer } from './UsersPage';

/**
 * 承接原型左侧组织架构、右侧摘要和三个内容页签。
 */
export function DepartmentsPage() {
  const session = useSession();

  const { message } = App.useApp();

  const [params, setParams] = useSearchParams();

  const [treeKeyword, setTreeKeyword] = useState('');

  const [memberForm] = Form.useForm();
  const [moreMemberFiltersOpen, setMoreMemberFiltersOpen] = useState(false);

  const displayName = params.get('displayName') ?? undefined;
  const username = params.get('username') ?? undefined;
  const displayStatus = params.get('displayStatus') ?? undefined;

  useEffect(() => {
    // 回退到部门成员查询时恢复已应用条件，不将初次进入的值带给其他部门。
    memberForm.setFieldsValue({ displayName, username, displayStatus });
  }, [memberForm, displayName, username, displayStatus]);

  const [editor, setEditor] = useState<{ department: Department | null; parentId?: string } | null>(
    null,
  );

  const [action, setAction] = useState<{
    name: string;
    member?: { person: Person; relation: Membership };
  } | null>(null);

  const [detailId, setDetailId] = useState<string | null>(null);

  const [transfer, setTransfer] = useState<Person | null>(null);

  const fullTree = useQuery({
    queryKey: ['department-tree'],
    queryFn: () => api<Department[]>('/iam/departments/tree'),
  });

  const filteredTree = useQuery({
    queryKey: ['department-tree-filter', treeKeyword],
    queryFn: () => api<Department[]>(`/iam/departments/tree?${queryString({ name: treeKeyword })}`),
  });

  const selected = params.get('department') ?? fullTree.data?.[0]?.id;

  const tab = params.get('tab') ?? 'members';

  const detail = useQuery({
    queryKey: ['department', selected],
    queryFn: () => api<Department>(`/iam/departments/${selected}`),
    enabled: Boolean(selected),
  });

  const memberParams = new URLSearchParams(params);
  memberParams.delete('department');
  memberParams.delete('tab');
  memberParams.delete('childPage');
  memberParams.delete('childPageSize');

  const members = useQuery({
    queryKey: ['department-members', selected, memberParams.toString()],
    queryFn: () => api<Page<Person>>(`/iam/departments/${selected}/members?${memberParams}`),
    enabled: Boolean(selected) && tab === 'members',
  });

  const children = useQuery({
    queryKey: [
      'department-children',
      selected,
      params.get('childPage') ?? '1',
      params.get('childPageSize') ?? '10',
    ],
    queryFn: () =>
      api<Page<Department>>(
        `/iam/departments/${selected}/children?${queryString({ pageNum: params.get('childPage') ?? 1, pageSize: params.get('childPageSize') ?? 10 })}`,
      ),
    enabled: Boolean(selected) && tab === 'children',
  });

  const department = detail.data;

  /**
   * 重置成员条件但保留当前部门、查看范围和每页条数。
   */
  function resetMembers() {
    setMoreMemberFiltersOpen(false);
    memberForm.setFieldsValue({
      displayName: undefined,
      username: undefined,
      displayStatus: undefined,
    });
    setParams(
      queryString({
        department: selected,
        tab: 'members',
        includeDescendants: params.get('includeDescendants') !== 'false',
        pageNum: 1,
        pageSize: params.get('pageSize') ?? 10,
      }),
    );
    memberForm.focusField('displayName');
  }

  /**
   * 树节点展示真实去重人数，停用或归档以状态代替正常人数。
   */
  function treeNodes(
    nodes: Department[],
  ): { key: string; title: React.ReactNode; children: ReturnType<typeof treeNodes> }[] {
    return nodes.map((node) => ({
      key: node.id,
      title: (
        <span title={node.name} className={node.matched === false ? 'muted' : ''}>
          {node.name}
          <small className="tree-status">
            {node.lifecycle === 'ENABLED'
              ? (node.totalMemberCount ?? '')
              : statuses[node.lifecycle]}
          </small>
        </span>
      ),
      children: treeNodes(node.children ?? []),
    }));
  }

  /**
   * 切换组织后回到成员页签并清除上一组织的局部筛选。
   */
  function selectDepartment(id: string) {
    memberForm.setFieldsValue({
      displayName: undefined,
      username: undefined,
      displayStatus: undefined,
    });
    setParams({ department: id, pageNum: '1' });
  }

  /**
   * 页签与查看范围通过 URL 保留，切换不会触发写入。
   */
  function updateQuery(values: Record<string, string>) {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      Object.entries(values).forEach(([key, value]) => next.set(key, value));
      return next;
    });
  }

  /**
   * 编辑子部门时读取其完整当前资料及版本。
   */
  async function editDepartment(id: string) {
    try {
      setEditor({ department: await api<Department>(`/iam/departments/${id}`) });
    } catch (failure) {
      // 不用树节点摘要冒充完整资料，读取失败时保留当前部门。
      message.error(
        readErrorMessage(failure instanceof Error ? failure : new Error('读取部门失败')),
      );
    }
  }

  const actions = [
    {
      key: 'new-child',
      label: '新增子部门',
      permission: 'departments.create',
      visible: department?.lifecycle === 'ENABLED',
    },
    {
      key: 'edit',
      label: '编辑部门',
      permission: 'departments.edit',
      visible: department?.lifecycle !== 'ARCHIVED',
    },
    {
      key: 'member-transfers',
      label: '迁移直属成员',
      permission: 'departments.transfer-members',
      visible: department?.lifecycle !== 'ARCHIVED',
    },
    {
      key: 'child-transfers',
      label: '迁移下级部门',
      permission: 'departments.transfer-members',
      visible: department?.lifecycle !== 'ARCHIVED',
    },
    {
      key: 'disable',
      label: '停用部门',
      permission: 'departments.disable',
      visible: department?.lifecycle === 'ENABLED',
      danger: true,
    },
    {
      key: 'enable',
      label: '复核并恢复部门',
      permission: 'departments.enable',
      visible: department?.lifecycle === 'DISABLED',
    },
    {
      key: 'archive',
      label: '归档部门',
      permission: 'departments.archive',
      visible: department?.lifecycle === 'DISABLED',
      danger: true,
    },
  ].filter((item) => item.visible && allowed(session, item.permission));

  /**
   * 原型中部门级动作集中在同一个具名菜单。
   */
  function chooseAction(key: string) {
    if (key === 'edit') setEditor({ department: department! });
    else if (key === 'new-child') setEditor({ department: null, parentId: selected });
    else setAction({ name: key });
  }

  /**
   * 主归属提供完整调岗，当前兼任提供针对该关系的移出。
   */
  function memberAction(person: Person) {
    const relation = person.matchingMemberships?.find((item) => item.department.id === selected);
    if (
      relation &&
      !(relation.isPrimary ?? relation.primary) &&
      allowed(session, 'departments.remove-member')
    ) {
      return (
        <Button
          type="link"
          danger
          onClick={() => setAction({ name: 'end', member: { person, relation } })}
        >
          移出兼任
        </Button>
      );
    }
    return allowed(session, 'users.transfer') ? (
      <Button type="link" onClick={() => setTransfer(person)}>
        调岗
      </Button>
    ) : (
      '—'
    );
  }

  // 成员页签的范围切换、查询和表格位置保持原型结构。
  const memberContent = (
    <>
      <div className="member-tools">
        <Segmented
          aria-label="成员查看范围"
          value={params.get('includeDescendants') === 'false' ? 'false' : 'true'}
          onChange={(value) => updateQuery({ includeDescendants: value, pageNum: '1' })}
          options={[
            { label: '含下级部门', value: 'true' },
            { label: '仅直属', value: 'false' },
          ]}
        />
        <Form
          name="department-members-query"
          form={memberForm}
          layout="inline"
          className="member-query compact-query"
          initialValues={Object.fromEntries(params)}
          onFinish={(values) => {
            // 未展开的条件仍使用原表单值，成员范围继续由工具栏独立控制。
            setMoreMemberFiltersOpen(false);
            setParams(
              queryString({
                ...values,
                username: memberForm.getFieldValue('username'),
                displayStatus: memberForm.getFieldValue('displayStatus'),
                department: selected,
                tab: 'members',
                includeDescendants: params.get('includeDescendants') !== 'false',
                pageNum: 1,
                pageSize: params.get('pageSize') ?? 10,
              }),
            );
          }}
        >
          <Form.Item name="displayName">
            <Input aria-label="姓名" placeholder="姓名" allowClear />
          </Form.Item>
          {/* 窄成员面板优先保留姓名搜索，登录名与状态收入同一浮层。 */}
          <div className="query-tail-actions">
            <Space>
              <MoreFilters
                open={moreMemberFiltersOpen}
                setOpen={setMoreMemberFiltersOpen}
                count={Number(params.has('username')) + Number(params.has('displayStatus'))}
              >
                <Form.Item name="username">
                  <Input aria-label="登录名" placeholder="登录名" allowClear />
                </Form.Item>
                <Form.Item name="displayStatus">
                  <Select
                    aria-label="状态"
                    placeholder="全部状态"
                    allowClear
                    options={Object.entries(statuses).map(([value, label]) => ({ value, label }))}
                  />
                </Form.Item>
              </MoreFilters>
              <Button htmlType="submit">查询</Button>
              <Button onClick={resetMembers}>重置</Button>
            </Space>
          </div>
        </Form>
      </div>
      <RecordTable<Person>
        error={members.error}
        retry={members.refetch}
        filtered={['displayName', 'username', 'displayStatus'].some((key) => params.has(key))}
        onReset={resetMembers}
        rowKey="id"
        loading={members.isFetching}
        dataSource={members.data?.items}
        scroll={{ x: 850, y: '100%' }}
        pagination={{
          current: members.data?.pageNum ?? 1,
          pageSize: members.data?.pageSize ?? 10,
          total: members.data?.total,
          showTotal: (total) => `共 ${total} 条`,
          showSizeChanger: true,
          onChange: (pageNum, pageSize) =>
            updateQuery({ pageNum: String(pageNum), pageSize: String(pageSize) }),
        }}
        columns={[
          {
            title: '人员',
            width: 190,
            render: (_, person) => (
              <div>
                {allowed(session, 'users.view') ? (
                  <Button
                    type="link"
                    className="cell-title"
                    title={person.displayName}
                    onClick={() => setDetailId(person.id)}
                  >
                    {person.displayName}
                  </Button>
                ) : (
                  <span className="cell-text" title={person.displayName}>
                    {person.displayName}
                  </span>
                )}
                <span className="table-secondary" title={person.username}>
                  {person.username}
                </span>
              </div>
            ),
          },
          {
            title: '所属部门',
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
            title: '成员关系',
            width: 150,
            render: (_, person) => {
              const types = new Set(
                person.matchingMemberships?.map(
                  (relation) => relation.isPrimary ?? relation.primary,
                ),
              );
              return types.size > 1 ? '主部门及兼任' : types.has(true) ? '主部门' : '兼任';
            },
          },
          {
            title: '状态',
            dataIndex: 'displayStatus',
            width: 120,
            render: (value) => <Status value={value} />,
          },
          {
            title: '操作',
            className: 'record-actions-cell',
            width: 120,
            fixed: 'right',
            render: (_, person) => memberAction(person),
          },
        ]}
      />
    </>
  );

  // 子部门和初始访问沿用同一页签承载，后续访问能力不伪造固定结果。
  const childContent = (
    <RecordTable<Department>
      error={children.error}
      retry={children.refetch}
      rowKey="id"
      loading={children.isFetching}
      dataSource={children.data?.items}
      scroll={{ x: 760, y: '100%' }}
      pagination={{
        current: children.data?.pageNum ?? 1,
        pageSize: children.data?.pageSize ?? 10,
        total: children.data?.total,
        showTotal: (total) => `共 ${total} 条`,
        onChange: (page, size) =>
          updateQuery({ childPage: String(page), childPageSize: String(size) }),
      }}
      columns={[
        {
          title: '部门',
          render: (_, child) => (
            <Button
              type="link"
              className="cell-title"
              title={child.name}
              onClick={() => selectDepartment(child.id)}
            >
              {child.name}
            </Button>
          ),
        },
        {
          title: '负责人',
          width: 130,
          render: (_, child) => (
            <span className="cell-text" title={child.manager?.displayName}>
              {child.manager?.displayName ?? '—'}
            </span>
          ),
        },
        {
          title: '含下级人数',
          dataIndex: 'totalMemberCount',
          width: 130,
          render: (value) => `${value} 人`,
        },
        {
          title: '状态',
          dataIndex: 'lifecycle',
          width: 110,
          render: (value) => <Status value={value} />,
        },
        {
          title: '操作',
          className: 'record-actions-cell',
          width: 130,
          fixed: 'right',
          render: (_, child) =>
            child.lifecycle !== 'ARCHIVED' && allowed(session, 'departments.edit') ? (
              <Button type="link" onClick={() => editDepartment(child.id)}>
                编辑部门
              </Button>
            ) : (
              <span className="table-action-note">仅供回看</span>
            ),
        },
      ]}
    />
  );

  const tabActions =
    department?.lifecycle === 'ENABLED' &&
    tab === 'members' &&
    allowed(session, 'departments.add-member') ? (
      <Button onClick={() => setAction({ name: 'secondary-members' })}>加入成员</Button>
    ) : department?.lifecycle === 'ENABLED' &&
      tab === 'children' &&
      allowed(session, 'departments.create') ? (
      <Button onClick={() => setEditor({ department: null, parentId: selected })}>
        新增子部门
      </Button>
    ) : null;

  // 原型树搜索只占一行，根部门创建入口不预选父节点。
  return (
    <div className="departments-workspace" aria-label="部门管理">
      <section className="panel department-tree" aria-label="部门组织">
        <div className="tree-title">
          <div>
            <h2>组织架构</h2>
            {fullTree.data && <small>{flattenDepartments(fullTree.data).length} 个部门</small>}
          </div>
          {allowed(session, 'departments.create') && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setEditor({ department: null })}
            >
              新增部门
            </Button>
          )}
        </div>
        <div className="tree-search">
          <Input
            aria-label="搜索部门"
            placeholder="搜索部门"
            prefix={<SearchOutlined />}
            allowClear
            value={treeKeyword}
            onChange={(event) => setTreeKeyword(event.target.value)}
          />
        </div>
        <ReadState
          error={fullTree.error ?? filteredTree.error}
          retry={() => {
            // 重试组织读取，不将连接故障显示成零部门。
            void fullTree.refetch();
            void filteredTree.refetch();
          }}
          loading={fullTree.isPending || filteredTree.isPending}
        >
          {!filteredTree.data?.length ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={treeKeyword ? '没有匹配的部门' : '暂无部门'}
            >
              {treeKeyword && <Button onClick={() => setTreeKeyword('')}>清空条件</Button>}
            </Empty>
          ) : (
            <Tree
              aria-label="部门组织树"
              blockNode
              defaultExpandAll
              key={filteredTree.data?.map((item) => `${item.id}:${item.version}`).join(',')}
              selectedKeys={selected ? [selected] : []}
              treeData={treeNodes(filteredTree.data ?? [])}
              onSelect={(keys) => {
                if (keys[0]) selectDepartment(String(keys[0]));
              }}
            />
          )}
        </ReadState>
      </section>
      <section className="department-content">
        <ReadState
          error={fullTree.error ?? detail.error}
          retry={() => {
            // 重试只恢复读取，不开放尚未取得对象的办理入口。
            void fullTree.refetch();
            if (selected) void detail.refetch();
          }}
          loading={fullTree.isPending || (Boolean(selected) && detail.isPending)}
          empty={!selected}
        >
          <section className="panel department-profile">
            <div className="list-toolbar">
              <div className="detail-heading">
                <h2>{department?.name ?? '请选择部门'}</h2>
                {department && <Status value={department.lifecycle} />}
              </div>
              {actions.length > 0 && (
                <ActionMenuButton
                  placement="bottomRight"
                  menu={{ items: actions, onClick: ({ key }) => chooseAction(key) }}
                >
                  部门操作
                </ActionMenuButton>
              )}
            </div>
            <Descriptions
              size="small"
              layout="vertical"
              column={4}
              colon={false}
              items={[
                { key: 'code', label: '部门编号', children: department?.code ?? '—' },
                { key: 'parent', label: '上级部门', children: department?.parent?.name ?? '—' },
                {
                  key: 'manager',
                  label: '负责人',
                  children: department?.manager?.displayName ?? '—',
                },
                {
                  key: 'count',
                  label: '成员范围',
                  children: department
                    ? `直属 ${department.directMemberCount} 人 / 含下级 ${department.totalMemberCount} 人`
                    : '—',
                },
              ]}
            />
            {department?.note && <p className="muted">{department.note}</p>}
          </section>
          <section className="panel records-panel department-panel">
            <Tabs
              className="department-tabs"
              activeKey={tab}
              onChange={(key) => updateQuery({ tab: key })}
              tabBarExtraContent={tabActions}
              items={[
                { key: 'members', label: '成员', children: memberContent },
                { key: 'children', label: '子部门', children: childContent },
                {
                  key: 'template',
                  label: '初始数据访问',
                  children: department ? (
                    <DepartmentAccessPanel key={department.id} id={department.id} />
                  ) : null,
                },
              ]}
            />
          </section>
        </ReadState>
      </section>
      {editor && (
        <DepartmentEditor
          key={editor.department?.id ?? `new:${editor.parentId ?? 'root'}`}
          department={editor.department}
          parentId={editor.parentId}
          tree={fullTree.data ?? []}
          close={() => setEditor(null)}
          created={selectDepartment}
        />
      )}
      {department && action && (
        <DepartmentAction
          key={`${department.id}:${department.version}:${action.name}`}
          department={department}
          action={action.name}
          member={action.member}
          tree={fullTree.data ?? []}
          close={() => setAction(null)}
        />
      )}
      {detailId && <PersonDetailsDrawer id={detailId} close={() => setDetailId(null)} />}
      {transfer && (
        <UserActionDialog person={transfer} action="transfer" close={() => setTransfer(null)} />
      )}
    </div>
  );
}
